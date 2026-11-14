#!/bin/bash
# October Journey pack sync (Linux / Steam Deck). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (the PC over Tailscale first, then GitHub) and brings the instance up to
# date with packwiz-installer. On a Steam Deck (hardware Jupiter or Galileo) it takes the Deck pack, which adds the
# Deck-only mods; anywhere else the main pack.
here="$(cd "$(dirname "$0")" && pwd)"
variant=main
case "$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null)" in
  Jupiter|Galileo) variant=deck ;;
esac
url=""
while IFS= read -r s || [ -n "$s" ]; do
  s="${s%%$'\r'}"; s="${s%/}"
  [ -z "$s" ] && continue
  [[ "$s" == \#* ]] && continue
  if curl -fsI --max-time 3 "$s/$variant/pack.toml" >/dev/null 2>&1; then url="$s/$variant/pack.toml"; break; fi
done < "$here/pack-sources.txt"
if [ -z "$url" ]; then echo "Pack sync: no source reachable; starting with the mods already installed."; exit 0; fi
echo "Pack sync ($variant): $url"
cd "$here" || exit 1
exec "${INST_JAVA:-java}" -jar "$here/packwiz-installer-bootstrap.jar" ${PACK_SYNC_ARGS} "$url"
