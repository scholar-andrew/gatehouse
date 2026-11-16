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
 *  Steam's shortcut list (userdata/&lt;account&gt;/config/shortcuts.vdf, a small binary file) is backed up in the game
 *  folder as shortcuts.vdf.bak-launcher first. Steam has to be closed: it writes that file itself when it exits. */
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

	/** A plain word for the kind of device, as setup works it out (it follows the shortcuts' names): Steam Deck, ROG,
	 *  Legion Go, Laptop (it has a battery) or PC. Null when it can't be told (the name then keeps what it had). */
	static String deviceKind() {
		try {
			String all; boolean battery;
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
						"$b = Get-ItemProperty 'HKLM:/HARDWARE/DESCRIPTION/System/BIOS'; Add-Type -AssemblyName System.Windows.Forms; "
						+ "$b.SystemManufacturer + ' ' + $b.SystemProductName + ' ' + $b.SystemFamily; 'battery=' + [System.Windows.Forms.SystemInformation]::PowerStatus.BatteryChargeStatus").redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0 || !out.contains("battery=")) return null;
				all = out.substring(0, out.indexOf("battery=")).toLowerCase(java.util.Locale.ROOT);
				battery = !out.substring(out.indexOf("battery=")).contains("NoSystemBattery");
			} else {
				Path dmi = Path.of("/sys/class/dmi/id");
				if (!Files.isReadable(dmi.resolve("product_name"))) return null;
				StringBuilder sb = new StringBuilder();
				for (String f : new String[] {"sys_vendor", "product_name", "product_family"}) try { sb.append(Files.readString(dmi.resolve(f)).trim()).append(' '); } catch (IOException ignored) {}
				all = sb.toString().toLowerCase(java.util.Locale.ROOT);
				battery = false;
				try (java.util.stream.Stream<Path> s = Files.list(Path.of("/sys/class/power_supply"))) { battery = s.anyMatch(x -> x.getFileName().toString().startsWith("BAT")); } catch (IOException ignored) {}
			}
			if (all.contains("valve") || all.contains("jupiter") || all.contains("galileo")) return "Steam Deck";
			if (all.contains("rog ally") || all.contains("rog xbox ally")) return "ROG";
			if (all.contains("legion go")) return "Legion Go";
			return battery ? "Laptop" : "PC";
		} catch (IOException | InterruptedException | RuntimeException e) { return null; }
	}

	/** Prism's Flatpak isn't allowed to see Steam's shortcut list yet; the message is the command that allows it. */
	static final class NeedsPermission extends IOException {
		NeedsPermission(String command) { super(command); }
	}

	/** Steam's settings folder for the account in use (userdata/&lt;account&gt;/config, where its shortcut list and art
	 *  are): where another pack's shortcut was put, else (Windows) where the registry says Steam is, for the account
	 *  that signed in last. */
	private static Path steamConfig(Path mc) throws IOException {
		Path instances = mc.toAbsolutePath().getParent().getParent();
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Map<String, Object> st = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(d.resolve("minecraft/steam-target.json"))));
				String grid = str(st.get("grid"));
				if (grid.isEmpty() && st.get("targets") instanceof List<?> l && !l.isEmpty()) grid = str(ServerControl.asMap(l.get(0)).get("grid"));
				if (!grid.isEmpty() && Path.of(grid).getParent() != null) return Path.of(grid).getParent();
			}
		} catch (IOException ignored) {}
		if (System.getProperty("os.name", "").startsWith("Windows")) try {
			Process q = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			q.waitFor();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			if (m.find()) {
				Path steam = Path.of(m.group(1).trim());
				String best = null;
				java.util.regex.Matcher u = java.util.regex.Pattern.compile("\"(7656\\d{13})\"\\s*\\{(.*?)\\}", java.util.regex.Pattern.DOTALL).matcher(LaunchPopup.readFile(steam.resolve("config/loginusers.vdf")));
				while (u.find()) if (best == null || u.group(2).matches("(?s).*\"MostRecent\"\\s*\"1\".*")) best = u.group(1);
				if (best != null) return steam.resolve("userdata").resolve(String.valueOf(Long.parseLong(best) - 76561197960265728L)).resolve("config");
			}
		} catch (IOException | InterruptedException | RuntimeException ignored) {}
		throw new IOException("Steam's files weren't found on this device (Steam isn't installed, or on Linux no pack here has a Steam shortcut yet: run setup for one first)");
	}

	/** The server's name, as its game folder has it (extras/profile.json). */
	private static String packName(Path mc) {
		String n = str(ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("extras/profile.json")))).get("name"));
		return n.isEmpty() ? LaunchPopup.NAME : n;
	}

	/** What adding a shortcut would do, as one line; throws when it can't be done here. */
	static String canAdd(Path mc) throws IOException {
		steamConfig(mc);
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		if (!win && !Files.exists(Path.of("/.flatpak-info"))) throw new IOException("a Steam shortcut can only be added here when Prism Launcher is the Flatpak");
		return "Add a Steam shortcut for " + packName(mc) + " (it starts this server through the Gatehouse launcher)";
	}

	/** Adds a Steam shortcut for the server whose game folder this is, as setup would have: it runs the Gatehouse
	 *  launcher's script for this instance, which starts the game through Prism. Records it in steam-target.json, so
	 *  the usual update then gives it its art and controller layout. Steam has to be closed. Returns what was done. */
	static String add(Path mc) throws IOException {
		boolean windows = System.getProperty("os.name", "").startsWith("Windows");
		Path cfg = steamConfig(mc), vdf = cfg.resolve("shortcuts.vdf");
		String instance = mc.toAbsolutePath().getParent().getFileName().toString();
		Path script = mc.toAbsolutePath().resolve("extras").resolve(windows ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh");
		if (!Files.isRegularFile(script)) throw new IOException("the launcher's script isn't in this server's folder yet: open the Gatehouse launcher's menu once, then try again");
		if (!Files.isRegularFile(vdf) && Files.exists(Path.of("/.flatpak-info"))) {
			String app = "org.prismlauncher.PrismLauncher";
			try { for (String l : Files.readAllLines(Path.of("/.flatpak-info"))) if (l.startsWith("name=")) { app = l.substring(5).trim(); break; } } catch (IOException ignored) {}
			throw new NeedsPermission("flatpak override --user --filesystem=" + vdf + " " + app);
		}
		byte[] before = Files.isRegularFile(vdf) ? Files.readAllBytes(vdf) : new byte[] {0, 's', 'h', 'o', 'r', 't', 'c', 'u', 't', 's', 0, 8, 8};
		List<Object[]> root = read(ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN));
		List<Object[]> list = null;
		for (Object[] n : root) if (((String) n[1]).equalsIgnoreCase("shortcuts") && n[2] instanceof List<?>) list = nodes(n[2]);
		if (list == null) throw new IOException("Steam's shortcut list isn't in a form this knows: nothing was changed");
		String kind = deviceKind(), name = packName(mc) + " - " + (kind == null ? "PC" : kind), exe, startDir, opts, flatpak = "";
		if (windows) {
			Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			exe = "\"" + ps + "\""; startDir = "\"" + ps.getParent() + "\\\"";
			Path prism = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs", "PrismLauncher", "prismlauncher.exe");
			opts = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + script + "\"" + (Files.isRegularFile(prism) ? " -Prism \"" + prism + "\"" : "") + " -Play " + instance;
		} else {
			flatpak = "org.prismlauncher.PrismLauncher";
			try { for (String l : Files.readAllLines(Path.of("/.flatpak-info"))) if (l.startsWith("name=")) { flatpak = l.substring(5).trim(); break; } } catch (IOException ignored) {}
			exe = "\"/usr/bin/flatpak\""; startDir = "\"/usr/bin/\"";
			opts = "run --command=bash " + flatpak + " \"" + script + "\" --play " + instance;
		}
		for (Object[] n : list) if (n[2] instanceof List<?> && name.equals(str(get(nodes(n[2]), "AppName")))) throw new IOException("Steam already has a shortcut called \"" + name + "\"");
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update((exe + name).getBytes(StandardCharsets.UTF_8));
		long appid = (crc.getValue() | 0x80000000L) & 0xFFFFFFFFL;
		Path grid = cfg.resolve("grid");
		List<Object[]> e = new ArrayList<>();
		e.add(new Object[] {(byte) 2, "appid", (int) appid});
		for (String[] kv : new String[][] {{"AppName", name}, {"Exe", exe}, {"StartDir", startDir}, {"icon", grid.resolve(appid + "_icon.png").toString()}, {"ShortcutPath", ""}, {"LaunchOptions", opts}}) e.add(new Object[] {(byte) 1, kv[0], kv[1]});
		for (Object[] kv : new Object[][] {{"IsHidden", 0}, {"AllowDesktopConfig", 1}, {"AllowOverlay", 1}, {"OpenVR", 0}, {"Devkit", 0}}) e.add(new Object[] {(byte) 2, kv[0], kv[1]});
		e.add(new Object[] {(byte) 1, "DevkitGameID", ""});
		e.add(new Object[] {(byte) 2, "DevkitOverrideAppID", 0});
		e.add(new Object[] {(byte) 2, "LastPlayTime", 0});
		e.add(new Object[] {(byte) 1, "FlatpakAppID", flatpak});
		e.add(new Object[] {(byte) 0, "tags", new ArrayList<Object[]>()});
		list.add(new Object[] {(byte) 0, String.valueOf(list.size()), e});
		if (Files.isRegularFile(vdf)) Files.write(mc.resolve("shortcuts.vdf.bak-launcher"), before);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, root);
		Files.createDirectories(cfg);
		Files.write(vdf, out.toByteArray());
		read(ByteBuffer.wrap(Files.readAllBytes(vdf)).order(ByteOrder.LITTLE_ENDIAN));   // (reads back cleanly, or this throws)
		// Where its art and controller layout go, for the update that follows and for each launch after.
		Path layouts = cfg.getParent().getParent().getParent().resolve("steamapps").resolve("common").resolve("Steam Controller Configs").resolve(cfg.getParent().getFileName().toString()).resolve("config");
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("grid", grid.toString()); t.put("appid", String.valueOf(appid)); t.put("controller", layouts.resolve(name.toLowerCase(java.util.Locale.ROOT)).toString()); t.put("art", "pack");
		Files.writeString(mc.resolve("steam-target.json"), "{\"targets\": [" + one(t, true) + "], " + one(t, false) + "}\n", StandardCharsets.UTF_8);
		return "\"" + name + "\" added to Steam.";
	}

	/** Does it for both shortcuts; returns what was done, a line each. */
	static List<String> run(Path mc) throws IOException { return run(mc, "all", true); }

	/** Which game folder holds the record of the Gatehouse launcher's own shortcut (setup files it with the pack it was
	 *  run for): the first of these that has one, or null. */
	static Path launcherHome(List<Path> gameFolders) {
		for (Path mc : gameFolders) {
			Object st = LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("steam-target.json")));
			if (ServerControl.asMap(st).get("targets") instanceof List<?> l) for (Object o : l) { String art = str(ServerControl.asMap(o).get("art")); if (art.equals("prism") || art.equals("launcher")) return mc; }
		}
		return null;
	}

	/** Starts Steam again after an update (it had to be closed). Windows: Steam's own program, as the registry names it.
	 *  Linux: asks the desktop to open a steam: link, which starts Steam (through the Flatpak portal inside Prism's
	 *  sandbox). True when something was started. */
	static boolean startSteam() {
		try {
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				Process q = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamExe").redirectErrorStream(true).start();
				String out = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				q.waitFor();
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("SteamExe\\s+REG_SZ\\s+(.+)").matcher(out);
				if (!m.find()) return false;
				Path exe = Path.of(m.group(1).trim());
				if (!Files.isRegularFile(exe)) return false;
				new ProcessBuilder(exe.toString()).directory(exe.getParent().toFile()).start();
				return true;
			}
			return new ProcessBuilder("xdg-open", "steam://open/main").redirectErrorStream(true).start().waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
		} catch (IOException | InterruptedException | RuntimeException e) { return false; }
	}

	/** The update for one shortcut ("pack": this pack's own; "launcher": the Gatehouse launcher's) or "all". With apply
	 *  false nothing is changed: the lines say what would be (none: it's all up to date). */
	static List<String> run(Path mc, String which, boolean apply) throws IOException {
		List<String> did = new ArrayList<>();
		boolean windows = System.getProperty("os.name", "").startsWith("Windows");
		Map<String, Object> st = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("steam-target.json"))));
		List<Map<String, Object>> targets = new ArrayList<>();
		if (st.get("targets") instanceof List<?> l) for (Object o : l) targets.add(new LinkedHashMap<>(ServerControl.asMap(o)));
		else if (st.containsKey("appid")) { Map<String, Object> one = new LinkedHashMap<>(st); one.put("art", "pack"); targets.add(one); }
		if (targets.isEmpty()) throw new IOException("setup didn't add Steam shortcuts for this pack on this device (run setup to add them)");
		Path vdf = Path.of(str(targets.get(0).get("grid"))).getParent().resolve("shortcuts.vdf");
		if (!Files.isRegularFile(vdf)) {
			// Inside Prism's Flatpak only the folders setup allowed can be seen (the art and the controller layouts), and
			// Steam's shortcut list isn't among them on an install set up before the launcher could change it. Allowing
			// that one file is a change to Prism's permissions, so it's the player's to make: one command, given here.
			if (Files.exists(Path.of("/.flatpak-info"))) {
				String app = "org.prismlauncher.PrismLauncher";
				try { for (String l : Files.readAllLines(Path.of("/.flatpak-info"))) if (l.startsWith("name=")) { app = l.substring(5).trim(); break; } } catch (IOException ignored) {}
				throw new NeedsPermission("flatpak override --user --filesystem=" + vdf + " " + app);
			}
			throw new IOException("Steam's shortcut list isn't where setup left it (" + vdf + ")");
		}
		byte[] before = Files.readAllBytes(vdf);
		List<Object[]> root = read(ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN));
		List<Object[]> list = null;
		for (Object[] n : root) if (((String) n[1]).equalsIgnoreCase("shortcuts") && n[2] instanceof List<?>) list = nodes(n[2]);
		if (list == null) throw new IOException("Steam's shortcut list has no shortcuts in it");
		String instance = mc.toAbsolutePath().getParent().getFileName().toString();
		Path script = mc.toAbsolutePath().resolve("extras").resolve(windows ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh");
		if (!Files.isRegularFile(script)) throw new IOException("this pack's launcher script isn't here yet (" + script.getFileName() + "): press Restart to update the pack, then try again");
		int changed = 0;
		String kind = deviceKind();
		for (Map<String, Object> t : targets) {
			long want;
			try { want = (long) Double.parseDouble(str(t.get("appid"))); } catch (NumberFormatException e) { continue; }
			List<Object[]> entry = null;
			for (Object[] n : list) if (n[2] instanceof List<?>) { Object id = get(nodes(n[2]), "appid"); if (id instanceof Integer i && (i & 0xFFFFFFFFL) == want) entry = nodes(n[2]); }
			if (entry == null) { did.add("A shortcut setup added (app " + want + ") isn't in Steam any more: left alone."); continue; }
			boolean game = !str(t.get("art")).equals("prism") && !str(t.get("art")).equals("launcher");
			if (!which.equals("all") && which.equals("pack") != game) continue;
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
			// Its name: the pack's keeps its own, the launcher's becomes the Gatehouse launcher's; and what follows the dash
			// becomes the kind of device (PC, Laptop, Steam Deck, ROG), as setup names them now, where that can be told.
			int dash = name.lastIndexOf(" - ");
			String first = game ? (dash >= 0 ? name.substring(0, dash) : name) : LAUNCHER_NAME;
			String newName = first + (kind != null ? " - " + kind : dash >= 0 ? name.substring(dash) : "");
			if (!newExe.equals(exe) || !newOpts.equals(opts) || !newName.equals(name)) {
				if (apply) { set(entry, "AppName", newName); set(entry, "Exe", newExe); set(entry, "StartDir", startDir); set(entry, "LaunchOptions", newOpts); }
				changed++;
				boolean how = !newExe.equals(exe) || !newOpts.equals(opts);
				if (apply) did.add(newName.equals(name) ? "\"" + name + "\" now starts through the Gatehouse launcher." : "\"" + name + "\" is now \"" + newName + "\"" + (!how ? "." : game ? ", and starts through the Gatehouse launcher." : ": it opens the Gatehouse launcher."));
				else {
					if (!newName.equals(name)) did.add("Rename \"" + name + "\" to \"" + newName + "\"");
					if (how) did.add(game ? "\"" + newName + "\" starts through the Gatehouse launcher" : "\"" + newName + "\" opens the Gatehouse launcher");
				}
			}
			// Its controller layout is filed under the shortcut's name: for a renamed one, a folder under the new name.
			if (!game && !str(t.get("art")).equals("launcher")) { t.put("art", "launcher"); changed++; }
			Path layouts = Path.of(str(t.get("controller"))).getParent();
			if (layouts != null && !newName.equals(name)) t.put("controller", layouts.resolve(newName.toLowerCase(java.util.Locale.ROOT)).toString());
			// The art and the layout, from the pack as it is on this device now.
			String art = game ? "steam/art" : "steam/art-launcher";
			Path grid = Path.of(str(t.get("grid")));
			int files = 0;
			for (String[] a : new String[][] {{"cover.png", "p.png"}, {"hero.png", "_hero.png"}, {"logo.png", "_logo.png"}, {"icon.png", "_icon.png"}, {"wide.png", ".png"}}) {
				Path src = mc.resolve(art).resolve(a[0]), dst = grid.resolve(want + a[1]);
				if (!Files.isRegularFile(src) || (Files.isRegularFile(dst) && java.util.Arrays.equals(Files.readAllBytes(src), Files.readAllBytes(dst)))) continue;
				if (apply) { Files.createDirectories(grid); Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING); }
				files++;
			}
			int artFiles = files;
			Path layout = mc.resolve("steam/controller_neptune.vdf"), dir = Path.of(str(t.get("controller")));
			if (Files.isRegularFile(layout) && dir.getParent() != null && Files.isDirectory(dir.getParent())) {
				Path target = dir.resolve("controller_neptune.vdf");
				String wantText = Files.readString(layout, StandardCharsets.UTF_8).replaceAll("\"url\"\\s*\"[^\"]*\"", java.util.regex.Matcher.quoteReplacement("\"url\"\t\t\"autosave://" + target.toString().replace("\\", "/") + "\""));
				Path set = dir.getParent().resolve("configset_controller_neptune.vdf");
				String text = Files.isRegularFile(set) ? Files.readString(set, StandardCharsets.UTF_8) : "\"controller_config\"\n{\n}\n", key = dir.getFileName().toString();
				boolean listed = text.toLowerCase(java.util.Locale.ROOT).contains("\"" + key.toLowerCase(java.util.Locale.ROOT) + "\"") || text.lastIndexOf('}') < 0;
				boolean same = Files.isRegularFile(target) && Files.readString(target, StandardCharsets.UTF_8).equals(wantText);
				if (!same || !listed) {
					if (apply) {
						Files.createDirectories(dir);
						Files.writeString(target, wantText, StandardCharsets.UTF_8);
						if (!listed) { int end = text.lastIndexOf('}'); Files.writeString(set, text.substring(0, end) + "\t\"" + key + "\"\n\t{\n\t\t\"autosave\"\t\t\"1\"\n\t}\n" + text.substring(end), StandardCharsets.UTF_8); }
					}
					files++;
				}
			}
			if (files > 0) did.add(apply ? "\"" + newName + "\": art and controller layout put in place (" + files + " file" + (files == 1 ? "" : "s") + ")."
					: "\"" + newName + "\": " + (artFiles > 0 ? artFiles + " art file" + (artFiles == 1 ? "" : "s") : "") + (artFiles > 0 && files > artFiles ? " and " : "") + (files > artFiles ? "the controller layout" : "") + " to refresh");
		}
		if (!apply) return did;
		if (changed > 0) {
			Files.write(mc.resolve("shortcuts.vdf.bak-launcher"), before);   // (in the game folder: beside the list itself may not be writable)
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			write(out, root);
			Files.write(vdf, out.toByteArray());
			read(ByteBuffer.wrap(Files.readAllBytes(vdf)).order(ByteOrder.LITTLE_ENDIAN));   // (reads back cleanly, or this throws)
		} else if (did.isEmpty()) did.add("Already up to date.");
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
