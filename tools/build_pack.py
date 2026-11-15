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
SRC = os.path.join(ROOT, "source")
# OJ_DIST: build somewhere else (for testing) instead of dist/, which devices download straight away.
DIST = os.environ.get("OJ_DIST") or os.path.join(ROOT, "dist")


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
    # Steam extras, applied by the sync after the mods: the library art and the Steam Deck controller layout.
    for src_rel, rel in [("installer/art/" + n, "steam/art/" + n) for n in ("cover.png", "hero.png", "logo.png", "icon.png", "wide.png")] + [("installer/steam/controller_neptune.vdf", "steam/controller_neptune.vdf")]:
        data = open(os.path.join(ROOT, src_rel), "rb").read()
        os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
        with open(os.path.join(out, rel), "wb") as f:
            f.write(data)
        entries.append((rel, sha256(data), False))
    # The server list, from installer/config.json: the sync keeps these in Minecraft's multiplayer list.
    cfg_servers = json.load(open(os.path.join(ROOT, "installer", "config.json"), encoding="utf-8"))["servers"]
    rel = "extras/servers.json"
    data = write(os.path.join(out, rel), json.dumps([{"name": s["name"], "address": s["address"]} for s in cfg_servers], indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # Mods players can switch on and off (Mod Menu > October Journey), with the pack's default for each. The sync
    # scripts disable the ones that are off after each update; the October Journey mod shows the switches.
    rel = "extras/mod-toggles.json"
    data = write(os.path.join(out, rel), json.dumps([
        {"slug": m["slug"], "modid": m["toggle"]["modid"], "name": m["title"], "description": m["toggle"]["description"],
         "default": m["toggle"]["default"]}
        for m in mods if "toggle" in m and not (m.get("deck") and variant != "deck")], indent=1) + "\n")
    entries.append((rel, sha256(data), False))
    # The sync scripts themselves: each run swaps in the pack's copy for the next launch.
    for n in ("pack-sync.ps1", "pack-sync.sh"):
        data = open(os.path.join(ROOT, "clients", n), "rb").read()
        rel = "extras/" + n
        os.makedirs(os.path.join(out, "extras"), exist_ok=True)
        with open(os.path.join(out, rel), "wb") as f:
            f.write(data)
        entries.append((rel, sha256(data), False))
    index = 'hash-format = "sha256"\n'
    for rel, h, metafile in sorted(entries):
        index += f"\n[[files]]\nfile = {toml_str(rel)}\nhash = {toml_str(h)}\n" + ("metafile = true\n" if metafile else "")
    index_data = write(os.path.join(out, "index.toml"), index)
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
    "octoberjourney": ("mods-src/octoberjourney", "The pack's own settings in Mod Menu: switch some of the pack's mods on and off. Made for this pack."),
    "RoughlyEnoughItems": ("mods-src/rei-brewingfix", "Roughly Enough Items 26.3.824 with one class patched so brewing recipes load on the server. Also installed on the server."),
}


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
        "| Mod | File | From |",
        "| --- | --- | --- |",
    ]
    own = []
    for m in sorted(mods, key=lambda m: m["title"].lower()):
        if "url" in m:
            lines.append(f"| {m['title']} | `{m['filename']}` | [Modrinth](https://modrinth.com/mod/{m['slug']}) |")
        else:
            key = next((k for k in OWN_MODS if m["filename"].startswith(k)), None)
            where = f"this repo, source in [`{OWN_MODS[key][0]}`](../{OWN_MODS[key][0]})" if key else "this repo"
            lines.append(f"| {m['title']} | `{m['filename']}` | {where} |")
            if key:
                own.append(f"- **{m['filename']}**: {OWN_MODS[key][1]}")
    if own:
        lines += ["", "## Mods made or patched for this pack", ""] + own
    if server.get("mods"):
        lines += ["", "## On the server", "",
                  "The server (`dist/server-mods.json`) runs these; the client mods above that match them must stay on the same versions.", ""]
        lines += [f"- `{n}`" for n in server["mods"]]
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    with open(os.path.join(ROOT, "docs", "mods.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main() -> None:
    src = json.load(open(os.path.join(SRC, "mods.json"), encoding="utf-8"))
    for variant in ("main", "deck"):
        build(variant, src["pack"], src["mods"])
    # A record of what the server runs, kept beside the pack (a reference list; nothing checks it automatically).
    with open(os.path.join(DIST, "server-mods.json"), "w", encoding="utf-8") as f:
        json.dump(src.get("server", {}), f, indent=1)
    if not os.environ.get("OJ_DIST"):
        write_mod_list(src["pack"], src["mods"], src.get("server", {}))


if __name__ == "__main__":
    main()
