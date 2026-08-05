//! Jukeblock — flat C ABI bridge over the Windows System Media Transport Controls.
//!
//! Exposes to the JVM side, all through a plain C ABI so JNA can bind them without any
//! C++ name mangling or COM knowledge on the Java side:
//!
//!   * `jukeblock_get_sessions()`     -> JSON array of every media session on the system
//!   * `jukeblock_get_now_playing()`  -> JSON describing one session in full
//!   * `jukeblock_get_thumbnail()`    -> raw album-art bytes (PNG/JPEG as the app supplied them)
//!   * `jukeblock_control()`          -> play/pause/next/prev/seek/shuffle/repeat
//!
//! Every session-facing export takes a `source` argument: pass NULL for "whatever the
//! system considers current", or a `sourceAppId` from `jukeblock_get_sessions` to pin
//! the call to one specific player.
//!
//! Every export catches unwinds at the boundary: a panic crossing into the JVM is
//! undefined behaviour, and taking Minecraft down over a media-player hiccup is not
//! an acceptable failure mode.

use std::ffi::{c_char, c_int, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

use serde_json::{json, Value};
use windows::core::HSTRING;
use windows::Foundation::TimeSpan;
use windows::Media::Control::{
    GlobalSystemMediaTransportControlsSession as Session,
    GlobalSystemMediaTransportControlsSessionManager as SessionManager,
    GlobalSystemMediaTransportControlsSessionPlaybackStatus as PlaybackStatus,
};
use windows::Media::MediaPlaybackAutoRepeatMode as RepeatMode;
use windows::Storage::Streams::DataReader;
use windows::core::Interface;
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::Media::Audio::{
    eConsole, eRender, IAudioSessionControl2, IAudioSessionManager2, IMMDeviceEnumerator,
    ISimpleAudioVolume, MMDeviceEnumerator,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED,
};
use windows::Win32::System::Threading::{
    OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32,
    PROCESS_QUERY_LIMITED_INFORMATION,
};

/// Bumped whenever the exported signatures change, so the Java side can refuse a stale
/// DLL rather than misread it.
const ABI_VERSION: c_int = 3;

/// SMTC is WinRT, so the calling thread needs to live in a COM apartment.
///
/// ⚠️ This is deliberately **per-thread**, and must stay that way.
///
/// The first version called `CoIncrementMTAUsage`, which creates a *process-wide*
/// implicit MTA. That broke the host: Jukeblock initialises the bridge during mod
/// setup, several seconds before Minecraft chooses its graphics backend, and once the
/// process is MTA the Vulkan/DXGI init path can no longer enter a single-threaded
/// apartment — it gets `RPC_E_CHANGED_MODE` and silently falls back to OpenGL.
/// Confirmed against Astro's logs: five runs with the mod on OpenGL, one without it on
/// Vulkan, same NVIDIA driver throughout.
///
/// Every call into this library arrives on the single `Jukeblock-Media` thread (see
/// MediaService), so initialising that one thread's apartment is sufficient and leaves
/// the rest of the process untouched.
fn ensure_apartment() {
    thread_local! {
        static APARTMENT: () = unsafe {
            // Result ignored on purpose: `S_FALSE` means this thread was already
            // initialised, and `RPC_E_CHANGED_MODE` means someone else established an
            // apartment here that we must not fight. In both cases the WinRT calls that
            // follow report their own failures, which is where a real error belongs.
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        };
    }
    APARTMENT.with(|_| {});
}

/// 100-nanosecond ticks -> milliseconds.
fn ticks_to_ms(t: i64) -> i64 {
    t / 10_000
}

/// WinRT `DateTime` is 100ns ticks since 1601-01-01; Unix epoch is 11644473600s later.
fn winrt_datetime_to_unix_ms(ticks: i64) -> i64 {
    ticks_to_ms(ticks) - 11_644_473_600_000
}

fn hstring_to_string(h: HSTRING) -> String {
    h.to_string_lossy()
}

/// Reads an optional HSTRING getter, treating "player didn't fill this in" as blank.
fn opt_str(r: windows::core::Result<HSTRING>) -> String {
    r.map(hstring_to_string).unwrap_or_default()
}

fn playback_status_str(s: PlaybackStatus) -> &'static str {
    match s {
        PlaybackStatus::Closed => "CLOSED",
        PlaybackStatus::Opened => "OPENED",
        PlaybackStatus::Changing => "CHANGING",
        PlaybackStatus::Stopped => "STOPPED",
        PlaybackStatus::Playing => "PLAYING",
        PlaybackStatus::Paused => "PAUSED",
        _ => "UNKNOWN",
    }
}

