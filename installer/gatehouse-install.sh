#!/bin/bash
# Gatehouse installer for Linux, including Steam Deck. A plain script: read it before you run it.
#
# It installs Gatehouse, the launcher players use, by itself: no game and no server pack. In Gatehouse you add a
# Gatekeeper with what its owner gave you, and install its servers from there. (Gatekeeper, the program for the
# machine that runs the servers, is Windows only: it works with MC Server Soft.)
#
# What it does to this computer, and nothing else:
#   - Prism Launcher, only if it's missing and you agree: installed for your user from Flathub (flatpak).
#   - Files in <Prism Launcher's folder>/gatehouse/app: the launcher, the scripts that start it, and where to look for
#     a newer launcher. Each file is checked against the published SHA-256 before it is kept.
#   - A menu entry, "Gatehouse" (~/.local/share/applications/gatehouse.desktop), which runs one of those scripts
#     inside Prism Launcher's Flatpak. On a Steam Deck: in Desktop Mode, right-click it and choose Add to Steam to
#     have it in Game Mode.
# Nothing runs in the background, nothing needs the system password, and nothing is sent anywhere.
#
# Run it:   bash gatehouse-install.sh
# Options:  --yes              answer yes to the questions
#           --no-prism         don't offer Prism Launcher
#           --no-menu          no menu entry
#           --source=URL       where Gatehouse is published (default: its GitHub repository)
#           --prism-root=DIR   Prism Launcher's data folder (default: its Flatpak's)
#           --dry-run          say what would be done, and do none of it
set -u
REPO="https://raw.githubusercontent.com/scholar-andrew/gatehouse/main"
APP_ID="org.prismlauncher.PrismLauncher"
YES=0; NO_PRISM=0; NO_MENU=0; DRY=0; SOURCE=""; PRISM_ROOT=""
for a in "$@"; do
  case "$a" in
    --yes) YES=1 ;; --no-prism) NO_PRISM=1 ;; --no-menu) NO_MENU=1 ;; --dry-run) DRY=1 ;;
    --source=*) SOURCE="${a#*=}" ;; --prism-root=*) PRISM_ROOT="${a#*=}" ;;
    *) echo "Unknown option: $a"; exit 2 ;;
  esac
done
[ -n "$SOURCE" ] || SOURCE="$REPO/dist"
SOURCE="${SOURCE%/}"
say() { echo "$*"; }
ask() { [ "$YES" = 1 ] && return 0; local r; read -r -p "$1 [Y/n] " r; case "$r" in n*|N*) return 1 ;; *) return 0 ;; esac; }
stop() { say ""; say "Stopped: $*"; exit 1; }
command -v curl >/dev/null 2>&1 || stop "curl is needed and wasn't found."
command -v sha256sum >/dev/null 2>&1 || stop "sha256sum is needed and wasn't found."
# The Steam Deck gets the Deck's build of what is published (the same launcher; the folder differs).
variant=main
case "$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null)" in Jupiter|Galileo) variant=deck ;; esac

say "Gatehouse installer"
say ""
# ---- Prism Launcher
flatpak_prism=0
if command -v flatpak >/dev/null 2>&1 && flatpak info "$APP_ID" >/dev/null 2>&1; then flatpak_prism=1; fi
if [ -n "$PRISM_ROOT" ]; then say "Prism Launcher: using the folder given ($PRISM_ROOT)."
elif [ "$flatpak_prism" = 1 ]; then say "Prism Launcher: found (Flatpak)."
elif [ "$NO_PRISM" = 1 ]; then say "Prism Launcher: not found, and --no-prism was given. Gatehouse needs it to start the game."
elif ! command -v flatpak >/dev/null 2>&1; then say "Prism Launcher: not found, and there's no flatpak here to install it with. Install Prism Launcher your own way; Gatehouse is set up all the same."
elif [ "$DRY" = 1 ]; then say "Prism Launcher: not found. Would offer to install it for your user from Flathub ($APP_ID)."
elif ask "Prism Launcher isn't installed. Install it for your user from Flathub?"; then
  flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo && flatpak install --user -y flathub "$APP_ID" && flatpak_prism=1
  [ "$flatpak_prism" = 1 ] && say "Prism Launcher: installed." || say "Prism Launcher: the install didn't finish. Gatehouse is set up all the same; run this again afterwards."
