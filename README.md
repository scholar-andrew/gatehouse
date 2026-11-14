# October Journey

## Download

| Operating system | Install | Uninstall |
| --- | --- | --- |
| **Windows** 10 (64-bit) | [**OctoberJourneySetup.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneySetup.exe) | [**OctoberJourneyUninstall.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneyUninstall.exe) |
| **Linux**, including **Steam Deck** (SteamOS 3.9) | [**october-journey-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-setup.sh) | [**october-journey-uninstall.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-uninstall.sh) |

One download sets up everything: Prism Launcher, the modpack (it updates itself each time you play), the server in your
server list, and a Steam shortcut with its own art and a Steam Deck controller layout.

- **Windows:** run the exe. If Windows says "Windows protected your PC", click **More info**, then **Run anyway** (the
  file isn't code-signed).
- **Linux, including Steam Deck:** in a terminal (on a Steam Deck: Desktop Mode, then Konsole), go to your Downloads
  folder and run `bash october-journey-setup.sh`.

Step-by-step instructions and requirements (Minecraft account, Tailscale access, whitelist): [INSTALL.md](INSTALL.md).
What it was tested on, with hardware: [INSTALL.md: Tested on](INSTALL.md#tested-on).
All versions: [Releases](https://github.com/scholar-andrew/october-journey-pack/releases).

### What it installs and runs
In short (the full list, with every file and folder, is in
[INSTALL.md: What setup does to your computer](INSTALL.md#what-setup-does-to-your-computer)):

- **Prism Launcher**, if you don't have it: the official installer from Prism's GitHub releases on Windows (checked
  against a recorded SHA-256), or the Flathub app on Linux.
- **A Prism instance**, "October Journey 26.3 (Fabric)", with a small update script that Prism runs before every launch.
- **The October Journey server** in the instance's server list.
- **A Steam shortcut** with this pack's library art and a Steam Deck controller layout. Setup closes or asks you to close
  Steam for this, and backs up the files it changes.
- **On Linux only**, permission for Prism's Flatpak sandbox to write two Steam folders (library art and controller
  layouts).

No administrator rights, no services, nothing that starts with your computer. Every time you play, the update script
downloads changed mods (from Modrinth, each checked against a recorded hash) and keeps the art, layout and server entry
current: see [What runs each time you play](INSTALL.md#what-runs-each-time-you-play) and
[What it connects to](INSTALL.md#what-it-connects-to). The uninstallers undo all of it:
[Removing it](INSTALL.md#removing-it).

---

## What's in this repo

| Where | What |
| --- | --- |
| [INSTALL.md](INSTALL.md) | For players: installing, what it was tested on, what setup changes, what runs on each launch, network use, removal. |
| [docs/mods.md](docs/mods.md) | Every mod in the pack and where it comes from (generated). |
| [docs/how-it-works.md](docs/how-it-works.md) | For the maintainer: how the pack is built, hosted and synced, the Steam keyboard setup, and how to make common changes. |
| [mods-src/](mods-src/README.md) | Source of the pack's own mods: Chat Lift, Controller Handoff Fix, and the REI brewing patch. |
| `source/` | The pack definition (`mods.json`) and files shipped as they are. |
| `dist/` | The built pack (packwiz format) that devices download. |
| `clients/` | The update script each device runs before every launch, and the files setup installs with it. |
| `installer/` | The installers and uninstallers for Windows and Linux, their settings (`config.json`), the Steam art and the controller layout. |
| `tools/` | Building the pack and mods, serving the pack over Tailscale, making the art. |
