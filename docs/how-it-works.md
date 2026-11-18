# How the October Journey pack works

For whoever maintains the pack: how it is built, served and brought onto each device. Players want
[Getting started](getting-started.md); building, testing and publishing the program is in [For developers](developers.md).

## The pieces

```
source/ + installer/art + installer/steam + clients/pack-sync.*      what you edit
        │  python tools/build_pack.py
        ▼
dist/main, dist/deck  (packwiz pack)  +  docs/mods.md                 what devices download
        │  served two ways
        ├── owner's PC over Tailscale: http://<its Tailscale address>:8467   (tools/serve.ps1, scheduled task)
        └── GitHub: raw.githubusercontent.com/.../main/dist           (git push)
        ▼
each device: Prism pre-launch → pack-sync.ps1 / pack-sync.sh → packwiz-installer → game starts
```

- **The pack** is in [packwiz](https://packwiz.infra.link/) format: `pack.toml`, `index.toml` (every file with its
  SHA-256) and one `mods/<slug>.pw.toml` per Modrinth mod (download URL plus SHA-512). Modrinth jars are never stored
  here; jars that aren't on Modrinth are stored in the pack.
- **Two variants**, `dist/main` and `dist/deck`. A mod marked `"deck": true` in `source/mods.json` goes only into the
  Deck variant; the Linux sync picks `deck` on Steam Deck hardware (DMI product `Jupiter` or `Galileo`). Right now no
  mod is Deck-only, so the two are the same; the mechanism is kept for later.
