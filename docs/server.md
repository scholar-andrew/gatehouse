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

## The host helper (starting and closing MCSS)

MCSS's web panel only exists while MCSS is running, so starting MCSS itself needs something else on the PC: the host
helper (`host/`, built by `tools/build_host.ps1`, installed by `tools/install_host.ps1` into
`C:\Minecraft\OctoberJourneyHost`). A scheduled task, "October Journey host helper", starts it (javaw, no window)
when the owner signs in to Windows, in the owner's session, so MCSS opens on the desktop as usual.

- It listens on the Tailscale address only (port 25570, `helper_url` in `server_control`), plus 127.0.0.1 for the
  panel.
- Each launcher introduces itself from the pop-up's Server controls tab: an id and a secret it made itself, its
  computer name and system. The helper also asks Tailscale which device the request came from (`tailscale whois`).
  New launchers wait for permission.
- **October Journey host panel** (Start menu) lists the launchers (name, Tailscale device, system, first and last
  seen), with **Start MCSS** / **Close MCSS** for MCSS itself at the top (the same start and close the launchers
  ask for), and with their four permissions, **Server control**, **MCSS control**, **PC sleep** and **Helper control**, each switched on or off
  for the selected launcher, plus **Forget** and **Set MCSS key...**, and shows a log of every request.
- A launcher shows its Server controls and Server config tabs only once it has at least one permission (or its own
  panel access). It remembers what the helper last said (`perm_*` in its `server-access.json`), so the tabs are still
  there to wake a sleeping PC.
- Launchers allowed **Server control** start and stop the Minecraft server through the helper (`POST
  /v1/server/start|stop`; status from `GET /v1/server`, the shutdown check from `GET /v1/server/log`, the end of
  `logs/latest.log`), with no panel account or key on the device. The helper does it with its own MCSS API key, which
  the owner makes in MCSS's web panel (API Keys: view, start and stop this server) and enters once with **Set MCSS
  key...**. Or, simpler, **Sign in to MCSS...** in the host panel: the owner types a panel account's username and
  password there, the helper signs in (the panel's `POST /auth`) and keeps the key MCSS hands back, not the password. It's kept in `host.json` on the PC only and is never sent to launchers.
- Allowed launchers can start MCSS, and close it. Closing is refused while the Minecraft server is running (stop it
  first). How depends on MCSS's own close setting (`mcssClosePreference` in `resources/mcss_config.json`), which the
  helper reports to launchers (`close` in `/v1/mcss`) so the button can say what it will do: with the exit choice,
  **Close MCSS** asks MCSS's window to close as its X button would and waits up to 30 seconds, never forcing; with
  HideToTaskbar or MinimizeToTaskbar (the X button only hides it), the button reads **Force close MCSS** and the helper
  ends MCSS, only when the request says force. It only
  touches the configured `mcss.exe`.
- Separately, launchers the owner allows **PC sleep** (its own switch in the panel) can put the PC to sleep
  (`POST /v1/pc/sleep`): refused while the Minecraft server is running; otherwise the helper suspends the PC about 5
  seconds later (Windows' SetSuspendState, as the Start menu's Sleep). MCSS stays open; Wake on LAN wakes the PC.
- Launchers allowed **Helper control** get **Manage launchers...** in their Server config tab: the panel's list,
  permission switches, Forget and activity, through `GET /v1/admin/devices|log` and `POST /v1/admin/perm|forget`.
  Three things stay with the panel on the PC: giving or removing Helper control, forgetting a launcher that has it
  (or the one asking), and the helper's MCSS key, which is never sent anywhere. Every change is logged with who
  asked.
- Each launcher tells the helper how it controls the server (`X-Access`: `helper`, or what it has saved, `X-Saved`:
  `signin`, `key` (one saved before sign-in became the only way), `none`; a sign-in's username in `X-Access-User`), shown in the panel's **Controls the
  server** column. MCSS control includes server control.
- **Remove server access** (panel, and Manage launchers): switches the launcher's server and MCSS control off and tells it to forget whatever it has saved (`clear_access`, until it reports `none`). A
  sign-in or key it got elsewhere still exists in MCSS; the panel says so, and only removing that account or key in
  MCSS blocks it for good.
- **Players** (the panel's second tab). The helper reads the Minecraft server's `logs/latest.log` for joins (`Name[/address:port]
  logged in`) and ties the player to the launcher that talks to the helper from the same Tailscale address: that
  launcher is then confirmed as theirs (`player`, `player_confirmed`, `player_seen` in `devices.json`). A launcher
  also says which account its game last started as (`X-Player`, from "Setting user" in the game's own log), shown as
  "not confirmed" until the server sees that player join from it. What the owner allows a player (`players.json`)
  is set on all their confirmed launchers and on ones they join from later; a claim alone gets nothing. Player names
  stay in the helper's files on the PC.
- `devices.json` keeps a hash of each secret, never the secret. Settings: `host.json` (`bind`, `port`, `mcss_exe`,
  `minecraft`, `tailscale_exe`, `mcss_api_key`, and if they differ from the defaults `mcss_panel`, `mcss_server`,
  `server_dir`). It only works while the owner is signed in to Windows.

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