fn repeat_mode_str(m: RepeatMode) -> &'static str {
    match m {
        RepeatMode::None => "NONE",
        RepeatMode::Track => "TRACK",
        RepeatMode::List => "LIST",
        _ => "UNKNOWN",
    }
}

fn manager() -> windows::core::Result<SessionManager> {
    ensure_apartment();
    SessionManager::RequestAsync()?.join()
}

/// Resolves the session a call should act on.
///
/// `None` means "the system's current session" — the one SMTC itself considers focused.
/// `Some(id)` pins the call to a specific player by `SourceAppUserModelId`, so a user
/// who picked Spotify in the UI keeps talking to Spotify even while a browser tab
/// steals the system's idea of "current".
fn resolve_session(target: Option<&str>) -> windows::core::Result<Option<Session>> {
    let manager = manager()?;

    let Some(id) = target else {
        // No active session is a normal state (nothing is playing), not an error.
        return Ok(manager.GetCurrentSession().ok());
    };

    let sessions = manager.GetSessions()?;
    for session in &sessions {
        if opt_str(session.SourceAppUserModelId()) == id {
            return Ok(Some(session));
        }
    }
    // The requested player went away; the caller decides whether to fall back.
    Ok(None)
}

fn describe_session(session: &Session) -> windows::core::Result<Value> {
    let props = session.TryGetMediaPropertiesAsync()?.join()?;
    let timeline = session.GetTimelineProperties()?;
    let info = session.GetPlaybackInfo()?;

    let position: TimeSpan = timeline.Position().unwrap_or_default();
    let end: TimeSpan = timeline.EndTime().unwrap_or_default();
    let start: TimeSpan = timeline.StartTime().unwrap_or_default();

    // Shuffle/repeat are IReference<T> — genuinely absent for players that don't support them.
    let shuffle: Option<bool> = info.IsShuffleActive().ok().and_then(|r| r.Value().ok());
    let repeat: Option<&'static str> = info
        .AutoRepeatMode()
        .ok()
        .and_then(|r| r.Value().ok())
        .map(repeat_mode_str);

    let controls = info.Controls()?;
    let cap = |r: windows::core::Result<bool>| r.unwrap_or(false);

    Ok(json!({
        "sourceAppId": opt_str(session.SourceAppUserModelId()),
        "title": opt_str(props.Title()),
        "artist": opt_str(props.Artist()),
        "album": opt_str(props.AlbumTitle()),
        "albumArtist": opt_str(props.AlbumArtist()),
        "trackNumber": props.TrackNumber().unwrap_or(0),
        "trackCount": props.AlbumTrackCount().unwrap_or(0),
        "status": playback_status_str(info.PlaybackStatus().unwrap_or(PlaybackStatus::Closed)),
        "positionMs": ticks_to_ms(position.Duration),
        "startMs": ticks_to_ms(start.Duration),
        "durationMs": ticks_to_ms(end.Duration),
        "lastUpdatedUnixMs": timeline
            .LastUpdatedTime()
            .map(|d| winrt_datetime_to_unix_ms(d.UniversalTime))
            .unwrap_or(0),
        "shuffle": shuffle,
        "repeat": repeat,
        "hasThumbnail": props.Thumbnail().is_ok(),
        "caps": {
            "play": cap(controls.IsPlayEnabled()),
            "pause": cap(controls.IsPauseEnabled()),
            "next": cap(controls.IsNextEnabled()),
            "previous": cap(controls.IsPreviousEnabled()),
            "seek": cap(controls.IsPlaybackPositionEnabled()),
            "shuffle": cap(controls.IsShuffleEnabled()),
            "repeat": cap(controls.IsRepeatEnabled()),
            "stop": cap(controls.IsStopEnabled()),
        }
    }))
}

fn build_now_playing(target: Option<&str>) -> windows::core::Result<Value> {
    let Some(session) = resolve_session(target)? else {
        return Ok(json!({ "ok": true, "active": false }));
    };

    let mut v = describe_session(&session)?;
    let obj = v.as_object_mut().expect("describe_session returns an object");
    obj.insert("ok".into(), json!(true));
    obj.insert("active".into(), json!(true));
    Ok(v)
}

