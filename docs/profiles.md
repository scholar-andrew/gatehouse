# Server profiles: making the launcher and helper work with more than one server

Status: plan agreed 2026-11-15. **Stage 1 is built** (pack 1.9.10): the profile ships as `extras/profile.json`; the
launcher, the sync scripts and the helper no longer have the server's name, address or folder built in (checked: the
launcher's summary text and picture, the sync's status lines and the helper's readings are identical before and
after). Not yet moved: the installers' and uninstallers' own text, and the tool's own names (below). **Stage 2 is built** (helper only, no pack change): everything the helper does with MCSS is in
`host/src/.../McssHost.java`, behind the `ServerHost` interface; the rest of the helper only uses `host.…` (checked:
the same 41-line scenario against the helper before and after gives identical output, and the real helper's readings
are unchanged). **Stage 3, first part, is built: the helper looks after several servers.** `host.json` `servers` is a list (id, name,
folder, game port, MCSS id); a pack's `server_control.server` names the one it is for, the launcher sends it
(`X-Server`), and the host panel has a Server pick when there is more than one. Server control is per server
(`server` on a launcher or player for the first server, `server:<id>` for others); MCSS control, PC sleep and helper
control are about the machine. Sleeping the machine and closing MCSS wait for every server. Checked: with one server
the 41-line scenario is identical to before; with two stand-in servers each launcher sees and controls only its own.
**Stage 3, second part, is built: the build makes a pack per profile.** `python tools/build_pack.py --profile <id>`
builds `profiles/<id>/` (its `config.json`, `source/`, optional `art/` and `server/`) into `dist/<id>/main` and
`dist/<id>/deck`. Without `--profile` it builds October Journey from where its files always were, and that pack comes
out byte-for-byte the same as before (115 files compared). `profiles/example/` is a made-up second profile to copy;
its pack installs into a fresh folder and the launcher shows its name and adds its server to the game's list.

Still to do in stage 3, waiting for a real second server:
- the installers' and uninstallers' own wording still says October Journey (a second profile installs today with
  `--config=<its config.json address>`, but the messages name the wrong pack);
- publishing a second pack (its `dist/<id>/` isn't built into the repo, and its pack sources would point there);
- the tool's own name. The audience for now is the owner running several servers
for the same group; the design keeps a later move to other hosts cheap (see "Rules").

## The idea

Today everything serves one server, October Journey. The goal is that a server is a **profile**: one bundle of
settings that the launcher, the sync scripts, the installers and the host helper all read, so a second server is a
second profile and not a second copy of the code.

Two names are separated from here on:

- the **tool**: the launcher, the installers and the host helper. They are not about any one server.
- a **server profile**: October Journey is the first.

## What is tied to October Journey today (audit)

Already in one settings file (`installer/config.json`), which is most of a profile:
pack name, instance id and name, Steam shortcut names, pack sources, the server list, `server_control` (panel, helper
and game addresses, the MCSS server id, wake details), Prism versions, art.

Still fixed in code or scripts:

| Where | What |
| --- | --- |
| Launcher (`LaunchPopup.java`) | "October Journey" in the window title, the checklist heading, the update question and every summary headline (10 places) |
| Sync scripts (`pack-sync.ps1`, `.sh`) | "October Journey" in two messages each; the rest is already read from files beside the script |
| Host helper (`HostHelper.java`) | default address `100.91.134.102`, default server folder `...\servers\octoberserver`, one MCSS server, window titles |
| `tools/install_host.ps1`, `tools/serve.ps1` | the PC's Tailscale address, `C:\Minecraft\...` paths, task and shortcut names |
| Installers and uninstallers | the name in every message (31 places in the Windows setup), the Tailscale address in the uninstallers |
| `clients/pack-sources.txt` | the two pack sources (a copy of what the profile already says) |
| Build (`tools/build_pack.py`) | one pack built from `source/`, into `dist/main` and `dist/deck` |
| The settings mod (`mods-src/octoberjourney`) | its name and id |

Named after the project but not about the server (the tool's own names): the installer file names, the Java package
`dev.octoberjourney`, the install folder `C:\Minecraft\GatehouseHost`, the scheduled task and Start menu names.
These change only when the tool gets its own name.

## Stages

1. **One profile, no change in behaviour.**
   - `installer/config.json` becomes the profile (same content, plus the display name the programs use).
   - The build ships it to devices as `extras/profile.json`; the launcher and sync scripts take the server's name and
     details from it instead of from text in the code.
   - The helper's `host.json` gets a `servers` list with one entry (folder, MCSS id, game port); its fixed defaults go.
   - Check: the launcher's pictures and the sync's status output are identical before and after.
2. **MCSS behind one seam in the helper.** Start, stop, status, console and "is the host program running" go through
   one small internal layer with MCSS as its only implementation. No change in behaviour.
3. **More than one.**
   - Launcher and installers: several profiles, one Prism instance each.
   - Helper: several servers on one machine; server control per server; MCSS control, PC sleep and helper control
     stay per machine (MCSS is one program for all of them).
   - Build and publishing: one pack per profile.

## Rules (so a later move to other hosts stays cheap)

1. Nothing about a server lives outside its profile: no addresses, names or ids in code.
2. MCSS is only ever reached through the helper's one seam (stage 2).
3. What is safe only because everything is on a private Tailscale network is kept in one place and listed here:
   - the helper speaks plain HTTP;
   - a launcher's identity is a secret it made itself, sent on each request;
   - the pack server on the PC is plain HTTP, with GitHub as the fallback.
4. Windows-only parts of the helper (sleep, closing MCSS, scheduled tasks) stay separate from the rest.

## The tool's name

The tool is **Gatehouse** (decided 2026-11-15). Done: the host helper and panel (titles, the Start menu entry, the
scheduled task, the folder `C:\Minecraft\GatehouseHost`), the launcher's title bar, and the settings mod's name in
the game's mod menu. The installers and uninstallers are renamed too (`GatehouseSetup.exe`,
`GatehouseUninstall.exe`, `gatehouse-setup.sh`, `gatehouse-uninstall.sh`; setup's messages take the pack's name from the
profile). What setup saves on a device keeps its old names so existing installs keep working: the tools folder
(`OctoberJourney`, `~/.local/share/october-journey`), the saved copies (`OctoberJourneySetup.exe`,
`OctoberJourneyUninstall.exe`), the Settings > Apps key and the app-menu files. The uninstallers still know only
October Journey. Still under the old name: the repository (first a pack update that points devices at the new address, then
the rename once every device has launched). Names nobody sees (the Java package `dev.octoberjourney`, the mod's id
`octoberjourney`, `oj-host.jar`) stay as they are.
