# Modrinth listing

Everything needed to fill in the project page. The block under **Description** is the body
to paste; the rest are form fields.

---

## Form fields

| Field | Value |
|---|---|
| **Name** | Jukeblock |
| **Summary** | See what you're listening to, without leaving the game. No login, no companion app, works with whatever player you already use. |
| **Categories** | Utility |
| **Client side** | Required |
| **Server side** | Unsupported |
| **Licence** | MIT |
| **Source** | `https://github.com/ItsAstroDude/jukeblock` |
| **Issues** | `https://github.com/ItsAstroDude/jukeblock/issues` |
| **Loader** | Fabric |
| **Game version** | 26.2 |
| **Version number** | Use the jar's own version, e.g. `1.0.0` |
| **Release channel** | Release for `1.0.0`; Beta for anything still `-pre.N` |

Dependencies to declare on the version, not the project:

| Dependency | Type |
|---|---|
| Fabric API | Required |
| Fabric Language Kotlin | Required |
| Mod Menu | Optional |
| Cloth Config | Optional |

## Screenshots to capture

Roughly in the order they should appear on the page:

1. **The panel open, with a good cover.** The square-art shot is the strongest one —
   album art, title, artist, progress, transport all readable.
2. **The now-playing HUD in a corner while playing normally.** Shows the mod's quiet
   mode, which is how most people will actually run it.
3. **The config screen** in Mod Menu, appearance section open.
4. **The drag-to-place screen** with the snap guides visible.
5. Optional: **two players open**, panel showing the source label, to sell the switcher.

Crop out the debug overlay (F3) — coordinates and FPS read as clutter on a store page.

---

## Description

# Jukeblock

**See what you're listening to, without leaving the game.**

Jukeblock reads whatever Windows is already playing and shows it on a panel in Minecraft —
album art, title, artist, progress, and working controls. There is nothing to sign in to,
no companion app to keep running, and no account to link.

It works with Spotify — **including free accounts** — browser YouTube, Windows Media Player,
and anything else that reports itself to Windows.

## Why this one

Most mods in this space ask you to register your own Spotify developer application, or run a
second program alongside the game, or hold a Premium subscription. Jukeblock asks for none of
that, because it doesn't talk to any music service at all. It reads the same system media
information that Windows' own volume popup shows.

That's also why it isn't limited to one service. If the player can appear in that popup,
Jukeblock can show it.

## What you get

- **A full-height panel** — press `Z`. Album art, title, artist, a progress bar you can drag
  to seek, transport controls, shuffle and repeat, and a volume slider for that player alone
  rather than the whole system.
- **A now-playing bar** — a compact readout in a screen corner, or dragged anywhere you like.
  Have it appear for a few seconds when the track changes, or stay up while music plays.
- **Multiple players** — click the source name to cycle through everything Windows can see,
  and lock onto one so a background browser tab can't hijack the panel mid-song.
- **Colour that follows the cover** — the highlight is pulled from the current album art and
  adjusted so it stays readable against whatever panel colour you chose.
- **Themes** — six built in, and it reads your own from a folder.
- **`/np`** — check what's playing. `/np share` says it in chat. It's a client command, so it
  works on servers that have never heard of this mod.
- **Keybinds for skip and pause** that work without opening anything, for when a track turns
  in the middle of a fight. Unbound by default so it doesn't claim keys you were using.

Controls a player doesn't support are greyed out rather than hidden, so a button never lies
about what it will do.

## Before you install

> **Windows only.** The whole no-login approach rests on the Windows System Media Transport
> Controls, which have no equivalent on Linux or macOS. On those systems the mod loads,
> reports that there's no media source, and otherwise stays out of the way.

> **A player has to report itself.** Windows only knows about apps that opt in. Most do —
> **VLC doesn't**, so Jukeblock can't see it, and no update here can change that.
>
> **The test:** open Windows' own media popup, above the volume slider. If your player shows
> up there, Jukeblock will show it too. If it doesn't, it can't.

Client-side only — it never needs to be installed on a server.

## Requirements

- Minecraft **26.2**, Fabric Loader **0.19.3+**, Java **25**
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) **1.13.13+**
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) and
  [Cloth Config](https://modrinth.com/mod/cloth-config) for the settings screen. Without them
  the mod still runs; you'd edit `config/jukeblock.json` by hand.

## Questions

**Does this need Spotify Premium?** No. It doesn't use Spotify's API at all, so free accounts
work exactly the same as paid ones.

**Do I have to register a developer application?** No.

**Does it work in multiplayer?** Yes — it's entirely client-side and never talks to the
server. `/np share` sends a normal chat message, so other players see the text whether or not
they have the mod.

**Will it slow the game down?** It shouldn't. The system is polled on a background thread and
the game's render thread only ever reads the latest result. Polling slows down on its own when
nothing is on screen.

**Linux or macOS?** Not possible in this form — there's no equivalent system API to read.

**Why can't it see my player?** Check Windows' media popup above the volume slider. Jukeblock
can only see players that appear there.

## Source

[github.com/ItsAstroDude/jukeblock](https://github.com/ItsAstroDude/jukeblock) — MIT.
