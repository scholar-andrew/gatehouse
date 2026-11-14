#!/bin/bash
# October Journey setup for Linux, including the Steam Deck (Flatpak Prism Launcher). Run it from a terminal (on a
# Steam Deck: in Desktop Mode, from Konsole):
#   bash october-journey-setup.sh
# It reads installer/config.json from the GitHub repo and:
#   1. installs Prism Launcher from Flathub if it is missing, and makes the October Journey instance with the pack sync
#      and the server list entry;
#   2. adds the Steam shortcut "Minecraft (Prism Launcher) - <this device>" (Steam must be closed; it asks);
#   3. adds the library art (original to the pack, from the repo);
#   4. installs the Steam Deck controller layout for the shortcut;
#   5. saves the uninstaller to ~/.local/share/october-journey/uninstall.sh and adds "Uninstall October Journey" to the
#      app menu (Desktop Mode on a Steam Deck);
#   6. every launch then syncs the pack (the owner's PC over Tailscale first, then GitHub), and with it any new library
#      art or controller layout.
# Flags: --yes (no questions; stops if Steam is running), --prism-root=DIR --steam-root=DIR (testing).
set -euo pipefail
CONFIG_URL="https://raw.githubusercontent.com/scholar-andrew/october-journey-pack/main/installer/config.json"
YES=0; PRISM_ROOT=""; STEAM_ROOT=""; TESTING=0
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;;
    --prism-root=*) PRISM_ROOT="${a#*=}"; TESTING=1 ;;
    --steam-root=*) STEAM_ROOT="${a#*=}"; TESTING=1 ;;
  esac
done
say() { echo "October Journey setup: $*"; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
curl -fsSL "$CONFIG_URL" -o "$work/config.json"
APP="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["prism_flatpak"])' "$work/config.json")"

# 1. Prism Launcher from Flathub.
if [ -z "$PRISM_ROOT" ]; then
  if ! flatpak info "$APP" >/dev/null 2>&1; then
    say "installing Prism Launcher from Flathub..."
    flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
    flatpak install --user -y flathub "$APP"
  else
    say "Prism Launcher is already installed."
  fi
  PRISM_ROOT="$HOME/.var/app/$APP/data/PrismLauncher"
fi

# 2. Steam must be closed while its shortcut list is changed.
if [ -z "$STEAM_ROOT" ]; then
  STEAM_ROOT="$HOME/.local/share/Steam"
  while pgrep -x steam >/dev/null 2>&1; do
    if [ "$YES" = 1 ]; then say "Steam is running; quit Steam and run setup again."; exit 1; fi
    read -r -p "Steam has to be closed while the shortcut, art and controller layout are added. Close Steam now? [Y/n] " ans
    case "$ans" in n|N) say "cancelled."; exit 1 ;; esac
    steam -shutdown >/dev/null 2>&1 || true
    for i in $(seq 1 30); do pgrep -x steam >/dev/null 2>&1 || break; sleep 2; done
  done
fi

python3 - "$work/config.json" "$PRISM_ROOT" "$STEAM_ROOT" "$APP" "$work" <<'PY'
import hashlib, json, os, re, socket, struct, sys, urllib.request, zlib, shutil
cfg = json.load(open(sys.argv[1])); prism, steam, app, work_dir = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
repo = cfg["repo_raw"]
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) OctoberJourneySetup/1.0"})
    return urllib.request.urlopen(req, timeout=60).read()
def say(s): print("October Journey setup:", s)

