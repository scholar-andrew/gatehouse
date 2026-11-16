#!/bin/bash
# Gatehouse, for a plain (unmodded) server set up from the launcher's menu: Prism runs this before the game starts.
# There is no pack to update; it only shows the server's launcher window (whether it's on, Request server, the
# whitelist, Server controls) and does what the window says: Play starts the game, Restart shows it again, Quit
# cancels the launch. Anything else (no Java new enough for the launcher, the launcher missing) just starts the game.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rm -f "$here/launch-cancelled"
jar="$here/extras/launch-popup.jar"
[ -f "$jar" ] || exit 0
# A Java 17 or newer for the launcher: the one Prism hands over if it's new enough, else one Prism downloaded.
java=""; best=0
for j in "$INST_JAVA" "$here"/../../../java/*/bin/java; do
  [ -n "$j" ] && [ -f "$j" ] && [ -x "$j" ] || continue
  v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$(dirname "$(dirname "$j")")/release" 2>/dev/null | head -1)"
  if [ -n "$v" ] && [ "$v" -ge 17 ] && [ "$v" -gt "$best" ]; then java="$j"; best="$v"; fi
done
[ -n "$java" ] || exit 0
while true; do
  "$java" -jar "$jar" "$here" ok; code=$?
  [ "$code" = 11 ] && continue          # Restart
  [ "$code" = 10 ] || exit 0            # Play, or the launcher couldn't run: the game starts
  # Quit: with the launch wrapper set up, leave it a note and end well (Prism sees a launch that ended normally);
  # without it, the only way to stop the launch is to fail this step.
  if [ -f "$here/launch-wrapper.sh" ] && grep -q '^WrapperCommand=.*launch-wrapper' "$here/../instance.cfg" 2>/dev/null; then echo quit > "$here/launch-cancelled"; exit 0; fi
  exit 1
done
