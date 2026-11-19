#!/bin/bash
# What the Gatehouse Steam shortcuts run on Linux, including Steam Deck (setup points them here:
# <instance>/minecraft/extras/, kept up to date with the pack). Run inside Prism Launcher's Flatpak
# (flatpak run --command=bash org.prismlauncher.PrismLauncher <this file>), or directly where Prism isn't a Flatpak.
# It opens the Gatehouse launcher window, which lists the packs installed in Prism Launcher with whether each server
# is online and a Play button; with --play <instance id> it starts that pack straight away. With --choose it opens
# the program's first screen instead (Play or Host): what the applications menu entry does. The Steam shortcut runs
# this without it, and so goes straight into the launcher.
# It only finds Java and the launcher and runs it; everything else is in the launcher (extras/launch-popup.jar).
main() {
  local here instances data jar java best v prism app
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # Installed by itself, with no pack (gatehouse-install.sh), this file is in <Prism's folder>/gatehouse/app/extras, and
  # the launcher's own copy is beside it; otherwise it is in a pack's extras folder, three levels below "instances".
  if [ "$(basename "$(dirname "$here")")" = app ] && [ "$(basename "$(dirname "$(dirname "$here")")")" = gatehouse ]; then
    app="$(dirname "$here")"
    data="$(cd "$app/../.." && pwd)"
    instances="$data/instances"
    mkdir -p "$instances"
  else
    instances="$(cd "$here/../../.." && pwd)"
    data="$(cd "$instances/.." && pwd)"
    app="$data/gatehouse/app"
  fi
  # The newest launcher there is: among the installed packs, and Gatehouse's own copy if it has one.
  newest_launcher() { ls -t "$instances"/*/minecraft/extras/launch-popup.jar "$app/extras/launch-popup.jar" 2>/dev/null | head -1; }
  prism="$(command -v prismlauncher || echo prismlauncher)"
  prism_instead() {
    # Without Java or the launcher (a pack that has never been started yet): Prism Launcher itself does the job.
    if [ -n "$OJ_HOME_DRY" ]; then echo "would start Prism Launcher itself: $prism $*"; exit 0; fi   # (testing)
    if [ "$1" = "--play" ] && [ -n "$2" ]; then exec "$prism" --launch "$2"; else exec "$prism"; fi
  }
  jar="$(newest_launcher)"
  [ -n "$jar" ] || prism_instead "$@"
  # Java 17 or newer: the ones Prism downloaded for itself, then the system's.
  java=""; best=0
  # (also the Javas that come inside Prism Launcher's Flatpak, for a Gatehouse installed with no pack: Prism has
  # downloaded none of its own until an instance has been started)
  for j in "$data"/java/*/bin/java "${XDG_DATA_HOME:-$HOME/.local/share}"/PrismLauncher/java/*/bin/java /app/jdk/*/bin/java /app/jre/bin/java /usr/lib/sdk/openjdk*/bin/java /usr/lib/jvm/*/bin/java; do
    [ -x "$j" ] || continue
    v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$(dirname "$(dirname "$j")")/release" 2>/dev/null | head -1)"
    if [ -n "$v" ] && [ "$v" -ge 17 ] && [ "$v" -gt "$best" ]; then java="$j"; best="$v"; fi
  done
  [ -n "$java" ] || java="$(command -v java)"
  [ -n "$java" ] || prism_instead "$@"
  # The launcher ends with 12 when a newer copy of itself is in place (it updated itself): the newest is then opened
  # in its place, each time that happens (GATEHOUSE_RELAUNCH tells the launcher that this script does that).
  export GATEHOUSE_RELAUNCH=loop
  # (R80: the chat's video player sends its sound through ALSA, which SteamOS hands to PipeWire; with PipeWire's small
  # default buffer it crackled and dragged the video with it. A larger one, for Gatehouse's own sound only; whatever
  # the device already sets wins.)
  export PIPEWIRE_LATENCY="${PIPEWIRE_LATENCY:-2048/48000}"
  export PULSE_LATENCY_MSEC="${PULSE_LATENCY_MSEC:-60}"
  if [ "$1" = "--choose" ]; then
    if [ -n "$OJ_HOME_DRY" ]; then echo "would open the first screen: $java -jar $jar --choose"; exit 0; fi   # (testing)
    exec "$java" -jar "$jar" --choose
  fi
  round=0
  while :; do
    "$java" -jar "$jar" --home "$instances" --prism "$prism" "$@"; code=$?
    [ "$code" = 12 ] || exit "$code"
    round=$((round + 1)); [ "$round" -lt 8 ] || exit 0
    jar="$(newest_launcher)"
    [ -n "$jar" ] || exit 0
    export GATEHOUSE_UPDATED=1
    set --   # (a server named with --play was for the first time round)
  done
}
main "$@"; exit
