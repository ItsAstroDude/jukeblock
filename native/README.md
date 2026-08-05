# Native SMTC bridge

`smtc-bridge` is a Rust `cdylib` that exposes the Windows **System Media Transport
Controls** over a flat C ABI, so the JVM side can bind it with JNA and never touch COM.

## Why this exists

SMTC is WinRT. There is no usable pure-Java path to it, and it's the one thing that
makes Jukeblock work with no login, for any player — Spotify free accounts, browser
YouTube, VLC, local files. See `../PLAN.md` §2.

## Building

Requires the Rust MSVC toolchain (`rustup default stable-x86_64-pc-windows-msvc`) and
the VS Build Tools C++ workload for the linker.

```
cargo build --release
```

Output: `target/release/smtc_bridge.dll` (~242 KB).

`.cargo/config.toml` statically links the CRT so the DLL depends only on OS libraries —
no Visual C++ redistributable required on the user's machine.

## Exports

| Export | Returns |
|---|---|
| `jukeblock_abi_version()` | ABI version, currently `1` |
| `jukeblock_get_now_playing()` | owned JSON C string — free with `jukeblock_free_string` |
| `jukeblock_get_thumbnail(usize* out_len)` | owned album-art bytes — free with `jukeblock_free_bytes` |
| `jukeblock_control(const char* cmd, i64 arg)` | `1` accepted · `0` declined/no session · `-1` error |

`cmd` is one of `play`, `pause`, `toggle`, `stop`, `next`, `previous`, `seek`,
`shuffle`, `repeat`. `arg` carries milliseconds for `seek`, `0`/`1` for `shuffle`, and
`0`/`1`/`2` (none/track/list) for `repeat`.

Errors are reported *inside* the now-playing JSON (`{"ok": false, "error": …}`) rather
than as a null pointer, so the Java side has a single code path.

Every export wraps its body in `catch_unwind`. A panic unwinding into the JVM is
undefined behaviour, and a media-player hiccup must never take Minecraft down.

## Ownership rules

Anything the DLL returns is owned by the caller and must be handed back to the matching
`free` function. The JNA bindings deliberately type these as `Pointer`, not `String` —
JNA would marshal a `String` and then drop the pointer, leaking on every poll.

## Phase 0 gate result (2026-08-05)

Verified from a plain JVM (`../tools/jna-spike`) on JDK 25:

- DLL load 193 ms, first call 20 ms, **3.0 ms per poll** thereafter
- Album art returned as a 14.7 KB PNG
- Live session read was **browser YouTube**, not Spotify — the universal claim holds
