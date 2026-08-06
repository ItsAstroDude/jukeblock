# Native SMTC bridge

`smtc-bridge` is a Rust `cdylib` that exposes the Windows **System Media Transport
Controls** over a flat C ABI, so the JVM side can bind it with JNA and never touch COM.

## Why this exists

SMTC is WinRT. There is no usable pure-Java path to it, and it's the one thing that
makes Jukeblock work with no login, for any player — Spotify free accounts, browser
YouTube, VLC, local files.

## Building

Requires the Rust MSVC toolchain (`rustup default stable-x86_64-pc-windows-msvc`) and
the VS Build Tools C++ workload for the linker.

```
cargo build --release
```

Output: `target/release/smtc_bridge.dll` (~263 KB). `./gradlew buildNative` runs the same
build and copies the result into `src/main/resources/win32-x86-64/`, which is where the
committed copy lives.

`.cargo/config.toml` statically links the CRT so the DLL depends only on OS libraries —
no Visual C++ redistributable required on the user's machine.

## Exports

ABI version **3**. `SmtcBridge.EXPECTED_ABI` on the Kotlin side must match, and refuses
to use the DLL if it doesn't — the usual cause is a stale extraction from an older
Jukeblock.

| Export | Returns |
|---|---|
| `jukeblock_abi_version()` | ABI version, currently `3` |
| `jukeblock_get_sessions()` | owned JSON C string listing every visible player |
| `jukeblock_get_now_playing(const char* source)` | owned JSON C string — free with `jukeblock_free_string` |
| `jukeblock_get_thumbnail(const char* source, usize* out_len)` | owned album-art bytes — free with `jukeblock_free_bytes` |
| `jukeblock_control(const char* source, const char* cmd, i64 arg)` | `1` accepted · `0` declined/no session · `-1` error |
| `jukeblock_get_volume(const char* source)` | `0.0`–`1.0`, or negative when the player owns no audio session |
| `jukeblock_set_volume(const char* source, f32 value)` | `1` applied · `0` no session · `-1` error |

`source` is a session id from `jukeblock_get_sessions`, or `NULL` to follow whatever
Windows considers the current session.

`cmd` is one of `play`, `pause`, `toggle`, `stop`, `next`, `previous`, `seek`,
`shuffle`, `repeat`. `arg` carries milliseconds for `seek`, `0`/`1` for `shuffle`, and
`0`/`1`/`2` (none/track/list) for `repeat`.

Volume is **not** an SMTC concept — those two exports go through WASAPI's per-process
audio sessions instead, matched to the player by process. Enumerating them opens a handle
per process, so the Kotlin side only polls volume while something is showing the slider.

Errors are reported *inside* the now-playing JSON (`{"ok": false, "error": …}`) rather
than as a null pointer, so the Java side has a single code path.

Every export wraps its body in `catch_unwind`. A panic unwinding into the JVM is
undefined behaviour, and a media-player hiccup must never take Minecraft down.

## COM apartments: per-thread, never process-wide

`ensure_apartment` initialises COM with a **thread-local** `CoInitializeEx`. An earlier
version used `CoIncrementMTAUsage`, which is process-wide — and because Jukeblock loads
before Minecraft picks a rendering backend, it silently forced the game off Vulkan onto
OpenGL. A library has no business changing the host process's global state; scope it to
the thread that actually needs it.

## Ownership rules

Anything the DLL returns is owned by the caller and must be handed back to the matching
`free` function. The JNA bindings deliberately type these as `Pointer`, not `String` —
JNA would marshal a `String` and then drop the pointer, leaking on every poll.

## Phase 0 gate result (2026-08-05)

Verified from a plain JVM (`../tools/jna-spike`) on JDK 25:

- DLL load 193 ms, first call 20 ms, **3.0 ms per poll** thereafter
- Album art returned as a 14.7 KB PNG
- Live session read was **browser YouTube**, not Spotify — the universal claim holds
