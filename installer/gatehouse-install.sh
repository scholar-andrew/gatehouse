#!/bin/bash
# Gatehouse installer and uninstaller for Linux, including Steam Deck. A plain script: read it before you run it.
#
# It installs Gatehouse, the launcher players use, by itself: no game and no server pack. In Gatehouse you add a
# Gatekeeper with what its owner gave you, and install its servers from there.
#
# With --gatekeeper it installs Gatekeeper instead, the program for the machine that runs the servers. Note that
# Gatekeeper runs servers through MC Server Soft, which is Windows only, so on Linux it can't host yet. It fetches the published Gatekeeper, checks it against its published SHA-256,
# fetches install-keeper.sh from the repository, and runs that, which says what it does (a systemd user service, a
# menu entry, files in ~/.local/share/GatehouseKeeper; no system password). --gatekeeper --uninstall removes it.
# Not supported on Linux or SteamOS: adding Gatekeeper to your Steam library.
#
# What it does to this computer, and nothing else:
#   - Prism Launcher, only if it's missing and you agree: installed for your user from Flathub (flatpak).
#   - Files in <Prism Launcher's folder>/gatehouse/app: the launcher, the scripts that start it, where to look for a
#     newer launcher, art for Steam, and a copy of this script. Each file fetched is checked against the published
#     SHA-256 before it is kept.
#   - A menu entry, "Gatehouse" (~/.local/share/applications/gatehouse.desktop), which runs one of those scripts
#     inside Prism Launcher's Flatpak.
#   - Steam, if you say yes: "Gatehouse" is added to your Steam library, with its art, so it is there in Game Mode on
#     a Steam Deck. Steam has to be closed while that is done (it writes its own list of shortcuts when it exits);
#     this script never closes or starts Steam. Steam's list as it was is kept beside it as
#     shortcuts.vdf.bak-gatehouse. Needs python3 (a Steam Deck has it).
# Nothing runs in the background, nothing needs the system password, and nothing is sent anywhere.
#
# --uninstall takes out again what this put in: the Steam shortcut, the menu entry and Gatehouse's files. Prism
# Launcher and anything installed in it are left alone. With --purge it also removes what Gatehouse remembered (its
# Gatekeepers, and this device's identity with them).
#
# Run it:   bash gatehouse-install.sh
# Options:  --gatekeeper            Gatekeeper (for the machine that runs the servers) instead of Gatehouse
#           --uninstall [--purge]   remove instead of install
#           --steam / --no-steam    add to Steam without being asked / don't
#           --with-pack             (with --uninstall) this Gatehouse came with a pack: take out what is its own anyway
#           --yes                   answer yes to the questions; Steam is then only touched if it is already closed
#           --no-prism              don't offer Prism Launcher
#           --no-menu               no menu entry
#           --source=URL            where Gatehouse is published (default: its GitHub repository)
#           --prism-root=DIR        Prism Launcher's data folder (default: its Flatpak's)
#           --steam-config=DIR      Steam's userdata/<account>/config folder (default: found in your home folder)
#           --dry-run               say what would be done, and do none of it
set -u
REPO="https://raw.githubusercontent.com/scholar-andrew/gatehouse/main"
APP_ID="org.prismlauncher.PrismLauncher"
YES=0; NO_PRISM=0; NO_MENU=0; DRY=0; SOURCE=""; PRISM_ROOT=""; UNINSTALL=0; PURGE=0; WITH_PACK=0; GATEKEEPER=0; STEAM=0; NO_STEAM=0; STEAM_CONFIG=""; ROOT_GIVEN=0
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;; --no-prism) NO_PRISM=1 ;; --no-menu) NO_MENU=1 ;; --dry-run) DRY=1 ;;
    --uninstall) UNINSTALL=1 ;; --purge) PURGE=1 ;; --with-pack) WITH_PACK=1 ;; --gatekeeper) GATEKEEPER=1 ;; --steam) STEAM=1 ;; --no-steam) NO_STEAM=1 ;;
    --source=*) SOURCE="${a#*=}" ;; --prism-root=*) PRISM_ROOT="${a#*=}"; ROOT_GIVEN=1 ;; --steam-config=*) STEAM_CONFIG="${a#*=}" ;;
    *) echo "Unknown option: $a"; exit 2 ;;
  esac
