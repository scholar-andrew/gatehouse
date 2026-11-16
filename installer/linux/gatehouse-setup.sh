#!/bin/bash
# Gatehouse setup for Linux: installs a server profile's pack (docs/profiles.md; by default October Journey's, whose
# profile is CONFIG_URL below; --config= gives another). The pack's name in every message comes from the profile.
# An October Journey install from before setup knew about profiles keeps its folder (~/.local/share/october-journey)
# and app-menu files (october-journey-setup.desktop, -uninstall.desktop); any other install is gatehouse-<instance id>.
# For Linux, including the Steam Deck (Flatpak Prism Launcher). Run it from a terminal (on a
# Steam Deck: in Desktop Mode, from Konsole):
#   bash gatehouse-setup.sh
# It reads installer/config.json from the GitHub repo and always installs the October Journey modpack: a Prism Launcher
# instance whose mods update on every launch, in Prism's own instances folder. It first offers a quick install
# (everything below, in the usual places); otherwise it asks about each of these (Enter = yes) and where to save the
# uninstaller and installer (Prism Launcher's place is set by Flatpak, and Steam's by Steam):
#   prism        Prism Launcher from Flathub, if it isn't installed
#   server       the October Journey server in the instance's server list
#   game         a Steam shortcut "October Journey - <this device>" that starts the game and joins the server (Prism's
#                --launch and --server; the pack update and launch pop-up still run first)
#   shortcut     a Steam shortcut "Gatehouse Minecraft Launcher - <kind of device>" that opens the Gatehouse launcher
#                (the packs installed here, whether each server is online, Play, and a button for Prism Launcher)
#                (Steam must be closed for either shortcut; it asks)
#   art          Steam library art: October Journey's for its shortcut, Gatehouse's for the launcher's
#   controller   the Steam Deck controller layout for both shortcuts
#   updates      keep the server entry, art and layout up to date on each launch (pack-options.json); also lets Prism's
#                Flatpak write the two Steam folders that needs
#   uninstaller  save the uninstaller to ~/.local/share/october-journey, with "Uninstall October Journey" in the app menu
#   installer    save this installer there too, with "October Journey setup" in the app menu, to change choices later
# When the pack is already here but the October Journey shortcut isn't (set up before it existed), it first offers adding
# just that shortcut, with the art and layouts of both shortcuts and a refreshed saved uninstaller.
# Flags: --yes (no questions: everything above; stops if Steam is running)  --skip=item,item (leave those out)
#        --add-shortcut (with --yes: only add the October Journey shortcut, as that first question does)
#        --tools-dir=DIR (where to save the uninstaller and installer; default ~/.local/share/october-journey)
#        --pause (wait for Enter at the end; used by the app-menu entry)
#        --prism-root=DIR --steam-root=DIR (testing: use these folders instead of the real ones)
#        --profile=ID (install another server profile published in this repository: dist/ID/config.json)
#        --config=URL (a profile's config.json anywhere, e.g. file:///home/.../installer/config.json for testing)
set -euo pipefail
CONFIG_URL="https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/installer/config.json"
YES=0; PRISM_ROOT=""; STEAM_ROOT=""; TESTING=0; SKIP=","; PAUSE=0; TOOLS_DIR=""; QUICK=0; ADD_FLAG=0; ADD_ONLY=0
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;;
    --skip=*) SKIP=",${a#*=}," ;;
    --pause) PAUSE=1 ;;
    --add-shortcut) ADD_FLAG=1 ;;
    --config=*) CONFIG_URL="${a#*=}" ;;
    --profile=*) CONFIG_URL="${CONFIG_URL%/installer/config.json}/dist/${a#*=}/config.json" ;;
    --tools-dir=*) TOOLS_DIR="${a#*=}" ;;
    --prism-root=*) PRISM_ROOT="${a#*=}"; TESTING=1 ;;
    --steam-root=*) STEAM_ROOT="${a#*=}"; TESTING=1 ;;
  esac