- **Hosting.** The owner's PC is fast, and live as soon as `dist/` is rebuilt; GitHub works anywhere, but only after
  a push, and `raw.githubusercontent.com` caches for about five minutes. Since Gatehouse 3.32.0 a device asks every
  source at once, including other Gate Systems that hand the pack out, and takes the newest that answers well
  ([For developers](developers.md#fetching-and-handing-out-pack-updates-r40)); older ones try the sources in order.

## Repository layout

| Path | What it is |
| --- | --- |
| `source/mods.json` | The pack definition: name, version, Minecraft and Fabric versions, every mod (Modrinth URL and SHA-512, or a file in `source/files/mods`), and the server's mod list. |
| `source/changelog.json` | Patch notes, newest first. The top entry must be the pack version or the build stops. Shipped as `extras/changelog.txt` for the launch pop-up, and written out as `CHANGELOG.md`. |
| `source/files/launch-popup.jar` | The launch pop-up, built from `mods-src/launch-popup` by `tools/build_program.ps1`. Shipped as `extras/launch-popup.jar`. |
| `source/files/config/` | Config files shipped as they are (into the instance's `config/`), kept in line with the pack on every update. |
| `source/files/config-defaults/` | Default settings, written only where the file doesn't exist yet (`preserve` in the index), so a player's own changes stay. Now: Inventory Profiles Next's hotbar Auto Refill icons hidden. |
| `source/files/mods/` | Mod jars that aren't on Modrinth: the pack's own and patched mods (see below). |
| `mods-src/` | Source for those jars. See [mods-src/README.md](../mods-src/README.md). |
| `clients/pack-sync.ps1`, `clients/pack-sync.sh` | The update script each device runs before every launch (Windows / Linux). Also shipped in the pack, so devices update it themselves. |
| `clients/packwiz-installer-bootstrap.jar` | packwiz-installer-bootstrap v0.0.3, installed by setup; its SHA-256 is in `installer/config.json`. |
| `clients/mmc-pack.json` | The Prism instance's component list (Minecraft, Fabric Loader). |
| `clients/pack-sources.txt` | The default list of pack addresses (setup writes its own from `config.json`). |
| `installer/config.json` | Read by both installers on every run: server list, pack addresses, Prism version and SHA-256, bootstrap SHA-256, art paths. Changing it changes the next install without a rebuild. |
| `installer/windows/GatehouseSetup.cs`, `GatehouseUninstall.cs` | Windows installer and uninstaller source (C# 5, .NET Framework 4). The `.exe` files are gitignored and published only in releases. |
| `installer/windows/Options.cs`, `Nbt.cs` | Shared by both: the quick-or-custom first screen, the window of choices (with the dependencies between items and the folder pickers), and reading and writing Minecraft's `servers.dat`. |
| `installer/linux/gatehouse-setup.sh`, `gatehouse-uninstall.sh` | Linux installer and uninstaller, including the Steam Deck. |
| `installer/art/*.png` | Steam library art for the October Journey shortcut, made for this pack by `tools/make_art.ps1`. |
| `installer/art-prism/*.png` | Steam library art for the Prism Launcher shortcut: Prism's logo (`source/prism-logo.png`, rendered from the scalable logo that ships with Prism) on a plain background, by `tools/make_prism_art.ps1`. Licence in its `NOTICE.md`. |
| `installer/steam/controller_neptune.vdf` | Steam Deck controller layout ("Minecraft - R4 jump, L4 crouch, trackpad mouse"). |
| `dist/` | Built pack, served to devices. Generated; commit it. |
| `docs/mods.md` | Mod list, generated by `build_pack.py`. |
| `source/gatehouse.json` | Gatehouse's own version and notes (`version`, `changes`: newest first), apart from any pack's. `tools/build_program.ps1` puts it inside the launcher; Gatekeeper fetches it from the repository for its Gatehouse updates tab (a copy built into Gatekeeper is the fallback). |
| `source/gatekeeper.json` | Gatekeeper's own version and notes, same shape. `tools/build_program.ps1` puts it inside Gatekeeper, which shows it on its first screen and gives it to launchers at `GET /v1/about`. |
| `CHANGELOG.md` | Patch notes, generated by `build_pack.py` from `source/changelog.json`. |
| `server/state.json`, `server/changelog.json` | What the server runs (generated by `tools/server_snapshot.py`) and notes about its changes. Shipped as `extras/server-state.json` and `extras/server-changelog.txt` for the pop-up's Server tab; also the source of the server mod list in `docs/mods.md` and `dist/server-mods.json`. |
| `tools/` | See [Tools](#tools). |

## What a device does on every launch

Prism's pre-launch command runs the sync script in the instance's `minecraft` folder (`INSTALL.md` has the
player-facing version). In order:

1. **Sources:** every address in `pack-sources.txt` whose `<variant>/pack.toml` answers within 5 seconds, in order.
   None: wait 5 seconds and check again (the network can still be coming up); still none: log it and start the game
   with what's installed (the pop-up says it couldn't check).
2. **packwiz-installer** (through the bootstrap) brings the instance in line with `index.toml`, with `-g` (no windows:
   with them, a failed download waits on an error dialog for a click). It's pinned to one release (`installerTag` /
   `installer_tag` in the scripts, now v0.5.14): while `packwiz-installer.jar`'s manifest shows that version the
   bootstrap gets `--bootstrap-no-update` and doesn't contact GitHub; otherwise `--bootstrap-update-url` with that
   release. Each run is stopped after 5 minutes (`PACK_SYNC_TIMEOUT` seconds, for testing). A failed or stopped run is
   tried with the next source that answered, then the first once more. On Windows the script uses the console
   `java.exe` beside Prism's `javaw.exe` so it can wait for it. An unexpected error anywhere in the Windows script is
   caught: with mods installed the game still starts (exit 0). On Windows Java runs as `javaw` with no window, so no
   console window opens for it.
3. **Steam extras.** `pack-options.json` (written by setup and the uninstaller from the player's choices:
   `steam_art`, `steam_controller`, `server_entry`; a missing file or key means yes) says which of steps 3 and 4 run.
   With `steam-target.json` present (written by setup: `targets`, one `{grid, appid, controller, art}` per shortcut,
   `art` being `pack` for the October Journey shortcut and `prism` for the Prism Launcher one; files from before there
   were two shortcuts have just `{grid, appid, controller}`, taken as one `pack` target), copies `steam/art/*.png` or
   `steam/art-prism/*.png` to `<grid>/<appid>p.png`, `_hero`, `_logo`, `_icon` and `<appid>.png`, and
   `steam/controller_neptune.vdf` to `<controller>/controller_neptune.vdf` (with its `url` line pointed at itself), and
   makes sure `configset_controller_neptune.vdf` lists that folder. State is in `steam-applied.json`: first apply keeps
   the old file as `*.before-pack`; later a file is replaced only while it still matches the hash last applied, so
   changes made in Steam win. Steam reads these at startup.
4. **Server list.** `extras/servers.json` (made from `installer/config.json`'s `servers`) is merged into `servers.dat`:
   a missing entry is added at the top; a wrong address is corrected only while it's still the one last set (state in
   `servers-applied.json`). Other entries are untouched; the first change keeps `servers.dat.before-pack`.
5. **Mod switches.** `extras/mod-toggles.json` (from mods marked `"toggle"` in `source/mods.json`) lists the mods players
   can switch, with the pack's default; the player's choices are `mods` in `pack-options.json`, set from Mod Menu by the
   October Journey mod. Before packwiz-installer runs, mods this script disabled (`mods-switched-off.json`) are renamed
   back so it finds them intact; afterwards, each mod that's off has its jar (from `packwiz.json`'s `cachedLocation`)
   renamed to `.jar.disabled`. Without a connection, the switches still apply.
   **The launch wrapper.** A pre-launch step can only stop a launch by failing, and Prism then opens its log. So the
   instance also has a wrapper command (`WrapperCommand` in `instance.cfg`, written by setup): `launch-wrapper.exe`
   (Windows; `mods-src/launch-wrapper`, built by `tools/build_wrapper.ps1`) or `bash launch-wrapper.sh`, kept beside
   the sync script (copied from `extras/` each run). On Quit, when the wrapper is set up, the script writes
   `launch-cancelled` and exits 0; the wrapper finds it, removes it and starts nothing (exit 0), so Prism sees a launch
   that ended normally. Otherwise the wrapper runs the game's command untouched (same input, output and exit code; on
   Windows the game is tied to it, so killing it from Prism still ends the game). Without the wrapper in
   `instance.cfg`, Quit fails the step as before.
6. **Self-update.** If `extras/pack-sync.ps1` / `.sh` differs from the running script, it replaces it for next time
   (bash by write-then-rename, because bash is still reading the running file).
7. **Launch pop-up.** Started right after Java is found as `extras/launch-popup.jar --progress <game folder>`
   (run from a copy, `launcher-run.jar` in the game folder: Windows locks a running jar, and packwiz-installer must be
   able to replace the one in `extras/` while the window is open): a
   checklist of the steps, ticked off from the `check` lines in `pack-sync-status.txt` (the mods step shows the last
   line of `pack-sync-java.log`). Before packwiz-installer runs, the script writes `ask|<pack address>`; the launcher
   fetches `update-sizes.json` from there (each indexed file's hash and download size, written by the build beside
   `pack.toml`), compares it with `packwiz.json`, and when something would be downloaded shows the size with Download
   and Skip this time. It answers in `pack-sync-answer.txt`: `wait` while asking, then `go` or `skip|<size>` (skip:
   packwiz-installer isn't run this launch). Nothing to download, or the sizes unreadable: `go` at once. No answer
   begun within 30 seconds: the update runs. The ask line ends `|self-update`: the launcher first compares its own
   jar's SHA-256 with `extras/launch-popup.jar`'s in `update-sizes.json`; if they differ it downloads the pack's,
   checks the hash, writes it beside itself as `launch-popup.jar.new` and exits with code 12. The script moves it into
   place and starts the launcher again (once), and the new one answers the question. The script ends by writing `done|ok|offline|failed`, and the same window becomes the
   summary. Its exit code is the player's choice: 0 Play, 10 Quit (the script exits 1, so Prism doesn't start the
   game), 11 Restart (the script runs itself again, hidden). Without that program yet (a first install), it runs
   `extras/launch-popup.jar <game folder> ok|offline|failed` with the Java found for step 2
   (`javaw.exe` on Windows, so no console opens) and waits for it, so Prism starts the game after it closes. Each step writes how it went to `pack-sync-status.txt`
   (emptied at the start of each run): `check|<ok, updated, off, warn or failed>|<what>|<detail>`,
   `switch|<on or off>|<mod>`, or `note|<text>`. At the start the script also copies packwiz-installer's file record
   `packwiz.json` to `packwiz-before.json` (`{}` on a first install). The pop-up compares the two records to list the
   mods added, removed and updated, naming them from `extras/mod-names.json` (written by the build: each mod's index
   path and title). It shows the checks with coloured dots, then those lists, switches, notes and patch notes, using a
   small JSON reader of its own. It also compares the top version in `extras/changelog.txt` with
   `pack-version-seen.txt`, and then writes the new version there. A new version, or anything changed or failed, shows
   the notes for every version since the one seen (just the newest when none was seen). Otherwise it says "no updates", or that the pack couldn't be reached. It never closes or starts the game by itself, and the scripts wait for it with no time limit; closing the window counts as Quit. The
   top-level `popup_updates` and `popup_no_updates` in `pack-options.json` (set in Mod Menu > Gatehouse Settings; missing
   means on) turn either kind off. With no display it just records the version. For testing, `OJ_POPUP_PNG=<file>`
   saves a picture of the window (and its text in `<file>.txt`) instead of showing it.
8. Everything is logged to `minecraft/pack-sync.log`, and packwiz-installer's output to `pack-sync-java.log`.

The scripts stop at step 1 if no source answers, so a device that can't reach either source just plays the last version
it had (after applying the switches and showing the pop-up).

## Packs a Gatekeeper keeps itself
A Gatekeeper can build a server's pack and hand it to launchers, with no repository involved
(`host/src/.../Packs.java`, `PackBuilder.java`, `PackArt.java`; the Pack tab of a server's Manage window).

- **Where it is kept.** `packs/<server id>/` beside Gatekeeper's settings: `def/` is the definition (the mod list in
  the shape of `source/mods.json`, the notes, `source/files`, `config.json`, `art/`, and `art-auto/` for the Steam
  pictures Gatekeeper makes), `built/` is the pack in its `main` and `deck` variants, `pack.json` says what was
  built last.
- **The builder** writes what `tools/build_pack.py` writes, file for file. Built from October Journey's definition
  it gives the published pack back byte for byte, apart from the files that say where the pack comes from.
- **What launchers fetch**, with the update step and Install they already have:
  `GET /v1/pack/<server id>/profile.json` (the profile Install reads: this address is both its repository and its
  one pack source), `/clients/<file>` (what Install puts in the game folder) and `/main|deck/<file>` (the pack).
  No secret is asked, as none was asked by the pack server before; they answer only on this machine and on its
  Tailscale address. `/v1/catalog` gives that profile for a server with a pack.
- **What every pack carries of the program** (sync scripts, launch wrappers, the launcher's scripts and art, the
  Deck's controller layout, `packwiz-installer-bootstrap.jar`) is inside the program (`pack-extras`, put there by
  `tools/build_program.ps1`), and the launcher a pack carries is the Gatekeeper's own program file. A pack built
  by an older program is built again when Gatekeeper starts.
- **Making one.** *Make a pack for this server* (a Fabric server: its Minecraft and loader versions, no mods yet),
  or *Bring in a pack from a folder* (a definition, or the folder October Journey is built from). *New version*
  takes the notes players read and the next version number. Only Gatekeeper's own window can do these: not the web
  panel, not another Gatekeeper.
- **Mods players get** (`ModList.java`). Gatekeeper reads the jars in the server's `mods` folder (each one's
  `fabric.mod.json`), asks Modrinth which of those exact files it publishes (`POST /v2/version_files` with their
  SHA-512 hashes; no sign-in; answers kept in `packs/<id>/lookup.json`), guesses which ones players need, and the
  host confirms the list, a switch for each. A mod published there is fetched by players from its own page; any
  other is copied into the pack and comes from Gatekeeper. Either way it is checked against the hash of the
  server's own file. Entries the list adds are marked `"from": "server"` in the definition's `mods.json`; mods put
  there by hand are never touched. Nothing reaches players until the pack's next version is built.