/// Lightweight listing of every session, for a source picker.
///
/// Deliberately thinner than [`build_now_playing`]: enough to name and identify each
/// player, without the timeline and capability round-trips for sessions the user isn't
/// looking at.
fn build_sessions() -> windows::core::Result<Value> {
    let manager = manager()?;
    let current_id = manager
        .GetCurrentSession()
        .ok()
        .map(|s| opt_str(s.SourceAppUserModelId()));

    let mut out = Vec::new();
    for session in &manager.GetSessions()? {
        let id = opt_str(session.SourceAppUserModelId());
        // One misbehaving player must not blank the whole list.
        let (title, artist) = match session.TryGetMediaPropertiesAsync().and_then(|op| op.join()) {
            Ok(p) => (opt_str(p.Title()), opt_str(p.Artist())),
            Err(_) => (String::new(), String::new()),
        };
        let status = session
            .GetPlaybackInfo()
            .and_then(|i| i.PlaybackStatus())
            .map(playback_status_str)
            .unwrap_or("UNKNOWN");

        out.push(json!({
            "isCurrent": current_id.as_deref() == Some(id.as_str()),
            "sourceAppId": id,
            "title": title,
            "artist": artist,
            "status": status,
        }));
    }

    Ok(json!({ "ok": true, "sessions": out }))
}

fn json_to_c_string(v: &Value) -> *mut c_char {
    match CString::new(v.to_string()) {
        Ok(c) => c.into_raw(),
        // Only reachable if the JSON contained an interior NUL, which serde_json won't emit.
        Err(_) => std::ptr::null_mut(),
    }
}

/// Reads an optional C string argument. `None` for NULL, `Err` for invalid UTF-8.
///
/// # Safety
/// `p` must be NULL or a valid NUL-terminated C string.
unsafe fn opt_c_str(p: *const c_char) -> Result<Option<String>, ()> {
    if p.is_null() {
        return Ok(None);
    }
    match CStr::from_ptr(p).to_str() {
        Ok(s) => Ok(Some(s.to_owned())),
        Err(_) => Err(()),
    }
}

/// Runs a fallible JSON producer, turning both WinRT errors and panics into an
/// `{"ok": false, "error": …}` payload so the Java side has one code path.
fn json_export(f: impl FnOnce() -> windows::core::Result<Value>) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| match f() {
        Ok(v) => v,
        Err(e) => json!({ "ok": false, "error": e.message(), "hresult": e.code().0 }),
    }));

    match result {
        Ok(v) => json_to_c_string(&v),
        Err(_) => json_to_c_string(&json!({ "ok": false, "error": "panic in native bridge" })),
    }
}

/// Returns a NUL-terminated JSON string describing one SMTC session.
///
/// Pass NULL for `source` to describe the system's current session, or a `sourceAppId`
/// to pin the read to a specific player.
///
/// Never returns NULL except on allocation failure. The caller owns the returned
/// pointer and must hand it back to [`jukeblock_free_string`].
///
/// # Safety
/// `source` must be NULL or a valid NUL-terminated C string.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_get_now_playing(source: *const c_char) -> *mut c_char {
    let Ok(target) = opt_c_str(source) else {
        return json_to_c_string(&json!({ "ok": false, "error": "source is not valid UTF-8" }));
    };
    json_export(|| build_now_playing(target.as_deref()))
}

/// Returns a NUL-terminated JSON string listing every media session on the system.
///
/// Shape: `{"ok": true, "sessions": [{"sourceAppId", "title", "artist", "status",
/// "isCurrent"}, …]}`. The caller owns the returned pointer and must hand it back to
/// [`jukeblock_free_string`].
#[no_mangle]
pub extern "C" fn jukeblock_get_sessions() -> *mut c_char {
    json_export(build_sessions)
}

/// Frees a string handed out by this library.
///
/// # Safety
/// `ptr` must be a pointer this library returned, and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_free_string(ptr: *mut c_char) {
    if ptr.is_null() {
        return;
    }
    let _ = CString::from_raw(ptr);
}

fn read_thumbnail(target: Option<&str>) -> windows::core::Result<Option<Vec<u8>>> {
    let Some(session) = resolve_session(target)? else {
        return Ok(None);
    };
    let props = session.TryGetMediaPropertiesAsync()?.join()?;
    let Ok(reference) = props.Thumbnail() else {
        return Ok(None); // player supplied no art
    };

    let stream = reference.OpenReadAsync()?.join()?;
    let size = stream.Size()? as u32;
    if size == 0 {
        return Ok(None);
    }

    let reader = DataReader::CreateDataReader(&stream)?;
    reader.LoadAsync(size)?.join()?;
    let mut buf = vec![0u8; size as usize];
    reader.ReadBytes(&mut buf)?;
    Ok(Some(buf))
}

