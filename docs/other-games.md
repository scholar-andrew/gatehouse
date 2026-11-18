# Other games: the plan (R34)

Status: stages 1 and 2 built (3.37.0 and 3.39.0), and stage 3 with **Palworld** (3.40.0, 2026-11-18). Where Gatehouse goes beyond
Minecraft, and in what order.

**Neutral by default** (the owner, 2026-11-18): neither program assumes Minecraft. Every server records its game; a
new one has its game chosen first. Gatekeeper on Windows runs servers itself unless it is set up with MCSS. Gatehouse
opens as "Gatehouse", and Minecraft's own parts (Prism Launcher, the Minecraft account, Open Prism Launcher) appear
only once a Minecraft server is installed or shown. Settings from before are written down once, so nothing that
worked changes.

## What carries over as it is

Most of Gatehouse has nothing to do with Minecraft, and keeps working for any game unchanged:

- devices pairing with a Gatekeeper, the Access Manager's launchers and players, requests and their chimes;
- Tailscale (it carries a game's UDP traffic as well as Minecraft's TCP);
- chat and file sharing, the web panel, the status page, phone notifications, Wake-on-LAN and sleep;
- updates of the program itself, constellations, hand-out between Gate Systems;
- crash watch, memory and processor readings, backups and tasks for a server Gatekeeper runs itself
  (`DirectHost`, `ServerWatch`, `ServerTools`), which already only see "a program in a folder".

## What is Minecraft today

| Part | Where | What it assumes |
| --- | --- | --- |
| Making a server | `ServerTools.create/make` | Vanilla, Paper, Fabric, Forge from their own sites; Java chosen by version; `eula.txt` |
| Starting and stopping | `DirectHost.launch`, `McssHost` | `java -jar server.jar nogui`, stopped by typing `stop`; or MCSS |
| Ready, and who is on | `HostHelper` log reading (`joined the game`, `left the game`), `ServerWatch.lagSince` | Minecraft's log lines, "Can't keep up!" |
| Who may join | `HostHelper.whitelist*`, `LocalLink.whitelist`, `whitelistOn` | `whitelist.json` and `usercache.json`, Minecraft names and UUIDs |
| Tying a player to a device | `HostHelper` log reading | The server log shows a name joining from a Tailscale address |
| Settings | `ServerTools.properties`, Game settings tab | `server.properties` |
| A server's pack | `Packs`, `PackBuilder`, `ModList`; Gatehouse `PackInstall`, `PackRelay`, `SteamUpdate` | packwiz, Modrinth, Fabric |
| Playing | Gatehouse `LaunchPopup` (Prism `--launch`), `Home` | Prism Launcher instances, a Microsoft account in Prism |

## The idea: a game is a kind of server

Every server in `host.json` gets a `game` (missing means `minecraft`, so nothing existing changes). Each game is a
small adapter in Gatekeeper, and a smaller one in Gatehouse, that answers the same questions:

