# Jukeblock

**Universal now-playing panel for Minecraft.** Works the second you install it, with no
login, for whatever you're actually listening to.

Spotify (free accounts included), browser YouTube, VLC, local files, Apple Music,
anything else that reports itself to Windows — Jukeblock reads them all through the same
system API the volume flyout uses. No companion app, no developer account, no Premium,
no OAuth.

> **Windows only.** The whole zero-setup story rests on the Windows System Media
> Transport Controls, which have no Linux or macOS equivalent. On other platforms the mod
> loads and stays quiet: the panel says there's no media source, and nothing else is
> affected.

---

## What it does

- **Panel** (`Z` by default) — a side rail with album art, title, artist, a scrubbable
  progress bar, transport controls, shuffle/repeat, and a volume slider for that player
  alone.
- **Now-playing HUD** — a compact bar in a screen corner, or dragged anywhere you like.
  Set it to appear only on a track change, or to stay up while something plays.
- **Multiple players** — click the source label to cycle between everything Windows can
  see, or pin one so a background browser tab can't hijack the panel mid-song.
- **Colour from the cover** — the accent is pulled from the current album art and pushed
  back into a legible range, so it never disappears against your panel colour.
- **Themes** — six built in; drop a `.json` into `config/jukeblock/themes/` for your own.
- **`/np`** — see what's playing. `/np share` says it in chat. A client command, so it
  works on servers that have never heard of this mod.
- **Global transport keybinds** — skip a track without opening anything. Unbound by
  default.

Controls the player can't do are greyed out rather than hidden, so the panel never lies
about what a button will accomplish.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Loader | Fabric ≥ 0.19.3 |
| Java | 25 |
| Required | [Fabric API](https://modrinth.com/mod/fabric-api), [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) ≥ 1.13.13 |
| Optional | [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) for the settings screen |
| OS | Windows |

Client-side only. Never needs to be on the server.

## Configuration

Everything is in Mod Menu → Jukeblock, and written to `config/jukeblock.json` if you'd
rather edit it by hand. Panel colour, opacity and backdrop dim; rail width; accent
source; and a full set of the same for the HUD, behind a **Follow main theme** toggle —
the HUD sits over the world rather than a dimmed backdrop, so it usually wants to be more
solid than the panel.

To place the HUD freely, set its corner to `FREE` and use **Move freely…** in the config
screen, `/jukeblock hud`, or the *Move now-playing HUD* keybind. Drag it, scroll to
resize, `ESC` when it looks right.

## How it works

Jukeblock ships a small Rust `cdylib` that exposes the Windows System Media Transport
Controls over a flat C ABI, bound from Kotlin with JNA. Everything native happens on one
dedicated background thread — the render thread only ever reads a snapshot and never
blocks.

Details, including the ABI and the ownership rules, are in
[`native/README.md`](native/README.md).

## Building

```bash
./gradlew build
```

The compiled bridge is committed under `src/main/resources/win32-x86-64/`, so a plain
build needs no Rust toolchain. To rebuild it after changing anything under `native/`:

```bash
./gradlew buildNative
```

That needs the Rust MSVC toolchain and the VS Build Tools C++ workload.

There's also `./gradlew smoke`, which exercises the media layer from a plain JVM without
launching Minecraft — much faster than a game launch when you're changing the bridge.

## Third-party

- [JNA](https://github.com/java-native-access/jna) (Apache 2.0 / LGPL 2.1), bundled.
  Only the Windows x64 native is kept; the other platforms' copies are stripped, since
  the mod can't run there anyway.
- The mod icon is rendered from Minecraft's own jukebox textures.

## Licence

[MIT](LICENSE).
