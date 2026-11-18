# For developers

How the program and the packs are built, tested, published and signed, and what an update must never break. The
deeper references are [How the pack works](how-it-works.md) (the pack, its update on every launch, the installers)
and [Gatekeeper internals](gatekeeper-internals.md) (its calls, files and settings). Adding another server:
[Server profiles](profiles.md).

## The pieces

| Where | What |
| --- | --- |
| `mods-src/program` | Where the program starts (`dev.gatehouse.Main`): decides which half was asked for. Also `Signed` (release signatures). |
| `mods-src/launch-popup` | Gatehouse, the Play half: the main window (`Home`), a server's page (`LaunchPopup`), the controller (`Nav`, `Pad`), installing packs (`PackInstall`), self-update (`Notes`), fetching pack updates (`PackRelay`) and handing them out (`PackShare`). |
| `host/src` | Gatekeeper, the Host half (`HostHelper` and the classes beside it). `host/web` is the web panel. |
| `mods-src/look` | The look both halves share: `SteamLook`, `GameShell` (sections, menu, bottom strip), `GameCard`, `Wizard` (the setup guides). |
| `mods-src/<mod>` | The pack's own Fabric mods (see [mods-src/README.md](../mods-src/README.md)). |
| `source/` | October Journey's pack definition (`mods.json`), notes (`changelog.json`, `gatehouse.json`, `gatekeeper.json`), files shipped as they are, and the release key's public half. |
| `clients/` | The update script every device runs before each launch (`pack-sync.ps1` / `.sh`) and what setup installs with it. |
| `installer/` | The installers: Gatehouse and Gatekeeper by themselves, October Journey's setup, art and the Deck controller layout. |
| `dist/` | The built packs and the published program. Generated: commit it. |
| `tools/` | Building, testing, serving and signing. |

Plain Java 17 for the program (a small part for Java 22, the controller on Windows), no build system: `javac` and `jar`.
Python 3 for the pack build.

## Building

| Command | What it makes |
| --- | --- |
| `powershell -ExecutionPolicy Bypass -File tools\build_program.ps1` | The one program, both halves, into `source/files/launch-popup.jar` and `host/oj-host.jar` (the same bytes). It puts `source/gatehouse.json`, `source/gatekeeper.json`, the install scripts, the wizard pictures and what every pack carries of the program inside it. |
| `python tools/build_pack.py` | `dist/` (each pack's `main` and `deck` variants, `dist/gatekeeper/`), `CHANGELOG.md`, `docs/mods.md` and `docs/version-history.md`. It refuses a program not recorded as tested in `tools/tests/passed.txt`, a pack version without a changelog entry, and server changes without a note. |
| `powershell -File tools\build_mod.ps1 <mod>` | One of the pack's own mods into `source/files/mods/`. |
| `tools/build_wrapper.ps1` | The launch wrapper. |

**Building the pack publishes.** `dist/` is served from the owner's PC as soon as it is rebuilt, and from GitHub once
pushed, and running Gatekeepers and launchers hold themselves to what is there. Build only what is ready to go out.

## Tests

```powershell
powershell -ExecutionPolicy Bypass -File tools\run_tests.ps1
```

Each test runs against stand-ins in a folder of its own: nothing on the machine is touched and nothing real is
started (a throwaway signing key is made and wiped). They cover signatures, notes and icons, a Gatekeeper made to
freeze (its watchdog must restart it), a busy Gatekeeper (many calls at once), Gatekeeper's doors (requests from web
pages refused, oversized and deeply nested requests harmless), and the update check below. On success the program's
checksum goes in `tools/tests/passed.txt`, which the pack build requires. `-Quick` leaves out the update check.

`tools\update_check.ps1` takes old versions out of this repository's history, starts each in a stand-in folder, and
passes when each has updated itself to the build about to be published (`-All` for every version).

