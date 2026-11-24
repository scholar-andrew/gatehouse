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
| **Chat** | The rooms (this Gatekeeper and each server), where you write as Host, paste or attach files, view what others offered (a video or sound plays in Gatehouse's player: see [Using Gatehouse](gatehouse.md)), and can empty a room. |
| **Updates** | What's new in Gatekeeper and in Gatehouse, **Updates pulled** (what launchers fetched from this machine), and the **Update log**. |

The **menu** at the top left has the rest:

| Menu | What is there |
| --- | --- |
| **Host setup** | Every check this machine needs before players can use it, each done, to do, or worth changing, with what to do; Gatekeeper's and MCSS's own lines (Start and Close MCSS, Sign in to MCSS); **Share pairing**; the name launchers show for this Gatekeeper. A red number is steps still to do. |
| **Activity log** | Everything Gatekeeper did and was asked, newest first. |
| **Web panel** | Turning the panel on, and its accounts ([below](#the-web-panel-the-status-page-and-phone-notifications)). |
| **Gatekeepers**, **Constellation**, **Systems**, **History of servers** | [Below](#updates-and-constellations). |
| **Options** | Pictures, update mode, notifications, the status page, starting servers with Gatekeeper, ending and uninstalling. |
| **Setup guide**, **Open Gatehouse** (yellow castle) | The steps one at a time; Gatehouse on this machine, or the first screen to set it up (3.54.0; it was Play or Host). |
| **User guide**, **GitHub page** | Open the [user guide](user-guide.md) or the program's GitHub page in your browser (the web panel's menu has them too). |

Questions, messages, choosing a file and pages such as New server, a server's pack and Add a Gatekeeper open inside the window on a dimmed layer (3.56.0), not in windows of their own; **Close**, Esc or B goes back. The desktop and taskbar icon is the blue castle the window shows.

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
| **Pack** | The pack players get for this server, in sub-tabs (3.54.0): **Pack** (its state, New version, Build again, Bring in), **Mods** (Mods players get, Find mods on Modrinth), **Who it's for** (its visibility and invites), **Pictures** ([below](#packs-gatekeeper-builds)) and **Find a modpack** (3.56.0): say what you want in your own words, and this Gatekeeper's AI (Ollama or the built-in one, whichever is set up and on; otherwise it says what to switch on) turns it into Modrinth searches, then picks the Fabric modpacks that fit best from Modrinth's facts about them, each with why, pros, cons and Open on Modrinth, and says which it would choose. It installs nothing. Sent to Modrinth: the search words; to the AI: your words and those facts. A small AI's notes can be wrong, so check the pack's page. |

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

Each server is **private** (the default), **shareable** or **public**, set on its **Pack** tab (**Who it's for**):

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
and the server's picture, unless you choose your own. The same picture list has **In launchers** first: the picture
on this server's card in launchers (the same one as Options, Pictures), which changes at once (3.46.0). An animated
GIF (8 MB at most) plays on the card while it is under the pointer or chosen with a controller, in Gatekeeper and in
Gatehouse, and stands still otherwise (3.48.0). **New version** takes the notes players read. Launchers install
and update it from this Gatekeeper like any other pack. These packs aren't signed, so they are never handed out
between machines.

**Find mods on Modrinth…** (3.41.0) searches Modrinth for Fabric mods that fit the server's Minecraft version, shows
how many people use each and whether players need it too, and **Add to the server** puts the chosen one in the
server's `mods` folder, with the mods it says it requires. Each file comes from Modrinth's own file server and is kept
only if it matches the checksum Modrinth publishes. It opens on the most downloaded mods that fit; words narrow it
down. It works for every Fabric server whose folder Gatekeeper knows, MCSS's too (3.48.2); a mod added to an MCSS
server is loaded the next time MCSS starts it. Nothing reaches players until the server has restarted and you use **Mods players get…** and
**New version…**. Only the search words and the Minecraft version are sent to Modrinth.

## The AI helper

If [Ollama](https://ollama.com) runs on the Gatekeeper machine with the model `qwen2.5:3b`, Gatekeeper can use it
(3.41.0; Options, **AI helper**). Gatekeeper talks to Ollama directly; nothing else needs installing. Nothing leaves the
machine: the model runs locally, and Gatekeeper talks to it on 127.0.0.1.

**Using an Ollama you already have** (3.42.0): in Options, AI helper, **Ollama at** is its address
(`http://127.0.0.1:11434` is Ollama on this machine) and **model** lists the models it has; press **Use these**. The
line under them says whether it answers and has that model, and what to do if not (`ollama pull <model>`). An Ollama on
another machine works too, if it listens beyond its own machine (`OLLAMA_HOST=0.0.0.0` there): the questions, and the
facts about your servers sent with them, then go to that machine.

- **In chat**, a message starting with `@ai` gets an answer from "Gatekeeper AI" in the same room, about the servers
  (which are on, who is on them, who may join, how to join). One question at a time per room.
- **It reads the room it is asked in** (3.45.0): it can answer "who has shared files here?", "how many messages has Sam
  sent?" or "how many people mentioned diamonds?". Gatekeeper counts those itself (messages per person, files shared,
  who used a word) and gives the model the exact numbers along with the room's last 60 messages, so a small model
  doesn't have to count. It only ever sees the room it was asked in.
- **When this Gatekeeper's AI is off or has no model** (3.45.0), `@ai` no longer goes unanswered. If this Gatekeeper
  joined another one (a constellation) whose AI helper is on, the question is passed to it and its answer is posted
  here. You can untick this under AI helper: "When this Gatekeeper has no AI of its own, ask the Gatekeeper it joined".
  Otherwise Gatekeeper AI says in the room that the helper is off here, at most once every 10 minutes.
- **When a server Gatekeeper runs stops by itself**, the error lines at the end of its log are explained in plain words,
  in the log and in the "server went down" notification. If the log shows no error, it says so instead of guessing.

- **Help, Ask Gatekeeper AI…** (3.42.0; also a button under AI helper) is for your own questions about Gatekeeper:
  which version this is, where its GitHub page and guide are, which section and button does something, and where your
  files are on this machine (Gatekeeper's folder, each server's folder and backups). Folders are given to it only here,
  never for a chat room, which players read.

The answers are a small model's guesses: useful as a pointer, not a diagnosis.

### The built-in AI (opt-in)

For a machine without Ollama, Gatekeeper can run a small AI of its own (3.45.0). Under Options, AI helper, **Built-in
AI**:

- Nothing is downloaded until you press **Get the built-in AI…**. A dialog first lists what it fetches: llama.cpp's Java
  binding (`de.kherud:llama` 4.2.0, 12.2 MB, from Maven Central) and the model Qwen2.5 1.5B Instruct (Q4_K_M, 1.12 GB,
  from Qwen's Hugging Face page, Apache 2.0 licence). Each file is checked against a SHA-256 written into the signed
  program before it is kept. The model is checked again before it is first used after each start.
- **Answers from the built-in AI** then replaces Ollama for this Gatekeeper's answers. It runs on the processor (about
  1.5 GB of memory while answering) and is unloaded after 10 minutes without a question. **Answers from Ollama** switches
  back.
- **Remove the built-in AI** (opting out) deletes the engine and the model, and answers come from Ollama again. On
  Windows, once the engine has been used its files stay locked until Gatekeeper stops. In that case the built-in AI counts
  as removed straight away, and its last files are deleted the next time Gatekeeper starts.
- **Battery machines** (laptops) can get it, but are warned first, under the buttons and in the dialog: plug in before
  downloading, the download can be costly on a metered connection, and the battery drains faster while it answers.
- **Handheld PCs can't get it.** On a Steam Deck, ROG Ally, Legion Go, MSI Claw, GPD, AYANEO, OneXPlayer, AOKZOE or
  Zotac Gaming Zone, the controls are greyed out with the reason: the download and the work are too heavy for their
  battery and memory. A handheld Gatekeeper can still answer `@ai` through the Gatekeeper it joined (above).
  Gatekeeper recognises a handheld from what the machine reports about itself (maker, model, SteamOS). It also needs a
  64-bit x86 Windows or Linux machine.

Alternatives (untried here): running Ollama or LM Studio on the machine gives a choice of bigger models; llamafile is a
single-file llama.cpp. The built-in AI needs neither but is smaller, so its answers are plainer.

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
the window. A section the account may not use is shown greyed; the rest is in the menu at the top left. Since 3.58.0
the browser tab is named after the Gatekeeper ("PC · Gatekeeper"), and on **Systems** each machine's castle is in its
programs' colours: yellow for a Gatehouse, blue for a Gatekeeper, half of each (split on a slant) for a machine with
both; chat times show in the phone's own time. Since 3.60.0 a link in a chat message gets **Open link** and **Show preview**: only when that is pressed does Gatekeeper fetch the page (never this machine, the home network or Tailscale addresses; at most 30 a minute) and the panel show its title, site, description and a small picture. A link straight to a picture or a video or sound file gets **Show picture** or **Play**, a YouTube video **Play here** (YouTube's privacy-enhanced player), and an X post **Show post** (the post with its photos and video, and the post it quotes as a card under it with its own); those come from their own sites to the phone, once pressed. On a phone the chat area is taller, and pictures and players smaller. A phone can
keep it on its home screen (Share, Add to Home Screen; or the browser's menu, Install), where it opens like an app.

| Section | What is there |
|---|---|
| **Systems** | Every Gate System at a glance: this Gatekeeper (its servers and who is on each, whether an update is ready, requests waiting on you), then a card for each machine in its constellation, marked with the halves it has (Gatehouse, Gatekeeper, or both), with a line for each: in game and on which server, in the launcher, or offline; its version and update mode; its ping to this Gatekeeper; its battery (a laptop or a Steam Deck); whether it hands out pack updates; when it was last seen. Filters: All, Online, In game. Needs *See the constellation*. |
| **Servers** | Start, stop and restart; a server's console, backups and tasks. |
| **Players** | Players and the whitelist; launchers, their requests and permissions. |
| **Chat** | Every room as a chip with a count of new messages, the messages as bubbles, pictures shown in the page, videos and sounds played in it (pause, skip 10 seconds, volume, theater, full screen, with the usual media icons; the player is as wide as its video, not the whole chat), and other files to save. You write as Host. |
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
  or the machine. The menu's **Gatekeepers** page (3.47.0) lists this Gatekeeper and every other one it may manage,
  like Gatehouse's list: each with its address, how many servers it has and how many people are on them, and
  **Open**. **Add a Gatekeeper...** (3.48.0) walks you through putting another one there, a step at a time: one
  Gatekeeper joins the other, is let in, and lets the other manage it. It does here what can be done here, tells you
  what to press on the other machine, and moves on by itself as each step is done.
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

## Without Tailscale (3.49.0)

Host setup is in sub-tabs (3.58.0): **Steps** (what this machine still needs, with Server setup and Share pairing), **Network** (how devices reach it, HTTPS, the public name and Tailscale's invite link) and **Name** (what launchers call it). Its **Network** tab has **Devices reach this Gatekeeper over**: **Tailscale** (as before), **Home network** (only devices at
home, on the same Wi-Fi or cable), or **Another private network** (ZeroTier, NetBird, Headscale, Radmin VPN and the
like, untried here). Choose it and one of this machine's addresses on it (listed with its network adapter), and Save;
launchers paired before need the new pairing (Share pairing). Off Tailscale, Tailscale's own setup steps and its
invite link are left out, and Gatehouse's Connect steps skip Tailscale. On a home network the web panel stays off
unless HTTPS is on, and Gatehouse's hand-outs between devices only run over Tailscale for now:
devices update from the Gatekeeper or GitHub instead.

**Use HTTPS** (3.50.0, beside it) makes Gatekeeper's own certificate and speaks only HTTPS on that address, on any
of the three networks. Launchers need the new pairing afterwards (it carries the certificate's fingerprint, which
Gatehouse checks), and launchers older than 3.50.0 can't use it. **New certificate...** makes another (every launcher
then pairs again). [Security](security.md#the-network-tailscale-or-your-own) says what is checked.

**The internet, through your router** (3.51.0) lets players reach Gatekeeper without Tailscale or a private network.
Choose it and this machine's home-network address, fill in **Public name** (your home's internet address, or better a
free dynamic name that follows it, such as DuckDNS or No-IP, untried here), and forward the ports in your router: the
setup steps list them (Gatekeeper's port and each Minecraft server's) and the address to forward them to. HTTPS is
always on, new launchers need the pairing (it carries a join key) or an invite, requests are limited per address, and
the web panel and status page stay off. **New join key...** stops old pairings letting newcomers in.

## Waking this machine from outside the home (3.61.0)

A sleeping machine only hears its wake signal inside its own home network, so something there has to pass a request
from outside on to it. **Host setup**, **Wake** sets up two ways, and they work together. Launchers whose player has
**Machine wake/sleep** learn them while this machine is awake and keep them, so their **Wake** button works from
anywhere after that. On Windows the network card has to be allowed to wake the computer (Device Manager, the adapter,
Power Management, "Allow this device to wake the computer" and "Only allow a magic packet"), and often the BIOS too.

- **Network card**: the adapter that listens. Gatekeeper finds the one on the home network by itself; fill it in only
  if it picked the wrong one.
- **Through your router**: forward a UDP port (9 is usual) in your router to the home network's broadcast address
  (192.168.0.255, say), or to this machine's address if the router keeps a fixed entry for it (many routers forget a
  sleeping machine otherwise). Then fill in your home's public name and that port, like `myhome.duckdns.org:9`. No
  other device is needed, but not every router allows it, and the signal carries no secret: anyone who finds the port
  can wake the machine (and do nothing else).
- **Through a machine that stays awake (a relay)**: another machine at home that is always on (a laptop, a Raspberry
  Pi, an old PC) runs Gatekeeper and wakes this one. Press **Make a relay code...** here and paste the code into that
  machine's Gatekeeper (Host setup, Wake, **Wake another machine from here**) while this machine is awake; the relay
  then tells this machine where it is. Launchers reach the relay the way they reach any Gatekeeper: over Tailscale, or,
  facing the internet, with the relay's own Gatekeeper port forwarded in your router (a different port from this
  machine's). Each launcher gets a ticket for each relay that is good for 30 days and only for that launcher; the relay
  checks it and sends nothing otherwise. **Remove** on either side ends it. The code holds the relay's secret: send it
  only to yourself.

## Times (3.59.0)

Every time Gatekeeper keeps or sends is a unix time: the exact moment, in milliseconds since 1970 (UTC), in a field
named `at` or ending `_at` (`last_seen_at`, `since_at`, `made_at`...). It is the same number on every machine, so
comparing two times ("seen in the last 7 days", "the same update heard twice") never depends on which machine's clock
wrote them. Only screens turn it into words, in the viewing device's own time zone: Gatekeeper's window, a Gatehouse,
the web panel and the status page. Records written before 3.59.0 hold a text time in the Gatekeeper machine's clock;
Gatekeeper reads those as its own clock, and other devices show them as they are. New records still carry the old
text beside the unix time, so older Gatehouses have something to show. Log lines (`host.log`) start with the unix time,
then the machine's time for reading. Left as they are, on purpose: file names (backups, safety copies, saved pictures),
daily tasks (they run at the clock time you set), Minecraft's own `eula.txt` line, the certificate's dates (that
format is fixed by the standard) and a version's day in the notes.

## Options, ending and removing it

**Options** is in sub-tabs (3.46.0): **General** (about, the pictures for this Gatekeeper and each server shown on
launchers' cards, this machine's kind), **Updating**, **Notifications** (phone, the status page, crash watch), **AI
helper**, and **End or uninstall**. Esc or the menu leaves it. Options also has which servers start with Gatekeeper, **End Gatekeeper** (refused while a server it
runs is on; start it again from the launcher's **Start Gatekeeper**, its window, or by signing in again) and
**Uninstall** (type `confirm`; a tick box decides whether its settings go too: who may do what, its MCSS key, its
log). Removing Gatekeeper leaves MCSS, your servers and their worlds untouched. From outside: the install script
with `-Uninstall` / `--uninstall`.

Gatekeeper keeps its settings in its folder (`host.json`, `devices.json`, `players.json`, ...), each written whole
before it replaces the old one, with the last ten copies of the important ones kept in `backups`. Its log is
`host.log` there.