- **Pictures for Steam.** Cover, hero, logo, icon and wide: each is one the owner chose (brought to the slot's size)
  or one made from the pack's name and the server's picture, the same file every time for the same name.

## Who a server and its pack are for
Each server is private, shareable or public (`Invites.java`; set on the server's Pack tab, at Gatekeeper's own
window only; `"visibility"` on the server in `host.json`; private when nothing is set).

- **Private:** this machine's launcher, a launcher with Gatekeeper or MCSS control, one allowed to start and stop
  this server, one playing as someone on its whitelist, and one that used an invite the owner made.
- **Shareable:** those, and a launcher that used an invite made by a launcher that has the server.
- **Public:** every launcher that connects to the Gatekeeper.
- A launcher the server is not for isn't told of it in `/v1/catalog` and isn't given its pack (`403`). A suspended
  or blacklisted launcher gets neither, whatever the setting.
- **How a pack's files are guarded.** packwiz-installer can't send a launcher's secret, so a request under
  `/v1/pack/<id>/` is matched to launchers by the address it comes from: the address a launcher last spoke to
  Gatekeeper from with its secret (`ip` in `devices.json`, kept current on every call). On Tailscale an address
  belongs to one device. The machine itself is always given them.
- **Invites.** `POST /v1/invite {server}` (a launcher that has a shareable or public server; the owner's is
  `POST /admin/pack/invite`) answers a code and a link, `gatehouse://invite/<address>?code=ABCDE-FGHIJ`, good for
  seven days and any number of launchers. The other launcher adds the Gatekeeper with it (Add Gatekeeper takes an
  invite) and hands the code in, `POST /v1/invite/accept {code}`. It only makes the server "for" that launcher: no
  whitelist, no permission. A player's invite stops counting while the server is private; the owner's always
  counts. Eight wrong codes from one launcher lock it out of invites for an hour. *Take back invites* clears a
  server's codes and the launchers that came in by one.

## Systems: neighbors, remembered, unknown
A system is what one machine has of the program: its Gatehouse, its Gatekeeper, or both (`Systems.java` in
Gatekeeper, `SystemsBox.java` in the launcher; *Systems* on Gatekeeper's Constellation tab and on the launcher's
Constellation page).

- **One id for a machine.** A Gatekeeper's is the id it signs the history of servers with. The launcher linked to the
  Gatekeeper on its machine takes that id on (`system-local.json` in its folder) and gives it wherever it reports,
  so the two are listed as one system. A machine with only a launcher goes by the launcher's install id.
- **Neighbor:** in a constellation with this system now. For a Gatekeeper: launchers it has let in somewhere,
  Gatekeepers that joined and were allowed in with the systems on their side, and what the Gatekeeper it joined
  counts as its constellation. For a launcher: what each Gatekeeper it is connected to tells it.
- **Remembered:** kept by the owner, which can only be done while the system is a neighbor; it stays listed, with
  its name and its Gatekeeper's address, after the constellation is left. One-sided: each side decides for itself,
  each is shown that the other remembers it, and it gives the other nothing.
- **Unknown:** a launcher that introduced itself to a Gatekeeper and has been let in nowhere.
- **Where it is kept.** `systems.json` beside Gatekeeper's settings (`remembered`, `remembered_by`). A launcher on a
  machine with no Gatekeeper keeps its own `systems.json`; one that has a Gatekeeper beside it asks that Gatekeeper
  (`POST /v1/systems/remember`, which only this machine's own launcher may call).
- **How it travels.** `POST /v1/systems` from a launcher or a Gatekeeper that joined says what it is, what it
  remembers and the systems on its side, and is answered with the systems of the constellation; only for one that
  is in it. A Gatekeeper asks the one it joined every half minute.

## The history of servers
`history.json` beside Gatekeeper's settings holds a line for each server it made, started or stopped looking after,
and each new pack version, with the Gatekeeper's name and address (`History.java`; *History of servers* on the
Constellation tab). Lines are only added, and stay after a server is gone. A Gatekeeper that joined another hands
its lines over and takes that one's (`POST /v1/history`, as the member it is), so a Gatekeeper set up later learns
of every server and Gatekeeper before it by joining. It holds no folders, player names or keys.

## Steam keyboard and controllers

- **Linux on a Steam Deck:** SDL (Minecraft 26's window layer) asks Steam for its keyboard whenever a text box is focused, and
  Chat Lift raises chat by 43% of the screen while it's open.
- **Windows with a Steam controller:** Steam ignores keyboard requests from the game here (and in Big Picture opens a
  large keyboard the trackpads can't use), so nothing is opened automatically: the player presses Steam + X. Chat Lift
  raises chat by 22% (normal Steam window) or 38% (started from Big Picture, `SteamTenfoot=1`). It also turns on
  Controlify's **mixed input** (otherwise each key from Steam's keyboard flips the game to keyboard-and-mouse mode and
  nothing is typed) and turns off Controlify's own on-screen keyboard.
- **Other controllers:** Controlify's on-screen keyboard is turned back on when such a controller becomes the current
  one. Settings changed by hand in Controlify hold until the controller changes.
- Fractions are in each device's `config/chatlift.properties` (`lift_fraction`, `lift_fraction_big_picture`,
  `lift_fraction_desktop`).

## Common changes

Every change ends the same way: `python tools/build_pack.py`, check `git diff`, commit, push. Devices pick it up on
their next launch; nobody has to touch a device.

- **Add or update a Modrinth mod:** add or edit its entry in `source/mods.json` (`title`, `slug`, `filename`, `url`,
  `sha512`, `project`, `version`, all from Modrinth's version page or API). If the server runs it too, update the
  server and record it (next item).
- **After a server change** (installed with the server stopped): `python tools/server_snapshot.py` shows what changed
  in the server's files since `server/state.json`; `--note "..."` (one per line) records the new state and a note in
  `server/changelog.json`. Then build and publish the pack; the launch pop-up's Server tab shows it. The build stops if
  the state changed without a note, and warns when a mod on both sides has different files. The owner's
  "Server stopped" also approves publishing that change's server notes.
- **Remove a mod:** delete its entry. packwiz-installer removes it from devices.
- **Let players switch a mod on and off:** give its entry a `"toggle": {"modid": "<its Fabric mod id>",
  "description": "...", "default": true, "needs": ["<key of a switchable mod it requires>"]}` (`default` and `needs`
  are optional; a bundled jar can also set `"name"`). Every mod that isn't a library has one now. It shows in Mod Menu >
  October Journey, and the pack sync disables it while it, or a mod it needs, is off. A mod the server also runs must
  still let clients join without it (no new items, blocks or menus). Libraries stay unswitchable: other mods would
  fail to start without them.
- **Ship a default setting** that players can still change: put the file under `source/files/config-defaults/`.
- **Change a config every device gets:** put the file under `source/files/config/`.
- **Change the art or Deck controller layout:** edit `installer/art/*.png` (or `tools/make_art.ps1`), the Prism
  shortcut's `installer/art-prism/*.png` (or `tools/make_prism_art.ps1`), or `installer/steam/controller_neptune.vdf`.
- **The two Steam shortcuts** are made only by setup (Steam must be closed, because it rewrites `shortcuts.vdf` when it
  exits; the update runs while Steam is open). Their names are `game_shortcut_name` and `shortcut_name` in
  `installer/config.json`, plus `" - <kind of device>"` (PC, Laptop, Steam Deck, ROG; installs made before 1.9.17 used the computer's name). Setup recognises the October Journey one by `--launch <instance id>`
  in its launch options, and the Prism one as a Prism shortcut without `--launch`. When the instance is there but the
  October Journey shortcut isn't, setup's first choice adds only it (`--yes --add-shortcut` for scripts). Testing:
  `--config=file:///.../config.json` with `repo_raw` pointed at a local copy or a local web server, plus
  `--prism-root` and `--steam-root`.
- **Change the server address, or add a public one:** edit `servers` in `installer/config.json`. Both new installs and
  every device's next launch pick it up.
- **Change the sync scripts:** edit `clients/pack-sync.*`. Devices run the new copy from their second launch after the
  push (the first launch downloads it). Keep `pack-sync.sh` with LF line endings. Bash can't be tested on Windows; a
  Linux machine with `bash -n` is the minimum check.
- **Bump the pack version** (`pack.version` in `source/mods.json`) for anything players would notice, and add an entry
  for it at the top of `source/changelog.json` (`version`, `date`, `notes`: short sentences written for players). The
  build refuses to run without it. Players see these notes in the launch pop-up.
- **Change the launch pop-up:** edit `mods-src/launch-popup`, run `tools/build_program.ps1`, then build the pack.
- **Four kinds of notes, four places.** A change to the pack goes in `source/changelog.json` (a server's Client updates
  tab). A change to the server goes in `server/changelog.json` (Server updates). A change to Gatehouse itself goes in
  `source/gatehouse.json`, with its `version` raised (the Gatehouse updates tab on the launcher's main window, and in
  Gatekeeper). A change to Gatekeeper goes in `source/gatekeeper.json`, with its `version` raised (Gatekeeper's first
  screen, and the Gatekeeper updates tab beside its servers in a launcher). The launcher still travels inside each pack,
  so a launcher change also needs a pack version, whose note can simply point at the Gatehouse updates tab.
- **The update inside the Gatehouse window (the first step of one program).** Launch in the main window runs the
  pack's update script itself (`Home.startSync`: `pack-sync.ps1` / `.sh` with `GATEHOUSE_EMBED=1`, and the Java the
  launcher runs on as `INST_JAVA`), and shows the checklist in its own window (`LaunchPopup.checklist` given that
  window, which is also what `--progress` uses in a window of its own). Run that way the script opens no window, asks
  about the download through the same status and answer files (no `self-update`: the main window updates Gatehouse
  itself), and at the end writes `done|<status>` and `pack-sync-fresh.txt` (`<unix time>|<status>`) instead of waiting
  for Play. Gatehouse then has Prism start the instance; Prism runs the script as always, which finds that note less
  than three minutes old, keeps the first run's status file and records, and goes straight to the launcher window.
  Without the note (the script ended early, a pack started from Prism itself) everything is as before. A script that
  doesn't mention `GATEHOUSE_EMBED` (from before pack 1.18.0) is not run this way. `OJ_HOME_LAUNCH=<game folder>`
  runs just this part, in a window that isn't shown, for tests.
- **The server's launcher inside the Gatehouse window (the second step).** When the pack's script also mentions
  `GATEHOUSE_PLAY` (pack 1.19.0 on), the main window goes on after the checklist to show the launcher itself
  (`LaunchPopup.summary`, with the main window as the window it "reuses"). That code ends by closing its window; the
  main window is a `HostFrame`, whose `dispose()` runs `LaunchPopup.embedClose` instead while a page is shown in it,
  and that tells the page's own listeners it was closed (stopping its timers and letting `summary` return) without
  closing anything. Then: Gatehouse button (`embedBack`): the main window's own page is put back. Restart: the script
  is run again, here. Quit, or the window's X: Gatehouse closes. Play: `pack-sync-fresh.txt` is written as
  `<unix time>|play`, the window closes and Prism starts the instance; Prism's run of the script sees "play" and
  ends at once, so the game starts. A script with `GATEHOUSE_EMBED` only (pack 1.18.0) still gets the first step.
  `OJ_HOME_LAUNCH_PAGE=1` with `OJ_HOME_LAUNCH` takes the page's pictures too.
- **Nothing to update.** The ask line ends in `|current-ok` when the script understands the answer `current`. The
  launcher gives it when `pending()` finds every file of the served `update-sizes.json` installed with the same hash,
  the files themselves on disk, and nothing installed that the pack no longer has. The script then skips
  packwiz-installer (unless `pack-sync-full.txt`, the time of its last full run, is missing or over a week old). The
  script also looks only for the first source that answers; the others are asked if the update from it fails.
  `Home.startSync` empties the status file before starting the script, so the checklist can't read the last run's
  `done`.
- **Right click in the launcher's windows (Steam Deck).** The pack's layout makes a press of the right trackpad the
  right mouse button, for the game. `Nav.rightClickIsLeftClick` (on Deck hardware; `OJ_RIGHT_AS_LEFT=1` for tests)
  puts an event queue in front of the launcher's windows that hands every right-button mouse event on as a
  left-button one. Steam and the layout aren't involved.
- **Removing a Gatekeeper in the launcher.** `Home.forgetKeeper` takes it out of `gatekeepers.json`, adds it to
  `gatekeepers-removed.json` and deletes this device's folder for it under `keepers/`. While it is on the removed
  list, packs that name it are treated as packs with no Gatekeeper (Other packs) and don't bring it back. Add
  Gatekeeper with the same address takes it off the removed list.
- **Publishing Gatekeeper.** `tools/build_pack.py` copies `host/oj-host.jar` to `dist/gatekeeper/` with
  `gatekeeper.json` (its version and checksum, and the Gatehouse version of this build). It stops if the jar's own
  notes aren't the current `source/gatekeeper.json`: run `tools/build_program.ps1` first. Because running Gatekeepers
  hold themselves and their launchers to what is published, building the pack publishes Gatekeeper too: install the
  new Gatekeeper on the server machine before building, or its servers won't start until it's updated.
- **Gatehouse by itself.** `installer/gatehouse-install.ps1` (Windows; also Gatekeeper, or both) and
  `installer/gatehouse-install.sh` (Linux, Steam Deck) fetch the launcher and the scripts beside it from
  `<source>/<main or deck>/extras`, checking each against `update-sizes.json`, into `<Prism's folder>/gatehouse/app`:
  `extras/` laid out as in a pack, and `pack-sources.txt` saying where a newer launcher is found. `Notes.app` is that
  folder; the self-update treats it like a pack (and, when it is the copy being updated, refreshes the scripts in it,
  `Notes.APP_FILES`); `gatehouse-launcher.ps1` / `.sh` know when they are run from there and count its launcher among
  the candidates; `PackInstall.equip` takes a plain server's files from it when no pack is installed. For Gatekeeper
  the Windows script fetches `gatekeeper/oj-host.jar` (checked against `gatekeeper.json`) and
  `installer/keeper/install-keeper.ps1`, and runs that. Java, when none is found: the Temurin 21 runtime from
  Adoptium's API, with its checksum, into `%LOCALAPPDATA%\Gatehouse\jre`, which the launcher's script also searches.
  Steam: the Windows script asks the launcher itself, `java -jar launch-popup.jar --steam from <file>` (lines: `add`,
  name, program, start folder, options; or `remove`, name), which uses `SteamUpdate.addShortcut` / `removeShortcut`
  (`--config`, `--art`, `--flatpak`; exit 3 when Steam is running). The Linux script does the same with `python3` on
  the host, since Steam's files can't be seen from inside Prism's Flatpak. Both replace a shortcut of the same name,
  renumber the list, keep the old list as `shortcuts.vdf.bak-gatehouse`, and copy or delete the five art files in
  `grid`. `-Uninstall` / `--uninstall` (with `-Purge` / `--purge`) undo what was installed; the script copies itself
  into `gatehouse/app` for that. Released as `gatehouse-install.zip` (the two scripts, `Install Gatehouse.cmd` and
  `Uninstall Gatehouse.cmd`).
- **Options and Uninstall in the launcher.** The wheel on the main window (drawn by `SteamLook.gear`; the fonts
  don't all have the character) shows the Options page. `Home.uninstallPlan` decides what Uninstall does: `script`
  (installed by itself: `gatehouse/app/gatehouse-install.ps1` / `.sh` with uninstall and yes, run and its output
  shown, then the launcher closes; on Windows the copy it ran from is deleted a moment later), `program` (it came
  with a pack on Windows: the `UninstallString` under `HKCU\...\Uninstall\Gatehouse*` or `OctoberJourney`), or, with no
  uninstaller on record, `script` again with the install script the launcher carries inside itself (put there by
  `tools/build_program.ps1`, written to the temporary folder and run with `-WithPack` / `--with-pack`: it takes out
  what is Gatehouse's own and leaves the instances), or `tell` (inside Prism's Flatpak: the command is shown). The word `confirm` has to be
  typed first. `OJ_UNINSTALL_DRY` shows the command instead of running it; `OJ_UNINSTALL_EXTRA` adds options to it.
- **One program, two halves.** Gatehouse (the launcher, "Play") and Gatekeeper (the server machine's part, "Host")
  are built into one file by `tools/build_program.ps1`, starting at `dev.gatehouse.Main` (`mods-src/program`), which
  only decides which half was asked for: `--serve`/`--panel` and friends are the Host half, `--choose` (or nothing at
  all, for a file that isn't a Gatekeeper's own) is the first screen with a card for each half, anything else is the
  Play half. The same bytes are published under both old names (`extras/launch-popup.jar` in the pack,
  `gatekeeper/oj-host.jar`), so each half still updates the way it did, and both carry one version number (the build
  refuses two). One installer, same-machine linking and one combined update followed in later versions.
- **Two ways Gatekeeper runs servers.** `ServerHost` is the seam: `McssHost` (through MC Server Soft, Windows) and
  `DirectHost` (Gatekeeper starts the server's own files and keeps its console; always on Linux and SteamOS, where
  there is no MCSS). [Gatekeeper internals](gatekeeper-internals.md) has the details. What a system or a host kind can't do stays listed, marked
  "Not supported on <system>".
- **The setup guides.** `mods-src/look/.../Wizard.java` is the page both programs show: steps down the left with a
  mark each (`ok`, `todo`, `warn`, `info`, `wait`), one step on the right with its text, buttons and Back / Next; the
  steps are asked for again every three seconds while it's showing, and it is redrawn only when something changed.
  Gatehouse builds its steps in `Home` (Prism found, an account in Prism's `accounts.json`, a Gatekeeper on the
  list, one that answers, a server installed); Gatekeeper's come from the running Gatekeeper (`GET /admin/wizard`,
  `wizardSteps`: Host setup's checks, plus MCSS found, a server in it, and its web API answering). Both open it from
  Options; Gatehouse also when nothing is set up yet, Gatekeeper the first time its window opens with steps still to
  do (`setup-guide-shown.txt`). **Pictures:** put `<step key>.png` in `source/wizard/gatehouse/` or
  `source/wizard/gatekeeper/`; the builds put them inside the programs, and a step that has one shows it above its
  text. Step keys: Gatehouse `welcome`, `prism`, `account`, `gatekeeper`, `connect`, `install`, `play`; Gatekeeper
  `welcome`, `tailscale`, `tailscale_on`, `tailscale_stay`, `mcss`, `mcss_server`, `mcss_api`, `setup`, `mcss_key`,
  `ports`, `whitelist`, `invite`, `pairing`, `done`. `OJ_WIZARD_STEP=<key>` picks the step for the test pictures
  (`-wizard.png`).
- **Connect to this Gatekeeper.** `Connect.java`: the steps between having a pairing and the Gatekeeper answering,
  looked at every 2.5 seconds: a Tailscale address on this device (an interface address in 100.64.0.0/10, which also
  works inside Prism's Flatpak), the Gatekeeper's port reachable, and its answer to this device's introduction.
  Whether the owner's invite was accepted can't be seen, so that step ticks with reachability. A pairing may carry the
  invite: `ts=` in the link, `tailscale_share` in the file, kept with the Gatekeeper in `gatekeepers.json`; only a
  `https://login.tailscale.com/` link is kept or opened (`Connect.invite`). Shown when an added Gatekeeper doesn't
  answer (it is now remembered all the same) and from the Connect button on one that isn't answering.
  `OJ_CONNECT_FAKE=<tailscale><reach><pair>` (each 0 or 1) stands in for the checks; `OJ_CONNECT_SHOT=<pairing>` with
  `OJ_POPUP_PNG` writes `<file>-connect.png`.
- **The rest in the one window (the third step).** `Home.embeddable` says whether a game folder's launcher can be
  shown in the main window: `pack` (its `pack-sync` script mentions `GATEHOUSE_PLAY`), `plain` (no pack; its
  `plain-launch` script mentions it), or not. A plain server has no update: Launch goes straight to its page, and
  Play leaves the same `<unix time>|play` note, which `plain-launch.ps1` / `.sh` take as "start the game". Opened
  with `--play <instance>` (a pack's own Steam shortcut), the main window opens on that Gatekeeper's servers and
  presses that pack's Launch itself (`autoLaunch`), leaving a `direct-launch` note before the launcher's page, which
  then goes straight to Play when there is nothing to show and the server is ready. What can't be embedded (an older
  script, a launch from Prism Launcher itself) uses the window hand-over below, which stays as the fallback.
- **One window to the eye.** The main window and a server's launcher are two programs: the second is started by
  Prism Launcher (the pack's update, before the game). On Launch the main window writes where it is to
  `gatehouse-window.txt` in the game folder and stays up; the pack's first window (the checklist, else the launcher)
  opens in that place, at least that big, and removes the note, which is the main window's cue to go (it also goes
  when Prism finishes or after 30 seconds). The Gatehouse button writes the same into `back-to-menu`, and the main
  window opens there.
- **Noticing updates while open.** Both windows look again every two minutes (`Notes.pollMillis`;
  `OJ_UPDATE_POLL_MS` for tests). The main window compares the served launcher's hash with its own (`Notes.newer`). A
  server's launcher compares the served `update-sizes.json` with what's installed (`Notes.packNewer`, the same count
  the download question uses); what was served when the window opened is the starting point, so only a change after
  that is announced. `OJ_GATEHOUSE_NEWER` (`version|bytes`) and `OJ_PACK_NEWER` (`version|files|bytes`) pretend one
  was found, for pictures.
- **Gatehouse updating itself.** Opened directly (the Gatehouse shortcut, no server chosen), the launcher looks where
  the installed packs get their updates (`pack-sources.txt`), compares the hash of `extras/launch-popup.jar` in
  `update-sizes.json` with its own, and if they differ says so under the heading (the version from `gatehouse` in
  `update-sizes.json`, which the build takes from `source/gatehouse.json`, and the size) with Update Gatehouse and
  Later. Update fetches the new one, checks it against that hash, puts it in place and ends with code 12. `gatehouse-launcher.ps1` / `.sh` see the 12 and open the newest one, each
  time it happens (up to eight; `GATEHOUSE_RELAUNCH=loop` tells the launcher the script does this; under an older
  script that reopened only once, the launcher opens the new one itself the second time, `Notes.reopen`). The Windows
  script waits for the launcher's own process only (not Prism, which it may have started) and copies the jar to run
  under one of two names, retrying while the last copy is still locked. A source whose `gatehouse` version is older
  than the running launcher is never taken as an update (`Notes.servedIsOlder`). `--self-update <instances folder>` does only the looking, for tests.

### Changing one of the pack's own mods
1. Edit the source under `mods-src/<mod>/` and raise the version in its `fabric.mod.json` (or `gradle.properties` for
   handofffix).
2. `powershell -ExecutionPolicy Bypass -File tools\build_mod.ps1 <mod>` writes `source/files/mods/<mod>-<version>.jar`
   and prints its SHA-512.
3. In `source/mods.json`, point the mod's entry at the new `filename` and `sha512`; `git rm` the old jar.
4. Build the pack, commit, push.

### What an update must never break
Any version ever published has to be able to reach the newest one in a single step, with the updater it has: it
can't be given a better one first. So what old versions rely on is fixed, and only ever added to.

- **Where they look.** `dist/gatekeeper/gatekeeper.json` and `oj-host.jar` on each pack server and on GitHub's
  `main` (a Gatekeeper), and `<source>/main|deck/update-sizes.json` with `extras/launch-popup.jar` (a launcher, from
  each installed pack's `pack-sources.txt`). Those paths and the repository's address stay.
- **What they read.** `gatekeeper.json`: `version`, `sha256`, `size`, `gatehouse`. `update-sizes.json`: `gatehouse`,
  and `files["extras/launch-popup.jar"].hash` (SHA-256) and `.size`. Fields are added (`needs`), never renamed or
  removed, and an old reader must be able to ignore a new one.
- **What they do with it.** A Gatekeeper fetches the jar, checks the SHA-256, checks it holds
  `dev/octoberjourney/host/HostHelper.class`, `META-INF/MANIFEST.MF` and `gatekeeper-notes.json`, swaps it in with
  a script the OLD version wrote, starts it with `-jar oj-host.jar --serve`, and expects `GET /admin/update` on its
  port to answer 200 within 30 seconds, or it puts the old jar back. A launcher replaces `extras/launch-popup.jar`
  and runs it again. So the new program must: keep those class and entry names, start from that command line with
  the settings files the old version left, need no newer Java than 17, and answer that call quickly.
- **Settings and records** (`host.json`, `devices.json`, `gatekeepers.json`, ...) are read by the new version as
  any older version wrote them: a key that is missing means what it meant then.
- **`needs`** (`source/gatekeeper.json`) is the oldest version that still works with the one being published. It
  holds an older client up; it never stops one updating. Raise it only when a launcher and a Gatekeeper, or the two
  halves, would misunderstand each other, or for a fix that can't wait.

`tools\update_check.ps1` tries it for real: it takes old versions out of this repository's history, starts each
in a stand-in folder, and passes when each has updated itself to the build about to be published (`-All` for every
version). Run it before publishing anything that touches how updates are published, fetched or applied.

There are other ways in if a version's own updater ever fails: a launcher also gets the program with a pack's own
update at Launch, and from a Gatekeeper (`/v1/program`); a Gatekeeper gets it from the launcher on its machine
(`Main.pairUpdate`); and the install scripts replace the program and keep the settings.

### Releasing the installers and uninstallers
The installers download everything else at run time, so they only need a new release when their own code changes.
1. Windows: build both with the compiler that ships with Windows:
   ```
   C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /optimize /target:exe /out:installer\windows\GatehouseSetup.exe /r:System.Web.Extensions.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll installer\windows\GatehouseSetup.cs installer\windows\Options.cs installer\windows\Nbt.cs
   C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /optimize /target:exe /out:installer\windows\GatehouseUninstall.exe /r:System.Web.Extensions.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll installer\windows\GatehouseUninstall.cs installer\windows\Options.cs installer\windows\Nbt.cs
   ```
2. Test without touching your real setup: run setup, then the uninstaller, both with
   `--yes --prism-root=<temp dir> --steam-root=<temp dir>` (the Linux scripts take the same flags; the temp Steam
   folder needs a `config/loginusers.vdf` with one account). Also try `--skip=...` on each, and check that only the
   chosen items were added or removed. On Windows, `--screenshot=<file.png>` saves a picture of the options window and
   closes it, to check its layout.
3. If an uninstaller changed, put its new SHA-256 in `installer/config.json` (`uninstall_windows`, `uninstall_linux`):
   setup saves the uninstaller on each device only when the hash matches, and otherwise skips it. The Linux one is
   fetched from `main`, the Windows one from the latest release.
   The two zips are made, not kept: `gatehouse-install.zip` is `installer/gatehouse-install.ps1`,
   `gatehouse-install.sh`, `Install Gatehouse.cmd` and `Uninstall Gatehouse.cmd`; `gatehouse-keeper.zip` is
   `host/oj-host.jar` (the published build) with `installer/keeper/install-keeper.ps1` and `install-keeper.sh`.
   Make them again whenever those scripts change, and the second also when it has fallen far behind the program.
4. Upload all four files to the release (`gh release upload setup-v1 installer\windows\*.exe
   installer\linux\*.sh --clobber`, or a new release). The README links to `releases/latest/download/<file>`, so keep
   the file names.
5. If the tested devices changed, update "Tested on" in `INSTALL.md`.

## Tools

| Tool | Use |
| --- | --- |
| `tools/build_pack.py` | Builds `dist/` and `docs/mods.md` from `source/`, the art, the layout, `installer/config.json` and the sync scripts. |
| `tools/build_mod.ps1 <mod>` | Builds `mods-src/chatlift`, `handofffix` or `octoberjourney` into `source/files/mods/`. |
| `mods-src/launch-popup/.../Connection.java` | The Connection tab: the checks (this device's Tailscale address from its network interfaces; `tailscale status --json` where the command can be run; connections to the server machine's ports) and what to do about each. `java -jar launch-popup.jar --server <game folder> connection` prints them. |
| `mods-src/launch-popup/.../ServerControl.java` | The Server controls tab: status (Minecraft's server list ping for online, ping and players; panel port; MCSS API; Gatekeeper's port, so a PC with MCSS closed still counts as awake), Wake on LAN, Start, Stop, opening the panel, sign-in. `java -jar launch-popup.jar --server <game folder> status\|wake\|start\|stop` runs one of them from a terminal (`OJ_WAKE_TEST=host:port` also sends the wake signal there, for testing). |
| `tools/build_program.ps1` | Builds the one program, both halves (`mods-src/launch-popup`, `host/`, `mods-src/look`, `mods-src/program`; plain Java 17), into `source/files/launch-popup.jar` and `host/oj-host.jar` (the same file). Gatekeeper is installed by `installer/gatehouse-install.ps1` / `.sh` ([Gatekeeper internals](gatekeeper-internals.md#installing-and-running)). |
| `tools/mod_sizes.py` | Records each Modrinth mod's download size (`size` in `source/mods.json`) from Modrinth's listing, for the launcher's update size question. Run it after adding or updating a mod; the build refuses a mod without one. |
| `tools/server_snapshot.py` | Records what the server runs from its files (`server/state.json`) with a note for players (`server/changelog.json`); `--check` reports drift. |
| `tools/serve.ps1` | Serves `dist/` on the Tailscale address. Run at logon by the scheduled task "Minecraft pack server"; waits for Tailscale and restarts the server if it stops. |
| `tools/make_art.ps1` | Draws the October Journey shortcut's Steam library art into `installer/art/`. |
| `tools/make_launcher_art.ps1` | Draws the Gatehouse launcher shortcut's art (an original gatehouse mark) into `installer/art-launcher/`. |
| `clients/gatehouse-launcher.ps1`, `.sh` | What the Steam shortcuts run (shipped in `extras/`): they find Java and the newest `launch-popup.jar` among the installed packs and run it with `--home <instances> --prism <Prism> [--play <instance>]`. The launcher lists the packs, or with `--play` leaves a `direct-launch` note in the game folder and starts the instance through Prism; the pack's window is then skipped when there's nothing to show and the server is online. |
| `mods-src/look/.../SteamLook.java` | The look both the launcher and Gatekeeper use (compiled into both): Java's Metal look with dark colours, and flat buttons, tabs and scroll bars. |
| `installer/keeper/install-keeper.ps1` | Installs Gatekeeper from `gatehouse-keeper.zip` on any Windows machine (a readable script, not a program). |
| `tools/make_prism_art.ps1` | Draws the Prism Launcher shortcut's art into `installer/art-prism/` from Prism's logo. |
| `tools/steam_shortcut.py` | Adds or renames a non-Steam shortcut in `shortcuts.vdf` by hand (Steam closed). The installers do this themselves. |
| `tools/move-prism-out-of-sandbox.ps1` | One-off repair from 2026-11-14 (Prism data written into another app's sandboxed AppData). Kept for reference. |
| `tools/run_tests.ps1`, `tools/update_check.ps1` | The tests run before every publish ([For developers](developers.md#tests)). |
| `tools/setup_signing_key.ps1`, `tools/sign-release.workflow.yml` | The release key and the workflow that signs each publish ([For developers](developers.md#signing)). |
| `tools/version_history.py` | Writes `docs/version-history.md` from the program's notes; run by `build_pack.py`. |

On the owner's PC there's no system Python; the build is run with a Python runtime kept apart from the system.
