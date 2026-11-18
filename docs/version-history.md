# Version history

Every version of the program, newest first, with the notes its Updates section shows. Since 3.0.0 Gatehouse
and Gatekeeper are one program with one version; before that each half had its own. The October Journey
pack's own versions are in [CHANGELOG.md](../CHANGELOG.md).

This page is written by `tools/build_pack.py` from `source/gatehouse.json` and `source/gatekeeper.json`; edit
those, not this.

## 3.34.0 - 2026-11-18

- The right stick scrolls sideways too (R41): push it left or right where a list or page is wider than the window, such as a wide table. Up and down work as before; pushed slantwise, it does both. In Gatehouse and in Gatekeeper.

## 3.33.0 - 2026-11-18

- Updates from any Gate System, step 3 of 3 (R40): Gate Systems now hand pack updates to each other over Tailscale. Gatekeeper hands out the packs on its machine all the time; Gatehouse hands out while its window is open. A device then takes an update from whichever answers fastest: your PC, GitHub, or another Gate System.
- Only the published pack is handed out, with its signature, and only once the machine holds all of it. The device that takes it checks the signature and every file, and never takes an older pack, or another pack, from another machine. Packs a Gatekeeper builds aren't handed out.
- Only to the devices its Gatekeepers know. A Steam Deck hands out only while it is plugged in. A switch turns it off: in Gatehouse, Updates, Update options; in Gatekeeper, on the Constellation tab, where the Now column also shows who is handing out. The update log names the machine an update came from.

## 3.32.0 - 2026-11-18

- Updates from any Gate System, step 2 of 3 (R40): Gatehouse now fetches a pack's update itself. It asks every source at once and takes the newest pack that answers well (between equals, whichever answered fastest). Every file is checked against the pack's signed list before it is used; a copy that doesn't match is thrown away and the next source asked.
- From your PC and GitHub an unsigned pack is still taken, as before. From another Gate System only a signed one is (step 3 is when other machines start handing out updates). Checked files are kept on the device, so it can hand them on in step 3 and a file already there isn't fetched again.
- If anything goes wrong with fetching through Gatehouse, the update falls back to the sources one by one, as before. Older Gatehouses aren't affected.

## 3.31.0 - 2026-11-18

- Updates from any Gate System, step 1 of 3 (R40): each pack is now signed on GitHub like the program. Its pack.toml holds the fingerprint of the pack's list of files, and the list holds every file's, so one signature covers the whole pack.
- Before updating a pack from a source, Gatehouse checks that signature, and skips a source whose pack isn't the published one. From your PC and GitHub an unsigned pack is still taken, as now (GitHub signs a minute or two after each publish). The check matters most from step 2 on: there, a pack handed over by another machine is only taken when its signature fits.

## 3.30.0 - 2026-11-18

- Gatekeeper's web panel has the same sections as Gatekeeper's window now (R38, phase E, the last of the menu and look audit): SERVERS, PLAYERS (Players and Launchers), CHAT and UPDATES (the update log). The machine, Constellation, Activity log and Options are in a menu behind the button with three lines at the top left. Its grey text is lighter, and buttons that remove or end something have a red outline, as in the windows.
- Gatekeeper: Systems, History of servers and New server open as pages inside the window, not as windows over it (R39). New server has ‹ Servers to go back without making one.

## 3.29.0 - 2026-11-18

- Gatehouse: a server's page (after Launch) has the same top bar as the main window, SERVERS, CHAT and UPDATES, with SERVERS lit; CHAT and UPDATES go back to the main window on that section (R38, phase D of the menu and look audit). ‹ Servers (or B) goes back to the list.
- The page's own tabs are flat and in a new order: Overview (whether the server is up, waking the machine and starting or stopping things, what this device may do), News (what changed on this device and on the server, together), Connection, Server, Mods. It opens on News when something changed, otherwise on Overview. The bumpers (Q and E) move along these tabs.
- Host tools replaces the Remote Gatekeeper tab for a device allowed Gatekeeper control: a short page with Open Gatekeeper (when there is one on this machine), and the Access Manager only when you ask for it.
- Servers: Start or Stop is on each server's card (when this device may), and the host's machine is one line above the cards (awake or not, MCSS, with Sleep, Wake or Start MCSS when allowed). So The host's machine and What this device may do are no longer pages in the menu.
- Running the update again and Quit are in the menu on a server's page too, as on the main window.

## 3.28.0 - 2026-11-18

- Gatekeeper reorganised (R38, phase C of the menu and look audit): PLAYERS is a section of its own in the top bar. It holds the Access Manager for all of Gatekeeper, with a choice of which server the server-control and whitelist columns are for. It used to be inside a server's page, and in the menu as Launchers and players.
- A server's page is only about that server now: Overview first (Start or Stop, and what the panel beside the cards says: whether it's on, who is playing, how it's doing), then Console, Settings, Game settings, Backups, Tasks and Pack. Its heading is the server's name.
- Gatekeeper's and MCSS's lines (Start MCSS, Sign in to MCSS, Set MCSS key) moved from a server's Server Status tab to Host setup. The activity log is a page of its own in the menu (Activity log), and Updates pulled is under UPDATES.
- Each server's card has Start or Stop and Open. Pack and Manage are gone from the card, since both are tabs on the server's page. MCSS is one line at the top of Servers instead of a box with its full path (the path is in its tip).
- Fixed: with only one server, the button back to the list of servers could be missing on that server's page.

## 3.27.0 - 2026-11-18

- Easier to read on a Steam Deck (R39, phase B of the menu and look audit): all text is one size bigger, so the smallest is 13 points on the Deck's screen, and table rows are taller to match.
- Quieter grey text (hints, “Not installed on this device”, status lines) is lighter, so it can be read on cards too.
- The Access Manager's permission cells are dark now, with green text for allowed and red for denied, instead of pale mint, pink and white. Its button sets have small headings instead of boxes, and its columns fit what is in them, so names and dates aren't cut off.
- Buttons come in four kinds, the same everywhere: blue for the main thing (Launch, Open, Start), plain for the rest, text-only for extras, and a red outline for anything that removes or ends something (Remove, Delete, Forget, Suspend, Blacklist, Uninstall). Remove on a server's card is at the far left, away from Launch. Restart and Quit are no longer gold and red.
- Gatekeeper's Constellation page: shorter button names, so the row fits.

## 3.26.0 - 2026-11-18

- Menus tidied, the first part of the menu and look audit (R38, phase A): Restart and Quit moved from the bottom bar into the menu (≡), at the bottom, as in a game's menu; Y and Start on a controller open the menu on them.
- Add Gatekeeper shows only on the Servers pages, not on Chat, Updates or Options.
- Gatekeeper: Host setup has Server setup and Share pairing buttons of its own, and each step that needs one has the button under it. Its steps no longer send you to a "Servers tab" that isn't there any more.
- Gatekeeper: History of servers and Systems are no longer buttons on the Constellation page as well as in the menu (the row fits on one line now), and Options no longer lists the setup guide a second time (it is in the menu).
- The setup guide says where it lives now (the menu) and where Restart is.

## 3.25.2 - 2026-11-18

- Tidier code, with nothing changed in what Gatehouse and Gatekeeper do (the code review, R36): Gatekeeper's window code is in pieces now (its main part went from 1,874 lines to under 1,000, with twelve parts in classes of their own), long lines are broken up (lines over 200 characters went from 797 to about 190), and work over several servers is done through helpers that can't leave the wrong server selected.
- Errors that are passed over on purpose now leave a trace when debugging is on: put a file named debug-on.txt in Gatekeeper's folder (or Gatehouse's home folder) and they are written to debug.log there.

## 3.25.1 - 2026-11-18

- Security fix (important): Gatekeeper no longer takes requests from web pages. Before, a web page open in a browser on the same machine could have sent Gatekeeper requests (browsers allow that), including ones that change settings or start programs. Requests carrying a browser's marks, or addressed to this machine by another name, are now refused. Gatekeeper's own window, the launchers and other Gatekeepers are unaffected, as are the web panel and the status page.
- Gatekeeper refuses requests over 1 MB (program and shared files keep their own limits) and messages nested absurdly deep, so neither can be used to wear it down.

## 3.25.0 - 2026-11-18

- Phone notifications (Options, Phone notifications): when a friend joins a server, when a server goes down, and when an update needs you or Gatekeeper restarted itself. Through ntfy (free, no account): install the ntfy app and add the topic shown there. Off until you turn it on; only names and a sentence are sent.
- Status page for friends (Options): a read-only page at http://<this machine's Tailscale address>:<Gatekeeper's port>/status showing which servers are up and who is playing, with no sign-in. Only people on your Tailscale network can open it. Off until you turn it on.
- Crash watch: Gatekeeper notices a server going down without being asked to (MC Server Soft's servers too), tells you, and, for a server ticked under Options, Crash watch, starts it again: up to three times in ten minutes.
- Performance on the server's card: its memory and processor use now and their highest in the last hour, and whether it is keeping up (Minecraft's own "Can't keep up!" warnings).

## 3.24.1 - 2026-11-18

- Fixed: a Gatekeeper window opened before Gatekeeper updated kept showing the old version (and its old buttons) until it was closed. It now notices within a minute that the Gatekeeper behind it is newer, and opens again as the new version. Windows opened on 3.24.0 or earlier still need closing and opening once.

## 3.24.0 - 2026-11-18

