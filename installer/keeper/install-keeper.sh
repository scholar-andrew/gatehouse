#!/bin/bash
# Gatekeeper setup for Linux and SteamOS: installs the program that runs on the machine hosting the Minecraft servers
# (docs/server.md). A plain script, so you can read exactly what it does before running it. It needs oj-host.jar (the
# Gatekeeper) in the same folder as this script: both come in gatehouse-keeper.zip. It downloads nothing, and asks for
# no administrator rights (nothing outside your home folder is touched).
#
# Run it (after unpacking the zip; on a Steam Deck: Desktop Mode, Konsole):
#   bash install-keeper.sh
#
# What it does (safe to run again: it updates in place and keeps the settings, launchers and players already there):
#   - needs Java 17 or newer. It looks in JAVA_HOME, the PATH, /usr/lib/jvm and the Java that Prism Launcher fetched,
#     and stops, saying where to get it, if there is none.
#   - copies Gatekeeper to its folder (an install that's already there stays where it is; a new one goes to
#     ~/.local/share/GatehouseKeeper) and writes host.json there if it's missing
#   - starts it when you sign in, with no window: a systemd user service "gatehouse-keeper", restarted if it fails;
#     or, on a system without systemd, an entry in ~/.config/autostart
#   - adds "Gatekeeper" to the applications menu
#   - starts Gatekeeper and opens its window, where Server setup ties it to MCSS and its servers
#
# Not supported on Linux or SteamOS: adding Gatekeeper to your Steam library from here.
# Not supported on SteamOS: putting the machine to sleep from a launcher (Gatekeeper says so where that would be).
#
#   --uninstall   remove it: the service, the menu entry and the program. Settings and the launcher list are kept.
#   --purge       with --uninstall: remove the settings, launcher list and log too
#   --dir=DIR     where to install         --java=PATH   the java to run it with
#   --yes         no questions             --no-panel    don't open Gatekeeper at the end
#   --unit=NAME, --no-menu, --no-start, --from-panel   (testing, and Gatekeeper's own Options > Uninstall)
UNINSTALL=0; PURGE=0; DIR=""; JAVA=""; YES=0; NO_PANEL=0; UNIT="gatehouse-keeper"; NO_MENU=0; NO_START=0; FROM_PANEL=0
for a in "$@"; do
  case "$a" in
    --uninstall) UNINSTALL=1 ;; --purge) PURGE=1 ;; --yes) YES=1 ;; --no-panel) NO_PANEL=1 ;; --no-menu) NO_MENU=1 ;;
    --no-start) NO_START=1 ;; --from-panel) FROM_PANEL=1 ;;
    --dir=*) DIR="${a#*=}" ;; --java=*) JAVA="${a#*=}" ;; --unit=*) UNIT="${a#*=}" ;;
    *) echo "Unknown option: $a"; exit 2 ;;
  esac
done
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="${XDG_CONFIG_HOME:-$HOME/.config}"; data="${XDG_DATA_HOME:-$HOME/.local/share}"
unit_file="$config/systemd/user/$UNIT.service"
autostart="$config/autostart/$UNIT.desktop"
menu="$data/applications/gatekeeper.desktop"
system="Linux"; grep -qs '^ID="\?steamos"\?$' /etc/os-release && system="SteamOS"

say() { echo "$*"; }
ask() { [ "$YES" = 1 ] && return 0; local r; read -r -p "$1 [Y/n] " r; case "$r" in n*|N*) return 1 ;; *) return 0 ;; esac; }
finish() { say ""; say "$1"; exit "$2"; }
# systemd for this user, and answering (not so inside some containers, or on a system without it)
has_systemd() { command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; }

# Where a Gatekeeper is installed already: where its service runs it from.
existing_dir() {
  local j
  if [ -f "$unit_file" ]; then
    j="$(sed -n 's/^ExecStart=.* -jar "\(.*\)\/oj-host\.jar" --serve$/\1/p' "$unit_file" | head -n 1)"
    if [ -n "$j" ] && [ -d "$j" ]; then echo "$j"; return; fi
  fi
}

