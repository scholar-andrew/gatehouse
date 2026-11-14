# October Journey

## Download the installer

| Device | Download |
| --- | --- |
| **Windows** | [**OctoberJourneySetup.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneySetup.exe) |
| **Steam Deck / Linux** | [**october-journey-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-setup.sh) |

One download sets up everything: Prism Launcher, the modpack (it updates itself each time you play), the server in your
server list, and a Steam shortcut with its own art and Steam Deck controller layout.

- **Windows:** run the exe. If Windows says "Windows protected your PC", click **More info**, then **Run anyway** (the
  file isn't code-signed).
- **Steam Deck / Linux:** in Desktop Mode, open Konsole in your Downloads folder and run `bash october-journey-setup.sh`.

Step-by-step instructions and requirements (Minecraft account, Tailscale access, whitelist): [INSTALL.md](INSTALL.md).
All versions: [Releases](https://github.com/scholar-andrew/october-journey-pack/releases).

---

## What's in this repo

One modpack for every device that plays on the October Journey server, in packwiz format.

- `dist/main/pack.toml`: the pack every device uses.
- `dist/deck/pack.toml`: the Steam Deck variant (currently the same as main; kept so Deck-only mods can return).
- `source/mods.json`: the list it is built from; `source/files/`: configs and jars that are not on Modrinth.
- `tools/build_pack.py`: rebuilds `dist/` from `source/`, including the Steam art and controller layout.
- `tools/serve.ps1`: serves `dist/` over Tailscale at http://100.91.134.102:8467/ (scheduled task "Minecraft pack server").
- `clients/`: what each device's Prism instance runs before launch. It tries the owner's PC over Tailscale first, then
  GitHub, and also keeps the Steam art and controller layout up to date.
- `installer/`: the Windows and Linux installers, their settings (`installer/config.json`) and the art.
