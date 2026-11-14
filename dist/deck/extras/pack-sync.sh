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
# pack-options.json (written by setup and the uninstaller) says which of these the player wants kept up to date:
# steam_art, steam_controller, server_entry. Missing file or key: yes.
opt() { python3 -c 'import json,sys
try: print("1" if json.load(open(sys.argv[1])).get(sys.argv[2], True) else "0")
except Exception: print("1")' "$here/pack-options.json" "$1" 2>/dev/null || echo 1; }
OPT_ART=$(opt steam_art); OPT_CTRL=$(opt steam_controller); OPT_SERVER=$(opt server_entry)
if [ -f "$here/steam-target.json" ] && command -v python3 >/dev/null 2>&1 && { [ "$OPT_ART" = 1 ] || [ "$OPT_CTRL" = 1 ]; }; then
python3 - "$here" "$OPT_ART" "$OPT_CTRL" <<'PY' || echo "$(date '+%Y-%m-%d %H:%M:%S')  Steam extras skipped" >> "$here/pack-sync.log"
import hashlib, json, os, re, shutil, sys, time
here = sys.argv[1]; want_art, want_ctrl = sys.argv[2] == "1", sys.argv[3] == "1"
t = json.load(open(os.path.join(here, "steam-target.json")))
sp = os.path.join(here, "steam-applied.json")
state = json.load(open(sp)) if os.path.exists(sp) else {}
g, a = t["grid"], str(t["appid"])
items = [("steam/art/cover.png", os.path.join(g, a + "p.png")), ("steam/art/hero.png", os.path.join(g, a + "_hero.png")),
         ("steam/art/logo.png", os.path.join(g, a + "_logo.png")), ("steam/art/icon.png", os.path.join(g, a + "_icon.png")),
         ("steam/art/wide.png", os.path.join(g, a + ".png")), ("steam/controller_neptune.vdf", os.path.join(t["controller"], "controller_neptune.vdf"))]
changed = []
for src, dst in items:
    if not (want_ctrl if src.endswith(".vdf") else want_art): continue
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
if want_ctrl and ('"%s"' % key.lower()) not in txt.lower():
    e = txt.rindex("}"); open(cs, "w").write(txt[:e] + '\t"%s"\n\t{\n\t\t"autosave"\t\t"1"\n\t}\n' % key + txt[e:]); changed.append("controller list")
json.dump(state, open(sp, "w"), indent=1)
if changed:
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  Steam extras updated: %s (shows after Steam restarts)\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), ", ".join(changed)))
PY
fi

# The pack's servers (extras/servers.json) in Minecraft's multiplayer list (servers.dat, uncompressed NBT). A missing one is
# added at the top; a wrong address is corrected while it is still the one this sync last set (or it never set one), so an
# entry edited or deleted in the game afterwards is left alone. Other entries are kept as they are.
if [ -f "$here/extras/servers.json" ] && command -v python3 >/dev/null 2>&1 && [ "$OPT_SERVER" = 1 ]; then
python3 - "$here" <<'PY' || echo "$(date '+%Y-%m-%d %H:%M:%S')  server list skipped" >> "$here/pack-sync.log"
import json, os, shutil, struct, sys, time
here = sys.argv[1]
want = json.load(open(os.path.join(here, "extras", "servers.json")))
dat, sp = os.path.join(here, "servers.dat"), os.path.join(here, "servers-applied.json")
state = json.load(open(sp, encoding="utf-8-sig")) if os.path.exists(sp) else {}
d = open(dat, "rb").read() if os.path.exists(dat) else b""
i = 0
def u16():
    global i; i += 2; return struct.unpack(">H", d[i - 2:i])[0]
def i32():
    global i; i += 4; return struct.unpack(">i", d[i - 4:i])[0]
def st():
    global i; n = u16(); i += n; return d[i - n:i].decode("utf-8")
def skip(t):
    global i
    if t in (1, 2, 3, 4, 5, 6): i += {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}[t]
    elif t == 7: n = i32(); i += n
    elif t == 8: n = u16(); i += n
    elif t == 9:
        et = d[i]; i += 1
        for _ in range(i32()): skip(et)
    elif t == 10:
        while d[i]: tt = d[i]; i += 1; st(); skip(tt)
        i += 1
    elif t == 11: i += 4 * i32()
    elif t == 12: i += 8 * i32()
    else: raise ValueError("unknown NBT tag %d" % t)
def spay(s):
    b = s.encode("utf-8"); return struct.pack(">H", len(b)) + b
other, entries = [], []
if d:
    assert d[0] == 10, "servers.dat is not plain NBT"
    i = 1; st()
    while d[i]:
        start, tt = i, d[i]; i += 1; nm = st()
        if tt == 9 and nm == "servers":
            et = d[i]; i += 1; n = i32()
            assert n == 0 or et == 10
            for _ in range(n):
                fields = []
                while d[i]:
                    ft = d[i]; i += 1; fn = st(); ps = i; skip(ft); fields.append([ft, fn, d[ps:i]])
                i += 1; entries.append(fields)
        else:
            skip(tt); other.append(d[start:i])
val = lambda e, k: next((f[2][2:].decode("utf-8") for f in e if f[0] == 8 and f[1] == k), None)
changes = []
for s in want:
    e = next((x for x in entries if val(x, "name") == s["name"]), None) or next((x for x in entries if val(x, "ip") == s["address"]), None)
    last = state.get(s["name"])
    if not e:
        if last: continue  # deleted in the game: left out
        entries.insert(0, [[8, "name", spay(s["name"])], [8, "ip", spay(s["address"])]])
        changes.append("added %s (%s)" % (s["name"], s["address"])); state[s["name"]] = s["address"]; continue
    ip = val(e, "ip") or ""
    if ip == s["address"]: state[s["name"]] = ip; continue
    if last and ip != last: continue  # changed in the game: kept
    f = next((f for f in e if f[1] == "ip"), None)
    if f: f[2] = spay(s["address"])
    else: e.append([8, "ip", spay(s["address"])])
    changes.append("%s address %s -> %s" % (s["name"], ip, s["address"])); state[s["name"]] = s["address"]
if changes:
    out = b"\x0a\x00\x00" + b"".join(other) + b"\x09" + spay("servers") + b"\x0a" + struct.pack(">i", len(entries))
    for e in entries:
        out += b"".join(bytes([f[0]]) + spay(f[1]) + f[2] for f in e) + b"\x00"
    out += b"\x00"
    if d and not os.path.exists(dat + ".before-pack"): shutil.copy2(dat, dat + ".before-pack")
    open(dat, "wb").write(out)
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  server list: %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), "; ".join(changes)))
json.dump(state, open(sp, "w"), indent=1)
PY
fi

# This script, from the pack: the next launch runs the pack's copy. Swapped in by rename, because bash is still reading
# this file as it runs.
if [ -f "$here/extras/pack-sync.sh" ] && ! cmp -s "$here/extras/pack-sync.sh" "$0"; then
  cp "$here/extras/pack-sync.sh" "$here/pack-sync.sh.new" && chmod +x "$here/pack-sync.sh.new" && mv -f "$here/pack-sync.sh.new" "$here/pack-sync.sh" \
    && echo "$(date '+%Y-%m-%d %H:%M:%S')  sync script updated (used from the next launch)" >> "$here/pack-sync.log"
fi
exit $code
