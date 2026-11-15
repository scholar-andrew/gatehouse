#!/bin/bash
# The launch wrapper for Linux and the Steam Deck (Prism Launcher's "wrapper command" for the October Journey
# instance). Prism starts the game as:  bash launch-wrapper.sh <java> <java's arguments...>
# Normally this just becomes that command, untouched. When the player pressed Quit in the launcher, the pre-launch
# script left a file called "launch-cancelled" beside this script: then nothing is started and this ends with exit
# code 0, so Prism sees a launch that ended normally instead of a failed one (a failed pre-launch step makes Prism
# open its log window).
here="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$here/launch-cancelled" ]; then
  rm -f "$here/launch-cancelled"
  exit 0
fi
exec "$@"
