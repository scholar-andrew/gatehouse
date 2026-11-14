#!/bin/bash
# October Journey uninstaller for Linux, including the Steam Deck: undoes what october-journey-setup.sh did. Run it in
# Desktop Mode, from Konsole:
#   bash october-journey-uninstall.sh
# It removes, for every Steam account on this device:
#   - Steam shortcuts named "Minecraft (Prism Launcher)..." that start Prism Launcher (shortcuts.vdf is backed up first
#     as shortcuts.vdf.bak-uninstall), with their library art and their controller layout folder and list entry;
#   - the two folder permissions setup gave Prism's Flatpak (Steam's art folder and controller layout folder), and no
#     other Flatpak override;
# and then, after asking:
#   - the Prism instance ~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/october-journey
#     (screenshots and any single-player worlds in it go too; answer n to keep it);
#   - Prism Launcher itself (default no; it is shared with any other Prism instances you have);
# and last, its own copy in ~/.local/share/october-journey and its app-menu entry (put there by setup).
# Steam must be closed while its files are changed; it asks before closing it. Nothing is downloaded.
# Flags: --yes (no questions: removes the shortcut, art, layout, permissions and instance, keeps Prism; stops if Steam
#        is running)  --keep-instance  --remove-prism  --pause (wait for Enter at the end; used by the app-menu entry)
#        --prism-root=DIR --steam-root=DIR (testing: use these folders instead of the real ones; Flatpak is not touched)
set -euo pipefail
APP=org.prismlauncher.PrismLauncher
YES=0; KEEP_INSTANCE=0; REMOVE_PRISM=0; PAUSE=0; PRISM_ROOT=""; STEAM_ROOT=""; TESTING=0
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;;
    --keep-instance) KEEP_INSTANCE=1 ;;
    --remove-prism) REMOVE_PRISM=1 ;;
    --pause) PAUSE=1 ;;
    --prism-root=*) PRISM_ROOT="${a#*=}"; TESTING=1 ;;
    --steam-root=*) STEAM_ROOT="${a#*=}"; TESTING=1 ;;
  esac
done
say() { echo "October Journey uninstall: $*"; }
ask() {  # ask "question" default(y/n)
  if [ "$YES" = 1 ]; then [ "$2" = y ]; return; fi
  local hint="[y/N]"; [ "$2" = y ] && hint="[Y/n]"
  read -r -p "$1 $hint " ans
  case "${ans:-$2}" in y|Y|yes) return 0 ;; *) return 1 ;; esac
}

[ -n "$PRISM_ROOT" ] || PRISM_ROOT="$HOME/.var/app/$APP/data/PrismLauncher"
inst="$PRISM_ROOT/instances/october-journey"
echo "This removes October Journey from this device: the Steam shortcut \"Minecraft (Prism Launcher) - ...\", its"
echo "library art and controller layout, Prism's two Steam folder permissions, and (after asking) the Prism instance."
echo "Prism Launcher itself is only removed if you say so."
ask "Continue?" y || { say "cancelled."; exit 1; }

# 1. Steam must be closed while its shortcut list is changed.
if [ -z "$STEAM_ROOT" ]; then
  STEAM_ROOT="$HOME/.local/share/Steam"
  while pgrep -x steam >/dev/null 2>&1; do
    if [ "$YES" = 1 ]; then say "Steam is running; quit Steam and run the uninstaller again."; exit 1; fi
    ask "Steam has to be closed while its shortcut list is changed. Close Steam now?" y || { say "cancelled."; exit 1; }
    steam -shutdown >/dev/null 2>&1 || true
    for i in $(seq 1 30); do pgrep -x steam >/dev/null 2>&1 || break; sleep 2; done
  done
fi

