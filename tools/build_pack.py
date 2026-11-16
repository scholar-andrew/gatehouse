"""
Builds the October Journey client pack in packwiz format, in two variants:

  dist/main/  every device (PC, laptop)
  dist/deck/  the same plus the Steam Deck-only mods

Source of truth: source/mods.json (one entry per mod, "deck": true for Deck-only) and source/files/ (files copied as-is:
configs, and jars that are not on Modrinth). Modrinth mods are referenced by URL and SHA-512, so their jars are never
stored here. Also writes docs/mods.md (the mod list). Run: python tools/build_pack.py
"""
import hashlib, json, os, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Which server profile to build (docs/profiles.md). Without --profile it is the first one, October Journey, whose files
# are where they always were: source/, installer/config.json, server/, built into dist/main and dist/deck.
# --profile <id> builds another from profiles/<id>/: config.json (the profile), source/ (mods.json, changelog.json,
# files/), optionally art/ (its own Steam art) and server/ (what its server runs), into dist/<id>/main and
# dist/<id>/deck, with its mod list and patch notes written beside its config. The launcher, the sync scripts and the
# shared art are the tool's, the same for every profile.
PROFILE = next((sys.argv[i + 1] for i, a in enumerate(sys.argv[:-1]) if a == "--profile"), None)
PDIR = os.path.join(ROOT, "profiles", PROFILE) if PROFILE else ROOT
if PROFILE and not os.path.isfile(os.path.join(PDIR, "config.json")):
    sys.exit(f"no such profile: profiles/{PROFILE}/config.json is missing")
