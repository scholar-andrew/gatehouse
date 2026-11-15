# Playing on October Journey

One download sets everything up: Prism Launcher, the modpack (it updates itself every time you play), the server in your
server list, and a Steam shortcut with its own library art and Steam Deck controller layout. You choose which of these
you want, and the uninstaller lets you choose what to remove.

This page covers installing, [choosing what to install](#choosing-what-to-install), [what it was tested on](#tested-on), then
[exactly what setup changes on your computer](#what-setup-does-to-your-computer),
[what runs each time you play](#what-runs-each-time-you-play), [what it connects to](#what-it-connects-to) and
[how to remove it](#removing-it).

## Before you start
- **Minecraft Java Edition** on your Microsoft account.
- **Access to the server.** It's reached through Tailscale: install [Tailscale](https://tailscale.com/download), sign
  in, and accept the share the owner sends you. You also need to be on the server's whitelist; ask the owner.
- **Steam** (optional). Without it, setup still makes the Prism instance; you just play from Prism.

## Windows
1. Download [**OctoberJourneySetup.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneySetup.exe).
2. Run it. Windows may say "Windows protected your PC" because the file isn't code-signed: click **More info**, then
   **Run anyway**. It doesn't need administrator rights. Its full source is
   [`installer/windows/OctoberJourneySetup.cs`](installer/windows/OctoberJourneySetup.cs).
   - **If the file disappears** and Windows says it "cannot access the specified device, path, or file", your
     antivirus removed it. Panda Dome did this on the tested desktop without listing it in its quarantine; with Panda
     Dome uninstalled, the same file ran normally. Allow the file in your antivirus (or check its quarantine) and
     download it again. The same applies to the uninstaller.
3. Click **Quick install** for everything in the usual folders, or **Choose what to install...** to tick items and pick
   folders ([Choosing what to install](#choosing-what-to-install)).
4. If you chose the Steam shortcut, it asks you to quit Steam (Steam menu, then Exit): do that and click OK.
5. Start Steam, find **Minecraft (Prism Launcher) - your computer's name** in your library, and play. The first time
   Prism opens, sign in with your Microsoft account and pick **October Journey 26.3 (Fabric)**.

## Linux, including Steam Deck
1. Download (on a Steam Deck, switch to **Desktop Mode** first)
   [**october-journey-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-setup.sh).
   It's a plain script: you can open it in a text editor and read it before running it.
2. Open a terminal in your Downloads folder (on a Steam Deck: **Konsole**) and run:
   ```bash
   bash october-journey-setup.sh
   ```
   It first offers a quick install (press Enter); answer `n` to be asked about each item and where to save the
   uninstaller and installer ([Choosing what to install](#choosing-what-to-install)). It installs
   Prism Launcher from Flathub if you want it, and asks before closing Steam. It needs `bash`, `curl`, `python3` and
   `flatpak` (SteamOS already has them) and Steam installed at `~/.local/share/Steam`.
3. Start Steam (on a Steam Deck, go back to Game Mode) and play **Minecraft (Prism Launcher) - your device's name**
   (`steamdeck` on a Steam Deck). Sign in to your Microsoft account the first time Prism opens.

## Choosing what to install
The modpack itself (a Prism Launcher instance whose mods update every time you play) is always installed. Everything
else is your choice. The first screen offers **Quick install**: every item below, in the usual folders. Otherwise you
choose item by item (Windows: checkboxes; Linux: a question per item; all on by default) and where some of it goes.

| Item | What it adds | Notes |
| --- | --- | --- |
| **Prism Launcher** | The game launcher | Only offered when it isn't installed. Without it, the Steam shortcut can't be made; install Prism later and the instance shows up in it. |
| **October Journey server** | The server in the game's server list | |
| **Steam library shortcut** | **Minecraft (Prism Launcher) - your device's name** in Steam | Needs Steam and Prism. Steam has to be closed. |
| **Steam library art** | Cover, banner, logo and icon for the shortcut | Needs the shortcut. |
| **Steam Deck controller layout** | The pack's layout for Steam Deck controls on the shortcut | Needs the shortcut. |
| **Keep these up to date when you play** | Each launch brings the server entry, art and layout up to date with the pack | Only for the ones you chose. Changes you make yourself are kept either way. On Linux this also gives Prism's Flatpak access to the two Steam folders it needs. |
| **Save the uninstaller** | The uninstaller, kept on your device | Windows: in **Settings, Apps**. Linux: **Uninstall October Journey** in the app menu. |
| **Save this installer** | A copy of setup, to change your choices later | Windows: **Settings, Apps**, **Modify** (with the uninstaller saved). Linux: **October Journey setup** in the app menu. |

**Where things go.** You can choose:
- **Prism Launcher's folder**, on Windows, when setup installs it (default `%LOCALAPPDATA%\Programs\PrismLauncher`). On
  Linux, Flatpak decides.
- **The uninstaller and installer's folder** (default `%LOCALAPPDATA%\OctoberJourney` or
  `~/.local/share/october-journey`).

Set by other programs, so not chosen here: the game instance goes in Prism Launcher's instances folder (Prism's own
setting, if you've changed it, is followed), and the Steam shortcut, art and layout go where Steam keeps them.

Running setup again adds what you tick; it doesn't remove what you leave unticked (that's the uninstaller's job).
For scripts: `--yes` is a quick install without asking, `--skip=item,item` leaves items out (`prism`, `server`,
`shortcut`, `art`, `controller`, `updates`, `uninstaller`, `installer`), `--tools-dir=DIR` picks the uninstaller and
installer's folder, and on Windows `--prism-dir=DIR` picks Prism Launcher's; for example
`OctoberJourneySetup.exe --yes --skip=art,updates`.

## Staying up to date
Nothing to do: every launch checks for mod changes and installs them before the game starts. Running setup again is
safe; it updates your setup without duplicating anything.

## Mods you can switch on and off
Two mods in the pack are **off by default**:
- **Stack to Nearby Chests**: buttons and keys to stack your items into, or restock from, nearby chests.
- **Nearby Chest Crafting**: craft with items from nearby chests. The server has it too; with it off you craft the
  usual way, and you can still join.

To switch them on (or back off): on Minecraft's title screen, click **Mods**, pick **October Journey**, click its
settings button, and flip the switch. It takes effect the next time you start the game. The update before each launch
then disables the mods you've switched off (Prism shows them as disabled), so they aren't loaded at all.

## Controllers
- **Linux on a Steam Deck:** the Deck's controls get the pack's layout (R4 jump, L4 crouch, trackpad as mouse). In a
  text box, Steam's keyboard opens by itself and chat moves up above it.
- **Windows with a Steam Controller:** press **Steam + X** in a text box for Steam's keyboard; chat moves up above it. Press
  Steam + X again to close it. Launching from Steam's normal window gives the smaller keyboard; Big Picture gives the
  larger one, and chat moves up to match either.
- **Other controllers** work through the Controlify mod, with Controlify's own on-screen keyboard. If you use one on a
  PC, you may want to turn off Steam Input for the shortcut (Properties, Controller).

---

## Tested on

The pack, its update-on-launch and the controller and keyboard handling are in daily use on these three devices. All
run Minecraft 26.3 with Fabric Loader 0.19.5 through Prism Launcher 11.1.1, launched from Steam.

| | Windows desktop | Windows laptop | Linux: Steam Deck |
| --- | --- | --- | --- |
| **Operating system** | Windows 10 Home 22H2, 64-bit (build 19045.6466) | Windows 10 Home 22H2, 64-bit (build 19045.6466) | SteamOS 3.9.6 (build 20261112.101, beta channel), Linux 7.2.7; Steam client beta |
| **Device** | Custom PC, ASUS TUF Gaming Z690-Plus WiFi D4 | ASUS ROG Strix 17 GL703GE | Steam Deck OLED ("Galileo"), 1 TB |
| **CPU** | Intel Core i7-12700K | Intel Core i7-8750H | AMD Custom APU 0932 |
| **Memory** | 32 GB | 16 GB | 16 GB |
| **Graphics** | NVIDIA GeForce RTX 3070 Ti | NVIDIA GeForce GTX 1050 Ti + Intel UHD 630 | AMD (integrated) |
| **Display** | 2560 × 1080 | 1920 × 1080 | 1280 × 800 |
| **Controller** | Steam Controller | Steam Controller | Built-in controls |
| **Prism Launcher** | Windows installer | Windows installer | Flathub (Flatpak) |

What was tested where:

| | Windows desktop | Windows laptop | Linux: Steam Deck |
| --- | --- | --- | --- |
| Playing, update on launch (mods, art, controller layout, server list, the script updating itself) | Yes | Yes | Yes |
| Steam keyboard and chat position | Yes (Steam + X, normal and Big Picture) | Yes | Yes |
| Installer, including leaving items out | In test folders (`--prism-root`, `--steam-root`) | No | In test folders |
| Uninstaller, including keeping items (saved by setup, then run) | In test folders; the Settings, Apps entry itself not yet | No | In test folders, including the app-menu entry files and answering the questions |
| Quick or custom; chosen folders; a moved Prism instances folder | In test folders (the quick and custom windows as pictures; Prism's own folder not, since Prism is installed here) | No | In test folders, answering the questions |
| Update on launch respecting those choices | In a test instance | No | In a test instance |
| Uninstaller downloaded from the release and opened by hand | Yes: first screen, item list and Cancel all worked (nothing removed) | No | No |

The installers were checked in test folders rather than run start to finish on a fresh computer: the three devices
were set up with the same steps before the installers existed. Not tested at all: Windows 11, Linux distributions
other than SteamOS, the Steam Deck LCD model, and controllers other than Steam's.

## What setup does to your computer

Setup first downloads its settings, [`installer/config.json`](installer/config.json), from this repository. Everything
it installs comes from the places listed in [What it connects to](#what-it-connects-to). It runs as you, needs no
administrator rights, installs no services and adds nothing that starts with your computer. Each numbered step below
happens only if you chose that item, except the game instance, which is always installed.

### Windows (`OctoberJourneySetup.exe`)
1. **Prism Launcher** (item *Prism Launcher*). If Prism isn't installed (it looks in
   `%LOCALAPPDATA%\Programs\PrismLauncher` and in Windows' list of installed programs), it downloads the official
   Prism Launcher Windows installer (the version in `config.json`) from Prism's GitHub releases, checks it against the
   SHA-256 in `config.json`, and runs it silently (`/S /D=<the folder you chose>`) for your user only. If Prism is
   already installed, it's left as it is.
2. **The game instance** at `%APPDATA%\PrismLauncher\instances\october-journey` (or `october-journey` in the
   instances folder set in Prism's settings):
   - `mmc-pack.json`: Minecraft 26.3 with Fabric Loader 0.19.5. Prism downloads Minecraft, Fabric and a Java runtime
     itself the first time you play, as for any Prism instance.
   - `instance.cfg`: the instance's name, and a **pre-launch command** that makes Prism run
     `powershell -NoProfile -ExecutionPolicy Bypass -File minecraft\pack-sync.ps1` before each game start. The
     execution-policy setting applies to that one run of that one script; your system's PowerShell policy isn't
     changed.
   - In `minecraft\`: `pack-sync.ps1` (the update script), `packwiz-installer-bootstrap.jar` (checked against the
     SHA-256 in `config.json`), `pack-sources.txt` (where updates come from), `pack-options.json` (which of the server
     entry, art and layout each launch keeps up to date: item *Keep these up to date*), and, with item *October Journey
     server*, `servers.dat` with the server added (existing entries are kept).
3. **Steam** (item *Steam library shortcut*, and Steam installed). It finds Steam through the registry (`HKCU\Software\Valve\Steam`) and your
   Steam account number from Steam's `config\loginusers.vdf` (the account you used most recently). Steam must be closed
   for this part; setup asks you to quit it and waits.
   - Adds a non-Steam game shortcut, **Minecraft (Prism Launcher) - your computer's name**, to
     `Steam\userdata\<account>\config\shortcuts.vdf`, or updates an existing Prism shortcut instead of adding a second.
     The old file is kept as `shortcuts.vdf.bak-setup`.
   - Item *Steam library art*: saves the library art (five PNGs made for this pack, from [`installer/art`](installer/art)) in
     `Steam\userdata\<account>\config\grid\`, named after the shortcut's app ID.
   - Item *Steam Deck controller layout*: saves the layout as
     `Steam\steamapps\common\Steam Controller Configs\<account>\config\<shortcut name>\controller_neptune.vdf` (an
     existing one is kept as `.bak-setup`) and lists it in `configset_controller_neptune.vdf` in the folder above.
   - Writes `steam-target.json` in the instance's `minecraft\` folder: where the art and layout live, so updates can
     reach them.
4. **The uninstaller** (item *Save the uninstaller*). Downloads `OctoberJourneyUninstall.exe` from this repository's
   release, checks it against the SHA-256 in `config.json`, saves it as `OctoberJourneyUninstall.exe` in the folder
   you chose (default `%LOCALAPPDATA%\OctoberJourney`), and lists it in **Settings, Apps** as **October Journey
   (Minecraft modpack)** (a registry entry under `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney`,
   for your user only). If the download or check fails, setup carries on without it.
5. **The installer** (item *Save this installer*): copies itself to `OctoberJourneySetup.exe` in the same folder;
   with the uninstaller saved too, the Settings, Apps entry gets a **Modify** button that runs it.
6. A log of every step is written to `%TEMP%\OctoberJourneySetup.log`.

### Linux, including Steam Deck (`october-journey-setup.sh`)
Same steps, with these differences:
1. **Prism Launcher** comes from Flathub: if `org.prismlauncher.PrismLauncher` isn't installed, it adds the Flathub
   remote for your user (`flatpak remote-add --user --if-not-exists flathub ...`) and runs
   `flatpak install --user -y flathub org.prismlauncher.PrismLauncher`.
2. **The instance** is at `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`
   (or in the instances folder set in Prism's settings), and its pre-launch command is `bash $INST_MC_DIR/pack-sync.sh`.
3. **Steam** is at `~/.local/share/Steam`. If Steam is running, setup asks first and then closes it with
   `steam -shutdown`. The shortcut runs `flatpak run org.prismlauncher.PrismLauncher`.
4. **Flatpak permission** (item *Keep these up to date*, with the art or layout chosen). Prism runs in Flatpak's
   sandbox and can't normally see Steam's folders. So that updates can refresh the art and controller layout, setup grants Prism access to exactly two folders with
   `flatpak override --user --filesystem=...`: `~/.local/share/Steam/userdata/<account>/config/grid` and
   `~/.local/share/Steam/steamapps/common/Steam Controller Configs/<account>/config`. Nothing else of Steam's is
   opened up.
5. **The uninstaller** is saved as `uninstall.sh` in the folder you chose (default `~/.local/share/october-journey`;
   checked against the SHA-256 in `config.json`), with an app-menu entry, **Uninstall October Journey**
   (`~/.local/share/applications/october-journey-uninstall.desktop`; on a Steam Deck it shows in Desktop Mode). If the
   download or check fails, setup carries on without it.
6. **The installer** (item *Save this installer*) is copied to `setup.sh` in the same folder, with an
   app-menu entry, **October Journey setup**, to change your choices later.
7. Output goes to the terminal; nothing is logged to a file during setup.

## What runs each time you play

When you press Play, Prism runs the instance's update script ([`clients/pack-sync.ps1`](clients/pack-sync.ps1) on
Windows, [`clients/pack-sync.sh`](clients/pack-sync.sh) on Linux) before Minecraft starts. It:

1. Tries each address in `pack-sources.txt` in order (the owner's PC over Tailscale, then GitHub) and uses the first
   that answers. If none answers, the game starts with what's already installed, except on the very first launch:
   with no mods installed yet, it stops the launch with a message instead of starting a Minecraft that can't join the
   server. Press Play again once you're online.
2. Runs packwiz-installer (with Prism's own Java, or another it finds), which compares your instance with the pack's file list and downloads, checks (SHA-256 or
   SHA-512) and installs anything new or changed: mods, their configs, and a few pack files. Mods that were removed
   from the pack are removed from your instance; files you added yourself are left alone.
3. Copies new library art and controller layout into Steam, using `steam-target.json`, if `pack-options.json` says
   to keep them up to date. The first time, it keeps what
   was there as `*.before-pack`. After that it only replaces a file that's still the version it last installed, so art
   or a layout you change in Steam is kept. Steam shows these after it restarts.
4. Disables the mods you've switched off (see [Mods you can switch on and off](#mods-you-can-switch-on-and-off)) by
   renaming them to `.jar.disabled`, after briefly renaming them back so the update sees them intact. Your choices are
   under `mods` in `pack-options.json`.
5. Makes sure the October Journey server is in your server list, if `pack-options.json` says to. If you edit or delete that entry in the game, it's
   left as you set it.
6. Replaces itself with the pack's newest copy of the script, for the next launch.
7. Writes one line per step to `pack-sync.log` in the instance's `minecraft` folder, and packwiz-installer's own output
   to `pack-sync-java.log` beside it. If the mods ever fail to install, those two files say why.

## What it connects to
| Address | When | What for |
| --- | --- | --- |
| `raw.githubusercontent.com/scholar-andrew/october-journey-pack` | setup; launches when the owner's PC can't be reached | `config.json`, the update script, packwiz bootstrap, art, controller layout; the pack itself |
| `github.com/PrismLauncher/PrismLauncher/releases` | Windows setup, only if Prism isn't installed | Prism Launcher installer |
| `dl.flathub.org` | Linux setup, only if Prism isn't installed | Prism Launcher |
| `github.com/packwiz/packwiz-installer` | launches | packwiz-installer-bootstrap fetches and updates packwiz-installer itself |
| `100.91.134.102:8467` | launches | the pack, served from the owner's PC; only reachable through Tailscale |
| `cdn.modrinth.com` | launches | the mods listed in [docs/mods.md](docs/mods.md) |
| `100.91.134.102:25565` | when you join | the Minecraft server, through Tailscale |

Prism Launcher also talks to Microsoft (sign-in), Mojang (Minecraft) and Fabric, as it does for any instance. Nothing
in this pack sends anything about you anywhere.

## Removing it
The uninstallers look for each part of October Journey, wherever setup put it, and first offer **Quick uninstall**:
everything they find except Prism Launcher. Otherwise you choose what to remove: checkboxes on Windows, a question per
item on Linux. Anything you keep stays as it is, and the update script is told not to put back what you
removed. Steam has to be closed if you remove anything of Steam's; they ask. Nothing is downloaded.

| Operating system | Already installed by setup | Or download it | Source |
| --- | --- | --- | --- |
| **Windows** | **Settings**, **Apps**, **October Journey (Minecraft modpack)**, **Uninstall** | [OctoberJourneyUninstall.exe](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/OctoberJourneyUninstall.exe) | [`OctoberJourneyUninstall.cs`](installer/windows/OctoberJourneyUninstall.cs) |
| **Linux**, including **Steam Deck** | **Uninstall October Journey** in the app menu (Steam Deck: Desktop Mode) | [october-journey-uninstall.sh](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-uninstall.sh), then `bash october-journey-uninstall.sh` | [`october-journey-uninstall.sh`](installer/linux/october-journey-uninstall.sh) |

The downloaded copy works too on a setup made before setup saved the uninstaller.

| Item | What goes | Default |
| --- | --- | --- |
| **Steam library shortcut** | Each Steam shortcut named **Minecraft (Prism Launcher) - ...** that starts Prism Launcher, in every Steam account on the device. The shortcut list is backed up first as `shortcuts.vdf.bak-uninstall`; other shortcuts are kept. Its art and controller layout go with it. | yes |
| **Steam library art** | The shortcut's art, and the `.before-pack` copies the update script kept | yes |
| **Steam Deck controller layout** | The shortcut's layout folder and its entry in Steam's layout list | yes |
| **Prism's Steam folder permissions** (Linux) | The two Flatpak folder permissions setup gave Prism; other Flatpak settings for Prism are kept | yes |
| **Game instance** | `%APPDATA%\PrismLauncher\instances\october-journey`, or `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`, with its screenshots and any single-player worlds. The server entry goes with it. | yes |
| **October Journey server** | The server's entry in the game's server list (when the instance is kept) | yes |
| **Prism Launcher** | On Windows, Prism's own uninstaller (`uninstall.exe /S`); on Linux, `flatpak uninstall --user org.prismlauncher.PrismLauncher`, which keeps Prism's data folder. The October Journey uninstallers never delete Prism's data folder themselves. | **no** |
| **Saved installer** | The copy of setup and its app-menu entry (Linux) or **Modify** button (Windows) | yes |
| **The uninstaller** | Its saved copy and its Settings, Apps entry (Windows) or app-menu entry (Linux) | yes |

Only items that are actually there are offered. For scripts: `--yes` is a quick uninstall without asking,
`--skip=item,item` keeps items (`shortcut`, `art`, `controller`, `permissions` (Linux), `instance`, `server`, `prism`,
`installer`, `uninstaller`), and `--keep-instance` and `--remove-prism` still work. The Windows one logs to
`%TEMP%\OctoberJourneyUninstall.log`.

Setup's backup of the shortcut list (`shortcuts.vdf.bak-setup`) is left in place; delete it by hand if you like.

**By hand, without the uninstaller:** remove the shortcut in Steam (right-click it, **Manage**,
**Remove non-Steam game from your library**), delete the instance folder above, and on Linux run
`flatpak override --user --reset org.prismlauncher.PrismLauncher` (this clears every per-user Flatpak setting for Prism,
including any of your own).
