# Playing on October Journey

One download sets everything up: Prism Launcher, the modpack (it updates itself every time you play), the server in your
server list, and a Steam shortcut with its own library art and Steam Deck controller layout.

This page covers installing, then [exactly what setup changes on your computer](#what-setup-does-to-your-computer),
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
3. When it asks, quit Steam (Steam menu, then Exit) and click OK.
4. Start Steam, find **Minecraft (Prism Launcher) - your computer's name** in your library, and play. The first time
   Prism opens, sign in with your Microsoft account and pick **October Journey 26.3 (Fabric)**.

## Steam Deck (or Linux)
1. Switch to **Desktop Mode** and download
   [**october-journey-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/october-journey-setup.sh).
   It's a plain script: you can open it in a text editor and read it before running it.
2. Open **Konsole** in your Downloads folder and run:
   ```bash
   bash october-journey-setup.sh
   ```
   It installs Prism Launcher from Flathub if needed and asks before closing Steam. It needs `curl`, `python3` and
   `flatpak`, which SteamOS already has.
3. Go back to Game Mode and play **Minecraft (Prism Launcher) - steamdeck** (or your device's name). Sign in to your
   Microsoft account the first time Prism opens.

## Staying up to date
Nothing to do: every launch checks for changes and installs them before the game starts. Running setup again is safe;
it updates your setup without duplicating anything.

## Controllers
- **Steam Deck:** its controls get the pack's layout (R4 jump, L4 crouch, trackpad as mouse). In a text box, Steam's
  keyboard opens by itself and chat moves up above it.
- **Steam Controller on a PC:** press **Steam + X** in a text box for Steam's keyboard; chat moves up above it. Press
  Steam + X again to close it. Launching from Steam's normal window gives the smaller keyboard; Big Picture gives the
  larger one, and chat moves up to match either.
- **Other controllers** work through the Controlify mod, with Controlify's own on-screen keyboard. If you use one on a
  PC, you may want to turn off Steam Input for the shortcut (Properties, Controller).

---

## What setup does to your computer

Setup first downloads its settings, [`installer/config.json`](installer/config.json), from this repository. Everything
it installs comes from the places listed in [What it connects to](#what-it-connects-to). It runs as you, needs no
administrator rights, installs no services and adds nothing that starts with your computer.

### Windows (`OctoberJourneySetup.exe`)
1. **Prism Launcher.** If `%LOCALAPPDATA%\Programs\PrismLauncher\prismlauncher.exe` doesn't exist, it downloads the
   official Prism Launcher Windows installer (the version in `config.json`) from Prism's GitHub releases, checks it
   against the SHA-256 in `config.json`, and runs it silently (`/S`) for your user only. If Prism is already installed,
   it's left as it is.
2. **The game instance** at `%APPDATA%\PrismLauncher\instances\october-journey`:
   - `mmc-pack.json`: Minecraft 26.3 with Fabric Loader 0.19.5. Prism downloads Minecraft, Fabric and a Java runtime
     itself the first time you play, as for any Prism instance.
   - `instance.cfg`: the instance's name, and a **pre-launch command** that makes Prism run
     `powershell -NoProfile -ExecutionPolicy Bypass -File minecraft\pack-sync.ps1` before each game start. The
     execution-policy setting applies to that one run of that one script; your system's PowerShell policy isn't
     changed.
   - In `minecraft\`: `pack-sync.ps1` (the update script), `packwiz-installer-bootstrap.jar` (checked against the
     SHA-256 in `config.json`), `pack-sources.txt` (where updates come from), and `servers.dat` with the October
     Journey server added (existing entries are kept).
3. **Steam**, only if Steam is installed. It finds Steam through the registry (`HKCU\Software\Valve\Steam`) and your
   Steam account number from Steam's `config\loginusers.vdf` (the account you used most recently). Steam must be closed
   for this part; setup asks you to quit it and waits.
   - Adds a non-Steam game shortcut, **Minecraft (Prism Launcher) - your computer's name**, to
     `Steam\userdata\<account>\config\shortcuts.vdf`, or updates an existing Prism shortcut instead of adding a second.
     The old file is kept as `shortcuts.vdf.bak-setup`.
   - Saves the library art (five PNGs made for this pack, from [`installer/art`](installer/art)) in
     `Steam\userdata\<account>\config\grid\`, named after the shortcut's app ID.
   - Saves the Steam Deck controller layout as
     `Steam\steamapps\common\Steam Controller Configs\<account>\config\<shortcut name>\controller_neptune.vdf` (an
     existing one is kept as `.bak-setup`) and lists it in `configset_controller_neptune.vdf` in the folder above.
   - Writes `steam-target.json` in the instance's `minecraft\` folder: where the art and layout live, so updates can
     reach them.
4. A log of every step is written to `%TEMP%\OctoberJourneySetup.log`.

### Steam Deck / Linux (`october-journey-setup.sh`)
Same steps, with these differences:
1. **Prism Launcher** comes from Flathub: if `org.prismlauncher.PrismLauncher` isn't installed, it adds the Flathub
   remote for your user (`flatpak remote-add --user --if-not-exists flathub ...`) and runs
   `flatpak install --user -y flathub org.prismlauncher.PrismLauncher`.
2. **The instance** is at `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`,
   and its pre-launch command is `bash $INST_MC_DIR/pack-sync.sh`.
3. **Steam** is at `~/.local/share/Steam`. If Steam is running, setup asks first and then closes it with
   `steam -shutdown`. The shortcut runs `flatpak run org.prismlauncher.PrismLauncher`.
4. **Flatpak permission.** Prism runs in Flatpak's sandbox and can't normally see Steam's folders. So that updates can
   refresh the art and controller layout, setup grants Prism access to exactly two folders with
   `flatpak override --user --filesystem=...`: `~/.local/share/Steam/userdata/<account>/config/grid` and
   `~/.local/share/Steam/steamapps/common/Steam Controller Configs/<account>/config`. Nothing else of Steam's is
   opened up.
5. Output goes to the terminal; nothing is logged to a file during setup.

## What runs each time you play

When you press Play, Prism runs the instance's update script ([`clients/pack-sync.ps1`](clients/pack-sync.ps1) on
Windows, [`clients/pack-sync.sh`](clients/pack-sync.sh) on Linux) before Minecraft starts. It:

1. Tries each address in `pack-sources.txt` in order (the owner's PC over Tailscale, then GitHub) and uses the first
   that answers. If none answers, the game starts with what's already installed.
2. Runs packwiz-installer, which compares your instance with the pack's file list and downloads, checks (SHA-256 or
   SHA-512) and installs anything new or changed: mods, their configs, and a few pack files. Mods that were removed
   from the pack are removed from your instance; files you added yourself are left alone.
3. Copies new library art and controller layout into Steam, using `steam-target.json`. The first time, it keeps what
   was there as `*.before-pack`. After that it only replaces a file that's still the version it last installed, so art
   or a layout you change in Steam is kept. Steam shows these after it restarts.
4. Makes sure the October Journey server is in your server list. If you edit or delete that entry in the game, it's
   left as you set it.
5. Replaces itself with the pack's newest copy of the script, for the next launch.
6. Writes one line per step to `pack-sync.log` in the instance's `minecraft` folder.

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
**Windows**
- Remove the Steam shortcut: in Steam, right-click it, **Manage**, **Remove non-Steam game from your library**.
- Delete the instance folder `%APPDATA%\PrismLauncher\instances\october-journey`.
- If setup installed Prism and you don't want it: **Settings**, **Apps**, **Prism Launcher**, **Uninstall**.
- Optional tidy-up, with Steam closed: the art files `<app ID>*.png` in `Steam\userdata\<account>\config\grid\`, and the
  `minecraft (prism launcher) - <name>` folder under `Steam\steamapps\common\Steam Controller Configs\<account>\config\`.

**Steam Deck / Linux**
- Remove the Steam shortcut the same way.
- Delete `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`.
- Remove the two folder permissions: `flatpak override --user --reset org.prismlauncher.PrismLauncher` (this clears every
  per-user Flatpak override for Prism, including any you made yourself).
- If you don't want Prism: `flatpak uninstall --user org.prismlauncher.PrismLauncher`.