done
say() { echo "Gatehouse setup: $*"; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
curl -fsSL "$CONFIG_URL" -o "$work/config.json"
APP="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["prism_flatpak"])' "$work/config.json")"

# What to install.
instances_dir() {  # Prism's instances folder: <data>/instances, unless Prism's own settings name another
  python3 -c 'import os,sys
d=sys.argv[1]; v=""
try:
    for l in open(os.path.join(d,"prismlauncher.cfg")):
        if l.startswith("InstanceDir="): v=l.split("=",1)[1].strip().strip(chr(34))
except OSError: pass
print(os.path.join(d, v or "instances"))' "$1"
}
choose() {  # choose item "question" default(y/n): sets C_<item> to 1 or 0
  local v ans hint="[Y/n]"; [ "$3" = n ] && hint="[y/N]"
  if [[ "$SKIP" == *",$1,"* ]]; then v=0
  elif [ "$YES" = 1 ] || [ "$QUICK" = 1 ]; then [ "$3" = y ] && v=1 || v=0
  else read -r -p "  $2 $hint " ans; case "${ans:-$3}" in y|Y|yes) v=1 ;; *) v=0 ;; esac; fi
  printf -v "C_$1" '%s' "$v"
}
[ -n "$STEAM_ROOT" ] || STEAM_ROOT="$HOME/.local/share/Steam"
PRISM_INSTALLED=0
if [ "$TESTING" = 1 ] || flatpak info "$APP" >/dev/null 2>&1; then PRISM_INSTALLED=1; fi
if [ "$TESTING" = 1 ]; then menu_dir="$PRISM_ROOT/applications"; else menu_dir="$HOME/.local/share/applications"; fi
# What this install is filed under on the device: the name of its tools folder and of its app-menu files. An October
# Journey install from before setup knew about profiles keeps the name it has (october-journey), so it carries on
# untouched; every other install, of any profile, is "gatehouse-<instance id>". So several packs can sit side by side.
INST_ID="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["instance_id"])' "$work/config.json")"
KEY="gatehouse-$INST_ID"
legacy_tools="$HOME/.local/share/october-journey"; [ "$TESTING" = 0 ] || legacy_tools="$PRISM_ROOT/october-journey"
if [ "$INST_ID" = october-journey ] && { [ -d "$legacy_tools" ] || [ -f "$menu_dir/october-journey-uninstall.desktop" ] || [ -f "$menu_dir/october-journey-setup.desktop" ]; }; then KEY="october-journey"; fi
default_tools="$HOME/.local/share/$KEY"; [ "$TESTING" = 0 ] || default_tools="$PRISM_ROOT/$KEY"
# Where an earlier setup saved the uninstaller (its app-menu entry says).
saved_tools="$(sed -n 's|^Exec=bash "\(.*\)/uninstall.sh".*|\1|p' "$menu_dir/$KEY-uninstall.desktop" 2>/dev/null | head -n 1 || true)"
[ -n "$saved_tools" ] && [ -d "$saved_tools" ] && default_tools="$saved_tools"
[ -n "$TOOLS_DIR" ] || TOOLS_DIR="$default_tools"

