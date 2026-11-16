# Playing on October Journey

One download sets everything up: Prism Launcher, the modpack (it updates itself every time you play), the server in your
server list, and two Steam shortcuts: **October Journey**, which updates the pack, starts the game and joins the
server, and **Prism Launcher**, which opens the launcher. Both get library art and the Steam Deck controller layout.
You choose which of these you want, and the uninstaller lets you choose what to remove.

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
1. Download [**GatehouseSetup.exe**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/GatehouseSetup.exe).
2. Run it. Windows may say "Windows protected your PC" because the file isn't code-signed: click **More info**, then
   **Run anyway**. It doesn't need administrator rights. Its full source is
   [`installer/windows/GatehouseSetup.cs`](installer/windows/GatehouseSetup.cs).
   - **If the file disappears** and Windows says it "cannot access the specified device, path, or file", your
     antivirus removed it. Panda Dome did this on the tested desktop without listing it in its quarantine; with Panda
     Dome uninstalled, the same file ran normally. Allow the file in your antivirus (or check its quarantine) and
     download it again. The same applies to the uninstaller.
3. Click **Quick install** for everything in the usual folders, or **Choose what to install...** to tick items and pick
   folders ([Choosing what to install](#choosing-what-to-install)).
4. If you chose a Steam shortcut, it asks you to quit Steam (Steam menu, then Exit): do that and click OK.
5. Start Steam, find **October Journey - your computer's name** in your library, and play: it updates the pack, then
   starts the game and joins the server. The first time, Prism Launcher opens to sign in with your Microsoft account.

## Linux, including Steam Deck
1. Download (on a Steam Deck, switch to **Desktop Mode** first)
   [**gatehouse-setup.sh**](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/gatehouse-setup.sh).
   It's a plain script: you can open it in a text editor and read it before running it.
2. Open a terminal in your Downloads folder (on a Steam Deck: **Konsole**) and run:
   ```bash
   bash gatehouse-setup.sh
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
| **Prism Launcher** | The game launcher | Only offered when it isn't installed. Without it, the Steam shortcuts can't be made; install Prism later and the instance shows up in it. |
| **October Journey server** | The server in the game's server list | |
| **October Journey shortcut** | **October Journey - your device's name** in Steam: updates the pack (and shows the [launch pop-up](#before-the-game-starts-the-launch-pop-up)), then starts the game and joins the server | Needs Steam and Prism. Steam has to be closed. |
| **Prism Launcher shortcut** | **Minecraft (Prism Launcher) - your device's name** in Steam: opens Prism Launcher | Needs Steam and Prism. Steam has to be closed. |
| **Steam library art** | Cover, banner, logo and icon: October Journey's own for its shortcut, Prism Launcher's logo for Prism's | Needs a shortcut. |
| **Steam Deck controller layout** | The pack's layout for Steam Deck controls, on both shortcuts | Needs a shortcut. |
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

Running setup again adds what you tick; it doesn't remove what you leave unticked (that's the uninstaller's job), and
it keeps your mod switches and pop-up settings.

**Set up before the October Journey shortcut existed?** Download setup again (or use the saved one: Windows **Settings,
Apps**, **Modify**; Linux **October Journey setup** in the app menu, from Desktop Mode on a Steam Deck) and run it with
Steam closed. Its first choice is **Add the October Journey shortcut**: that adds only the new shortcut, gives the
Prism Launcher shortcut Prism's own art, adds the controller layout to both, and updates the saved uninstaller and
installer. Nothing else changes. Updates can't add a shortcut themselves, because Steam rewrites its shortcut list
when it closes, and updates run while Steam is open.
For scripts: `--yes` is a quick install without asking, `--skip=item,item` leaves items out (`prism`, `server`,
`game` (the October Journey shortcut), `shortcut` (the Prism Launcher one), `art`, `controller`, `updates`,
`uninstaller`, `installer`), `--yes --add-shortcut` does only what **Add the October Journey shortcut** does, `--tools-dir=DIR` picks the uninstaller and
installer's folder, and on Windows `--prism-dir=DIR` picks Prism Launcher's; for example
`GatehouseSetup.exe --yes --skip=art,updates`.

## Staying up to date
Nothing to do: every launch checks for mod changes and installs them before the game starts. Running setup again is
safe; it updates your setup without duplicating anything.

### Before the game starts: the launch pop-up
From the moment you press Play, one October Journey window shows the update's steps as a checklist, each ticked off as
it finishes (the download shows its progress), with **Quit** to cancel the launch. When there's something to
download, it first says about how big the update is and where it downloads from (the owner's PC over Tailscale, or
GitHub when that isn't reachable; mods from Modrinth) and asks: the blue button reads **Download** and continues; **Quit** cancels
the launch. When the update is done, the same
window shows what happened, with **Play** (blue; it only works while the server is online. While the server is off
the same button reads **Request server**: it asks the owner's PC to turn the server on, where it chimes and shows who
asked, and it turns back into Play by itself when the server comes up), **Restart** (run the update again) and **Quit** (don't start the game;
Prism then says the pre-launch step failed, which is expected). It lists everything the update
checked, each with a coloured dot: the connection, mods and settings, your mod switches, the Steam art and controller
layouts, the server list, and the update script itself. Green means up to date, blue updated, grey off or not set up,
amber not checked (offline) and red failed. Below that:
- **Mods:** each mod added (green), updated (blue, with its old and new file) or removed (grey) by this update.
- **Mod switches:** mods switched on or off this launch.
- **Also on this device:** anything else the update did, such as a new pack default.
- **What's new:** the patch notes for every version since you last played.

A second tab, **Server updates**, starts with a live line saying whether the server answers right now (with ping and
players, or "not answering" when its PC is asleep or off), then shows what the server runs (game version, Jarvis, mods, main settings) and what changed
there since you last played, with notes. When something on the server is new, the button reads **Next: server
updates**: it takes you to that tab, and **Play** is there.

The next two tabs, **Server controls** and **Server config**, appear once the owner has allowed your device something
in the host panel on the server PC (MCSS control, PC sleep or Server control), or you have your own panel access.
Until then your device has asked and is waiting, and the tabs stay hidden.

A third tab, **Server controls**, is in three sections, each with its own status line: **Server Machine** (wake, sleep),
**MCSS** (the server software: start, close) and **Minecraft server**. It shows whether the server is online (checked every few seconds), your **ping** to
it as ten pips from red to green (all ten at 25 ms or less, one fewer for each step up to 500 ms), and the **players**
connected, with these buttons (each only when it applies: Wake while the server PC isn't answering, Start while the
server is off, Stop while it runs):
- **Wake server machine:** wakes the server's PC if it's asleep (Wake on LAN). This works from the home network; not from
  elsewhere.
- **Start server:** starts the Minecraft server through the server panel, for when the PC is awake but the server is
  off. It needs the owner's **Server control** permission for your device (set in the host panel; nothing to sign
  in to), or your own panel sign-in (Server config).
- **Stop server:** stops the Minecraft server through the panel (the world is saved first). It asks before stopping,
  since anyone playing is disconnected. Same access as Start. Then it watches the shutdown and says how it went: green
  "shut down smoothly" when the server stopped and its log shows the world being saved, amber when the save couldn't
  be confirmed or the log has errors, red when it didn't stop within 2 minutes.

Below them, **MCSS (the server software)** shows whether MCSS is running on the server PC, with **Start MCSS** and
**Close MCSS**. The first time, your device asks for this and waits until the owner allows it in the host panel on the
server PC. Closing only works once the Minecraft server is stopped, so the world is never cut off mid-save. The
button says how it will close MCSS, from MCSS's own close option on the server PC: **Close MCSS** when MCSS's X
button exits it (a normal close), or **Force close MCSS** when its X button only hides it to the taskbar (MCSS is
ended instead).

**Sleep server machine** puts the server PC to sleep (MCSS stays open and carries on when it wakes). It needs its own
permission from the owner, separate from MCSS control, and only works while the Minecraft server is stopped.

A fourth tab, **Server config**, says how this device controls the server (the same line is under Start and Stop in
Server controls): **through the host helper** (the owner allowed it; nothing is saved on your device) or **with your
panel sign-in**. When the owner allowed
the helper way, that is the one used. The owner can remove any of them from the server PC; your device then forgets
what it had saved. Below that is the **server panel sign-in**: username, password, **Sign in** and **Sign out**. The
launcher keeps the key the panel hands back (in `server-access.json` in the game folder on this device only), never
your password. You don't need it if the owner allowed your device through the host helper.

One more tab, **Remote host panel**, appears only on a device the owner has allowed **Helper control**. It has two
tabs inside:
- **Manage access:** who may do what. **By launcher** lists the launchers that have asked the server PC for access,
  with their permissions (Server control, MCSS control, PC sleep) switched on or off for the one you select, plus
  **Remove server access** and **Forget**. **By player** lists the players, and what you allow a player is set on all
  their confirmed devices and on ones they join the server from later.
- **Host activity:** whether MCSS and the Minecraft server are running right now, and the helper's activity.

Helper control itself is only given on the server PC.

The window stays open while the server wakes or starts: nothing closes it but you.

Nothing starts the game by itself: there are no countdowns. The window waits until you press **Play** (or Enter or
Space on it); closing the window is the same as **Quit**. With nothing new it shows the checks with "no updates".
Offline, it says so, and Play starts the game with what's already installed. To skip the window entirely, turn off
both pop-up switches in **Mods > Gatehouse Settings**; the game then starts straight after the update.

Turn either pop-up off in **Mods > Gatehouse Settings** (**Update pop-up**, **No-update pop-up**). All the patch notes
are in [CHANGELOG.md](CHANGELOG.md).

## Mods you can switch on and off
Every mod in the pack that isn't a library other mods need can be switched off: maps, recipe lookup, inventory
helpers, performance mods, controller support and so on. [docs/mods.md](docs/mods.md) lists which ones. All are on by
default except two:
- **Stack to Nearby Chests**: buttons and keys to stack your items into, or restock from, nearby chests.
- **Nearby Chest Crafting**: craft with items from nearby chests. The server has it too; with it off you craft the
  usual way, and you can still join.

To switch a mod: on Minecraft's title screen, click **Mods**, pick **Gatehouse Settings**, click its settings button, and
flip the switch (hover a switch to see what the mod does). The same screen has the two launch pop-up switches. It takes effect the next time you start the game. The
update before each launch then disables the mods you've switched off (Prism shows them as disabled), so they aren't
loaded at all. Some mods need another: Reese's Sodium Options needs Sodium, and Controller Handoff Fix needs
Controlify, so switching off the second switches off the first too.

The pack also sets one default: Inventory Profiles Next's Auto Refill arrows are hidden on the hotbar. Change it in
Inventory Profiles Next's own settings (Auto Refill, **Also Show Icons in the Hotbar**); the pack only sets it on a
new install and never changes it back.

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
| Installer, including leaving items out | In test folders (`--prism-root`, `--steam-root`) | Yes: a clean install on the real laptop (uninstall, setup, first launch installed the mods, joined the server) | In test folders |
| Adding the October Journey shortcut to an existing setup (setup's first choice), then playing from it: the update, the pop-up, the game starting and joining the server | Yes | Yes | Yes (Game Mode) |
| Two shortcuts: art (October Journey's and Prism's), layouts, updates keeping both, uninstall removing both | In test folders | No | In test folders |
| Launch pop-up (update, no update, offline, switched off) | Yes, real window; the cases as pictures | Yes | Yes (Game Mode: in front, Play works with the controller) |
| Uninstaller, including keeping items (saved by setup, then run) | In test folders; the Settings, Apps entry itself not yet | No | In test folders, including the app-menu entry files and answering the questions |
| Quick or custom; chosen folders; a moved Prism instances folder | In test folders (the quick and custom windows as pictures; Prism's own folder not, since Prism is installed here) | No | In test folders, answering the questions |
| Update on launch respecting those choices | In a test instance | No | In a test instance |
| Uninstaller downloaded from the release and opened by hand | Yes: first screen, item list and Cancel all worked (nothing removed) | No | No |

Also checked in play: Steam shows the game as running until Minecraft quits; the Mods > Gatehouse Settings
screen (pages, mod switches, pop-up switches); joining the server with Nearby Chest Crafting, and with Jade, switched
off.

Apart from the laptop's clean install, the installers were checked in test folders rather than run start to finish on
a fresh computer: the desktop and the Steam Deck were set up by hand before the installers existed, and all three have
since run setup's "Add the October Journey shortcut". Not tested at all: Windows 11, Linux distributions
other than SteamOS, the Steam Deck LCD model, and controllers other than Steam's.

## What setup does to your computer

Setup first downloads its settings, [`installer/config.json`](installer/config.json), from this repository. Everything
it installs comes from the places listed in [What it connects to](#what-it-connects-to). It runs as you, needs no
administrator rights, installs no services and adds nothing that starts with your computer. Each numbered step below
happens only if you chose that item, except the game instance, which is always installed.

### Windows (`GatehouseSetup.exe`)
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
3. **Steam** (items *October Journey shortcut* and *Prism Launcher shortcut*, and Steam installed). It finds Steam through the registry (`HKCU\Software\Valve\Steam`) and your
   Steam account number from Steam's `config\loginusers.vdf` (the account you used most recently). Steam must be closed
   for this part; setup asks you to quit it and waits.
   - Adds non-Steam game shortcuts to `Steam\userdata\<account>\config\shortcuts.vdf`, or updates existing ones
     instead of adding them again. Both run `prismlauncher.exe`. **October Journey - your computer's name** adds
     `--launch october-journey --server <the server's address>` (Prism launches the instance, which runs the update
     first, and Minecraft joins the server). **Minecraft (Prism Launcher) - your computer's name** has no options, so
     it opens Prism. The old file is kept as `shortcuts.vdf.bak-setup`.
   - Item *Steam library art*: saves five PNGs per shortcut in `Steam\userdata\<account>\config\grid\`, named after
     the shortcut's app ID. The October Journey shortcut gets the art made for this pack, from
     [`installer/art`](installer/art). The Prism Launcher shortcut gets Prism Launcher's logo on a plain background, from
     [`installer/art-prism`](installer/art-prism); its [NOTICE](installer/art-prism/NOTICE.md) has the logo's licence.
   - Item *Steam Deck controller layout*: saves the layout as
     `Steam\steamapps\common\Steam Controller Configs\<account>\config\<shortcut name>\controller_neptune.vdf` for
     each shortcut (an existing one is kept as `.bak-setup`) and lists it in `configset_controller_neptune.vdf` in the
     folder above.
   - Writes `steam-target.json` in the instance's `minecraft\` folder: where each shortcut's art and layout live, so
     updates can reach them.
4. **The uninstaller** (item *Save the uninstaller*). Downloads `GatehouseUninstall.exe` from this repository's
   release, checks it against the SHA-256 in `config.json`, saves it as `OctoberJourneyUninstall.exe` in the folder
   you chose (default `%LOCALAPPDATA%\OctoberJourney`), and lists it in **Settings, Apps** as **October Journey
   (Minecraft modpack)** (a registry entry under `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\OctoberJourney`,
   for your user only). If the download or check fails, setup carries on without it.
5. **The installer** (item *Save this installer*): copies itself to `OctoberJourneySetup.exe` in the same folder;
   with the uninstaller saved too, the Settings, Apps entry gets a **Modify** button that runs it.
6. A log of every step is written to `%TEMP%\GatehouseSetup.log`.

### Linux, including Steam Deck (`gatehouse-setup.sh`)
Same steps, with these differences:
1. **Prism Launcher** comes from Flathub: if `org.prismlauncher.PrismLauncher` isn't installed, it adds the Flathub
   remote for your user (`flatpak remote-add --user --if-not-exists flathub ...`) and runs
   `flatpak install --user -y flathub org.prismlauncher.PrismLauncher`.
2. **The instance** is at `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`
   (or in the instances folder set in Prism's settings), and its pre-launch command is `bash $INST_MC_DIR/pack-sync.sh`.
3. **Steam** is at `~/.local/share/Steam`. If Steam is running, setup asks first and then closes it with
   `steam -shutdown`. The shortcuts run `flatpak run org.prismlauncher.PrismLauncher`, the October Journey one with
   `--launch october-journey --server <the server's address>` added.
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

1. Checks each address in `pack-sources.txt` (the owner's PC over Tailscale, then GitHub), giving each 5 seconds. If
   none answers (the network may still be coming up after sleep), it waits 5 seconds and checks again. If none answers
   then, the game starts with what's already installed, except on the very first launch:
   with no mods installed yet, it stops the launch with a message instead of starting a Minecraft that can't join the
   server. Press Play again once you're online.
2. Runs packwiz-installer (with Prism's own Java, or another it finds; always the same pinned version, v0.5.14,
   without its own windows), with the first address that answered. If that fails or stalls (a run is stopped after 5
   minutes), it tries the next address, then the first once more. packwiz-installer compares your instance with the pack's file list and downloads, checks (SHA-256 or
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
7. Shows the [launch pop-up](#before-the-game-starts-the-launch-pop-up) (`extras/launch-popup.jar`, run with the same
   Java) and waits for it to close. It notes the pack version it showed in `pack-version-seen.txt`, and what this
   launch changed on your device in `pack-sync-news.txt`.
8. Writes one line per step to `pack-sync.log` in the instance's `minecraft` folder, and packwiz-installer's own output
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
| **Windows** | **Settings**, **Apps**, **October Journey (Minecraft modpack)**, **Uninstall** | [GatehouseUninstall.exe](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/GatehouseUninstall.exe) | [`OctoberJourneyUninstall.cs`](installer/windows/OctoberJourneyUninstall.cs) |
| **Linux**, including **Steam Deck** | **Uninstall October Journey** in the app menu (Steam Deck: Desktop Mode) | [gatehouse-uninstall.sh](https://github.com/scholar-andrew/october-journey-pack/releases/latest/download/gatehouse-uninstall.sh), then `bash gatehouse-uninstall.sh` | [`gatehouse-uninstall.sh`](installer/linux/gatehouse-uninstall.sh) |

The downloaded copy works too on a setup made before setup saved the uninstaller.

| Item | What goes | Default |
| --- | --- | --- |
| **Steam library shortcuts** | Each Steam shortcut named **October Journey - ...** or **Minecraft (Prism Launcher) - ...** that starts Prism Launcher, in every Steam account on the device. The shortcut list is backed up first as `shortcuts.vdf.bak-uninstall`; other shortcuts are kept. Their art and controller layouts go with them. | yes |
| **Steam library art** | The shortcuts' art, and the `.before-pack` copies the update script kept | yes |
| **Steam Deck controller layout** | The shortcuts' layout folders and their entries in Steam's layout list | yes |
| **Prism's Steam folder permissions** (Linux) | The two Flatpak folder permissions setup gave Prism; other Flatpak settings for Prism are kept | yes |
| **Game instance** | `%APPDATA%\PrismLauncher\instances\october-journey`, or `~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey`, with its screenshots and any single-player worlds. The server entry goes with it. | yes |
| **October Journey server** | The server's entry in the game's server list (when the instance is kept) | yes |
| **Prism Launcher** | On Windows, Prism's own uninstaller (`uninstall.exe /S`); on Linux, `flatpak uninstall --user org.prismlauncher.PrismLauncher`, which keeps Prism's data folder. The October Journey uninstallers never delete Prism's data folder themselves. | **no** |
| **Saved installer** | The copy of setup and its app-menu entry (Linux) or **Modify** button (Windows) | yes |
| **The uninstaller** | Its saved copy and its Settings, Apps entry (Windows) or app-menu entry (Linux) | yes |

Only items that are actually there are offered. For scripts: `--yes` is a quick uninstall without asking,
`--skip=item,item` keeps items (`shortcut`, `art`, `controller`, `permissions` (Linux), `instance`, `server`, `prism`,
`installer`, `uninstaller`), and `--keep-instance` and `--remove-prism` still work. The Windows one logs to
`%TEMP%\GatehouseUninstall.log`.

Setup's backup of the shortcut list (`shortcuts.vdf.bak-setup`) is left in place; delete it by hand if you like.

**By hand, without the uninstaller:** remove the shortcut in Steam (right-click it, **Manage**,
**Remove non-Steam game from your library**), delete the instance folder above, and on Linux run
`flatpak override --user --reset org.prismlauncher.PrismLauncher` (this clears every per-user Flatpak setting for Prism,
including any of your own).
