# Getting started

Gatehouse gets a group of friends onto the same Minecraft server. It is one program with two halves:

| | **Play**: the Gatehouse launcher | **Host**: Gatekeeper |
| --- | --- | --- |
| Who sets it up | Each player, on the device they play on | The host, on the machine that runs the Minecraft servers |
| What it does | Lists the servers you can join, installs and updates each server's mods, shows what changed, and starts the game | Lets the launchers you allow see, start and stop your servers, and decides who may do what |
| How it runs | A window you open when you want to play | In the background from the moment you sign in; its window is only for looking and changing things |
| Works with | [Prism Launcher](https://prismlauncher.org/), which runs Minecraft | On Windows, [MC Server Soft](https://www.mcserversoft.com/) (MCSS), or Gatekeeper running the servers itself. On Linux and SteamOS, Gatekeeper runs them itself |

It is the same program file either way, with one version number. Most people set up only Play. The host sets up
Host, and a host who also plays sets up both. Devices on different networks talk over
[Tailscale](https://tailscale.com/), a private network between the host's machine and the devices the host lets in:
nothing is opened to the internet and no router settings change.

Neither half needs administrator rights. Both are installed by plain scripts you can read first; each download is
checked against a published checksum, and `-DryRun` (Windows) or `--dry-run` (Linux) shows what would happen without
doing it.

- [Players: from nothing to playing](#players-from-nothing-to-playing)
- [Hosts: from nothing to a server friends can join](#hosts-from-nothing-to-a-server-friends-can-join)
- [Inviting friends](#inviting-friends)
- [Hosting and playing on one machine](#hosting-and-playing-on-one-machine)
- [What works on which system](#what-works-on-which-system)

---

## Players: from nothing to playing

**You need:** Minecraft: Java Edition on your Microsoft account; something from the host (a pairing link, a pairing
file, an invite, or their Gatekeeper's address); and Windows 10 or later, or Linux, including a Steam Deck.

### 1. Install Gatehouse

**Windows.** Download
[gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip),
unpack it, and double-click **Install Gatehouse.cmd**. Choose **Gatehouse**. It offers Prism Launcher and Java if
you don't have them, adds **Gatehouse** to the Start menu, and offers to add it to your Steam library (Steam has to be
closed for that).

**Linux, including Steam Deck.** On a Steam Deck, switch to Desktop Mode and open Konsole. Then:

```bash
curl -fsSLO https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh
```

```bash
bash gatehouse-install.sh
```

It offers Prism Launcher from Flathub if it's missing, adds **Gatehouse** to the applications menu, and offers to add
it to Steam so it's there in Game Mode.

> Playing on **October Journey**? Its own setup program installs Gatehouse, the modpack and Steam shortcuts in one
> go: see [INSTALL.md](../INSTALL.md).

### 2. Follow the setup guide

The first time, Gatehouse opens on its **setup guide**. Each step says what to do, has a button where there is one,
and ticks by itself once it's done. It's always in the menu (the three lines at the top left) as **Setup guide**.

1. **Prism Launcher.** Done when Gatehouse finds it. If you just installed it, press **Restart**.
2. **Your Minecraft account.** **Open Prism Launcher**, then in Prism: Accounts, Add Microsoft, and sign in.
   Gatehouse never sees your password.
3. **Add the host's Gatekeeper.** **Add Gatekeeper...** and paste the link, invite or address the host gave you, or
   open the pairing file they sent.
4. **Connect to it.** If it can't be reached yet, Gatehouse shows what's missing, each ticking by itself:
   - Install [Tailscale](https://tailscale.com/download) and sign in with your own account (it's free). On a Steam
     Deck, in Desktop Mode, following Tailscale's guide for the Deck.
   - Accept the host's invite. **Open invite** opens it when the pairing came with one. It gives your device access
     to the host's one machine, nothing else.
   - Wait for the host to allow your device. A new device can do nothing until they do.
5. **Install a server.** Pick it and press **Install**. Gatehouse says where the files come from and asks first.
6. **Play.** **Launch** brings the server's mods up to date in the same window; **Play** starts the game.

Day-to-day use is in [Using Gatehouse](gatehouse.md).

---

## Hosts: from nothing to a server friends can join

**You need:** a machine that stays on while people play (Windows 10 or later, Linux or SteamOS); Java 17 or newer;
a free Tailscale account; and on Windows, MC Server Soft if you want MCSS to run your servers (Gatekeeper can run them
itself instead).

### 1. Install Gatekeeper

**Windows.** The same [gatehouse-install.zip](https://github.com/scholar-andrew/gatehouse/releases/latest/download/gatehouse-install.zip):
choose **Gatekeeper** (or **Both** if you also play here). Gatekeeper then starts whenever you sign in to Windows,
with no window; **Gatekeeper** in the Start menu opens its window.

**Linux and SteamOS.** In Konsole (Desktop Mode on a Steam Deck):

```bash
curl -fsSLO https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/gatehouse-install.sh
```

```bash
bash gatehouse-install.sh --gatekeeper
```

It installs Gatekeeper for your user as a background service that starts when you sign in, with **Gatekeeper** in
the applications menu. On SteamOS it uses the Java that Prism Launcher fetches: install Prism from Discover and open
it once first.

### 2. Follow Gatekeeper's setup guide

Gatekeeper's window opens on its own setup guide the first time; **Host setup** in its menu shows every check at
once. On Windows with MCSS:

1. **Install Tailscale** and **sign in** until it says Connected.
2. **Keep this machine signed in.** In Tailscale's admin page, under Machines, open this machine's menu and choose
   **Disable key expiry**; on Windows also turn on **Run unattended** in Tailscale's menu. Otherwise players lose the
   server months later until you sign in again.
3. **Install MC Server Soft** somewhere permanent (it has no installer: the folder is the program), **make a server
   in it** (start it once and accept Minecraft's EULA, then stop it), and **turn on its web API** (MCSS: Options, Web
   API, port 25560; Gatekeeper uses it on this machine only).
4. **Server setup** tells Gatekeeper where MCSS is and which servers players should see.
5. **Sign in to MCSS** once, on Host setup (its MCSS lines): Gatekeeper makes a key of its own and keeps no
   password.
6. **Give each server its own port** (Host setup suggests free ones) and **turn the whitelist on** (Host setup has a
   button for it).
7. **Make an invite** in Tailscale's admin page (Machines, this machine, **Share**, a reusable link), paste it into
   Host setup and Save. It shares only this machine, not the rest of your network.

On **Linux and SteamOS**, and on Windows without MCSS, steps 3 to 5 are replaced by **New server** on Gatekeeper's
Servers section: give it a name, pick its kind (Vanilla, Paper, Fabric, Forge or NeoForge, or a server program you
already have) and version, tick that you agree to [Minecraft's EULA](https://aka.ms/MinecraftEULA), and press
**Make the server**. Gatekeeper finds a free port and a Java that fits; it doesn't install Java. A server you already
have goes in its own folder inside `minecraft-servers` in your home folder (or point Server setup at your servers).

Everything else, from the Access Manager to backups, is in [Using Gatekeeper](gatekeeper.md).

---

## Inviting friends

1. On Gatekeeper's **Host setup**, check the Tailscale invite is saved (step 7 above).
2. **Share pairing** (Host setup) gives a pairing link and a small pairing file. Send either to your friend. Neither
   grants anything by itself.
3. Your friend adds it in Gatehouse (**Add Gatekeeper...**), accepts the Tailscale invite, and their launcher appears
   in your **Players** section, waiting.
4. Allow what you choose: the whitelist to join, and if you like, starting and stopping the server.

To let someone install a particular server's pack, set that server's visibility on its **Pack** tab, or make an
invite there: a code good for seven days that makes the server visible to whoever uses it, without whitelisting them
or giving them any control. [Using Gatekeeper: who a server is for](gatekeeper.md#who-a-server-is-for).

---

## Hosting and playing on one machine

Set up both halves. When the launcher opens and finds a Gatekeeper running on the same machine it links itself to it:
nothing to pair, no request to answer, and no Tailscale needed between the two. That launcher is allowed everything
on every server, and the account you play as in Prism Launcher is put on each whitelist. Gatekeeper keeps running in
the background while you play, and on a machine with both halves one update covers both.

---

## What works on which system

| | Windows | Linux | SteamOS (Steam Deck) |
| --- | --- | --- | --- |
| Gatehouse (Play) | Yes | Yes | Yes |
| Gatekeeper (Host) | Yes, with MCSS or running servers itself | Yes, running servers itself | Yes, running servers itself |
| MCSS's own controls (Start MCSS, Sign in, MCSS control) | Yes | Not supported | Not supported |
| New server, console, settings, backups, tasks | Yes, for servers Gatekeeper runs itself | Yes | Yes |
| Web panel, status page, phone notifications | Yes | Yes | Yes |
| Updating while a server Gatekeeper runs is on | Waits until it is off | Waits until it is off | Waits until it is off |
| Putting the machine to sleep from a launcher | Yes | Yes | Not supported |
| Gatekeeper in your Steam library | Yes | Not supported | Not supported |
| Handing pack updates to other machines | Yes | Yes | Yes, only while plugged in |

What a system can't do is still listed in Gatekeeper where it would be, marked "Not supported on Linux" (or
SteamOS). A Steam Deck that is asleep can't be woken from another device, so keep it plugged in and awake while it
serves. Tailscale doesn't officially support SteamOS, and a SteamOS update can remove it; Host setup shows when that
has happened.

**How far each has been tested.** Windows 10 is where both halves are used every day. Both halves are in use on one
Steam Deck. A plain Linux desktop has only been tried with stand-ins. Hardware details:
[INSTALL.md: Tested on](../INSTALL.md#tested-on).
