"""
Builds the October Journey client pack in packwiz format, in two variants:

  dist/main/  every device (PC, laptop)
  dist/deck/  the same plus the Steam Deck-only mods

Source of truth: source/mods.json (one entry per mod, "deck": true for Deck-only) and source/files/ (files copied as-is:
configs, and jars that are not on Modrinth). Modrinth mods are referenced by URL and SHA-512, so their jars are never
stored here. Run: python tools/build_pack.py
"""
import hashlib, json, os, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "source")
DIST = os.path.join(ROOT, "dist")


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


def main() -> None:
    src = json.load(open(os.path.join(SRC, "mods.json"), encoding="utf-8"))
    for variant in ("main", "deck"):
        build(variant, src["pack"], src["mods"])
    # What the server must match, checked against the server's mods folder by tools/check_server.py.
    with open(os.path.join(DIST, "server-mods.json"), "w", encoding="utf-8") as f:
        json.dump(src.get("server", {}), f, indent=1)


if __name__ == "__main__":
    main()