# 2. Shortcuts, art and controller layouts, for every Steam account here.
if [ -d "$STEAM_ROOT/userdata" ]; then
python3 - "$STEAM_ROOT" "$APP" <<'PY'
import os, re, shutil, struct, sys
steam, app = sys.argv[1], sys.argv[2]
PREFIX = "Minecraft (Prism Launcher)"
def say(s): print("October Journey uninstall:", s)
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
for acct in sorted(os.listdir(os.path.join(steam, "userdata"))):
    cdir = os.path.join(steam, "userdata", acct, "config"); svdf = os.path.join(cdir, "shortcuts.vdf")
    if not os.path.isfile(svdf): continue
    root, _ = rmap(open(svdf, "rb").read(), 0)
    top = next((x for x in root if x[1].lower() == "shortcuts"), None)
    if not top: continue
    keep, gone = [], []
    for item in top[2]:
        e = item[2]; name = field(e, "AppName") or ""
        prism = "prismlauncher" in (field(e, "Exe") or "").lower() or app.lower() in (field(e, "LaunchOptions") or "").lower()
        (gone if prism and name.startswith(PREFIX) else keep).append(item)
    if not gone: continue
    for n, item in enumerate(keep): item[1] = str(n)
    top[2] = keep
    shutil.copy2(svdf, svdf + ".bak-uninstall"); open(svdf, "wb").write(wmap(root))
    for item in gone:
        name = field(item[2], "AppName"); appid = (field(item[2], "appid") or 0) & 0xFFFFFFFF
        say('removed Steam shortcut "%s" (account %s).' % (name, acct))
        n = 0
        for f in ("%dp.png", "%d_hero.png", "%d_logo.png", "%d_icon.png", "%d.png"):
            for p in (os.path.join(cdir, "grid", f % appid), os.path.join(cdir, "grid", f % appid) + ".before-pack"):
                if os.path.isfile(p): os.remove(p); n += 1
        say("removed %d library art file(s)." % n)
        croot = os.path.join(steam, "steamapps", "common", "Steam Controller Configs", acct, "config")
        d = os.path.join(croot, name.lower())
        if os.path.isdir(d): shutil.rmtree(d); say("removed the controller layout folder.")
        cs = os.path.join(croot, "configset_controller_neptune.vdf")
        if os.path.isfile(cs):
            text = open(cs).read()
            new = re.sub(r'[ \t]*"%s"\s*\{[^{}]*\}[ \t]*\n?' % re.escape(name.lower()), "", text, flags=re.I)
            if new != text: open(cs, "w").write(new); say("removed it from Steam's controller layout list.")
PY
else
  say "no Steam folder at $STEAM_ROOT; nothing to remove there."
fi

# 3. The two Flatpak folder permissions setup added (only those; other overrides stay).
ov="$HOME/.local/share/flatpak/overrides/$APP"
if [ "$TESTING" = 0 ] && [ -f "$ov" ]; then
python3 - "$ov" <<'PY'
import re, sys
p = sys.argv[1]; lines = open(p).read().splitlines(); out = []; dropped = 0
for l in lines:
    if l.startswith("filesystems="):
        parts = [x for x in l[len("filesystems="):].split(";") if x]
        keep = [x for x in parts if not re.search(r"Steam/userdata/\d+/config/grid$|Steam Controller Configs/\d+/config$", x)]
        dropped += len(parts) - len(keep)
        if keep: out.append("filesystems=" + ";".join(keep) + ";")
        continue
    out.append(l)
open(p, "w").write("\n".join(out) + "\n")
print("October Journey uninstall: removed %d Flatpak folder permission(s) from Prism." % dropped)
PY
fi

# 4. The instance.
if [ ! -d "$inst" ]; then say "no October Journey instance at $inst."
elif [ "$KEEP_INSTANCE" = 0 ] && ask "Delete the October Journey instance at $inst? Its screenshots and any single-player worlds are deleted with it." y; then
  rm -rf "$inst"; say "deleted the instance."
else say "kept the instance at $inst."; fi

# 5. Prism Launcher itself.
if [ "$TESTING" = 1 ]; then say "testing: Prism Launcher left installed."
elif ! flatpak info --user "$APP" >/dev/null 2>&1; then say "Prism Launcher isn't installed for this user; left alone."
elif [ "$REMOVE_PRISM" = 1 ] || { [ "$YES" = 0 ] && ask "Also uninstall Prism Launcher? Choose n if you use Prism for anything else." n; }; then
  flatpak uninstall --user -y "$APP"; say "Prism Launcher uninstalled."
else say "kept Prism Launcher."; fi

# 6. Its own saved copy and app-menu entry (bash has this file open, so removing it is safe).
if [ "$TESTING" = 1 ]; then app_dir="$PRISM_ROOT/october-journey"; menu_dir="$PRISM_ROOT/applications"
else app_dir="$HOME/.local/share/october-journey"; menu_dir="$HOME/.local/share/applications"; fi
if [ -f "$menu_dir/october-journey-uninstall.desktop" ]; then rm -f "$menu_dir/october-journey-uninstall.desktop"; say "removed the app-menu entry."; fi
if [ -d "$app_dir" ]; then rm -rf "$app_dir"; say "removed the saved uninstaller."; fi

say "done. Start Steam again (or switch to Game Mode) whenever you like."
if [ "$PAUSE" = 1 ]; then read -r -p "Press Enter to close. " _ || true; fi
