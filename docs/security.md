# Security and privacy

What Gatehouse and Gatekeeper open, who can reach what, how files are checked, and what leaves your machine.

- [The network: Tailscale only](#the-network-tailscale-only)
- [Who may do what](#who-may-do-what)
- [Gatekeeper's doors](#gatekeepers-doors)
- [Checking what is installed](#checking-what-is-installed)
- [Updates from any Gate System](#updates-from-any-gate-system)
- [The web panel and the status page](#the-web-panel-and-the-status-page)
- [What leaves your machine](#what-leaves-your-machine)
- [What is kept, and where](#what-is-kept-and-where)
- [Known limits](#known-limits)

## The network: Tailscale only

Launchers, Gatekeepers, the web panel, the status page and the pack server talk over
[Tailscale](https://tailscale.com/), a private network between the host's machine and the devices the host lets in.
Gatekeeper listens on two places only: this machine's Tailscale address (port 25570 by default), and 127.0.0.1 for
its own window and the launcher on the same machine. Nothing is opened to the internet, no router settings change,
and nothing here installs, signs in to or configures Tailscale for you.

A host shares **only the one machine** with players (Tailscale's Share, a reusable invite link), not the rest of
their network. A Tailscale address belongs to one device, which is why Gatekeeper can match a request to a launcher
by the address it comes from.

## Who may do what

- **A new launcher can do nothing.** It introduces itself with an id and a secret it made itself (Gatekeeper keeps
  only a hash of the secret) and waits under Players. Pairing links, pairing files and invites grant nothing on
  their own.
- **Permissions** are per launcher or per player, and per server where that makes sense: whitelist, server control,
  MCSS control, machine wake/sleep, and Gatekeeper control. Each is allowed, not allowed, or denied.
- **Gatekeeper control** (managing other launchers from a launcher) is only given at the host's machine, and never
  lets anyone touch a launcher or player that also has it, give it out, or read Gatekeeper's MCSS key.
- **Suspend and blacklist** take everything away; a blacklisted launcher is told nothing about the machine or the
  server. Both still get pack updates.
- **The launcher on the host's own machine** is allowed everything, and only that one: no launcher on another device
  is ever treated as local.
- **Joining a constellation** gives no control of a server or setting, either way. Managing another Gatekeeper is
  off unless that Gatekeeper's owner turns it on, at that machine, and never reaches its settings, updates, web
  accounts or the machine.

## Gatekeeper's doors

A web page in any browser can be made to send requests to 127.0.0.1 or a Tailscale address. Gatekeeper's doors are
for its own window, the launchers and other Gatekeepers, none of which is a browser, so:

- A request carrying a browser's marks (`Origin`, or any `Sec-Fetch-` header) is refused.
- On the 127.0.0.1 door, a request whose `Host` isn't this machine is refused too (a web address made to point here,
  "DNS rebinding").
- Requests are limited in size and nesting.

The web panel and the status page are on their own paths and are built for a browser ([below](#the-web-panel-and-the-status-page)).

## Checking what is installed

| What | Checked against |
| --- | --- |
| The installers' downloads | A published SHA-256 for each file |
| Gatehouse and Gatekeeper updates | The published SHA-256, then the **release signature** (Ed25519). One whose signature doesn't fit is refused, whoever handed it over. One not signed yet (GitHub signs a minute or two after each publish) is taken, unless Gatekeeper's `require_signed` is `true` |
| A pack's `pack.toml` | The release signature. From the owner's own sources an unsigned one is still taken; one whose signature doesn't fit never is; from another Gate System only a signed one is |
| Every file of a pack | The SHA-256 in the pack's file list, whose own fingerprint is in the signed `pack.toml` |
| Mods from Modrinth | The SHA-512 recorded in the pack |
| A server Gatekeeper makes | Mojang's and PaperMC's published checksums (Fabric publishes none) |
| A program file handed between Gatekeepers | Its size, checksum, that it is the program, that it is newer, and its signature |

**How signing works.** The release key's private half is a secret of the GitHub repository and never leaves it. On
every publish, a GitHub workflow signs the program file and each pack's `pack.toml`, and uploads the signatures
(`<sha256>.sig`) to the `signatures` release. The public half is `source/release-key.pub.pem`, built into the program.
Anyone can check a file by hand:

```bash
openssl pkeyutl -verify -pubin -inkey source/release-key.pub.pem -rawin -in oj-host.jar -sigfile <sha256>.sig
```

## Updates from any Gate System

A device can take a pack update from the owner's PC, from GitHub, or from another Gate System (a machine with
Gatehouse or Gatekeeper on it): whichever answers fastest with the newest version. That is safe because of what a
device checks, not because of who hands it over:

- The pack's `pack.toml` must carry the release signature when it comes from another machine, and every file must
  match the fingerprint in the signed list. A file that doesn't match is thrown away and fetched from the next
  source.
- A device never takes an **older** pack, or a **different** pack, from another machine.
- Packs a Gatekeeper builds itself are never signed, so they are never handed out between machines.

What a machine hands out, and to whom:

- **Only what it has checked,** and only once it has all of it. It checks each file again before sending it.
- **Over Tailscale only,** and only to the devices its Gatekeepers know (by their Tailscale addresses).
- **Gatekeeper** hands out always; **Gatehouse** only while its window is open; a **Steam Deck** only while it is
  plugged in. Each has a switch: Gatehouse's **Update options**, Gatekeeper's **Constellation** page.
- On Windows, the first time Gatehouse hands out, Windows may ask whether Java may use the network. That choice is
  yours; nothing here changes firewall settings.

## The web panel and the status page

Both are off until the host turns them on, and both listen only on the machine's Tailscale address.

- **The web panel** is plain `http`, which is safe there because Tailscale encrypts everything between devices.
  Accounts are made only at the machine. Passwords aren't kept, only a PBKDF2 hash (210 000 rounds); five wrong
  passwords lock that account out from that device for five minutes. Sessions are an HttpOnly, SameSite=Strict
  cookie plus a token every change must carry, and end after 2 idle hours (24 at most). Nothing reachable from the
  panel can name a program or a folder on the machine, make accounts, or touch Gatekeeper's access to MCSS. Every
  change is logged with the account. The page runs under a strict Content-Security-Policy.
- **Files offered in chat** are given to the panel only to an account allowed chat. A picture (by its first bytes:
  PNG, JPEG, GIF or WebP) is sent as that picture so the page can show it; anything else only as a download, never
  opened in the page, and every file under its own strict Content-Security-Policy.
- **The status page** is read-only and needs no sign-in: server names, which are up, and who is playing. No
  addresses, folders, keys or buttons.

## What leaves your machine

| What | Where to | When |
| --- | --- | --- |
| Pack files and the program | Fetched from the owner's PC over Tailscale, GitHub, or another Gate System | Updates |
| Mods | Fetched from Modrinth | Updates |
| Modrinth lookups (file hashes only, no sign-in) | Modrinth | When a Gatekeeper builds a pack's mod list |
| Searches for mods (the words and the server's Minecraft version), and the mods you add | Modrinth | When you use Find mods on Modrinth |
| Nothing by default: the AI helper's questions go to Ollama on the same machine (127.0.0.1) | Only if you set another machine's Ollama in Options: the questions and the facts sent with them (servers, who is on; for your own questions, folders) go to that machine | When the AI helper is on |
| A chat room's `@ai` question, this Gatekeeper's server facts and that room's recent messages | The Gatekeeper this one joined (its constellation lead), over the same signed-in link as the rest of the constellation | Only when this Gatekeeper's own AI is off or not ready, its owner left "ask the Gatekeeper it joined" ticked, and someone writes `@ai` in a room; the lead answers only Gatekeepers that joined it |
| Nothing but the request for its files: the built-in AI (llama.cpp's Java binding 4.2.0, 12.2 MB; the model Qwen2.5 1.5B Instruct Q4_K_M, 1.12 GB) | Maven Central (repo1.maven.org) and Hugging Face (huggingface.co) | Once, when the owner presses Get the built-in AI and confirms; never on handheld PCs. Each file is checked against the SHA-256 inside the signed program before it is kept, and again before first use after each start. Questions then stay on the machine |
| Nothing but the request for its files: the media player for chat's videos and sounds (OpenJFX 23.0.2, or 21.0.6 on Java older than 21; about 7.5 MB) | Maven Central (repo1.maven.org) | Once per device, the first time someone presses Play on a video or sound and says yes; each file is checked against the SHA-256 inside the signed program before it is kept and before it is used |
| Server programs | Mojang, PaperMC, Fabric; for other games SteamCMD, from Valve (signing in anonymously) | Only when you press Make the server |
| The Steam account signed in on a launcher's device (its Steam ID and name) | That launcher's own Gatekeepers, over Tailscale | With each report, when Steam is signed in there; used only for games whose list of who may join holds Steam IDs |
| Whether a link works (the link only) | The site it goes to; for the web panel's links, from the Gatekeeper machine | When you press a link |
| Phone notifications: the server's and player's names and a sentence; for chat, who wrote, the room and the start of the message | [ntfy](https://ntfy.sh) (or your own ntfy server); on Android delivered through Google's Firebase | Only when turned on (chat has its own switch). A topic has no password: its long random name is the secret |
| Nothing else | | Neither half reports anything about you anywhere |

Prism Launcher talks to Microsoft (sign-in), Mojang and Fabric as it does for any instance. Gatehouse never sees your
Microsoft password.

## What is kept, and where

- **On a player's device:** the Gatekeepers it knows (`gatekeepers.json`), an id and secret for each, and what each
  last said this device may do; in each game folder, the update's logs and checked copies of the pack
  (`gatehouse-cache`).
- **On the host's machine:** Gatekeeper's folder holds its settings (`host.json`, including its own MCSS API key,
  which is never sent anywhere), launchers (`devices.json`, with hashes of their secrets), players and their
  permissions (`players.json`), web accounts (`users.json`), the activity log (`host.log`), the update log, chat and
  shared files (a day, 500 MB at most). These are ordinary files on that machine: anyone who can sign in to it can
  read them.
- **In this public repository:** never player names, the world seed, keys, home addresses or the internet wake
  address. The server notes record only player-facing facts.

## Known limits

- **Unsigned updates are still accepted** from the owner's own sources by default, because GitHub signs a minute or
  two after each publish. A Gatekeeper with `require_signed` set takes only signed program updates; requiring
  signatures everywhere is planned.
- **The Wake on LAN signal** is a broadcast, so it only reaches the host's PC from its home network.
- **A Tailscale account** that is signed out (key expiry) cuts players off until the host signs in again; turn key
  expiry off for the host's machine.
- **Files on the host's machine** are protected only by that machine's own sign-in.