# The java of the newest Java that is 17 or newer.
java_version() {  # the major version of the Java whose bin/java this is, or nothing
  local home rel v
  home="$(cd "$(dirname "$1")/.." 2>/dev/null && pwd)"; rel="$home/release"
  if [ -f "$rel" ]; then v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$rel" | head -n 1)"; fi
  if [ -z "$v" ]; then v="$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"; fi
  echo "$v"
}
find_java() {
  local best="" best_v=0 c v real
  for c in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" /usr/lib/jvm/*/bin/java \
           "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java"/*/bin/java \
           "$data/PrismLauncher/java"/*/bin/java "$data/Gatehouse/java"/*/bin/java; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    real="$(readlink -f "$c" 2>/dev/null || echo "$c")"
    v="$(java_version "$real")"
    case "$v" in ''|*[!0-9]*) continue ;; esac
    if [ "$v" -ge 17 ] && [ "$v" -gt "$best_v" ]; then best="$real"; best_v="$v"; fi
  done
  echo "$best"
}

# This Gatekeeper's own processes of one kind (--serve or --panel): only ones started from this folder's oj-host.jar.
mine() { local p c; for p in /proc/[0-9]*; do c="$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null)"; case "$c" in *"$1"*"$2"*) echo "${p#/proc/}" ;; esac; done; }
stop_keeper() {  # stop_keeper <jar>
  local p
  if has_systemd; then systemctl --user stop "$UNIT.service" >/dev/null 2>&1; fi
  for p in $(mine "$1" --serve); do kill "$p" 2>/dev/null; done
  # (its window too, except when that window is what asked for this)
  if [ "$FROM_PANEL" != 1 ]; then for p in $(mine "$1" --panel); do kill "$p" 2>/dev/null; done; fi
  sleep 1
}

[ -n "$DIR" ] || DIR="$(existing_dir)"
[ -n "$DIR" ] || DIR="$data/GatehouseKeeper"
jar="$DIR/oj-host.jar"

if [ "$UNINSTALL" = 1 ]; then
  say "Remove Gatekeeper from this machine? ($DIR)"
  if [ "$PURGE" = 1 ]; then say "Its settings, launcher list and log are removed too."; else say "Its settings, launcher list and log are kept in that folder."; fi
  ask "Remove it?" || finish "Nothing was changed." 1
  stop_keeper "$jar"
  if has_systemd; then systemctl --user disable "$UNIT.service" >/dev/null 2>&1; fi
  rm -f "$unit_file" "$autostart"
  if has_systemd; then systemctl --user daemon-reload >/dev/null 2>&1; fi
  [ "$NO_MENU" = 1 ] || rm -f "$menu"
  rm -f "$jar" "$DIR/update-restart.sh"
  if [ "$PURGE" = 1 ]; then
    rm -f "$DIR/host.json" "$DIR/devices.json" "$DIR/players.json" "$DIR/host.log" "$DIR/install-keeper.sh" "$DIR/setup-guide-shown.txt"
    rmdir "$DIR" 2>/dev/null
    finish "Gatekeeper is removed, with its settings." 0
  fi
  finish "Gatekeeper is removed. Its settings are still in $DIR." 0
fi

source_jar="$here/oj-host.jar"
[ -f "$source_jar" ] || finish "oj-host.jar isn't beside this script. Unpack the whole gatehouse-keeper.zip and run install-keeper.sh from there." 2
[ -n "$JAVA" ] || JAVA="$(find_java)"
if [ -z "$JAVA" ] || [ ! -x "$JAVA" ]; then
  if [ "$system" = "SteamOS" ]; then
    finish "Gatekeeper needs Java 17 or newer, and none was found. On SteamOS the simplest is Prism Launcher (Discover, in Desktop Mode): open it once and let it fetch Java 21 (Settings, Java), then run this again." 2
  fi
  finish "Gatekeeper needs Java 17 or newer, and none was found. Install it with your system's package manager (for example openjdk-21-jre) or from https://adoptium.net, then run this again." 2
