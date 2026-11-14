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
if [ -z "$url" ]; then echo "$(date '+%Y-%m-%d %H:%M:%S')  no source reachable; started with the mods already installed" >> "$here/pack-sync.log"; exit 0; fi
cd "$here" || exit 1
"${INST_JAVA:-java}" -jar "$here/packwiz-installer-bootstrap.jar" ${PACK_SYNC_ARGS} "$url"
code=$?
echo "$(date '+%Y-%m-%d %H:%M:%S')  ($variant) $url -> exit $code" >> "$here/pack-sync.log"
echo "Pack sync ($variant): $url -> exit $code"

# Steam extras from the pack (library art, Steam Deck controller layout), into this device's Steam shortcut. steam-target.json
# (written by setup) says where. First time: installed, keeping what was there as *.before-pack. After that a file is
# replaced only while it is still the version last installed here, so art or a layout changed in Steam is kept. Steam reads
# both when it starts: a change shows after Steam is next restarted (or after switching back to Game Mode).
if [ -f "$here/steam-target.json" ] && command -v python3 >/dev/null 2>&1; then
python3 - "$here" <<'PY' || echo "$(date '+%Y-%m-%d %H:%M:%S')  Steam extras skipped" >> "$here/pack-sync.log"
import hashlib, json, os, re, shutil, sys, time
here = sys.argv[1]
t = json.load(open(os.path.join(here, "steam-target.json")))
sp = os.path.join(here, "steam-applied.json")
state = json.load(open(sp)) if os.path.exists(sp) else {}
g, a = t["grid"], str(t["appid"])
items = [("steam/art/cover.png", os.path.join(g, a + "p.png")), ("steam/art/hero.png", os.path.join(g, a + "_hero.png")),
         ("steam/art/logo.png", os.path.join(g, a + "_logo.png")), ("steam/art/icon.png", os.path.join(g, a + "_icon.png")),
         ("steam/art/wide.png", os.path.join(g, a + ".png")), ("steam/controller_neptune.vdf", os.path.join(t["controller"], "controller_neptune.vdf"))]
changed = []
for src, dst in items:
    sp_ = os.path.join(here, src)
    if not os.path.exists(sp_): continue
    data = open(sp_, "rb").read()
    if src.endswith(".vdf"):
        data = re.sub(r'"url"\s*"[^"]*"', '"url"\t\t"autosave://%s"' % dst, data.decode("utf-8"), count=1).encode("utf-8")
    new = hashlib.sha256(data).hexdigest()
    have = hashlib.sha256(open(dst, "rb").read()).hexdigest() if os.path.exists(dst) else None
    last = state.get(src)
    if have == new: state[src] = new; continue
    if have and last and have != last: continue
    if have and not last: shutil.copy2(dst, dst + ".before-pack")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, "wb").write(data); state[src] = new; changed.append(os.path.basename(src))
cs = os.path.join(os.path.dirname(t["controller"]), "configset_controller_neptune.vdf"); key = os.path.basename(t["controller"])
txt = open(cs).read() if os.path.exists(cs) else '"controller_config"\n{\n}\n'
if ('"%s"' % key.lower()) not in txt.lower():
    e = txt.rindex("}"); open(cs, "w").write(txt[:e] + '\t"%s"\n\t{\n\t\t"autosave"\t\t"1"\n\t}\n' % key + txt[e:]); changed.append("controller list")
json.dump(state, open(sp, "w"), indent=1)
if changed:
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  Steam extras updated: %s (shows after Steam restarts)\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), ", ".join(changed)))
PY
fi
exit $code