else say "Prism Launcher: not installed. Gatehouse is set up all the same, but needs Prism to start the game."; fi
[ -n "$PRISM_ROOT" ] || PRISM_ROOT="$HOME/.var/app/$APP_ID/data/PrismLauncher"
app="$PRISM_ROOT/gatehouse/app"

# ---- the launcher and its scripts: what the source publishes, each file checked
names="launch-popup.jar gatehouse-launcher.sh gatehouse-launcher.ps1 plain-launch.sh plain-launch.ps1 launch-wrapper.sh launch-wrapper.exe"
if [ "$DRY" = 1 ]; then
  say "Launcher: would fetch these from $SOURCE/$variant/extras into $app/extras, each checked against $SOURCE/$variant/update-sizes.json:"
  say "  $names"
else
  work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
  curl -fsSL --max-time 30 "$SOURCE/$variant/update-sizes.json" -o "$work/sizes.json" || stop "couldn't reach $SOURCE."
  # (the file list is JSON: "extras/<name>": { "hash": "<sha256>", ... ; read with what every system has)
  hash_of() { tr -d '\n\r' < "$work/sizes.json" | sed -n "s/.*\"extras\/$1\": *{[^}]*\"hash\": *\"\([0-9a-fA-F]*\)\".*/\1/p"; }
  mkdir -p "$app/extras" "$PRISM_ROOT/instances"
  for n in $names; do
    want="$(hash_of "$n")"
    if [ -z "$want" ]; then [ "$n" = launch-popup.jar ] && stop "the source ($SOURCE) doesn't list the launcher."; continue; fi
    curl -fsSL --max-time 120 "$SOURCE/$variant/extras/$n" -o "$work/$n" || stop "couldn't fetch $n."
    got="$(sha256sum "$work/$n" | cut -d' ' -f1)"
    [ "$(echo "$got" | tr 'A-F' 'a-f')" = "$(echo "$want" | tr 'A-F' 'a-f')" ] || stop "$n isn't what was published (its checksum differs). Nothing was installed from it."
    mv -f "$work/$n" "$app/extras/$n"
    case "$n" in *.sh) chmod +x "$app/extras/$n" ;; esac
  done
  # (where the launcher looks for a newer copy of itself: this source, then the repository)
  { echo "# Where Gatehouse looks for a newer launcher, in order. (Written by gatehouse-install.sh.)"; echo "$SOURCE"; [ "$SOURCE" = "$REPO/dist" ] || echo "$REPO/dist"; } > "$app/pack-sources.txt"
  version="$(tr -d '\n\r' < "$work/sizes.json" | sed -n 's/.*"gatehouse": *"\([^"]*\)".*/\1/p')"
  say "Launcher: Gatehouse ${version:-} is in $app."
fi

# ---- a menu entry: the launcher's script, inside Prism Launcher's Flatpak when that is how Prism is installed
desktop="${XDG_DATA_HOME:-$HOME/.local/share}/applications/gatehouse.desktop"
if [ "$flatpak_prism" = 1 ]; then run="flatpak run --command=bash $APP_ID \"$app/extras/gatehouse-launcher.sh\""; else run="bash \"$app/extras/gatehouse-launcher.sh\""; fi
if [ "$NO_MENU" = 1 ]; then say "Menu entry: left out (--no-menu)."
elif [ "$DRY" = 1 ]; then say "Menu entry: would write $desktop, which runs: $run"
else
  mkdir -p "$(dirname "$desktop")"
  printf '[Desktop Entry]\nType=Application\nName=Gatehouse\nComment=Your Minecraft servers and their Gatekeepers\nExec=%s\nTerminal=false\nCategories=Game;\n' "$run" > "$desktop"
  say "Menu entry: \"Gatehouse\" is in your applications menu."
fi
say ""
if [ "$DRY" = 1 ]; then say "Nothing was changed (--dry-run)."
else
  say "Gatehouse is installed. Open it from the applications menu, press Add Gatekeeper, and paste the address, link or file its owner gave you."
  [ "$variant" = deck ] && say "On the Steam Deck: in Desktop Mode, right-click Gatehouse in the menu and choose Add to Steam, to have it in Game Mode."
fi
exit 0
