#!/bin/bash
# October Journey pack sync (Linux / Steam Deck). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (the PC over Tailscale first, then GitHub) and brings the instance up to
# date with packwiz-installer. On a Steam Deck (hardware Jupiter or Galileo) it takes the Deck pack, which adds the
# Deck-only mods; anywhere else the main pack.
here="$(cd "$(dirname "$0")" && pwd)"
# The pack's name, from its profile (extras/profile.json, shipped with the pack): nothing about a server is fixed here.
pack_name="$(sed -n 's/.*"name": *"\([^"]*\)".*/\1/p' "$here/extras/profile.json" 2>/dev/null | head -n 1)"
[ -n "$pack_name" ] || pack_name="The pack"
variant=main
case "$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null)" in
  Jupiter|Galileo) variant=deck ;;
esac
note() { echo "$(date '+%Y-%m-%d %H:%M:%S')  $*" >> "$here/pack-sync.log"; echo "Pack sync: $*"; }
# For the pop-up: what this launch checked and how it went (pack-sync-status.txt, started afresh each launch), one line
# each: "check|<ok, updated, off, warn or failed>|<what>|<detail>", "switch|<on or off>|<mod>" or "note|<text>". And
# packwiz-installer's record of the installed files as it was before this launch's update (packwiz-before.json; "{}" on
# a first install), which the pop-up compares with the record afterwards to list the mods added, removed and updated.
# Run by the Gatehouse launcher instead of Prism (GATEHOUSE_EMBED=1): its own window is the checklist, so no window is
# opened from here; the question about the download is asked there; and at the end, instead of waiting for Play, this
# leaves "pack-sync-fresh.txt" (when, and how it went) and ends. Gatehouse then has Prism start the instance, which
# runs this script again in the usual way: finding that note less than three minutes old, it updates nothing a second
# time and goes straight to the launcher window, with what the first run found.
embed="${GATEHOUSE_EMBED:-}"
fresh=""
if [ -z "$embed" ] && [ -f "$here/pack-sync-fresh.txt" ]; then
  IFS='|' read -r fresh_when fresh_what < "$here/pack-sync-fresh.txt" || true
  fresh_what="${fresh_what%%$'\r'}"
  case "$fresh_when" in ''|*[!0-9]*) fresh_when=0 ;; esac
  fresh_age=$(( $(date +%s) - fresh_when ))
  if [ "$fresh_age" -ge 0 ] && [ "$fresh_age" -lt 180 ] && [ -n "$fresh_what" ]; then fresh="$fresh_what"; fi
fi
rm -f "$here/pack-sync-fresh.txt"
status() { echo "check|$1|$2|${3:-}" >> "$here/pack-sync-status.txt"; }
if [ -z "$fresh" ]; then   # (otherwise the status and the records are the first run's: kept)
  : > "$here/pack-sync-status.txt"
  rm -f "$here/pack-sync-news.txt"   # (what syncs before this one wrote)
  if [ -f "$here/packwiz.json" ]; then cp -f "$here/packwiz.json" "$here/packwiz-before.json"; else echo "{}" > "$here/packwiz-before.json"; fi
