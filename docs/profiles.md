# Server profiles: making the launcher and helper work with more than one server

Status: plan agreed 2026-11-15. **Stage 1 is built** (pack 1.9.10): the profile ships as `extras/profile.json`; the
launcher, the sync scripts and the helper no longer have the server's name, address or folder built in (checked: the
launcher's summary text and picture, the sync's status lines and the helper's readings are identical before and
after). Not yet moved: the installers' and uninstallers' own text, and the tool's own names (below). **Stage 2 is built** (helper only, no pack change): everything the helper does with MCSS is in
`host/src/.../McssHost.java`, behind the `ServerHost` interface; the rest of the helper only uses `host.…` (checked:
the same 41-line scenario against the helper before and after gives identical output, and the real helper's readings
are unchanged). Stage 3 is not built. The audience for now is the owner running several servers
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
`dev.octoberjourney`, the install folder `C:\Minecraft\OctoberJourneyHost`, the scheduled task and Start menu names.
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
   - Helper: several servers on one machine; permissions (server control, MCSS control) per server; PC sleep and
     helper control stay per machine.
   - Build and publishing: one pack per profile.

## Rules (so a later move to other hosts stays cheap)

1. Nothing about a server lives outside its profile: no addresses, names or ids in code.
2. MCSS is only ever reached through the helper's one seam (stage 2).
3. What is safe only because everything is on a private Tailscale network is kept in one place and listed here:
   - the helper speaks plain HTTP;
   - a launcher's identity is a secret it made itself, sent on each request;
   - the pack server on the PC is plain HTTP, with GitHub as the fallback.
4. Windows-only parts of the helper (sleep, closing MCSS, scheduled tasks) stay separate from the rest.

## Open decision

The tool needs its own name before stage 3, since "October Journey" will then be just one of the servers.
