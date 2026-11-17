# Gatehouse

Gatehouse gets a group of friends onto the same Minecraft server and keeps them there. It is one program with two
halves, and a device sets up the half it needs:

- **Play**, the Gatehouse launcher, on each player's device. It lists the servers you can join, installs and updates
  each server's mods, shows what changed on your device and on the server, and has the controls for the server that
  its owner has allowed your device. Then it starts the game through [Prism Launcher](https://prismlauncher.org/).
- **Host**, Gatekeeper, on the machine that runs the servers. It lets the launchers its owner allows see, start and
  stop the servers, shows who asked for what, and has an Access Manager for deciding who may do which, per device or
  per player, including each server's whitelist. It works with [MC Server Soft](https://www.mcserversoft.com/) on
  Windows, or runs the servers itself (always on Linux and SteamOS, where there is no MCSS).

A host who also plays sets up both on one machine: the launcher there links itself to its Gatekeeper, is allowed
everything, and its player is whitelisted, with nothing to pair.

Launchers and Gatekeepers on different devices talk over the owner's private [Tailscale](https://tailscale.com/)
network, so nothing is opened to the internet. GitHub is only a fallback for downloads.

## Start here

**[How to use Gatehouse](docs/guide.md)**: step-by-step instructions for players and for hosts, from
nothing installed to playing, plus updates, removing them, what works on which system, and what to do when something
doesn't work. The program carries the same steps inside it as setup guides that check each one for you.

## Download

| You want | Windows 10 or later | Linux, including Steam Deck / SteamOS |
| --- | --- | --- |
| **Gatehouse** (to play) | [gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip): unpack, run **Install Gatehouse.cmd**, choose Gatehouse | [gatehouse-install.sh](https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh): `bash gatehouse-install.sh` |
| **Gatekeeper** (to host) | The same zip: choose Gatekeeper | The same script: `bash gatehouse-install.sh --gatekeeper` |
| **October Journey** (Gatehouse, the modpack and Steam shortcuts in one) | [GatehouseSetup.exe](https://github.com/scholar-andrew/gatehouse/releases/latest/download/GatehouseSetup.exe) | [gatehouse-setup.sh](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-setup.sh) |

The Gatehouse and Gatekeeper installers are plain scripts you can read first. They need no administrator rights,
check each download against a published SHA-256, and have a dry run that shows what they would do.

Current version: **Gatehouse 3.12.1**, for both halves. What changed is listed in the program (the Gatehouse updates
and Gatekeeper updates tabs), it updates itself, and on a machine with both halves one press updates both.

| | Windows | Linux | SteamOS (Steam Deck) |
| --- | --- | --- | --- |
| Gatehouse | Yes | Yes | Yes |
| Gatekeeper | Yes, with MC Server Soft | Yes, running the servers itself (new, not yet run on a real machine) | Yes, running the servers itself (new, not yet run on a real Deck) |

MC Server Soft is Windows only, so on Linux and SteamOS Gatekeeper starts and stops the Minecraft servers itself.
Details: [what works on which system](docs/guide.md#what-works-on-which-system).

## October Journey

A server and its modpack are a **profile**, so one copy of Gatehouse can serve several. The first is **October
Journey**: a private Minecraft 26.3 (Fabric) survival server with Jarvis, a butler who farms, mines, builds and runs
errands. **About that server** (settings, mods, Jarvis, and what runs with it): [docs/server.md](docs/server.md).

### Download (October Journey)

| Operating system | Install | Uninstall |
| --- | --- | --- |
| **Windows** 10 (64-bit) | [**GatehouseSetup.exe**](https://github.com/scholar-andrew/gatehouse/releases/latest/download/GatehouseSetup.exe) | [**GatehouseUninstall.exe**](https://github.com/scholar-andrew/gatehouse/releases/latest/download/GatehouseUninstall.exe) |
| **Linux**, including **Steam Deck** (SteamOS 3.9) | [**gatehouse-setup.sh**](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-setup.sh) | [**gatehouse-uninstall.sh**](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-uninstall.sh) |

One download sets up everything: Prism Launcher, the modpack (it updates itself each time you play), the server in your
server list, and two Steam shortcuts: **October Journey** (updates, starts the game and joins the server) and **Prism
Launcher**, each with library art and a Steam Deck controller layout. Set up before the October Journey shortcut
existed? Run setup again and pick **Add the October Journey shortcut**.

- **Windows:** run the exe. If Windows says "Windows protected your PC", click **More info**, then **Run anyway** (the
  file isn't code-signed).
- **Linux, including Steam Deck:** in a terminal (on a Steam Deck: Desktop Mode, then Konsole), go to your Downloads
  folder and run `bash gatehouse-setup.sh`.

Step-by-step instructions and requirements (Minecraft account, Tailscale access, whitelist): [INSTALL.md](INSTALL.md).
What it was tested on, with hardware: [INSTALL.md: Tested on](INSTALL.md#tested-on).
All versions: [Releases](https://github.com/scholar-andrew/gatehouse/releases).

### What it installs and runs
The modpack (a Prism Launcher instance) is always installed. **Quick install** adds everything below in the usual
folders; or you choose which of the rest you want and where Prism Launcher and the uninstaller go. The uninstaller
likewise offers **Quick uninstall** or choosing what to remove ([INSTALL.md: Choosing what to install](INSTALL.md#choosing-what-to-install)). In short
(the full list, with every file and folder, is in
[INSTALL.md: What setup does to your computer](INSTALL.md#what-setup-does-to-your-computer)):

- **Prism Launcher**, if you don't have it: the official installer from Prism's GitHub releases on Windows (checked
  against a recorded SHA-256), or the Flathub app on Linux.
- **A Prism instance**, "October Journey 26.3 (Fabric)", with a small update script that Prism runs before every launch.
- **The October Journey server** in the instance's server list.
- **Two Steam shortcuts**: October Journey (this pack's art), which starts the game and joins the server, and Prism
  Launcher (Prism's own logo), which opens the launcher. Both get a Steam Deck controller layout. Setup closes or asks
  you to close Steam for this, and backs up the files it changes.
- **On Linux only**, permission for Prism's Flatpak sandbox to write two Steam folders (library art and controller
  layouts).
- **The uninstaller**, kept on your computer: in **Settings, Apps** on Windows, or as **Uninstall October Journey** in
  the app menu on Linux.
- **The installer**, kept beside it, to change your choices later.

No administrator rights, no services, nothing that starts with your computer. Every time you play, the update script
downloads changed mods (from Modrinth, each checked against a recorded hash) and keeps the art, layout and server entry
current: see [What runs each time you play](INSTALL.md#what-runs-each-time-you-play) and
[What it connects to](INSTALL.md#what-it-connects-to). The uninstallers undo all of it:
[Removing it](INSTALL.md#removing-it).

---

## What's in this repo

| Where | What |
| --- | --- |
| [docs/guide.md](docs/guide.md) | **How to use Gatehouse and Gatekeeper**: for players and hosts, step by step. |
| [INSTALL.md](INSTALL.md) | For players: installing, choosing what to install, what it was tested on, what setup changes, what runs on each launch, network use, removal. |
| [docs/server.md](docs/server.md) | The October Journey server (settings, its mods, Jarvis), and Gatekeeper in depth. |
| [docs/mods.md](docs/mods.md) | Every mod in the pack and where it comes from (generated). |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version (the same notes the launch pop-up shows). |
| [docs/how-it-works.md](docs/how-it-works.md) | For the maintainer: how the pack is built, hosted and synced, the Steam keyboard setup, and how to make common changes. |
| [docs/profiles.md](docs/profiles.md) | Server profiles: how Gatehouse serves more than one server, and the steps for adding one. |
| [mods-src/](mods-src/README.md) | Source of the launcher (the window before the game starts), the launch wrapper, and the pack's own mods: Gatehouse Settings, Chat Lift, Controller Handoff Fix, and the REI brewing patch. |
| `host/` | Gatekeeper: the program on the machine that runs the servers, and its window. |
| `source/` | October Journey's pack definition (`mods.json`) and files shipped as they are. |
| `profiles/` | Other server profiles, one folder each (`example/` is a template). |
| `dist/` | The built packs (packwiz format) that devices download. |
| `clients/` | The update script each device runs before every launch, and the files setup installs with it. |
| `installer/` | The installers and uninstallers for Windows and Linux (Gatehouse and Gatekeeper by themselves: `gatehouse-install.ps1` / `.sh`, `keeper/`), October Journey's profile (`config.json`), the Steam art and the controller layout. |
| `server/` | What the October Journey server runs, recorded for players (shown in the launcher's Server updates tab). |
| `tools/` | Building the packs, the launcher, Gatekeeper and the mods; installing Gatekeeper; serving the packs over Tailscale; making the art. |
