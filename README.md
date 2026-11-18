# Gatehouse

Gatehouse gets a group of friends onto the same Minecraft server and keeps them there. It is one program with two
halves:

- **Play**, the Gatehouse launcher, on each player's device: the servers you can join, their mods installed and kept
  up to date, what changed, chat, and the server controls the host allows you. It starts the game through
  [Prism Launcher](https://prismlauncher.org/).
- **Host**, Gatekeeper, on the machine that runs the servers: who may see, start and stop which server, the
  whitelist, new servers, backups, a web panel, and phone notifications. It works with
  [MC Server Soft](https://www.mcserversoft.com/) on Windows, or runs the servers itself.

Devices talk over the host's private [Tailscale](https://tailscale.com/) network, so nothing is opened to the
internet. Every update is checked against its published checksum and release signature.

Current version: **Gatehouse 3.41.0**, both halves.

## Start here

| | |
| --- | --- |
| **[User guide](docs/user-guide.md)** | The easy way in: playing, hosting and your phone, step by step with pictures |
| **[Getting started](docs/getting-started.md)** | Installing, your first server, inviting friends |
| **[Using Gatehouse](docs/gatehouse.md)** | Play: the window, a server's page, updates, controllers |
| **[Using Gatekeeper](docs/gatekeeper.md)** | Host: every page and option |
| **[Security and privacy](docs/security.md)** | Tailscale, permissions, signatures, what is sent where |
| **[Troubleshooting](docs/troubleshooting.md)** | When something doesn't work, including the Steam Deck |
| **[For developers](docs/developers.md)** | Building, the tests, publishing, signing, the update contract |
| **[Version history](docs/version-history.md)** | Every version of the program |

## Download

| You want | Windows 10 or later | Linux, including Steam Deck / SteamOS |
| --- | --- | --- |
| **Gatehouse** (to play) | [gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip): unpack, run **Install Gatehouse.cmd**, choose Gatehouse | [gatehouse-install.sh](https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh): `bash gatehouse-install.sh` |
| **Gatekeeper** (to host) | The same zip: choose Gatekeeper | The same script: `bash gatehouse-install.sh --gatekeeper` |
| **October Journey** (Gatehouse, the modpack and Steam shortcuts in one) | [GatehouseSetup.exe](https://github.com/scholar-andrew/gatehouse/releases/latest/download/GatehouseSetup.exe) | [gatehouse-setup.sh](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-setup.sh) |

The installers are plain scripts you can read first. They need no administrator rights, check each download
against a published SHA-256, and have a dry run that shows what they would do. All versions:
[Releases](https://github.com/scholar-andrew/gatehouse/releases).

| | Windows | Linux | SteamOS (Steam Deck) |
| --- | --- | --- | --- |
| Gatehouse | Yes | Yes | Yes |
| Gatekeeper | Yes, with MC Server Soft or running servers itself | Yes, running servers itself | Yes, running servers itself |

## Tested on

Both halves are used every day on Windows 10, and both are in use on a Steam Deck OLED (SteamOS). A plain Linux
desktop has only been tried with stand-ins. Hardware and what was tested where:
[INSTALL.md: Tested on](INSTALL.md#tested-on).

## October Journey

A server and its modpack are a **profile**, so one copy of Gatehouse can serve several
([Server profiles](docs/profiles.md)). The first is **October Journey**: a private Minecraft 26.3 (Fabric) survival
server with Jarvis, a butler who farms, mines, builds and runs errands.

- [The server](docs/server.md): settings, its mods, Jarvis, and what runs with it.
- [Installing it](INSTALL.md): one download for Prism Launcher, the modpack, the server entry and Steam shortcuts,
  with exactly what setup changes on your computer and how to remove it.
- [Its mods](docs/mods.md) and [its changes](CHANGELOG.md).

## What's in this repository

| Where | What |
| --- | --- |
| `docs/` | The pages above, plus [How the pack works](docs/how-it-works.md) and [Gatekeeper internals](docs/gatekeeper-internals.md) for maintainers. |
| `mods-src/` | The program's Play half, the look both halves share, where it starts, and the pack's own mods ([README](mods-src/README.md)). |
| `host/` | The program's Host half (Gatekeeper) and its web panel. |
| `source/` | October Journey's pack definition, the program's notes, and the release key's public half. |
| `profiles/` | Other server profiles (`example/` is a template). |
| `dist/` | The built packs and the published program, which devices download. |
| `clients/` | The update script each device runs before every launch, and what setup installs with it. |
| `installer/` | The installers and uninstallers, the Steam art and the controller layout. |
| `server/` | What the October Journey server runs, recorded for players. |
| `tools/` | Building, testing, serving and signing. |
