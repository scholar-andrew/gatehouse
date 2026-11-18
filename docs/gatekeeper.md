# Using Gatekeeper (Host)

Gatekeeper runs in the background on the machine that hosts the Minecraft servers; its window is only for looking
and changing things, and closing it stops nothing. Setting it up: [Getting started](getting-started.md#hosts-from-nothing-to-a-server-friends-can-join).

- [The window](#the-window)
- [Servers](#servers)
- [Players: who may do what](#players-who-may-do-what)
- [Who a server is for](#who-a-server-is-for)
- [Packs Gatekeeper builds](#packs-gatekeeper-builds)
- [Running servers without MCSS](#running-servers-without-mcss)
- [The web panel, the status page and phone notifications](#the-web-panel-the-status-page-and-phone-notifications)
- [Updates and constellations](#updates-and-constellations)
- [Systems and the history of servers](#systems-and-the-history-of-servers)
- [Options, ending and removing it](#options-ending-and-removing-it)

## The window

Four sections across the top (click one, or flip with Q and E, or a controller's bumpers). It always opens on
**Servers**.

| Section | What is there |
| --- | --- |
| **Servers** | The servers as cards (picture, whether it's on, who is playing, memory and processor use), the panel beside them with more about the one you are on, **New server**, **Server setup**, **Check for updates**. |
| **Players** | The Access Manager: launchers and players and what each may do ([below](#players-who-may-do-what)). A red number is launchers asking for something. |
| **Chat** | The rooms (this Gatekeeper and each server), where you write as Host, paste or attach files, view what others offered, and can empty a room. |
| **Updates** | What's new in Gatekeeper and in Gatehouse, **Updates pulled** (what launchers fetched from this machine), and the **Update log**. |

The **menu** at the top left has the rest:

| Menu | What is there |
| --- | --- |
| **Host setup** | Every check this machine needs before players can use it, each done, to do, or worth changing, with what to do; Gatekeeper's and MCSS's own lines (Start and Close MCSS, Sign in to MCSS); **Share pairing**; the name launchers show for this Gatekeeper. A red number is steps still to do. |
| **Activity log** | Everything Gatekeeper did and was asked, newest first. |
| **Web panel** | Turning the panel on, and its accounts ([below](#the-web-panel-the-status-page-and-phone-notifications)). |
| **Constellation**, **Systems**, **History of servers** | [Below](#updates-and-constellations). |
| **Options** | Pictures, update mode, notifications, the status page, starting servers with Gatekeeper, ending and uninstalling. |
| **Setup guide**, **Play or Host** | The steps one at a time; which halves this machine has. |
| **User guide**, **GitHub page** | Open the [user guide](user-guide.md) or the program's GitHub page in your browser (the web panel's menu has them too). |

## Servers

**Open** on a server's card opens its own page:

| Tab | What it has |
| --- | --- |
| **Overview** | Start and Stop, and the server's details. |
| **Console** | What the server prints, a line to type commands into, uptime, memory, processor and players; Start/Stop, Restart and End it now. *For servers Gatekeeper runs itself.* |
| **Settings** | Name, memory, port, which Java, an own start command, start with Gatekeeper, start again after a crash; **Delete this server** (out of Gatekeeper only, or its folder too, which needs its name typed). *Same.* |
| **Game settings** | The server's own `server.properties`. *Same.* |
| **Backups** | A zip of the world or of the whole folder, keeping the newest few. With the server on, it is told to hold its saving while the copy is made. *Same.* |
| **Tasks** | Restart, stop, start, back up or type commands, every day at a time or every so many minutes. *Same.* |
| **Pack** | The pack players get for this server, its visibility and invites ([below](#packs-gatekeeper-builds)). |

**Crash watch.** Once a minute Gatekeeper looks at each server. One that was on and is now off without Gatekeeper
being asked to stop it went down: you are told (activity log, and your phone when notifications are on), and a server
set to start again after a crash is started again, at most three times in ten minutes. An MCSS server stopped in
MCSS itself looks the same from here, so the message says "stopped outside Gatekeeper, or crashed". The same look
notes friends joining, and keeps the last hour of memory and processor use and the server's own "Can't keep up!"
warnings for its card.

**Starting finishes the job.** Every start (from a launcher, Gatekeeper's own button, or approving a Request
server) starts MCSS first when it is closed, waits for it, then starts the server. **Close MCSS** stops each running
server first and only then closes MCSS; it is refused while a server won't stop. **Sleep** from a launcher is refused
while a server is running.

## Players: who may do what

When a player adds your Gatekeeper, their launcher appears under **Players**, waiting. A new launcher can do nothing
until you allow it. Decide **by launcher** (one device) or **by player** (all their devices); what you set for a
player goes to all their confirmed devices, except one that is itself denied.

| Permission | What it lets them do |
| --- | --- |
| Whitelist | Join the Minecraft server (per server) |
| Server control | Start and stop that server |
| MCSS control | Start and close MCSS itself (includes server control). Windows with MCSS only |
| Machine wake/sleep | Wake this machine and put it to sleep |
| Gatekeeper control | Manage other launchers' access from their own launcher. Only given at this machine |

Each cell is green (allowed), white (not allowed: nobody decided, they can ask) or red (denied); click to change.
**Suspend** (they can't start the game or use any control), **Blacklist** (the same, and they see nothing of the
server), **Lift**, and **Kick** (off the server now) each take an optional reason the player is shown. A player is
tied to a launcher when the server's log shows them joining from that device's Tailscale address; until then the
name the launcher reports is marked "not confirmed" and gets nothing on its own.

When a player presses a Request button, this machine chimes and shows who asked, with buttons to grant or dismiss
(with an optional reason).

## Who a server is for

Each server is **private** (the default), **shareable** or **public**, set on its **Pack** tab:

- **Private:** this machine's launcher, launchers with Gatekeeper or MCSS control, one allowed to start and stop
  this server, one whose player is on its whitelist, and one that used an invite you made.
- **Shareable:** those, and one that used an invite made by a launcher that has the server.
- **Public:** every launcher that connects.

A launcher a server isn't for isn't told of it and isn't given its pack. An **invite** is a code (or link) good for
seven days and any number of launchers: it only makes the server visible to them, with no whitelist and no
permission. **Take back invites** clears a server's codes and the launchers that came in by one.

## Packs Gatekeeper builds

A server's **Pack** tab can make and keep the pack players get, with no repository involved: **Make a pack for this
server** (a Fabric server: its versions, no mods yet) or **Bring in a pack from a folder**. Gatekeeper reads the jars
in the server's `mods` folder, asks Modrinth which of those exact files it publishes, guesses which ones players need,
and you confirm the list. A mod published on Modrinth is fetched by players from there; any other comes from
Gatekeeper; either way it is checked against the server's own file. Pictures for Steam are made from the pack's name
and the server's picture, unless you choose your own. **New version** takes the notes players read. Launchers install
and update it from this Gatekeeper like any other pack. These packs aren't signed, so they are never handed out
between machines.

**Find mods on Modrinth…** (3.41.0) searches Modrinth for Fabric mods that fit the server's Minecraft version, shows
how many people use each and whether players need it too, and **Add to the server** puts the chosen one in the
server's `mods` folder, with the mods it says it requires. Each file comes from Modrinth's own file server and is kept
only if it matches the checksum Modrinth publishes. It works for Fabric servers Gatekeeper runs itself (for an MCSS
server, add mods in MCSS). Nothing reaches players until the server has restarted and you use **Mods players get…** and
**New version…**. Only the search words and the Minecraft version are sent to Modrinth.

## The AI helper

If [Ollama](https://ollama.com) runs on the Gatekeeper machine with the model `qwen2.5:3b` (or another, set as
`ollama_model` in host.json), Gatekeeper can use it (3.41.0; Notifications section, **AI helper**). Nothing leaves the
machine: the model runs locally, and Gatekeeper talks to it only on 127.0.0.1.

- **In chat**, a message starting with `@ai` gets an answer from "Gatekeeper AI" in the same room, about the servers
  (which are on, who is on them, who may join, how to join). One question at a time per room.
- **When a server Gatekeeper runs stops by itself**, the error lines at the end of its log are explained in plain words,
  in the log and in the "server went down" notification. If the log shows no error, it says so instead of guessing.

The answers are a small model's guesses: useful as a pointer, not a diagnosis.

## Palworld

**New server** can make a Palworld server (3.40.0): choose Palworld as its game, give it a name and a password
(Palworld has no list of players: whoever has the password joins), and press Make the server. Gatekeeper fetches
SteamCMD from Valve the first time, installs Palworld's dedicated server with it (about 5 GB), and makes its settings
(name, password, port; Palworld's own defaults for the rest, changeable under Game settings). It also turns on
Palworld's REST API for itself, on this machine, with a long random password: that is how it knows the server is up and
who is on, saves before a backup, and stops it cleanly (players get a five-second warning). Friends join from
Gatehouse: Play starts Palworld and copies the address to paste in its Join Multiplayer Game. The game port (8211 by
default) has to be allowed through Windows Firewall for Palworld's server: Windows asks the first time it starts.

## Running servers without MCSS

MC Server Soft is Windows only, so on Linux and SteamOS Gatekeeper runs the servers itself; on Windows, tick
**Gatekeeper may also run servers itself, beside MCSS's** on the Servers section to have both (each server says which
runs it). **New server** makes one:

| Kind | For | Where its program comes from |
|---|---|---|
| Vanilla | The plain game | Mojang, checked against Mojang's checksum |
| Paper | Plugins | PaperMC, checked against PaperMC's checksum |
| Fabric | Mods | Fabric (which publishes no checksum for it) |
| Forge or NeoForge | Mods | An installer you download yourself; Gatekeeper runs it |
| A server jar I already have | Anything else | A file you pick |

Nothing is fetched until you press the button, no server is made without your EULA tick, and Gatekeeper doesn't
install Java (it says which one is needed). Two things to know: Gatekeeper won't update itself while a server it runs
is on (it waits, and updates by itself once that server is off), and tasks and crash restarts need Gatekeeper running,
which it normally is.

## The web panel, the status page and phone notifications

**The web panel** controls Gatekeeper from a browser on any device in your Tailscale network. It is off until you
turn it on. Under **Web panel**: **New account** (a name, a password of 10 characters or more, and what it may do, or
**Admin** for everything), tick **The web panel is on**, and open the address beside it
(`http://<this machine's Tailscale address>:25570/panel/`). It is made for a phone first: there the sections are a bar
along the bottom, within thumb's reach, and wide tables become cards. On a wide screen they run across the top, as in
the window. A section the account may not use is shown greyed; the rest is in the menu at the top left. A phone can
keep it on its home screen (Share, Add to Home Screen; or the browser's menu, Install), where it opens like an app.

| Section | What is there |
|---|---|
| **Systems** | Every Gate System at a glance: this Gatekeeper (its servers and who is on each, whether an update is ready, requests waiting on you), then a card for each machine in its constellation, marked with the halves it has (Gatehouse, Gatekeeper, or both), with a line for each: in game and on which server, in the launcher, or offline; its version and update mode; its ping to this Gatekeeper; its battery (a laptop or a Steam Deck); whether it hands out pack updates; when it was last seen. Filters: All, Online, In game. Needs *See the constellation*. |
| **Servers** | Start, stop and restart; a server's console, backups and tasks. |
| **Players** | Players and the whitelist; launchers, their requests and permissions. |
| **Chat** | Every room as a chip with a count of new messages, the messages as bubbles, pictures shown in the page and other files to save. You write as Host. |
| **Updates** | The update log. |

| An account that may... | Gets |
|---|---|
| See | Servers and their state, players, the activity log |
| Start and stop | Start, Stop and Restart for servers; Start and Close MCSS |
| Console | A server's console and typing into it; Kick |
| Settings, backups, tasks | Back up now, running a task, settings that name nothing on the disk |
| Launchers and players | Answering requests, permissions, the whitelist, chat |
| Sleep and wake | Sleep for this machine, Wake for the devices you listed |
| See the constellation and its update log | The constellation's clients and the update log |
| Admin | All of those, and Update Gatekeeper and New server |

Accounts are made only at the machine. The panel can't do Server setup, touch Gatekeeper's access to MCSS, or set
anything that names a program or folder on the machine. To wake other computers from it, add each by name and MAC
address under **Web panel**; they must be on the same home network and set to wake on a Wake-on-LAN signal.

**The status page** (Options, **Share the status page**) is a read-only page for friends at
`http://<Tailscale address>:25570/status`: which servers there are, which are up, and who is playing. No sign-in,
no buttons, no addresses or folders. Off until you turn it on.

**Phone notifications** (Options) go through [ntfy](https://ntfy.sh): install the ntfy app and subscribe to the topic
Options shows. You choose which: a friend joins a server, a server goes down, an update needs you (or Gatekeeper froze
and restarted itself), someone writes in chat (at most one a minute for each room), and a Gate System updates
(those that update within a minute of each other come as one message). What is sent is the server's
and player's names and a sentence; for chat, who wrote, the room and the first 140 characters of the message. Off until
you turn it on. An admin account can set all of this from the web panel too (menu, Options): turn it on, pick the
kinds, see and copy the topic, open it straight in the ntfy app on that phone, make a new topic, and send a test.

## Updates and constellations

**Gatekeeper's own updates.** When a newer one is out, the Servers section says so, with a chime and **Update
Gatekeeper**. It fetches the file, checks its checksum and release signature, swaps it in, and checks the new one
answers within 30 seconds, or puts the old one back. Servers MCSS runs aren't touched. Under **Options, Updating**:
**Normal** (press Update), **Auto** (by itself, waiting only for servers it runs to be off) or **Constellation**.
A Gatekeeper older than the oldest version still supported starts no server until it is updated.

**A constellation** is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that joined
it, and their launchers. The **Constellation** page lists each with its kind, player, system, version, whether it is
open (and handing out pack updates), and its update mode. In constellation mode, when a new version is out the
Gatekeeper waits until every open client in constellation mode has seen it and no server it runs is on (at most ten
minutes), then gives the go and they all update at once, to a four-note chime. Every client shows a row of pips
while it gathers: green ready, amber not seen yet, red a Gatekeeper with a server still on.

- **Joining another Gatekeeper's constellation** (a PC and a Steam Deck, say): on the one to join, type the other's
  address or pairing link and press **Join**; on the leader, allow it in. Joining gives no control of a server or a
  setting either way. **Leave** or **Remove** ends it.
- **Passing an update round.** A constellation fetches a new program from outside once: whoever sees it first
  fetches it and hands it on. Every copy is checked against its checksum and the release signature, whoever hands it
  over. **Share program updates with that Gatekeeper** turns this off for a Gatekeeper you joined.
- **Managing another Gatekeeper** is off unless the owner of the one to be managed chooses it, at that machine. The
  other then gets a window on its servers, console, players and requests; never its settings, updates, web accounts
  or the machine.
- **Hand out pack updates** (on unless turned off): this machine hands the published pack to the devices of this
  constellation over Tailscale ([Security](security.md#updates-from-any-gate-system)).
- **The update log** (Updates section, or **Update log...**): every update in the constellation, when, from which
  version to which, how, and which machine it came from. The newest 5000 lines.

## Systems and the history of servers

A **system** is what one machine has of the program: its Gatehouse, its Gatekeeper, or both. **Systems** lists the
machines in a constellation with this one (neighbors), the ones you chose to remember (they stay listed after the
constellation is left; each side decides for itself, and it gives the other nothing), and launchers let in nowhere.
**History of servers** keeps a line for each server a Gatekeeper made, started or stopped looking after, and each
new pack version. Gatekeepers that join pass their lines on, so a new one learns the history by joining. It holds no
folders, player names or keys.

## Options, ending and removing it

**Options** has the pictures (one for this Gatekeeper and each server, shown on launchers' cards), the update mode,
notifications, the status page, which servers start with Gatekeeper, **End Gatekeeper** (refused while a server it
runs is on; start it again from the launcher's **Start Gatekeeper**, its window, or by signing in again) and
**Uninstall** (type `confirm`; a tick box decides whether its settings go too: who may do what, its MCSS key, its
log). Removing Gatekeeper leaves MCSS, your servers and their worlds untouched. From outside: the install script
with `-Uninstall` / `--uninstall`.

Gatekeeper keeps its settings in its folder (`host.json`, `devices.json`, `players.json`, ...), each written whole
before it replaces the old one, with the last ten copies of the important ones kept in `backups`. Its log is
`host.log` there.