# 1. The instance, the pack sync and the server list.
inst = os.path.join(prism, "instances", cfg["instance_id"]); mc = os.path.join(inst, "minecraft")
os.makedirs(mc, exist_ok=True)
open(os.path.join(inst, "mmc-pack.json"), "wb").write(get(repo + "/clients/mmc-pack.json"))
cfgp = os.path.join(inst, "instance.cfg")
lines = open(cfgp).read().splitlines() if os.path.exists(cfgp) else ["[General]", "ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default"]
lines = [l for l in lines if not l.startswith(("name=", "OverrideCommands=", "PreLaunchCommand="))]
i = lines.index("[General]") if "[General]" in lines else (lines.insert(0, "[General]") or 0)
lines[i + 1:i + 1] = ["name=" + cfg["instance_name"], "OverrideCommands=true", "PreLaunchCommand=bash $INST_MC_DIR/pack-sync.sh"]
open(cfgp, "w").write("\n".join(lines) + "\n")
sh = get(repo + "/clients/pack-sync.sh"); open(os.path.join(mc, "pack-sync.sh"), "wb").write(sh); os.chmod(os.path.join(mc, "pack-sync.sh"), 0o755)
jar = get(repo + "/clients/packwiz-installer-bootstrap.jar")
if hashlib.sha256(jar).hexdigest().upper() != cfg["bootstrap_sha256"].upper(): sys.exit("packwiz-installer-bootstrap.jar does not match its checksum")
open(os.path.join(mc, "packwiz-installer-bootstrap.jar"), "wb").write(jar)
open(os.path.join(mc, "pack-sources.txt"), "w").write("# Tried in order; the first that answers is used.\n" + "".join(s + "\n" for s in cfg["pack_sources"]))

# servers.dat (uncompressed NBT): add each server unless its address is already listed.
def nbt_read(b, i, t):
    if t == 1: return b[i], i + 1
    if t == 2: return struct.unpack(">h", b[i:i+2])[0], i + 2
    if t in (3, 5): return b[i:i+4], i + 4
    if t in (4, 6): return b[i:i+8], i + 8
    if t == 7: n = struct.unpack(">i", b[i:i+4])[0]; return b[i+4:i+4+n], i + 4 + n
    if t == 8: n = struct.unpack(">H", b[i:i+2])[0]; return b[i+2:i+2+n].decode("utf-8"), i + 2 + n
    if t == 9:
        lt = b[i]; n = struct.unpack(">i", b[i+1:i+5])[0]; i += 5; out = []
        for _ in range(n): v, i = nbt_read(b, i, lt); out.append(v)
        return (lt, out), i
    if t == 10:
        out = []
        while True:
            ct = b[i]; i += 1
            if ct == 0: return out, i
            n = struct.unpack(">H", b[i:i+2])[0]; name = b[i+2:i+2+n].decode("utf-8"); i += 2 + n
            v, i = nbt_read(b, i, ct); out.append((ct, name, v))
    if t == 11: n = struct.unpack(">i", b[i:i+4])[0]; return b[i:i+4+4*n], i + 4 + 4 * n
    if t == 12: n = struct.unpack(">i", b[i:i+4])[0]; return b[i:i+4+8*n], i + 4 + 8 * n
    raise ValueError("NBT tag %d" % t)
def nbt_write(t, v):
    if t == 1: return bytes([v])
    if t == 2: return struct.pack(">h", v)
    if t in (3, 4, 5, 6, 11, 12): return v
    if t == 7: return struct.pack(">i", len(v)) + v
    if t == 8: e = v.encode("utf-8"); return struct.pack(">H", len(e)) + e
    if t == 9: lt, items = v; return bytes([lt if items else 0]) + struct.pack(">i", len(items)) + b"".join(nbt_write(lt, x) for x in items)
    if t == 10: return b"".join(bytes([ct]) + nbt_write(8, n) + nbt_write(ct, x) for ct, n, x in v) + b"\x00"
sd = os.path.join(mc, "servers.dat")
if os.path.exists(sd):
    b = open(sd, "rb").read(); root, _ = nbt_read(b, 1 + 2 + struct.unpack(">H", b[1:3])[0], 10)
else:
    root = []
servers = next((x for x in root if x[1] == "servers"), None)
if servers is None: servers = (9, "servers", (10, [])); root.append(servers)
items = servers[2][1]
root[root.index(servers)] = (9, "servers", (10, items))
for s in cfg["servers"]:
    if not any(ct == 8 and n == "ip" and v.lower() == s["address"].lower() for entry in items for ct, n, v in entry):
        items.append([(8, "name", s["name"]), (8, "ip", s["address"]), (1, "acceptTextures", 1)])
open(sd, "wb").write(b"\x0a" + nbt_write(8, "") + nbt_write(10, root))
say("Prism instance ready: " + inst)