SRC = os.path.join(PDIR, "source")
CONFIG = os.path.join(PDIR, "config.json") if PROFILE else os.path.join(ROOT, "installer", "config.json")
SERVER = os.path.join(PDIR, "server")
TOOL_FILES = os.path.join(ROOT, "source", "files")   # (where the built launcher is kept)
# OJ_DIST: build somewhere else (for testing) instead of dist/, which devices download straight away.
DIST = os.environ.get("OJ_DIST") or os.path.join(ROOT, "dist")
if PROFILE:
    DIST = os.path.join(DIST, PROFILE)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def toml_str(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def write(path: str, text: str) -> bytes:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = text.encode("utf-8")
    with open(path, "wb") as f:
        f.write(data)
    return data


def build(variant: str, meta: dict, mods: list) -> None:
    out = os.path.join(DIST, variant)
    shutil.rmtree(out, ignore_errors=True)
    entries = []  # (path relative to the pack, sha256, metafile)
    for m in mods:
        if m.get("deck") and variant != "deck":
            continue
        if "url" in m:
            slug = m["slug"]
            text = (
                f"name = {toml_str(m['title'])}\n"
                f"filename = {toml_str(m['filename'])}\n"
                f"side = \"client\"\n\n"
                f"[download]\n"
                f"url = {toml_str(m['url'])}\n"
                f"hash-format = \"sha512\"\n"
                f"hash = {toml_str(m['sha512'])}\n\n"
                f"[update.modrinth]\n"
                f"mod-id = {toml_str(m['project'])}\n"
                f"version = {toml_str(m['version'])}\n"
            )
            rel = f"mods/{slug}.pw.toml"
            entries.append((rel, sha256(write(os.path.join(out, rel), text)), True))
        else:
            # A jar that is not on Modrinth: shipped in the pack itself.
            data = open(os.path.join(SRC, "files", "mods", m["filename"]), "rb").read()
            if hashlib.sha512(data).hexdigest() != m["sha512"]:
                sys.exit(f"{m['filename']} does not match its recorded SHA-512")
            rel = f"mods/{m['filename']}"
            os.makedirs(os.path.join(out, "mods"), exist_ok=True)
            with open(os.path.join(out, rel), "wb") as f:
                f.write(data)
            entries.append((rel, sha256(data), False))
    # Configs and other files, copied as they are.
    cfg = os.path.join(SRC, "files", "config")
    for base, _, names in os.walk(cfg):
        for n in sorted(names):
            p = os.path.join(base, n)
            rel = "config/" + os.path.relpath(p, cfg).replace(os.sep, "/")
            data = open(p, "rb").read()
            os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
            with open(os.path.join(out, rel), "wb") as f:
                f.write(data)
            entries.append((rel, sha256(data), False))
    # Default settings (source/files/config-defaults/): written only where the file doesn't exist yet ("preserve"), so
    # new installs get them and a player's own changes are never overwritten.
    defaults = os.path.join(SRC, "files", "config-defaults")
    for base, _, names in os.walk(defaults):
        for n in sorted(names):
            p = os.path.join(base, n)
            rel = "config/" + os.path.relpath(p, defaults).replace(os.sep, "/")
            data = open(p, "rb").read()
            os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
            with open(os.path.join(out, rel), "wb") as f:
                f.write(data)
            entries.append((rel, sha256(data), "preserve"))
    # Steam extras, applied by the sync after the mods: the library art and the Steam Deck controller layout.
    # Prism Launcher's logo art goes to the shortcut that opens Prism; the pack's own to the one that starts the game.
    arts = ("cover.png", "hero.png", "logo.png", "icon.png", "wide.png")
    for src_rel, rel in [("installer/art/" + n, "steam/art/" + n) for n in arts] + [("installer/art-prism/" + n, "steam/art-prism/" + n) for n in arts] + [("installer/steam/controller_neptune.vdf", "steam/controller_neptune.vdf")]:
        # (a profile's own art, profiles/<id>/art/, when it has it; otherwise the shared files)
        own = os.path.join(PDIR, "art", os.path.basename(src_rel)) if PROFILE and src_rel.startswith("installer/art/") else None
        data = open(own if own and os.path.isfile(own) else os.path.join(ROOT, src_rel), "rb").read()
        os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
        with open(os.path.join(out, rel), "wb") as f:
            f.write(data)
        entries.append((rel, sha256(data), False))
    # The server list, from installer/config.json: the sync keeps these in Minecraft's multiplayer list.
    cfg_servers = json.load(open(CONFIG, encoding="utf-8"))["servers"]
    rel = "extras/servers.json"
    data = write(os.path.join(out, rel), json.dumps([{"name": s["name"], "address": s["address"]} for s in cfg_servers], indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # Each mod's entry in the pack index and its name, so the launch pop-up can name mods added, removed and updated.
    rel = "extras/mod-names.json"
    data = write(os.path.join(out, rel), json.dumps({(f"mods/{m['slug']}.pw.toml" if "url" in m else f"mods/{m['filename']}"): m["title"]
                                                      for m in mods if not (m.get("deck") and variant != "deck")}, indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # The profile, as far as a device needs it (installer/config.json is the whole of it; see docs/profiles.md): the
    # name the launcher and the sync scripts show, the instance it belongs to, and where the pack is fetched from (a
    # device's pack-sources.txt follows this, so the pack's address can move without visiting each device).
    prof = json.load(open(CONFIG, encoding="utf-8"))
    rel = "extras/profile.json"
    data = write(os.path.join(out, rel), json.dumps({"name": prof["pack_name"], "instance_id": prof["instance_id"], "pack_sources": prof.get("pack_sources", [])}, indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # The Server tab's controls (installer/config.json "server_control"): panel, wake, status. No secrets in it.
    sc = json.load(open(CONFIG, encoding="utf-8")).get("server_control", {})
    rel = "extras/server-control.json"
    data = write(os.path.join(out, rel), json.dumps({k: v for k, v in sc.items() if not k.startswith("_")}, indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # Mods players can switch on and off (Mod Menu > October Journey), with the pack's default for each. The sync
    # scripts disable the ones that are off after each update; the October Journey mod shows the switches.
    #   key    what the player's choice is saved under (the Modrinth slug, or the mod id for a jar in the pack)
    #   file   the mod's entry in the pack index (packwiz.json on the device maps it to the installed jar)
    #   needs  keys of switchable mods this one can't run without: it's off whenever one of them is off
    toggles = []
    for m in mods:
        if "toggle" not in m or (m.get("deck") and variant != "deck"):
            continue
        tg = m["toggle"]
        toggles.append({"key": m.get("slug") or tg["modid"],
                        "file": f"mods/{m['slug']}.pw.toml" if "url" in m else f"mods/{m['filename']}",
                        "modid": tg["modid"], "name": tg.get("name", m["title"]), "description": tg["description"],
                        "default": tg.get("default", True), "needs": tg.get("needs", [])})
    rel = "extras/mod-toggles.json"
    data = write(os.path.join(out, rel), json.dumps(toggles, indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # What the server runs and the notes about its changes (server/, from tools/server_snapshot.py), for the pop-up's
    # Server tab.
    state, slog = load_server()
    rel = "extras/server-state.json"
    data = write(os.path.join(out, rel), json.dumps(state, indent=1, sort_keys=True) + "\n")
    entries.append((rel, sha256(data), False))
    rel = "extras/server-changelog.txt"
    data = write(os.path.join(out, rel), "".join(f"== {e['id']} | {e['date']}\n" + "".join(f"- {n}\n" for n in e["notes"]) for e in slog))
    entries.append((rel, sha256(data), False))
    # Patch notes and the pop-up that shows them before the game starts (run by the sync scripts).
    rel = "extras/changelog.txt"
    data = write(os.path.join(out, rel), changelog_text(load_changelog(meta)))
    entries.append((rel, sha256(data), False))
    data = open(os.path.join(TOOL_FILES, "launch-popup.jar"), "rb").read()
    rel = "extras/launch-popup.jar"
    with open(os.path.join(out, rel), "wb") as f:
        f.write(data)
    entries.append((rel, sha256(data), False))
    # The sync scripts themselves: each run swaps in the pack's copy for the next launch.
    # (and the launch wrappers, which each run copies beside itself: see clients/launch-wrapper.sh)
    for n in ("pack-sync.ps1", "pack-sync.sh", "launch-wrapper.sh", "launch-wrapper.exe"):
        data = open(os.path.join(ROOT, "clients", n), "rb").read()
        rel = "extras/" + n
        os.makedirs(os.path.join(out, "extras"), exist_ok=True)
        with open(os.path.join(out, rel), "wb") as f:
            f.write(data)
        entries.append((rel, sha256(data), False))
    index = 'hash-format = "sha256"\n'
    for rel, h, metafile in sorted(entries):
        index += f"\n[[files]]\nfile = {toml_str(rel)}\nhash = {toml_str(h)}\n" + (
            "preserve = true\n" if metafile == "preserve" else "metafile = true\n" if metafile else "")
    index_data = write(os.path.join(out, "index.toml"), index)
    # update-sizes.json, beside the pack (not part of it): what each file in the index costs to download, so the
    # launcher can say how big an update is, and ask, before anything is fetched. A Modrinth mod counts as its jar
    # (the size recorded in mods.json by tools/mod_sizes.py); everything else is the file itself.
    mod_size = {f"mods/{m['slug']}.pw.toml": m.get("size") for m in mods if "url" in m}
    files = {}
    for rel, h, metafile in sorted(entries):
        if metafile is True and not mod_size.get(rel):
            sys.exit(f"{rel}: no download size recorded in source/mods.json (run tools/mod_sizes.py)")
        files[rel] = {"hash": h, "size": mod_size[rel] if metafile is True else os.path.getsize(os.path.join(out, rel))}
        if metafile == "preserve":
            files[rel]["preserve"] = True
    write(os.path.join(out, "update-sizes.json"), json.dumps({"version": meta["version"], "files": files}, indent=1) + "\n")
    name = meta["name"] + (" (Steam Deck)" if variant == "deck" else "")
    pack = (
        f"name = {toml_str(name)}\n"
        f"author = {toml_str(meta['author'])}\n"
        f"version = {toml_str(meta['version'])}\n"
        f"pack-format = \"packwiz:1.1.0\"\n\n"
        f"[index]\nfile = \"index.toml\"\nhash-format = \"sha256\"\nhash = {toml_str(sha256(index_data))}\n\n"
        f"[versions]\nminecraft = {toml_str(meta['minecraft'])}\nfabric = {toml_str(meta['fabric'])}\n"
    )
    write(os.path.join(out, "pack.toml"), pack)
    print(f"{variant}: {sum(1 for e in entries if e[0].startswith('mods/'))} mods, {sum(1 for e in entries if e[0].startswith('config/'))} config file(s), {sum(1 for e in entries if e[0].startswith('steam/'))} Steam file(s)")


OWN_MODS = {  # jars shipped in the pack itself, with where their source lives in this repo
    "chatlift": ("mods-src/chatlift", "Steam keyboard handling: lifts chat above Steam's keyboard, keeps Controlify's own keyboard for non-Steam controllers, lets Steam's keyboard type (Controlify mixed input). Made for this pack."),
    "handofffix": ("mods-src/handofffix", "Fixes Controlify losing the Steam Deck's controls after another controller is unplugged. Made for this pack."),
    "octoberjourney": ("mods-src/octoberjourney", "Gatehouse Settings: the pack's settings in Mod Menu (switch the pack's mods and the launcher's windows on and off). Part of Gatehouse, the launcher these packs use."),
    "RoughlyEnoughItems": ("mods-src/rei-brewingfix", "Roughly Enough Items 26.3.824 with one class patched so brewing recipes load on the server. Also installed on the server."),
}


def load_server() -> tuple:
    """server/state.json and server/changelog.json. The newest note must describe the current state (its "state"
    checksum), so a server change can't ship without a note for players."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("server_snapshot", os.path.join(ROOT, "tools", "server_snapshot.py"))
    snap = importlib.util.module_from_spec(spec); spec.loader.exec_module(snap)
    if not os.path.isfile(os.path.join(SERVER, "state.json")):
        return {}, []   # (a profile with no record of its server yet: the launcher's Server updates tab stays empty)
    state = json.load(open(os.path.join(SERVER, "state.json"), encoding="utf-8"))
    log = json.load(open(os.path.join(SERVER, "changelog.json"), encoding="utf-8"))
    if not log or log[0].get("state") != snap.state_hash(state):
        sys.exit("server/state.json changed without a note for players: run tools/server_snapshot.py --note \"...\"")
    for w in snap.client_mismatches(state):
        print("WARNING, client and server differ: " + w)
    return state, log


def load_changelog(meta: dict) -> list:
    """source/changelog.json, newest first. Its first entry must be the pack's version, so every release has notes."""
    log = json.load(open(os.path.join(SRC, "changelog.json"), encoding="utf-8"))
    if not log or log[0]["version"] != meta["version"]:
        sys.exit(f"source/changelog.json needs an entry for {meta['version']} at the top (the pack version in source/mods.json)")
    return log


def changelog_text(log: list) -> str:
    """The pop-up's copy: "== version | date" then "- note" lines (simple enough to read without a JSON library)."""
    out = []
    for v in log:
        out.append(f"== {v['version']} | {v.get('date', '')}")
        out += [f"- {n}" for n in v["notes"]]
    return "\n".join(out) + "\n"


def write_changelog_md(log: list) -> None:
    lines = ["# Changes", "", "What changed in each version of the pack, newest first. Generated by `tools/build_pack.py` from",
             "`source/changelog.json`; players see the same notes in the pop-up before the game starts after an update.", ""]
    for v in log:
        lines += [f"## {v['version']}" + (f" ({v['date']})" if v.get("date") else ""), ""] + [f"- {n}" for n in v["notes"]] + [""]
    with open(os.path.join(PDIR, "CHANGELOG.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def write_mod_list(meta: dict, mods: list, server: dict) -> None:
    """docs/mods.md: every mod in the pack, regenerated on each build so it always matches source/mods.json."""
    lines = [
        "# Mods in the pack",
        "",
        f"Generated by `tools/build_pack.py` from `source/mods.json` (pack {meta['version']}, Minecraft {meta['minecraft']}, "
        f"Fabric Loader {meta['fabric']}). Do not edit by hand.",
        "",
        "Every device gets every mod. Mods from Modrinth are downloaded from Modrinth's CDN and checked against the SHA-512 "
        "recorded here; the others are stored in this repo (`source/files/mods/`).",
        "",
        "| Mod | File | From | Switch (Mod Menu > Gatehouse Settings) |",
        "| --- | --- | --- | --- |",
    ]
    own = []
    def switch(m):
        if "toggle" not in m:
            return "always on (needed by other mods, or the pack itself)"
        tg = m["toggle"]
        s = "on by default" if tg.get("default", True) else "**off by default**"
        return s + (f"; off while {', '.join(tg['needs'])} is off" if tg.get("needs") else "")
    for m in sorted(mods, key=lambda m: m["title"].lower()):
        if "url" in m:
            lines.append(f"| {m['title']} | `{m['filename']}` | [Modrinth](https://modrinth.com/mod/{m['slug']}) | {switch(m)} |")
        else:
            key = next((k for k in OWN_MODS if m["filename"].startswith(k)), None)
            where = f"this repo, source in [`{OWN_MODS[key][0]}`](../{OWN_MODS[key][0]})" if key else "this repo"
            lines.append(f"| {m['title']} | `{m['filename']}` | {where} | {switch(m)} |")
            if key:
                own.append(f"- **{m['filename']}**: {OWN_MODS[key][1]}")
    if own:
        lines += ["", "## Mods made or patched for this pack", ""] + own
    if server.get("mods"):
        lines += ["", "## On the server", "",
                  "From `server/state.json` (made by `tools/server_snapshot.py` from the server's files). The client mods above that match "
                  "these must stay on the same versions; the build warns when they don't. More in [docs/server.md](server.md).", ""]
        lines += [f"- `{n}`" for n in server["mods"]]
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    with open(os.path.join(PDIR, "MODS.md") if PROFILE else os.path.join(ROOT, "docs", "mods.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main() -> None:
    src = json.load(open(os.path.join(SRC, "mods.json"), encoding="utf-8"))
    for variant in ("main", "deck"):
        build(variant, src["pack"], src["mods"])
    # A record of what the server runs, kept beside the pack (a reference list; nothing checks it automatically).
    with open(os.path.join(DIST, "server-mods.json"), "w", encoding="utf-8") as f:
        json.dump({"mods": sorted(m["file"] for m in load_server()[0].get("mods", {}).values())}, f, indent=1)
    os.makedirs(DIST, exist_ok=True)
    if not os.environ.get("OJ_DIST"):
        write_mod_list(src["pack"], src["mods"], {"mods": sorted(m["file"] for m in load_server()[0].get("mods", {}).values())})
        write_changelog_md(load_changelog(src["pack"]))


if __name__ == "__main__":
    main()
