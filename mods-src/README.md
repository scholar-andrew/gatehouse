# The pack's own mods

Four jars in `source/files/mods/` aren't from Modrinth. Their source is here so they can be read, rebuilt and changed.
All are client-side Fabric mods for Minecraft 26.3, except the REI patch, which the server runs too.

## chatlift (Chat Lift)
`chatlift/`, built with `tools/build_mod.ps1 chatlift`. Made for this pack.

- **Linux on a Steam Deck:** when chat opens, asks Steam for its on-screen keyboard (`steam://open/keyboard`) and raises the
  chat box and history (by `lift_fraction`, 43% of the screen) so the keyboard doesn't cover them; closes the keyboard
  when chat closes.
- **Windows (and other Linux devices), under Steam, with a Steam controller in controller mode:** raises chat for the keyboard the player opens
  with Steam + X: `lift_fraction_desktop` (22%) or, when the game was started from Big Picture, `lift_fraction_big_picture`
  (38%). It opens nothing itself (see [docs/how-it-works.md](../docs/how-it-works.md#steam-keyboard-and-controllers)).
- **With Controlify present:** turns Controlify's own on-screen keyboard off for Steam controllers and on for others
  (each time the current controller changes), and turns on Controlify's mixed input while a Steam controller is used,
  so key presses from Steam's keyboard reach the text box.
- Settings: `config/chatlift.properties`, rewritten with comments on each start.

Files: `ChatLift.java` (settings, lift, opening `steam://` links), `SteamKeyboard.java` and `ControlifyBridge.java`
(controller handling; the bridge is the only class that touches Controlify, so the mod loads without it),
`mixin/ChatScreenMixin.java` (moves chat), `mixin/MinecraftMixin.java` (per-tick hook).

## octoberjourney (October Journey pack settings)
`octoberjourney/`, built with `tools/build_mod.ps1 octoberjourney`. Made for this pack. Adds **October Journey Settings** to Mod
Menu, whose settings screen has the two launch pop-up switches (**Update pop-up**, **No-update pop-up**, saved as
`popup_updates` and `popup_no_updates` at the top of `pack-options.json`), then an on/off switch for each mod listed in
`extras/mod-toggles.json` (with the pack's default and whether it's loaded right now in the tooltip). A mod switch
saves the player's choice under `mods` in `pack-options.json`; the pack sync applies it before the next launch by
disabling or re-enabling the mod's jar. Nothing happens in the running game: a loaded mod can't be unloaded.

## launch-popup (the window before the game starts)
`launch-popup/`, built with `tools/build_popup.ps1` into `source/files/launch-popup.jar`. It isn't a Minecraft mod: it's
a small Swing program (Java 17, no libraries) that the pack sync runs with Prism's Java before the game starts. It
lists what the update checked (coloured dots), the mods it added, removed and updated, mod switches, notes and the
patch notes, or "no updates"; a Server tab shows what the server runs and what changed there. It honours the pop-up
switches above.
[docs/how-it-works.md](../docs/how-it-works.md) (step 7 of a launch) describes what it reads and writes.

## handofffix (Controller Handoff Fix)
`handofffix/`, built with `tools/build_mod.ps1 handofffix`. Made for this pack. Requires Controlify.

Controlify names a controller by a hash of its type plus a counter. Every Steam Input pad shares one hash, so after an
unplug and replug the counter could repeat an ID still in use; the newer controller then replaced the older one (the
Deck's own controls), which vanished when the newer one was unplugged. The mixin on `SDLCommonDriver.createUid` picks
the first ID that no connected controller is using.

## rei-brewingfix (Roughly Enough Items, brewing fix)
`rei-brewingfix/`: a patch, not a full project. The shipped jar is the official REI 26.3.824 release with one class
replaced (`DefaultBrewingDisplay`) and the version label changed to `26.3.824+brewingfix.1`.

REI's brewing displays called a client-only method (`EntryStack.tooltip`) in code the dedicated server also runs, so
all 279 brewing recipes failed to load on the server. The patched class adds those tooltips only on the client.
- `DefaultBrewingDisplay.original.java` / `DefaultBrewingDisplay.java`: before and after.
- `brewing-tooltip-fix.patch`: the diff.
- `upstream-commit.txt`: the REI commit the source came from. `UPSTREAM-LICENSE`: REI's MIT licence.

When an official REI release fixes this, switch the pack and the server back to the Modrinth version.

## Building
`tools/build_mod.ps1` (chatlift, handofffix or octoberjourney) compiles with plain `javac` against the Prism Launcher libraries and the `october-journey`
instance's mods on the owner's PC (Minecraft 26.x isn't obfuscated, so no Gradle/Loom or refmap is needed). The
original Gradle projects for chatlift and handofffix are on the Steam Deck in `~/mcmods/`; their `gradle.properties` is
copied here for the version numbers.
