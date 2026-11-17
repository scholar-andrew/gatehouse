# How to use Gatehouse and Gatekeeper

This page takes you from nothing installed to playing on a server (if you're a player) or to running one your
friends can join (if you're the host). Both programs also carry this as a **setup guide** inside them, which checks
each step for you; this page is the same road, written out.

- [What the two programs are](#what-the-two-programs-are)
- [For players: Gatehouse](#for-players-gatehouse)
- [For hosts: Gatekeeper](#for-hosts-gatekeeper)
- [Updates](#updates)
- [Removing them](#removing-them)
- [What works on which system](#what-works-on-which-system)
- [When something doesn't work](#when-something-doesnt-work)

## What the two programs are

| | Gatehouse | Gatekeeper |
| --- | --- | --- |
| Who runs it | Each player, on the device they play on | The host, on the machine that runs the Minecraft servers |
| What it does | Lists the servers you can join, installs and updates each server's mods, shows what changed, and starts the game | Lets the launchers you allow see, start and stop your servers, and decides who may do what |
| Works with | [Prism Launcher](https://prismlauncher.org/), which runs Minecraft | On Windows, [MC Server Soft](https://www.mcserversoft.com/) (MCSS), which runs the servers. On Linux and SteamOS, nothing else: Gatekeeper runs the servers itself |

The two talk over [Tailscale](https://tailscale.com/), a private network between the host's machine and the devices
the host lets in. Nothing is opened to the internet, and no router settings are changed.

Neither program needs administrator rights or the system password. Both are plain Java programs installed by
scripts you can read first.

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
ticks by itself once you've done it. You can leave the guide at any point; it's always under **Options** (the wheel
at the top right).

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

Gatehouse's main window has three levels:

- **Gatekeepers**: the hosts this device knows. **Open** one to see its servers; **Connect** if it isn't answering.
- **Servers**: each server, whether it's on, and **Install** or **Launch**. The **Connections** tab beside it shows
  the host's machine, MCSS and each server, with the buttons the host has allowed your device: start or stop a
  server, start or close MCSS, wake the machine or put it to sleep.
- **The server's own page**: what changed in the pack and on the server, your mod options, and **Play**.

If a button you need isn't there, the host hasn't allowed it for your device. Where the page offers a **Request**
button (to be whitelisted, or to have the server started), pressing it tells the host.

Along the bottom: **Open Prism Launcher**, **Restart**, and **Quit**.

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

> Gatekeeper on Linux and SteamOS is new and has not yet been run on a real Linux machine or Steam Deck. See
> [What works on which system](#what-works-on-which-system).

### 2. Follow the setup guide

Gatekeeper's window opens on its **setup guide** the first time. As in Gatehouse, each step shows whether it's done
and ticks by itself. It's always under **Options** (the wheel at the top right), and the **Host setup** tab shows the
same checks all at once.

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
| Settings | Name, memory, port, which Java, an own start command, start with Gatekeeper, start again after a crash |
| Game settings | The server's own `server.properties` (difficulty, game mode, how many may play...) |
| Backups | A zip of the world or of the whole folder, keeping the newest few. With the server on, it is told to hold its saving still while the copy is made |
| Tasks | Things done by the clock: restart, stop, start, back up or type commands, every day at a time or every so many minutes |

Two things to know:

- **Update Gatekeeper with the servers off.** A server's console belongs to the Gatekeeper that started it, so
  Gatekeeper won't update itself while one is on: stop it, update, start it again.
- **Tasks and crash handling need Gatekeeper running.** It is a background service, so it normally is.

On Windows the same works without MCSS when `host.json` in Gatekeeper's folder says `"host": "builtin"`. With MCSS,
New server and Manage are greyed: MCSS does those for its own servers.

### The web panel

A page for controlling Gatekeeper from a browser, on any system, with or without MCSS. It is off until you turn it on.

1. In Gatekeeper, open the **Web panel** tab and press **New account**. Type a name and a password (10 characters or
   more), and tick what the account may do, or **Admin** for everything.
2. Tick **The web panel is on**, and copy the address beside it (`http://100.x.y.z:25570/panel/`).
3. Open that address in a browser on a device that is on your Tailscale network, and sign in.

| An account that may... | Gets |
|---|---|
| See | Servers and their state, players, Gatekeeper's log |
| Start and stop | Start, Stop and Restart for servers; Start and Close MCSS |
| Console | A server's console and typing into it (where Gatekeeper runs the server itself); Kick |
| Settings, backups, tasks | Back up now, running a task, memory and other settings that name nothing on the disk |
| Launchers and players | Answering launchers' requests, their permissions, the whitelist |
| Sleep and wake | Sleep for the server machine, Wake for the devices you listed |
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

## Updates

- **Mods and settings for a server** update by themselves each time a player presses Launch.
- **Gatehouse** looks for a newer version of itself and offers **Update Gatehouse**.
- **Gatekeeper** shows **Update Gatekeeper** when a newer one is out. It fetches it, checks it, restarts, and goes
  back to the version before if the new one doesn't answer. Running Minecraft servers aren't touched.

Versions have to match for anyone to play:

- An out-of-date **Gatekeeper** starts no server until the host updates it.
- An out-of-date **Gatehouse** isn't offered Play until that player updates it.

Each program lists what changed in it: the **Gatehouse updates** and **Gatekeeper updates** tabs.

## Removing them

In either program: **Options** (the wheel), **Uninstall**, and type `confirm`. A tick box decides whether what the
program remembers goes too (for Gatehouse, its Gatekeepers and this device's identity; for Gatekeeper, who is allowed
what and its key for MCSS).

- Removing **Gatehouse** leaves Prism Launcher and the servers installed in it. Remove a server in Prism Launcher:
  right-click it, Delete.
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
| New server and Manage (console, settings, backups, tasks) in Gatekeeper | In MCSS instead | Yes | Yes |
| Web panel with accounts; waking other devices | Yes | Yes | Yes |
| Access Manager, Host setup, setup guide | Yes | Yes | Yes |
| Starts when you sign in | Yes | Yes | Yes |
| Updates itself | Yes | Yes | Yes |
| Puts the machine to sleep from a launcher | Yes | Yes | Not supported |
| Gatekeeper in your Steam library | Yes | Not supported | Not supported |

On SteamOS: a Steam Deck that is asleep can't be woken from another device, so keep it plugged in and awake while it
serves. Tailscale doesn't officially support SteamOS, and a SteamOS update can remove it; Gatekeeper's Host setup
shows when that has happened.

**How far each has been tested.** Windows 10 is where both programs are used day to day. Gatehouse is used on a
Steam Deck. Gatekeeper on Linux and SteamOS has so far only been tested from Windows with stand-ins: the install
script, its background service and running a real Minecraft server (only a stand-in so far) have not been tried on
a real machine. More detail, with
hardware: [INSTALL.md: Tested on](../INSTALL.md#tested-on).

## When something doesn't work

| What you see | What to do |
| --- | --- |
| A Gatekeeper says it isn't answering | Press **Connect**: it shows which step is missing (Tailscale, the invite, or the host's machine being off or asleep). |
| **Update needed** instead of Play | Your Gatehouse or the host's Gatekeeper is out of date. The line under the heading says which; update that one. |
| No Play, and a **Request** button instead | You aren't on the whitelist, the server is off and you may not start it, or you've been suspended. Press the button to ask the host. |
| The server won't start from a launcher | In Gatekeeper, check **Host setup**. Windows: MCSS has to be found, its web API on, and Gatekeeper signed in to MCSS. Linux and SteamOS: the server's folder needs its program and `eula=true`; if it starts and stops again, Gatekeeper's log has the last thing it said (often the wrong Java version or too little memory). |
| A new player's launcher never appears in Access Manager | Their device has to reach your machine over Tailscale. Check they accepted your invite, and on Windows that the firewall lets Java in on private networks. |
| Everything looked right and stopped working months later | Tailscale signed the machine out. Sign in again, and turn off key expiry for it (step 3 for hosts). |

Gatekeeper keeps a log, `host.log`, in its folder (shown under Options). For anything else, open an
[issue](https://github.com/scholar-andrew/gatehouse/issues).

---

More detail: [INSTALL.md](../INSTALL.md) lists everything the installers change on a computer and what runs on each
launch. [docs/server.md](server.md) covers Gatekeeper in depth. [docs/how-it-works.md](how-it-works.md) is for
maintainers.