fi
# The pop-up (extras/launch-popup.jar, a small Java window): started first thing as a checklist of this script's steps,
# ticked off as their "check" lines arrive in pack-sync-status.txt, then (after "done|<status>") the summary in the same
# window: what changed, patch notes, the Server tab, and Play, Restart (run this again) or Quit (cancel the launch).
# Prism waits for it. Its exit code says what the player chose: 0 play, 10 quit, 11 restart.
popup_pid=""
# The launcher runs from a copy (launcher-run.jar, beside this script), as on Windows, where a running program's file
# is locked and the update has to be able to replace extras/launch-popup.jar while the launcher's window is open.
# Does this device's launcher know --pack-signed (R40: 3.31.0 or newer)? Read from the notes inside it, never by running
# it: an older one would take the unknown word for something else.
pack_checkable() {
  command -v python3 >/dev/null 2>&1 || return 1
  python3 -c 'import json, re, sys, zipfile
v = json.loads(zipfile.ZipFile(sys.argv[1]).read("gatehouse-notes.json")).get("version", "0")
p = [int(x) for x in re.sub(r"[^0-9.].*$", "", v).split(".") if x]
sys.exit(0 if p >= [int(x) for x in sys.argv[2].split(".")] else 1)' "$here/extras/launch-popup.jar" "${1:-3.31.0}" 2>/dev/null
}
# R40, step 2: Gatehouse fetching the update itself (3.32.0 or newer): every source asked at once, the newest pack that
# answers well taken (between equals, the fastest), and every file checked against the signed list before the installer
# gets it. Started in the background; relay_url is its address, empty when it couldn't be started.
relay_pid=""; relay_url=""
start_relay() {
  rm -f "$here/pack-relay-port.txt" "$here/pack-relay-used.txt"
  local srcs=() s
  while IFS= read -r s || [ -n "$s" ]; do s="${s%%$'\r'}"; s="${s%/}"; [ -z "$s" ] && continue; [[ "$s" == \#* ]] && continue; srcs+=("$s"); done < "$here/pack-sources.txt"
  "$java_bin" -jar "$(run_jar)" --pack-relay "$variant" "$here" "${srcs[@]}" >/dev/null 2>&1 &
  relay_pid=$!
  local i port
  for i in $(seq 1 50); do
    if [ -f "$here/pack-relay-port.txt" ]; then port=$(tr -dc '0-9' < "$here/pack-relay-port.txt"); [ -n "$port" ] && { relay_url="http://127.0.0.1:$port/pack.toml"; return 0; }; fi
    sleep 0.2
  done
  kill "$relay_pid" 2>/dev/null; relay_pid=""; return 1
}
run_jar() { cp -f "$here/extras/launch-popup.jar" "$here/launcher-run.jar" 2>/dev/null; if [ -f "$here/launcher-run.jar" ]; then echo "$here/launcher-run.jar"; else echo "$here/extras/launch-popup.jar"; fi; }
start_progress() {
  [ -n "$embed" ] && return 0   # (the Gatehouse window is the checklist)
  [ -n "$java_bin" ] && [ -f "$here/extras/launch-popup.jar" ] || return 0
  "$java_bin" -jar "$(run_jar)" --progress "$here" >> "$here/pack-sync-popup.log" 2>&1 &
  popup_pid=$!
}
stop_progress() { [ -n "$popup_pid" ] && kill "$popup_pid" 2>/dev/null; popup_pid=""; }
popup() {  # popup ok|offline|failed; returns the player's choice
  if [ -n "$embed" ]; then
    echo "done|$1" >> "$here/pack-sync-status.txt"
    echo "$(date +%s)|$1" > "$here/pack-sync-fresh.txt"
    return 0
  fi
  if [ -n "$popup_pid" ] && kill -0 "$popup_pid" 2>/dev/null; then
    echo "done|$1" >> "$here/pack-sync-status.txt"
  else
    if [ -n "$popup_pid" ]; then wait "$popup_pid"; local c=$?; popup_pid=""; [ "$c" = 10 ] && return 10; fi   # quit while the update ran
    [ -n "$java_bin" ] && [ -f "$here/extras/launch-popup.jar" ] || return 0
    "$java_bin" -jar "$(run_jar)" "$here" "$1" >> "$here/pack-sync-popup.log" 2>&1 &
    popup_pid=$!
  fi
  # Waits for the player: nothing starts the game but Play (no time limit).
  wait "$popup_pid"
}
# Quit: no game this time (Prism then reports the pre-launch step failed). Restart: this script again, from the top
# (the newest copy, if it just updated itself).
# Quit with the launch wrapper set up for this instance (instance.cfg has it as Prism's wrapper command): this step
# ends well and leaves "launch-cancelled" for the wrapper, which then starts nothing, so Prism sees a launch that ended
# normally. Without the wrapper the only way to stop the launch is to fail this step (Prism then shows its log).
rm -f "$here/launch-cancelled"
# GATEHOUSE_PLAY: the note says "play" when the update AND the launcher window were both in the Gatehouse window, and
# Play was pressed there. Nothing is left to do or show here: the game starts.
if [ "$fresh" = play ]; then note "updated and Play pressed in the Gatehouse window: starting the game"; exit 0; fi
cancel_launch() {
  if [ -f "$here/launch-wrapper.sh" ] && grep -q '^WrapperCommand=.*launch-wrapper' "$here/../instance.cfg" 2>/dev/null; then
    echo quit > "$here/launch-cancelled"
    exit 0
  fi
  exit 1
}
after_popup() {
  case "$1" in
    10) note "launch cancelled in the pop-up (Quit)"; cancel_launch ;;
    11) note "running the update again (Restart in the pop-up)"; exec bash "$here/pack-sync.sh" ;;
  esac
}
# A brand-new instance has no mods yet: starting the game then would only show a modless Minecraft that can't join the
# server, so in that case a failed sync stops the launch (Prism shows the error) instead.
# Mods the player can switch on and off (Mod Menu > October Journey; the list and the pack's defaults are in
# extras/mod-toggles.json, the player's choices under "mods" in pack-options.json). A mod that's off has its jar renamed
# to .jar.disabled after each update (as Prism does, so the game doesn't load it), and renamed back before the next
# update so packwiz-installer finds it intact. mods-switched-off.json remembers which jars this did.
switches() {  # switches restore|apply
  command -v python3 >/dev/null 2>&1 || return 0
  python3 - "$here" "$1" <<'PY' || note "mod switches skipped"
import json, os, sys, time
here, mode = sys.argv[1], sys.argv[2]
state = os.path.join(here, "mods-switched-off.json")
def note(s):
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), s)); print("Pack sync:", s)
if mode == "restore":
    # The list stays until "apply", which compares with it so only real changes are noted.
    if os.path.isfile(state):
        for jar in json.load(open(state)):
            if os.path.isfile(jar + ".disabled") and not os.path.exists(jar): os.rename(jar + ".disabled", jar)
    sys.exit(0)
