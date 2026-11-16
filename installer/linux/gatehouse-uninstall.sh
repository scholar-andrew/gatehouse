#!/bin/bash
# Gatehouse uninstaller for Linux. It still knows one pack, October Journey (its instance, shortcut names and server,
# and the folder and app-menu files setup saves under that name): it works offline, without a profile to read.
# For Linux, including the Steam Deck: undoes what gatehouse-setup.sh (once october-journey-setup.sh) did, item by
# item. Run it from a terminal (on a Steam Deck: in Desktop Mode, from Konsole), or from the app menu ("Uninstall October
# Journey"):
#   bash gatehouse-uninstall.sh
# It first offers a quick uninstall (everything it finds except Prism Launcher); otherwise it asks about each item it
# finds (Enter = yes, except Prism Launcher). It finds the instance in Prism's own instances folder, and the saved
# installer and uninstaller wherever setup put them (from their app-menu entries). The items:
#   shortcut     Steam shortcuts "Minecraft (Prism Launcher) - ..." and "October Journey - ..." that start Prism (every Steam account; shortcuts.vdf is
#                backed up first as shortcuts.vdf.bak-uninstall). Their art and controller layout go with them.
#   art          the shortcut's library art (and the .before-pack copies the pack sync kept)
#   controller   the shortcut's controller layout folder and its entry in Steam's layout list
#   permissions  the two Steam folder permissions setup gave Prism's Flatpak (no other Flatpak setting is touched)
#   instance     the Prism instance (screenshots and any single-player worlds in it go too); the server entry goes with it
#   server       the October Journey server in the instance's server list
#   prism        Prism Launcher itself (default no; shared with any other Prism instances)
#   installer    the installer saved in ~/.local/share/october-journey, and its app-menu entry
#   uninstaller  this uninstaller's saved copy and its app-menu entry
# What's kept stays as it is; with the instance kept, the pack sync is told not to put back what was removed
# (pack-options.json). Steam must be closed while its files change; it asks before closing it. Nothing is downloaded.
# Flags: --yes (no questions: everything found except Prism; stops if Steam is running)  --skip=item,item (keep those)
#        --keep-instance  --remove-prism  --pause (wait for Enter at the end; used by the app-menu entry)
#        --prism-root=DIR --steam-root=DIR (testing: use these folders instead of the real ones; Flatpak is not touched)
set -euo pipefail
APP=org.prismlauncher.PrismLauncher
YES=0; PAUSE=0; QUICK=0; PRISM_ROOT=""; STEAM_ROOT=""; TESTING=0; SKIP=","; REMOVE_PRISM=0
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;;
    --skip=*) SKIP="$SKIP${a#*=}," ;;
    --keep-instance) SKIP="${SKIP}instance," ;;
    --remove-prism) REMOVE_PRISM=1 ;;
    --pause) PAUSE=1 ;;
    --prism-root=*) PRISM_ROOT="${a#*=}"; TESTING=1 ;;
    --steam-root=*) STEAM_ROOT="${a#*=}"; TESTING=1 ;;
    --profile-file=*) PROFILE_FILE="${a#*=}" ;;
  esac