# 2. The Steam shortcut (binary shortcuts.vdf).
login = os.path.join(steam, "config", "loginusers.vdf")
users = re.findall(r'"(7656\d{13})"\s*\{(.*?)\}', open(login).read(), re.S) if os.path.exists(login) else []
if not users: sys.exit("could not find your Steam account; sign in to Steam once, quit it, and run setup again")
best = next((u for u, body in users if re.search(r'"MostRecent"\s*"1"', body)), users[0][0])
acct = str(int(best) - 76561197960265728)
cdir = os.path.join(steam, "userdata", acct, "config"); os.makedirs(cdir, exist_ok=True)
def rmap(b, i):
    out = []
    while True:
        t = b[i]; i += 1
        if t == 8: return out, i
        j = b.index(0, i); k = b[i:j].decode("utf-8"); i = j + 1
        if t == 0: v, i = rmap(b, i)
        elif t == 1: j = b.index(0, i); v = b[i:j].decode("utf-8"); i = j + 1
        elif t == 2: v = struct.unpack("<i", b[i:i+4])[0]; i += 4
        else: raise ValueError("vdf type %d" % t)
        out.append([t, k, v])
def wmap(items):
    o = b""
    for t, k, v in items:
        o += bytes([t]) + k.encode() + b"\0"
        o += wmap(v) if t == 0 else (v.encode() + b"\0" if t == 1 else struct.pack("<i", v))
    return o + b"\x08"
svdf = os.path.join(cdir, "shortcuts.vdf")
if os.path.exists(svdf): shutil.copy2(svdf, svdf + ".bak-setup"); root, _ = rmap(open(svdf, "rb").read(), 0)
else: root = [[0, "shortcuts", []]]
sc = next(x for x in root if x[1].lower() == "shortcuts")[2]
name = cfg["shortcut_name"] + " - " + socket.gethostname()
exe, opts = '"/usr/bin/flatpak"', "run " + app
def field(e, k):
    return next((x for x in e if x[1].lower() == k.lower()), None)
def setf(e, k, t, v):
    f = field(e, k)
    if f: f[0], f[2] = t, v
    else: e.append([t, k, v])
entry = None
for _, _, e in sc:
    lo = field(e, "LaunchOptions"); an = field(e, "AppName")
    if (lo and app in lo[2]) or (an and an[2] == name): entry = e; break
appid = (zlib.crc32((exe + name).encode()) | 0x80000000) & 0xFFFFFFFF
if entry is None:
    entry = []; sc.append([0, str(len(sc)), entry])
    setf(entry, "appid", 2, struct.unpack("<i", struct.pack("<I", appid))[0])
    for k in ("ShortcutPath", "DevkitGameID"): setf(entry, k, 1, "")
    for k in ("IsHidden", "OpenVR", "Devkit", "DevkitOverrideAppID", "LastPlayTime"): setf(entry, k, 2, 0)
    setf(entry, "AllowDesktopConfig", 2, 1); setf(entry, "AllowOverlay", 2, 1); setf(entry, "tags", 0, [])
else:
    appid = field(entry, "appid")[2] & 0xFFFFFFFF
old = (field(entry, "AppName") or [0, 0, ""])[2]
setf(entry, "AppName", 1, name); setf(entry, "Exe", 1, exe); setf(entry, "StartDir", 1, '"/usr/bin/"')
setf(entry, "LaunchOptions", 1, opts); setf(entry, "FlatpakAppID", 1, app)
grid = os.path.join(cdir, "grid"); os.makedirs(grid, exist_ok=True)
setf(entry, "icon", 1, os.path.join(grid, "%d_icon.png" % appid))
open(svdf, "wb").write(wmap(root)); rmap(open(svdf, "rb").read(), 0)
say("Steam shortcut: %s (app %d)" % (name, appid))

# 3. Library art, original to the pack.
try:
    for key, fn in (("cover", "%dp.png"), ("hero", "%d_hero.png"), ("logo", "%d_logo.png"), ("icon", "%d_icon.png"), ("wide", "%d.png")):
        open(os.path.join(grid, fn % appid), "wb").write(get(repo + "/" + cfg["art"][key]))
    say("library art added.")