tp, pw = os.path.join(here, "extras", "mod-toggles.json"), os.path.join(here, "packwiz.json")
if not (os.path.isfile(tp) and os.path.isfile(pw)): sys.exit(0)
files = json.load(open(pw)).get("cachedFiles", {})
try: choices = json.load(open(os.path.join(here, "pack-options.json"))).get("mods", {}) or {}
except Exception: choices = {}
toggles = json.load(open(tp))
# What each mod should be: the player's choice, else the pack's default; and off whenever a mod it needs is off.
want = {t["key"]: bool(choices[t["key"]]) if t["key"] in choices else t["default"] for t in toggles}
for _ in toggles:
    for t in toggles:
        if any(not want.get(n, True) for n in t.get("needs", [])): want[t["key"]] = False
off, changed = [], []
try: before = set(json.load(open(state)))
except Exception: before = set()
for t in toggles:
    entry = files.get(t["file"])
    if not entry: continue
    jar = os.path.join(here, entry.get("cachedLocation") or t["file"])
    if not want[t["key"]]:
        if os.path.isfile(jar):
            if os.path.exists(jar + ".disabled"): os.remove(jar + ".disabled")
            os.rename(jar, jar + ".disabled")
            if jar not in before: changed.append(t["name"] + " off")
        if os.path.isfile(jar + ".disabled"): off.append(jar)
    elif os.path.isfile(jar + ".disabled") and not os.path.exists(jar):
        os.rename(jar + ".disabled", jar); changed.append(t["name"] + " on")
    elif jar in before: changed.append(t["name"] + " on")
if off: json.dump(off, open(state, "w"))
elif os.path.exists(state): os.remove(state)
st = open(os.path.join(here, "pack-sync-status.txt"), "a")
if changed:
    note("mods switched: " + ", ".join(changed))
    for c in changed: st.write("switch|%s|%s\n" % ("on" if c.endswith(" on") else "off", c.rsplit(" ", 1)[0]))
    st.write("check|updated|Mod switches|%d changed; %d off\n" % (len(changed), len(off)))
else:
    st.write("check|ok|Mod switches|%s\n" % ("As set; %d off" % len(off) if off else "All on"))
