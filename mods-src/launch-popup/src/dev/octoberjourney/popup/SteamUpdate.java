package dev.octoberjourney.popup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Brings this device's Steam shortcuts up to date with the pack, without running setup again (the launcher's
 *  "Update Steam shortcuts" button). It works from what setup left in the game folder (steam-target.json: each
 *  shortcut's app id, art folder and controller layout folder) and what the pack ships (extras/gatehouse-launcher.*,
 *  steam/art*, steam/controller_neptune.vdf):
 *    - the pack's shortcut starts through the Gatehouse launcher (so the game can start straight away); its name stays
 *    - the shortcut that opened Prism Launcher becomes "Gatehouse Minecraft Launcher - ...", opening the launcher
 *      window, with Gatehouse's art and the controller layout under its new name
 *    - the art and controller layout of both are put in place again from the pack
 *  Steam's shortcut list (userdata/&lt;account&gt;/config/shortcuts.vdf, a small binary file) is backed up beside itself as
 *  shortcuts.vdf.bak-launcher first. Steam has to be closed: it writes that file itself when it exits. */
final class SteamUpdate {
	private SteamUpdate() {}

	static final String LAUNCHER_NAME = "Gatehouse Minecraft Launcher";

	/** Is there anything here to update: did setup add Steam shortcuts for this pack? */
	static boolean available(Path mc) { return Files.isRegularFile(mc.resolve("steam-target.json")); }