done
[ -n "$SOURCE" ] || SOURCE="$REPO/dist"
SOURCE="${SOURCE%/}"
say() { echo "$*"; }
ask() { [ "$YES" = 1 ] && return 0; local r; read -r -p "$1 [Y/n] " r; case "$r" in n*|N*) return 1 ;; *) return 0 ;; esac; }
stop() { say ""; say "Stopped: $*"; exit 1; }
# ------------------------------------------------------------------ Gatekeeper (the machine that runs the servers)
if [ "$GATEKEEPER" = 1 ]; then
  say "Gatekeeper (for the machine that runs the servers)"
  pass=""; [ "$YES" = 1 ] && pass="--yes"
  kept="${XDG_DATA_HOME:-$HOME/.local/share}/GatehouseKeeper/install-keeper.sh"
  if [ "$UNINSTALL" = 1 ]; then
    [ "$PURGE" = 1 ] && pass="$pass --purge"
    if [ "$DRY" = 1 ]; then say "  would run install-keeper.sh --uninstall$([ "$PURGE" = 1 ] && echo " --purge") (its own copy in $(dirname "$kept"), else fetched from $REPO/installer/keeper): it stops Gatekeeper and removes its service, its menu entry and its program."; say "Gatekeeper: nothing was changed (--dry-run)."; exit 0; fi
    if [ -f "$kept" ]; then bash "$kept" --uninstall $pass; exit $?; fi
    command -v curl >/dev/null 2>&1 || stop "curl is needed and wasn't found."
    work="$(mktemp -d)" || stop "couldn't make a temporary folder."
    curl -fsSL --max-time 60 "$REPO/installer/keeper/install-keeper.sh" -o "$work/install-keeper.sh" || { rm -rf "$work"; stop "couldn't fetch install-keeper.sh."; }
    bash "$work/install-keeper.sh" --uninstall $pass; code=$?; rm -rf "$work"; exit $code
  fi
  if [ "$DRY" = 1 ]; then
    say "  would read $SOURCE/gatekeeper/gatekeeper.json, fetch $SOURCE/gatekeeper/oj-host.jar (checked against it) and $REPO/installer/keeper/install-keeper.sh,"
    say "  and run install-keeper.sh, which installs Gatekeeper and says what it does."
    say "Not supported on Linux or SteamOS: adding Gatekeeper to your Steam library."
    say "Gatekeeper: nothing was changed (--dry-run)."; exit 0
  fi
  command -v curl >/dev/null 2>&1 || stop "curl is needed and wasn't found."
  command -v sha256sum >/dev/null 2>&1 || stop "sha256sum is needed and wasn't found."
  work="$(mktemp -d)" || stop "couldn't make a temporary folder."
  curl -fsSL --max-time 30 "$SOURCE/gatekeeper/gatekeeper.json" -o "$work/gatekeeper.json" || { rm -rf "$work"; stop "couldn't reach $SOURCE."; }
  want="$(tr -d '\r\n' < "$work/gatekeeper.json" | sed -n 's/.*"sha256": *"\([0-9a-fA-F]*\)".*/\1/p')"
  [ -n "$want" ] || { rm -rf "$work"; stop "what is published there doesn't say what Gatekeeper's checksum is."; }
  curl -fsSL --max-time 120 "$SOURCE/gatekeeper/oj-host.jar" -o "$work/oj-host.jar" || { rm -rf "$work"; stop "couldn't fetch Gatekeeper."; }
  got="$(sha256sum "$work/oj-host.jar" | cut -d' ' -f1)"
  [ "$(echo "$got" | tr 'A-F' 'a-f')" = "$(echo "$want" | tr 'A-F' 'a-f')" ] || { rm -rf "$work"; stop "what was fetched doesn't match the published checksum. Nothing was installed."; }
  curl -fsSL --max-time 60 "$REPO/installer/keeper/install-keeper.sh" -o "$work/install-keeper.sh" || { rm -rf "$work"; stop "couldn't fetch install-keeper.sh."; }
  say "  Gatekeeper fetched and checked. Its own install script takes over:"
  say ""
  bash "$work/install-keeper.sh" $pass; code=$?; rm -rf "$work"
  say "Not supported on Linux or SteamOS: adding Gatekeeper to your Steam library."
  exit $code