PY
}
# Defaults the pack added later, applied once to devices that already had the setting (a new install gets them from the
# pack's config-defaults). pack-defaults-applied.txt lists the ones done; after that the player's own choice stays.
#   ipn-hotbar-icons-off  Inventory Profiles Next's Auto Refill arrows on the hotbar hidden (pack 1.5.0 default)
defaults_once() {
  command -v python3 >/dev/null 2>&1 || return 0
  python3 - "$here" <<'PY' || note "pack default skipped"
import json, os, sys, time
here = sys.argv[1]; done = os.path.join(here, "pack-defaults-applied.txt")
have = open(done).read().split() if os.path.exists(done) else []
if "ipn-hotbar-icons-off" not in have:
    f = os.path.join(here, "config", "inventoryprofilesnext", "inventoryprofiles.json")
    if os.path.exists(f):
        o = json.load(open(f, encoding="utf-8-sig"))
        o.setdefault("AutoRefillSettings", {})["auto_refill_enable_horbar_indicator_icons"] = {"value": False}
        json.dump(o, open(f, "w"), indent=4)
        open(os.path.join(here, "pack-sync.log"), "a").write("%s  pack default applied once: Inventory Profiles Next hotbar arrows hidden\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
        open(os.path.join(here, "pack-sync-status.txt"), "a").write("note|Inventory Profiles Next: the purple Auto Refill arrows on the hotbar are now hidden (the pack default; change it in that mod's settings)\n")
    open(done, "a").write("ipn-hotbar-icons-off\n")
PY
}
stop_if_no_mods() {  # stop_if_no_mods "why" ["advice"] [ok|offline|failed for the pop-up]
  if ls "$here"/mods/*.jar >/dev/null 2>&1; then switches apply; defaults_once; note "$1; starting with the mods already installed"; popup "${3:-failed}"; after_popup $?; exit 0; fi
  note "$1, and no mods are installed yet: stopping the launch. Details: pack-sync.log and pack-sync-java.log in $here"
  echo "$pack_name couldn't install its mods yet ($1). ${2:-Check your connection (Tailscale or internet) and press Play again.}"
  stop_progress
  exit 1
}
cd "$here" || exit 1
# Java: the one Prism hands over (INST_JAVA), else one Prism downloaded, else whatever is on PATH. On an instance's
# first launch Prism hasn't chosen a Java yet and INST_JAVA can be just its data folder, so only a java program counts.
java_bin=""
for c in "${INST_JAVA:-}" "$here"/../../../java/*/bin/java; do
  case "$(basename "$c")" in java|javaw) ;; *) continue ;; esac
  if [ -f "$c" ] && [ -x "$c" ]; then java_bin="$c"; break; fi
done
[ -n "$java_bin" ] || java_bin="$(command -v java || true)"
if [ -n "$fresh" ]; then note "updated a moment ago in the Gatehouse window ($fresh): straight to the launcher"; popup "$fresh"; after_popup $?; exit 0; fi
start_progress
# Which sources answer, in order, each given 5 seconds. If none does (after sleep or at startup the network can still be
# coming up), one more round after 5 seconds.
urls=()
reachable() {
  urls=()
  while IFS= read -r s || [ -n "$s" ]; do
    s="${s%%$'\r'}"; s="${s%/}"
    [ -z "$s" ] && continue
    [[ "$s" == \#* ]] && continue
    if curl -fsI --max-time 5 "$s/$variant/pack.toml" >/dev/null 2>&1; then
      urls+=("$s/$variant/pack.toml")
      [ "${1:-}" = first ] && break
    fi
  done < "$here/pack-sources.txt"
}
# To begin with only the first one that answers is looked for (the others are asked only if the update from it fails):
# every launch waited on all of them before.
reachable first
if [ "${#urls[@]}" = 0 ]; then note "no source answered; trying again in 5 seconds"; sleep 5; reachable first; fi
if [ "${#urls[@]}" = 0 ]; then
  status warn "Connection" "No pack source answered (Tailscale or internet)"; status warn "Mods and settings" "Not checked; playing what's installed"
  stop_if_no_mods "no source reachable" "" offline
fi
[ -n "$java_bin" ] || stop_if_no_mods "no Java found yet (INST_JAVA was '${INST_JAVA:-}')" "Prism Launcher is still setting up Java: press Play again."
# Before anything is downloaded the launcher says how big the update is and asks: Download, Skip this time or Quit.
# "ask|<pack address>" in the status file starts it; the launcher works the size out from the pack's update-sizes.json
# against what's installed here, and answers in pack-sync-answer.txt: "wait" while it's asking, then "go" or
# "skip|<size>" (Quit closes it: exit code 10). With nothing to download it answers "go" at once. No launcher window,
# or no answer begun within 30 seconds: no question, the update just runs.
# The launcher updates itself first ("self-update" on the ask line says this script can handle it): if the pack has a
# newer launch-popup.jar, it fetches and checks it, leaves it as launch-popup.jar.new and closes with exit code 12;
# this script swaps it in and opens the new launcher, which then answers the same question. One such round at most.
rm -f "$here/pack-sync-answer.txt" "$here/extras/launch-popup.jar.new"
skip=0; skip_size=""
# Nothing to update: the launcher compares the pack's file list with what's installed here anyway (for the size of the
# download), and when every file is here and none is left over it answers "current" ("current-ok" on the ask line says
# this script understands that). packwiz-installer then isn't run at all, which is most of what a launch with no
# update used to wait for. As a safety net it is still run in full when it hasn't been for a week
# (pack-sync-full.txt: when it last was).
current=0; recent_full=0
full_when="$(tr -d '\r\n' < "$here/pack-sync-full.txt" 2>/dev/null || true)"
case "$full_when" in ''|*[!0-9]*) ;; *) full_age=$(( $(date +%s) - full_when )); if [ "$full_age" -ge 0 ] && [ "$full_age" -lt 604800 ]; then recent_full=1; fi ;; esac
for round in 1 2; do
  if [ -z "$embed" ]; then [ -n "$popup_pid" ] && kill -0 "$popup_pid" 2>/dev/null || break; fi
  # (Gatehouse's window updates Gatehouse itself, in its own time: no "self-update" for it)
  if [ "$round" = 1 ]; then
    if [ -n "$embed" ]; then echo "ask|${urls[0]%/pack.toml}||current-ok" >> "$here/pack-sync-status.txt"; else echo "ask|${urls[0]%/pack.toml}|self-update|current-ok" >> "$here/pack-sync-status.txt"; fi
  fi
  asked=$SECONDS; answer=""
  while [ -n "$embed" ] || kill -0 "$popup_pid" 2>/dev/null; do
    answer="$(tr -d '\r\n' < "$here/pack-sync-answer.txt" 2>/dev/null || true)"
    if [ -n "$answer" ] && [ "$answer" != wait ]; then break; fi
    if [ -z "$answer" ] && [ $((SECONDS - asked)) -gt 30 ]; then note "the launcher gave no answer about the update size; updating"; break; fi
    sleep 0.05
  done
  if [ -n "$embed" ]; then
    if [ "$answer" = quit ]; then note "launch cancelled in the Gatehouse window (Quit)"; exit 10; fi
  elif ! kill -0 "$popup_pid" 2>/dev/null; then
    wait "$popup_pid"; c=$?; popup_pid=""
    [ "$c" = 10 ] && after_popup 10
    if [ "$round" = 1 ] && [ "$c" = 12 ] && [ -f "$here/extras/launch-popup.jar.new" ]; then
      if mv -f "$here/extras/launch-popup.jar.new" "$here/extras/launch-popup.jar"; then note "the launcher updated itself; opening the new one"; fi
      rm -f "$here/pack-sync-answer.txt"
      start_progress
      continue
    fi
  fi
  if [ "$answer" = current ] && [ "$recent_full" = 1 ]; then current=1; note "nothing to update: every file of the pack is already here"; fi
  case "$answer" in skip*) skip=1; skip_size="${answer#skip}"; skip_size="${skip_size#|}"; note "update skipped in the launcher (about $skip_size waiting)" ;; esac
  break