	/** Is Steam running? "yes", "no", or "unknown" (inside Prism's Flatpak on Linux, other programs can't be seen). */
	static String steamRunning() {
		try {
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH").redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				p.waitFor();
				return out.toLowerCase(java.util.Locale.ROOT).contains("steam.exe") ? "yes" : "no";
			}
			if (Files.exists(Path.of("/.flatpak-info"))) return "unknown";
			Process p = new ProcessBuilder("pgrep", "-x", "steam").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor() == 0 ? "yes" : "no";
		} catch (IOException | InterruptedException e) { return "unknown"; }
	}

	/** Does it; returns what was done, a line each. */
	static List<String> run(Path mc) throws IOException {
		List<String> did = new ArrayList<>();
		boolean windows = System.getProperty("os.name", "").startsWith("Windows");
		Map<String, Object> st = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("steam-target.json"))));
		List<Map<String, Object>> targets = new ArrayList<>();
		if (st.get("targets") instanceof List<?> l) for (Object o : l) targets.add(new LinkedHashMap<>(ServerControl.asMap(o)));
		else if (st.containsKey("appid")) { Map<String, Object> one = new LinkedHashMap<>(st); one.put("art", "pack"); targets.add(one); }
		if (targets.isEmpty()) throw new IOException("setup didn't add Steam shortcuts for this pack on this device (run setup to add them)");
		Path vdf = Path.of(str(targets.get(0).get("grid"))).getParent().resolve("shortcuts.vdf");
		if (!Files.isRegularFile(vdf)) throw new IOException("Steam's shortcut list isn't where setup left it (" + vdf + ")");
		byte[] before = Files.readAllBytes(vdf);
		List<Object[]> root = read(ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN));
		List<Object[]> list = null;
		for (Object[] n : root) if (((String) n[1]).equalsIgnoreCase("shortcuts") && n[2] instanceof List<?>) list = nodes(n[2]);
		if (list == null) throw new IOException("Steam's shortcut list has no shortcuts in it");
		String instance = mc.toAbsolutePath().getParent().getFileName().toString();
		Path script = mc.toAbsolutePath().resolve("extras").resolve(windows ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh");
		if (!Files.isRegularFile(script)) throw new IOException("this pack's launcher script isn't here yet (" + script.getFileName() + "): press Restart to update the pack, then try again");
		int changed = 0;
		for (Map<String, Object> t : targets) {
			long want;
			try { want = (long) Double.parseDouble(str(t.get("appid"))); } catch (NumberFormatException e) { continue; }
			List<Object[]> entry = null;
			for (Object[] n : list) if (n[2] instanceof List<?>) { Object id = get(nodes(n[2]), "appid"); if (id instanceof Integer i && (i & 0xFFFFFFFFL) == want) entry = nodes(n[2]); }
			if (entry == null) { did.add("A shortcut setup added (app " + want + ") isn't in Steam any more: left alone."); continue; }
			boolean game = !str(t.get("art")).equals("prism") && !str(t.get("art")).equals("launcher");
			String name = str(get(entry, "AppName")), exe = str(get(entry, "Exe")), opts = str(get(entry, "LaunchOptions")), newExe, newOpts, startDir;
			if (windows) {
				// Prism's program: what the shortcut runs now, or what it hands the launcher script already.
				String prism = exe.toLowerCase(java.util.Locale.ROOT).contains("prismlauncher") ? exe.replace("\"", "") : between(opts, "-Prism \"", "\"");
				if (prism.isEmpty()) { did.add("\"" + name + "\" doesn't start Prism Launcher: left alone."); continue; }
				Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
				newExe = "\"" + ps + "\"";
				startDir = "\"" + ps.getParent() + "\\\"";
				newOpts = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + script + "\" -Prism \"" + prism + "\"" + (game ? " -Play " + instance : "");
			} else {
				String app = str(get(entry, "FlatpakAppID"));
				if (app.isEmpty()) { String[] w = opts.trim().split("\\s+"); for (String x : w) if (x.contains(".") && !x.startsWith("-") && !x.startsWith("\"") && !x.startsWith("/")) { app = x; break; } }
				if (app.isEmpty() || !exe.contains("flatpak")) { did.add("\"" + name + "\" doesn't start Prism Launcher's Flatpak: left alone."); continue; }
				newExe = exe; startDir = str(get(entry, "StartDir"));
				newOpts = "run --command=bash " + app + " \"" + script + "\"" + (game ? " --play " + instance : "");
			}
			String newName = name;
			if (!game && !name.startsWith(LAUNCHER_NAME)) {
				int dash = name.lastIndexOf(" - ");
				newName = LAUNCHER_NAME + (dash >= 0 ? name.substring(dash) : "");
			}
			if (!newExe.equals(exe) || !newOpts.equals(opts) || !newName.equals(name)) {
				set(entry, "AppName", newName); set(entry, "Exe", newExe); set(entry, "StartDir", startDir); set(entry, "LaunchOptions", newOpts);
				changed++;
				did.add(newName.equals(name) ? "\"" + name + "\" now starts through the Gatehouse launcher." : "\"" + name + "\" is now \"" + newName + "\": it opens the Gatehouse launcher.");
			}
			// Its controller layout is filed under the shortcut's name: for a renamed one, a folder under the new name.
			if (!game) {
				t.put("art", "launcher");
				Path layouts = Path.of(str(t.get("controller"))).getParent();
				if (layouts != null && !newName.equals(name)) t.put("controller", layouts.resolve(newName.toLowerCase(java.util.Locale.ROOT)).toString());
			}
			// The art and the layout, from the pack as it is on this device now.
			String art = game ? "steam/art" : "steam/art-launcher";
			Path grid = Path.of(str(t.get("grid")));
			int files = 0;
			for (String[] a : new String[][] {{"cover.png", "p.png"}, {"hero.png", "_hero.png"}, {"logo.png", "_logo.png"}, {"icon.png", "_icon.png"}, {"wide.png", ".png"}}) {
				Path src = mc.resolve(art).resolve(a[0]);
				if (Files.isRegularFile(src)) { Files.createDirectories(grid); Files.copy(src, grid.resolve(want + a[1]), StandardCopyOption.REPLACE_EXISTING); files++; }
			}
			Path layout = mc.resolve("steam/controller_neptune.vdf"), dir = Path.of(str(t.get("controller")));
			if (Files.isRegularFile(layout) && dir.getParent() != null && Files.isDirectory(dir.getParent())) {
				Files.createDirectories(dir);
				Path target = dir.resolve("controller_neptune.vdf");
				Files.writeString(target, Files.readString(layout, StandardCharsets.UTF_8).replaceAll("\"url\"\\s*\"[^\"]*\"", java.util.regex.Matcher.quoteReplacement("\"url\"\t\t\"autosave://" + target.toString().replace("\\", "/") + "\"")), StandardCharsets.UTF_8);
				Path set = dir.getParent().resolve("configset_controller_neptune.vdf");
				String text = Files.isRegularFile(set) ? Files.readString(set, StandardCharsets.UTF_8) : "\"controller_config\"\n{\n}\n", key = dir.getFileName().toString();
				if (!text.toLowerCase(java.util.Locale.ROOT).contains("\"" + key.toLowerCase(java.util.Locale.ROOT) + "\"") && text.lastIndexOf('}') >= 0) {
					int end = text.lastIndexOf('}');
					Files.writeString(set, text.substring(0, end) + "\t\"" + key + "\"\n\t{\n\t\t\"autosave\"\t\t\"1\"\n\t}\n" + text.substring(end), StandardCharsets.UTF_8);
				}
				files++;
			}
			did.add("\"" + newName + "\": art and controller layout put in place (" + files + " files).");
		}
		if (changed > 0) {
			Files.write(vdf.resolveSibling("shortcuts.vdf.bak-launcher"), before);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			write(out, root);
			Files.write(vdf, out.toByteArray());
			read(ByteBuffer.wrap(Files.readAllBytes(vdf)).order(ByteOrder.LITTLE_ENDIAN));   // (reads back cleanly, or this throws)
		} else did.add(0, "The shortcuts themselves were already up to date.");
		// What each launch keeps up to date from now on (read by the pack's update): the same list, as it is now.
		StringBuilder sb = new StringBuilder("{\"targets\": [");
		Map<String, Object> pack = null;
		for (int i = 0; i < targets.size(); i++) {
			Map<String, Object> t = targets.get(i);
			if (pack == null && str(t.get("art")).equals("pack")) pack = t;
			sb.append(i == 0 ? "" : ", ").append(one(t, true));
		}
		sb.append("]").append(pack == null ? "" : ", " + one(pack, false)).append("}\n");
		Files.writeString(mc.resolve("steam-target.json"), sb.toString(), StandardCharsets.UTF_8);
		return did;
	}

	private static String one(Map<String, Object> t, boolean braces) {
		long id;
		try { id = (long) Double.parseDouble(str(t.get("appid"))); } catch (NumberFormatException e) { id = 0; }
		String s = "\"grid\": \"" + ServerControl.jsonEsc(str(t.get("grid"))) + "\", \"appid\": " + id + ", \"controller\": \"" + ServerControl.jsonEsc(str(t.get("controller"))) + "\"";
		return braces ? "{" + s + ", \"art\": \"" + ServerControl.jsonEsc(str(t.get("art"))) + "\"}" : s;
	}

	private static String str(Object o) { return o == null ? "" : o.toString(); }
	private static String between(String s, String a, String b) { int i = s.indexOf(a); if (i < 0) return ""; int j = s.indexOf(b, i + a.length()); return j < 0 ? "" : s.substring(i + a.length(), j); }

	// ---- Steam's binary shortcuts.vdf: a tree of {type, key, value}; type 0 a nested map, 1 text, 2 a 32-bit number; 8 ends a map.
	@SuppressWarnings("unchecked")
	private static List<Object[]> nodes(Object o) { return (List<Object[]>) o; }
	private static List<Object[]> read(ByteBuffer b) throws IOException {
		List<Object[]> out = new ArrayList<>();
		try {
			while (true) {
				byte t = b.get();
				if (t == 8) return out;
				String k = text(b);
				if (t == 0) out.add(new Object[] {t, k, read(b)});
				else if (t == 1) out.add(new Object[] {t, k, text(b)});
				else if (t == 2) out.add(new Object[] {t, k, b.getInt()});
				else throw new IOException("Steam's shortcut list has something this doesn't know (type " + t + "): nothing was changed");
			}
		} catch (java.nio.BufferUnderflowException e) { throw new IOException("Steam's shortcut list ends early: nothing was changed"); }
	}
	private static String text(ByteBuffer b) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		for (byte c; (c = b.get()) != 0; ) o.write(c);
		return o.toString(StandardCharsets.UTF_8);
	}
	private static void write(ByteArrayOutputStream o, List<Object[]> list) {
		for (Object[] n : list) {
			o.write((Byte) n[0]); o.writeBytes(((String) n[1]).getBytes(StandardCharsets.UTF_8)); o.write(0);
			if (n[2] instanceof List<?>) write(o, nodes(n[2]));
			else if (n[2] instanceof String s) { o.writeBytes(s.getBytes(StandardCharsets.UTF_8)); o.write(0); }
			else o.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((Integer) n[2]).array());
		}
		o.write(8);
	}
	private static Object get(List<Object[]> e, String key) { for (Object[] n : e) if (((String) n[1]).equalsIgnoreCase(key)) return n[2]; return null; }
	private static void set(List<Object[]> e, String key, String value) {
		for (Object[] n : e) if (((String) n[1]).equalsIgnoreCase(key)) { n[0] = (byte) 1; n[2] = value; return; }
		e.add(new Object[] {(byte) 1, key, value});
	}
}