/// Writes the album-art bytes for a session's current track into a fresh buffer.
///
/// Returns the pointer and writes the length to `out_len`; returns NULL (and length 0)
/// when there is no session or the player supplied no art. The bytes are whatever the
/// player handed SMTC — normally PNG or JPEG, so the Java side should sniff, not assume.
///
/// The caller owns the buffer and must free it with [`jukeblock_free_bytes`], passing
/// back the same length.
///
/// # Safety
/// `source` must be NULL or a valid NUL-terminated C string, and `out_len` must be a
/// valid, writable pointer to a `usize`.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_get_thumbnail(
    source: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    if out_len.is_null() {
        return std::ptr::null_mut();
    }
    *out_len = 0;

    let Ok(target) = opt_c_str(source) else {
        return std::ptr::null_mut();
    };

    let result = catch_unwind(AssertUnwindSafe(|| read_thumbnail(target.as_deref())));
    let bytes = match result {
        Ok(Ok(Some(b))) => b,
        _ => return std::ptr::null_mut(),
    };

    let mut boxed = bytes.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    *out_len = boxed.len();
    std::mem::forget(boxed);
    ptr
}

/// Frees a buffer handed out by [`jukeblock_get_thumbnail`].
///
/// # Safety
/// `ptr`/`len` must be exactly what this library returned, and unused afterwards.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_free_bytes(ptr: *mut u8, len: usize) {
    if ptr.is_null() || len == 0 {
        return;
    }
    drop(Vec::from_raw_parts(ptr, len, len));
}

fn run_control(target: Option<&str>, cmd: &str, arg: i64) -> windows::core::Result<bool> {
    let Some(session) = resolve_session(target)? else {
        return Ok(false);
    };

    // SMTC reports success as a bool: the player received the request and accepted it.
    let ok = match cmd {
        "play" => session.TryPlayAsync()?.join()?,
        "pause" => session.TryPauseAsync()?.join()?,
        "toggle" => session.TryTogglePlayPauseAsync()?.join()?,
        "stop" => session.TryStopAsync()?.join()?,
        "next" => session.TrySkipNextAsync()?.join()?,
        "previous" => session.TrySkipPreviousAsync()?.join()?,
        // arg is milliseconds; SMTC wants 100ns ticks.
        "seek" => session
            .TryChangePlaybackPositionAsync(arg.saturating_mul(10_000))?
            .join()?,
        "shuffle" => session.TryChangeShuffleActiveAsync(arg != 0)?.join()?,
        "repeat" => {
            let mode = match arg {
                1 => RepeatMode::Track,
                2 => RepeatMode::List,
                _ => RepeatMode::None,
            };
            session.TryChangeAutoRepeatModeAsync(mode)?.join()?
        }
        _ => false,
    };
    Ok(ok)
}

/// Sends a transport command to a session.
///
/// `source` is NULL for the current session or a `sourceAppId` to pin it. `cmd` is one
/// of `play`, `pause`, `toggle`, `stop`, `next`, `previous`, `seek`, `shuffle`,
/// `repeat`. `arg` carries the payload where one is needed: milliseconds for `seek`,
/// 0/1 for `shuffle`, and 0/1/2 (none/track/list) for `repeat`.
///
/// Returns 1 if the player accepted the command, 0 if it declined or there was no
/// session, and -1 on an internal error.
///
/// # Safety
/// `cmd` must be a valid NUL-terminated C string; `source` must be NULL or one.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_control(
    source: *const c_char,
    cmd: *const c_char,
    arg: i64,
) -> c_int {
    if cmd.is_null() {
        return -1;
    }
    let (Ok(target), Ok(Some(cmd))) = (opt_c_str(source), opt_c_str(cmd)) else {
        return -1;
    };

    match catch_unwind(AssertUnwindSafe(|| {
        run_control(target.as_deref(), &cmd, arg)
    })) {
        Ok(Ok(true)) => 1,
        Ok(Ok(false)) => 0,
        _ => -1,
    }
}

// --- volume ---------------------------------------------------------------
//
// SMTC has no concept of volume, so this is a separate Windows API entirely:
// WASAPI audio sessions. Per-application volume rather than the system master —
// a now-playing panel that turned Minecraft's own audio down with the music
// would be worse than having no slider at all.