# The pack being installed, as its profile names it.
PACK="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["pack_name"])' "$work/config.json" 2>/dev/null || echo "the pack")"
# What's here already: the pack, and which of the two Steam shortcuts (prints "<pack> <prism shortcut> <game shortcut>").
read -r PACK_HERE HAS_PRISM_SC HAS_GAME_SC < <(python3 - "$work/config.json" "$STEAM_ROOT" "$(instances_dir "${PRISM_ROOT:-$HOME/.var/app/$APP/data/PrismLauncher}")" <<'PY' || echo "0 0 0"
import json, os, re, socket, struct, sys
cfg = json.load(open(sys.argv[1])); steam, instances = sys.argv[2], sys.argv[3]
def device_kind():
    # A plain word for the kind of device (it follows the shortcut's name), not the computer's own name.
    def rd(p):
        try: return open(p).read().strip()
        except OSError: return ""
    vendor, product = rd("/sys/class/dmi/id/sys_vendor"), rd("/sys/class/dmi/id/product_name")
    both = (vendor + " " + product + " " + rd("/sys/class/dmi/id/product_family")).lower()
    if vendor.lower() == "valve" or product in ("Jupiter", "Galileo"): return "Steam Deck"
    if "rog ally" in both or "rog xbox ally" in both: return "ROG"
    if "legion go" in both: return "Legion Go"
    try:
        if any(n.startswith("BAT") for n in os.listdir("/sys/class/power_supply")): return "Laptop"
    except OSError: pass
    return "PC"
inst_id, app, host = cfg["instance_id"], cfg["prism_flatpak"], device_kind()
pack = os.path.isfile(os.path.join(instances, inst_id, "instance.cfg"))
game_name = cfg.get("game_shortcut_name", cfg["pack_name"]) + " - " + host; prism_name = cfg["shortcut_name"] + " - " + host
found = [0, 0]
try:
    users = re.findall(r'"(7656\d{13})"\s*\{(.*?)\}', open(os.path.join(steam, "config", "loginusers.vdf")).read(), re.S)
    best = next((u for u, body in users if re.search(r'"MostRecent"\s*"1"', body)), users[0][0])
    b = open(os.path.join(steam, "userdata", str(int(best) - 76561197960265728), "config", "shortcuts.vdf"), "rb").read()
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
    root, _ = rmap(b, 0)
    for _, _, e in next(x for x in root if x[1].lower() == "shortcuts")[2]:
        f = {k.lower(): v for _, k, v in e}
        lo, an = f.get("launchoptions", ""), f.get("appname", "")
        if ("--launch " + inst_id) in lo or ("--play " + inst_id) in lo or an == game_name: found[1] = 1
        elif (app in lo and "--launch" not in lo and "--play" not in lo) or an == prism_name: found[0] = 1
except Exception:
    pass
print(int(pack), found[0], found[1])
PY
)
OFFER_ADD=0
[ "$PACK_HERE" = 1 ] && [ "$HAS_GAME_SC" = 0 ] && [ -d "$STEAM_ROOT" ] && [ "$PRISM_INSTALLED" = 1 ] && OFFER_ADD=1
[ "$ADD_FLAG" = 1 ] && [ "$OFFER_ADD" = 1 ] && ADD_ONLY=1
echo "Gatehouse setup installs the $PACK modpack: a Prism Launcher instance whose mods update every time you play."
if [ "$YES" = 0 ] && [ "$OFFER_ADD" = 1 ]; then
  read -r -p "$PACK is already set up here. Add just the new \"$PACK\" Steam shortcut (starts the game and joins the server), with the art and controller layout for both shortcuts? [Y/n] " ans
  case "${ans:-y}" in y|Y|yes) ADD_ONLY=1 ;; esac
fi
if [ "$ADD_ONLY" = 1 ]; then
  # Only the new shortcut, the art and layouts of both, and the saved uninstaller and installer refreshed if they're here.
  C_prism=1; C_server=0; C_game=1; C_shortcut=$HAS_PRISM_SC; C_art=1; C_controller=1; C_updates=0
  C_uninstaller=0; [ -f "$TOOLS_DIR/uninstall.sh" ] && C_uninstaller=1
  C_installer=0; [ -f "$TOOLS_DIR/setup.sh" ] && C_installer=1
else
if [ "$YES" = 0 ]; then
  read -r -p "Quick install: Prism Launcher, the server, two Steam shortcuts ($PACK and Prism Launcher) with their art and controller layout, updates, and the uninstaller, in the usual places? [Y/n] " ans
  case "${ans:-y}" in y|Y|yes) QUICK=1 ;; *) echo "Choose what to add (Enter = the answer in capitals):" ;; esac
fi
if [ "$PRISM_INSTALLED" = 1 ]; then C_prism=1; echo "  Prism Launcher: already installed."
else choose prism "Install Prism Launcher from Flathub?" y; fi
choose server "Add the $PACK server to the server list?" y
if [ -d "$STEAM_ROOT" ] && [ "$C_prism" = 1 ]; then
  choose game "Add the $PACK Steam shortcut, which starts the game and joins the server? (Steam has to be closed)" y
  choose shortcut "Add a Prism Launcher Steam shortcut, which opens the launcher? (Steam has to be closed)" y
else C_game=0; C_shortcut=0; [ -d "$STEAM_ROOT" ] || echo "  Steam isn't installed: no Steam shortcuts."; fi
if [ "$C_game$C_shortcut" != 00 ]; then
  choose art "Add the Steam library art ($PACK's, and Gatehouse's for the launcher shortcut)?" y
  choose controller "Add the Steam Deck controller layout to the shortcuts?" y
else C_art=0; C_controller=0; fi
if [ "$C_server" = 1 ] || [ "$C_art" = 1 ] || [ "$C_controller" = 1 ]; then
  choose updates "Keep those up to date each time you play? (changes you make yourself are kept)" y
