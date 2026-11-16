#!/bin/bash
# What the Gatehouse Steam shortcuts run on Linux, including Steam Deck (setup points them here:
# <instance>/minecraft/extras/, kept up to date with the pack). Run inside Prism Launcher's Flatpak
# (flatpak run --command=bash org.prismlauncher.PrismLauncher <this file>), or directly where Prism isn't a Flatpak.
# It opens the Gatehouse launcher window, which lists the packs installed in Prism Launcher with whether each server
# is online and a Play button; with --play <instance id> it starts that pack straight away.
# It only finds Java and the launcher and runs it; everything else is in the launcher (extras/launch-popup.jar).
main() {
  local here instances data jar java best v prism
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  instances="$(cd "$here/../../.." && pwd)"
  data="$(cd "$instances/.." && pwd)"
  prism="$(command -v prismlauncher || echo prismlauncher)"
  prism_instead() {
    # Without Java or the launcher (a pack that has never been started yet): Prism Launcher itself does the job.
    if [ -n "$OJ_HOME_DRY" ]; then echo "would start Prism Launcher itself: $prism $*"; exit 0; fi   # (testing)
    if [ "$1" = "--play" ] && [ -n "$2" ]; then exec "$prism" --launch "$2"; else exec "$prism"; fi
  }
  # The newest launcher among the installed packs.
  jar="$(ls -t "$instances"/*/minecraft/extras/launch-popup.jar 2>/dev/null | head -1)"
  [ -n "$jar" ] || prism_instead "$@"
  # Java 17 or newer: the ones Prism downloaded for itself, then the system's.
  java=""; best=0
  for j in "$data"/java/*/bin/java "${XDG_DATA_HOME:-$HOME/.local/share}"/PrismLauncher/java/*/bin/java; do
    [ -x "$j" ] || continue
    v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$(dirname "$(dirname "$j")")/release" 2>/dev/null | head -1)"
    if [ -n "$v" ] && [ "$v" -ge 17 ] && [ "$v" -gt "$best" ]; then java="$j"; best="$v"; fi
  done
  [ -n "$java" ] || java="$(command -v java)"
  [ -n "$java" ] || prism_instead "$@"
  exec "$java" -jar "$jar" --home "$instances" --prism "$prism" "$@"
}
main "$@"; exit
