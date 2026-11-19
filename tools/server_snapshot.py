"""
What the October Journey server runs, recorded for players: server/state.json (generated from the server's files) and
server/changelog.json (notes for players, newest first). Both ship in the client pack, and the launch pop-up shows the
changes on its Server tab. Run it while the server is stopped, after a change is installed:

  python tools/server_snapshot.py                       show what changed since server/state.json (writes nothing)
  python tools/server_snapshot.py --note "..." [--note "..."]
                                                         record it: write server/state.json and a changelog entry
  python tools/server_snapshot.py --check                exit 1 if the live server differs from server/state.json

  --server DIR   the server folder (default: OJ_SERVER, else C:/Minecraft/MCSS/servers/octoberserver)

Only player-facing facts are recorded: versions, each mod's name, version and checksum, and chosen settings from
server.properties. Never the world seed, passwords, keys, player lists (whitelist, ops, bans, usercache) or IP logs:
this repository is public. tools/build_pack.py refuses to build when server/state.json changed without a new
changelog entry (each entry records the checksum of the state it describes).
"""
import time
import argparse, datetime, hashlib, json, os, re, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE = os.path.join(ROOT, "server", "state.json")
LOG = os.path.join(ROOT, "server", "changelog.json")
DEFAULT_SERVER = os.environ.get("OJ_SERVER") or "C:/Minecraft/MCSS/servers/octoberserver"

# server.properties keys worth telling players about, with how to show them. Nothing else is read.
SETTINGS = [
    ("difficulty", "Difficulty"), ("gamemode", "Game mode"), ("hardcore", "Hardcore"), ("pvp", "PvP"),
    ("white-list", "Whitelist"), ("online-mode", "Microsoft accounts only"), ("max-players", "Max players"),
    ("view-distance", "View distance (chunks)"), ("simulation-distance", "Simulation distance (chunks)"),
    ("spawn-protection", "Spawn protection (blocks)"), ("allow-flight", "Flight allowed"), ("allow-nether", "Nether"),
    ("spawn-monsters", "Monsters"), ("enable-command-block", "Command blocks"), ("player-idle-timeout", "Idle kick (minutes, 0 = off)"),
    ("pause-when-empty-seconds", "Pauses when empty (seconds)"), ("require-resource-pack", "Resource pack required"),
]


def nice(v: str) -> str:
    return {"true": "On", "false": "Off"}.get(v, v[:1].upper() + v[1:] if v else v)