Tests that need a window take pictures instead: `OJ_PANEL_PNG` (Gatekeeper), `OJ_POPUP_PNG` (a server's page) and the
others named in [How the pack works](how-it-works.md). Tests never point at the owner's real game, launcher or
servers; `OJ_HOME_DRY`, `OJ_HOST_DIR`, `OJ_PRISM_ROOT` and `OJ_KEEPER_DIR` keep them in their own folders.

## Publishing

1. Raise the version in `source/gatehouse.json` and `source/gatekeeper.json` (one version for both halves) with
   notes for players; raise the pack version in `source/mods.json` with an entry at the top of
   `source/changelog.json`. Decide whether `needs` in `source/gatekeeper.json` must rise ([below](#what-an-update-must-never-break)).
2. `tools\build_program.ps1`, then `tools\run_tests.ps1`.
3. `python tools/build_pack.py`, commit and push.
4. GitHub's **Sign the published program** workflow signs the program and each `pack.toml` a minute or two later.

Four kinds of notes, four places: the pack (`source/changelog.json`), the server (`server/changelog.json`, through
`tools/server_snapshot.py`), Gatehouse (`source/gatehouse.json`) and Gatekeeper (`source/gatekeeper.json`). The
installers are released separately, only when their own code changes ([How the pack works](how-it-works.md#releasing-the-installers-and-uninstallers)).

## Signing

The release key is Ed25519. `tools/setup_signing_key.ps1`, run once by the owner, made it, stored the private half
as the repository secret `RELEASE_SIGNING_KEY`, and added `source/release-key.pub.pem` and the workflow
(`.github/workflows/sign-release.yml`, kept as `tools/sign-release.workflow.yml`). On every push that changes
`dist/gatekeeper/gatekeeper.json` or a `dist/*/pack.toml`, the workflow signs those files and uploads
`<sha256 of the file>.sig` to the `signatures` release.

A `pack.toml` holds the fingerprint of the pack's file list, and the list holds every file's, so its one signature
covers the whole pack. `dev.gatehouse.Signed.check(bytes, sha, places, keep)` answers `signed`, `unsigned` or `bad`:
it looks for the signature in a local folder, then the places given (Gatekeepers, other Gate Systems), then the
`signatures` release.

## Fetching and handing out pack updates (R40)

- **The relay** (`PackRelay`, started by the update script as `java -jar launch-popup.jar --pack-relay <variant>
  <game folder> <sources...>` when the program is 3.32.0 or newer). packwiz-installer is pointed at it
  (`http://127.0.0.1:<port>/pack.toml`). It asks every source for `pack.toml` at once (the owner's sources from
  `pack-sources.txt`, and the Gate Systems in `pack-peers.txt`, `<base>|<signatures>` a line, `{variant}` standing for
  the variant), takes the newest version that answers well (between equals, the fastest), and checks the signature:
  from the owner's sources unsigned is allowed, from a peer only signed, and never an older version or another pack's
  name than the one this device has. Each file is checked against the signed list, kept in `gatehouse-cache/<variant>`,
  and fetched from the next source when it doesn't match. Files outside the list come only from the owner's sources.
  It writes `pack-relay-port.txt`, `pack-relay-used.txt` and `pack-relay.log`, and ends after five idle minutes. If it
  can't be used, the script falls back to the sources one by one.
- **Handing out** (`PackShare`). Serves `/pack/<variant>/<file>` and `/pack/sigs/<sha256>.sig` on the machine's
  Tailscale address, to loopback and the addresses its Gatekeepers give, from the checked copies: only a signed pack,
  only once all of it is there (what is missing is copied from the installed game when its fingerprint fits, or
  fetched from the owner's sources), each file checked again before it is sent. Gatekeeper runs it from
  `Handout.start` (unless `hand_out` is `false` in `host.json`); Gatehouse from its five-second report to each
  Gatekeeper, unless `hand_out` is `false` in `gatehouse-options.json`, the machine's own Gatekeeper is doing it, or a
  Linux machine is on its battery.
- **Who hands out.** Each open launcher's `POST /v1/update/state` carries `share` (`port`, `packs`); the answer
  carries `peers` (the others, newest versions only), `allow` (the devices' Tailscale addresses), `you` and `local`.
  The launcher writes `pack-peers.txt` into each pack it updates (not a Gatekeeper-built one). A Gatekeeper that
  joined another passes on what it hears there.

## What an update must never break

Any version ever published has to reach the newest one in a single step, with the updater it has. So what old
versions rely on is fixed, and only ever added to:

- **Where they look:** `dist/gatekeeper/gatekeeper.json` and `oj-host.jar` on each pack server and on GitHub's `main`,
  and `<source>/main|deck/update-sizes.json` with `extras/launch-popup.jar`. Those paths and the repository's address
  stay.
- **What they read:** `gatekeeper.json` `version`, `sha256`, `size`, `gatehouse`; `update-sizes.json` `gatehouse` and
  `files["extras/launch-popup.jar"]` `hash` and `size`. Fields are added, never renamed or removed.
- **What they do:** check the checksum, check the jar holds `dev/octoberjourney/host/HostHelper.class`,
  `META-INF/MANIFEST.MF` and `gatekeeper-notes.json`, swap it in with a script the old version wrote, start it with
  `-jar oj-host.jar --serve`, and expect `GET /admin/update` to answer within 30 seconds. So the new program keeps
  those names, starts from that command line with the old settings files, needs no Java newer than 17, and answers
  quickly.
- **Settings and records** are read as any older version wrote them.
- **`needs`** in `source/gatekeeper.json` is the oldest version that still works with the one published. It holds an
  older client back from playing; it never stops one updating. Raise it only when versions would misunderstand each
  other, or for a fix that can't wait.

Run `tools\update_check.ps1` before publishing anything that touches how updates are published, fetched or applied.
If a version's own updater ever fails, there are other ways in: a launcher gets the program with a pack's update and
from its Gatekeepers, a Gatekeeper gets it from the launcher on its machine, and the install scripts replace it while
keeping the settings.

## Version history

[docs/version-history.md](version-history.md) lists every version of the program with its notes, and
[CHANGELOG.md](../CHANGELOG.md) every version of the October Journey pack. Both are written by the pack build.