/// Reduces an identifier to something comparable: `Spotify.exe` and
/// `Helium.NXYZFKH5N5QLK4VHZYCROOE6P4` both become their leading app name.
fn normalise_app_name(raw: &str) -> String {
    raw.rsplit(['\\', '/'])
        .next()
        .unwrap_or(raw)
        .split(['!', '_', '.'])
        .next()
        .unwrap_or(raw)
        .to_ascii_lowercase()
}

fn process_name(pid: u32) -> Option<String> {
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buf = [0u16; 260];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(
            handle,
            PROCESS_NAME_WIN32,
            windows::core::PWSTR(buf.as_mut_ptr()),
            &mut len,
        );
        let _ = CloseHandle(handle);
        if ok.is_err() {
            return None;
        }
        Some(String::from_utf16_lossy(&buf[..len as usize]))
    }
}

/// Applies `action` to every audio session belonging to the target player.
///
/// Returns the first session's volume (0.0-1.0), or `None` when the player owns no
/// audio session — which is normal for a paused app that has released its stream.
fn with_audio_sessions(target: Option<&str>, set: Option<f32>) -> windows::core::Result<Option<f32>> {
    ensure_apartment();

    let enumerator: IMMDeviceEnumerator =
        unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)? };
    let device = unsafe { enumerator.GetDefaultAudioEndpoint(eRender, eConsole)? };
    let manager: IAudioSessionManager2 = unsafe { device.Activate(CLSCTX_ALL, None)? };
    let sessions = unsafe { manager.GetSessionEnumerator()? };
    let count = unsafe { sessions.GetCount()? };

    let wanted = target.map(normalise_app_name);
    let mut result: Option<f32> = None;

    for index in 0..count {
        let control = unsafe { sessions.GetSession(index)? };
        let control2: IAudioSessionControl2 = control.cast()?;
        let pid = unsafe { control2.GetProcessId()? };
        if pid == 0 {
            continue; // the system sounds session
        }

        if let Some(want) = &wanted {
            let name = match process_name(pid) {
                Some(n) => normalise_app_name(&n),
                None => continue,
            };
            // Browsers in particular run audio in a differently-named child process,
            // so accept a prefix match either way rather than demanding equality.
            if !(name == *want || name.starts_with(want.as_str()) || want.starts_with(&name)) {
                continue;
            }
        }

        let volume: ISimpleAudioVolume = control2.cast()?;
        match set {
            // A player can own several sessions at once; setting only the first would
            // leave the others at the old level.
            Some(v) => {
                unsafe { volume.SetMasterVolume(v, std::ptr::null()) }?;
                result = Some(v);
            }
            None => {
                if result.is_none() {
                    result = Some(unsafe { volume.GetMasterVolume()? });
                }
            }
        }
    }

    Ok(result)
}

/// Returns the player's volume as 0.0-1.0, or a negative value when it has no audio
/// session (paused apps often release theirs) or the lookup failed.
///
/// # Safety
/// `source` must be NULL or a valid NUL-terminated C string.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_get_volume(source: *const c_char) -> f32 {
    let Ok(target) = opt_c_str(source) else {
        return -1.0;
    };
    match catch_unwind(AssertUnwindSafe(|| with_audio_sessions(target.as_deref(), None))) {
        Ok(Ok(Some(v))) => v,
        _ => -1.0,
    }
}

/// Sets the player's volume. `value` is clamped to 0.0-1.0.
///
/// Returns 1 on success, 0 if the player owns no audio session, -1 on error.
///
/// # Safety
/// `source` must be NULL or a valid NUL-terminated C string.
#[no_mangle]
pub unsafe extern "C" fn jukeblock_set_volume(source: *const c_char, value: f32) -> c_int {
    let Ok(target) = opt_c_str(source) else {
        return -1;
    };
    let clamped = value.clamp(0.0, 1.0);
    match catch_unwind(AssertUnwindSafe(|| {
        with_audio_sessions(target.as_deref(), Some(clamped))
    })) {
        Ok(Ok(Some(_))) => 1,
        Ok(Ok(None)) => 0,
        _ => -1,
    }
}

/// ABI version, so the Java side can refuse a stale DLL rather than misread it.
#[no_mangle]
pub extern "C" fn jukeblock_abi_version() -> c_int {
    ABI_VERSION
}
