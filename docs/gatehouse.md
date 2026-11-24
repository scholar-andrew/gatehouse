# Using Gatehouse (Play)

The Gatehouse launcher is the window you open to play. Setting it up: [Getting started](getting-started.md).

- [The window](#the-window)
- [A server's page](#a-servers-page)
- [Connecting to a Gatekeeper](#connecting-to-a-gatekeeper)
- [Updates](#updates)
- [Using a controller](#using-a-controller)
- [Options and removing it](#options-and-removing-it)

## The window

Gatehouse is laid out like a game's menu. Three sections run across the top: click one, or flip between them with a
controller's bumpers (Q and E on a keyboard). It always opens on **Servers**.

| Section | What is there |
| --- | --- |
| **Servers** | The servers of the Gatekeeper you used last, as cards. Each says whether it's on and has **Install** or **Launch**; an installed one also has **Remove** (it asks first, then deletes its instance in Prism Launcher; close Prism Launcher first). A strip shows the host's machine, MCSS and the server, with the buttons the host has allowed your device (wake or sleep the machine, start or close MCSS, start or stop the server). Since 3.61.0 **Wake** also works from outside the host's home when the host has set that up (Gatekeeper's Host setup, Wake): your device learns how while the machine is awake and keeps it. The panel beside the cards says more about the one you are on. With more than one Gatekeeper, a button for each sits under the heading; **Add Gatekeeper** is at the top right. |
| **Chat** | A room for each Gatekeeper and each of its servers you have been let onto, with a red count of new messages. Since 3.60.0 the rooms are a column down the left on a wide window (the Steam Deck, a PC), with **Clear chat media** and the sound switch under them, so the messages get the whole height; on a narrow window they are one row across the top that scrolls sideways. **Paste** puts words in the line, or offers a copied picture or file to the room; **Attach** offers a file. Nothing someone offered is fetched until you press **View**, **Play** or **Save a copy**. **Play** plays a video or a sound right in the message (3.44.0; in 3.43.0 it opened a window of its own): pause, a seek bar, back and forward 10 seconds, volume, **Theater** (the player as wide as the chat) and **Full screen** (Esc brings it back into the chat); keys Space, Left/Right, Up/Down, M, T, F. Since 3.55.0 its buttons are the usual media icons, and the player is as wide as the video it plays (at most 270 high and 640 wide, never enlarged) or, for a sound, its name, but never so narrow that a button is hidden. **Close player** puts the message back as it was. It plays MP4, M4A, MP3, WAV and AIFF. When something can't be played, the message says why and offers **Open with this device's program**; the reason is also written to `player.log` beside the player's files. The player is fetched once, the first time you press Play, and only if you say yes: about 7.5 MB of OpenJFX (Java's media player) from Maven Central, checked against checksums inside the signed program: version 23.0.2 on Java 21 or newer, 21.0.6 on older Java. On Linux and the Steam Deck, video uses the system's FFmpeg libraries: 23.0.2 supports FFmpeg up to 7 (the Deck's Prism Flatpak has 7), 21.0.6 up to 6; FFmpeg 8 isn't supported by any OpenJFX yet. A message with a web link in it (3.56.0) gets **Open link** (**Watch on YouTube** or **Watch on Vimeo** for theirs, which open in the browser or their app) and **Show preview**: only when that is pressed does this device fetch the page from its own site (no preview service in between) and show its title, site, description and picture. A link straight to a video or sound file (.mp4, .mp3 and the like, up to 200 MB) also gets **Play**. A YouTube video's link also gets **Play here** (3.57.0): it plays in the message in YouTube's own (privacy-enhanced) player, in a web view this device fetches once, asked first: OpenJFX's web view from Maven Central, about 35 MB on Windows and 45 MB on Linux and the Steam Deck, checked against checksums in the signed program; it needs Java 21 or newer. YouTube wants its player inside a page with an address, so Gatehouse serves one tiny page for it on this machine only. Vimeo's videos don't play in that web view (Vimeo refuses it the video), so they stay **Watch on Vimeo**. On Linux and the Steam Deck some YouTube videos' sound plays slow and an octave low in the web view (3.59.1 says so under the player): a fault in OpenJFX's Linux player with HE-AAC sound, which Gatehouse can't correct for a video streamed straight from YouTube. **Watch on YouTube** plays those right. A report for OpenJFX is in [openjfx-he-aac-report.md](openjfx-he-aac-report.md). An X (Twitter) post's link gets **Watch on X** and, since 3.60.0, one **Show post** (before, Show preview and Show media): the post shown in the chat, its author and words, its photos, and its video with **Play video**, which plays in Gatehouse's own player; a post that quotes another shows the quoted post as a card of its own under it, with its photos and video too. When X won't give the quoted post (deleted or private), it's a plain link. They come from X's own embed service and file servers, only when pressed. Links to this machine, the home network or Tailscale's addresses are never fetched. In the web panel, links in messages can be opened. Since 3.58.0 a GIF moves when you View it, each message's time is shown in this device's own time (just the time for today), and a picture, video or sound you fetched has **Remove from this device**, which deletes this device's copy (it stays in the chat; View or Play fetches it again while its Gatekeeper keeps it). **Clear chat media**, under the chat, deletes every copy this device fetched, after asking. A short sound plays for a message in a room you aren't reading (switch it off under the chat). Since 3.59.0 every other time a Gatekeeper sends (when a launcher was last seen, pulls, requests, systems, the update log) is a unix time too, shown in this device's own time; see Gatekeeper's guide, Times. |
| **Updates** | What changed in **Gatehouse** and in your **Gatekeepers**, and **Update options** ([below](#updates)). A red dot means a new version has arrived. |

The **menu** (the three lines at the top left, a controller's View button, or **M**) opens a column down the left:

| Menu | What is there |
| --- | --- |
| **Gatekeepers** | Every Gatekeeper this device knows, with **Connect** for one that isn't answering, **Remove**, and **Start Gatekeeper** for this machine's own. |
| **Systems** | The other machines in a constellation with this one, and the ones you remember ([Using Gatekeeper: systems](gatekeeper.md#systems-and-the-history-of-servers)). |
| **Options** | About this Gatehouse, and Uninstall. |
| **Setup guide** | The steps from nothing to playing, each ticking by itself when done. |
| **Open Gatekeeper** (blue castle) | Opens Gatekeeper on this device, or, where it isn't set up yet, the first screen that offers to set it up (3.54.0; it was Play or Host). |
| **User guide**, **GitHub page** | Open the [user guide](user-guide.md) or the program's GitHub page in your browser. |

A strip along the bottom shows the version and update mode on the left, and what the buttons do on the right: **A**
(Enter) presses what is selected, **B** (Esc) goes back, **X** Open Prism Launcher, **Y** Restart, **Start** Quit. It
shows a controller's buttons or the keyboard's keys, whichever you used last. Everything can also be clicked. Since 3.56.0 nothing opens a window of its own: questions, messages, choosing a file, and pages like Connect, Files, New server, a server's pack and Add a Gatekeeper open on a dimmed layer inside the window, with **Close** (or Esc, or B) to go back; with a controller only their buttons can be chosen while they're open. The program's icon on the desktop, the taskbar and the window is the same castle as inside, in its colour: yellow for Gatehouse, blue for Gatekeeper.

If a button you need isn't there, the host hasn't allowed it for your device.

## A server's page

**Launch** on a server's card brings its mods up to date in the same window: a checklist, with a question first
when something is to be downloaded (how big, and where from). Then the server's own page takes its place, with
**Play** at the bottom right and **‹ Servers** to go back. Its tabs:

| Tab | What is there |
| --- | --- |
| **Overview** | Whether the server is on, its ping and who is playing, and what this update changed on your device: each check with a coloured dot (green up to date, blue updated, grey off, amber not checked, red failed), mods added, updated or removed, and what's new in the pack. |
| **News** | What changed on the server itself (its game version, mods, settings), with the host's notes. |
| **Connection** | The server's controls (machine, MCSS, Minecraft server) with whatever your device may do, each permission shown as allowed, not allowed (you can **Request permission**) or denied; and a **network check** that finds the first thing in the way (internet, Tailscale on this device, the host's machine, Gatekeeper, the server) and says what to do. It only looks; it changes nothing. |
| **Server** | The server's details: game, version, address, what runs it. |
| **Mods** | The pack's optional mods, each with a switch. A switch takes effect the next time the update runs, so press **Restart** before you play. |
| **Host tools** | Only when the host has given your device Gatekeeper control: the Access Manager (launchers and players, their permissions and the whitelist; suspend, blacklist, kick) and Gatekeeper's activity. |

**Play** only works while the server is on and you may join. Otherwise the same button asks for what is missing:
**Request server** (turn it on; it becomes Play by itself when the server is up), **Request whitelist**, or
**Request unsuspend** / **Request unblacklist**. The host's machine chimes and shows who asked; your launcher chimes
when it is granted, and the button flashes red with the host's reason when it is dismissed.

Nothing starts the game by itself, and the window waits until you press Play. Offline, it says so, and Play starts
the game with what is already installed.

A pack started from its own Steam shortcut goes straight into the game when the update went through, the server is
on and you may join; otherwise it shows this page.

## Connecting to a Gatekeeper

**Add Gatekeeper** takes an address, a pairing link, a pairing file or an invite. If it can't be reached yet,
Gatehouse keeps it in the list and shows the steps, each ticking by itself:

1. **Tailscale is running on this device and connected.** **Get Tailscale** opens Tailscale's download page.
2. **You've accepted the host's invite.** **Open invite** opens it, when the pairing came with one.
3. **This device can reach the Gatekeeper.** If the first two are done and this isn't, its machine may be asleep.
4. **The Gatekeeper knows this device.** After that the host decides what this device may do.

Gatehouse doesn't install Tailscale, sign in or accept anything for you. **Remove** takes a Gatekeeper off the list
(packs installed from it stay, under **Other packs**, and update and start as before).

## Updates

- **A server's pack** updates every time you press Launch.
- **Gatehouse itself** says when a newer one is out, under the heading, with **Update Gatehouse** and **Later**. It
  fetches the new file, checks it against its published checksum and release signature, and opens again. **Check for
  updates** looks right away.
- **Where updates come from.** From the host's PC over Tailscale, from GitHub, or from another Gate System that has
  it: whichever answers fastest with the newest version. Every file is checked against the pack's signed list, so it
  doesn't matter which one hands it over ([Security](security.md#updates-from-any-gate-system)).

**Update options** (Updates section):

| Mode | When a new version of Gatehouse is out |
| --- | --- |
| **Normal** | It tells you, and updates when you press Update. |
| **Auto** | While the window is open, it updates by itself. |
| **Constellation** | It waits for a Gatekeeper in constellation mode, which gathers everyone and gives the go; then they all update together. |

**Hand out pack updates to other Gate Systems while Gatehouse is open** is on unless you turn it off. While the
window is open, other devices of your Gatekeepers can fetch a pack update from this one over Tailscale. Only the
published, signed pack is handed out, and only once this device has all of it. A Steam Deck hands out only while it
is plugged in, and a machine with its own Gatekeeper leaves it to Gatekeeper. The first time, Windows may ask whether
Java may use the network: Allow on private networks lets it hand out; Cancel just means it doesn't.

A launcher older than the oldest version the Gatekeeper still works with shows **Update needed** instead of Play
until it is updated. Most updates don't raise that, so they don't interrupt anyone.

## Using a controller

Every window can be driven with a controller: the Steam Deck's own controls, a Steam Controller, or any pad Steam
presents as a gamepad.

The button pictures follow the controller that is connected (3.55.0): with a PlayStation controller plugged in they
are its shapes (Cross, Circle, Square, Triangle) and L1, R1, Options and Create; with a Nintendo one, L, R, + and −;
otherwise Xbox's A, B, X, Y and bumpers. The table below uses Xbox's names. With more than one controller (3.56.0),
they follow the one pressed last: the Deck's own buttons show Xbox's, the PlayStation controller's its shapes.

| Control | What it does |
| --- | --- |
| Left stick, d-pad | Move the selection to the nearest button, tab, box or list that way. On tabs, left and right change the tab; in a list, up and down move the row. |
| A | Press the selected button (tick a box, open a drop-down). |
| B | Back: closes the menu if it is open, goes back where that is safe, and otherwise selects the back (or Cancel) button for A to confirm. |
| Bumpers | Flip between the sections across the top. |
| View | Open or close the menu. |
| X, Y, Start | Select Open Prism Launcher, Restart, Quit (nothing happens until you press A; where those are in the menu, the menu opens on them). |
| Right stick | Scroll the list or page the selection is in, else the biggest one that scrolls: up and down, and left and right where something is wider than the window. Faster the further it is pushed. |
| Right trigger | A left click where the pointer is. |
| Right trackpad | Moves the pointer; pressing it clicks. |
| Left trackpad (Steam Deck) | Swipe to scroll whatever scrolls, up, down and sideways: the page follows your finger. Clicking it is still the d-pad. |

Typing still needs the on-screen keyboard (Steam + X). The Deck's left trackpad is read from the controller itself
alongside Steam (read only), since Steam's layout keeps it a d-pad for the game. On Windows the controller is read through XInput, which needs
Java 22 or newer (the Java the game itself uses); on Linux through the system's joystick devices. Opened straight from
the desktop rather than through Steam, a PlayStation, Switch or other controller that isn't Xbox-style is read through
Windows' own joystick interface instead, with its buttons where an Xbox controller has them (on a Switch controller,
by the letters printed on it).

## Options and removing it

**Options** (in the menu) shows which Gatehouse this is and where it lives. **Uninstall** asks you to type
`confirm`, then removes Gatehouse's own files and shortcuts; a tick box decides whether what it remembers (its
Gatekeepers, and this device's identity with them) goes too. Prism Launcher and the servers installed in it stay;
remove a server with **Remove** on its card, or in Prism Launcher. A Gatehouse that came with a pack is removed by
that pack's uninstaller ([INSTALL.md](../INSTALL.md#removing-it)). From outside, the install script does the same:
`-Uninstall` on Windows, `--uninstall` on Linux (`-Purge` / `--purge` for what it remembers too).