done
switches restore
# packwiz-installer (the program the bootstrap runs) is pinned to one release, so it only changes when the pack changes
# it. Once that release is here (packwiz-installer.jar's manifest says so), the bootstrap doesn't ask GitHub at all:
# one less connection that can fail, and no GitHub rate limit. Otherwise it fetches exactly that release.
installer_tag="v0.5.14"
installer_release="https://api.github.com/repos/packwiz/packwiz-installer/releases/tags/$installer_tag"
installer_here() {
  command -v python3 >/dev/null 2>&1 || return 1
  python3 -c 'import re, sys, zipfile
m = zipfile.ZipFile(sys.argv[1]).read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
sys.exit(0 if re.search(r"Implementation-Version:\s*" + re.escape(sys.argv[2]) + r"\s", m) else 1)' "$here/packwiz-installer.jar" "$installer_tag" 2>/dev/null
}
# One run of packwiz-installer against one source, stopped if it hasn't finished in 5 minutes (a stalled download would
# otherwise hold the launch forever; exit 124 then). Its output goes to pack-sync-java.log.
run_installer() {
  # R40: the pack at this source has to be the published one. Its pack.toml's release signature is checked first (by
  # the launcher; only one from 3.31.0 on knows how, so an older one isn't asked). A signature that doesn't fit skips
  # this source (exit 125) and the next is tried; none found yet (GitHub signs a minute or two after each publish) is taken.
  if [[ "$1" != http://127.0.0.1:* ]] && pack_checkable; then
    local chk; chk=$("$java_bin" -jar "$(run_jar)" --pack-signed "$1" 2>/dev/null | head -n 1)
    note "pack signature at $1: $chk"
    case "$chk" in bad*) note "  skipped: this source's pack isn't the published one"; return 125;; esac
  fi
  # -g: no windows. Otherwise a failed download waits on an error dialog (Quit / Continue) for someone to click,
  # holding the launch; without them a failure just ends the run, and the next source is tried.
  local a=(-jar "$here/packwiz-installer-bootstrap.jar" -g) t=()
  if installer_here; then a+=(--bootstrap-no-update); else a+=(--bootstrap-update-url "$installer_release"); fi
  command -v timeout >/dev/null 2>&1 && t=(timeout "${PACK_SYNC_TIMEOUT:-300}")   # seconds; the variable is for testing
  "${t[@]}" "$java_bin" "${a[@]}" ${PACK_SYNC_ARGS} "$1" > "$here/pack-sync-java.log" 2>&1
}
# Each source that answered, in order, then the first once more: an update that fails or stalls (say the connection
# dropped part-way) is tried again. packwiz-installer only replaces files it has fully downloaded and checked, and
# picks up where it left off on the next try.
code=1; url=""; first_url="${urls[0]}"
queue=("$first_url"); widened=0
if [ "$skip" = 1 ] || [ "$current" = 1 ]; then code=0; url="$first_url"; queue=(); fi
relay_used=""
if [ "${#queue[@]}" -gt 0 ] && pack_checkable 3.32.0 && start_relay; then
  # (through Gatehouse first; if that doesn't finish, the sources one by one, as before)
  jar_was="$(sha256sum "$here/extras/launch-popup.jar" 2>/dev/null | cut -c1-64)"
  run_installer "$relay_url"; code=$?
  note "($variant) through Gatehouse ($relay_url) -> exit $code"
  [ -f "$here/pack-relay.log" ] && tail -n 3 "$here/pack-relay.log" | while IFS= read -r l; do note "  relay: $l"; done
  kill "$relay_pid" 2>/dev/null
  if [ "$code" = 0 ] && [ -f "$here/pack-relay-used.txt" ]; then
    url="$(sed -n 1p "$here/pack-relay-used.txt")/pack.toml"; relay_used="$(sed -n 2p "$here/pack-relay-used.txt")"; queue=()
    # (R40: Gatehouse itself came with this update from another Gate System: noted, for the update log)
    if [ "$relay_used" = peer ]; then
      jar_now="$(sha256sum "$here/extras/launch-popup.jar" 2>/dev/null | cut -c1-64)"
      peer_host="$(sed -n 1p "$here/pack-relay-used.txt" | sed -E 's#^https?://\[?([^]/:]+).*#\1#')"
      [ -n "$jar_now" ] && [ "$jar_now" != "$jar_was" ] && printf '%s|another Gate System (%s)\n' "$jar_now" "$peer_host" > "$here/extras/launch-popup.source.txt"
    fi
  else code=1; fi
fi
while [ "${#queue[@]}" -gt 0 ]; do
  u="${queue[0]}"; queue=("${queue[@]:1}")
  run_installer "$u"; code=$?; url="$u"
  note "($variant) $u -> exit $code (Java: $java_bin)"
  [ "$code" = 0 ] && break
  [ "$code" = 124 ] && note "  stopped: not finished after 5 minutes"
  tail -n 5 "$here/pack-sync-java.log" | while IFS= read -r l; do note "  java: $l"; done
  # (it failed: now the other sources that answer, then the first once more)
  if [ "$widened" = 0 ]; then
    widened=1; reachable
    for m in "${urls[@]}"; do [ "$m" != "$first_url" ] && queue+=("$m"); done
    queue+=("$first_url")
  fi
done
if [ "$code" = 0 ] && [ "$skip" != 1 ] && [ "$current" != 1 ]; then date +%s > "$here/pack-sync-full.txt"; fi
case "$url" in *githubusercontent*) where="GitHub" ;; *) if [ "$relay_used" = peer ]; then where="another Gate System"; else where="the owner's PC (Tailscale)"; fi ;; esac
if [ "$skip" = 1 ]; then
  status ok "Connection" "$where"; status warn "Mods and settings" "Update skipped this time (about $skip_size waiting); playing what's installed"