def snapshot(server: str) -> dict:
    s = {}
    versions = [d for d in sorted(os.listdir(os.path.join(server, "versions"))) if os.path.isdir(os.path.join(server, "versions", d))] if os.path.isdir(os.path.join(server, "versions")) else []
    s["minecraft"] = versions[-1] if versions else "unknown"
    loader = os.path.join(server, "libraries", "net", "fabricmc", "fabric-loader")
    s["fabric_loader"] = sorted(os.listdir(loader))[-1] if os.path.isdir(loader) else "unknown"
    try:
        mc = json.load(open(os.path.join(server, "mcss_server_config.json"), encoding="utf-8-sig"))
        m = re.search(r"jdk-(\d+)", mc.get("javaPathOverride") or "")
        s["java"] = m.group(1) if m else "unknown"
        xms = re.search(r"-Xms(\d+)([MG])", mc.get("startupLine") or ""); xmx = re.search(r"-Xmx(\d+)([MG])", mc.get("startupLine") or "")
        gb = lambda x: (int(x.group(1)) / 1024 if x.group(2) == "M" else int(x.group(1))) if x else None
        if xms and xmx: s["memory"] = "%g GB starting, %g GB maximum" % (gb(xms), gb(xmx))
    except (OSError, ValueError):
        pass
    mods = {}
    md = os.path.join(server, "mods")
    for f in sorted(os.listdir(md)):
        if not f.endswith(".jar"):
            continue
        p = os.path.join(md, f)
        data = open(p, "rb").read()
        try:
            meta = json.loads(zipfile.ZipFile(p).read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
        except (KeyError, zipfile.BadZipFile, ValueError):
            meta = {}
        mid = meta.get("id") or f[:-4]
        mods[mid] = {"name": meta.get("name") or mid, "version": str(meta.get("version") or ""), "file": f,
                     "sha256": hashlib.sha256(data).hexdigest()}
    s["mods"] = mods
    props = {}
    for line in open(os.path.join(server, "server.properties"), encoding="utf-8", errors="replace"):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            props[k.strip()] = v.strip()
    s["settings"] = {label: nice(props[k]) for k, label in SETTINGS if k in props}
    dp = os.path.join(server, "world", "datapacks")
    s["datapacks"] = sorted(os.listdir(dp)) if os.path.isdir(dp) else []
    return s


def state_hash(state: dict) -> str:
    return hashlib.sha256(json.dumps(state, sort_keys=True).encode("utf-8")).hexdigest()


def diff(old: dict, new: dict) -> list:
    """Human-readable changes from old to new."""
    out = []
    for k, label in (("minecraft", "Minecraft"), ("fabric_loader", "Fabric Loader"), ("java", "Java"), ("memory", "Memory")):
        if old.get(k) != new.get(k):
            out.append("%s: %s -> %s" % (label, old.get(k, "none"), new.get(k, "none")))
    om, nm = old.get("mods", {}), new.get("mods", {})
    for mid in sorted(nm, key=lambda x: nm[x]["name"].lower()):
        if mid not in om:
            out.append("Added mod: %s %s" % (nm[mid]["name"], nm[mid]["version"]))
        elif om[mid]["sha256"] != nm[mid]["sha256"]:
            out.append("Updated mod: %s %s -> %s" % (nm[mid]["name"], om[mid]["version"], nm[mid]["version"]))
    for mid in sorted(om, key=lambda x: om[x]["name"].lower()):
        if mid not in nm:
            out.append("Removed mod: %s" % om[mid]["name"])
    os_, ns = old.get("settings", {}), new.get("settings", {})
    for k in ns:
        if os_.get(k) != ns[k]:
            out.append("%s: %s -> %s" % (k, os_.get(k, "not set"), ns[k]))
    for k in os_:
        if k not in ns:
            out.append("%s: no longer set" % k)
    if old.get("datapacks", []) != new.get("datapacks", []):
        out.append("Data packs: %s -> %s" % (", ".join(old.get("datapacks", [])) or "none", ", ".join(new.get("datapacks", [])) or "none"))
    return out


def client_mismatches(state: dict) -> list:
    """Server mods also in the client pack whose file differs from the pack's: players on the pack may not be able to
    join, or may see mismatched features. Matched by mod name against the pack's titles."""
    norm = lambda t: re.sub(r"[^a-z0-9]", "", t.lower())
    src = json.load(open(os.path.join(ROOT, "source", "mods.json"), encoding="utf-8"))
    out = []
    for mid, m in state.get("mods", {}).items():
        n = norm(m["name"])
        client = [c for c in src["mods"] if n and (norm(c["title"]).startswith(n) or n.startswith(norm(c["title"])))]
        if client and all(c["filename"] != m["file"] for c in client):
            out.append("%s: server has %s, the pack has %s" % (m["name"], m["file"], ", ".join(c["filename"] for c in client)))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default=DEFAULT_SERVER)
    ap.add_argument("--note", action="append", default=[])
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    new = snapshot(a.server)
    old = json.load(open(STATE, encoding="utf-8")) if os.path.exists(STATE) else {}
    changes = diff(old, new) if old else ["(first snapshot)"]
    print("Server: Minecraft %s, Fabric Loader %s, %d mods" % (new["minecraft"], new["fabric_loader"], len(new["mods"])))
    print("Changes since server/state.json:" if changes else "No changes since server/state.json.")
    for c in changes:
        print("  " + c)
    for w in client_mismatches(new):
        print("WARNING, client and server differ: " + w)
    if a.check:
        sys.exit(1 if changes else 0)
    if not a.note:
        if changes:
            print('Record it with --note "<what players should know>" (one --note per line).')
        return
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(new, f, indent=1, sort_keys=True); f.write("\n")
    log = json.load(open(LOG, encoding="utf-8")) if os.path.exists(LOG) else []
    today = datetime.date.today().isoformat()
    n = sum(1 for e in log if e["date"] == today) + 1
    log.insert(0, {"id": "%s.%d" % (today, n), "date": today, "at": int(time.time() * 1000), "state": state_hash(new), "notes": a.note})  # (R100: the moment as a unix time; "date" stays the day it names)
    with open(LOG, "w", encoding="utf-8", newline="\n") as f:
        json.dump(log, f, indent=1, ensure_ascii=False); f.write("\n")
    print("Recorded %s.%d. Build and publish the pack to send it to players." % (today, n))


if __name__ == "__main__":
    main()