done
say() { echo "Gatehouse uninstall: $*"; }
# Which pack this removes comes from the profile setup saved beside this script (gatehouse-profile.json: the pack's
# name, its instance, its two shortcut names, its servers, and the name it is filed under). Without that file it is an
# install from before setup saved one, which was always October Journey: the values here.
KEY="october-journey"; PACK="October Journey"; INST_ID="october-journey"
me_dir="$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")"
[ -n "${PROFILE_FILE:-}" ] || PROFILE_FILE="$me_dir/gatehouse-profile.json"
export GH_PROFILE=""
if [ -f "$PROFILE_FILE" ]; then
  pf() { python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$PROFILE_FILE" "$1"; }
  KEY="$(pf key)"; PACK="$(pf pack_name)"; INST_ID="$(pf instance_id)"
  export GH_PROFILE="$PROFILE_FILE"
fi
say "$PACK (filed as $KEY)"
finish() { if [ "$PAUSE" = 1 ]; then read -r -p "Press Enter to close. " _ || true; fi; }
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

[ -n "$PRISM_ROOT" ] || PRISM_ROOT="$HOME/.var/app/$APP/data/PrismLauncher"
[ -n "$STEAM_ROOT" ] || STEAM_ROOT="$HOME/.local/share/Steam"
inst="$(instances_dir "$PRISM_ROOT")/$INST_ID"; mc="$inst/minecraft"
if [ "$TESTING" = 1 ]; then menu_dir="$PRISM_ROOT/applications"; app_dir="$PRISM_ROOT/$KEY"
else menu_dir="$HOME/.local/share/applications"; app_dir="$HOME/.local/share/$KEY"; fi
# The shortcut that just opens Prism Launcher is shared by every pack set up here: it stays while another pack's
# instance is still installed.
export GH_KEEP_PRISM_SHORTCUT=0
for d in "$(dirname "$inst")"/*/; do
  if [ "$(basename "$d")" != "$INST_ID" ] && [ -f "$d/minecraft/pack-sync.sh" ]; then GH_KEEP_PRISM_SHORTCUT=1; fi
done
[ "$GH_KEEP_PRISM_SHORTCUT" = 0 ] || say "another pack is installed here too: its Prism Launcher shortcut is left alone."
# Setup may have saved them elsewhere: its app-menu entries say where; or this is the saved copy itself.
for f in "$menu_dir/$KEY-uninstall.desktop" "$menu_dir/$KEY-setup.desktop"; do
  if [ -f "$f" ]; then p="$(sed -n 's/^Exec=bash "\(.*\)" --pause$/\1/p' "$f" | head -1)"; [ -n "$p" ] && { app_dir="$(dirname "$p")"; break; }; fi
done
me="$(readlink -f "$0" 2>/dev/null || true)"
if [ -n "$me" ] && [ "$(basename "$me")" = uninstall.sh ] && [ ! -f "$app_dir/uninstall.sh" ]; then app_dir="$(dirname "$me")"; fi
ov="$HOME/.local/share/flatpak/overrides/$APP"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

# Steam's binary shortcuts.vdf and Minecraft's servers.dat, for both finding and removing.
cat > "$work/lib.py" <<'PY'
import json, os, re, shutil, struct
_P = json.load(open(os.environ["GH_PROFILE"])) if os.environ.get("GH_PROFILE") else None   # (the saved profile, if any)
# Setup's two shortcuts, each " - <device>": the pack's own, and the launcher's (under the names it has had: the
# Gatehouse launcher, and Prism Launcher's from installs set up before that). The launcher's stays when another pack
# is installed here too.
PREFIX = (_P["game_shortcut_name"] if _P else "October Journey",)
if os.environ.get("GH_KEEP_PRISM_SHORTCUT") != "1": PREFIX += ((_P["shortcut_name"],) if _P else ()) + ("Gatehouse Minecraft Launcher", "Minecraft (Prism Launcher)")
DEFAULT_SERVERS = _P["servers"] if _P else [{"name": "October Journey", "address": "100.91.134.102:25565"}]
APP = "org.prismlauncher.PrismLauncher"
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
def field(e, k):
    f = next((x for x in e if x[1].lower() == k.lower()), None)
    return f[2] if f else None
def ours(e):
    prism = "prismlauncher" in (field(e, "Exe") or "").lower() or APP.lower() in (field(e, "LaunchOptions") or "").lower()
    return prism and (field(e, "AppName") or "").startswith(PREFIX)
def shortcuts(steam, remove=False):
    """[(account, name, appid)] for our shortcuts; with remove=True also takes them out of shortcuts.vdf."""
    found = []
    ud = os.path.join(steam, "userdata")
    for acct in sorted(os.listdir(ud)) if os.path.isdir(ud) else []:
        svdf = os.path.join(ud, acct, "config", "shortcuts.vdf")
        if not os.path.isfile(svdf): continue
        root, _ = rmap(open(svdf, "rb").read(), 0)
        top = next((x for x in root if x[1].lower() == "shortcuts"), None)
        if not top: continue
        mine = [it for it in top[2] if ours(it[2])]
        found += [(acct, field(it[2], "AppName"), (field(it[2], "appid") or 0) & 0xFFFFFFFF) for it in mine]
        if remove and mine:
            keep = [it for it in top[2] if not ours(it[2])]
            for n, it in enumerate(keep): it[1] = str(n)
            top[2] = keep
            shutil.copy2(svdf, svdf + ".bak-uninstall"); open(svdf, "wb").write(wmap(root))
    return found
def art_files(steam, acct, appid):
    g = os.path.join(steam, "userdata", acct, "config", "grid")
    for f in ("%dp.png", "%d_hero.png", "%d_logo.png", "%d_icon.png", "%d.png"):
        yield os.path.join(g, f % appid); yield os.path.join(g, f % appid) + ".before-pack"
def layout_root(steam, acct):
    return os.path.join(steam, "steamapps", "common", "Steam Controller Configs", acct, "config")
def nbt_read(b, i, t):
    if t == 1: return b[i], i + 1
    if t == 2: return b[i:i+2], i + 2
    if t in (3, 5): return b[i:i+4], i + 4
    if t in (4, 6): return b[i:i+8], i + 8
    if t == 7: n = struct.unpack(">i", b[i:i+4])[0]; return b[i:i+4+n], i + 4 + n
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
    if t in (2, 3, 4, 5, 6, 7, 11, 12): return v
    if t == 8: e = v.encode("utf-8"); return struct.pack(">H", len(e)) + e
    if t == 9: lt, items = v; return bytes([lt if items else 0]) + struct.pack(">i", len(items)) + b"".join(nbt_write(lt, x) for x in items)
    if t == 10: return b"".join(bytes([ct]) + nbt_write(8, n) + nbt_write(ct, x) for ct, n, x in v) + b"\x00"
def our_servers(mc):
    try: s = json.load(open(os.path.join(mc, "extras", "servers.json")))
    except Exception: s = DEFAULT_SERVERS
    return {x["address"].lower() for x in s}, {x["name"] for x in s} | {x["name"] for x in DEFAULT_SERVERS}
def servers(mc, remove=False):
    """How many of our entries servers.dat has; with remove=True takes them out."""
    sd = os.path.join(mc, "servers.dat")
    if not os.path.isfile(sd): return 0
    b = open(sd, "rb").read(); root, _ = nbt_read(b, 1 + 2 + struct.unpack(">H", b[1:3])[0], 10)
    addrs, names = our_servers(mc)
    lst = next((x for x in root if x[1] == "servers"), None)
    if not lst: return 0
    lt, items = lst[2]
    hit = lambda e: any(ct == 8 and ((n == "ip" and v.lower() in addrs) or (n == "name" and v in names)) for ct, n, v in e)
    gone = [e for e in items if hit(e)]
    if remove and gone:
        root[root.index(lst)] = (9, "servers", (lt, [e for e in items if not hit(e)]))
        open(sd, "wb").write(b"\x0a" + nbt_write(8, "") + nbt_write(10, root))
    return len(gone)
PERM = re.compile(r"Steam/userdata/\d+/config/grid$|Steam Controller Configs/\d+/config$")
PY

# What's here.
eval "$(python3 - "$work" "$STEAM_ROOT" "$mc" "$ov" <<'PY'
import os, sys, shlex
sys.path.insert(0, sys.argv[1]); from lib import *
steam, mc, ov = sys.argv[2], sys.argv[3], sys.argv[4]
found = shortcuts(steam)
art = any(os.path.isfile(p) for a, n, i in found for p in art_files(steam, a, i))
lay = any(os.path.isdir(os.path.join(layout_root(steam, a), n.lower())) for a, n, i in found)
perms = 0
if os.path.isfile(ov):
    for l in open(ov):
        if l.startswith("filesystems="): perms = sum(1 for x in l[12:].strip().split(";") if x and PERM.search(x))
try: srv = servers(mc)
except Exception: srv = 0
print("F_SHORTCUTS=%s" % shlex.quote(", ".join('"%s"' % n for a, n, i in found)))
print("F_ART=%d F_LAYOUT=%d F_PERMS=%d F_SERVER=%d" % (art, lay, 1 if perms else 0, 1 if srv else 0))
PY
)"
F_INSTANCE=0; [ -d "$inst" ] && F_INSTANCE=1
[ "$F_INSTANCE" = 1 ] || F_SERVER=0
[ "$TESTING" = 0 ] || F_PERMS=0
F_PRISM=0; if [ "$TESTING" = 0 ] && flatpak info --user "$APP" >/dev/null 2>&1; then F_PRISM=1; fi
F_SETUP=0; { [ -f "$app_dir/setup.sh" ] || [ -f "$menu_dir/$KEY-setup.desktop" ]; } && F_SETUP=1
F_SELF=0; { [ -f "$app_dir/uninstall.sh" ] || [ -f "$menu_dir/$KEY-uninstall.desktop" ]; } && F_SELF=1

if [ -z "$F_SHORTCUTS" ] && [ "$F_ART$F_LAYOUT$F_PERMS$F_INSTANCE$F_SERVER$F_PRISM$F_SETUP$F_SELF" = 00000000 ]; then
  say "nothing of $PACK was found on this device."; finish; exit 0
fi

# What to remove.
if [ "$YES" = 0 ]; then
  found="$([ -z "$F_SHORTCUTS" ] || echo -n "the Steam shortcut $F_SHORTCUTS with its art and layout, ")$([ "$F_PERMS" = 0 ] || echo -n "Prism's Steam folder permissions, ")$([ "$F_INSTANCE" = 0 ] || echo -n "the game instance, ")$([ "$F_SETUP$F_SELF" = 00 ] || echo -n "the saved installer and uninstaller, ")"
  read -r -p "Quick uninstall: remove ${found%, } (Prism Launcher stays)? [Y/n] " ans
  case "${ans:-y}" in y|Y|yes) QUICK=1 ;; *) echo "Choose what to remove (Enter = the answer in capitals). Anything you answer no to stays as it is." ;; esac
fi
C_shortcut=0; C_art=0; C_controller=0; C_permissions=0; C_instance=0; C_server=0; C_prism=0; C_installer=0; C_uninstaller=0
[ -z "$F_SHORTCUTS" ] || choose shortcut "Remove the Steam library shortcut $F_SHORTCUTS (its art and controller layout go with it)?" y
if [ "$C_shortcut" = 1 ]; then C_art=$F_ART; C_controller=$F_LAYOUT
else
  [ "$F_ART" = 0 ] || choose art "Remove the shortcut's Steam library art?" y
  [ "$F_LAYOUT" = 0 ] || choose controller "Remove the shortcut's Steam Deck controller layout?" y
fi
[ "$F_PERMS" = 0 ] || choose permissions "Remove Prism's permission to write Steam's art and controller layout folders?" y
[ "$F_INSTANCE" = 0 ] || choose instance "Delete the game instance at $inst? Its screenshots and any single-player worlds go with it." y
if [ "$C_instance" = 1 ]; then C_server=0
elif [ "$F_SERVER" = 1 ]; then choose server "Remove the $PACK server from the server list?" y; fi
if [ "$F_PRISM" = 1 ]; then
  if [ "$REMOVE_PRISM" = 1 ]; then C_prism=1; else choose prism "Uninstall Prism Launcher? (say no if you use Prism for anything else)" n; fi
fi
[ "$F_SETUP" = 0 ] || choose installer "Remove the saved installer and its app-menu entry?" y
[ "$F_SELF" = 0 ] || choose uninstaller "Remove this uninstaller and its app-menu entry?" y
say "removing: shortcut=$C_shortcut art=$C_art controller=$C_controller permissions=$C_permissions instance=$C_instance server=$C_server prism=$C_prism installer=$C_installer uninstaller=$C_uninstaller"

# Steam must be closed while its files are changed.
if [ "$TESTING" = 0 ] && [ "$C_shortcut$C_art$C_controller" != 000 ]; then
  while pgrep -x steam >/dev/null 2>&1; do
    if [ "$YES" = 1 ]; then say "Steam is running; quit Steam and run the uninstaller again."; exit 1; fi
    read -r -p "Steam has to be closed while its files are changed. Close Steam now? [Y/n] " ans
    case "$ans" in n|N) say "cancelled."; finish; exit 1 ;; esac
    steam -shutdown >/dev/null 2>&1 || true
    for i in $(seq 1 30); do pgrep -x steam >/dev/null 2>&1 || break; sleep 2; done
  done
fi

# Steam shortcut, art and layout; the server entry and what the pack sync may put back.
python3 - "$work" "$STEAM_ROOT" "$mc" "$C_shortcut$C_art$C_controller$C_instance$C_server" <<'PY'
import json, os, re, shutil, sys
sys.path.insert(0, sys.argv[1]); from lib import *
steam, mc = sys.argv[2], sys.argv[3]
c_short, c_art, c_ctrl, c_inst, c_srv = (x == "1" for x in sys.argv[4])
def say(s): print("Gatehouse uninstall:", s)
found = shortcuts(steam, remove=c_short)
for acct, name, appid in found:
    if c_short: say('removed Steam shortcut "%s" (account %s).' % (name, acct))
    if c_art:
        n = 0
        for p in art_files(steam, acct, appid):
            if os.path.isfile(p): os.remove(p); n += 1
        if n: say("removed %d library art file(s)." % n)
    if c_ctrl:
        root = layout_root(steam, acct); d = os.path.join(root, name.lower())
        if os.path.isdir(d): shutil.rmtree(d); say("removed the controller layout folder.")
        cs = os.path.join(root, "configset_controller_neptune.vdf")
        if os.path.isfile(cs):
            text = open(cs).read()
            new = re.sub(r'[ \t]*"%s"\s*\{[^{}]*\}[ \t]*\n?' % re.escape(name.lower()), "", text, flags=re.I)
            if new != text: open(cs, "w").write(new); say("removed it from Steam's controller layout list.")
if os.path.isdir(mc) and not c_inst:
    if c_srv: say("removed %d server list entr(y/ies)." % servers(mc, remove=True))
    po = {"steam_art": True, "steam_controller": True, "server_entry": True}
    try: po.update(json.load(open(os.path.join(mc, "pack-options.json"))))
    except Exception: pass
    if c_art or c_short: po["steam_art"] = False
    if c_ctrl or c_short: po["steam_controller"] = False
    if c_srv: po["server_entry"] = False
    json.dump(po, open(os.path.join(mc, "pack-options.json"), "w"))
    if c_short and os.path.isfile(os.path.join(mc, "steam-target.json")): os.remove(os.path.join(mc, "steam-target.json"))
PY

# The two Flatpak folder permissions setup added (only those; other overrides stay).
if [ "$C_permissions" = 1 ]; then
python3 - "$work" "$ov" <<'PY'
import sys
sys.path.insert(0, sys.argv[1]); from lib import PERM
p = sys.argv[2]; out = []; dropped = 0
for l in open(p).read().splitlines():
    if l.startswith("filesystems="):
        parts = [x for x in l[len("filesystems="):].split(";") if x]
        keep = [x for x in parts if not PERM.search(x)]
        dropped += len(parts) - len(keep)
        if keep: out.append("filesystems=" + ";".join(keep) + ";")
        continue
    out.append(l)
open(p, "w").write("\n".join(out) + "\n")
print("Gatehouse uninstall: removed %d Flatpak folder permission(s) from Prism." % dropped)
PY
fi

# The instance.
if [ "$C_instance" = 1 ]; then rm -rf "$inst"; say "deleted the instance."
elif [ "$F_INSTANCE" = 1 ]; then say "kept the instance; updates on launch won't put back what was removed."; fi

# Prism Launcher itself.
if [ "$C_prism" = 1 ]; then flatpak uninstall --user -y "$APP"; say "Prism Launcher uninstalled."; fi

# The saved installer and this uninstaller (bash has this file open, so removing it is safe).
if [ "$C_installer" = 1 ]; then rm -f "$app_dir/setup.sh" "$menu_dir/$KEY-setup.desktop"; say "removed the saved installer."; fi
if [ "$C_uninstaller" = 1 ]; then rm -f "$app_dir/uninstall.sh" "$app_dir/gatehouse-profile.json" "$menu_dir/$KEY-uninstall.desktop"; say "removed the saved uninstaller."; fi
rmdir "$app_dir" 2>/dev/null || true

say "done.$([ "$C_shortcut$C_art$C_controller" = 000 ] || echo " Start Steam again (or switch to Game Mode) whenever you like.")"
finish