elif [ "$code" = 0 ]; then
  status ok "Connection" "$where"; status ok "Mods and settings"   # (the pop-up adds what changed, from the records)
else
  status warn "Connection" "$where; the update didn't finish"
  status failed "Mods and settings" "Didn't finish (exit $code); playing what's installed. Details: pack-sync.log"
fi
if [ "$code" != 0 ]; then
  ls "$here"/mods/*.jar >/dev/null 2>&1 || stop_if_no_mods "the mod download failed (exit $code)"
fi
switches apply
defaults_once
# Where the pack is fetched from follows its profile (extras/profile.json "pack_sources"): pack-sources.txt is rewritten
# to match, for the next launch. Only after an update that worked, and only when the new list still has the source that
# just worked in it, so a wrong list can never leave a device with no source that answers.
if [ "$code" = 0 ] && [ "$skip" != 1 ] && command -v python3 >/dev/null 2>&1; then
  python3 - "$here" "${url%/$variant/pack.toml}" <<'PY' || true
import json, os, sys, time
here, worked = sys.argv[1], sys.argv[2].rstrip("/")
try: want = [s.strip().rstrip("/") for s in json.load(open(os.path.join(here, "extras/profile.json"))).get("pack_sources", []) if s and s.strip()]
except Exception: want = []
p = os.path.join(here, "pack-sources.txt")
have = [l.strip().rstrip("/") for l in open(p).read().splitlines() if l.strip() and not l.strip().startswith("#")]
if want and worked in want and want != have:
    open(p, "w").write("# Tried in order; the first that answers is used. (Kept up to date from the pack's profile.)\n" + "".join(s + "\n" for s in want))
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  pack sources updated from the profile: %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), ", ".join(want)))
PY
fi
# The launch wrapper, kept beside this script (Prism runs it from there; see cancel_launch above).
if [ -f "$here/extras/launch-wrapper.sh" ] && ! cmp -s "$here/extras/launch-wrapper.sh" "$here/launch-wrapper.sh"; then
  cp -f "$here/extras/launch-wrapper.sh" "$here/launch-wrapper.sh" && chmod +x "$here/launch-wrapper.sh"
fi

# Steam extras from the pack (library art, Steam Deck controller layout), into this device's Steam shortcuts.
# steam-target.json (written by setup) says where: "targets", one per shortcut, each with its grid folder, app id,
# controller layout folder and which art it gets ("pack": October Journey's, for the shortcut that starts the game;
# "prism": Prism Launcher's logo, for the one that opens Prism). From before there were two shortcuts it may just have
# grid / appid / controller for one shortcut with the pack's art. First time: installed, keeping what was there as
# *.before-pack. After that a file is replaced only while it is still the version last installed here, so art or a
# layout changed in Steam is kept. Steam reads both when it starts: a change shows after Steam is next restarted (or
# after switching back to Game Mode).
# pack-options.json (written by setup and the uninstaller) says which of these the player wants kept up to date:
# steam_art, steam_controller, server_entry. Missing file or key: yes.
opt() { python3 -c 'import json,sys
try: print("1" if json.load(open(sys.argv[1], encoding="utf-8-sig")).get(sys.argv[2], True) else "0")
except Exception: print("1")' "$here/pack-options.json" "$1" 2>/dev/null || echo 1; }
OPT_ART=$(opt steam_art); OPT_CTRL=$(opt steam_controller); OPT_SERVER=$(opt server_entry)
if [ ! -f "$here/steam-target.json" ]; then status off "Steam art and controller layouts" "No Steam shortcut set up by setup"
elif [ "$OPT_ART$OPT_CTRL" = 00 ]; then status off "Steam art and controller layouts" "Not kept up to date (your choice at setup)"; fi
if [ -f "$here/steam-target.json" ] && command -v python3 >/dev/null 2>&1 && { [ "$OPT_ART" = 1 ] || [ "$OPT_CTRL" = 1 ]; }; then
python3 - "$here" "$OPT_ART" "$OPT_CTRL" <<'PY' || { echo "$(date '+%Y-%m-%d %H:%M:%S')  Steam extras skipped" >> "$here/pack-sync.log"; status failed "Steam art and controller layouts" "Skipped after an error (pack-sync.log)"; }
import hashlib, json, os, re, shutil, sys, time
here = sys.argv[1]; want_art, want_ctrl = sys.argv[2] == "1", sys.argv[3] == "1"
st = json.load(open(os.path.join(here, "steam-target.json")))
legacy = not st.get("targets")
targets = [dict(st, art="pack")] if legacy else st["targets"]
sp = os.path.join(here, "steam-applied.json")
state = json.load(open(sp, encoding="utf-8-sig")) if os.path.exists(sp) else {}
changed = []
for t in targets:
    g, a, art = t["grid"], str(t["appid"]), "steam/art-prism" if t.get("art") == "prism" else "steam/art-launcher" if t.get("art") == "launcher" else "steam/art"
    items = [(art + "/cover.png", os.path.join(g, a + "p.png")), (art + "/hero.png", os.path.join(g, a + "_hero.png")),
             (art + "/logo.png", os.path.join(g, a + "_logo.png")), (art + "/icon.png", os.path.join(g, a + "_icon.png")),
             (art + "/wide.png", os.path.join(g, a + ".png")), ("steam/controller_neptune.vdf", os.path.join(t["controller"], "controller_neptune.vdf"))]
    for src, dst in items:
        if not (want_ctrl if src.endswith(".vdf") else want_art): continue
        sp_ = os.path.join(here, src)
        if not os.path.exists(sp_): continue
        data = open(sp_, "rb").read()
        if src.endswith(".vdf"):
            data = re.sub(r'"url"\s*"[^"]*"', '"url"\t\t"autosave://%s"' % dst, data.decode("utf-8"), count=1).encode("utf-8")
        new = hashlib.sha256(data).hexdigest()
        have = hashlib.sha256(open(dst, "rb").read()).hexdigest() if os.path.exists(dst) else None
        # What was last installed there (recorded by destination; by pack file before there were two shortcuts).
        last = state.get(dst) or (state.get(src) if legacy else None)
        if have == new: state[dst] = new; continue
        if have and last and have != last: continue
        if have and not last: shutil.copy2(dst, dst + ".before-pack")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, "wb").write(data); state[dst] = new; changed.append(os.path.basename(src))
    cs = os.path.join(os.path.dirname(t["controller"]), "configset_controller_neptune.vdf"); key = os.path.basename(t["controller"])
    txt = open(cs).read() if os.path.exists(cs) else '"controller_config"\n{\n}\n'
    if want_ctrl and ('"%s"' % key.lower()) not in txt.lower():
        e = txt.rindex("}"); open(cs, "w").write(txt[:e] + '\t"%s"\n\t{\n\t\t"autosave"\t\t"1"\n\t}\n' % key + txt[e:]); changed.append("controller list")
json.dump(state, open(sp, "w"), indent=1)
if changed:
    open(os.path.join(here, "pack-sync.log"), "a").write("%s  Steam extras updated: %s (shows after Steam restarts)\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), ", ".join(changed)))
    open(os.path.join(here, "pack-sync-status.txt"), "a").write("check|updated|Steam art and controller layouts|%d updated for %d shortcut(s); shows after Steam restarts\n" % (len(changed), len(targets)))
else:
    open(os.path.join(here, "pack-sync-status.txt"), "a").write("check|ok|Steam art and controller layouts|Up to date (%d shortcut%s)\n" % (len(targets), "" if len(targets) == 1 else "s"))
PY
fi

# The pack's servers (extras/servers.json) in Minecraft's multiplayer list (servers.dat, uncompressed NBT). A missing one is
# added at the top; a wrong address is corrected while it is still the one this sync last set (or it never set one), so an
# entry edited or deleted in the game afterwards is left alone. Other entries are kept as they are.
[ "$OPT_SERVER" = 1 ] || status off "Server list" "Not kept up to date (your choice at setup)"
if [ -f "$here/extras/servers.json" ] && command -v python3 >/dev/null 2>&1 && [ "$OPT_SERVER" = 1 ]; then
python3 - "$here" <<'PY' || { echo "$(date '+%Y-%m-%d %H:%M:%S')  server list skipped" >> "$here/pack-sync.log"; status failed "Server list" "Skipped after an error (pack-sync.log)"; }
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
    open(os.path.join(here, "pack-sync-status.txt"), "a").write("check|updated|Server list|%s\n" % "; ".join(changes))
else:
    open(os.path.join(here, "pack-sync-status.txt"), "a").write("check|ok|Server list|%s is listed\n" % ", ".join(s["name"] for s in want))
json.dump(state, open(sp, "w"), indent=1)
PY
fi

# This script, from the pack: the next launch runs the pack's copy. Swapped in by rename, because bash is still reading
# this file as it runs.
if [ -f "$here/extras/pack-sync.sh" ] && ! cmp -s "$here/extras/pack-sync.sh" "$0"; then
  if cp "$here/extras/pack-sync.sh" "$here/pack-sync.sh.new" && chmod +x "$here/pack-sync.sh.new" && mv -f "$here/pack-sync.sh.new" "$here/pack-sync.sh"; then
    echo "$(date '+%Y-%m-%d %H:%M:%S')  sync script updated (used from the next launch)" >> "$here/pack-sync.log"
    status updated "Update script" "New version, used from the next launch"
  else status failed "Update script" "Couldn't replace it"; fi
elif [ -f "$here/extras/pack-sync.sh" ]; then status ok "Update script" "Current"; fi
if [ "$code" = 0 ]; then popup ok; else popup failed; fi
after_popup $?
exit 0