else C_updates=0; fi
choose uninstaller "Save the uninstaller (app menu: Uninstall $PACK)?" y
choose installer "Save this installer (app menu: $PACK setup), to change these choices later?" y
fi
if [ "$YES" = 0 ] && [ "$QUICK" = 0 ] && [ "$ADD_ONLY" = 0 ] && [ "$C_uninstaller$C_installer" != 00 ]; then
  while :; do
    read -r -p "  Where should the uninstaller and installer be saved? [$TOOLS_DIR] " ans
    ans="${ans:-$TOOLS_DIR}"; ans="${ans/#\~/$HOME}"
    case "$ans" in /*) TOOLS_DIR="${ans%/}"; break ;; *) echo "  That has to be a full folder path, starting with / or ~." ;; esac
  done
fi
say "chosen: prism=$C_prism server=$C_server game=$C_game shortcut=$C_shortcut add_only=$ADD_ONLY art=$C_art controller=$C_controller updates=$C_updates uninstaller=$C_uninstaller installer=$C_installer tools=$TOOLS_DIR"

# 1. Prism Launcher from Flathub.
if [ "$TESTING" = 0 ]; then
  if [ "$C_prism" = 1 ] && ! flatpak info "$APP" >/dev/null 2>&1; then
    say "installing Prism Launcher from Flathub..."
    flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
    flatpak install --user -y flathub "$APP"
  fi
  PRISM_ROOT="$HOME/.var/app/$APP/data/PrismLauncher"
fi

# 2. Steam must be closed while its shortcut list is changed.
if [ "$TESTING" = 0 ] && [ "$C_game$C_shortcut" != 00 ]; then
  while pgrep -x steam >/dev/null 2>&1; do
    if [ "$YES" = 1 ]; then say "Steam is running; quit Steam and run setup again."; exit 1; fi
    read -r -p "Steam has to be closed while the shortcut, art and controller layout are added. Close Steam now? [Y/n] " ans
    case "$ans" in n|N) say "cancelled."; exit 1 ;; esac
    steam -shutdown >/dev/null 2>&1 || true
    for i in $(seq 1 30); do pgrep -x steam >/dev/null 2>&1 || break; sleep 2; done
  done
fi

python3 - "$work/config.json" "$(instances_dir "$PRISM_ROOT")" "$STEAM_ROOT" "$APP" "$work" "$C_server$C_shortcut$C_art$C_controller$C_updates$C_game$ADD_ONLY" <<'PY'
import hashlib, json, os, re, socket, struct, sys, urllib.request, zlib, shutil
cfg = json.load(open(sys.argv[1])); instances, steam, app, work_dir = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
want_server, want_shortcut, want_art, want_ctrl, want_updates, want_game, add_only = (c == "1" for c in sys.argv[6])
repo = cfg["repo_raw"]
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) OctoberJourneySetup/1.0"})
    return urllib.request.urlopen(req, timeout=60).read()
def say(s): print("Gatehouse setup:", s)

# 1. The instance, the pack sync and the server list.
inst = os.path.join(instances, cfg["instance_id"]); mc = os.path.join(inst, "minecraft")
os.makedirs(mc, exist_ok=True)
open(os.path.join(inst, "mmc-pack.json"), "wb").write(get(repo + "/" + cfg.get("mmc_pack", "clients/mmc-pack.json")))  # (the profile's own, when it names one)
cfgp = os.path.join(inst, "instance.cfg")
lines = open(cfgp).read().splitlines() if os.path.exists(cfgp) else ["[General]", "ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default"]
lines = [l for l in lines if not l.startswith(("name=", "OverrideCommands=", "PreLaunchCommand=", "WrapperCommand="))]
i = lines.index("[General]") if "[General]" in lines else (lines.insert(0, "[General]") or 0)
lines[i + 1:i + 1] = ["name=" + cfg["instance_name"], "OverrideCommands=true", "PreLaunchCommand=bash $INST_MC_DIR/pack-sync.sh",
                     "WrapperCommand=bash $INST_MC_DIR/launch-wrapper.sh"]  # (lets Quit cancel a launch without Prism calling it a failure)
open(cfgp, "w").write("\n".join(lines) + "\n")
sh = get(repo + "/clients/pack-sync.sh"); open(os.path.join(mc, "pack-sync.sh"), "wb").write(sh); os.chmod(os.path.join(mc, "pack-sync.sh"), 0o755)
open(os.path.join(mc, "launch-wrapper.sh"), "wb").write(get(repo + "/clients/launch-wrapper.sh")); os.chmod(os.path.join(mc, "launch-wrapper.sh"), 0o755)
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
if want_server:
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
if want_server: say("server added to the server list.")
# What each launch keeps up to date (read by pack-sync.sh), kept beside the player's own settings in the same file (mod
# switches, pop-ups). Adding only the new shortcut leaves it as it is.
if not add_only:
    pop = os.path.join(mc, "pack-options.json")
    try: po = json.load(open(pop, encoding="utf-8-sig"))
    except Exception: po = {}
    if not isinstance(po, dict): po = {}
    po.update({"steam_art": want_updates and want_art, "steam_controller": want_updates and want_ctrl, "server_entry": want_updates and want_server})
    json.dump(po, open(pop, "w"), indent=1)

if want_shortcut or want_game:
    # 2. The Steam shortcuts (binary shortcuts.vdf): "October Journey - <device>" starts the game and joins the server
    # (through the Gatehouse launcher: Prism's --launch and --server; the pack update still runs first, and its window
    # is skipped when there's nothing to show and the server is online); "Gatehouse Minecraft Launcher - <device>" opens
    # the Gatehouse launcher window. Both run extras/gatehouse-launcher.sh inside Prism's Flatpak; it's fetched here so
    # the shortcuts work before the pack's first update has brought its own copy.
    launcher_sh = os.path.join(mc, "extras", "gatehouse-launcher.sh")
    try:
        os.makedirs(os.path.dirname(launcher_sh), exist_ok=True)
        open(launcher_sh, "wb").write(get(repo + "/clients/gatehouse-launcher.sh")); os.chmod(launcher_sh, 0o755)
    except Exception as e: say("Gatehouse launcher script not fetched (%s): the pack's first update brings it." % e)
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
    def device_kind():
        # A plain word for the kind of device (it follows the shortcut's name), not the computer's own name.
        def rd(p):
            try: return open(p).read().strip()
            except OSError: return ""
        vendor, product = rd("/sys/class/dmi/id/sys_vendor"), rd("/sys/class/dmi/id/product_name")
        both = (vendor + " " + product + " " + rd("/sys/class/dmi/id/product_family")).lower()
        if vendor.lower() == "valve" or product in ("Jupiter", "Galileo"): return "Steam Deck"
        if "rog ally" in both or "rog xbox ally" in both: return "ROG"
        if "legion go" in both: return "Legion Go"
        try:
            if any(n.startswith("BAT") for n in os.listdir("/sys/class/power_supply")): return "Laptop"
        except OSError: pass
        return "PC"
    host, inst_id = device_kind(), cfg["instance_id"]
    names = {"game": cfg.get("game_shortcut_name", cfg["pack_name"]) + " - " + host, "prism": cfg["shortcut_name"] + " - " + host}
    exe = '"/usr/bin/flatpak"'
    def field(e, k):
        return next((x for x in e if x[1].lower() == k.lower()), None)
    def text(e, k):
        f = field(e, k); return f[2] if f and isinstance(f[2], str) else ""
    def setf(e, k, t, v):
        f = field(e, k)
        if f: f[0], f[2] = t, v
        else: e.append([t, k, v])
    def is_game(e, name):
        lo = text(e, "LaunchOptions"); return ("--launch " + inst_id) in lo or ("--play " + inst_id) in lo or text(e, "AppName") == name
    def is_prism(e, name):
        lo = text(e, "LaunchOptions"); return (app in lo and "--launch" not in lo and "--play" not in lo) or text(e, "AppName") == name
    grid = os.path.join(cdir, "grid"); os.makedirs(grid, exist_ok=True)
    root_l = os.path.join(steam, "steamapps", "common", "Steam Controller Configs", acct, "config")
    targets, game_target, play, made = [], None, None, []
    for kind in ("game", "prism"):
        if not (want_game if kind == "game" else want_shortcut): continue
        name = names[kind]
        opts = 'run --command=bash %s "%s"' % (app, launcher_sh) + (" --play " + inst_id if kind == "game" else "")
        entry = None
        for _, _, e in sc:
            if (is_game(e, name) if kind == "game" else (not is_game(e, "\0") and is_prism(e, name))): entry = e; break
        appid = (zlib.crc32((exe + name).encode()) | 0x80000000) & 0xFFFFFFFF
        if entry is None:
            entry = []; sc.append([0, str(len(sc)), entry])
            setf(entry, "appid", 2, struct.unpack("<i", struct.pack("<I", appid))[0])
            for k in ("ShortcutPath", "DevkitGameID"): setf(entry, k, 1, "")
            for k in ("IsHidden", "OpenVR", "Devkit", "DevkitOverrideAppID", "LastPlayTime"): setf(entry, k, 2, 0)
            setf(entry, "AllowDesktopConfig", 2, 1); setf(entry, "AllowOverlay", 2, 1); setf(entry, "tags", 0, [])
        else:
            appid = field(entry, "appid")[2] & 0xFFFFFFFF
        old = text(entry, "AppName")
        setf(entry, "AppName", 1, name); setf(entry, "Exe", 1, exe); setf(entry, "StartDir", 1, '"/usr/bin/"')
        setf(entry, "LaunchOptions", 1, opts); setf(entry, "FlatpakAppID", 1, app)
        setf(entry, "icon", 1, os.path.join(grid, "%d_icon.png" % appid))
        made.append((kind, name, old, appid))
    open(svdf, "wb").write(wmap(root)); rmap(open(svdf, "rb").read(), 0)
    for kind, name, old, appid in made:
        say("Steam shortcut: %s (app %d)" % (name, appid))
        if want_art:
            # 3. Library art: the pack's own for its shortcut, Gatehouse's for the launcher's.
            art = cfg["art"] if kind == "game" else (cfg.get("art_launcher") or cfg.get("art_prism"))
            try:
                if not art: raise ValueError("not in setup's settings yet")
                for key, fn in (("cover", "%dp.png"), ("hero", "%d_hero.png"), ("logo", "%d_logo.png"), ("icon", "%d_icon.png"), ("wide", "%d.png")):
                    open(os.path.join(grid, fn % appid), "wb").write(get(repo + "/" + art[key]))
                say("library art added.")
            except Exception as e:
                say("library art skipped: %s" % e)
        # 4. The Steam Deck controller layout, under the shortcut's (lower-case) name; carried over from an older name if any.
        if want_ctrl:
            try:
                d = os.path.join(root_l, name.lower()); os.makedirs(d, exist_ok=True)
                target = os.path.join(d, "controller_neptune.vdf")
                prev = os.path.join(root_l, old.lower(), "controller_neptune.vdf") if old else ""
                if prev and prev != target and os.path.exists(prev): layout = open(prev).read()
                else: layout = get(repo + "/installer/steam/controller_neptune.vdf").decode("utf-8")
                layout = re.sub(r'"url"\s*"[^"]*"', '"url"\t\t"autosave://%s"' % target, layout, count=1)
                if os.path.exists(target): shutil.copy2(target, target + ".bak-setup")
                open(target, "w").write(layout)
                cs = os.path.join(root_l, "configset_controller_neptune.vdf")
                text_ = open(cs).read() if os.path.exists(cs) else '"controller_config"\n{\n}\n'
                if ('"%s"' % name.lower()) not in text_.lower():
                    end = text_.rindex("}"); text_ = text_[:end] + '\t"%s"\n\t{\n\t\t"autosave"\t\t"1"\n\t}\n' % name.lower() + text_[end:]
                    open(cs, "w").write(text_)
                say("Steam Deck controller layout added.")
            except Exception as e:
                say("controller layout skipped: %s" % e)
        t = {"grid": grid, "appid": appid, "controller": os.path.join(root_l, name.lower()), "art": "pack" if kind == "game" else "launcher"}
        targets.append(t)
        if kind == "game": game_target = t
        play = play or name
    # Where the pack sync puts later art and layout updates: "targets", one per shortcut. The single grid / appid /
    # controller beside it is for pack syncs from before there were two shortcuts.
    st = {"targets": targets}
    if game_target: st.update({k: game_target[k] for k in ("grid", "appid", "controller")})
    json.dump(st, open(os.path.join(mc, "steam-target.json"), "w"))
    if (want_updates or add_only) and (want_art or want_ctrl): open(os.path.join(work_dir, "steam-paths"), "w").write(grid + "\n" + root_l + "\n" + svdf + "\n")   # (and Steam's shortcut list itself, for the launcher's Update Steam shortcuts)
    if play == names["game"]:
        say("all set. Start Steam (or switch to Game Mode) and play \"%s\": it updates the pack, starts the game and joins the server. The first time, sign in to Prism Launcher with your Microsoft account." % play)
    else:
        say("all set. Start Steam (or switch to Game Mode) and play \"%s\". The first time Prism opens, sign in with your Microsoft account. Every launch updates the mods." % play)
else:
    say("all set. Open Prism Launcher and play \"%s\". The first time Prism opens, sign in with your Microsoft account. Every launch updates the mods." % cfg["instance_name"])
PY

# Prism runs sandboxed: let it write the art and controller folders (and nothing else of Steam's) so every launch can
# keep them up to date.
if [ -f "$work/steam-paths" ] && [ "$STEAM_ROOT" = "$HOME/.local/share/Steam" ]; then
  while IFS= read -r d; do [ -n "$d" ] && flatpak override --user --filesystem="${d/#$HOME/~}" "$APP"; done < "$work/steam-paths"
fi

# The uninstaller, kept on this device with an app-menu entry. Checked against config.json's SHA-256; if it can't be
# fetched or doesn't match, setup carries on without it (it's also on the release page). Testing puts both under
# --prism-root instead.
app_dir="$TOOLS_DIR"
# What the uninstaller needs to know about this pack, since it works offline: saved beside it.
if [ "$C_uninstaller" = 1 ] || [ "$C_installer" = 1 ]; then
  mkdir -p "$app_dir"
  python3 - "$work/config.json" "$app_dir/gatehouse-profile.json" "$KEY" <<'PY' || true
import json, sys
c = json.load(open(sys.argv[1]))
json.dump({"key": sys.argv[3], "pack_name": c["pack_name"], "instance_id": c["instance_id"], "shortcut_name": c["shortcut_name"],
           "game_shortcut_name": c.get("game_shortcut_name", c["pack_name"]),
           "servers": [{"name": s["name"], "address": s["address"]} for s in c.get("servers", [])]}, open(sys.argv[2], "w"))
PY
fi
if [ "$C_uninstaller" = 0 ]; then :
elif python3 - "$work/config.json" "$work/uninstall.sh" <<'PY'
import hashlib, json, sys, urllib.request
u = json.load(open(sys.argv[1]))["uninstall_linux"]
data = urllib.request.urlopen(urllib.request.Request(u["url"], headers={"User-Agent": "OctoberJourneySetup/1.0"}), timeout=60).read()
if hashlib.sha256(data).hexdigest().upper() != u["sha256"].upper(): sys.exit("it does not match its checksum")
open(sys.argv[2], "wb").write(data)
PY
then
  mkdir -p "$app_dir" "$menu_dir"
  install -m 755 "$work/uninstall.sh" "$app_dir/uninstall.sh"
  cat > "$menu_dir/$KEY-uninstall.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Uninstall $PACK
Comment=Remove the $PACK Minecraft setup (Steam shortcut, art, controller layout, Prism instance)
Exec=bash "$app_dir/uninstall.sh" --pause
Icon=edit-delete
Terminal=true
Categories=Game;
EOF
  say "uninstaller saved: $app_dir/uninstall.sh (in the app menu as \"Uninstall $PACK\")."
else
  say "uninstaller not added; it can be downloaded from the release page."
fi

# This installer, saved so the choices can be changed later (only when it was run from a file).
if [ "$C_installer" = 1 ]; then
  if [ -f "$0" ]; then
    mkdir -p "$app_dir" "$menu_dir"
    [ "$(readlink -f "$0")" = "$(readlink -f "$app_dir/setup.sh" 2>/dev/null || true)" ] || install -m 755 "$0" "$app_dir/setup.sh"
    cat > "$menu_dir/$KEY-setup.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$PACK setup
Comment=Add $PACK items (Steam shortcut, art, controller layout, server entry) or update the setup
Exec=bash "$app_dir/setup.sh" --pause
Icon=system-software-install
Terminal=true
Categories=Game;
EOF
    say "installer saved: $app_dir/setup.sh (in the app menu as \"$PACK setup\")."
  else
    say "installer not saved: it wasn't run from a file."
  fi
fi
if [ "$PAUSE" = 1 ]; then read -r -p "Press Enter to close. " _ || true; fi