- Gatekeeper restarts itself if it freezes. A watchdog beside it looks twice a minute: parts of it waiting on each other for good (what happened on 17 November) restarts it within a minute; not answering at all for ten minutes does too. It restarts the way sign-in starts it, and says so in its activity log. Not while a server Gatekeeper runs itself is on.
- Gatekeeper's window shows a red bar with Restart Gatekeeper when the Gatekeeper behind it stops answering, instead of showing nothing.
- Fewer ways to freeze: fetching Gatehouse's notes from GitHub, reading the servers' logs, and checking an update's signature no longer hold up the rest of Gatekeeper while they wait.
- Settings are never left half-written: Gatekeeper writes each settings file whole and then swaps it in. It keeps up to ten earlier copies of its settings, the paired devices, players and web panel accounts in a backups folder.
- Gatehouse: an older copy still open after an update closes once the new one is up (unless it's in the middle of something).
- Updates: when two or more versions are new to you, a short "what's new since" list sits at the top, with one line for each.

## 3.23.0 - 2026-11-17

- Updates are checked against the release signature: each published version is signed on GitHub with the owner's release key, and Gatehouse and Gatekeeper check that signature before taking an update, whether it came from GitHub, the pack server or another Gatekeeper. One whose signature doesn't fit is refused and nothing changes. One with no signature yet (GitHub signs a minute or two after each publish) is still taken, as before, and the log says so.
- Gatekeepers keep the signatures they've checked and hand them on with the program file, so peer-to-peer updates are checked too, even with no way to reach GitHub.
- Gatekeeper: host.json "require_signed": "true" makes it take only signed updates. It is off unless set.

## 3.22.0 - 2026-11-17

- Controller: the right stick scrolls, faster the further it is pushed: the list or panel the selection is in, or else the biggest one on the page. Lists and panels with nothing to press in them (notes, the panel beside the servers, chat) are places the left stick can stop on now, outlined in blue, so the right stick can be pointed at them.
- Gatekeeper: Check for updates is on the Updates page now, beside its pages; Play or Host left the Servers page, as it is in the menu.
- Gatekeeper: the panel beside the server cards is tall enough to say everything, and opens at its top rather than part way down. The notes lists open at their top too.
- Gatekeeper's title bar says just Gatekeeper; the version is at the bottom left.

## 3.21.0 - 2026-11-17

- Updates: a red dot on UPDATES, and on the tab inside it, when there are notes you haven't read yet. Versions you haven't seen sit in a blue box with a red dot by their number; they stay boxed while you read and are plain the next time.
- Downloads show how far along they are: a bar with how much of how big and the time left, while Gatehouse or Gatekeeper downloads a newer version, and a count of files while a server's pack updates before Play.
- Shortcuts: Gatehouse and Gatekeeper make or mend their own Start menu shortcut when they start, pointing at where they are now, with their castle (yellow Gatehouse, blue Gatekeeper) instead of Java's cup. A shortcut on the desktop is mended too.
- Linux and SteamOS: the windows go by Gatehouse and Gatekeeper, with their castles, in the taskbar and the menu, and the processes are named so in the system monitor (the background part as "Gatekeeper (bg)"). Windows waits until updates are signed.

## 3.20.3 - 2026-11-17

- Gatekeeper: fixed a lock-up where Gatekeeper kept running but stopped answering (its window showed nothing and it stopped updating). Two of its parts could each wait on the other forever while a newer version was being handed over.
- Update log: a device with two copies of Gatehouse open (an old one beside its updated copy) no longer makes the log flip between their two versions every few seconds.

## 3.20.2 - 2026-11-17

- Chat: each room's card has a small castle on the corner of its picture: blue for a Gatekeeper's room, yellow for a server's.

## 3.20.1 (Gatehouse) - 2026-11-17

- Gatehouse's castle is yellow-orange, at the top left of its windows and in its window and taskbar icon; Gatekeeper's is blue.
- Fixed: the panel beside the server cards cut its words off at the right, under its own scroll bar. It wraps them now.
- Options no longer repeats the setup guide, which is in the menu.

## 3.20.1 (Gatekeeper) - 2026-11-17

- Gatekeeper's castle is blue, in the window and in its taskbar icon (the icon used to be yellow); Gatehouse's is yellow-orange, so the two are told apart at a glance.
- Fixed for small screens such as the Steam Deck's: the panel beside the server cards wraps its words inside its own scroll bar instead of cutting them off; the Web panel and Pack pages scroll instead of running off the bottom; the Access Manager's and Tasks' tables scroll sideways when there isn't room for their column headings, instead of cutting them short.

## 3.20.0 (Gatehouse) - 2026-11-17

- Remove: an installed server's card has Remove. It asks first, then deletes the server's instance in Prism Launcher on this device (close Prism Launcher first). The server and its world aren't touched, and Install sets it up again.
- A server its Gatekeeper no longer has says so on its card, in red, with Remove beside it.
- Chat: the rooms are cards with their pictures and a red count of new messages, in place of the list.
- A file offered in a room shows in the conversation with View and Save a copy. Nothing is downloaded until you press one. View shows a picture in the chat; a video or sound opens in this device's own player.
- Paste puts words in the line, or offers a copied picture or copied file to the room (Ctrl+V in the line does the same). Attach offers a file from this device.
- A short sound plays when a message arrives in a room you aren't reading; switch it off under the chat.

## 3.20.0 (Gatekeeper) - 2026-11-17

- Servers are cards now, like Gatehouse's: each server's picture, whether it's on and who is playing, with Pack, Manage and Open on the card, and a panel beside them saying more about the one you're on.
- Chat: the rooms are cards with their pictures and a red count of new messages, in place of the list. Chat in the top bar shows how many rooms have messages you haven't read.
- A file offered in a room now shows in the conversation with View and Save a copy. Nothing is fetched until you press one. View shows a picture in the chat; a video, a sound or any other file opens in this machine's own program for it.
- Paste puts words in the line, or offers a copied picture or copied file to the room (Ctrl+V in the line does the same). Attach offers a file from this machine.
- A short sound plays when a message arrives in a room you aren't reading; switch it off under the chat.

## 3.19.0 (Gatehouse) - 2026-11-17

- A new layout. Three sections across the top: Servers, Chat (what was People) and Updates (in place of Constellation). The rest is in a menu down the left, opened and closed with the three lines at the top left (on a controller, the View button; on a keyboard, M).
- The menu: Gatekeepers, The host's machine, What this device may do, Systems, Options, Setup guide, and Play or Host.
- Gatehouse always opens on Servers, on the Gatekeeper you used last. The list of Gatekeepers is no longer a step on the way: with more than one, a button for each is under the heading, and the full list is in the menu.
- Updates has three tabs: what changed in Gatehouse, what changed in your Gatekeepers, and Update options (how Gatehouse updates, and Check for updates). After an update Gatehouse no longer jumps there; Updates gets a red dot instead.
- Options keeps About and Uninstall; the setup guide and Play or Host are in the menu.

## 3.19.0 (Gatekeeper) - 2026-11-17

- A new layout. Three sections across the top: Servers, Chat and Updates. Everything about setting this machine and its constellation up is in a menu down the left, opened and closed with the three lines at the top left (on a controller, the View button; on a keyboard, M).
- The menu: Host setup, Launchers and players, Web panel, Constellation, Systems, History of servers, Options, Setup guide, and Play or Host. A red number beside Host setup is steps still to do; beside Launchers and players, launchers asking for something. A red dot on the menu button means one of these is waiting.
- A server is one page now. Open, Pack and Manage all go to it, each on its own tab: Access Manager, Server Status, Updates Pulled, Pack, and, for a server Gatekeeper runs itself, Console, Settings, Game settings, Backups and Tasks. Pack and Manage no longer open windows of their own.
- Updates has what's new in Gatekeeper and in Gatehouse, and the update log.
- The window always opens on Servers, also when Gatehouse brings it to the front.

## 3.18.1 (Gatehouse) - 2026-11-17

- Fixed: the panel beside a server listed who it is for last, where a window of the usual size cut it off. It is now near the top, and the panel scrolls when the window is too short for all of it.
- This launcher now tells each Gatekeeper which machine it is part of soon after it opens, so it is listed as one system with the Gatekeeper beside it.

## 3.18.1 (Gatekeeper) - 2026-11-17

- Fixed: choosing a picture for Steam replaced the picture the pack came with, and going back gave a made one instead. The pack's own picture is now kept the first time it is replaced, and the button is now Undo my choice, which gives it back. (A picture already lost this way comes back by pressing Bring in a pack from a folder again.)
- Fixed: in Systems, the same machine could be listed twice, once for its Gatekeeper and once for its launcher. A Gatekeeper now knows its own launcher's ids and tells the Gatekeeper it joined, and each launcher says which machine it is part of a few seconds after opening, so this no longer waits for someone to open Systems.
- A system that has a Gatekeeper is listed under that Gatekeeper's name. Systems has a new column, Last heard from.
- The Pack page now lists who you let in to the server and why (this machine's own launcher, may start and stop it, plays as someone on its whitelist, your invite), and how many other launchers known here don't have it. Those you let in have the server whatever it is set to, which is why setting it to private doesn't take it from them.

## 3.18.0 (Gatehouse) - 2026-11-17

- Systems, on the Constellation page: the other machines in a constellation with this one (neighbors), and the ones you chose to remember. Pick a neighbor and press Remember to keep it listed after the constellation is left.
- When this machine also has a Gatekeeper that this launcher is linked to, the two are one system, and what it remembers is kept by that Gatekeeper.
- Remembering gives the other system nothing, and each side decides for itself.

## 3.18.0 (Gatekeeper) - 2026-11-17

- Fixed: a server that MC Server Soft runs had no way to its Pack page, because that page was only inside Manage, which such a server doesn't have. Every server on the Servers page now has a Pack button of its own, beside Open: its pack and mods, its pictures for Steam, who it is for, and invites.
- Systems, on the Constellation tab. A system is what one machine has of the program: its Gatehouse, its Gatekeeper, or both. A Gatekeeper and the launcher linked to it on the same machine are now listed as one system.
- Each other system is a neighbor (in a constellation with this one now), remembered, or unknown (a launcher that introduced itself here and has been let in nowhere).
- Remember keeps a neighbor in the list after the constellation is left, with its name and its Gatekeeper's address. Only a neighbor can be remembered; Forget works any time.
- Remembering is one-sided. Each side decides for itself, each is shown when the other remembers it, and it gives the other nothing: no permission, no whitelist, no pack.
- A launcher that isn't part of this constellation isn't told which systems are in it.

## 3.17.0 (Gatehouse) - 2026-11-17

- Invite: on a server its owner made shareable or public, a new Invite button makes a link to send someone. It lasts seven days. It lets their Gatehouse install the server's pack and join; it doesn't put them on the whitelist or give them any control.
- Add Gatekeeper now also takes an invite: paste the link someone sent you, and the server is listed with Install.
- The panel beside a server says who it is for: private, shareable or public.
- A server whose owner hasn't let this launcher in is no longer listed, once its Gatekeeper is on 3.17.0. Ask the owner, or for an invite.

## 3.17.0 (Gatekeeper) - 2026-11-17

- Who a server is for. Each server's Pack tab has a new setting: private, shareable or public. It covers the server and its pack, and is changed at this Gatekeeper's own window only.
- Every server is private unless you change it. Private: this machine's launcher, a launcher with Gatekeeper or MCSS control, one allowed to start and stop that server, one playing as someone on its whitelist, and anyone you invite yourself.
- This changes what launchers are shown: a launcher you never let in is no longer told of your servers, and is refused their packs. What it already installed stays on its device.
- Shareable: those, and any launcher invited by someone who has the server. Public: every launcher that connects to this Gatekeeper.
- A suspended or blacklisted launcher is shown no server and given no pack, whatever the setting.
- Invites. Invite on the Pack tab makes a link to send someone, good for seven days and any number of people. In their Gatehouse they paste it into Add Gatekeeper, and the server is then listed for them with Install. They still have to be able to reach this Gatekeeper.
- An invite does nothing else: it puts nobody on a whitelist and gives no permission to start, stop or manage anything.
- Players with a shareable or public server can make invites too. A player's invite stops counting while the server is private; yours always counts. Take back invites cancels a server's unused invites and everyone who came in by one.
- A pack's files are given by the address a launcher last spoke from, since the program that downloads mods can't send a launcher's secret. After you let a launcher in or shut it out, downloads follow within a few seconds.

## 3.16.0 (Gatehouse) - 2026-11-17

- Nothing new in the launcher itself: this version goes with Gatekeeper 3.16.0, where a host chooses which of a server's mods players are sent.

## 3.16.0 (Gatekeeper) - 2026-11-17

- Mods players get: a new button on a server's Pack tab. Gatekeeper reads the mods in the server's mods folder, guesses which ones players need, and shows the list with a switch for each. You decide; only a choice that differs from the guess is kept as yours.
- Where a player's copy comes from: the mod's own page on Modrinth when that exact file is published there, and this Gatekeeper otherwise. Either way it is checked against the server's own file.
- To find that out, Gatekeeper asks Modrinth about the files' checksums when you open the list. Nothing about the server or its players is sent, and no sign-in is used. Answers are kept, so a file is asked about once; Look again asks afresh.
- A mod is left off by default when its own file says it is for servers only, or its page says players don't use it.
- A mod updated on the server is updated in the pack the next time the list is saved, and one taken off the server is taken out. Mods put in a pack by hand are left alone.
- Saving the list changes the pack's definition only. Players get it with the pack's next version: New version opens with what was added, updated and removed already written as its notes.
- If Modrinth can't be reached, mods it hasn't been asked about are not added until it answers, and the list says so.

## 3.15.0 (Gatehouse) - 2026-11-17

- A server whose Gatekeeper keeps its pack itself is installed and kept current from that Gatekeeper. Nothing changes in how Install or Play look.

## 3.15.0 (Gatekeeper) - 2026-11-17

- Packs. A server's Manage window has a new tab, Pack. Gatekeeper can now keep a server's pack itself, build it, and hand it to launchers from its own address, with no outside site involved. Launchers install it and keep it current with what they already have.
- Make a pack for this server: for a Fabric server, a pack with its Minecraft and Fabric loader versions and no mods yet. Not supported for other kinds of server yet (a plain server needs no pack).
- Bring in a pack from a folder: takes a pack's definition from a folder on this machine. The pack the server had before is kept beside it until the next time.
- New version takes the notes players read before updating and gives the pack its next version number. Build again builds it as it is.
- Pictures for Steam: cover, hero, logo, icon and wide. Each is a picture you choose, brought to the right size here, or one Gatekeeper makes from the pack's name and the server's picture. A change goes out with the next build.
- Making, building and changing a pack is done at this Gatekeeper's own window only: not from the web panel, and not from another Gatekeeper.
- History of servers, on the Constellation tab: a line for each server a Gatekeeper made, started or stopped looking after, and each new pack version, with which Gatekeeper it was and its address. Lines stay after a server is gone.
- The history is shared in a constellation: a Gatekeeper that joined another and was allowed in hands over its lines and takes that one's, so one set up later learns of every server and Gatekeeper before it. It holds no folders, player names or keys.

## 3.14.0 (Gatehouse) - 2026-11-17

- Cards show real pictures: the one a Gatekeeper's owner chose for it, and one for each server (or the server's own server-icon.png). A picture is fetched once and kept, and fetched again only when it changes; a Gatekeeper's is still shown while its machine is off.
- Beside a Gatekeeper's servers there is now a panel about the server you are on: whether it is on, the game and version, its address, what runs it, whether it is installed on this device, and what you may do with it. It follows the card you move to.
- With that panel there, the servers sit in two columns at the Steam Deck's size.
- Chat. People now has chat beside what this device may do: a room for each Gatekeeper and for each of its servers this device has been let onto. Pick the room, write, press Enter. A red number on People says how many rooms have something new. What was said is kept on the Gatekeeper, so it is there when you open the launcher later.
- Files. In a room, Files shows what has been offered there: save a copy of one, or offer one of your own to the room (the Gatekeeper's owner sets how big one may be). A file only reaches your device when you choose to save it; only open files from people you trust.
- This update holds nothing up: you keep Play while it waits.

## 3.14.0 (Gatekeeper) - 2026-11-17

- Pictures. Options has a new part, Pictures: choose a picture for this Gatekeeper and one for each server, and launchers show them on their cards in place of the generated art. Any picture file will do (PNG, JPEG, GIF, BMP); it is made square and kept at 256 by 256.
- A server with no picture chosen shows the server-icon.png in its own folder, if it has one: the picture Minecraft itself shows for the server in its list. A Gatekeeper with none keeps the picture of the kind of machine it is on.
- Pictures are chosen at the machine only; the web panel can't set them.
- Chatrooms. There is a room for this Gatekeeper and one for each server, kept here (the newest 500 messages of each). A server's room is for the launcher on this machine, launchers with Gatekeeper control, and launchers you have let onto that server: allowed to start and stop it, or playing as someone on its whitelist. This Gatekeeper's room is for anyone in any of them. A launcher you suspended or blacklisted is in none. You write as Host on the new Chat page (People), or in the web panel with an account that has Launchers, players and their permissions. Emptying a room is done at the machine.
- Files. Anyone in a room can offer a file to it (Files, on the Chat page): it is kept here for a day, the room is told, and anyone in that room can save a copy. Nothing is put on anyone's device unless they save it, and Gatekeeper never opens what it is given. One file may be 25 MB at most unless you set otherwise (host.json "share_max_mb"; "share": false turns it off), and all of them together 500 MB.
- One Gatekeeper managing another. Off unless you choose it, at the machine to be managed, for one other Gatekeeper of your constellation. On the one that leads: Constellation, click a Gatekeeper that joined, Let it manage this Gatekeeper. On one that joined: Constellation, Let that Gatekeeper manage this one. The other can then open a window on this one (Manage it) and see its servers, start and stop them, use a console, and answer launchers and players. It cannot change settings, update it, make servers, use backups or web accounts, sleep the machine, end or uninstall it, or change who may manage it. Each thing it does is written in this Gatekeeper's log.
- This update holds nothing up: 3.13.0 and later work with it, so update when it suits.

## 3.13.0 (Gatehouse) - 2026-11-17

- A newer Gatehouse being out no longer takes Play away by itself. Each published version says the oldest version that still works with it; a launcher at or above that keeps Play, and updates when it suits (or by itself, in Auto or Constellation mode). Only a launcher older than that sees Update needed.
- Every launcher has to reach 3.13.0 once for this, since older ones don't know the rule.

## 3.13.0 (Gatekeeper) - 2026-11-17

- A newer version being out no longer holds everything up by itself. Each published version now says the oldest version that still works with it. A Gatekeeper at or above that keeps starting servers, and a launcher at or above it is still offered Play, while the update waits for when it suits. Only a version below it is held up, as every out-of-date version used to be.
- That oldest version is raised only when it has to be: when the two halves, or a launcher and a Gatekeeper, would no longer understand each other, or for a fix that mustn't wait. This version is such a point itself (every client has to reach 3.13.0 once, since no older one knows the rule); the updates after it are the ones that stop interrupting play.
- Updating is unchanged: the window still says when a newer version is out, Auto and Constellation modes still update by themselves, and a version that is held up can always still update. The line about a newer version now says which it is: nothing is held up, or servers aren't started until it's updated.
- A published version that says nothing about this (one from before 3.13.0) is treated as it always was: only the newest will do.

## 3.12.2 (Gatehouse) - 2026-11-17

- Open Gatekeeper now works in the Steam Deck's Game Mode. The launcher used to ask Gatekeeper to open its own window, but Gatekeeper is a background service there, and Game Mode only shows windows of the program in front, so nothing appeared. On Linux inside Prism Launcher's sandbox (the Steam Deck), the launcher now shows Gatekeeper's window itself; if one is already open, it switches to it. Windows, and Linux outside a sandbox, are as before.

## 3.12.2 (Gatekeeper) - 2026-11-17

- Gatekeeper's window can now be shown by the launcher on the same machine, as a part of the launcher. That is for where Gatekeeper can't put a window on the screen itself: on a Steam Deck in Game Mode only the windows of the program in front are shown, and Gatekeeper is a background service that isn't that program.
- A window opened that way is the same window, with one thing it can't do: Uninstall is still listed, but greyed, because from the launcher's sandbox Gatekeeper's own folder can't be reached. Open Gatekeeper from its own entry in the applications menu (Desktop Mode on a Steam Deck) for that.

## 3.12.1 (Gatehouse) - 2026-11-17

- When this launcher updates and tells the Gatekeeper on the same machine to update with it, it now says how that came about (the constellation's go, auto mode, or Update pressed), so that Gatekeeper's update log is right. Nothing else changed.

## 3.12.1 (Gatekeeper) - 2026-11-17

- Fixes to the update log. Nothing else changed: how updates happen is as it was.
- A Gatekeeper that updated on its constellation's go, on a machine that also has the launcher, was written down as "normal (Update pressed in the launcher on this machine)". The launcher there does tell Gatekeeper to update, but because the constellation said go, not because anyone pressed anything. The launcher now says how its own update came about, and Gatekeeper's line says the same: constellation, auto, or Update pressed.
- A launcher that is paired with two Gatekeepers of one constellation was listed twice for each update: once by the Gatekeeper that leads, and once more handed on by the one that joined. It is one line now, the one heard from the launcher itself, and its Through column says which Gatekeeper it was also heard through. This works straight away, before the other Gatekeeper is updated: where a line carries no id for its install, it is matched by the device's name, its system, the versions and the time, and one line takes in one other at most, so two devices of the same name (every Steam Deck is "steamdeck" until renamed) are still two lines.
- "Fetched from" for a Gatekeeper now says where the file it became really came from: a copy a client handed over, when that is what it used, not the place it would otherwise have looked.
- "Fetched from the Gatekeeper at 127.0.0.1" now reads "the Gatekeeper on its own machine": that address only means something on the machine that wrote it.
- Longer descriptions of how an update came about are no longer cut short when a joined Gatekeeper hands its lines on.
- The log as it stands is tidied once, when this version first starts: lines doubled before these fixes are made one by the same rule, and the "fetched from: not said" line this Gatekeeper used to write for a joined Gatekeeper goes where that Gatekeeper's own line is here. The log as it was is kept beside it as updates-before-tidy.json. What old lines say about how an update came about is left as it was written.

## 3.12.0 (Gatehouse) - 2026-11-17

- Start Gatekeeper. When the Gatekeeper on this same machine isn't running, its card has a Start Gatekeeper button, on Windows and on SteamOS and Linux. It starts in the background, as it does when you sign in; when it answers, the launcher reopens and links itself to it. A server you set to start with Gatekeeper starts too.
- On SteamOS the launcher runs inside Prism Launcher's sandbox and asks the system outside it to start Gatekeeper. Where the sandbox isn't allowed to do that, the launcher says so, and nothing else changes.
- The launcher now remembers which Gatekeeper is this machine's, so it still shows it as such (with Start Gatekeeper) while it isn't running.
- Open Gatekeeper switches to Gatekeeper's window when it is already open, instead of opening another.

## 3.12.0 (Gatekeeper) - 2026-11-17

- Options has a new part, End Gatekeeper. Gatekeeper runs in the background whether its window is open or not, and closing the window stops nothing; this ends it, until you next sign in or start it again. It asks first, and won't end while a server it runs itself is on (stop that server first). Servers MC Server Soft runs keep running.
- One window. If Gatekeeper's window is already open, opening it again (from the launcher's Open Gatekeeper, the Play or Host screen, or its own shortcut) brings that window to the front instead of opening a second one.
- Opening Gatekeeper's window while Gatekeeper itself isn't running now says so and offers to start it, instead of showing an empty window.
- Starting it by hand uses what signing in uses: its scheduled task on Windows (or the program from its folder where there is no task), its background service on Linux and SteamOS.

## 3.11.1 - 2026-11-17

- A test update: nothing changed. It is here so that an update being passed from client to client can be watched on real devices: who raises its hand first, which copy each client takes, and what the update log says each fetched it from.

## 3.11.0 (Gatehouse) - 2026-11-17

- Gatehouse now takes a newer version of itself from a Gatekeeper in your list when one has it, before it looks anywhere outside. That is usually quicker, and it works when the place a pack names can't be reached.
- When a Gatekeeper tells this launcher it knows of a newer version, the launcher looks straight away instead of at its next round.
- When this launcher is the first to see a newer version, it raises its hand with the Gatekeepers that trust it for that (the one on this same machine, or one where you have Gatekeeper control), fetches the file once, and hands it over, so the rest of the constellation gets it from there.
- On a machine with both halves, updating the launcher gives the same file to its Gatekeeper: one fetch for the pair. A Gatekeeper that is running takes it and updates itself with it. One that is set up but not running has its file replaced (Windows; not supported on Linux or SteamOS, where the launcher can't be sure it isn't running, or can't reach its folder).

## 3.11.0 (Gatekeeper) - 2026-11-17

- An update is now passed from client to client, so a constellation fetches it from outside only once. Each Gatekeeper keeps the newest program file it has heard of and gives it to its launchers and to Gatekeepers that joined it. It fetches a newer version as soon as it sees one, not only when it updates.
- The first to see a newer version raises its hand: it tells its Gatekeeper it is fetching it, the others are told it is in hand and wait for that copy, and when it arrives it is handed over. A Gatekeeper that joined another hands on what it gets to that one, and takes what that one has. The log says who saw it first and where each copy came from.
- Who may hand a file over: the launcher on this same machine, a launcher you gave Gatekeeper control, and a Gatekeeper you allowed into this constellation. Nobody else. What is handed over has to be a Gatekeeper that can run, newer than this one, and match the checksum sent with it. Nothing checks who made the file: it is trusted because the client handing it over is. So allowing a Gatekeeper into your constellation now also means trusting it to pass program updates, both ways.
- On a Gatekeeper that joined another: Constellation has a switch, Share program updates with that Gatekeeper. It is on unless you turn it off; off, this Gatekeeper fetches its own again. (A Steam Deck beside a PC used to fetch every update from GitHub, because it only ever looked on its own machine and then there.)
- One file for a machine with both halves. When the launcher there fetches an update, it hands the same file to its Gatekeeper, which manages its own update with it, instead of fetching a second copy. When Gatekeeper updates, it puts the same file where the launcher keeps its own, as before.
- Gatekeeper now asks every place it knows for the newest version and takes the newest, instead of stopping at the first place that answers. A place that is a few minutes behind, as GitHub can be, no longer hides a version another place already has.
- Fixed: the update log wrote two lines each time a joined Gatekeeper updated, one of them saying "fetched from: not said". Only the line that Gatekeeper hands on itself is kept now.
- For later: each published program can be signed on GitHub, with the key kept in the repository's secrets (tools/setup_signing_key.ps1 sets it up; you run it). Nothing requires a signature yet.

## 3.10.0 (Gatehouse) - 2026-11-17

- A Gatekeeper's card now shows a picture of the kind of machine it runs on, in place of its initials: a desktop, a laptop, a Steam Deck, a ROG Ally, another handheld, a mini PC, a Mac laptop, a Mac desktop, a Mac mini or a server. The Gatekeeper says which (from Gatekeeper 3.10.0), and its owner can correct it there.
- The picture is remembered, so the card still shows it while that Gatekeeper's machine is asleep or off. A Gatekeeper that is older, or that recognised nothing, keeps its initials.

## 3.10.0 (Gatekeeper) - 2026-11-17

- Gatekeeper now tells launchers what kind of machine it is on, so they can show a picture of it in their list: a desktop, a laptop, a Steam Deck, a ROG Ally, another handheld, a mini PC, a Mac laptop, a Mac desktop, a Mac mini or a server.
- It works that out by itself when it starts, from what the machine says about itself (its maker, model and case type; nothing is changed, and nothing leaves the machine but the kind). Options has a new line, This machine, that says what it found and lets you choose another kind if it got it wrong.
- Where it recognises nothing, launchers show this Gatekeeper's initials as before. The Mac kinds are there to be chosen; Gatekeeper itself has not been run on a Mac.
- The web panel on a phone: the sections now fold away behind a menu button at the top right (three lines, with the name of the section that is open). Tap it for the list of sections, your account and Sign out; picking one closes it again. On a wide screen nothing changes.

## 3.9.0 (Gatehouse) - 2026-11-17

- Nothing changed in the launcher: this version carries Gatekeeper's web panel in the new look.

## 3.9.0 (Gatekeeper) - 2026-11-17

- The web panel has the new look too, with the same five sections across the top: Servers, People (Players and Launchers), Constellation (Clients and the Update log), Machine (Sleep and wake, and the Log) and Options (updating Gatekeeper, New server). Q and E flip between them. A section an account isn't allowed is still listed, greyed.
- Servers are cards there, with Start as the main button. The card you are on glows. A red number on People is how many launchers are waiting for an answer.
- New in the web panel: the Constellation section shows the clients with their update modes, and a strip along the bottom shows the version, how this Gatekeeper updates, and the constellation's pips while it gathers. It is for looking only: allowing a Gatekeeper in, removing one and joining another constellation stay in Gatekeeper's own window.
- The account permission "See the update log" is now "See the constellation and its update log": an account that has it also sees the clients list (the same names, players, systems and versions the log already showed).
- Tick boxes in the web panel are drawn as switches, as in the windows.

## 3.8.0 (Gatehouse) - 2026-11-17

- Nothing changed in the launcher: this version carries Gatekeeper's window in the new look.

## 3.8.0 (Gatekeeper) - 2026-11-17

- Gatekeeper's window has the new look, laid out like a game's menu, with the same five sections as the launcher across the top: Servers, People, Constellation, Machine and Options. Click one, or flip with Q and E (the bumpers on a controller).
- Where things are now. Servers: MCSS and the list of servers, New server, Server setup, Check for updates; opening a server shows its Access Manager, Server Status and Updates Pulled as before. People: the web panel's accounts, and a button to the Access Manager of the first server. Constellation: the clients, with What's new in Gatekeeper and What's new in Gatehouse beside it. Machine: Host setup. Options: what the wheel used to open (the wheel is gone).
- A red number on People is how many launchers are waiting for an answer; on Machine, how many setup steps are still to do.
- The lines for MCSS and each server are slim cards that glow while one of their buttons is selected. A strip along the bottom shows the version, and what the buttons do.
- A controller now drives this window as it does the launcher's: move with the stick or d-pad, A presses, B (or Esc) goes back from a server to the list.
- Not changed yet: the Manage window, the pages' own contents, and the web panel in a browser.

## 3.7.0 (Gatehouse) - 2026-11-17

- A server's own page (what opens after Launch) has the new look too. Its tabs are now the sections across the top: Updates, Server news, Connection, Server, Mods, and Gatekeeper when the host has allowed this device Gatekeeper control. The bumpers (Q and E) flip between them, and a section with something new has a red dot.
- Play, Restart, Quit and the Gatehouse button (back to the menu) are in the strip along the bottom, with what reaches them. B on a controller, or Esc, goes back to the menu at once.
- The page shown while a pack is being brought up to date ("Getting ... ready") sits in the same frame.
- In a narrow window the sections in the top bar are squeezed together instead of any being pushed out of sight.

## 3.7.0 (Gatekeeper) - 2026-11-17

- Nothing changed in Gatekeeper itself: this version carries the launcher's next step (a server's own page in the new look). Gatekeeper's own window is next.

## 3.6.0 (Gatehouse) - 2026-11-17

- Gatehouse has a new look, laid out like a game's menu. Five sections run across the top: Servers, People, Constellation, Machine and Options. The left and right bumpers flip between them (Q and E on a keyboard), or click one.
- Gatekeepers and servers are cards now, each with its art, its name, how it is, and its buttons. The card you are on glows. A card's colours and initials come from its name until servers can have pictures of their own.
- Servers is where you pick a Gatekeeper and then a server, as before. People shows what this device may do on each installed server. Constellation has how this launcher updates (moved here from Options), Check for updates, and what changed in Gatehouse and in the open Gatekeeper. Machine is what used to be the Connections tab. Options keeps the setup guide, Play or Host and Uninstall.
- A strip along the bottom shows the version and update mode on the left (and the constellation's pips while it gathers), and what the buttons do on the right. It shows a controller's buttons or the keyboard's keys, whichever you used last.
- B on a controller, or Esc, now goes back at once instead of only selecting the back button. X selects Open Prism Launcher. Y and Start select Restart and Quit as before, for A to confirm.
- Tick boxes are drawn as switches and round buttons are clearer, so on and off can't be mistaken.
- Not changed in this version: a server's own page (what opens after Launch). That came in 3.7.0.

## 3.6.0 (Gatekeeper) - 2026-11-17

- The first step of the new look, which starts with the launcher: Gatekeeper's own window is laid out as before for now. Its turn is next.
- What you will notice here already: tick boxes are drawn as switches (green with the knob to the right when on, grey when off), and the chosen one of a set of round buttons has a bright ring with a filled middle, so on and off can't be mistaken.

## 3.5.2 - 2026-11-17

- A test update: nothing changed. It is here so that a constellation updating together can be watched on real devices: the chime as it goes, where each client fetched it from in the update log, and how quickly word of it travels.

## 3.5.1 (Gatehouse) - 2026-11-16

- When Gatehouse updates itself it now notes where it fetched the update from (the pack server at an address, or GitHub), and tells the Gatekeepers in your list. Their update log shows it.
- In constellation mode a launcher that sees a new version before its Gatekeeper has now passes that on straight away, so the whole constellation finds out sooner.
- When the constellation is ready and your Gatekeeper gives the go, Gatehouse plays the constellation's chime (four soft rising notes) before it updates.

## 3.5.1 (Gatekeeper) - 2026-11-16

- The update log says where each update was fetched from: a new "Fetched from" column, in Gatekeeper's window and on the web panel's Updates page. For Gatekeeper it is the place it downloaded its own update from (the pack server at an address, or GitHub). For a launcher it is the place that launcher noted when it fetched its update; a launcher that was updated by the Gatekeeper on its own machine says so, with where that Gatekeeper got it.
- "Not said" there means the client didn't know: it is on a version from before this, or its launcher arrived with a pack's own update or an installer and not through the launcher's Update.
- Word of an update now travels through a constellation instead of each client finding it by itself. A launcher or member Gatekeeper that sees a newer version before this Gatekeeper has makes it look at once (it used to look once a minute), and a member is told at once when the Gatekeeper it joined starts gathering. The log says who saw it first.
- A constellation that is ready plays its own chime as it gives the go: four soft rising notes over a quiet chord, on this Gatekeeper and on every client told to go.

## 3.5.0 (Gatehouse) - 2026-11-16

- Along with its version and update mode, Gatehouse now tells the Gatekeepers in your list its operating system and Java version. A Gatekeeper keeps an update log from that: when each client went from one version to another, and how. Its owner reads it at the machine, or through the web panel with an account allowed to.

## 3.5.0 (Gatekeeper) - 2026-11-16

- An update log for the whole constellation: every time a client goes from one version of the program to another, Gatekeeper keeps a line saying when, which client and what kind, who plays on it, its system (operating system and Java), from which version to which, and how (normal, auto or constellation). It covers this Gatekeeper's launchers, this Gatekeeper itself, and the Gatekeepers that joined with their launchers: a member keeps its own log and hands new lines on to the Gatekeeper it joined, marked as having come through it.
- Reading it: Update log on the Constellation tab, at the machine. Through the web panel it has a permission of its own, "See the update log", so only an account you gave that to (with the password you set for it) can read it; the web panel has a new Updates page for those accounts.
- What it can't know: updates from before this version, and a launcher's update is noticed when that launcher next reports, so its time is when it reopened. The log is the file updates.json in Gatekeeper's folder, readable by whoever can read this machine's files; the newest 5000 lines are kept.

## 3.4.1 - 2026-11-16

- A test update: nothing changed. It is here so that a constellation updating together can be watched on real devices, with the pips that 3.4.0 added.

## 3.4.0 (Gatehouse) - 2026-11-16

- In constellation mode, while your Gatekeeper is gathering an update, Gatehouse shows who it is waiting for under its title: a row of pips, one for each client being taken along, and a line such as "Waiting for 2 of 6 others". Green is ready, amber hasn't seen the update yet, red is a Gatekeeper with a server still on. Gatekeepers are squares, launchers circles, and your own pip has a white ring. Pointing at the row says which is which (with names only if you have Gatekeeper control there, or it is your own machine's Gatekeeper).

## 3.4.0 (Gatekeeper) - 2026-11-16

- In constellation mode, while an update is being gathered, every client shows who the constellation is waiting for: a row of pips, one for each client being taken along, with a line beside it such as "Waiting for 2 of 6 others". Green is ready, amber hasn't seen the update yet (or, for a Gatekeeper, one of its launchers hasn't), red is a Gatekeeper with a server of its own still on. Gatekeepers are squares and launchers circles, and a client's own pip has a white ring.
- Gatekeeper shows the pips at the bottom of its first screen and on the Constellation tab; pointing at them lists who is who. A Gatekeeper that joined another's constellation passes that Gatekeeper's picture on to its own launchers, with itself and its launchers in it, so every client in the constellation sees the same thing.
- Names behind the pips are given to Gatekeepers in the constellation, to the launcher on a Gatekeeper's own machine and to launchers with Gatekeeper control. Other launchers see the pips and the count without names.

## 3.3.1 (Gatehouse) - 2026-11-16

- Gatehouse now gives this install an id of its own (a random one, made once) and tells it to the Gatekeepers in your list along with its version and update mode. A Gatekeeper that hears of this launcher twice, from itself and through another Gatekeeper in its constellation, uses it to list the launcher once.

## 3.3.1 (Gatekeeper) - 2026-11-16

- The Constellation tab recognises the same client reached more than one way. A launcher paired with this Gatekeeper that is also paired with a Gatekeeper that joined was listed twice: once by itself and once under that Gatekeeper. It is now listed and counted once, with the other way beside it ("also through ..."), and a line under the list says how many clients are connected more than one way.
- How it knows: each install of the program now gives itself an id once and reports it to every Gatekeeper, directly or through another. A client from before this version is recognised by its name, player and system all matching, and the list says when that is how.

## 3.3.0 (Gatehouse) - 2026-11-16

- Three update modes, chosen in Options under Updating. Normal: you press Update (what it is unless you choose otherwise). Auto: while its window is open, Gatehouse updates itself as soon as it sees a new version, without waiting for anyone. Constellation: Gatehouse updates together with a Gatekeeper in your list and its other launchers; this is what 3.2.2 called "update automatically, together with my Gatekeepers", and a launcher that had that ticked is in constellation mode now.
- While its window is open, Gatehouse tells the Gatekeepers in your list its version and its update mode, whatever the mode. A Gatekeeper's owner sees that in its new Constellation tab.

## 3.3.0 (Gatekeeper) - 2026-11-16

- Three update modes, chosen in Options under Updating. Normal: nothing updates until you press Update Gatekeeper (what it is unless you choose otherwise). Auto: Gatekeeper looks every minute and updates itself as soon as a new version is out, waiting only for a server it runs itself to be off. Constellation: the group goes together; this is what 3.2.2 called "update automatically, together with the launchers that are open", and a Gatekeeper that had that ticked is in constellation mode now.
- A constellation is this Gatekeeper and every client connected to it. The new Constellation tab lists them all: each client's kind, player, system, version, update mode (normal, auto or constellation) and whether it is open now, with a count of how many are in each mode and how this Gatekeeper's own update stands. Launchers now report in while their window is open whatever their mode; one from before this version shows as "not said".
- Each launcher chooses its own mode in its own Options. In constellation mode Gatekeeper waits only for the open launchers that are in constellation mode too.
- Gatekeepers can join one constellation. On the Constellation tab, give another Gatekeeper's address (or its pairing link) and press Join: this Gatekeeper introduces itself there and is listed as having asked. That Gatekeeper's owner allows it in on their own Constellation tab (Allow into this constellation), or removes it. Joining gives no control of anything.
- Once a Gatekeeper is a member, the Gatekeeper it joined lists it with its version and mode, and its launchers under it. In constellation mode the member no longer decides about updates by itself: it reports when it is ready (it has seen the update, its own constellation-mode launchers have, and no server it runs itself is on), the leading Gatekeeper waits for it like for any client, and both update on the lead's go, each taking its own launchers along. If the lead can't be reached for two minutes, a member carries on by itself. Leave returns it to leading its own constellation.

## 3.2.4 - 2026-11-16

- A test update: nothing changed. It is here so that updating itself can be tried, automatic updating in particular (Options, Updating).

## 3.2.3 (Gatehouse) - 2026-11-16

- Nothing changed in the launcher itself. Automatic updating with two Gatekeepers in your list: this launcher updates with whichever of them goes first, and the second no longer waits up to ten minutes for it before updating too (fixed in Gatekeeper).

## 3.2.3 (Gatekeeper) - 2026-11-16

- Automatic updating, fixed for a launcher that is connected to two Gatekeepers: it updates with whichever of them goes first, and the other Gatekeeper then treated it as a launcher that hadn't seen the update yet and waited for it, for up to ten minutes. A launcher already on the version that is out now counts as ready.

## 3.2.2 (Gatehouse) - 2026-11-16

- Fixed: a pack or connection left on this device for a server its Gatekeeper no longer has (the owner removed or deleted it) showed as a working server, and its buttons acted on a different server on that Gatekeeper. It now says "No longer on this Gatekeeper" and can't be launched; delete it in Prism Launcher when you no longer need it.
- Fixed: Steam's pop-up was drawn into Gatehouse's window, sometimes over the buttons at the bottom right, and left a smear behind. Gatehouse had a setting against this that was being applied too late to take effect; it is now applied first. The window also redraws itself for a while after it opens.
- New, and off unless you turn it on: Options, Updating, "Update automatically, together with my Gatekeepers". With it on, while Gatehouse's window is open it tells the Gatekeepers in your list that it is here. A Gatekeeper whose owner has turned on the same thing waits until every open launcher has seen a new version and then has them all update at once, this one included, without Update being pressed. With it off nothing changes: you press Update.
- On a machine that hosts and plays, its own Gatekeeper is listed once. If it was already in your list from pairing by hand, that line is marked "(this machine)" and has the Open Gatekeeper button; the separate line above it is gone.

## 3.2.2 (Gatekeeper) - 2026-11-16

- Fixed: a launcher asking about a server this Gatekeeper no longer has (removed here, or deleted in MCSS) was answered as if it had asked about the first server. A device with a leftover pack or connection for the removed server then showed, started and stopped a different server through it. Such a launcher is now told the server is gone.
- Fixed: Update Gatekeeper could seem to do nothing, most of all on a Steam Deck. The window gave up waiting after five seconds, while fetching the update over the internet takes longer than that, and then said it couldn't reach Gatekeeper although the update was still going. It now waits as long as the fetch takes, and says plainly what happened. The same went for Check for updates and for New server.
- When an update is waiting for a server to be off, the update bar says so (and which version is waiting) instead of offering the button again.
- New, and off unless you turn it on: Options, Updating, "Update automatically, together with the launchers that are open". With it on, when a newer version is out Gatekeeper looks every minute, waits until every open launcher that has the same thing on has seen the update (at least fifteen seconds, at most ten minutes), waits for any server it runs itself to be off, and then tells them all to go and updates itself a few seconds later. Options shows how it stands and which launchers are taking part; the log says each step. Launchers without it on are not waited for and update when their player presses Update.

## 3.2.1 (Gatehouse) - 2026-11-16

- Fixed on Steam Deck: Gatehouse didn't recognise the Gatekeeper on the same machine. Play or Host showed Host as not set up, the This machine hosts servers line was missing, and the launcher didn't link itself to it. Gatehouse looked for Gatekeeper's folder, which it can't see on a Deck (it runs inside Prism Launcher's Flatpak, which is shown only its own files). It now asks whether a Gatekeeper is answering on the machine.
- Open Gatekeeper now asks the running Gatekeeper to open its own window, so it works from inside that sandbox too. This needs Gatekeeper 3.2.1 on that machine as well.
- Shortcuts: the Gatehouse shortcut in Steam still opens the launcher directly. The Gatehouse entry outside Steam (the Start menu on Windows, the applications menu on Linux and SteamOS) now opens the Play or Host screen instead, where either half is opened or set up. On Windows an existing Start menu shortcut is changed to do this the next time Gatehouse is opened; on Linux and SteamOS the Gatekeeper on the machine does it, and a device with no Gatekeeper keeps its old entry until Gatehouse's install script is run again.

## 3.2.1 (Gatekeeper) - 2026-11-16

- Fixed on Steam Deck: the launcher didn't recognise the Gatekeeper on the same machine, so Play or Host offered to set it up again, there was no This machine line, and the launcher didn't link itself. The launcher looked for Gatekeeper's folder, which it can't see there (it runs inside Prism Launcher's Flatpak). It now asks for a Gatekeeper on the machine instead, and this Gatekeeper answers.
- The launcher can ask this Gatekeeper to open its own window, which is how Open Gatekeeper works from inside that sandbox.
- Shortcuts: the Gatehouse entry outside Steam (the Start menu on Windows, the applications menu on Linux and SteamOS) now opens the Play or Host screen, and the Gatehouse shortcut in Steam goes on opening the launcher directly. On Linux and SteamOS, Gatekeeper brings an existing menu entry up to date when it starts, because the launcher there can't reach it.

## 3.2.0 (Gatehouse) - 2026-11-16

- On a machine that hosts and plays, the launcher now links itself to that machine's Gatekeeper when it opens: no pairing link, no request to wait for, and no Tailscale needed between the two. The Gatekeeper appears in the list by itself if it wasn't there; if you had already paired with it, that pairing is the one used.
- Linked that way, this launcher is allowed everything on that Gatekeeper (start and stop servers, MCSS, sleep, the Access Manager), and the account you play as in Prism is put on each server's whitelist, so Play works straight away.
- This is only for the launcher on the same machine as the Gatekeeper. On any other device nothing changes: you pair, and the owner allows what they choose.

## 3.2.0 (Gatekeeper) - 2026-11-16

- The third step of making Gatehouse and Gatekeeper one program: the launcher on this same machine links itself to this Gatekeeper. It needs no pairing link, no request for you to answer, and no Tailscale, because it asks through the door that only answers on this machine. It shows in the Access Manager like any launcher, allowed everything.
- That launcher is allowed everything here, on every server, including ones made later; and the Minecraft account it plays as is put on each server's whitelist, with the server off or on.
- Only this machine's own launcher gets this. A launcher on any other device still introduces itself and waits for you to allow it, whoever plays on it.
- Launchers on this machine can also reach Gatekeeper without Tailscale now, and are given this machine itself as the address to join a server at.

## 3.1.0 (Gatehouse) - 2026-11-16

- For someone who hosts and plays on the same machine: the first screen now shows "This machine hosts servers" at the top, saying whether its Gatekeeper is running in the background, with an Open Gatekeeper button. Playing and hosting are on at the same time; the Gatekeeper never needs its window open.
- One update covers both halves on such a machine: pressing Update here also tells the Gatekeeper on this machine to update. If a server it runs itself is on, the Gatekeeper waits until that server is off (so the game you are in isn't interrupted) and then updates by itself.

## 3.1.0 (Gatekeeper) - 2026-11-16

- The second step of making Gatehouse and Gatekeeper one program: on a machine that has both halves, one update covers both. When Gatekeeper updates, the Gatehouse launcher on the same machine gets the same file at once; when the launcher is updated, it tells this Gatekeeper to update too.
- An update asked for while a server Gatekeeper runs itself is on no longer has to be asked for again: it waits, and Gatekeeper updates by itself as soon as that server is off. (Updating restarts Gatekeeper, which would lose that server's console.) Servers MC Server Soft runs don't hold an update up.
- The program can be started with --start, for one shortcut to the whole thing: it opens the launcher where that is set up (Gatekeeper is a button away in it), Gatekeeper's window where only that is, and the Play or Host screen where neither is.

## 3.0.0 (Gatehouse) - 2026-11-16

- Gatehouse and Gatekeeper are now one program with two halves: Play (this launcher) and Host (Gatekeeper, which looks after the Minecraft servers on a machine). This is the first of four steps towards that, and nothing about playing changes in it.
- Options has a new Play or Host button: the program's first screen, with a card for each half that says whether it is set up on this device and a button that opens it, or sets it up if it isn't. Someone who only plays can ignore it.
- From now on both halves carry the same version number (this one is 3.0.0 for both).

## 3.0.0 (Gatekeeper) - 2026-11-16

- Gatehouse and Gatekeeper are now one program with two halves: Play (the Gatehouse launcher) and Host (Gatekeeper). This is the first of four steps towards that, and nothing about how Gatekeeper works changes in it: the same window, the same background service, the same settings and permissions.
- The one program file is published under both of its old names, so this Gatekeeper updates exactly as before, and from now on the two halves always carry the same version number (this one is 3.0.0 for both).
- Play or Host, at the bottom of the first screen, opens the program's first screen: a card for each half that says whether it is set up on this device, with a button that opens it, or sets it up if it isn't (the setup opens in a window of its own and asks before it fetches anything).
- Still to come, in later updates: one installer per system, a Gatehouse on the same machine linking to its Gatekeeper by itself with its player whitelisted and allowed everything, and both halves updating in one go.

## 2.10.1 (Gatehouse) - 2026-11-16

- Remote Gatekeeper, Access Manager: the sets of buttons under the list (Server accesses, Administrative actions, List) were squeezed in a narrow window until buttons disappeared. They now sit side by side while they fit and move into rows when they don't, so every button stays whole; scroll down if the window is short. "Administration actions" is now "Administrative actions".

## 2.10.0 (Gatehouse) - 2026-11-16

- Gatehouse has an icon, on its windows and on the taskbar: the gatehouse, with a blue gate. A red circle with a number on it means something chimed while you weren't looking at Gatehouse (the owner answered a request, a server you asked for came up, an update turned up); it clears when you look. A green circle means a Gatehouse update is ready.
- Buttons that open a web page now try every way the system has, the same on Windows, Linux and SteamOS.
- Buttons on the Connections tab could miss a click, because the tab was redrawn every few seconds and a button replaced between press and release did nothing. It is now redrawn only when something on it changed.
- Three buttons that did nothing when they failed now say what went wrong: Open Tailscale, Open Windows' installed apps, and the setup guide's Open Prism Launcher.

## 2.9.2 (Gatehouse) - 2026-11-16

- Check for updates, at the bottom of the main window: Gatehouse looks right now for a newer Gatehouse instead of waiting, offers again an update you put off with Later, asks the Gatekeeper on screen again (so an "Update needed" clears as soon as its owner has updated), and says what it found.

## 2.9.1 (Gatehouse) - 2026-11-16

- Connections now shows what really runs the servers on a Gatekeeper's machine. Where the Gatekeeper runs them itself (always on Linux and SteamOS), the MCSS line is still listed, but grey, saying "Not supported on SteamOS: Gatekeeper runs the servers itself" (or Linux; on Windows, "Not used here") instead of a green "Running", and its button is greyed. Where a Windows Gatekeeper uses both, each server says whether MCSS or Gatekeeper itself runs it.
- A Gatekeeper line, under Server machine: that it is answering, its version and the system it runs on. This needs Gatekeeper 2.7.2 or newer on the server machine; with an older one nothing changes.

## 2.9.0 (Gatehouse) - 2026-11-16

- A setup guide: the steps from a device with nothing set up to playing on a server, one at a time. Prism Launcher, your Minecraft account, adding the owner's Gatekeeper, connecting to it, installing a server, and playing. Each step says what to do, has a button for it where there is one, and shows whether it's done; the guide looks again every few seconds, so a step ticks by itself.
- The guide opens by itself on a device that has no Gatekeeper and no server yet, and is always in Options (the wheel at the top right).
- The steps can carry pictures; none are included yet.
- Fixed: Options had no Uninstall button for a Gatehouse that came with a pack when Windows had no uninstaller for that pack on record (it only offered to open Windows' installed apps, where there was nothing to find). Gatehouse now carries its own uninstall script and runs it there: it takes out Gatehouse's program files, menu entry and Steam shortcut, with a tick box for what it remembers. The servers installed in Prism Launcher stay, and are removed in Prism Launcher.

## 2.8.1 (Gatekeeper) - 2026-11-16

- Access Manager: the sets of buttons under the list (Server accesses, Administrative actions, List) no longer get squeezed when the window is narrow. They sit side by side while they fit and move into rows when they don't, and a set wider than the window puts its own buttons in rows, so every button stays whole. "Administration actions" is now "Administrative actions".

## 2.8.0 (Gatehouse) - 2026-11-16

- A settings wheel at the top right of the main window opens Options: which Gatehouse this is and how it got here, and Uninstall.
- Uninstall asks you to type confirm first. A Gatehouse installed by itself is removed there and then by its own uninstall script (its files, its menu entry, its Steam shortcut if Steam is closed), with a tick box for also removing what it remembers; you're shown what the script did, and Gatehouse closes. One that came with a pack is removed with that pack: Options opens the pack's own uninstaller where Windows has it on record, and otherwise says where to find it.
- On Linux inside Prism Launcher's Flatpak, Gatehouse can't reach the applications menu or Steam, so Options gives the one command to run in a terminal instead.

## 2.8.0 (Gatekeeper) - 2026-11-16

- Fixed on SteamOS and Linux: buttons that open a web page did nothing (in the setup guide: Open Tailscale's admin page, Open MCSS's site, and the rest; and Open the EULA in New server). They now use the system's own way of opening a page, and if nothing on the machine takes it, the address is shown with a Copy button instead of nothing happening.
- Buttons on the first screen could miss a click: the list of servers was redrawn every three seconds, and a button replaced between press and release did nothing. It is now redrawn only when something on it changed. A long folder path also no longer pushes a server's buttons off the right edge: the text is shortened instead.
- Gatekeeper notices when MC Server Soft has deleted a server it still lists (the server's folder, or MCSS's own file in it, is gone from MCSS's servers folder). It says so in red on the first screen with a Remove from Gatekeeper button, in Host setup and once in the log, stops showing that server to launchers, and refuses to start it.
- The window keeps one size: it opens at the size you last gave it (a fixed size the first time) instead of fitting itself to whatever was on screen that day, and switching tabs or screens doesn't change it.
- Gatekeeper has an icon, on its window and on the taskbar: the gatehouse, with an amber gate (Gatehouse's is blue). A red circle with a number on it means launchers' requests are waiting for you; a green circle means a Gatekeeper update is ready.

## 2.7.4 (Gatekeeper) - 2026-11-16

- Check for updates, at the bottom of the first screen: Gatekeeper looks right now for a newer Gatekeeper instead of waiting for its next look (every five minutes), and says what it found: up to date, a newer one (Update Gatekeeper then shows at the top), or that it couldn't reach where Gatekeeper is published.

## 2.7.3 (Gatekeeper) - 2026-11-16

- A server can be deleted: Manage, Settings, Delete this server. It asks which you mean: take it out of Gatekeeper and leave its files where they are, or delete it for good, folder and world included. Deleting for good needs the server's name typed, and can't be undone. Either way the server has to be off first, backups Gatekeeper made of it are kept, and launchers stop listing it.
- This is for servers Gatekeeper runs itself. A server MC Server Soft runs is deleted in MCSS; Gatekeeper stops looking after it when you untick it in Server setup. Deleting can't be done from the web panel.

## 2.7.2 (Gatekeeper) - 2026-11-16

- Launchers are now told what runs the servers on this machine: MC Server Soft, Gatekeeper itself, or both, and for each server which of the two. Before, a Gatekeeper that runs the servers itself (every one on Linux and SteamOS) answered that MCSS was running, so launchers showed a green "MCSS: Running" for a machine that has no MCSS. Gatehouse 2.9.1 shows it properly.

## 2.7.1 (Gatekeeper) - 2026-11-16

- Fixed: Manage opened the wrong server, or said "MC Server Soft does this" for a server Gatekeeper runs itself. Once any server had been opened in the window, Manage asked about that one (or the first in the list) instead of the one whose button was pressed. The web panel was not affected.
- New server: the File box is only for the last two kinds (a Forge or NeoForge installer, or a server jar you already have). For the others it is greyed and now says why ("Not needed for this kind: Gatekeeper fetches it"), and the version list says the same when a file decides the version.

## 2.7.0 (Gatehouse) - 2026-11-16

- Gatehouse can be installed by itself, with no game and no server pack: a new installer (gatehouse-install.ps1 on Windows, gatehouse-install.sh on Linux and Steam Deck) sets up just the launcher. You then add a Gatekeeper and install its servers from Gatehouse.
- Installed that way, Gatehouse has a home of its own (a gatehouse\app folder inside Prism Launcher's) and keeps itself and the scripts beside it up to date from there, whether or not any pack is installed.
- A plain server can be set up on a device with no pack installed: it takes its launcher files from Gatehouse's own home.
- On Windows the same installer can install Gatekeeper instead, or both.
- The installer offers to add what it installed to your Steam library (Gatehouse, and on Windows Gatekeeper's window), with its art. Steam has to be closed while that's done; the installer never closes or starts it.
- The same script removes it again (-Uninstall on Windows, --uninstall on Linux): the Steam shortcut, the Start menu or applications-menu entry, and Gatehouse's files. What Gatehouse remembered (its Gatekeepers, this device's identity with them) is kept unless you ask for it to go too. Prism Launcher and anything installed in it are left alone.

## 2.7.0 (Gatekeeper) - 2026-11-16

- Where Gatekeeper runs a server itself, it now does for it what MCSS does for its servers. That is every server on Linux and SteamOS. On Windows with MCSS it is a choice: tick "Gatekeeper may also run servers itself, beside MCSS's" on the first screen, and New server makes servers that Gatekeeper runs itself (in C:\Minecraft\servers unless set otherwise), each with Manage, next to the ones MCSS runs. Each server says which of the two runs it. MCSS's own servers stay MCSS's: for those, Manage is greyed and says MCSS does this. Until the box is ticked nothing changes on Windows.
- New server, on the first screen, makes a Minecraft server from nothing: a name, its kind and the Minecraft version, and Gatekeeper fetches the server's program from its makers, checks it and sets it up in a folder of its own. Vanilla comes from Mojang and Paper from PaperMC, both checked against the checksum they publish; Fabric comes from Fabric (which publishes no checksum for it); for Forge or NeoForge you pick the installer you downloaded and Gatekeeper runs it; or pick a server jar you already have. It finds a free port, picks a Java the version can run on (it says which one is missing if none fits; it doesn't install Java), and nothing is fetched until you press Make the server.
- Minecraft's EULA is still yours to agree to: New server has a box for it and makes no server without it, and Gatekeeper writes eula.txt only when you ticked it. A server you brought yourself has the same box under Manage, Settings. This replaces 2.6.0's 'Gatekeeper downloads no server'.
- Manage, beside each server, opens five tabs. Console: what the server prints, a line to type commands, how long it has been up, its memory, processor use and players, and Start/Stop, Restart and End it now (for a server that no longer answers). Settings: name, memory, port, which Java, an own start command, start it when Gatekeeper starts, start it again if it crashes (given up after three times in ten minutes). Game settings: the server's own server.properties. Backups: a zip of the world or of the whole folder, made with the server told to hold its saving still, keeping the newest few. Tasks: things done by the clock (every day at a time, or every so many minutes): restart, stop, start, back up, or type commands, optionally only when nobody (or somebody) is playing.
- Forge and NeoForge servers are started from the argument files their installer leaves, with the Java and memory you set, on Windows too.
- A web panel, on every system and with or without MCSS: a page for controlling this Gatekeeper from a browser after signing in. It is off until you turn it on in the new Web panel tab, and it is served only on this machine's Tailscale address, so only devices on your Tailscale network can open it. From it, as far as the account is allowed: see, start, stop and restart servers (and start or close MCSS), the players and the whitelist, kick, launchers' requests and permissions, Gatekeeper's log, updating Gatekeeper, and, where Gatekeeper runs the servers itself, a server's console, Back up now, running a task and New server.
- Accounts for it are made, changed and removed only in Gatekeeper's window on this machine (Web panel tab): a name, a password of at least 10 characters that you type yourself, and what the account may do (see; start and stop; console; settings, backups and tasks; launchers and players; sleep and wake), or admin for everything. Gatekeeper keeps only a salted, scrambled form of a password that can't be turned back. Five wrong passwords lock that account out from that device for five minutes, sign-ins and refusals go in the log, and a session ends after two hours unused, after a day, when the account is disabled or its password changed, and when Gatekeeper restarts.
- What the web panel can never do, whatever the account: make or change accounts, Server setup, Gatekeeper's sign-in or key for MCSS, or anything that names a program or a place on this machine's disk (a server's own start command, its Java, its backup folder, a server from an installer or a jar). Those stay at the machine.
- The panel can wake other computers on this machine's home network (Wake-on-LAN): list them in the Web panel tab by name and MAC address, and an account with the sleep-and-wake permission gets a Wake button for each. The device has to be set to wake on a network signal (its adapter's Power Management, often its BIOS too). The panel can also put this machine to sleep, with its servers stopped; it can't wake this machine, since it runs on it.
- Not carried over from MCSS: its API keys for other programs, Bedrock and proxy servers (BungeeCord, Waterfall, Velocity), and its plugin list.
- Tested with stand-ins only: a pretend download source, installer and Minecraft server, and dummy accounts on a test Gatekeeper. It has not yet fetched from the real Mojang, PaperMC or Fabric, run a real Forge installer, run a real Minecraft server, been opened over real Tailscale, or woken a real device.

## 2.6.2 (Gatehouse) - 2026-11-16

- Fixed: removing a Gatekeeper and adding it again made this device look like a new one to its owner (a second entry in their Access Manager, with nothing allowed). When a pack from that Gatekeeper is installed, adding it again now picks up the same identity, so everything the owner had allowed still applies.

## 2.6.1 (Gatehouse) - 2026-11-16

- Fixed: after Gatehouse updated itself it didn't always open again. Three causes, all dealt with. The shortcut's script reopened it only once, so a second update in the same sitting just closed it; it now reopens it every time. On Windows the script waited for everything Gatehouse had started, so with Prism Launcher still open the new Gatehouse didn't appear until Prism was closed; it now waits for Gatehouse alone. And the copy Gatehouse runs from could still be locked for a moment; it now tries again, under a second name if need be.
- Gatehouse never updates backwards. A source that is behind (GitHub's copy can lag by some minutes) used to be offered as an update because it was different; now only a newer version is.
- The script changes arrive with a pack's update, so they take effect one launch after this. Until then, on a second update under the old script, Gatehouse opens the new one itself.

## 2.6.0 (Gatehouse) - 2026-11-16

- Third step of making Gatehouse one program: the two ways left that still opened a second window now stay in this one.
- A plain server: Launch shows its launcher (whether it's on, Request server, the whitelist, Server controls, Play) inside the main window, like a pack's.
- A pack started from its own Steam shortcut: the update's checklist and the launcher show in this window. As before, it goes straight into the game when there's nothing new to see and the server is ready.
- Both take effect one launch after this update arrives. Starting an instance from Prism Launcher itself still opens the launcher as a window of its own, and so does a pack whose scripts are older; that path is kept as the fallback.

## 2.6.0 (Gatekeeper) - 2026-11-16

- Gatekeeper can run the Minecraft servers itself, without MC Server Soft. This is what makes hosting on Linux and SteamOS work: MCSS is Windows only, and the notes for 2.5.0 were wrong to speak of a Linux build of it. On Linux and SteamOS Gatekeeper always runs the servers itself; on Windows it keeps using MCSS unless host.json says "host": "builtin".
- A server is then a folder with the server's program in it, inside one servers folder (minecraft-servers in your home folder, or the one you pick in Server setup). Gatekeeper starts it there, keeps its console (stop, the whitelist, kicking a player), knows when it has finished starting, and notes in its log how it ended and the last it said if it failed. It starts run.sh where there is one, else the server's jar with Java; the Java and the memory can be set per server in host.json ("java", "memory"; 4G unless set).
- Gatekeeper downloads no server and accepts nothing for you. A server whose eula.txt doesn't say eula=true isn't started, and Host setup and the setup guide say where that file is.
- What belongs to MCSS is still listed and greyed, marked "not supported on Linux" (or SteamOS): Start MCSS, Sign in to MCSS, Set MCSS key, the MCSS control permission, and three steps of the setup guide. Server control is the permission that starts and stops a server.
- A server only has a console while the Gatekeeper that started it is running. So Gatekeeper doesn't update itself while a server it runs is on: stop the server, then update. If Gatekeeper ends anyway, the server carries on and the next Gatekeeper shows it as on; on Linux it can still stop it properly, on Windows it tells you to stop it from inside the game.
- Nothing changes for a Gatekeeper that uses MCSS.

## 2.5.0 (Gatehouse) - 2026-11-16

- A server's launcher can now show Update needed instead of Play, with the reason. Its Gatekeeper says so in two cases: the Gatekeeper itself is out of date (its owner has to update it), or Gatehouse on this device is (update it on the main window: the Update Gatehouse line, or Restart).
- Gatehouse tells a Gatekeeper which version it is, so the Gatekeeper can tell.
- A pack started straight from its own Steam shortcut shows its launcher instead of going straight into the game when joining is blocked like this.

## 2.5.0 (Gatekeeper) - 2026-11-16

- Gatekeeper runs on Linux and SteamOS as well as Windows. It is the same program doing the same things: the servers, Access Manager, Host setup, the setup guide, updating itself, Options and Uninstall. On Linux it is installed by install-keeper.sh (a script you can read; no system password): it runs as a systemd user service, has an entry in the applications menu, and lives in ~/.local/share/GatehouseKeeper. It needs Java 17 or newer. (This note first said it needs MC Server Soft's Linux build. There is none: see 2.6.0.)
- What a system can't do is still listed where it would be, marked "Not supported on SteamOS" (or Linux). On SteamOS that is putting the machine to sleep from a launcher: a Steam Deck that is asleep can't be woken from another device, so the Machine wake/sleep permission is greyed out, and Host setup says so. Keep the Deck plugged in and awake while it serves.
- Host setup and the setup guide use each system's own wording (a terminal and systemd on Linux instead of the icon by the clock), and on Linux check that Tailscale's service starts with the machine. On SteamOS they say that Tailscale doesn't officially support it and that a SteamOS update can remove it.
- On Linux, closing MCSS asks it to end the ordinary way and never forces it, as on Windows.
- Nothing changes for a Gatekeeper on Windows.

## 2.4.1 (Gatehouse) - 2026-11-16

- Any Gatekeeper can now be removed from the list. Until now Remove was only there for one with no packs installed. It asks first. Gatehouse forgets the Gatekeeper and this device's link to it; packs installed from it stay, move to Other packs, and update and start as before.
- A removed Gatekeeper stays off the list (an installed pack that names it no longer brings it back) until you add it again with its address, link or file.
- On the owner's side nothing changes: this device stays in their Access Manager until they remove it there.

## 2.4.0 (Gatehouse) - 2026-11-16

- New: Connect to this Gatekeeper. When a Gatekeeper you add doesn't answer, Gatehouse no longer just says so: it remembers it and shows the steps to get connected, each with how it stands and what to do. Tailscale running on this device, the owner's invite accepted, this device reaching the Gatekeeper, and the Gatekeeper knowing this device. It looks again every few seconds, so each step ticks by itself, and it chimes when you're connected.
- A pairing link or file can now carry the owner's Tailscale invite (from Gatekeeper 2.1.0, Host setup). The steps then have an Open invite button, which gives this device access to that one machine. Only a link from login.tailscale.com is ever opened.
- A Gatekeeper in the list that isn't answering has a Connect button that opens the same steps.
- Gatehouse doesn't install Tailscale, sign in or accept anything for you: its buttons open Tailscale's own download page, Tailscale itself, and the invite in your browser.

## 2.4.0 (Gatekeeper) - 2026-11-16

- A setup guide: the steps from a machine with nothing on it to a server your friends can join, one at a time. Tailscale (installing it, signing in, keeping the machine signed in), MC Server Soft (installing it, making a server, turning on its web API), choosing the servers, letting Gatekeeper use MCSS, ports, the whitelist, the invite for players, and pairing. Each step says what to do, has buttons that open the right page or the right part of Gatekeeper, and shows whether it's done; the guide looks again every few seconds, so a step ticks by itself.
- The guide opens by itself the first time Gatekeeper's window is opened on a machine that still has steps to do, and is always in Options (the wheel at the top right). Host setup stays as the list of everything at once.
- The steps can carry pictures; none are included yet.

## 2.3.1 (Gatehouse) - 2026-11-16

- Taken out again: the Steam Deck controller-layout switching from 2.3.0. Gatehouse no longer asks Steam to change layouts, and its own Steam shortcut gets the pack's layout back with the next update (shows after Steam restarts, or after switching back to Game Mode).
- Instead, on a Steam Deck, Gatehouse's own windows take a right click as a left click. The pack's layout makes a press of the right trackpad a right click, which is what the game wants; in Gatehouse that press now presses whatever the pointer is on. Nothing about the layout or Steam changes, and the game is as before.

## 2.3.0 (Gatehouse) - 2026-11-16

- Steam Deck: no special controller layout while Gatehouse is what's open. The pack's layout (back grips, trackpad as mouse) is for the game. Gatehouse's own Steam shortcut no longer gets it, so Steam's plain layout applies there; the update takes the pack's layout off that shortcut if it's still as the pack left it (shows after Steam restarts, or after switching back to Game Mode).
- When you press Play, Gatehouse asks Steam to use the layout of the pack's shortcut for the game, and to go back to its own choice when the game is over. Opened from a pack's own shortcut, it asks for the plain one while a Gatehouse window is up.
- This part is new and couldn't be tried against Steam before release. If Steam doesn't take the request, nothing else changes: the layout of the shortcut you started from stays in use. What was asked is noted in steam-input.log, beside Prism Launcher's instances folder.

## 2.3.0 (Gatekeeper) - 2026-11-16

- A settings wheel at the top right of the window opens Options: which Gatekeeper this is, where it runs from, where its settings are, and Uninstall.
- Uninstall asks you to type confirm first, then runs Gatekeeper's own install script with -Uninstall in a window you can read: Gatekeeper is stopped, and its start-up task, Start menu shortcut and program are removed. A tick box also removes its settings (who is allowed to do what, its key for MCSS, its log). MC Server Soft, the servers and their worlds are not touched.
- Gatekeeper now carries that script inside itself, so this works however it was installed.

## 2.2.2 (Gatekeeper) - 2026-11-16

- Nothing changed in what Gatekeeper does. This release exists so that Gatekeeper's own updating can be tried for real: the Update Gatekeeper line, the swap, and the restart.

## 2.2.1 (Gatehouse) - 2026-11-16

- Fixed: going from Launch to Play took a long time when there was nothing to update. The checklist could take the previous launch's result for this one's, show everything ticked, and then wait unseen for the real update to finish.
- With nothing to update, the update itself is now much shorter: the launcher already compares the pack's files with what's installed, and when everything matches the installer program isn't run at all. (As a safety net it still runs in full once a week, and whenever a file is missing or left over.)
- The update asks only the first pack source that answers, instead of waiting on all of them every time.

## 2.2.1 (Gatekeeper) - 2026-11-16

- Updating itself is sturdier. What was fetched is checked to be a Gatekeeper that can run before anything is replaced. The old program is kept until the new one has answered: if the new one doesn't answer within 30 seconds, the old one is put back and started again, and the log says so.
- After the swap Gatekeeper is started through its scheduled task and then looked for; if it didn't come up that way it is started directly. Any Gatekeeper window that was open is closed for the swap and opened again afterwards.
- The restart is done by a script you can read, update-restart.ps1 in Gatekeeper's folder, instead of a command passed along in encoded form. Each step it takes is written to host.log.

## 2.2.0 (Gatehouse) - 2026-11-16

- Second step of making Gatehouse one program: after the update, that server's launcher (Client updates, Server updates, Connection and the rest, with Play) now shows inside the main Gatehouse window too. Nothing closes and nothing opens between pressing Launch and pressing Play.
- The Gatehouse button goes back to the list of servers at once, in the same window. Restart runs the update again right there. Quit closes Gatehouse without starting the game.
- Play closes the window and has Prism Launcher start the game; Prism no longer shows a window of the launcher's on the way.
- As before, this takes effect one launch after the update arrives (the pack's update script has to be the new one). Starting a pack from Prism Launcher itself, or from its own Steam shortcut, still opens the launcher as a window of its own.

## 2.2.0 (Gatekeeper) - 2026-11-16

- Gatekeeper now updates itself, as Gatehouse does. Each build of the pack publishes the Gatekeeper it was made with (on the owner's pack server and on GitHub). A running Gatekeeper looks there every five minutes; when a newer one is out, its window says so at the top of the Servers tab, with a chime and an Update Gatekeeper button. Update fetches it, checks it against the published checksum, and restarts Gatekeeper as the new one in a few seconds. Minecraft servers that are running aren't touched.
- While Gatekeeper is out of date it starts no server (from its own window, for a launcher, or for a request it grants), and it tells launchers that joining is blocked: they show Update needed instead of Play, with the reason.
- A launcher whose Gatehouse is behind the published one is told the same, for itself only, until that player updates Gatehouse. (From Gatehouse 2.5.0, which is the first to say which version it is.)
- Host setup has a step for this, Gatekeeper is up to date.
- Host setup: the whitelist step now has a button, Turn the whitelist on. It asks first, saying how many players each server's whitelist has. A running server is told in its console (it takes effect at once); one that's off gets white-list=true in its settings file.
- If nothing to compare with can be reached, nothing is blocked. host.json "require_current": false turns the blocking off altogether.

## 2.1.2 (Gatekeeper) - 2026-11-16

- You can set the name launchers list this Gatekeeper under: Host setup, Name shown in launchers. Left empty it is this machine's own name, as before. Launchers that are open pick a change up within a few seconds; pairings made after it carry the new name.
- Fixed: a Gatekeeper with no MCSS chosen yet answered launchers with an error instead of its list of servers.

## 2.1.1 (Gatekeeper) - 2026-11-16

- Host setup looks again by itself every 5 seconds while its tab is open, so a change you make somewhere else (in MCSS, in Tailscale, in a server's files) shows without pressing anything. The line above the invite field says how many steps are left and when it last looked. A step that has just been done says "Done just now" for a minute.
- When two servers share a port, Host setup now names ports to use: the next ones up from 25565 that no server in MCSS is set to and that nothing on the machine is listening on.
- Ports are read from each server's own server.properties as it is now, so the port step turns green as soon as the file is changed. If Gatekeeper still has the old port from Server setup, a new step says so and what to do (Server setup, Save).

## 2.1.0 (Gatehouse) - 2026-11-16

- First step of making Gatehouse one program: pressing Launch beside a server now runs that pack's update inside the main Gatehouse window. The window stays where it is and shows the checklist, and the question about the download when there is one. Until now Prism Launcher ran the update, in a second window.
- When the update is done, Prism Launcher starts the instance and that server's launcher opens in the same place, without updating a second time. (That launcher is still a second window underneath; bringing it into this one is the next step.)
- Quit on the checklist cancels the launch and goes back to the list of servers.
- This starts working one launch after this update arrives: the pack's update script has to be the new one first. A pack whose script is older, and a plain server, open as before.

## 2.1.0 (Gatekeeper) - 2026-11-16

- New tab on the first screen: Host setup. It lists everything this machine needs to serve players, and for each step says whether it's done, still to do, or worth changing, with what to do about it: Tailscale installed and connected, this machine staying signed in to Tailscale, Tailscale running before anyone signs in to Windows, Gatekeeper's address, MCSS found and usable, the servers chosen, each on its own port, whitelists on, and whether a launcher has ever reached this Gatekeeper from outside. The tab's title says how many steps are left. Looking changes nothing.
- Host setup has a place for a Tailscale invite link for players: the reusable link you make in Tailscale's admin page for sharing this one machine. Share pairing then puts it in the pairing link and the pairing file, so a player gets the way in together with the address. Only a link from login.tailscale.com is accepted, and it's kept on this PC.

## 2.0.4 (Gatehouse) - 2026-11-16

- Updates are noticed while a window is open, with no need to reopen anything. The main window looks for a newer Gatehouse every couple of minutes; a server's launcher looks for a newer version of its pack. When one appears, the line under the heading shows up, with a chime.
- In a server's launcher that line says which pack version is ready and how much there is to download, with Update now (the update runs again, as with Restart) and Later.
- Later is remembered for as long as that window is open: you aren't asked again about the same update, only about a newer one.

## 2.0.3 (Gatehouse) - 2026-11-16

- Going from the main window into a server's launcher no longer looks like one window closing and another opening somewhere else. The main window stays up, saying which server it's opening, until that server's window (its update checklist, then its launcher) has appeared in exactly the same place, at least as big. The Gatehouse button comes back the same way.
- The main window now opens as wide as a server's launcher, so little changes on the way in.

## 2.0.2 (Gatehouse) - 2026-11-16

- Gatehouse asks before updating itself. When a newer one is there, the main window says so under its heading: which version, which you have, and how big the download is, with Update Gatehouse and Later. Until now it fetched the newer one without a word. This is apart from a pack's update, which that server's launcher asks about.
- Starting a server straight from its Steam shortcut still updates both: Gatehouse first, then that pack.
- Fixed: on Windows, if the launcher's working copy couldn't be replaced, the shortcut kept opening the old one.

## 2.0.1 (Gatehouse) - 2026-11-16

- The main window has a Restart button beside Quit: it opens the launcher again from the start and looks for a newer Gatehouse.
- In a server's launcher, the Menu button is now called Gatehouse. It still comes back to the main window without starting the game.
- In the list of servers, the button beside a server is now Launch instead of Play: it opens that server's launcher, where Play starts the game.

## 2.0.0 (Gatehouse) - 2026-11-16

- Gatehouse now has its own version and its own notes, here. Until now its changes were listed with each pack's, so a server's Client updates tab was mostly about the launcher. From now on that tab is only about the pack; changes to Gatehouse are on this tab, and changes to Gatekeeper are on a Gatekeeper updates tab beside a Gatekeeper's servers.
- Gatehouse updates itself when you open it directly, with no server lined up: it checks where your packs get their updates, fetches a newer launcher if there is one, and reopens. This tab comes to the front when that has happened.
- Everything before this is in the pack's own notes (CHANGELOG.md in the repository), up to pack 1.16.0: the three-level menu, Add Gatekeeper, Install, plain servers, the Steam config buttons, controller navigation and the rest.

## 2.0.0 (Gatekeeper) - 2026-11-16

- Gatekeeper now has its own version and its own notes, here, and tells launchers which version it is. Gatehouse shows these notes on a Gatekeeper updates tab beside this Gatekeeper's servers.
- The first screen has two more tabs: Gatekeeper updates (these notes) and Gatehouse updates (the launcher's notes, fetched from the repository, with the copy that came with this Gatekeeper as a fallback).
- Everything before this is in the pack's notes (CHANGELOG.md in the repository), up to pack 1.16.0: Server setup, Share pairing, Access Manager, presence, Updates Pulled, per-server state from MCSS, the safe Close MCSS and the rest.
