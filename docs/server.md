# The October Journey server

What the server is, how it's set up, and everything that runs with it. The server itself (its files, world and the
Jarvis source) isn't in this repository yet; this page describes it until it gets a repository of its own.
To join, see [INSTALL.md](../INSTALL.md).

## At a glance

| | |
| --- | --- |
| **Game** | Minecraft Java Edition 26.3 with Fabric Loader 0.19.5 |
| **Mode** | Survival, Easy, PvP on, no hardcore |
| **Who can join** | Whitelisted players only, with a Microsoft account (online mode, secure chat profiles) |
| **Address** | `100.91.134.102:25565`, reached through [Tailscale](https://tailscale.com/download) only; there's no public address yet |
| **Players** | Up to 20 |
| **Host** | The owner's Windows desktop (Windows 10, Intel Core i7-12700K, 32 GB; full specs in [INSTALL.md: Tested on](../INSTALL.md#tested-on)), run with MCSS (MC Server Soft) 13.10 on Java 25 (Eclipse Temurin), 2 GB starting / 4 GB maximum memory |
| **Client** | The October Journey pack: [docs/mods.md](mods.md) lists every client mod |

## World settings

| Setting | Value |
| --- | --- |
| Difficulty | Easy |
| Game mode | Survival (not forced) |
| PvP | On |
| World type | Normal, with structures; Nether on |
| View / simulation distance | 10 chunks / 10 chunks |
| Spawn protection | 16 blocks |
| Command blocks | Off |
| Flight | Not allowed |
| Idle kick | Off |
| Pauses when empty | After 60 seconds |
| Resource pack | None required |

## Mods on the server

| Mod | What it does here | Also in the client pack |
| --- | --- | --- |
| **Jarvis** (custom build `0.17.0+estate.74`) | The server's butler: see [Jarvis](#jarvis) below | No (server only; nothing to install) |
| **Fabric API** 0.163.0 | The library most Fabric mods need | Yes |
| **Roughly Enough Items** 26.3.824, brewing fix | Recipe lookup; the server side supplies recipes, including brewing ([patched](../mods-src/README.md#rei-brewingfix-roughly-enough-items-brewing-fix) so brewing recipes load) | Yes, same build |
| **Nearby Chest Crafting** 2.1.1 with **SeamlessLib** 2.0.2 | Craft with items from nearby chests | Yes, switched off by default; players turn it on in Mod Menu, and can join without it |
| **Jade** 26.3.5 | Server-side data for the "what am I looking at" tooltip | Yes |
| **AppleSkin** 3.0.10 | Server-side food and saturation data for the hunger tooltips | Yes |
| **Architectury** 22.0.5, **Cloth Config** 26.3.159 | Libraries for other mods | Yes |
| **Lithium** 0.26.2 | Game-logic performance | No (server only) |
| **FerriteCore** 9.0.0 | Memory use | Yes |
| **Krypton** 0.3.2 | Network performance | No (server only) |
| **spark** 1.10.187 | Performance profiler for the owner | No (server only) |

The exact list, with versions and checksums, is in [`server/state.json`](../server/state.json), made from the
server's own files by `tools/server_snapshot.py`, and printed in [docs/mods.md: On the server](mods.md#on-the-server).
Mods that are on both sides have to stay on matching versions; the pack build warns when they don't.

## Waking and starting the server

The host PC listens for Wake on LAN on its wired network card (Intel I225-V; Windows allows it to wake the PC, magic
packet only). Waking from sleep needs nothing else; waking from fully off would also need **Power On By PCI-E** in the
motherboard's BIOS and Windows' Fast Startup turned off. A sleeping PC also takes the pack server offline; devices
then update from GitHub.

The launch pop-up's Server controls tab can wake it, start and stop the Minecraft server (MCSS actions 2 and 1; 1 is
a normal stop that saves the world, not a kill), and open the MCSS web panel
(`installer/config.json` `server_control`: panel address, the server's MCSS id, the game address, the network card's
address and the home broadcast address). Starting goes through the panel's API (`POST
/api/v2/servers/<id>/execute/action`, action 2) with an API key the owner makes per player in the panel (API Keys),
limited to this server and to viewing and starting. Players enter it in the pop-up's **Server access**; it stays in
`server-access.json` on their device. Easier: **Sign in** there with a panel account (the panel's own `POST /auth`); the
pop-up keeps the session key the panel returns, never the password. The wake signal is a broadcast, so it only reaches the PC from the home
network, unless a player has an internet wake address (a router that passes wake signals from the internet on to the
home network); that address is also kept on the device, not in this public repository.

## Gatekeeper (starting and closing MCSS)

Setting Gatekeeper up step by step, for a host: [How to use Gatehouse and Gatekeeper](guide.md#for-hosts-gatekeeper).
This section is the reference.

MCSS's web panel only exists while MCSS is running, so starting MCSS itself needs something else on the PC: the host
helper (`host/`, built by `tools/build_host.ps1`). It is installed from **gatehouse-keeper.zip** (on the releases
page): unpack it and run `install-keeper.ps1`, a plain PowerShell script you can read first
([`installer/keeper/install-keeper.ps1`](../installer/keeper/install-keeper.ps1)); it needs Java 17 or newer, downloads
nothing, and `-Uninstall` removes it. A new install goes to `%LOCALAPPDATA%\Programs\GatehouseKeeper`; the owner's
own is in `C:\Minecraft\GatehouseKeeper` (kept current from this repository by `tools/install_host.ps1`).
**Linux and SteamOS.** The same Gatekeeper runs there, installed by `install-keeper.sh` from the same zip
([`installer/keeper/install-keeper.sh`](../installer/keeper/install-keeper.sh); on a Steam Deck: Desktop Mode,
Konsole, `bash install-keeper.sh`), or fetched and checked for you by `bash gatehouse-install.sh --gatekeeper`. It
asks for no system password: Gatekeeper goes to `~/.local/share/GatehouseKeeper`, runs as a systemd user service
(`gatehouse-keeper`, `KillMode=process` so the script that restarts it after an update outlives it; an entry in
`~/.config/autostart` where there is no systemd), and gets an entry in the applications menu. It needs Java 17 or newer (on SteamOS, the Java that Prism Launcher fetches is found and used). `--uninstall`
removes it. Which system it is on is decided once (`HostHelper.SYSTEM`: `windows`, `linux`, or `steamos` from
`/etc/os-release`; `OJ_OS` overrides it for tests), and what a system can't do stays listed where it would be, as
**Not supported on SteamOS** (or Linux): Host setup has such lines (state `na`), and the Machine wake/sleep permission
is greyed out. Differences:

| | Windows | Linux | SteamOS |
|---|---|---|---|
| Starts at sign-in | scheduled task | systemd user service | systemd user service |
| Updates itself | `update-restart.ps1` | `update-restart.sh` | `update-restart.sh` |
| Closing MCSS | asks its window to close | asks it to end (SIGTERM) | asks it to end (SIGTERM) |
| "Tailscale runs before anyone signs in" | Run unattended | `tailscaled` service enabled | `tailscaled` service enabled |
| Sleep from a launcher | yes | yes (`systemctl suspend`) | not supported |
| Gatekeeper in the Steam library | from `gatehouse-install.ps1` | not supported | not supported |
| Options > Uninstall | in a window of its own | in Options, its answer shown | in Options, its answer shown |

Tested so far only from Windows: Gatekeeper told it is on Linux or SteamOS (`OJ_OS`), and the scripts in a scratch
home without systemd. Not yet run on a real Linux machine or a Steam Deck.

**Running the servers without MCSS (`DirectHost`).** MC Server Soft is Windows only (its own site says so), so
on Linux and SteamOS Gatekeeper runs the Minecraft servers itself; on Windows `"host": "builtin"` in `host.json`
chooses the same. `HostHelper.builtin()` decides, and `host` is then a `DirectHost` instead of a `McssHost` (both
are `ServerHost`). What it does:

- **Servers** are folders inside one folder (`servers_dir`; `~/minecraft-servers`, or `C:\Minecraft\servers`).
  Server setup lists the ones that look like a server (a server jar, `run.sh` or `server.properties`).
- **Starting** one runs, in its folder, the first of: the server's `"start"` in `host.json` (a whole command line);
  `sh run.sh nogui` on Linux; `<java> -Xmx<memory> -jar <jar> nogui`, with the server's `"java"` / `"memory"`, else
  `server_java` / `server_memory`, else Gatekeeper's own Java and 4G. The jar is `fabric-server*.jar`,
  `quilt-server*.jar`, `server.jar`, a Paper/Purpur/Spigot/Forge jar, or the only jar there. It is refused, with the
  reason, when there is no program or `eula.txt` doesn't say `eula=true`. Nothing is downloaded or accepted.
- **Its console** is kept: output is read (the server is "on" once it prints `Done (...)! For help`; the last six
  lines go in `host.log` if it ends unasked), and `stop`, `whitelist ...` and `kick ...` are typed into it.
- **A server that outlives its Gatekeeper** (a crash) is recognised by `gatekeeper-server.pid` in its folder
  (process id and start time) and shown as on, without a console: on Linux it is stopped with the ordinary request
  to end (it saves and shuts down); on Windows Gatekeeper refuses and says to `/stop` it in the game. For the same
  reason `applyUpdate` refuses while a server it started is on.
- **MCSS's own things** stay listed, greyed and marked "not supported on Linux" (`noMcss()`): Start and Close MCSS,
  Sign in, Set key, the MCSS control permission, and the guide's `mcss`, `mcss_api` and `mcss_key` steps (state
  `na`). The guide gains `eula`. Launchers still show an MCSS line; its buttons answer with the same words.

**Making and managing those servers (`ServerTools`, `ServerToolsUi`).** What MCSS does for its servers, for the
ones `DirectHost` runs; every call is `/admin/tools/...` and is refused (with "MCSS does this here") under `McssHost`.

- **New server** (`/admin/tools/new`): `vanilla` from Mojang's version list (SHA-1 checked), `paper` from PaperMC's
  `fill.papermc.io/v3` (SHA-256 checked), `fabric` from `meta.fabricmc.net` (its server launcher; no checksum is
  published), `forge` (an installer the owner picked, run with `--installServer`), `file` (a jar the owner picked).
  It runs in the background (`making` says how far it is); a failure removes the folder it made. Refused without
  the owner's EULA tick; `eula.txt` is written only with it. Java: the one chosen, else the lowest found that the
  version's `javaVersion.majorVersion` allows (found by the `release` file in each Java's folder; none is installed).
  `source_mojang`, `source_fabric`, `source_paper` in `host.json` replace the three lists (the tests' stand-in).
- **A server's entry in `host.json`** holds its settings: `memory`, `java`, `start`, `autostart`, `keep_online`
  (`crash`: started again when it ends unasked, at most three times in ten minutes), `backup_what` (`world`/`all`),
  `backup_keep`, `backup_dir`, `tasks`.
- **Console**: `DirectHost.Run.lines` keeps the newest 2000 lines; `/admin/tools/console` hands out what is new since
  a line number. `restart()` stops and starts again; `kill()` ends it at once.
- **Forge/NeoForge** are started as `<java> -Xmx<memory> @user_jvm_args.txt @libraries/.../win_args.txt|unix_args.txt
  nogui` when that file exists (before `run.sh`), so the chosen Java and memory apply and Windows needs no `run.bat`.
- **Backups**: `save-off`, `save-all flush`, wait for "Saved the game" (up to a minute), zip, `save-on`. A server
  whose console was lost has to be stopped first. Only zips named `backup_*.zip` in the backup folder are pruned.
- **Tasks**: checked every 20 seconds while Gatekeeper runs; `daily` at `HH:MM`, `every` N minutes (counted from when
  the list was saved or Gatekeeper started), or `manual`.
- **Not replicated**: MCSS's web panel, API keys and users, Bedrock and proxy server types, plugin management.

**Both on one Windows machine.** `host.json` `"native_servers": "true"` (the tick box on the first screen,
`/admin/tools/native`) lets Gatekeeper run servers of its own beside MCSS's. A server entry with `"native": true` is
then run by `HostHelper.DIRECT` (a `DirectHost`); the others by `host` (the `McssHost`). `sh()` picks per server and
is what every server-level call uses (`serverStatus`, `serverAction`, `command`, `diagnose`, starting); `host` is
still MCSS itself (running, start, close, sign-in). `isNative()` gates Manage, backups, tasks and autostart per
server, `nativeOk()` gates New server. Server setup lists MCSS's servers and the folders in `servers_dir`. Closing
MCSS stops only the servers MCSS runs. The box can't be unticked while a native server is still looked after.

**The web panel (`WebPanel`, `PanelUi`, `host/web`).** `/panel` on the launchers' listener (the Tailscale address
only), 404 unless `web_panel` is `true`. `host/web/panel.html|js|css` are served from inside the jar under a strict
Content-Security-Policy (`default-src 'self'`; the script builds the page with `textContent` only).

- **Accounts**: `users.json` (`salt`, `rounds`, `hash`: PBKDF2-HMAC-SHA256, 210 000 rounds; `admin`, `perms`,
  `enabled`). Only `/admin/panel/user` on the local listener writes it; no web call does.
- **Sessions**: `POST /panel/login` sets `gk_session` (HttpOnly, SameSite=Strict, Path=/panel); only its SHA-256 is
  kept, in memory. Idle 2 h, at most 24 h. Every non-GET call must carry `X-Panel-Token` (the session's own token).
  Five wrong passwords per account-and-address: locked for 5 minutes. A missing account costs the same hash time.
- **Calls**: `/panel/api/<x>` is `/admin/<x>` handed through, only if `WebPanel.ALLOWED` lists it, with the permission
  it names (`view`, `control`, `console`, `manage`, `access`, `machine`, or `admin`). A body with a `MACHINE_ONLY`
  key (`start`, `java`, `backup_dir`, `file`, `add`, `remove`) is refused, as is `tools/new` of kind `forge`/`file`:
  nothing reachable from the web can name a program or a path on the machine. Each POST is logged with the account.
- **Wake**: `wake_devices` in `host.json` (name, MAC); `WebPanel.wake` sends the 102-byte magic packet to UDP 9 and 7
  on each interface's broadcast address. `/admin/sleep` is `sleepPc()`.

Tested with a stand-in for a Minecraft server (`FakeServer` in the test scratch folder), dummy web accounts, and for making servers a
stand-in download source and installer: start, the Done line,
console commands, stop, a failed start, and a Gatekeeper ending and a new one taking the server over. Not yet with
a real Minecraft server, and not on real Linux.

When it finishes Gatekeeper opens with **Server setup** (Status tab, any time later too): it finds MCSS
(`mcss.exe`; Browse if it isn't in a usual place), lists the Minecraft servers in MCSS's `servers` folder (each has
MCSS's own `mcss_server_config.json`, with its name and id; the port is read from `server.properties`) with a tick-box
each, and offers this machine's Tailscale address. Saving writes `host.json` (`mcss_exe`, `bind`, `servers`) and the
Gatekeeper starts listening on the new address at once. Servers it already had keep their place and id.
**Copy pairing address** gives the address (`<Tailscale address>:25570`) a launcher is paired with in its Server
config tab (`keeper_url` in its `server-access.json`; it keeps a separate id and secret for each Gatekeeper). A scheduled task, "Gatehouse Keeper", starts it (javaw, no window)
when the owner signs in to Windows, in the owner's session, so MCSS opens on the desktop as usual.

- It listens on the Tailscale address only (port 25570, `helper_url` in `server_control`), plus 127.0.0.1 for the
  panel.
- Each launcher introduces itself from the pop-up's Server controls tab: an id and a secret it made itself, its
  computer name and system. Gatekeeper also asks Tailscale which device the request came from (`tailscale whois`).
  New launchers wait for permission.
- **Gatekeeper** (Start menu) lists the launchers (name, Tailscale device, system, first and last
  seen), with **Start MCSS** / **Close MCSS** for MCSS itself at the top (the same start and close the launchers
  ask for), and with their four permissions, **Server control**, **MCSS control**, **PC sleep** and **Gatekeeper control**, each switched on or off
  for the selected launcher, plus **Forget** and **Set MCSS key...**, and shows a log of every request.
- A launcher always shows Server controls (inside its Connection tab) and Server config, and is always told how the
  machine, MCSS and the server are (`GET /v1/mcss`), whatever it may do; only a blacklisted launcher isn't. It
  remembers what Gatekeeper last said (`perm_*`, `ban` in its `server-access.json`), so waking a sleeping PC and a
  suspension both hold while Gatekeeper can't be asked.
- **Staying signed in to MCSS.** What MCSS's sign-in (`POST /auth`) hands back only lasts until MCSS restarts. So
  **Sign in to MCSS...** uses it once to make Gatekeeper an API key of its own in MCSS (`POST /api/v2/keys`, named
  "Gatekeeper <date>": these servers only; view, console, start and stop) and keeps that (`mcss_api_key`,
  `mcss_key_id`, `mcss_key_lasting` in `host.json`); the one it made the time before is deleted. An account that
  may not make keys in MCSS leaves Gatekeeper with the sign-in's own key, and it says that this one ends when MCSS
  restarts.
- **Start server finishes the job.** Every start (the launcher's Start server, Gatekeeper's own button, approving a
  Request server) goes through one routine: with MCSS closed it starts MCSS, waits in the background until MCSS
  answers (up to 2 minutes), then starts the server; the reply says so at once and the log records how it ended.
- **Where everyone is.** The **Now** column (launchers and players) says **In game**, **In the launcher** or
  **Offline**. In game: the server's own log (`logged in`, `left the game`, `lost connection`, the server stopping)
  says the launcher's player is on a running server, having joined from that device's address. In the launcher: its
  window spoke to Gatekeeper within the last 20 seconds (it asks every few seconds while open). Nothing is stored.
- **Updates Pulled** (third tab). When a launcher has fetched an update from this machine (never when it came from
  GitHub), it tells Gatekeeper afterwards (`POST /v1/pulled`: the versions, files, mods, the download's size and how
  much of it came from this machine; mods the pack only lists come from Modrinth). Kept in `pulls.json` beside
  `host.json` (the latest 1000) and shown newest first, with totals. It is what launchers report, not a measurement
  on this machine.
- **The window's three levels.** It opens on **MCSS on this machine** (its program and whether it's running) and
  **Servers in this MCSS** (each with whether it's on, how many are playing, its port and folder; `GET
  /admin/overview`). **Open** goes into one server's pages (Access Manager, Server Status, Updates Pulled); **Servers** at
  the top right comes back. One MCSS per machine for now.
- **The catalog.** A launcher's menu asks `GET /v1/catalog` for this Gatekeeper's name (`name` in `host.json`, else
  the computer's name) and its servers: id, name, the address to join, whether it's on, and `profile`, the address
  of that server's pack profile (a `config.json`, docs/profiles.md; `profile` on the server in `host.json`, filled in
  from this repository by `tools/install_host.ps1`). A launcher can install a pack from that. A blacklisted launcher
  gets nothing. It also says what kind each server is (`kind`: `modded` when its folder has mods or a mod loader's files, else
  `vanilla`) and its Minecraft version (`minecraft_version`, from the server's own log, else its `versions` folder), so a
  launcher can set up a plain instance for a plain server that has no pack.
- **Share pairing** (Status tab): the address, a pairing link (`gatehouse://pair/<address>?name=<name>`, to paste
  into a launcher's Add Gatekeeper) and a pairing file (`<name>.gatekeeper`, the same two things as JSON, to send).
  None of them grants anything: the launcher still introduces itself and waits in Access Manager.
- **What each server is doing.** Gatekeeper asks MCSS for each server's own state (never the game port: two servers on
  one machine may share one) and reports a phase: `online`, `starting`, `stopping`, `off`, or `nomcss` (MCSS isn't
  running). Where MCSS can't be asked (not signed in), the server's world lock (`<world>/session.lock`) says whether
  it's running. Between Gatekeeper asking for a start or stop and MCSS showing it, the phase reads starting or
  stopping; MCSS itself reads `starting` or `closing` the same way. Launchers get it from `GET /v1/phase` and in the
  catalog; the window from `/admin/overview` and `/admin/devices`.
- **Close MCSS finishes the job.** With a server running, Close MCSS (launcher or window) stops each running server
  first, waits in the background until they are down (up to 3 minutes), then closes MCSS; if a server won't stop,
  MCSS is left open and the log says so.
- **Per server.** The log tags a line about one server with its name (`[Name]`), so a server's Server Status page
  shows its own lines and the machine-wide ones; Updates Pulled records which server each pull was for. Server
  control and the whitelist are per server; machine wake/sleep, MCSS control, Gatekeeper control and suspensions are
  about the machine or the person, so they are the same on every server's page.
- **Three states.** Each permission (machine wake and sleep, MCSS, Minecraft server) is `allowed`, `none` (not
  allowed: nobody decided) or `denied`, for a launcher (`devices.json`) or a player (`players.json`; `deny` holds the
  denials). A player's setting goes to all their confirmed launchers, except a launcher that is itself denied that
  permission. MCSS allowed includes the server. Gatekeeper's **Access Manager** tab shows them as green,
  white and red cells; click one to change it (`POST /admin/perm`, `/admin/player/perm` with `state`).
- **Suspend, blacklist, kick** (`POST /admin/ban` with `kind` `suspend`, `blacklist` or empty to lift, for a launcher
  `id` or a `player`; `POST /admin/kick`). A suspended launcher is told the server's state but may do nothing, and its
  Play button becomes a request to be let back. A blacklisted one is told nothing about the machine, MCSS or the
  server either (`/v1/mcss` carries only `ban`, `ban_scope` `machine` or `user`, `ban_reason` and its own requests).
  Both still get pack updates. Banning a player also kicks them and takes them off the whitelist until it's lifted;
  banning one launcher can't stop the player joining from another device, and the panel says so.
- **Whitelist gate.** `/v1/mcss` tells a launcher whether its player is on the whitelist (`whitelisted`: `yes`, `no`,
  `unknown`); on `no` its Play button asks to be added instead of starting the game.
- Launchers allowed **Server control** start and stop the Minecraft server through Gatekeeper (`POST
  /v1/server/start|stop`; status from `GET /v1/server`, the shutdown check from `GET /v1/server/log`, the end of
  `logs/latest.log`), with no panel account or key on the device. Gatekeeper does it with its own MCSS API key, which
  the owner makes in MCSS's web panel (API Keys: view, start and stop this server) and enters once with **Set MCSS
  key...**. Or, simpler, **Sign in to MCSS...** in Gatekeeper: the owner types a panel account's username and
  password there, Gatekeeper signs in (the panel's `POST /auth`) and keeps the key MCSS hands back, not the password. It's kept in `host.json` on the PC only and is never sent to launchers.
- Allowed launchers can start MCSS, and close it. Closing is refused while the Minecraft server is running (stop it
  first). How depends on MCSS's own close setting (`mcssClosePreference` in `resources/mcss_config.json`), which the
  helper reports to launchers (`close` in `/v1/mcss`) so the button can say what it will do: with the exit choice,
  **Close MCSS** asks MCSS's window to close as its X button would and waits up to 30 seconds, never forcing; with
  HideToTaskbar or MinimizeToTaskbar (the X button only hides it), the button reads **Force close MCSS** and Gatekeeper
  ends MCSS, only when the request says force. It only
  touches the configured `mcss.exe`.
- Separately, launchers the owner allows **PC sleep** (its own switch in the panel) can put the PC to sleep
  (`POST /v1/pc/sleep`): refused while the Minecraft server is running; otherwise Gatekeeper suspends the PC about 5
  seconds later (Windows' SetSuspendState, as the Start menu's Sleep). MCSS stays open; Wake on LAN wakes the PC.
- Launchers allowed **Gatekeeper control** get **Manage launchers...** in their Server config tab: the panel's list,
  permission switches, Forget and activity, through `GET /v1/admin/devices|log` and `POST /v1/admin/perm|forget`.
  Three things stay with the panel on the PC: giving or removing Gatekeeper control, forgetting a launcher that has it
  (or the one asking), and Gatekeeper's MCSS key, which is never sent anywhere. Every change is logged with who
  asked.
- Each launcher tells Gatekeeper how it controls the server (`X-Access`: `helper`, or what it has saved, `X-Saved`:
  `signin`, `key` (one saved before sign-in became the only way), `none`; a sign-in's username in `X-Access-User`), shown in the panel's **Controls the
  server** column. MCSS control includes server control.
- **Remove server access** (panel, and Manage launchers): switches the launcher's server and MCSS control off and tells it to forget whatever it has saved (`clear_access`, until it reports `none`). A
  sign-in or key it got elsewhere still exists in MCSS; the panel says so, and only removing that account or key in
  MCSS blocks it for good.
- **Players** (the panel's second tab). Gatekeeper reads the Minecraft server's `logs/latest.log` for joins (`Name[/address:port]
  logged in`) and ties the player to the launcher that talks to Gatekeeper from the same Tailscale address: that
  launcher is then confirmed as theirs (`player`, `player_confirmed`, `player_seen` in `devices.json`). A launcher
  also says which account its game last started as (`X-Player`, from "Setting user" in the game's own log), shown as
  "not confirmed" until the server sees that player join from it. What the owner allows a player (`players.json`)
  is set on all their confirmed launchers and on ones they join from later; a claim alone gets nothing. Player names
  stay in Gatekeeper's files on the PC.
- **Requests.** Any launcher Gatekeeper knows can ask the owner for something (`POST /v1/request` with `type` and an
  optional `message`; one a minute for each type): `server` (turn the server on: the Play button while it's off,
  refused while it runs), `perm:machine`, `perm:mcss`, `perm:server` (the Request permission buttons), `whitelist`,
  `unsuspend` and `unblacklist` (all a banned launcher may ask). The panel lists each with a button that grants it
  and **Dismiss...**, which takes an optional reason; the launcher reads the answer from `/v1/mcss` (`requests`,
  `request_reasons`, kept 10 minutes), chimes on granted and flashes its button red on denied. Gatekeeper logs
  it, plays a short chime and, if Gatekeeper isn't open, opens it minimized (`--panel --minimized`). The panel
  shows who asked in a banner with **Start server** (starts MCSS first if needed) and **Dismiss**, puts it in its
  window title and flashes its taskbar button. `request_alert` = `false` in `host.json` turns the chime and the
  pop-up off.
- Gatekeeper has two tabs. **Access**: manage access **by launcher** (one device) or **by player** (all their
  devices), switched with the two choices at the top. **Status**: Gatekeeper, MCSS (Start/Close, sign-in or key), the
  Minecraft server (Start/Stop) and the activity log. A launcher with Gatekeeper control has the same split in its
  **Remote Gatekeeper** tab, as **Access Manager** and **Gatekeeper activity** (`GET /v1/admin/players`, `POST /v1/admin/player/perm`; never Helper
  control itself). `OJ_PANEL_PNG=<file>` saves pictures of the panel's tabs instead of showing it (testing).
- In the code, everything about MCSS is one file, `McssHost.java`, behind the `ServerHost` interface (running, start,
  close, the server's state, start/stop, and Gatekeeper's own access). The rest of Gatekeeper never reaches MCSS any
  other way, so another host program would be a second implementation of that interface.
- **Whitelist.** In Gatekeeper's Access tab, **By player**, each player has a **Whitelisted** tick-box for the
  server being looked at. The list is every player Gatekeeper knows (from joins, or a launcher's word, marked "not
  confirmed") plus everyone already in the server's `whitelist.json`. Ticking or unticking: while the server runs,
  Gatekeeper sends it `whitelist add <name>` or `whitelist remove <name>` through MCSS (`POST
  .../execute/command`; Gatekeeper's MCSS key needs console permission), so the server finds the account and saves
  the file itself. While it's off, Gatekeeper edits `whitelist.json` directly; adding then needs the account's id from
  the server's `usercache.json`, so a player the server has never seen can only be added while it runs. A launcher
  with Gatekeeper control can do the same from its Access Manager (`POST /v1/admin/whitelist`), and kick, suspend and
  blacklist (`/v1/admin/kick`, `/v1/admin/ban`), though never a launcher or player that has Gatekeeper control.
- **A dismissed request** is told to the launcher that asked: `/v1/mcss` carries `request` (`asked`, `denied` or
  empty), and the launcher shows "Request denied" above its button.
- `devices.json` keeps a hash of each secret, never the secret. Settings: `host.json` (`bind`, `port`, `mcss_exe`,
  `minecraft`, `tailscale_exe`, `mcss_api_key`, and if they differ from the defaults `mcss_panel`, `mcss_server`,
  `server_dir`). It only works while the owner is signed in to Windows.

### Keeping Gatekeeper up to date

Each build of the pack publishes the Gatekeeper it was made with beside it: `dist/gatekeeper/oj-host.jar` and
`gatekeeper.json` (`version`, `sha256`, `size`, and `gatehouse`: the launcher's version in that same build). A running
Gatekeeper looks every five minutes at the pack server on its own machine if there is one (`host.json` `pack_port`,
8467), then at GitHub; `host.json` `update_sources` lists other places.

- **A newer Gatekeeper is out:** its window says so at the top of the Servers tab, with a chime and **Update
  Gatekeeper** (`GET` / `POST /admin/update`). Update fetches the jar, checks it against the published `sha256`, leaves
  checks that it is a Gatekeeper that can run, leaves it as `oj-host.jar.new`, writes `update-restart.ps1` beside
  its settings and ends. That script (readable; Windows locks a running program's file, so something else has to do
  the swap) waits for Gatekeeper to end, closes any Gatekeeper window still holding the file, keeps the old jar as
  `oj-host.jar.old`, puts the new one in place, starts Gatekeeper through its scheduled task (directly if it doesn't
  come up that way) and asks it whether it answers. No answer within 30 seconds: the old jar goes back and is
  started instead. Running Minecraft servers are not touched. Each step is in `host.log` ("update: ...").
- **Until then** Gatekeeper starts no server, and `/v1/phase` tells launchers `blocked`, with the reason: they show
  Update needed instead of Play.
- **A launcher that is behind** the published Gatehouse (it says which it is in `X-Gatehouse`, from Gatehouse 2.5.0)
  is told `blocked` for itself.
- Nothing reachable to compare with: nothing is blocked. `host.json` `require_current: false` turns the blocking off.

### The setup guide

Options also has **Open the setup guide**: the steps from a machine with nothing on it to a server friends can join,
one at a time, each with what to do, buttons for it, and a mark for whether it's done (it looks again every few
seconds). It opens by itself the first time the window is opened on a machine that still has steps to do. Host
setup remains the list of everything at once.

### Options and uninstalling

The settings wheel at the top right opens Options: the version, where Gatekeeper runs from and where its settings
are, and **Uninstall**. After `confirm` is typed, Gatekeeper writes out the install script it carries
(`install-keeper.ps1`, put inside it by `tools/build_host.ps1`) beside its settings and runs it with `-Uninstall -Yes`
(`-Purge` when the tick box for its settings is on) in a PowerShell window that stays open until Enter is pressed;
the script stops Gatekeeper, this window included. `--uninstall-command` prints what would be run, for tests.

### Host setup

The fourth tab on Gatekeeper's first screen. It asks the running Gatekeeper (`GET /admin/checklist`) for the steps a
machine needs before players can use it, each marked done, to do, or worth changing, with what to do:

- Tailscale installed and connected (from `tailscale status --json`; `host.json` `tailscale_exe` if it's installed
  somewhere unusual), key expiry off for this machine, and Run unattended on (from `tailscale debug prefs`).
- Gatekeeper listening on this machine's Tailscale address.
- MCSS found, and Gatekeeper holding its own key for it.
- The servers chosen in Server setup, each on its own port, with their whitelists on and online-mode not off (read
  from each `server.properties`). The whitelist step has a button, **Turn the whitelist on** (`POST
  /admin/whitelist/on`): a running server is told `whitelist on` in its console, one that's off gets
  `white-list=true` in the file. It asks first.
- Gatekeeper itself being up to date (see above).
- Whether any launcher has ever reached this Gatekeeper: the only check that sees it from outside. If none can,
  Windows Firewall has to allow Gatekeeper's Java on private networks.
- A Tailscale invite link for players. Make it in Tailscale's admin page (Machines, this machine, Share, a reusable
  invite link): it shares only this machine. Pasted into the field at the bottom (`POST /admin/checklist`; only a
  `https://login.tailscale.com/` link is taken), it is kept in `host.json` as `tailscale_share_link` and goes out in
  Share pairing: `&ts=` in the pairing link, `tailscale_share` in the pairing file.

Below the list, **Name shown in launchers** sets what connected launchers call this Gatekeeper (`host.json` `name`;
`POST /admin/checklist` with `name`, at most 40 characters; empty: this machine's own name). Launchers read it from
`/v1/catalog` and rename their entry by themselves.

The tab asks again every 5 seconds while it is open, and redraws only when something changed. The line above the
invite field says how many steps are left and when it last looked; a step that has just turned to done says so for a
minute. Ports come from each server's `server.properties` as it is now (not from what Server setup recorded), so a
port change shows at once; when two servers share one, the advice names free ports (`freePorts`: from 25566 up, not
set on any MCSS server, not Gatekeeper's own, nothing listening). If the recorded port is then out of date, a step
says to run Server setup again.

Nothing is changed by looking, and nothing here installs or configures Tailscale, MCSS or the firewall for you.

### Gatekeeper's and Gatehouse's own notes

Gatekeeper's window is titled with its version, and its first screen has three tabs: Servers, Gatekeeper updates (what
changed in Gatekeeper, newest on top) and Gatehouse updates (what changed in the launcher: the newest from the
repository, or the copy that came with this Gatekeeper when that can't be reached). Launchers ask a Gatekeeper for its
notes (`GET /v1/about`) and show them on a Gatekeeper updates tab beside its servers. `host.json` may name another
place for Gatehouse's notes with `gatehouse_notes_url`.

## Server changes for players

Players see what changed on the server in the launch pop-up's **Server** tab: mods added, updated or removed, the
Jarvis build, changed settings, and notes ([`server/changelog.json`](../server/changelog.json)). Both files ship in the
client pack. After a server change is installed (with the server stopped), `tools/server_snapshot.py` shows what
changed; `--note "..."` records the new state with a note for players, and the pack is rebuilt and published. The
build refuses to run when `server/state.json` changed without a note. Only player-facing facts are recorded: never
the world seed, passwords, keys, player lists or IP logs.

## Jarvis

Jarvis is a butler who lives on the server: a custom build of a Fabric NPC mod (based on its 0.17.0 release), heavily
extended for this server. Players don't install anything for him.

**Talking to him.** Say `Jarvis, ...` in chat, use `/jarvis ...` commands, or ring his bell for menus of grouped
controls. `/jarvis guide` (or "Jarvis, show the farming guide") shows in-game help. Commands work the same with or
without the AI model below; the model only helps him understand free-form requests, and it can't invent actions he
doesn't have.

**What he does:**
- **Farming:** builds and tends crop beds and plots, with paths, irrigation, signs and storage, and harvests on a cycle.
- **Forestry:** plants, tends and fells tree groves.
- **Mining:** branch mines and ore veins, with walkable tunnels, a way back, and torches where it's dark.
- **Work sites:** fishing, cooking and smelting until told to stop.
- **Storage and crafting:** fetches from and sorts into chests (players can mark chests private), crafts, and uses
  workstations.
- **The treehouse:** grows giant trees into a chunk-sized treehouse with a round trunk and roots, branches with
  landings and doorways, and decorations, one layer at a time.
- **Defence:** guards his work area without provoking neutral mobs.
- **Idle rounds:** when unassigned, inspects nearby builds, fields and animals without changing anything.

Plots and work storage have owners, so on a shared server he works for the player who asked and only takes from their
storage or chests they allow. He works at about a player's pace.

**Source and releases.** Jarvis's source, tests and patch notes live on the owner's PC for now. Each release is tested
on a separate test server, then installed only while the live server is stopped, with a checksum check.

## Running alongside the server

All on the same PC:

| What | Why | Reachable from |
| --- | --- | --- |
| **Ollama** with the `qwen2.5:3b` model | Helps Jarvis understand free-form chat requests. Local: nothing is sent to an online AI service. | That PC only |
| **The pack server** (`jwebserver`, scheduled task "Minecraft pack server", [`tools/serve.ps1`](../tools/serve.ps1)) | Serves this repository's `dist/` folder so devices update quickly | Tailscale only, port 8467 |
| **Tailscale** | Private network that players join to reach the server and the pack | Players the owner shares it with |

## Changing this page

Update it when the server's mods, settings, host or Jarvis build change, along with a server snapshot and note
(above). When the server gets its own repository, this page should move
there and this repository should link to it.