fi
update=0; [ -f "$jar" ] && update=1
say "Gatekeeper: $([ "$update" = 1 ] && echo update || echo install) in $DIR  ($system)"
say "  Java: $JAVA"
say "  It runs when you sign in, with no window, and lets the launchers you allow look after the Minecraft servers on"
say "  this machine. It listens only on this machine's Tailscale address."
[ "$NO_MENU" = 1 ] || say "  \"Gatekeeper\" is added to the applications menu."
[ "$update" = 1 ] && say "  Your settings, launchers and players are kept."
[ "$system" = "SteamOS" ] && say "  Not supported on SteamOS: putting this machine to sleep from a launcher. Keep it plugged in and awake while it serves."
ask "Go ahead?" || finish "Nothing was changed." 1

stop_keeper "$jar"
mkdir -p "$DIR" || finish "Couldn't make $DIR." 2
cp -f "$source_jar" "$jar" || finish "Couldn't copy Gatekeeper to $DIR." 2
[ "$here/install-keeper.sh" -ef "$DIR/install-keeper.sh" ] || cp -f "$here/install-keeper.sh" "$DIR/install-keeper.sh" 2>/dev/null   # (kept there, for --uninstall later)
[ -f "$DIR/host.json" ] || printf '{"port": "25570"}\n' > "$DIR/host.json"

# At sign-in, restarted if it fails. KillMode=process: when Gatekeeper updates itself, the script that puts the new one
# in place (update-restart.sh, started by the old one) has to outlive it.
how=""
if has_systemd; then
  mkdir -p "$(dirname "$unit_file")"
  cat > "$unit_file" <<EOF
[Unit]
Description=Gatekeeper (Gatehouse): lets allowed launchers look after the Minecraft servers on this machine (Tailscale only)

[Service]
ExecStart="$JAVA" -jar "$jar" --serve
WorkingDirectory=$DIR
Restart=on-failure
RestartSec=60
KillMode=process

[Install]
WantedBy=default.target
EOF
  rm -f "$autostart"
  systemctl --user daemon-reload >/dev/null 2>&1
  if [ "$NO_START" = 1 ]; then systemctl --user enable "$UNIT.service" >/dev/null 2>&1; else systemctl --user enable --now "$UNIT.service" >/dev/null 2>&1; fi
  how="It starts when you sign in (systemd user service \"$UNIT\"; see it with: systemctl --user status $UNIT)."
else
  mkdir -p "$(dirname "$autostart")"
  printf '[Desktop Entry]\nType=Application\nName=Gatehouse Keeper\nExec="%s" -jar "%s" --serve\nPath=%s\nTerminal=false\nNoDisplay=true\n' "$JAVA" "$jar" "$DIR" > "$autostart"
  if [ "$NO_START" != 1 ]; then (cd "$DIR" && nohup "$JAVA" -jar "$jar" --serve >/dev/null 2>&1 &); fi
  how="It starts when you sign in to the desktop (an entry in $config/autostart: this system has no systemd user services)."
fi
if [ "$NO_MENU" != 1 ]; then
  mkdir -p "$(dirname "$menu")"
  printf '[Desktop Entry]\nType=Application\nName=Gatekeeper\nComment=Gatehouse: who may do what on this server machine, and its status\nExec="%s" -jar "%s" --panel\nPath=%s\nTerminal=false\nCategories=Game;\n' "$JAVA" "$jar" "$DIR" > "$menu"
fi
sleep 3
if [ "$NO_PANEL" != 1 ]; then (cd "$DIR" && nohup "$JAVA" -jar "$jar" --panel >/dev/null 2>&1 &); fi
next=""
[ "$update" = 1 ] || next="
Gatekeeper is opening: its setup guide walks through Tailscale, MCSS and the servers Gatekeeper should look after."
finish "Gatekeeper is $([ "$update" = 1 ] && echo updated || echo installed). $how$next
To remove it later: bash \"$DIR/install-keeper.sh\" --uninstall" 0