| Question | Minecraft | A typical Steam game (Valheim, say) |
| --- | --- | --- |
| How is its server installed and updated? | Its makers' sites (as now) | SteamCMD, Valve's own tool, with the game's dedicated-server app id |
| How is it started and stopped? | `java -jar`, type `stop` | Its server program with arguments; stopped by its own command or a close signal |
| Is it ready, and who is on? | Log lines | Steam's server query (A2S), which most Steam game servers answer; log lines as a fallback |
| Who may join? | `whitelist.json` (names) | The game's own list: `permittedlist.txt` of Steam IDs, a server password, or nothing |
| Which device is this player? | Log: name from a Tailscale address | Gatehouse reports the Steam account signed in on the device (Steam's own `loginusers.vdf`), and the server's log confirms that Steam ID joined |
| Settings | `server.properties` | Its arguments and its own settings file |
| What a player must have | A Prism instance with the pack | The game in their Steam library; mods, if any, as a mod-manager profile |
| How a player joins | Prism `--launch` with the server | `steam://run/<app id>//+connect <address>`, or the address to type |
| What a backup copies | The world folder | The game's save folder |

Gatekeeper's pages stay the same; each shows what the server's game has (a game without settings has no Game
settings tab, a game without a list of players has no whitelist column). Gatehouse's server card gets **Play**,
which for a Steam game checks the game is installed (Steam's own library files) and starts it through Steam,
connected to the server.

## Stages

1. **Groundwork (built, 3.37.0).** `game` on every server; every Minecraft-only piece of Gatekeeper behind `Game`
   (`Minecraft.java` the only one), and Gatehouse's `Games.java`. Proved by a scenario run against 3.36.0 and the new
   build (who is on from the log, a device tied to its player, the whitelist off and on, settings, start, backup
   with the save commands, stop): every answer identical, apart from the server now saying `game: minecraft`.
2. **The generic pieces (built, 3.39.0).** Games described by a file ([Game definitions](game-definitions.md)),
   read by one shared adapter; Steam's server query (who is on); SteamCMD installing a game's server (fetched from
   Valve only when the owner presses Make the server); who may join as Steam IDs, names, a password or nothing, with
   each game's own word for it; Gatehouse finding Steam, the games installed, the Steam account signed in, and Play
   through Steam; neutral wording where a server isn't Minecraft. Proved with a stand-in game end to end (made with a
   stand-in SteamCMD, started, up, who is on by the query, a Steam ID allowed, what launchers are told, stopped), and
   Minecraft still behaves as in 3.36.0.
3. **Palworld, end to end (built, 3.40.0).** Chosen by the owner from the games on their Steam account. New server
   installs its dedicated server with SteamCMD and makes its settings; Gatekeeper uses Palworld's own REST API (turned
   on for Gatekeeper alone, on the game port + 1, with a random admin password) to know it is up, who is on (with Steam
   IDs), to save before a backup and to stop cleanly. Gatehouse's Play starts it through Steam and copies the address
   for the game's Join Multiplayer Game. Tried for real on the owner's PC: installed (5.4 GB), started, up in seconds,
   backed up, stopped cleanly. Not yet tried: friends joining from their devices.
   The plan's first version of this step: **The first other game, end to end:** make its server from Gatekeeper, start, stop, crash watch, who is on,
   who may join, backups, and Play from Gatehouse on the PC, the laptop and the Deck. Stand-ins first, then your
   own machines.
4. **Mods for that game, if it has them.** For Thunderstore games (Valheim, Lethal Company) Gatekeeper keeps a
   mod-profile code players import into r2modman or Gale; for Factorio, its mod list synced from the mod portal.
5. **A second game,** to prove the adapter really is general, then the user guide and docs.

## Good first games

All have a free dedicated server, run on Windows and Linux, join by address over Tailscale, and answer Steam's
server query.

| Game | Server via | Who may join | Mods |
| --- | --- | --- | --- |
| **Valheim** (recommended first) | SteamCMD (app 896660) | `permittedlist.txt` of Steam IDs, plus a password | Thunderstore (BepInEx) |
| Palworld | SteamCMD (app 2394010) | Password; admin list | Few |
| Project Zomboid | SteamCMD (app 380870) | Its own accounts and allow list | Steam Workshop |
| 7 Days to Die | SteamCMD (app 294420) | `serveradmin.xml` whitelist | Few |
| Terraria | Its own download | Password | tModLoader (separate) |
| Factorio | Its own download (headless, Linux) | `server-whitelist.json` of usernames | Mod portal |

Valheim fits best as the first: friends' groups play it, its allow list works like Minecraft's whitelist, its log
names the Steam ID of everyone who connects (so a player can be confirmed as now), and its mods work like a pack.

## Limits to know

- Each game has its own terms for running its server; Gatekeeper shows them and asks, as it does with Minecraft's EULA.
- Some servers are Windows-only or Linux-only; a Steam Deck can host only those with a Linux server.
- Games with anti-cheat or account-bound servers (some need a Steam login to download) may not be possible
  without your own Steam account; SteamCMD's anonymous login covers most dedicated servers.
- The Minecraft pack, Prism Launcher and October Journey stay exactly as they are.
