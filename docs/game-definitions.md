# Game definitions

Gatekeeper knows Minecraft in its own code. Most other games with a dedicated server can be described by a small
file instead, read by one shared adapter (`GameFile.java`; [Other games](other-games.md), stage 2). A definition goes
in Gatekeeper's own folder as `games/<id>.json`, and Gatekeeper reads it when it starts. The game then appears in
**New server**, and its servers are started, watched, backed up and joined like any other.

**Palworld** is built in (`Palworld.java`): its definition plus what only it has, namely its one-line settings file
and its REST API (who is on, saving, a clean stop).

## A definition

```json
{
  "id": "valheim",
  "name": "Valheim",
  "steam_server_app": 896660,
  "steam_game_app": 892970,
  "server_files": ["valheim_server.exe", "valheim_server.x86_64"],
  "start": {
    "windows": ["valheim_server.exe", "-nographics", "-batchmode", "-name", "{name}", "-port", "{port}", "-world", "{world}", "-password", "{password}", "-public", "0"],
    "linux": ["valheim_server.x86_64", "-nographics", "-batchmode", "-name", "{name}", "-port", "{port}", "-world", "{world}", "-password", "{password}", "-public", "0"]
  },
  "ready": "Game server connected",
  "stop_command": "",
  "joined": "Got character ZDOID from (?<name>[^:]+) :",
  "left": "Destroying abandoned non persistent zdo .* owner (?<name>\\S+)",
  "port": {"default": "2456"},
  "query_port": "+1",
  "allow": {"kind": "steam_ids", "file": "permittedlist.txt", "word": "permitted list"},
  "world": "worlds_local",
  "join": "steam://run/{app}//+connect {address}"
}
```

(The Valheim lines above show the shape only: they will be checked against a real server before Valheim is offered.)

| Field | What it is |
| --- | --- |
| `id`, `name` | Its id in `host.json` (lower case, not `minecraft`) and its name for people. |
| `steam_server_app` | Its dedicated server's Steam app id. New server installs it with **SteamCMD**, Valve's tool, signing in anonymously. SteamCMD is fetched from Valve the first time it is needed, only when you press Make the server. Without this, New server can't install it: put the server in a folder yourself and add it in Server setup. |
| `steam_game_app` | The game's own Steam app id, which players have. Gatehouse uses it to see whether the game is installed on a device, and for Play. |
| `server_files` | Files that mark its server's folder (any one present). |
| `start` | The command for each system, starting with its program (in the server's folder). `{port}`, `{name}`, `{world}`, `{password}` and `{dir}` are filled in from the server. |
| `ready` | A pattern for the line it prints once it is up. |
| `stop_command` | What is typed into its console to stop it; empty means it is asked to end, as closing its window would, and ended outright a minute later if it hasn't. |
| `log` | Its own log file in its folder; empty means what it prints, which Gatekeeper keeps as `gatekeeper-console.log`. |
| `joined`, `left`, `stopping`, `lag` | Patterns for its log's lines. `joined` names the player as `(?<name>...)`, and may give `(?<address>...)` (ties a player to their device) and `(?<time>...)`. |
| `port.default`, `port.setting` | Its usual port, and the settings key that holds it (when its settings file does). |
| `query_port` | Where it answers **Steam's server query** (A2S): a number, or `+n` from its game port. Gatekeeper then asks it whether it is up and who is on, every time it looks. |
| `settings.file` | Its settings file (`key=value` lines), shown and changed on Manage, Game settings. |
| `allow.kind` | Who may join: `steam_ids` (a file of Steam IDs), `names` (a file of player names), `password` (no list: whoever has the server's password), or `none`. `allow.file` is that file, and `allow.word` what the game calls it ("permitted list"). |
| `world` | The folder holding its world, for a world-only backup (empty: the whole folder). |
| `save_commands`, `saved` | Typed before a backup's copy, and the line that says it has saved (for games that have them). |
| `join` | How Gatehouse's **Play** starts the game connected: `{app}` and `{address}` filled in. Empty means Steam's own `steam://connect/<address>`. |
| `terms.url`, `terms.name` | Terms the owner must accept before its server runs (New server has the tick); none if missing. |

## Who may join, by Steam ID

Each launcher tells its Gatekeeper which Steam account is signed in on its device (Steam's own `loginusers.vdf`: its
Steam ID and name, nothing else). Allowing a player on a server whose list holds Steam IDs writes their Steam ID
there; the Access Manager shows the player's name. A player whose launcher hasn't told its Steam account yet can't be
added until they open Gatehouse once with Steam signed in.

## Playing

A Steam game's server card in Gatehouse says whether the game is installed on that device. **Play** starts it through
Steam, connected to the server; **Get it in Steam** opens Steam's page for it otherwise.
