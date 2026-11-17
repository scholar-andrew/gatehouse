# How to use Gatehouse

Gatehouse is one program with two halves. This page takes you from nothing installed to playing on a server (the
**Play** half), to running one your friends can join (the **Host** half), or both on the same machine. The program
also carries these steps inside it as **setup guides** that check each step for you; this page is the same road,
written out.

- [One program, two halves](#one-program-two-halves)
- [For players: Gatehouse](#for-players-gatehouse)
- [For hosts: Gatekeeper](#for-hosts-gatekeeper)
- [Hosting and playing on the same machine](#hosting-and-playing-on-the-same-machine)
- [Updates](#updates)
- [Removing it](#removing-it)
- [What works on which system](#what-works-on-which-system)
- [When something doesn't work](#when-something-doesnt-work)

## One program, two halves

| | Play: the Gatehouse launcher | Host: Gatekeeper |
| --- | --- | --- |
| Who sets it up | Each player, on the device they play on | The host, on the machine that runs the Minecraft servers |
| What it does | Lists the servers you can join, installs and updates each server's mods, shows what changed, and starts the game | Lets the launchers you allow see, start and stop your servers, and decides who may do what |
| How it runs | A window you open when you want to play | In the background from the moment you sign in; its window is only for looking and changing things |
| Works with | [Prism Launcher](https://prismlauncher.org/), which runs Minecraft | On Windows, [MC Server Soft](https://www.mcserversoft.com/) (MCSS), or Gatekeeper running the servers itself. On Linux and SteamOS, Gatekeeper runs them itself |

It is the same program file either way, with one version number. A device sets up the half it needs: most people
only Play, the host sets up Host, and a host who also plays sets up both. The **Play or Host** screen (in
Gatehouse's Options, and at the bottom of Gatekeeper's first screen) shows which halves a device has, opens either,
and sets up the one it doesn't have yet.

**Which shortcut opens what.** **Gatehouse** in the Start menu or the applications menu opens the Play or Host
screen, where you pick a half. **Gatehouse in your Steam library** goes straight to the launcher, ready to play.

Launchers and Gatekeepers on different devices talk over [Tailscale](https://tailscale.com/), a private network
between the host's machine and the devices the host lets in. Nothing is opened to the internet, and no router
settings are changed.

Neither half needs administrator rights or the system password. It is a plain Java program installed by scripts you
can read first.

---

## For players: Gatehouse

### What you need

- **Minecraft: Java Edition** on your Microsoft account.
- **Something from the host**: a pairing link, a pairing file, or their Gatekeeper's address.
- A device running **Windows 10 or later**, or **Linux**, including a **Steam Deck**.

### 1. Install Gatehouse

**Windows.** Download
[gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip),
unpack it, and double-click **Install Gatehouse.cmd**. When it asks what to install, choose **Gatehouse**. It offers
to install Prism Launcher and Java if you don't have them, and adds **Gatehouse** to the Start menu.

**Linux, including Steam Deck.** On a Steam Deck, switch to Desktop Mode and open Konsole. Then:

```bash
curl -fsSLO https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh
```

```bash
bash gatehouse-install.sh
```

It offers Prism Launcher from Flathub if it's missing, and adds **Gatehouse** to the applications menu. It also
offers to add Gatehouse to your Steam library, so it's there in Game Mode on a Steam Deck (Steam has to be closed
while it does that).

Both scripts say at the top exactly what they change, and `-DryRun` (Windows) or `--dry-run` (Linux) shows what
would happen without doing it.

> Playing on **October Journey**? Its own setup program installs Gatehouse, the modpack and Steam shortcuts in one
> go: see the [README](../README.md#download-october-journey).

### 2. Open Gatehouse and follow the setup guide

The first time, Gatehouse opens on its **setup guide**. Each step says what to do, has a button for it where there is
one, and shows a mark: green when it's done, red when it isn't. The guide looks again every few seconds, so a step
ticks by itself once you've done it. You can leave the guide at any point; it's always under **Options**, in
the top bar.

The steps:

1. **Prism Launcher.** Done when Gatehouse finds it. If you just installed it, press **Restart**.
2. **Your Minecraft account.** Press **Open Prism Launcher**, then in Prism: Accounts, Add Microsoft, and sign in.
   Gatehouse never sees your password.
3. **Add the host's Gatekeeper.** Press **Add Gatekeeper...** and paste the link or address the host gave you, or
   open the pairing file they sent.
4. **Connect to it.** If the Gatekeeper can't be reached yet, Gatehouse shows what's missing:
   - Install [Tailscale](https://tailscale.com/download) and sign in with your own account (it's free). On a Steam
     Deck this is done in Desktop Mode, following Tailscale's guide for the Deck.
   - Accept the host's invite. **Open invite** opens it in your browser when the pairing came with one. It gives
     your device access to the host's one machine, nothing else.
   - Wait for the host to allow your device. A new device can do nothing until they do.
5. **Install a server.** Open the Gatekeeper, pick a server and press **Install**. Gatehouse says where the files
   come from and asks first.
6. **Play.** Press **Launch** beside the server. Its mods are brought up to date in the same window, then **Play**
   starts the game.

### Day to day

Gatehouse is laid out like a game's menu. Five sections run across the top; click one, or flip between them with
the bumpers on a controller (Q and E on a keyboard):

| Section | What is there |
| --- | --- |
| **Servers** | The Gatekeepers this device knows, as cards, each with a picture of the machine it runs on. **Open** one to see its servers; **Connect** if it isn't answering. Each server's card says whether it's on and has **Install** or **Launch**. Launch opens the server's own page: what changed in the pack and on the server, your mod options, and **Play**. |
| **People** | What this device may do on each installed server: whether it is on the whitelist, and what the host has allowed it. |
| **Constellation** | How this launcher updates ([three ways](#three-ways-to-update)), **Check for updates**, and what changed in Gatehouse and in the open Gatekeeper. |
| **Machine** | The host's machine, MCSS and each server, with the buttons the host has allowed your device: start or stop a server, start or close MCSS, wake the machine or put it to sleep. |
| **Options** | The setup guide, Play or Host, and Uninstall. |

A server's own page is laid out the same way, with its own sections across the top: **Updates** (what changed in
the pack on this device), **Server news**, **Connection** (the server's controls and a network check), **Server**,
**Mods** (your optional mods), and **Gatekeeper** when the host has allowed your device Gatekeeper control. **Play**
is at the bottom right, and **B** (Esc) or the **Gatehouse** button goes back to the menu.

The card you are on glows. A strip along the bottom shows the version and update mode on the left, and what the
buttons do on the right: **A** (Enter) presses what is selected, **B** (Esc) goes back, **X** selects Open Prism
Launcher, **Y** Restart and **Start** Quit. It shows a controller's buttons or the keyboard's keys, whichever you
used last. Everything can also be clicked.

If a button you need isn't there, the host hasn't allowed it for your device. Where a server's page offers a
**Request** button (to be whitelisted, or to have the server started), pressing it tells the host.

---

## For hosts: Gatekeeper

### What you need

- A machine that stays on while people play: **Windows 10 or later**, **Linux**, or **SteamOS**.
- **Java 17 or newer** (your Minecraft servers need Java too).
- On **Windows**: **MC Server Soft** (MCSS). It is Windows only.
- On **Linux and SteamOS**: your Minecraft server's own files (its jar). Gatekeeper runs it itself.
- A free **Tailscale** account.

### 1. Install Gatekeeper

**Windows.** Download
[gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip),
unpack it, double-click **Install Gatehouse.cmd**, and choose **Gatekeeper** (or **Both** if you also play on this
machine). Gatekeeper starts when you sign in to Windows, with no window, and **Gatekeeper** in the Start menu opens
its window.

**Linux and SteamOS.** On a Steam Deck, switch to Desktop Mode and open Konsole. Then:

```bash
curl -fsSLO https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh
```

```bash
bash gatehouse-install.sh --gatekeeper
```

It fetches Gatekeeper, checks it against its published checksum, and installs it for your user: a background
service that starts when you sign in, and **Gatekeeper** in the applications menu. On SteamOS it uses the Java that
Prism Launcher fetches, so install Prism Launcher from Discover and open it once first.

> Gatekeeper on SteamOS is in use on one Steam Deck. A plain Linux desktop hasn't been tried yet. See
> [What works on which system](#what-works-on-which-system).

### 2. Follow the setup guide

Gatekeeper's window opens on its **setup guide** the first time. As in Gatehouse, each step shows whether it's done
and ticks by itself. It's always under **Options** in the top bar, and **Machine** (Host setup) shows the same checks
all at once.

Gatekeeper's window has the same five sections across the top as the launcher (click one, or flip with Q and E, or
a controller's bumpers):

| Section | What is there |
| --- | --- |
| **Servers** | MCSS and the list of servers, **New server**, **Server setup**, **Check for updates**. **Open** a server for its Access Manager, Server Status and Updates Pulled; **Manage** for the ones Gatekeeper runs itself. |
| **People** | The web panel's accounts, and **Launchers and players**: the Access Manager of the first server (for another server, open it under Servers). A red number here is launchers waiting for an answer. |
| **Constellation** | The clients and their update modes, joining and allowing, the update log; **What's new in Gatekeeper** and **in Gatehouse** beside it. |
| **Machine** | Host setup: every check at once. A red number here is steps still to do. |
| **Options** | **This machine** (the picture launchers show for this Gatekeeper: a desktop, a laptop, a Steam Deck...; found by itself, and yours to correct), how Gatekeeper updates, the setup guide, Play or Host, and Uninstall. |

Where this guide says a "tab" of Gatekeeper's first screen (Web panel, Constellation, Host setup), it is the page
of that name in these sections.

The steps on Windows (Linux and SteamOS differ in steps 4 to 8: see [below](#on-linux-and-steamos-the-servers)):

1. **Install Tailscale** from [tailscale.com/download](https://tailscale.com/download).
2. **Sign in to Tailscale** and make sure it says Connected.
3. **Keep this machine signed in.** In Tailscale's admin page, under Machines, open this machine's menu and choose
   **Disable key expiry**. Otherwise players lose the server after some months until you sign in again. On Windows,
   also turn on **Run unattended** in Tailscale's menu.
4. **Install MC Server Soft.** Unpack it somewhere permanent. It has no installer: the folder is the program.
5. **Make a server in MCSS.** Add Server, pick the type and version, start it once and accept Minecraft's EULA,
   then stop it.
6. **Turn on MCSS's web API.** In MCSS: Options, Web API, on, port 25560. Gatekeeper uses it on this machine only.
7. **Choose the servers.** **Server setup** tells Gatekeeper where MCSS is and which of its servers players should
   see. Tick them and Save.
8. **Let Gatekeeper use MCSS.** Open a server in Gatekeeper, and on its **Server Status** tab choose **Sign in to
   MCSS**. You sign in once; Gatekeeper makes a key of its own and doesn't keep your password.
9. **Give each server its own port**, if you have more than one. Host setup suggests free ports.
10. **Turn the whitelist on.** Host setup has a button that does it. With it on, only players you've allowed can
    join.
11. **Make an invite for players.** In Tailscale's admin page, under Machines, open this machine's menu, choose
    **Share**, and make a reusable invite link. Paste it into Host setup and Save. This shares only this machine,
    not the rest of your network.
12. **Invite a player.** **Share pairing** gives you a link and a small file. Send either to the player.

### On Linux and SteamOS: the servers

There is no MCSS on Linux, so Gatekeeper runs the servers itself, and does for them what MCSS does on Windows.
Steps 4 to 8 above become these (the MCSS steps stay in the guide, greyed and marked "Not supported on Linux"):

1. **Make a server.** On Gatekeeper's first screen press **New server**. Give it a name, pick its kind and the
   Minecraft version, and press **Make the server**:

   | Kind | For | Where its program comes from |
   |---|---|---|
   | Vanilla | The plain game | Mojang, checked against Mojang's checksum |
   | Paper | Plugins | PaperMC, checked against PaperMC's checksum |
   | Fabric | Mods | Fabric (which publishes no checksum for it) |
   | Forge or NeoForge | Mods | An installer you download yourself; Gatekeeper runs it |
   | A server jar I already have | Anything else | A file you pick |

   Gatekeeper finds a free port and a Java the version can run on. If no Java on the machine fits, it says which
   one is needed; it doesn't install Java. Nothing is fetched until you press the button.
2. **Agree to Minecraft's EULA.** New server has a box for it, with a button that opens
   [the EULA](https://aka.ms/MinecraftEULA). That is yours to do: Gatekeeper makes no server without the tick, and
   writes `eula.txt` only when you gave it.
3. **A server you already have** goes in a folder of its own inside `minecraft-servers` in your home folder (or
   point **Server setup** at where your servers are), then tick it in Server setup. Its EULA box is under
   **Manage**, Settings.

**Manage**, beside each server, is where the rest is:

| Tab | What it has |
|---|---|
| Console | What the server prints, a line to type commands into, uptime, memory, processor and players, and Start/Stop, Restart and End it now |
| Settings | Name, memory, port, which Java, an own start command, start with Gatekeeper, start again after a crash; and **Delete this server** (out of Gatekeeper only, or its folder too, which needs its name typed and can't be undone) |
| Game settings | The server's own `server.properties` (difficulty, game mode, how many may play...) |
| Backups | A zip of the world or of the whole folder, keeping the newest few. With the server on, it is told to hold its saving still while the copy is made |
| Tasks | Things done by the clock: restart, stop, start, back up or type commands, every day at a time or every so many minutes |

Two things to know:

- **Update Gatekeeper with the servers off.** A server's console belongs to the Gatekeeper that started it, so
  Gatekeeper won't update itself while one is on: stop it, update, start it again.
- **Tasks and crash handling need Gatekeeper running.** It is a background service, so it normally is.

**On Windows too.** Tick **Gatekeeper may also run servers itself, beside MCSS's** on Gatekeeper's first screen.
New server then makes servers Gatekeeper runs itself, in `C:\Minecraft\servers`, each with Manage, next to the ones
MCSS runs; every server says which of the two runs it. MCSS's own servers stay MCSS's (Manage is greyed for them),
and closing MCSS leaves Gatekeeper's own servers running. Without the tick, nothing changes. (Windows with no MCSS at
all: `"host": "builtin"` in `host.json` in Gatekeeper's folder.)

### The web panel

A page for controlling Gatekeeper from a browser, on any system, with or without MCSS. It is off until you turn it on.

1. In Gatekeeper, open the **Web panel** tab and press **New account**. Type a name and a password (10 characters or
   more), and tick what the account may do, or **Admin** for everything.
2. Tick **The web panel is on**, and copy the address beside it (`http://100.x.y.z:25570/panel/`).
3. Open that address in a browser on a device that is on your Tailscale network, and sign in.

The panel has the same five sections across the top as the windows (Q and E flip between them): **Servers** as
cards, **People** (Players, Launchers), **Constellation** (Clients, Update log), **Machine** (Sleep and wake, Log)
and **Options** (updating Gatekeeper, New server). A section the account isn't allowed is listed, greyed.
On a phone the sections fold away behind a menu button at the top right.

| An account that may... | Gets |
|---|---|
| See | Servers and their state, players, Gatekeeper's log |
| Start and stop | Start, Stop and Restart for servers; Start and Close MCSS |
| Console | A server's console and typing into it (where Gatekeeper runs the server itself); Kick |
| Settings, backups, tasks | Back up now, running a task, memory and other settings that name nothing on the disk |
| Launchers and players | Answering launchers' requests, their permissions, the whitelist |
| Sleep and wake | Sleep for the server machine, Wake for the devices you listed |
| See the constellation and its update log | The **Constellation** section: the clients with their update modes, and [every update in the constellation](#the-update-log) |
| Admin | All of those, and Update Gatekeeper and New server |

What to know about it:

- **Only Tailscale.** The panel listens on the machine's Tailscale address and nowhere else: not the internet, not
  the home network. It is plain `http`, which is safe there because Tailscale encrypts everything between devices.
- **Accounts are made at the machine only.** The web panel can't make, change or remove them. It also can't do
  Server setup, touch Gatekeeper's access to MCSS, or set anything that names a program or a folder on the machine
  (a server's own start command, its Java, an installer). Passwords are not kept, only a scrambled form that can't
  be turned back; five wrong ones lock that account out from that device for five minutes.
- **Waking other computers.** In the Web panel tab, add a device by name and MAC address (on Windows: Settings,
  Network & internet, the adapter's properties, Physical address). The server machine sends it a Wake-on-LAN signal
  when Wake is pressed, so the device must be on the same home network and set to wake on one: in Device Manager,
  the network adapter's Power Management ("Allow this device to wake the computer"), and often a setting in its
  BIOS. Those are yours to set. The panel can't wake the server machine itself, since it runs on it: a launcher on
  the home network does that.

### 3. Let players in

When a player adds your Gatekeeper, their launcher appears in a server's **Access Manager**. A new launcher can do
nothing until you allow it. You decide per device or per player:

| Permission | What it lets them do |
| --- | --- |
| Whitelist | Join the Minecraft server |
| Server control | Start and stop that server |
| MCSS control | Start and close MCSS itself (includes server control). Windows with MCSS only |
| Machine wake/sleep | Wake this machine and put it to sleep |
| Gatekeeper control | Manage other launchers' access from their own launcher |

**Suspend** and **Blacklist** take access away; **Lift** gives it back. When a player presses a Request button in
their launcher, your machine chimes and Gatekeeper shows what they asked for.

---

## Hosting and playing on the same machine

Both halves can be set up on one machine, and both are on at once: Gatekeeper keeps running in the background while
you play, and never needs its window open.

- **Nothing to pair.** When the launcher opens and finds a Gatekeeper running on the same machine, it links itself
  to it: no pairing link, no request to answer, and no Tailscale needed between the two. The Gatekeeper appears in
  the launcher's list by itself (or the pairing you already had is used).
- **You are let in.** That launcher is allowed everything on that Gatekeeper (starting and stopping servers, MCSS,
  sleep, the Access Manager), on every server, and the account you play as in Prism Launcher is put on each
  server's whitelist. Play works straight away.
- **Gatekeeper is a button away.** The launcher's first screen shows **This machine hosts servers** at the top, with
  whether its Gatekeeper is running and an **Open Gatekeeper** button.
- **Only this machine's launcher.** A launcher on any other device still pairs and waits for you to allow what you
  choose, whoever plays on it.

## Updates

- **Mods and settings for a server** update by themselves each time a player presses Launch.
- **The program** tells you when a newer one is out: **Update Gatehouse** in the launcher, **Update Gatekeeper** in
  Gatekeeper. It fetches the new file, checks it, and restarts; Gatekeeper goes back to the version before if the
  new one doesn't answer. **Check for updates**, in both, looks right away instead of waiting.
- **One press covers both halves** on a machine that has both: updating either one updates the other.
- **A running server isn't interrupted.** If a server Gatekeeper runs itself is on, Gatekeeper's update waits and
  is applied by itself as soon as that server is off. Servers MCSS runs aren't affected by the restart.

Versions have to match for anyone to play:

- An out-of-date **Gatekeeper** starts no server until the host updates it.
- An out-of-date **launcher** isn't offered Play until that player updates it.

What changed is listed in the program: the **Gatehouse updates** and **Gatekeeper updates** tabs.

### Three ways to update

Each launcher and each Gatekeeper chooses for itself, under **Options**, **Updating**. Nothing changes unless you
choose: Normal is what a new install is in.

| Mode | What happens when a new version is out |
| --- | --- |
| **Normal** | It tells you, and updates when you press Update. |
| **Auto** | It updates by itself. A Gatekeeper looks every minute and waits only for a server it runs itself to be off. |
| **Constellation** | It waits for the rest of its group, and they all update at the same moment. |

### Constellations

A **constellation** is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that have
joined it, and their launchers. The Gatekeeper leads it. Its **Constellation** tab lists every client with its
kind, player, system, version, whether it is open now, and which update mode it is in, with a count of each mode.

**Updating together.** When a new version is out, a Gatekeeper in constellation mode gathers its constellation: it
waits until every client that is open and in constellation mode too has seen the new version, and until any server
it (or a member Gatekeeper) runs itself is off. Then it gives the go, and they all update at once. A client that is
closed, or in another mode, isn't waited for. It doesn't wait for ever either: after ten minutes it goes with
whoever is ready.

- **Pips.** While it gathers, every client in it shows a row of small circles, one for each client being taken
  along, and a line such as "Waiting for 2 of 5 others". Green is ready, amber hasn't seen the update yet, red is a
  Gatekeeper with a server still on. Your own has a white ring. Pointing at the row says who is who (to the host
  and to members; other players see the circles without names).
- **Word travels.** Whichever client sees the new version first passes it on, up to the leading Gatekeeper and
  down to the members, so the constellation doesn't wait for each one's own next look.
- **The chime.** When everyone is ready, the Gatekeeper and every client told to go play the constellation's chime:
  four soft rising notes. A launcher lets it finish before it closes to update.

**Joining another Gatekeeper's constellation.** A host with more than one Gatekeeper (a PC and a Steam Deck, say)
can make them one constellation. On the Gatekeeper that is to join: Constellation tab, type the other Gatekeeper's
address (`100.x.y.z:25570`) or its pairing link, and press **Join**. On the leading Gatekeeper it then shows as
"Asked to join": click it and press **Allow into this constellation**. From then on the member updates on the
leader's go, and its launchers are listed with the leader's. Joining gives no control of a server or a setting: a member can't
start, stop or change anything on the leader, or the other way round. **Leave** on the member, or **Remove from
this constellation** on the leader, ends it.

**Passing an update round.** A constellation fetches a new version from outside once. Every Gatekeeper keeps the
newest program file it has heard of and gives it to its launchers and to Gatekeepers that joined it. Whoever sees
a new version first raises its hand (the others then wait for that copy instead of fetching one each), fetches it,
and hands it over; a Gatekeeper that joined another passes it on to that one. A launcher asks the Gatekeepers in
its list before it looks anywhere outside.

- **Who may hand a file over** to a Gatekeeper: the launcher on the same machine, a launcher with Gatekeeper
  control, and a Gatekeeper allowed into its constellation. What arrives must be a Gatekeeper that can run, newer
  than the one taking it, and match the checksum sent with it.
- **What that means for trust.** Nothing checks who made the file; it is trusted because the client handing it over
  is. Allowing a Gatekeeper into your constellation therefore also trusts it to pass program updates, both ways. On
  a Gatekeeper that joined another, **Share program updates with that Gatekeeper** (Constellation page) turns it
  off for that Gatekeeper: it then fetches its own.
- **A machine with both halves** fetches once: the launcher hands its file to its Gatekeeper, and Gatekeeper puts
  its file where the launcher keeps its own.

**The same device twice.** A launcher paired with both the leader and a member would show up two ways. It is listed
and counted once, with the other way beside it ("also ...").

### The update log

Each Gatekeeper keeps a log of every update in its constellation: when, which client, its kind, player and system,
from which version to which, how (by hand, auto or constellation), and **where the update was fetched from** (the
pack server at an address, or GitHub). A member Gatekeeper hands its lines on to the leader. **Update log...** on
the Constellation tab shows it; it is kept in `updates.json` in Gatekeeper's folder, the newest 5000 lines.
"Not said" means the client didn't know (a version from before the log existed, or a launcher that arrived with a
pack's own update or an installer). Through the web panel only an account given **See the constellation and its update log** can read it;
at the machine itself, anyone who can open Gatekeeper's window can.

## Removing it

In either half: **Options**, **Uninstall**, and type `confirm`. Each half is removed on its own, so a
machine with both can keep one. A tick box decides whether what that half remembers goes too (for the launcher, its
Gatekeepers and this device's identity; for Gatekeeper, who is allowed what and its key for MCSS).

- Removing the **launcher** leaves Prism Launcher and the servers installed in it. Remove a server in Prism
  Launcher: right-click it, Delete.
- Removing **Gatekeeper** leaves MCSS, your servers and their worlds untouched.

The install scripts do the same from outside: `-Uninstall` on Windows, `--uninstall` on Linux.

## What works on which system

**Gatehouse** runs on Windows and on Linux, including the Steam Deck.

**Gatekeeper** runs on Windows, Linux and SteamOS. On Windows it works through MC Server Soft. MCSS is Windows
only, so on Linux and SteamOS Gatekeeper runs the servers itself. What a system can't do is still listed in
Gatekeeper where it would be, marked "Not supported on SteamOS" or "Not supported on Linux".

| | Windows | Linux | SteamOS |
| --- | --- | --- | --- |
| Seeing, starting and stopping servers | Yes, through MCSS | Yes, Gatekeeper runs them | Yes, Gatekeeper runs them |
| MCSS's own controls (Start MCSS, Sign in, MCSS control) | Yes | Not supported | Not supported |
| Updating while a server is on | Yes | Stop the server first | Stop the server first |
| New server and Manage (console, settings, backups, tasks) in Gatekeeper | Yes, for servers Gatekeeper runs itself (a tick box turns that on); MCSS keeps its own | Yes | Yes |
| Web panel with accounts; waking other devices | Yes | Yes | Yes |
| Access Manager, Host setup, setup guide | Yes | Yes | Yes |
| Starts when you sign in | Yes | Yes | Yes |
| Updates itself; Auto and Constellation modes; joining a constellation | Yes | Yes | Yes |
| Puts the machine to sleep from a launcher | Yes | Yes | Not supported |
| Gatekeeper in your Steam library | Yes | Not supported | Not supported |

On SteamOS: a Steam Deck that is asleep can't be woken from another device, so keep it plugged in and awake while it
serves. Tailscale doesn't officially support SteamOS, and a SteamOS update can remove it; Gatekeeper's Host setup
shows when that has happened.

**How far each has been tested.** Windows 10 is where both halves are used day to day, and both are used on one
Steam Deck (SteamOS): there Gatekeeper is installed, runs in the background, updates itself and belongs to the
Windows machine's constellation. Still tested only with stand-ins: a plain Linux desktop, the web panel in a real
browser, making a server from Mojang's, PaperMC's and Fabric's real downloads, and a real Forge installer. More
detail, with hardware: [INSTALL.md: Tested on](../INSTALL.md#tested-on).

## When something doesn't work

| What you see | What to do |
| --- | --- |
| A Gatekeeper says it isn't answering | Press **Connect**: it shows which step is missing (Tailscale, the invite, or the host's machine being off or asleep). |
| **Update needed** instead of Play | Your Gatehouse or the host's Gatekeeper is out of date. The line under the heading says which; update that one. |
| No Play, and a **Request** button instead | You aren't on the whitelist, the server is off and you may not start it, or you've been suspended. Press the button to ask the host. |
| The server won't start from a launcher | In Gatekeeper, check **Host setup**. Windows: MCSS has to be found, its web API on, and Gatekeeper signed in to MCSS. Linux and SteamOS: the server's folder needs its program and `eula=true`; if it starts and stops again, Gatekeeper's log has the last thing it said (often the wrong Java version or too little memory). |
| A new player's launcher never appears in Access Manager | Their device has to reach your machine over Tailscale. Check they accepted your invite, and on Windows that the firewall lets Java in on private networks. |
| A Gatekeeper that joined doesn't show in the leader's constellation | Both have to be on the current version. On the leader, look for it as "Asked to join" and allow it. The member's Constellation tab says what the leader last answered. |
| A constellation update never starts | The pips show who is waited for. Amber: that client hasn't seen the update (press **Check for updates** on it, or close it). Red: stop the server that Gatekeeper runs. It goes with whoever is ready after ten minutes. |
| Everything looked right and stopped working months later | Tailscale signed the machine out. Sign in again, and turn off key expiry for it (step 3 for hosts). |

Gatekeeper keeps a log, `host.log`, in its folder (shown under Options). For anything else, open an
[issue](https://github.com/scholar-andrew/gatehouse/issues).

---

More detail: [INSTALL.md](../INSTALL.md) lists everything the installers change on a computer and what runs on each
launch. [docs/server.md](server.md) covers Gatekeeper in depth. [docs/how-it-works.md](how-it-works.md) is for
maintainers.