except Exception as e:
    say("library art skipped: %s" % e)

# 4. The Steam Deck controller layout, under the shortcut's (lower-case) name; carried over from an older name if any.
try:
    root = os.path.join(steam, "steamapps", "common", "Steam Controller Configs", acct, "config")
    d = os.path.join(root, name.lower()); os.makedirs(d, exist_ok=True)
    target = os.path.join(d, "controller_neptune.vdf")
    prev = os.path.join(root, old.lower(), "controller_neptune.vdf") if old else ""
    if prev and prev != target and os.path.exists(prev): layout = open(prev).read()
    else: layout = get(repo + "/installer/steam/controller_neptune.vdf").decode("utf-8")
    layout = re.sub(r'"url"\s*"[^"]*"', '"url"\t\t"autosave://%s"' % target, layout, count=1)
    if os.path.exists(target): shutil.copy2(target, target + ".bak-setup")
    open(target, "w").write(layout)
    cs = os.path.join(root, "configset_controller_neptune.vdf")
    text = open(cs).read() if os.path.exists(cs) else '"controller_config"\n{\n}\n'
    if ('"%s"' % name.lower()) not in text.lower():
        end = text.rindex("}"); text = text[:end] + '\t"%s"\n\t{\n\t\t"autosave"\t\t"1"\n\t}\n' % name.lower() + text[end:]
        open(cs, "w").write(text)
    say("Steam Deck controller layout added.")
except Exception as e:
    say("controller layout skipped: %s" % e)
# Where the pack sync puts later art and layout updates for this shortcut.
json.dump({"grid": grid, "appid": appid, "controller": os.path.join(root, name.lower())}, open(os.path.join(mc, "steam-target.json"), "w"))
open(os.path.join(work_dir, "steam-paths"), "w").write(grid + "\n" + root + "\n")
say("all set. Start Steam (or switch to Game Mode) and play \"%s\". The first time Prism opens, sign in with your Microsoft account. Every launch updates the mods." % name)
PY

# Prism runs sandboxed: let it write the art and controller folders (and nothing else of Steam's) so every launch can
# keep them up to date.
if [ -f "$work/steam-paths" ] && [ "$STEAM_ROOT" = "$HOME/.local/share/Steam" ]; then
  while IFS= read -r d; do [ -n "$d" ] && flatpak override --user --filesystem="${d/#$HOME/~}" "$APP"; done < "$work/steam-paths"
fi

# The uninstaller, kept on this device with an app-menu entry. Checked against config.json's SHA-256; if it can't be
# fetched or doesn't match, setup carries on without it (it's also on the release page). Testing puts both under
# --prism-root instead.
if [ "$TESTING" = 1 ]; then app_dir="$PRISM_ROOT/october-journey"; menu_dir="$PRISM_ROOT/applications"
else app_dir="$HOME/.local/share/october-journey"; menu_dir="$HOME/.local/share/applications"; fi
if python3 - "$work/config.json" "$work/uninstall.sh" <<'PY'
import hashlib, json, sys, urllib.request
u = json.load(open(sys.argv[1]))["uninstall_linux"]
data = urllib.request.urlopen(urllib.request.Request(u["url"], headers={"User-Agent": "OctoberJourneySetup/1.0"}), timeout=60).read()
if hashlib.sha256(data).hexdigest().upper() != u["sha256"].upper(): sys.exit("it does not match its checksum")
open(sys.argv[2], "wb").write(data)
PY
then
  mkdir -p "$app_dir" "$menu_dir"
  install -m 755 "$work/uninstall.sh" "$app_dir/uninstall.sh"
  cat > "$menu_dir/october-journey-uninstall.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Uninstall October Journey
Comment=Remove the October Journey Minecraft setup (Steam shortcut, art, controller layout, Prism instance)
Exec=bash "$app_dir/uninstall.sh" --pause
Icon=edit-delete
Terminal=true
Categories=Game;
EOF
  say "uninstaller saved: $app_dir/uninstall.sh (in the app menu as \"Uninstall October Journey\")."
else
  say "uninstaller not added; it can be downloaded from the release page."
fi
