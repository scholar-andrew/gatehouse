package dev.octoberjourney.popup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** Which Steam controller layout is in use, on a Steam Deck. Steam uses one layout per shortcut for as long as that
 *  shortcut runs, and Gatehouse and the game it starts run under the same shortcut. The pack's layout (back grips,
 *  trackpad as mouse) is for the game; Gatehouse's own windows are meant to be driven with a plain one. So:
 *    - Gatehouse's own Steam shortcut gets no layout from the pack (the update takes the pack's off it): Steam's
 *      default for the device applies;
 *    - while a Gatehouse window is open, Steam is asked to use that shortcut's layout (which matters when Gatehouse
 *      was opened from a pack's own shortcut);
 *    - when the game starts, Steam is asked to use the layout of the pack's shortcut; when Prism Launcher is done,
 *      and when Gatehouse closes, Steam is told to go back to deciding for itself.
 *  Steam is asked with its own address for this, steam://forceinputappid/&lt;shortcut's app id&gt; (0: decide for
 *  yourself), opened the way any link is (xdg-open). Only on a Steam Deck, and only when Gatehouse was started by
 *  Steam. Whatever is asked is noted in steam-input.log beside the instances folder. If Steam doesn't take the
 *  request nothing else changes: the shortcut's own layout stays in use.
 *  Testing: OJ_STEAM_INPUT_TEST=&lt;file&gt; makes it apply anywhere and writes the addresses there instead of opening them. */
final class SteamInput {
	private SteamInput() {}

	private static volatile String forced;
	private static volatile Path log;
	private static boolean hooked;

	static boolean applies() {
		if (System.getenv("OJ_STEAM_INPUT_TEST") != null) return true;
		if (System.getProperty("os.name", "").startsWith("Windows")) return false;
		try {
			String p = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim();
			if (!p.equals("Jupiter") && !p.equals("Galileo")) return false;
		} catch (IOException | RuntimeException e) { return false; }
		for (String k : new String[] {"SteamGameId", "SteamOverlayGameId", "SteamClientLaunch", "SteamAppId"}) { String v = System.getenv(k); if (v != null && !v.isEmpty()) return true; }
		return false;
	}

	/** The app id of the shortcut setup made for this game folder with that art ("pack": the one that starts this
	 *  pack; "launcher": Gatehouse's own), from steam-target.json; "" when there isn't one. */
	static String appid(Path mc, String art) {
		try {
			Map<String, Object> st = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("steam-target.json"))));
			if (st.get("targets") instanceof List<?> l) {
				for (Object o : l) { Map<String, Object> t = ServerControl.asMap(o); if (art.equals(String.valueOf(t.get("art")))) return number(t.get("appid")); }
				return "";
			}
			return art.equals("pack") ? number(st.get("appid")) : "";   // (from before there were two shortcuts)
		} catch (RuntimeException e) { return ""; }
	}
	private static String number(Object o) {
		String s = o == null ? "" : o.toString().trim();
		if (s.matches("\\d+\\.0+")) s = s.substring(0, s.indexOf('.'));
		if (s.matches("\\d(\\.\\d+)?[eE]\\+?\\d+")) try { s = new java.math.BigDecimal(s).toBigInteger().toString(); } catch (RuntimeException ignored) {}
		return s.matches("\\d+") ? s : "";
	}

	/** Gatehouse's own shortcut, as any installed pack's game folder records it. */
	static String launcherId(Path instances) {
		log = instances.toAbsolutePath().getParent().resolve("steam-input.log");
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) { String id = appid(d.resolve("minecraft"), "launcher"); if (!id.isEmpty()) return id; }
		} catch (IOException | RuntimeException ignored) {}
		return "";
	}

	/** Asks Steam to use that shortcut's layout from now on. Nothing happens off a Steam Deck, without an id, or when
	 *  that is what was asked last. */
	static void force(String id) {
		if (id == null || id.isEmpty() || id.equals(forced) || !applies()) return;
		forced = id;
		if (!hooked) { hooked = true; Runtime.getRuntime().addShutdownHook(new Thread(SteamInput::reset, "steam-input")); }
		open(id);
	}

	/** Tells Steam to go back to deciding for itself (the running shortcut's own layout). */
	static void reset() {
		if (forced == null || forced.equals("0")) return;
		forced = "0";
		open("0");
	}

	private static void open(String id) {
		String url = "steam://forceinputappid/" + id, result;
		try {
			String test = System.getenv("OJ_STEAM_INPUT_TEST");
			if (test != null) { Files.writeString(Path.of(test), url + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); return; }
			Process p = new ProcessBuilder("xdg-open", url).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			result = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) ? "xdg-open ended with " + p.exitValue() : "xdg-open still running after 5 seconds";
		} catch (IOException | InterruptedException | RuntimeException e) { result = "couldn't be opened: " + e; }
		Path l = log;
		if (l != null) try { Files.writeString(l, java.time.LocalDateTime.now().withNano(0) + "  " + url + "  " + result + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) {}
	}
}