fi

# The Steam Deck gets the Deck's build of what is published (the same launcher; the folder differs).
variant=main
case "$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null)" in Jupiter|Galileo) variant=deck ;; esac
flatpak_prism=0
if command -v flatpak >/dev/null 2>&1 && flatpak info "$APP_ID" >/dev/null 2>&1; then flatpak_prism=1; fi
desktop="${XDG_DATA_HOME:-$HOME/.local/share}/applications/gatehouse.desktop"

# ---- Steam: its list of shortcuts is a binary file (userdata/<account>/config/shortcuts.vdf), read and written here
# with python3. Steam is never closed or started from this script.
steam_config() {  # the config folder of the account used last, or nothing
  if [ -n "$STEAM_CONFIG" ]; then echo "$STEAM_CONFIG"; return; fi
  local root best="" newest=0 d t
  for root in "$HOME/.steam/steam" "$HOME/.local/share/Steam" "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam"; do
    for d in "$root"/userdata/*/config; do
      [ -d "$d" ] || continue
      t="$(stat -c %Y "$d/localconfig.vdf" 2>/dev/null || stat -c %Y "$d" 2>/dev/null || echo 0)"
      if [ "$t" -gt "$newest" ]; then newest="$t"; best="$d"; fi
    done
    [ -n "$best" ] && break
  done
  echo "$best"
}
steam_running() { [ -z "$STEAM_CONFIG" ] && pgrep -x steam >/dev/null 2>&1; }
steam_closed() {  # true once Steam is closed; false when it should be left out
  while steam_running; do
    if [ "$YES" = 1 ]; then say "Steam: it is running, so $1 was left as it is. Close Steam and run this again to do that part."; return 1; fi
    local r; read -r -p "Steam is running, and has to be closed for this (Steam menu, Exit). Close it and press Enter, or type s to skip the Steam part: " r
    case "$r" in s*|S*) return 1 ;; esac
  done
  return 0
}
steam_edit() {  # steam_edit add|remove <config dir> <name> [program] [start dir] [options] [flatpak app id] [art dir]
  python3 - "$@" <<'PY'
import binascii, os, shutil, struct, sys
what, cfg, name = sys.argv[1], sys.argv[2], sys.argv[3]
vdf = os.path.join(cfg, "shortcuts.vdf"); grid = os.path.join(cfg, "grid")
ART = [("cover.png", "p.png"), ("hero.png", "_hero.png"), ("logo.png", "_logo.png"), ("icon.png", "_icon.png"), ("wide.png", ".png")]

def read_map(b, i):
    out = []
    while True:
        t = b[i]; i += 1
        if t == 0x08: return out, i
        j = b.index(0, i); key = b[i:j].decode("utf-8"); i = j + 1
        if t == 0x00: val, i = read_map(b, i)
        elif t == 0x01: j = b.index(0, i); val = b[i:j].decode("utf-8", "surrogateescape"); i = j + 1
        elif t == 0x02: val = struct.unpack("<i", b[i:i + 4])[0]; i += 4
        else: raise ValueError("Steam's shortcut list isn't in a form this knows")
        out.append([t, key, val])

def write_map(items):
    out = b""
    for t, key, val in items:
        out += bytes([t]) + key.encode("utf-8") + b"\x00"
        if t == 0x00: out += write_map(val)
        elif t == 0x01: out += val.encode("utf-8", "surrogateescape") + b"\x00"
        else: out += struct.pack("<i", val)
    return out + b"\x08"

def field(e, key):
    for t, k, v in e:
        if k.lower() == key.lower(): return v
    return None

before = open(vdf, "rb").read() if os.path.exists(vdf) else b"\x00shortcuts\x00\x08\x08"
root, _ = read_map(before, 0)
lists = [n for n in root if n[1].lower() == "shortcuts" and n[0] == 0]
if not lists: sys.exit("Steam's shortcut list isn't in a form this knows: nothing was changed")
items = lists[0][2]
mine = [n for n in items if n[0] == 0 and field(n[2], "AppName") == name]
ids = [field(n[2], "appid") & 0xFFFFFFFF for n in mine if isinstance(field(n[2], "appid"), int)]
if what == "remove" and not mine:
    print('Steam has no shortcut called "%s".' % name); sys.exit(0)
items[:] = [n for n in items if n not in mine]
appid = None
if what == "add":
    exe, start, opts, flatpak, art = sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7], sys.argv[8]
    qexe = '"%s"' % exe
    appid = (binascii.crc32((qexe + name).encode("utf-8")) | 0x80000000) & 0xFFFFFFFF
    signed = appid - (1 << 32) if appid >= (1 << 31) else appid
    e = [[2, "appid", signed], [1, "AppName", name], [1, "Exe", qexe], [1, "StartDir", '"%s"' % start],
         [1, "icon", os.path.join(grid, "%d_icon.png" % appid) if art else ""], [1, "ShortcutPath", ""], [1, "LaunchOptions", opts],
         [2, "IsHidden", 0], [2, "AllowDesktopConfig", 1], [2, "AllowOverlay", 1], [2, "OpenVR", 0], [2, "Devkit", 0],
         [1, "DevkitGameID", ""], [2, "DevkitOverrideAppID", 0], [2, "LastPlayTime", 0], [1, "FlatpakAppID", flatpak], [0, "tags", []]]
    items.append([0, "x", e])
for i, n in enumerate(items): n[1] = str(i)   # (Steam numbers them 0, 1, 2...)
out = write_map(root)
read_map(out, 0)   # (reads back cleanly, or this stops before anything is written)
os.makedirs(cfg, exist_ok=True)
if os.path.exists(vdf): shutil.copyfile(vdf, os.path.join(cfg, "shortcuts.vdf.bak-gatehouse"))
open(vdf, "wb").write(out)
if what == "add":
    if art and os.path.isdir(art):
        os.makedirs(grid, exist_ok=True)
        for src, end in ART:
            if os.path.exists(os.path.join(art, src)): shutil.copyfile(os.path.join(art, src), os.path.join(grid, "%d%s" % (appid, end)))
    print('"%s" is in your Steam library (it shows when Steam next starts).' % name)
else:
    for i in ids:
        for _, end in ART:
            try: os.remove(os.path.join(grid, "%d%s" % (i, end)))
            except OSError: pass
    print('"%s" was taken out of your Steam library.' % name)
PY
}

if [ -n "$PRISM_ROOT" ]; then :; else PRISM_ROOT="$HOME/.var/app/$APP_ID/data/PrismLauncher"; fi
app="$PRISM_ROOT/gatehouse/app"

# ------------------------------------------------------------------ uninstall
if [ "$UNINSTALL" = 1 ]; then
  say "Gatehouse uninstaller"
  say ""
  if [ ! -d "$app" ] && [ ! -f "$desktop" ] && [ "$WITH_PACK" != 1 ]; then say "Gatehouse isn't installed by itself here (nothing in $app). A Gatehouse that came with a pack goes with that pack's uninstaller."; exit 0; fi
  if [ "$DRY" != 1 ]; then ask "Remove Gatehouse from this computer?$([ "$PURGE" = 1 ] && echo " (And what it remembered: its Gatekeepers and this device's identity with them.)")" || { say "Nothing was changed."; exit 0; }; fi
  cfg="$(steam_config)"
  if [ "$NO_STEAM" = 1 ] || [ -z "$cfg" ]; then :
  elif ! command -v python3 >/dev/null 2>&1; then say "Steam: python3 wasn't found: if Gatehouse is in your Steam library, remove it there (Manage, Remove non-Steam game)."
  elif [ "$DRY" = 1 ]; then say "Steam: would take \"Gatehouse\" out of your Steam library if it is there (Steam has to be closed for that)."
  elif steam_closed "\"Gatehouse\" in your Steam library"; then say "Steam: $(steam_edit remove "$cfg" Gatehouse 2>&1)"
  else say "Steam: if Gatehouse is in your library, remove it there (Manage, Remove non-Steam game)."; fi
  if [ "$DRY" = 1 ]; then
    say "Would remove: $app; the menu entry ($desktop)."
    [ "$PURGE" = 1 ] && say "Would also remove: $PRISM_ROOT/gatehouse (its Gatekeepers and this device's identity with them)."
    say "Nothing was changed (--dry-run)."
    exit 0
  fi
  rm -f "$desktop"
  rm -rf "$app"
  rm -f "$PRISM_ROOT"/gatehouse-launcher-run*.jar
  if [ "$PURGE" = 1 ]; then rm -rf "$PRISM_ROOT/gatehouse"; say "Gatehouse is removed, with what it remembered."
  else
    rmdir "$PRISM_ROOT/gatehouse" 2>/dev/null
    if [ -d "$PRISM_ROOT/gatehouse" ]; then say "Gatehouse is removed. What it remembered (its Gatekeepers, this device's identity with them) is still in $PRISM_ROOT/gatehouse; --purge removes that too."
    else say "Gatehouse is removed."; fi
  fi
  say "Left alone: Prism Launcher and everything installed in it."
  if [ "$WITH_PACK" = 1 ]; then
    say "The servers installed through Gatehouse are still in Prism Launcher, each with its own launcher page: to remove one, open Prism Launcher, right-click it and choose Delete."
    say "If \"Gatehouse Minecraft Launcher\" is in your Steam library, remove it there (Manage, Remove non-Steam game)."
  fi
  exit 0
fi

# ------------------------------------------------------------------ install
command -v curl >/dev/null 2>&1 || stop "curl is needed and wasn't found."
command -v sha256sum >/dev/null 2>&1 || stop "sha256sum is needed and wasn't found."
say "Gatehouse installer"
say ""
# ---- Prism Launcher
if [ "$ROOT_GIVEN" = 1 ]; then say "Prism Launcher: using the folder given ($PRISM_ROOT)."
elif [ "$flatpak_prism" = 1 ]; then say "Prism Launcher: found (Flatpak)."
elif [ "$NO_PRISM" = 1 ]; then say "Prism Launcher: not found, and --no-prism was given. Gatehouse needs it to start the game."
elif ! command -v flatpak >/dev/null 2>&1; then say "Prism Launcher: not found, and there's no flatpak here to install it with. Install Prism Launcher your own way; Gatehouse is set up all the same."
elif [ "$DRY" = 1 ]; then say "Prism Launcher: not found. Would offer to install it for your user from Flathub ($APP_ID)."
elif ask "Prism Launcher isn't installed. Install it for your user from Flathub?"; then
  flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo && flatpak install --user -y flathub "$APP_ID" && flatpak_prism=1
  [ "$flatpak_prism" = 1 ] && say "Prism Launcher: installed." || say "Prism Launcher: the install didn't finish. Gatehouse is set up all the same; run this again afterwards."
else say "Prism Launcher: not installed. Gatehouse is set up all the same, but needs Prism to start the game."; fi

# ---- the launcher, its scripts and its art for Steam: what the source publishes, each file checked
names="extras/launch-popup.jar extras/gatehouse-launcher.sh extras/gatehouse-launcher.ps1 extras/plain-launch.sh extras/plain-launch.ps1 extras/launch-wrapper.sh extras/launch-wrapper.exe steam/art-launcher/cover.png steam/art-launcher/hero.png steam/art-launcher/logo.png steam/art-launcher/icon.png steam/art-launcher/wide.png"
if [ "$DRY" = 1 ]; then
  say "Launcher: would fetch these from $SOURCE/$variant into $app, each checked against $SOURCE/$variant/update-sizes.json:"
  say "  $names"
else
  work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
  curl -fsSL --max-time 30 "$SOURCE/$variant/update-sizes.json" -o "$work/sizes.json" || stop "couldn't reach $SOURCE."
  # (the file list is JSON: "<path>": { "hash": "<sha256>", ... ; read with what every system has)
  hash_of() { local k; k="$(printf '%s' "$1" | sed 's/[\/.]/\\&/g')"; tr -d '\n\r' < "$work/sizes.json" | sed -n "s/.*\"$k\": *{[^}]*\"hash\": *\"\([0-9a-fA-F]*\)\".*/\1/p"; }
  mkdir -p "$app/extras" "$app/steam/art-launcher" "$PRISM_ROOT/instances"
  for n in $names; do
    want="$(hash_of "$n")"
    if [ -z "$want" ]; then [ "$n" = extras/launch-popup.jar ] && stop "the source ($SOURCE) doesn't list the launcher."; continue; fi
    curl -fsSL --max-time 120 "$SOURCE/$variant/$n" -o "$work/file" || stop "couldn't fetch $n."
    got="$(sha256sum "$work/file" | cut -d' ' -f1)"
    [ "$(echo "$got" | tr 'A-F' 'a-f')" = "$(echo "$want" | tr 'A-F' 'a-f')" ] || stop "$n isn't what was published (its checksum differs). Nothing was installed from it."
    mv -f "$work/file" "$app/$n"
    case "$n" in *.sh) chmod +x "$app/$n" ;; esac
  done
  # (where the launcher looks for a newer copy of itself: this source, then the repository)
  { echo "# Where Gatehouse looks for a newer launcher, in order. (Written by gatehouse-install.sh.)"; echo "$SOURCE"; [ "$SOURCE" = "$REPO/dist" ] || echo "$REPO/dist"; } > "$app/pack-sources.txt"
  # (a copy of this script, for removing Gatehouse later)
  self="${BASH_SOURCE[0]}"; if [ -f "$self" ] && [ "$(cd "$(dirname "$self")" && pwd)/$(basename "$self")" != "$app/gatehouse-install.sh" ]; then cp -f "$self" "$app/gatehouse-install.sh"; chmod +x "$app/gatehouse-install.sh"; fi
  version="$(tr -d '\n\r' < "$work/sizes.json" | sed -n 's/.*"gatehouse": *"\([^"]*\)".*/\1/p')"
  say "Launcher: Gatehouse ${version:-} is in $app."
fi

# ---- a menu entry: the launcher's script, inside Prism Launcher's Flatpak when that is how Prism is installed
script="$app/extras/gatehouse-launcher.sh"
if [ "$flatpak_prism" = 1 ]; then run="flatpak run --command=bash $APP_ID \"$script\""; else run="bash \"$script\""; fi
if [ "$NO_MENU" = 1 ]; then say "Menu entry: left out (--no-menu)."
elif [ "$DRY" = 1 ]; then say "Menu entry: would write $desktop, which runs: $run"
else
  mkdir -p "$(dirname "$desktop")"
  printf '[Desktop Entry]\nType=Application\nName=Gatehouse\nComment=Your Minecraft servers and their Gatekeepers\nExec=%s\nTerminal=false\nCategories=Game;\n' "$run" > "$desktop"
  say "Menu entry: \"Gatehouse\" is in your applications menu."
fi

# ---- Steam
cfg="$(steam_config)"
if [ "$NO_STEAM" = 1 ]; then :
elif [ -z "$cfg" ]; then say "Steam: not found here, so no Steam shortcut."
elif ! command -v python3 >/dev/null 2>&1; then say "Steam: python3 wasn't found, so no Steam shortcut. (In Steam: Add a Game, Add a Non-Steam Game, and pick Gatehouse from the list.)"
elif [ "$DRY" = 1 ]; then say "Steam: would offer to add \"Gatehouse\" to your Steam library (Steam has to be closed for that)."
elif [ "$STEAM" = 1 ] || ask "Add \"Gatehouse\" to your Steam library$([ "$variant" = deck ] && echo ", so it is there in Game Mode")?"; then
  if steam_closed "adding \"Gatehouse\""; then
    if [ "$flatpak_prism" = 1 ]; then said="$(steam_edit add "$cfg" Gatehouse /usr/bin/flatpak /usr/bin/ "run --command=bash $APP_ID \"$script\"" "$APP_ID" "$app/steam/art-launcher" 2>&1)"
    else said="$(steam_edit add "$cfg" Gatehouse /usr/bin/bash "$app/extras/" "\"$script\"" "" "$app/steam/art-launcher" 2>&1)"; fi
    say "Steam: $said"
  fi
fi
say ""
if [ "$DRY" = 1 ]; then say "Nothing was changed (--dry-run)."
else
  say "Gatehouse is installed. Open it from the applications menu, press Add Gatekeeper, and paste the address, link or file its owner gave you."
  say "To remove it later: bash \"$app/gatehouse-install.sh\" --uninstall"
fi
exit 0
