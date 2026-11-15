"""Records each Modrinth mod's download size ("size", in bytes) in source/mods.json, from Modrinth's own listing.

The launcher shows the size of an update before downloading it, so the build needs every mod's size. Run this after
adding or updating a mod:  python tools/mod_sizes.py   (only mods without a size are looked up; --all redoes them all).
The size is taken from the file whose SHA-512 matches the one recorded here, so it's the exact file devices download.
"""
import json, os, sys, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "source", "mods.json")


def main() -> None:
    doc = json.load(open(PATH, encoding="utf-8"))
    want = [m for m in doc["mods"] if "url" in m and ("size" not in m or "--all" in sys.argv)]
    if not want:
        print("every mod already has its size")
        return
    q = urllib.parse.quote(json.dumps([m["version"] for m in want]))
    req = urllib.request.Request("https://api.modrinth.com/v2/versions?ids=" + q, headers={"User-Agent": "packwiz-pack-build"})
    files = {f["hashes"]["sha512"]: f["size"] for v in json.load(urllib.request.urlopen(req, timeout=30)) for f in v["files"]}
    missing = [m["title"] for m in want if m["sha512"] not in files]
    if missing:
        sys.exit("Modrinth lists no file with the recorded SHA-512 for: " + ", ".join(missing))
    for m in want:
        m["size"] = files[m["sha512"]]
        print(f"{m['slug']}: {m['size']:,} bytes")
    with open(PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
