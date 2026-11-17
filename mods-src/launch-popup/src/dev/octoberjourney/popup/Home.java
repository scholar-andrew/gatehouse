package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** The Gatehouse launcher's own window (what the "Gatehouse Minecraft Launcher" Steam shortcut opens):
 *    --home &lt;Prism's instances folder&gt; --prism &lt;how to start Prism Launcher&gt; [--play &lt;instance id&gt;]
 *  Three levels:
 *    1. the Gatekeepers this device knows (the ones its installed packs name, and any added here by address, pairing
 *       link or pairing file), each with whether it answers;
 *    2. the servers one Gatekeeper looks after (its catalog, GET /v1/catalog), each with whether it's on and whether its
 *       pack is installed here;
 *    3. a server's own page: the pack's launcher, reached with Play (Prism starts the instance, the update runs first
 *       and its window appears). Its Menu button comes back here.
 *  With --play there's no window to begin with: that's a pack's own Steam shortcut, and the game starts straight away
 *  when it can. This program stays until Prism closes, so Steam sees the game as running.
 *  What it remembers is in a "gatehouse" folder beside Prism's instances: gatekeepers.json (name and address of each),
 *  and for each Gatekeeper a small folder with this device's id and secret for it (the same ones the packs use, so a
 *  Gatekeeper sees one launcher per device). */
final class Home {
	/** A Gatekeeper said, in answer to this launcher's report, that it knows of a newer version than this launcher is. */
	private static volatile boolean keeperSaysNewer;
	/** How the update that is about to start came about: "normal" (Update was pressed) unless auto mode or a
	 *  constellation's go pressed it. */
	private static volatile String updateHow = "normal";
	private Home() {}

	/** A pack installed in Prism: {instance id, name, address to join, game folder, its Gatekeeper's address or ""}. */
	private static List<String[]> installed(Path instances) {
		List<String[]> out = new ArrayList<>();
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Path mc = Files.isDirectory(d.resolve("minecraft")) ? d.resolve("minecraft") : d.resolve(".minecraft");
				String profile = LaunchPopup.readFile(mc.resolve("extras/profile.json"));
				if (profile.isBlank()) continue;
				String name = str(ServerControl.asMap(LaunchPopup.parseJson(profile)).get("name")), address = "";
				try { if (LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("extras/servers.json"))) instanceof List<?> l && !l.isEmpty()) address = str(ServerControl.asMap(l.get(0)).get("address")); } catch (RuntimeException ignored) {}
				String keeper = "";
				try { keeper = norm(new ServerControl(mc).helperUrl); } catch (RuntimeException ignored) {}
				out.add(new String[] {d.getFileName().toString(), name.isEmpty() ? d.getFileName().toString() : name, address, mc.toString(), keeper});
			}
		} catch (IOException ignored) {}
		return out;
	}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	/** Runs one of the Connections buttons' calls through the shared "press" handling, once. */
	private static final class ActionListenerOnce {
		static void run(JButton b, java.util.function.BiConsumer<JButton, java.util.concurrent.Callable<Map<String, Object>>> press, java.util.concurrent.Callable<Map<String, Object>> call) {
			JButton proxy = new JButton();
			press.accept(proxy, call);
			b.setEnabled(false);
			proxy.doClick(0);
		}
	}

	/** A list's panel that is always exactly as wide as the scrolling area it sits in, so a row's long status line is
	 *  shortened (with its full text on hover) and the buttons on its right stay in view. */
	private static final class Fitted extends JPanel implements javax.swing.Scrollable {
		Fitted() { super(new BorderLayout()); }
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) { return 16; }
		@Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) { return 96; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
	/** An address as it's kept: http://host:port, no slash at the end. Empty for nothing. */
	static String norm(String url) {
		String u = url == null ? "" : url.trim().replaceAll("/+$", "");
		if (u.isEmpty()) return "";
		if (!u.matches("(?i)https?://.*")) u = "http://" + u;
		try { java.net.URI x = java.net.URI.create(u); if (x.getHost() == null) return ""; return x.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + x.getHost() + ":" + (x.getPort() > 0 ? x.getPort() : 25570); }
		catch (RuntimeException e) { return ""; }
	}
	private static String shown(String url) { return url.replaceFirst("(?i)^https?://", ""); }

	/** What was pasted or opened to add a Gatekeeper: an address, a pairing link (gatehouse://pair/&lt;address&gt;?name=...) or
	 *  the text of a pairing file ({"address": ..., "name": ...}). Returns {address, name} or null. */
	static String[] parsePairing(String text) {
		String t = text == null ? "" : text.trim();
		if (t.isEmpty()) return null;
		if (t.startsWith("{")) {
			Map<String, Object> m = ServerControl.asMap(LaunchPopup.parseJson(t));
			String a = norm(str(m.get("address")));
			return a.isEmpty() ? null : new String[] {a, str(m.get("name")), Connect.invite(str(m.get("tailscale_share")))};
		}
		String name = "", share = "";
		// (an invite to a server names its Gatekeeper the same way: gatehouse://invite/<address>?code=...&name=...)
		if (t.toLowerCase(java.util.Locale.ROOT).startsWith("gatehouse://invite/")) t = "gatehouse://pair/" + t.substring("gatehouse://invite/".length());
		if (t.toLowerCase(java.util.Locale.ROOT).startsWith("gatehouse://pair/")) {
			t = t.substring("gatehouse://pair/".length());
			int q = t.indexOf('?');
			if (q >= 0) {
				for (String kv : t.substring(q + 1).split("&")) try {
					if (kv.startsWith("name=")) name = java.net.URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8);
					else if (kv.startsWith("ts=")) share = Connect.invite(java.net.URLDecoder.decode(kv.substring(3), StandardCharsets.UTF_8));   // (the owner's Tailscale invite)
				} catch (RuntimeException ignored) {}
				t = t.substring(0, q);
			}
		}
		String a = norm(t);
		return a.isEmpty() ? null : new String[] {a, name, share};
	}

	/** The code in an invite to a server (gatehouse://invite/<address>?code=ABCDE-FGHIJ, or the code by itself), or "". */
	static String inviteCode(String text) {
		String t = text == null ? "" : text.trim();
		java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(?i)[?&]code=([A-Za-z0-9\\-]{10,12})").matcher(t);
		if (t.toLowerCase(java.util.Locale.ROOT).startsWith("gatehouse://invite/") && mt.find()) return mt.group(1).toUpperCase(java.util.Locale.ROOT);
		return t.matches("(?i)[A-Z0-9]{5}-?[A-Z0-9]{5}") ? t.toUpperCase(java.util.Locale.ROOT) : "";
	}
	/** Hands an invite's code to its Gatekeeper, and returns what it said. */
	static String useInvite(ServerControl c, String code) {
		try {
			String say = "{\"code\":\"" + ServerControl.jsonEsc(code) + "\"}";
			Map<String, Object> r = c.helper("POST", "/v1/invite/accept", say);
			if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("POST", "/v1/invite/accept", say); }
			if ("404".equals(str(r.get("_code")))) return "That Gatekeeper is from before invites (older than Gatehouse 3.17.0), so the invite can't be used until its owner updates it.";
			return str(r.get("message")).isEmpty() ? "The Gatekeeper didn't take the invite (" + str(r.get("_code")) + ")." : str(r.get("message"));
		} catch (IOException | RuntimeException e) { return "The Gatekeeper isn't answering, so the invite wasn't used. It is still good: add it again once this device can reach it."; }
	}

	/** This device's line to one Gatekeeper: a small folder of its own (so the launcher's usual code can talk to it),
	 *  given the id and secret an installed pack already uses for that Gatekeeper when there is one. */
	static ServerControl client(Path home, String url, List<String[]> packs) {
		Path dir = home.resolve("keepers").resolve(shown(url).replaceAll("[^A-Za-z0-9.-]", "_"));
		try {
			Files.createDirectories(dir.resolve("extras"));
			Files.writeString(dir.resolve("extras/server-control.json"), "{\"helper_url\": \"" + ServerControl.jsonEsc(url) + "\"}\n", StandardCharsets.UTF_8);
			// (which Gatekeeper a pack belongs to is read from the pack itself, not from the list: after Remove its packs
			// are listed under Other packs, and adding the Gatekeeper again must find the same id, or the owner would
			// see this device as a new one with nothing allowed)
			if (!Files.isRegularFile(dir.resolve("server-access.json"))) for (String[] p : packs) {
				ServerControl sc = new ServerControl(Path.of(p[3]));
				if (!norm(sc.helperUrl).equals(url)) continue;
				if (sc.deviceId != null && !sc.deviceId.isEmpty() && sc.deviceToken != null && sc.deviceToken.length() >= 32) {
					Files.writeString(dir.resolve("server-access.json"), "{\"device_id\": \"" + ServerControl.jsonEsc(sc.deviceId) + "\", \"device_token\": \"" + ServerControl.jsonEsc(sc.deviceToken) + "\"}\n", StandardCharsets.UTF_8);
					break;
				}
			}
		} catch (IOException | RuntimeException ignored) {}
		return new ServerControl(dir);
	}

	/** A Gatekeeper's catalog: {"name": ..., "servers": [...]}; null when it doesn't answer; "_refused" set when it says no. */
	private static Map<String, Object> catalog(ServerControl c) {
		try {
			Map<String, Object> r = c.helper("GET", "/v1/catalog", null);
			if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("GET", "/v1/catalog", null); }
			if ("404".equals(str(r.get("_code")))) { r = new LinkedHashMap<>(); r.put("_old", "yes"); return r; }   // (a Gatekeeper from before catalogs)
			if (!"200".equals(str(r.get("_code")))) r.put("_refused", str(r.get("error")).isEmpty() ? "it said no (" + str(r.get("_code")) + ")" : str(r.get("error")));
			return r;
		} catch (IOException | RuntimeException e) { return null; }
	}

	/** Launch pressed: Prism Launcher being started for that pack while the window is still up (it goes when the
	 *  pack's own window has taken its place). */
	private static volatile Thread launching;
	private static volatile boolean launchBusy;
	/** A pack to start as soon as the window is up, as if Launch had been pressed (this launcher was opened from that
	 *  pack's own Steam shortcut); autoDirect: and to go straight into the game when nothing needs to be seen first. */
	private static volatile String[] autoLaunch;
	private static volatile boolean autoDirect;

	/** Can this game folder's launcher be shown inside this window: "pack" (its update script can be run from here
	 *  and knows about Play pressed here), "plain" (a plain server, with a script that knows the same), or "" (it
	 *  opens in a window of its own, as it always did). */
	static String embeddable(Path dir) {
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		Path sync = dir.resolve(win ? "pack-sync.ps1" : "pack-sync.sh"), plain = dir.resolve(win ? "plain-launch.ps1" : "plain-launch.sh");
		if (Files.isRegularFile(sync)) return LaunchPopup.readFile(sync).contains("GATEHOUSE_PLAY") ? "pack" : "";
		return Files.isRegularFile(plain) && LaunchPopup.readFile(plain).contains("GATEHOUSE_PLAY") ? "plain" : "";
	}

	/** Starts a pack's update script from here (GATEHOUSE_EMBED=1: it opens no window of its own, reports into its
	 *  status file, and ends when done instead of waiting for Play). Null when this pack has no such script, or one
	 *  from before it could be run this way: the pack then updates in its own window, as it always did. */
	static Process startSync(Path dir) {
		try {
			boolean win = System.getProperty("os.name", "").startsWith("Windows");
			Path script = dir.resolve(win ? "pack-sync.ps1" : "pack-sync.sh");
			if (!Files.isRegularFile(script) || !LaunchPopup.readFile(script).contains("GATEHOUSE_EMBED")) return null;
			ProcessBuilder pb = new ProcessBuilder(win ? List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString()) : List.of("bash", script.toString()));
			pb.directory(dir.toFile());
			pb.environment().put("GATEHOUSE_EMBED", "1");
			// (the Java this launcher runs on does for the update too: Prism isn't there to hand one over)
			String java = ProcessHandle.current().info().command().orElse("");
			if (!java.isEmpty() && pb.environment().get("INST_JAVA") == null) pb.environment().put("INST_JAVA", java);
			pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
			// (what the last run said is cleared here, before the script gets to it: the checklist starts reading at once,
			// and must not take the last run's "done" for this one's)
			Files.writeString(dir.resolve("pack-sync-status.txt"), "", StandardCharsets.UTF_8);
			Files.deleteIfExists(dir.resolve("pack-sync-answer.txt"));
			Files.deleteIfExists(dir.resolve("pack-sync-fresh.txt"));
			return pb.start();
		} catch (IOException | RuntimeException e) { return null; }
	}
	private static volatile String prismFor = "prismlauncher";
	/** Where a server's window was when its Gatehouse button was pressed: this window opens there. */
	private static volatile java.awt.Rectangle cameBack;

	static void run(String[] args) throws Exception {
		// Testing: OJ_CONNECT_SHOT=<pairing link>, with OJ_POPUP_PNG: a picture of the Connect steps for it (<file>-connect.png).
		if (System.getenv("OJ_CONNECT_SHOT") != null && System.getenv("OJ_POPUP_PNG") != null) {
			String[] got = parsePairing(System.getenv("OJ_CONNECT_SHOT"));
			System.out.println("pairing read as: " + (got == null ? "nothing" : shown(got[0]) + " | " + got[1] + " | invite " + (got[2].isEmpty() ? "none" : got[2])));
			if (got != null) { String[] g = got; SwingUtilities.invokeAndWait(() -> { dev.gatehouse.look.SteamLook.apply(); try { Connect.picture(System.getenv("OJ_POPUP_PNG").replaceAll("\\.png$", "") + "-connect.png", g[0], g[1], g[2]); } catch (IOException e) { e.printStackTrace(); } }); }
			return;
		}
		// Testing: OJ_HOME_LAUNCH=<game folder> runs that pack's update the way Launch does, in a window that isn't
		// shown, and says how it went (with OJ_POPUP_PNG, the checklist's pictures are taken too).
		if (System.getenv("OJ_HOME_LAUNCH") != null) {
			Path dir = Path.of(System.getenv("OJ_HOME_LAUNCH"));
			Process sync = startSync(dir);
			if (sync == null) { System.out.println("embedded update: this pack's script can't be run from Gatehouse"); return; }
			JFrame[] tf = {null};
			SwingUtilities.invokeAndWait(() -> { dev.gatehouse.look.SteamLook.apply(); tf[0] = new JFrame(); tf[0].setSize(900, 600); tf[0].addNotify(); });   // (laid out, never shown)
			String done = LaunchPopup.checklist(dir, tf[0], sync);
			sync.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
			if (done != null && System.getenv("OJ_HOME_LAUNCH_PAGE") != null) {   // (and the launcher's page, in that same window: pictures)
				LaunchPopup.exitCode = 0;
				LaunchPopup.summary(dir, done);
				System.out.println("launcher page in the same window: ended with " + LaunchPopup.exitCode);
			}
			System.out.println("embedded update: " + done + "; script ended " + (sync.isAlive() ? "no" : "with " + sync.exitValue()) + "; note left: " + LaunchPopup.readFile(dir.resolve("pack-sync-fresh.txt")).trim().replaceAll("^\\d+", "<when>"));
			SwingUtilities.invokeAndWait(() -> tf[0].dispose());
			return;
		}
		Path instances = Path.of(args[1]), home = instances.toAbsolutePath().getParent().resolve("gatehouse");
		String prism = "prismlauncher", play = null;
		for (int i = 2; i + 1 < args.length; i += 2) { if (args[i].equals("--prism")) prism = args[i + 1]; else if (args[i].equals("--play")) play = args[i + 1]; }
		String prismCmd = prism;
		prismFor = prism;
		String openAt = null;   // (a Gatekeeper to open on: the one whose server's Menu button brought us back)
		while (true) {
			PackInstall.refreshPlain(instances);
			List<String[]> packs = installed(instances);
			if (play != null) {
				String want = play; play = null;
				String[] hit = null;
				for (String[] e : packs) if (e[0].equals(want)) hit = e;
				if (hit != null) {
					// Inside this window when it can be (the update, then straight into the game unless there's something to
					// see or the server isn't ready); otherwise the pack's own window, as before.
					if (!embeddable(Path.of(hit[3])).isEmpty() && System.getenv("OJ_HOME_DRY") == null && !java.awt.GraphicsEnvironment.isHeadless()) { autoLaunch = hit; openAt = hit[4]; }
					else { LaunchPopup.startGame(prismCmd, hit, true); openAt = back(packs); if (openAt == null) return; continue; }
				}
			}
			String[][] chosen = {null};
			boolean[] openPrism = {false};
			String[] again = {null};
			window(instances, home, packs, openAt, chosen, openPrism, again);
			if ("\u0000restart".equals(again[0])) { openAt = null; continue; }   // (Restart: the main window again, from the top)
			if ("\u0000relaunch".equals(again[0])) {
				// A newer Gatehouse is in place. The shortcut's script opens it when this ends with RELAUNCH; a script from
				// before 2.6.1 does that only once, so on a second update under one of those the new one is opened from here.
				if (System.getenv("GATEHOUSE_RELAUNCH") == null && System.getenv("GATEHOUSE_UPDATED") != null && Notes.reopen(instances, args)) System.exit(0);
				System.exit(LaunchPopup.RELAUNCH);
			}
			if (again[0] != null) { openAt = again[0]; continue; }
			if (chosen[0] != null) {
				Thread t = launching; launching = null;
				if (t == null) LaunchPopup.startGame(prismCmd, chosen[0], false);
				// (already started, with the window up: wait for Prism to finish, or for the pack's Gatehouse button)
				else while (t.isAlive() && !Files.exists(Path.of(chosen[0][3]).resolve("back-to-menu"))) Thread.sleep(120);
			}
			else if (openPrism[0]) LaunchPopup.run(List.of(prismCmd));
			else return;
			if (System.getenv("OJ_HOME_DRY") != null) return;
			openAt = back(installed(instances));
			if (openAt == null) return;
		}
	}

	/** Did a pack's launcher ask to come back to this menu (its Menu button leaves a "back-to-menu" note in the game
	 *  folder)? Returns that pack's Gatekeeper ("" for none), or null when nobody asked. */
	private static String back(List<String[]> packs) {
		String at = null;
		for (String[] e : packs) {
			Path note = Path.of(e[3]).resolve("back-to-menu");
			if (Files.exists(note)) { cameBack = LaunchPopup.readBounds(note); try { Files.deleteIfExists(note); } catch (IOException ignored) {} at = e[4]; }
		}
		return at;
	}

	// ---- what's remembered
	static List<String[]> savedKeepers(Path home) {
		List<String[]> out = new ArrayList<>();
		if (LaunchPopup.parseJson(LaunchPopup.readFile(home.resolve("gatekeepers.json"))) instanceof List<?> l)
			for (Object o : l) { String u = norm(str(ServerControl.asMap(o).get("url"))); if (!u.isEmpty()) out.add(new String[] {u, str(ServerControl.asMap(o).get("name")), Connect.invite(str(ServerControl.asMap(o).get("tailscale_share")))}); }
		return out;
	}
	/** What kind of machine each Gatekeeper said it is on (gatekeeper-devices.txt: address, a tab, the kind), kept so
	 *  its card shows the right picture while it isn't answering too. */
	private static final Map<String, String> keeperDevices = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean devicesRead;
	private static synchronized String deviceOf(Path home, String url) {
		if (!devicesRead) { devicesRead = true; for (String line : LaunchPopup.readFile(home.resolve("gatekeeper-devices.txt")).split("\\R")) { String[] p = line.split("\t"); if (p.length == 2) keeperDevices.put(p[0], p[1].trim()); } }
		return keeperDevices.getOrDefault(url, "");
	}
	private static synchronized void rememberDevice(Path home, String url, String kind) {
		if (kind.equals(deviceOf(home, url))) return;
		if (kind.isEmpty()) keeperDevices.remove(url); else keeperDevices.put(url, kind);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : keeperDevices.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
		try { Files.createDirectories(home); Files.writeString(home.resolve("gatekeeper-devices.txt"), sb.toString(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	/** The pictures Gatekeepers gave for themselves and their servers, kept in Gatehouse's own folder (icons/) under
	 *  the tag the Gatekeeper gave each, so one is fetched once and again only when it changes. */
	private static final Map<String, java.awt.image.BufferedImage> pictures = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<String> picturesAsked = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static String pictureStem(String url, String what) { return Integer.toHexString(url.hashCode()) + "-" + what.replaceAll("[^A-Za-z0-9_\\-]", "_") + "-"; }
	/** Shows on a card the picture a Gatekeeper has for "gatekeeper" or one of its servers (tag: what it said in its
	 *  catalog; "" for none). */
	private static void picture(Path home, ServerControl c, String url, String what, String tag, dev.gatehouse.look.GameCard card) {
		if (tag.isEmpty()) { SwingUtilities.invokeLater(() -> card.picture(null)); return; }
		String key = pictureStem(url, what) + tag.replaceAll("[^A-Za-z0-9]", "");
		java.awt.image.BufferedImage have = pictures.get(key);
		if (have != null) { SwingUtilities.invokeLater(() -> card.picture(have)); return; }
		if (!picturesAsked.add(key)) return;
		Thread t = new Thread(() -> {
			try {
				Path f = home.resolve("icons").resolve(key + ".png");
				byte[] b;
				if (Files.isRegularFile(f)) b = Files.readAllBytes(f);
				else {
					java.net.HttpURLConnection h = (java.net.HttpURLConnection) java.net.URI.create(url.replaceAll("/+$", "") + "/v1/icon?for=" + java.net.URLEncoder.encode(what, StandardCharsets.UTF_8)).toURL().openConnection();
					h.setConnectTimeout(3000); h.setReadTimeout(15000);
					h.setRequestProperty("X-Device", str(c.deviceId)); h.setRequestProperty("X-Token", str(c.deviceToken));
					if (h.getResponseCode() != 200) return;
					try (java.io.InputStream in = h.getInputStream()) { b = in.readNBytes(4_000_000); }
					Files.createDirectories(f.getParent());
					Files.write(f, b);
				}
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
				if (img != null) { pictures.put(key, img); if (SwingUtilities.isEventDispatchThread()) card.picture(img); else SwingUtilities.invokeLater(() -> card.picture(img)); }
			} catch (IOException | RuntimeException ignored) { /* its initials, or its machine, as before */ }
			finally { picturesAsked.remove(key); }
		}, "picture");
		t.setDaemon(true);
		if (System.getenv("OJ_POPUP_PNG") != null) t.run(); else t.start();   // (testing: a picture of the window waits for its pictures)
	}
	/** The picture last fetched for a Gatekeeper itself, for its card while it isn't answering (null for none). */
	private static java.awt.image.BufferedImage lastPicture(Path home, String url) {
		try (java.util.stream.Stream<Path> s = Files.list(home.resolve("icons"))) {
			String stem = pictureStem(url, "gatekeeper");
			Path newest = s.filter(p -> p.getFileName().toString().startsWith(stem)).max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
			return newest == null ? null : javax.imageio.ImageIO.read(newest.toFile());
		} catch (IOException | RuntimeException none) { return null; }
	}
	private static void saveKeepers(Path home, List<String[]> keepers) {
		StringBuilder sb = new StringBuilder("[");
		for (String[] k : keepers) sb.append(sb.length() > 1 ? ",\n " : "\n ").append("{\"url\": \"").append(ServerControl.jsonEsc(k[0])).append("\", \"name\": \"").append(ServerControl.jsonEsc(k[1])).append(k.length > 2 && !k[2].isEmpty() ? "\", \"tailscale_share\": \"" + ServerControl.jsonEsc(k[2]) : "").append("\"}");
		try { Files.createDirectories(home); Files.writeString(home.resolve("gatekeepers.json"), sb.append("\n]\n").toString(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}

	/** Gatekeepers taken off this device's list with Remove (gatekeepers-removed.json): an installed pack that names
	 *  one doesn't bring it back; its packs are listed under Other packs. Adding it again clears this. */
	static java.util.Set<String> removedKeepers(Path home) {
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		if (LaunchPopup.parseJson(LaunchPopup.readFile(home.resolve("gatekeepers-removed.json"))) instanceof List<?> l) for (Object o : l) { String u = norm(str(o)); if (!u.isEmpty()) out.add(u); }
		return out;
	}
	private static void saveRemoved(Path home, java.util.Set<String> urls) {
		StringBuilder sb = new StringBuilder("[");
		for (String u : urls) sb.append(sb.length() > 1 ? ", " : "").append('"').append(ServerControl.jsonEsc(u)).append('"');
		try { Files.createDirectories(home); Files.writeString(home.resolve("gatekeepers-removed.json"), sb.append("]\n").toString(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	/** Forgets a Gatekeeper on this device: off the list (and kept off), and this device's own line to it (the small
	 *  folder with the id and secret it introduced itself with) deleted. Installed packs aren't touched. */
	static void forgetKeeper(Path home, List<String[]> keepers, String[] k) {
		keepers.remove(k);
		saveKeepers(home, keepers);
		java.util.Set<String> gone = removedKeepers(home);
		gone.add(k[0]);
		saveRemoved(home, gone);
		Path dir = home.resolve("keepers").resolve(shown(k[0]).replaceAll("[^A-Za-z0-9.-]", "_"));
		try (java.util.stream.Stream<Path> s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }); } catch (IOException ignored) {}
	}

	// ---- Options: removing Gatehouse
	/** How Gatehouse can be removed from this device, worked out from how it got here:
	 *    {"script", what it is, the command...}   installed by itself (gatehouse-install): its own script, kept in its
	 *                                             home, is run with "uninstall" and its answer shown
	 *    {"program", what it is, the command...}  it came with a pack on Windows: that pack's own uninstaller is opened
	 *    {"tell", what to do}                     neither can be done from here (inside Prism's Flatpak nothing outside
	 *                                             it can be reached; or no uninstaller is known): said instead
	 *  "purge": also what Gatehouse remembered. */
	static String[] uninstallPlan(Path instances, boolean purge) {
		boolean win = System.getProperty("os.name", "").startsWith("Windows"), flatpak = Files.exists(Path.of("/.flatpak-info"));
		Path root = instances.toAbsolutePath().getParent(), own = Notes.app(instances), script = own.resolve(win ? "gatehouse-install.ps1" : "gatehouse-install.sh");
		List<String> extra = new ArrayList<>();
		String more = System.getenv("OJ_UNINSTALL_EXTRA");   // (testing: more options for the script)
		if (more != null && !more.isBlank()) extra.addAll(List.of(more.trim().split("\\s+")));
		if (Files.isRegularFile(script)) {
			if (!win && flatpak) return new String[] {"tell", "Gatehouse runs inside Prism Launcher's Flatpak here, which can't reach your applications menu or Steam. To remove it completely, run this in a terminal (on a Steam Deck: Desktop Mode, Konsole):\nbash \"" + script + "\" --uninstall" + (purge ? " --purge" : "")};
			List<String> cmd = new ArrayList<>(win ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Uninstall", "-Gatehouse", "-Yes", "-PrismRoot", root.toString())
					: List.of("bash", script.toString(), "--uninstall", "--yes", "--prism-root=" + root));
			if (purge) cmd.add(win ? "-Purge" : "--purge");
			cmd.addAll(extra);
			cmd.add(0, "Gatehouse was installed by itself on this device. Its own uninstall script takes out its files, its menu entry and its Steam shortcut. Prism Launcher and anything installed in it are left alone.");
			cmd.add(0, "script");
			return cmd.toArray(new String[0]);
		}
		if (win) try {
			// (a pack's setup lists itself with Windows' installed apps, with the uninstaller it saved)
			Process q = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "/s", "/v", "UninstallString").redirectErrorStream(true).start();
			String out = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			q.waitFor();
			String key = "";
			for (String line : out.split("\\R")) {
				if (line.startsWith("HKEY_")) key = line.substring(line.lastIndexOf('\\') + 1).trim();
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("UninstallString\\s+REG_SZ\\s+\"?([^\"]+?)\"?\\s*$").matcher(line);
				if (m.find() && (key.startsWith("Gatehouse") || key.equals("OctoberJourney")) && Files.isRegularFile(Path.of(m.group(1))))
					return new String[] {"program", "Gatehouse came with a pack on this device (" + key + "), and is removed with it. This opens that pack's own uninstaller, which lets you choose what goes.", m.group(1)};
			}
		} catch (IOException | InterruptedException | RuntimeException ignored) {}
		// It came with a pack, and no uninstaller for that is on record: the script Gatehouse carries does it.
		Path carried = Path.of(System.getProperty("java.io.tmpdir"), "gatehouse-uninstall", script.getFileName().toString());
		if (!win && flatpak) {
			Path seen = root.resolve("gatehouse").resolve(script.getFileName().toString());
			try { carried(seen); } catch (IOException e) { return new String[] {"tell", "Gatehouse came with a pack on this device, and is removed with it: run that pack's uninstaller (\"Uninstall ...\" in the applications menu, or gatehouse-uninstall.sh)."}; }
			return new String[] {"tell", "Gatehouse runs inside Prism Launcher's Flatpak here, which can't reach your applications menu or Steam. To remove it, run this in a terminal (on a Steam Deck: Desktop Mode, Konsole):\nbash \"" + seen + "\" --uninstall --with-pack" + (purge ? " --purge" : "")};
		}
		List<String> cmd = new ArrayList<>(win ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", carried.toString(), "-Uninstall", "-Gatehouse", "-WithPack", "-Yes", "-PrismRoot", root.toString())
				: List.of("bash", carried.toString(), "--uninstall", "--with-pack", "--yes", "--prism-root=" + root));
		if (purge) cmd.add(win ? "-Purge" : "--purge");
		cmd.addAll(extra);
		cmd.add(0, "Gatehouse came with a pack on this device. Uninstall takes out what is Gatehouse's own: its program files in Prism Launcher's folder, its menu entry, and its \"Gatehouse\" Steam shortcut if Steam is closed. Prism Launcher and the servers installed in it stay; a server is removed in Prism Launcher (right-click, Delete), and until then starting one there still opens its launcher page.");
		cmd.add(0, "script");
		return cmd.toArray(new String[0]);
	}
	/** Writes the install script Gatehouse carries inside itself to that place (for uninstalling with it). */
	static void carried(Path to) throws IOException {
		try (java.io.InputStream in = Home.class.getResourceAsStream("/" + to.getFileName())) {
			if (in == null) throw new IOException("this Gatehouse doesn't carry its uninstall script");
			Files.createDirectories(to.getParent());
			Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** When this machine also hosts (the Host half is set up and running here), this launcher links itself to that
	 *  Gatekeeper through the door only this machine can use: no pairing link, no request, no Tailscale. It is then
	 *  allowed everything there, and the account it plays as is whitelisted. Uses the pairing it already has with that
	 *  Gatekeeper when there is one; otherwise adds it, reached on this machine itself. True when the list changed.
	 *  (Not under OJ_HOME_DRY, so a test never links to the real Gatekeeper; OJ_KEEPER_DIR names a stand-in one.) */
	/** Where the launcher that is running was fetched from, as noted beside the copy it was started from when it was
	 *  fetched (launch-popup.source.txt, with its checksum); "" when it came another way (with a pack's own update, or
	 *  an installer). */
	static String updateSource(Path instances) {
		try {
			Path me = Path.of(Home.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(me)) return "";
			String mine = LaunchPopup.sha256(Files.readAllBytes(me));
			List<Path> notes = new ArrayList<>();
			notes.add(Notes.app(instances).resolve("extras/launch-popup.source.txt"));
			try (java.util.stream.Stream<Path> s = Files.list(instances)) { s.forEach(i -> notes.add(i.resolve("minecraft/extras/launch-popup.source.txt"))); } catch (IOException none) { /* no instances */ }
			for (Path n : notes) { String[] t = LaunchPopup.readFile(n).trim().split("\\|", 2); if (t.length == 2 && t[0].equalsIgnoreCase(mine)) return t[1]; }
		} catch (Exception ignored) {}
		return "";
	}
	/** The id this install of Gatehouse gave itself the first time it was asked (install-id.txt in its own folder): a
	 *  random one, the same to every Gatekeeper it reports to, so that a Gatekeeper which hears of this launcher twice
	 *  (from itself, and through another Gatekeeper that joined its constellation) knows it is one launcher. */
	static String installId(Path home) {
		Path f = home.resolve("install-id.txt");
		String id = LaunchPopup.readFile(f).trim();
		if (id.matches("[0-9a-fA-F\\-]{16,64}")) return id;
		id = java.util.UUID.randomUUID().toString();
		try { Files.createDirectories(home); Files.writeString(f, id + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {}
		return id;
	}
	/** How this device's owner has it update (gatehouse-options.json in Gatehouse's own folder): normal unless it says
	 *  auto or constellation. (From before the modes, "auto_update": true meant what is now constellation.) */
	static String updateMode(Path home) {
		Map<String, Object> o = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(home.resolve("gatehouse-options.json"))));
		String m = str(o.get("update_mode"));
		if (m.equals("auto") || m.equals("constellation")) return m;
		return "true".equals(str(o.get("auto_update"))) ? "constellation" : "normal";
	}
	/** The Gatekeeper in this launcher's list that is the one on this same machine (as its address is listed), or "". */
	static volatile String hereUrl = "";
	static boolean linkHere(Path instances, Path home, List<String[]> keepers, List<String[]> packs) {
		hereUrl = "";
		if (System.getenv("OJ_HOME_DRY") != null && System.getenv("OJ_KEEPER_DIR") == null) return false;
		int doorPort = dev.gatehouse.Main.keeperDoor();   // (asked for, not looked for: its folder isn't visible from inside a sandbox)
		if (doorPort <= 0) return false;
		String door = "http://127.0.0.1:" + doorPort;
		try {
			Map<String, Object> about = ServerControl.asMap(LaunchPopup.parseJson(local("GET", door + "/admin/local/link", null)));
			if (str(about.get("port")).isEmpty()) return false;   // (a Gatekeeper from before this)
			String bound = norm(str(about.get("bind_url"))), url = null;
			for (String[] k : keepers) if (!bound.isEmpty() && k[0].equals(bound)) url = k[0];
			for (String[] k : keepers) if (url == null && k[0].equals(norm(door))) url = k[0];
			boolean changed = false;
			if (url == null) { url = norm(door); keepers.add(0, new String[] {url, str(about.get("name")), ""}); changed = true; java.util.Set<String> gone = removedKeepers(home); }
			hereUrl = url;
			// (remembered, so the launcher still knows which Gatekeeper is this machine's while it isn't running)
			try { Files.createDirectories(home); if (!url.equals(LaunchPopup.readFile(home.resolve("keeper-here.txt")).trim())) Files.writeString(home.resolve("keeper-here.txt"), url + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {}
			ServerControl sc = client(home, url, packs);
			// the account this device plays as, from Prism's own list (its name and its id, which a whitelist needs)
			String player = "", uuid = "";
			try {
				if (ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(instances.toAbsolutePath().getParent().resolve("accounts.json")))).get("accounts") instanceof List<?> l) for (Object o : l) {
					Map<String, Object> a = ServerControl.asMap(o), p = ServerControl.asMap(a.get("profile"));
					if (player.isEmpty() || "true".equals(str(a.get("active")))) { player = str(p.get("name")); uuid = str(p.get("id")); }
				}
			} catch (RuntimeException ignored) {}
			String name = "";
			try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ignored) {}
			local("POST", door + "/v1/hello", "{\"id\":\"" + ServerControl.jsonEsc(sc.deviceId) + "\",\"token\":\"" + ServerControl.jsonEsc(sc.deviceToken) + "\",\"name\":\"" + ServerControl.jsonEsc(name) + "\",\"os\":\"" + ServerControl.jsonEsc(ServerControl.systemName()) + "\"}");
			local("POST", door + "/admin/local/link", "{\"id\":\"" + ServerControl.jsonEsc(sc.deviceId) + "\",\"token\":\"" + ServerControl.jsonEsc(sc.deviceToken) + "\",\"player\":\"" + ServerControl.jsonEsc(player) + "\",\"uuid\":\"" + ServerControl.jsonEsc(uuid) + "\"}");
			return changed;
		} catch (IOException | RuntimeException e) { return false; }
	}
	private static String local(String method, String url, String body) throws IOException {
		java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
		c.setConnectTimeout(1500); c.setReadTimeout(8000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	// ---- the window
	private static void window(Path instances, Path home, List<String[]> packs, String openAt, String[][] chosen, boolean[] openPrism, String[] again) throws Exception {
		// The Gatekeepers: the ones remembered, and any an installed pack names that isn't remembered yet.
		List<String[]> keepers = savedKeepers(home);
		// (packs of a Gatekeeper that was removed here count as packs no Gatekeeper looks after)
		java.util.Set<String> removed = removedKeepers(home);
		for (String[] p : packs) if (removed.contains(p[4])) p[4] = "";
		boolean added = false;
		for (String[] p : packs) if (!p[4].isEmpty()) { boolean have = false; for (String[] k : keepers) if (k[0].equals(p[4])) have = true; if (!have) { keepers.add(new String[] {p[4], "", ""}); added = true; } }
		if (added) saveKeepers(home, keepers);
		if (linkHere(instances, home, keepers, packs)) saveKeepers(home, keepers);
		// (each Gatekeeper is a place the program is found, and is handed one this launcher finds first)
		List<String[]> peers = new ArrayList<>();
		for (String[] k : keepers) { ServerControl sc = client(home, k[0], packs); peers.add(new String[] {k[0], str(sc.deviceId), str(sc.deviceToken)}); }
		Notes.peers = peers;
		boolean loose = false;
		for (String[] p : packs) if (p[4].isEmpty()) loose = true;
		boolean hasLoose = loose;
		java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
		String shot = System.getenv("OJ_POPUP_PNG");
		SwingUtilities.invokeAndWait(() -> {
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = new LaunchPopup.HostFrame(LaunchPopup.TOOL);
			f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(14, 24, 10, 24));
			JLabel head = new JLabel(LaunchPopup.TOOL + " Minecraft Launcher");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 24f));
			JLabel sub = new JLabel(" ");
			sub.setFont(sub.getFont().deriveFont(14f));
			JButton backB = new JButton("‹ Gatekeepers");
			backB.setToolTipText("Back to the list of Gatekeepers");
			backB.setVisible(false);
			// (the wheel: Options)
			JButton gear = new JButton(dev.gatehouse.look.SteamLook.gear(18));
			gear.setToolTipText("Options");
			JPanel headButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			headButtons.add(backB);
			JPanel headRow = new JPanel(new BorderLayout(10, 0));
			headRow.add(head, BorderLayout.CENTER); headRow.add(headButtons, BorderLayout.EAST);
			JPanel top = new JPanel(new BorderLayout(0, 4));
			// Gatehouse's own update, asked about here (apart from any pack's, which its server's launcher asks about).
			JPanel ownUpdate = new JPanel(new BorderLayout(10, 0));
			ownUpdate.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x3D6FD6)), BorderFactory.createEmptyBorder(8, 12, 8, 8)));
			JLabel ownUpdateText = new JLabel(" ");
			JButton ownUpdateGo = new LaunchPopup.PlayButton("Update " + LaunchPopup.TOOL), ownUpdateLater = new JButton("Later");
			ownUpdateGo.setToolTipText("Fetches the newer " + LaunchPopup.TOOL + " and reopens it. No game or pack is touched.");
			ownUpdateLater.setToolTipText("Carry on with this one; you'll be asked again the next time " + LaunchPopup.TOOL + " is opened");
			JPanel ownUpdateButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			ownUpdateButtons.add(ownUpdateLater); ownUpdateButtons.add(ownUpdateGo);
			ownUpdate.add(ownUpdateText, BorderLayout.CENTER); ownUpdate.add(ownUpdateButtons, BorderLayout.EAST);
			ownUpdate.setVisible(false);
			// (in constellation mode, while a Gatekeeper is gathering an update: a pip for every client it is taking along)
			dev.gatehouse.look.Pips gatherPips = new dev.gatehouse.look.Pips();
			JPanel subRow = new JPanel(new BorderLayout(0, 2));
			subRow.add(sub, BorderLayout.CENTER);
			top.add(headRow, BorderLayout.NORTH); top.add(subRow, BorderLayout.CENTER); top.add(ownUpdate, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			for (javax.swing.JComponent clear : new javax.swing.JComponent[] {p, top, headRow, headButtons, subRow}) clear.setOpaque(false);
			// The window is laid out like a game's menu (GameShell): the sections across the top, this page in the
			// middle, and the prompts along the bottom. Which Gatekeeper is open, and whether its servers are showing:
			dev.gatehouse.look.GameShell[] shell = {null};
			String[] openUrl = {null};
			boolean[] atServers = {false};
			CardLayout cards = new CardLayout();
			JPanel deck = new JPanel(cards);
			deck.setOpaque(false);
			JPanel keeperList = new JPanel(), serverList = new JPanel(), connList = new JPanel();
			javax.swing.JTabbedPane news = new javax.swing.JTabbedPane();   // (what changed: Gatehouse's own notes, and the open Gatekeeper's)
			JPanel updating = new JPanel();
			updating.setLayout(new BoxLayout(updating, BoxLayout.Y_AXIS));
			for (JPanel l : new JPanel[] {keeperList, serverList, connList}) dev.gatehouse.look.GameCard.grid(l);   // (cards, in as many columns as fit)
			// (beside the servers: more about the one in focus)
			dev.gatehouse.look.GameCard.Side side = new dev.gatehouse.look.GameCard.Side();
			dev.gatehouse.look.GameCard.onHot = card -> { if (SwingUtilities.isDescendingFrom(card, serverList)) side.show(card); };
			for (Object[] c : new Object[][] {{keeperList, "keepers"}, {serverList, "Servers"}, {connList, "Connections"}}) {
				JPanel inner = new Fitted();   // (as wide as the window, never wider: a long line is cut short, not the buttons pushed out)
				inner.add((JPanel) c[0], BorderLayout.NORTH);
				JScrollPane sc = new JScrollPane(inner);
				sc.setBorder(null);
				sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
				sc.getVerticalScrollBar().setUnitIncrement(16);
				dev.gatehouse.look.GameShell.clear(sc);
				if (c[1].equals("Servers")) { JPanel both = new JPanel(new BorderLayout(14, 0)); both.setOpaque(false); both.add(sc, BorderLayout.CENTER); both.add(side, BorderLayout.EAST); deck.add(both, "servers"); }
				else deck.add(sc, c[1].equals("keepers") ? "keepers" : "machine");
			}
			// Gatehouse's own notes, newest on top: always there to read, and in front when Gatehouse has just updated.
			Map<String, Object> own = Notes.own();
			String seenFile = "gatehouse-version-seen.txt", ownVersion = Notes.version(own), seenVersion = LaunchPopup.readFile(home.resolve(seenFile)).trim();
			boolean fresh = !ownVersion.isEmpty() && !ownVersion.equals(seenVersion);
			news.addTab(fresh && !seenVersion.isEmpty() ? "Gatehouse updates (new)" : "Gatehouse updates", Notes.panel(own, "Gatehouse", "No notes came with this copy of Gatehouse."));
			Runnable seenNow = () -> {
				if (ownVersion.isEmpty()) return;
				try { Files.createDirectories(home); Files.writeString(home.resolve(seenFile), ownVersion + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {}
				news.setTitleAt(0, "Gatehouse updates");
			};
			// A Gatekeeper's own notes, asked of it: the third tab beside its servers.
			JPanel keeperNotes = new JPanel(new BorderLayout());
			news.addTab("Gatekeeper updates", keeperNotes);
			// ---- Options (the wheel at the top right): which Gatehouse this is, where it lives, and removing it.
			JPanel options = new JPanel();
			options.setLayout(new javax.swing.BoxLayout(options, javax.swing.BoxLayout.Y_AXIS));
			options.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
			boolean byItself = Files.isRegularFile(Notes.app(instances).resolve("extras/launch-popup.jar"));
			java.util.function.BiConsumer<String, Boolean> optLine = (text, bold) -> {
				JLabel l = new JLabel("<html><div style='width:470px'>" + LaunchPopup.esc(text).replace("\n", "<br>") + "</div></html>");
				if (bold) { l.setFont(l.getFont().deriveFont(Font.BOLD, 15f)); l.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0)); }
				else l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
				l.setAlignmentX(Component.LEFT_ALIGNMENT);
				options.add(l);
			};
			java.util.function.BiConsumer<String, Boolean> updLine = (text, bold) -> {
				JLabel l = new JLabel("<html><div style='width:640px'>" + LaunchPopup.esc(text).replace("\n", "<br>") + "</div></html>");
				if (bold) { l.setFont(l.getFont().deriveFont(Font.BOLD, 15f)); l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0)); }
				else l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
				l.setAlignmentX(Component.LEFT_ALIGNMENT);
				updating.add(l);
			};
			optLine.accept("About", true);
			optLine.accept(LaunchPopup.TOOL + " " + Notes.version(Notes.own()) + ". " + (byItself ? "Installed by itself, in " + Notes.app(instances) + "." : "It came with a pack installed in Prism Launcher on this device.")
					+ " " + packs.size() + (packs.size() == 1 ? " server is" : " servers are") + " installed here.", false);
			optLine.accept("Setup guide", true);
			optLine.accept("The steps from a device with nothing set up to playing on a server, each with a check for whether it's done.", false);
			JButton guideB = new JButton("Open the setup guide");
			JPanel guideRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
			guideRow.add(guideB); guideRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			options.add(guideRow);
			Runnable[] openGuide = {null};
			guideB.addActionListener(ev -> { if (openGuide[0] != null) openGuide[0].run(); });
			// How this launcher updates: normal unless changed here (kept in Gatehouse's own folder on this device).
			updLine.accept("Updating", true);
			String modeNow = updateMode(home);
			javax.swing.JRadioButton modeNormal = new javax.swing.JRadioButton("Normal: update when I press Update", modeNow.equals("normal")),
					modeAuto = new javax.swing.JRadioButton("Auto: update by itself as soon as a new version is out", modeNow.equals("auto")),
					modeGroup = new javax.swing.JRadioButton("Constellation: update together with my Gatekeeper and its other launchers", modeNow.equals("constellation"));
			javax.swing.ButtonGroup modeGroupOf = new javax.swing.ButtonGroup();
			java.awt.event.ActionListener pickMode = ev -> { try { Files.createDirectories(home); Files.writeString(home.resolve("gatehouse-options.json"), "{\"update_mode\": \"" + (modeAuto.isSelected() ? "auto" : modeGroup.isSelected() ? "constellation" : "normal") + "\"}\n", StandardCharsets.UTF_8); } catch (IOException ignored) {} };
			for (javax.swing.JRadioButton rb : new javax.swing.JRadioButton[] {modeNormal, modeAuto, modeGroup}) { modeGroupOf.add(rb); rb.setAlignmentX(Component.LEFT_ALIGNMENT); rb.addActionListener(pickMode); updating.add(rb); }
			updLine.accept("Normal is what it is unless you choose otherwise. Auto: while this window is open, " + LaunchPopup.TOOL + " updates itself as soon as it sees a new version, without waiting for anyone. Constellation: " + LaunchPopup.TOOL + " waits for a Gatekeeper in your list that is in constellation mode too; that Gatekeeper waits until every such launcher has seen the new version, and then they all update at once. With a Gatekeeper that isn't in constellation mode, you press Update as in Normal.", false);
			optLine.accept("Play and Host", true);
			optLine.accept(LaunchPopup.TOOL + " is one program with two halves: Play (this launcher) and Host (Gatekeeper, which looks after the Minecraft servers on a machine). The first screen opens either one, or sets up the one this device doesn't have yet.", false);
			JButton halvesB = new JButton("Play or Host\u2026");
			halvesB.addActionListener(ev -> dev.gatehouse.Main.choose(f));
			JPanel halvesRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
			halvesRow.add(halvesB); halvesRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			options.add(halvesRow);
			optLine.accept("Uninstall", true);
			String[] planNow = uninstallPlan(instances, false);
			optLine.accept(planNow[1], false);
			javax.swing.JCheckBox forget = new javax.swing.JCheckBox("Also remove what Gatehouse remembers: its Gatekeepers, and this device's identity with them");
			forget.setAlignmentX(Component.LEFT_ALIGNMENT);
			forget.setVisible(planNow[0].equals("script"));
			JButton uninstall = new LaunchPopup.PlayButton("Uninstall " + LaunchPopup.TOOL + "\u2026", LaunchPopup.QUIT_RED, Color.WHITE, 14f);
			uninstall.setToolTipText("Asks you to type confirm first");
			uninstall.setVisible(!planNow[0].equals("tell"));
			JPanel uninstallRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 8));
			uninstallRow.add(uninstall); uninstallRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			JButton appsList = new JButton("Open Windows' installed apps");
			appsList.setToolTipText("Windows Settings, Apps: where a pack that was installed with its setup program is listed");
			appsList.setVisible(planNow[0].equals("tell") && System.getProperty("os.name", "").startsWith("Windows"));
			appsList.addActionListener(ev -> { try { new ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:appsfeatures").start(); } catch (IOException ex) { JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>Windows' list of installed apps couldn't be opened from here. It is in Settings, Apps, Installed apps.</div></html>", "Installed apps", JOptionPane.PLAIN_MESSAGE); } });
			uninstallRow.add(appsList);
			options.add(forget); options.add(uninstallRow);
			uninstall.addActionListener(ev -> {
				String[] plan = uninstallPlan(instances, forget.isSelected());
				javax.swing.JTextField typed = new javax.swing.JTextField(16);
				JPanel ask = new JPanel(new BorderLayout(0, 8));
				ask.add(new JLabel("<html><div style='width:400px'>" + (plan[0].equals("script") ? "This removes " + LaunchPopup.TOOL + " from this device" + (forget.isSelected() ? ", with what it remembers" : "") + ". " : "This opens the uninstaller of the pack " + LaunchPopup.TOOL + " came with. ")
						+ "Type <b>confirm</b> to go ahead.</div></html>"), BorderLayout.NORTH);
				ask.add(typed, BorderLayout.CENTER);
				if (JOptionPane.showConfirmDialog(f, ask, "Uninstall " + LaunchPopup.TOOL, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				if (!typed.getText().trim().equalsIgnoreCase("confirm")) { JOptionPane.showMessageDialog(f, "Nothing was removed: the word typed wasn't \"confirm\".", "Uninstall " + LaunchPopup.TOOL, JOptionPane.PLAIN_MESSAGE); return; }
				uninstall.setEnabled(false);
				new Thread(() -> {
					String said; boolean gone = false;
					try {
						List<String> cmd = new ArrayList<>(java.util.Arrays.asList(plan).subList(2, plan.length));
						// (a Gatehouse that came with a pack has no script of its own on disk: the one it carries is put out)
						for (String part : cmd) if (part.contains("gatehouse-uninstall") && part.contains("gatehouse-install.") && !part.startsWith("--")) carried(Path.of(part));
						if (System.getenv("OJ_UNINSTALL_DRY") != null) said = "would run: " + String.join(" ", cmd);
						else if (plan[0].equals("program")) { new ProcessBuilder(cmd).start(); said = "The pack's uninstaller is open. " + LaunchPopup.TOOL + " closes now."; gone = true; }
						else {
							Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
							pr.getOutputStream().close();
							said = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
							gone = pr.waitFor() == 0 && !Files.exists(Notes.app(instances).resolve("extras/launch-popup.jar"));
						}
					} catch (IOException | InterruptedException | RuntimeException e) { said = "Couldn't run the uninstaller: " + e.getMessage(); }
					String msg = said; boolean done = gone;
					SwingUtilities.invokeLater(() -> {
						JOptionPane.showMessageDialog(f, "<html><div style='width:460px'>" + LaunchPopup.esc(msg).replace("\n", "<br>") + (done ? "<br><br>" + LaunchPopup.TOOL + " closes now." : "") + "</div></html>", "Uninstall " + LaunchPopup.TOOL, JOptionPane.PLAIN_MESSAGE);
						if (!done) { uninstall.setEnabled(true); return; }
						// (on Windows this ran from a copy that is still in use: taken away a moment after closing)
						if (System.getProperty("os.name", "").startsWith("Windows")) try {
							new ProcessBuilder("cmd.exe", "/c", "ping -n 3 127.0.0.1 >nul & del /q \"" + instances.toAbsolutePath().getParent().resolve("gatehouse-launcher-run*.jar") + "\"").start();
						} catch (IOException ignored) {}
						f.dispose();
					});
				}, "uninstall").start();
			});
			JPanel optionsTop = new JPanel(new BorderLayout()); optionsTop.add(options, BorderLayout.NORTH);
			JScrollPane optionsScroll = new JScrollPane(optionsTop); optionsScroll.setBorder(null);
			optionsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			deck.add(dev.gatehouse.look.GameShell.sheet(optionsScroll), "options");
			gear.addActionListener(ev -> {
				head.setText("Options");
				sub.setText("About this " + LaunchPopup.TOOL + ", and removing it.");
				backB.setVisible(false);
				cards.show(deck, "options");
				shell[0].light("OPTIONS");
			});
			deck.setPreferredSize(new Dimension(680, 330));
			p.add(deck, BorderLayout.CENTER);
			List<Runnable> polls = new ArrayList<>();   // (what the open level asks again every few seconds)
			Runnable[] showKeepers = {null};
			java.util.function.Consumer<String> showServers;

			// A row: a name, a line under it with a dot, and things on the right.
			java.util.function.BiFunction<String, Component[], Object[]> row = (name, right) -> {
				dev.gatehouse.look.GameCard r = new dev.gatehouse.look.GameCard(name, right);
				r.state.setIcon(LaunchPopup.dot(LaunchPopup.colour("off")));
				r.state.setText(" ");
				return new Object[] {r, r.name, r.state};
			};
			java.util.function.BiConsumer<JLabel, String[]> set = (label, st) -> { Runnable ui = () -> { label.setIcon(LaunchPopup.dot(LaunchPopup.colour(st[0]))); label.setText(st[1]); label.setToolTipText(st[1]); };
				if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui); };

			// ---- level 2: one Gatekeeper's servers
			showServers = url -> {
				polls.clear();
				serverList.removeAll();
				side.clear();
				String kname = "";
				for (String[] k : keepers) if (k[0].equals(url)) kname = k[1];
				head.setText(url.isEmpty() ? "Other packs" : kname.isEmpty() ? "Gatekeeper at " + shown(url) : kname);
				sub.setText(url.isEmpty() ? "Packs on this device that no Gatekeeper looks after." : "Pick a server to play. The pack updates first, as always.");
				backB.setVisible(true);
				Map<String, Object[]> rows = new LinkedHashMap<>();   // server id -> {state label, pack or null, Play button, name label}
				java.util.function.BiConsumer<String, String[]> add = (id, pack) -> {
					LaunchPopup.PlayButton go = new LaunchPopup.PlayButton("Launch");   // (not "Play": it opens the server's launcher, which has Play)
					go.setToolTipText("Updates this pack, then shows its window before the game starts");
					JLabel missing = new JLabel("Not installed on this device");
					missing.setForeground(new Color(0x8F98A0));
					JButton install = new JButton("Install");
					install.setToolTipText("Sets this server's pack up in Prism Launcher on this device. The mods download on the first launch.");
					install.setVisible(false);
					JButton inviteB = new JButton("Invite…");
					inviteB.setToolTipText("Makes an invite to send someone: their Gatehouse can then install this server's pack and join. It doesn't put them on the whitelist or give them any control.");
					inviteB.setVisible(false);
					Object[] r = row.apply(pack != null ? pack[1] : id, new Component[] {missing, inviteB, install, go});
					go.setVisible(pack != null); missing.setVisible(pack == null);
					if (pack != null) go.addActionListener(ev -> {
						if (launchBusy) return;
						if (System.getenv("OJ_HOME_DRY") != null || shot != null) { chosen[0] = pack; f.dispose(); return; }   // (testing)
						launchBusy = true;
						go.setEnabled(false);
						f.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
						// Prism Launcher starts the instance with this window still up; the window goes when the pack's
						// own window has taken its place (the note is gone), or nothing came of it (Prism finished, or
						// 30 seconds passed).
						Runnable handOver = () -> {
							chosen[0] = pack;
							Path note = Path.of(pack[3]).resolve(LaunchPopup.HANDOFF);
							LaunchPopup.leaveBounds(note, f);
							Thread t = new Thread(() -> LaunchPopup.startGame(prismFor, pack, false), "launch");
							launching = t;
							t.start();
							long since = System.currentTimeMillis();
							new Timer(100, tick -> {
								if (Files.exists(note) && t.isAlive() && System.currentTimeMillis() - since < 30_000) return;
								((Timer) tick.getSource()).stop();
								try { Files.deleteIfExists(note); } catch (IOException ignored) {}
								launchBusy = false;
								f.dispose();
							}).start();
						};
						// The pack's update, run from here with its checklist in this window, when the pack's update
						// script knows how to be run that way (one from before Gatehouse 2.1.0 opens its own window).
						Process sync = startSync(Path.of(pack[3]));
						boolean plainHere = sync == null && embeddable(Path.of(pack[3])).equals("plain");   // (a plain server: no update, just its launcher's page)
						boolean direct = autoDirect; autoDirect = false;
						if (sync == null && !plainHere) { sub.setText("Opening " + pack[1] + "\u2026"); handOver.run(); return; }
						java.awt.Container homePage = f.getContentPane();
						String homeTitle = f.getTitle();
						List<java.awt.event.WindowListener> homeListeners = List.of(f.getWindowListeners());
						Process[] running = {sync};
						new Thread(() -> {
							Path dir = Path.of(pack[3]);
							// (a script from pack 1.18.0 can be run from here but doesn't know about Play pressed here:
							// its launcher still opens as a window of its own, in this one's place)
							boolean whole = plainHere || LaunchPopup.readFile(dir.resolve(System.getProperty("os.name", "").startsWith("Windows") ? "pack-sync.ps1" : "pack-sync.sh")).contains("GATEHOUSE_PLAY");
							boolean first = true;
							while (true) {
								String done;
								if (plainHere) { done = "ok"; LaunchPopup.loadProfile(dir); LaunchPopup.embedded = true; LaunchPopup.reuse = f; LaunchPopup.embedQuit = false; }
								else try { done = LaunchPopup.checklist(dir, f, running[0]); } catch (Exception ex) { done = "stopped"; }
								boolean quit = LaunchPopup.embedQuit;
								LaunchPopup.embedQuit = false;
								if (done == null) {   // Quit (back to the list of servers, in the same place), or the window was closed
									running[0].destroy();
									LaunchPopup.reuse = null; LaunchPopup.embedded = false;
									launchBusy = false;
									if (!quit) return;
									SwingUtilities.invokeLater(() -> { cameBack = f.getBounds(); again[0] = pack[4]; f.dispose(); });
									return;
								}
								if (!whole || done.equals("stopped")) {
									LaunchPopup.reuse = null; LaunchPopup.embedded = false;
									SwingUtilities.invokeLater(() -> {
										f.setCursor(java.awt.Cursor.getDefaultCursor());
										JLabel line = LaunchPopup.progressSub;
										if (line != null) line.setText("Up to date. Opening " + pack[1] + "'s launcher\u2026");
										handOver.run();
									});
									return;
								}
								// The server's launcher, in this window. (The script has ended; its note isn't needed.)
								if (running[0] != null) try { running[0].waitFor(15, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
								// (opened from the pack's own shortcut: straight into the game when nothing needs seeing first, which
								// the launcher's page decides for itself on finding this note; only the first time round)
								try { if (direct && first) Files.writeString(dir.resolve("direct-launch"), String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8); else Files.deleteIfExists(dir.resolve("direct-launch")); } catch (IOException ignored) {}
								first = false;
								try { Files.deleteIfExists(dir.resolve("pack-sync-fresh.txt")); } catch (IOException ignored) {}
								LaunchPopup.exitCode = 0; LaunchPopup.embedBack = false;
								// (its buttons "close the window": here that ends the page, as closing would have, and no more)
								LaunchPopup.embedClose = () -> {
									for (java.awt.event.WindowListener l : f.getWindowListeners()) if (!homeListeners.contains(l)) {
										l.windowClosed(new java.awt.event.WindowEvent(f, java.awt.event.WindowEvent.WINDOW_CLOSED));
										f.removeWindowListener(l);
									}
								};
								SwingUtilities.invokeLater(() -> f.setCursor(java.awt.Cursor.getDefaultCursor()));
								try { LaunchPopup.summary(dir, done); } catch (Exception ex) { LaunchPopup.exitCode = LaunchPopup.QUIT; LaunchPopup.embedBack = true; }
								int chose = LaunchPopup.exitCode;
								boolean back = LaunchPopup.embedBack;
								LaunchPopup.embedClose = null; LaunchPopup.embedBack = false; LaunchPopup.exitCode = 0;
								if (chose == LaunchPopup.RESTART && !back) {   // the update again, here
									if (plainHere) continue;   // (nothing to update: its page again)
									Process again2 = startSync(dir);
									if (again2 != null) { running[0] = again2; SwingUtilities.invokeLater(() -> f.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE)); continue; }
									back = true;
								}
								LaunchPopup.reuse = null; LaunchPopup.embedded = false;
								if (back) {   // the Gatehouse button: this window's own page again, as it was
									SwingUtilities.invokeLater(() -> {
										f.setContentPane(homePage);
										f.setTitle(homeTitle);
										f.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
										go.setEnabled(true);
										launchBusy = false;
										f.validate();
										f.repaint();
										Nav.home(f);
									});
									return;
								}
								if (chose == 0) {
									// Play: Prism starts the instance; its run of the script finds this note and starts the game.
									try { Files.writeString(dir.resolve("pack-sync-fresh.txt"), (System.currentTimeMillis() / 1000) + "|play\n", StandardCharsets.US_ASCII); } catch (IOException ignored) {}
									SwingUtilities.invokeLater(() -> { chosen[0] = pack; launchBusy = false; f.dispose(); });
								} else SwingUtilities.invokeLater(() -> { launchBusy = false; f.dispose(); });   // Quit: nothing starts
								return;
							}
						}, "embedded-sync").start();
					});
					// (opened from this pack's own Steam shortcut: as if Launch had been pressed)
					if (pack != null && autoLaunch != null && autoLaunch[0].equals(pack[0])) { autoLaunch = null; autoDirect = true; SwingUtilities.invokeLater(go::doClick); }
					serverList.add((JPanel) r[0]);
					rows.put(id, new Object[] {r[2], pack, go, r[1], missing, install, r[0], inviteB});
				};
				for (String[] pk : packs) if (pk[4].equals(url)) add.accept(pk[0].startsWith("gatehouse-") ? pk[0].substring("gatehouse-".length()) : pk[0], pk);
				ServerControl c = url.isEmpty() ? null : client(home, url, packs);
				shell[0].light("SERVERS"); openUrl[0] = url; atServers[0] = true;
				keeperNotes.removeAll();
				JLabel asking = new JLabel(c == null ? " " : "Asking the Gatekeeper\u2026");
				asking.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 0));
				keeperNotes.add(asking, BorderLayout.NORTH);
				if (c != null) {
					Runnable about = () -> {
						Map<String, Object> r;
						try { r = c.helper("GET", "/v1/about", null); if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("GET", "/v1/about", null); } } catch (IOException | RuntimeException e) { r = null; }
						Map<String, Object> got = r;
						Runnable ui = () -> {
							keeperNotes.removeAll();
							if (got != null && "200".equals(str(got.get("_code")))) keeperNotes.add(Notes.panel(got, "Gatekeeper", "This Gatekeeper sent no notes."), BorderLayout.CENTER);
							else { JLabel no = new JLabel(got == null ? "This Gatekeeper isn't answering, so its notes can't be shown." : "This Gatekeeper is from before it kept notes of its own (before Gatekeeper 2.0.0)."); no.setForeground(new Color(0x8F98A0)); no.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 0)); keeperNotes.add(no, BorderLayout.NORTH); }
							keeperNotes.revalidate(); keeperNotes.repaint();
						};
						if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
					};
					if (shot != null) about.run(); else new Thread(about, "about").start();
				}
				String[] connShown = {null};
				Runnable poll = () -> {
					Map<String, Object> cat = c == null ? null : catalog(c);
					Runnable ui = () -> {
						if (cat != null && cat.get("servers") instanceof List<?> l) {
							String nm = str(cat.get("name"));
							for (String[] k : keepers) if (k[0].equals(url) && !nm.isEmpty() && !nm.equals(k[1])) { k[1] = nm; saveKeepers(home, keepers); head.setText(nm); }
							for (Object o : l) {
								Map<String, Object> s = ServerControl.asMap(o);
								String id = str(s.get("id"));
								if (!rows.containsKey(id)) { add.accept(id, null); serverList.revalidate(); }
								Object[] r = rows.get(id);
								{   // (its picture, and what the panel beside the cards says about it)
									dev.gatehouse.look.GameCard card = (dev.gatehouse.look.GameCard) r[6];
									picture(home, c, url, id, str(s.get("icon")), card);
									boolean up = "true".equals(str(s.get("online")));
									String ph = str(s.get("phase")), kindIs = str(s.get("kind")), ver = str(s.get("minecraft_version"));
									List<String[]> facts = new ArrayList<>();
									facts.add(new String[] {"Now", up ? "Online" : ph.equals("starting") ? "Starting" : ph.equals("stopping") ? "Shutting down" : "Off"});
									facts.add(new String[] {"Game", (kindIs.isEmpty() ? "" : kindIs.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + kindIs.substring(1)) + (ver.isEmpty() ? "" : (kindIs.isEmpty() ? "" : ", ") + "Minecraft " + ver)});
									facts.add(new String[] {"Address", str(s.get("address"))});
									facts.add(new String[] {"Run by", "true".equals(str(s.get("native"))) ? "Gatekeeper itself" : "MC Server Soft, through Gatekeeper"});
									facts.add(new String[] {"On this device", r[1] != null ? "Installed: Launch updates its pack, then shows its page with Play" : str(s.get("profile")).isEmpty() && !kindIs.equals("vanilla") ? "Not installed, and its pack isn't published for launchers: ask the owner" : "Not installed yet: Install sets it up in Prism Launcher"});
									facts.add(new String[] {"What you may do", "true".equals(str(s.get("allowed"))) ? "Start and stop this server from here (under Machine)" : "Play, once you are on its whitelist. Starting and stopping it is the host's to allow."});
									String vis = str(s.get("visibility"));
									if (!vis.isEmpty()) facts.add(new String[] {"Who it is for", vis.equals("public") ? "Public: every launcher that connects to this Gatekeeper. You can invite people." : vis.equals("shareable") ? "Shareable: launchers its owner let in, and anyone they invite. You can invite people." : "Private: only launchers its owner let in"});
									card.facts(facts);
								}
								{   // (Invite: for a server its owner made shareable or public)
									JButton vb = (JButton) r[7];
									boolean can = "true".equals(str(s.get("invite")));
									if (vb.isVisible() != can) { vb.setVisible(can); serverList.revalidate(); }
									if (can && vb.getActionListeners().length == 0) vb.addActionListener(ev -> {
										vb.setEnabled(false);
										new Thread(() -> {
											Map<String, Object> got;
											try { got = c.helper("POST", "/v1/invite", "{\"server\":\"" + ServerControl.jsonEsc(id) + "\"}"); } catch (IOException | RuntimeException ex) { got = null; }
											Map<String, Object> g = got;
											SwingUtilities.invokeLater(() -> {
												vb.setEnabled(true);
												if (g == null || !"ok".equals(str(g.get("result")))) { JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + LaunchPopup.esc(g == null ? "The Gatekeeper isn't answering." : str(g.get("message")).isEmpty() ? "The Gatekeeper didn't make an invite." : str(g.get("message"))) + "</div></html>", "Invite", JOptionPane.PLAIN_MESSAGE); return; }
												javax.swing.JTextField link = new javax.swing.JTextField(str(g.get("link")), 40);
												link.setEditable(false);
												JButton copy = new JButton("Copy");
												copy.addActionListener(e2 -> { try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(link.getText()), null); copy.setText("Copied"); } catch (RuntimeException ignored) {} });
												JPanel box = new JPanel(new BorderLayout(0, 8));
												box.add(new JLabel("<html><div style='width:440px'>An invite to <b>" + LaunchPopup.esc(str(g.get("server"))) + "</b>, good until " + LaunchPopup.esc(str(g.get("until"))) + ".<br><br>" + LaunchPopup.esc(str(g.get("message"))) + "</div></html>"), BorderLayout.NORTH);
												JPanel ln = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); ln.add(link); ln.add(copy);
												box.add(ln, BorderLayout.CENTER);
												JOptionPane.showMessageDialog(f, box, "Invite", JOptionPane.PLAIN_MESSAGE);
											});
										}, "invite").start();
									});
								}
								if (r[1] == null) ((JLabel) r[3]).setText(str(s.get("name")).isEmpty() ? id : str(s.get("name")));
								boolean on = "true".equals(str(s.get("online")));
								String profile = str(s.get("profile"));
								String kind = str(s.get("kind")), version = str(s.get("minecraft_version"));
								boolean plain = profile.isEmpty() && kind.equals("vanilla");
								String phase = str(s.get("phase"));
								set.accept((JLabel) r[0], new String[] {on ? "ok" : phase.equals("starting") || phase.equals("stopping") ? "warn" : "off", (on ? "Online" : phase.equals("starting") ? "Starting\u2026" : phase.equals("stopping") ? "Shutting down\u2026" : "Off") + (version.isEmpty() ? "" : ", Minecraft " + version) + (r[1] != null ? ""
										: plain ? (version.isEmpty() ? ". A plain server, but its version isn't known yet (it has to have been started once)." : ". A plain server: Install sets up a connection to it on this device.")
										: profile.isEmpty() ? ". It has mods, and its pack isn't published for launchers to install: ask the owner." : ". Install sets its pack up on this device.")});
								JButton ib = (JButton) r[5];
								if (r[1] == null && plain && !version.isEmpty() && !ib.isVisible() && ib.getActionListeners().length == 0) {
									ib.setVisible(true); ((JLabel) r[4]).setVisible(false);
									ib.setToolTipText("Makes a plain Minecraft " + version + " instance in Prism Launcher that joins this server");
									String plainName = str(s.get("name")).isEmpty() ? id : str(s.get("name")), addr = str(s.get("address"));
									ib.addActionListener(ev -> {
										int ok = JOptionPane.showConfirmDialog(f, "<html><div style='width:420px'>Set up <b>" + LaunchPopup.esc(plainName) + "</b> on this device?<br><br>It makes a plain Minecraft " + LaunchPopup.esc(version)
												+ " instance in Prism Launcher with this server (" + LaunchPopup.esc(addr) + ") in its server list. No mods. Prism downloads the game the first time you press Play. "
												+ "Its own launcher window can add a Steam shortcut afterwards; Prism Launcher has to be installed already.</div></html>", "Install", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
										if (ok != JOptionPane.OK_OPTION) return;
										try { PackInstall.vanilla(instances, id, plainName, addr, version, c); again[0] = url; f.dispose(); }
										catch (IOException | RuntimeException ex) { sub.setText("Not set up: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage())); }
									});
								}
								if (r[1] == null && !profile.isEmpty() && !ib.isVisible() && ib.getActionListeners().length == 0) {
									ib.setVisible(true); ((JLabel) r[4]).setVisible(false);
									ib.addActionListener(ev -> {
										ib.setEnabled(false);
										sub.setText("Looking at the pack\u2026");
										new Thread(() -> {
											String[] what; String problem = null;
											try { what = PackInstall.look(profile); } catch (IOException | RuntimeException ex) { what = null; problem = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
											String[] w = what; String pr = problem;
											SwingUtilities.invokeLater(() -> {
												if (w == null) { ib.setEnabled(true); sub.setText("Couldn't read the pack's profile: " + pr); return; }
												int ok = JOptionPane.showConfirmDialog(f, "<html><div style='width:420px'>Install <b>" + LaunchPopup.esc(w[1]) + "</b> on this device?<br><br>It makes a Prism Launcher instance (\"" + LaunchPopup.esc(w[0]) + "\") and downloads its setup files from<br>"
														+ LaunchPopup.esc(w[2]) + "<br><br>The mods download the first time you press Launch. No Steam shortcut is added; Prism Launcher has to be installed already.</div></html>", "Install", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
												if (ok != JOptionPane.OK_OPTION) { ib.setEnabled(true); sub.setText("Pick a server to play. The pack updates first, as always."); return; }
												new Thread(() -> {
													String err = null;
													try { PackInstall.install(instances, profile, c, m -> SwingUtilities.invokeLater(() -> sub.setText(m))); }
													catch (IOException | RuntimeException ex) { err = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
													String e2 = err;
													SwingUtilities.invokeLater(() -> {
														if (e2 != null) { ib.setEnabled(true); sub.setText("Not installed: " + e2); return; }
														again[0] = url; f.dispose();   // (the window comes straight back, with the pack listed and Play beside it)
													});
												}, "install").start();
											});
										}, "install-look").start();
									});
								}
							}
						} else for (Object[] r : rows.values()) if (r[1] != null) {
							// (No catalog: asleep, an older Gatekeeper, or refused. What this device can see for itself.)
							String[] pk = (String[]) r[1];
							new Thread(() -> { ServerControl sc = new ServerControl(Path.of(pk[3])); String[] st = sc.ban.equals("blacklist") ? new String[] {"off", "Not shown: this launcher is blacklisted"} : !sc.configured() ? new String[] {"off", "No server set up for this pack"} : sc.status(); set.accept((JLabel) r[0], st); }, "server-status").start();
						}
						// A pack installed here whose server this Gatekeeper no longer has (removed there, or deleted): said
						// so, and not launched, since its address may now be another server's.
						if (cat != null && !cat.containsKey("_refused") && !cat.containsKey("_old") && cat.get("servers") instanceof List<?> listed) {
							java.util.Set<String> there = new java.util.HashSet<>();
							for (Object o : listed) there.add(str(ServerControl.asMap(o).get("id")));
							for (Map.Entry<String, Object[]> e : rows.entrySet()) if (e.getValue()[1] != null && !there.contains(e.getKey())) {
								set.accept((JLabel) e.getValue()[0], new String[] {"off", "No longer on this Gatekeeper (removed or deleted there)"});
								((JButton) e.getValue()[2]).setEnabled(false);
								((JButton) e.getValue()[2]).setToolTipText("This server is no longer on this Gatekeeper. Its pack is still installed on this device: delete it in Prism Launcher if you don't need it.");
							}
						}
						// ---- Connections: the machine, MCSS and each server, each with the buttons this device is allowed.
						// (rebuilt only when what the Gatekeeper says changed, so a button isn't replaced under the mouse mid-click)
						String connSig = String.valueOf(cat) + "|" + (c != null);
						boolean connSame = connSig.equals(connShown[0]) && connList.getComponentCount() > 0;
						connShown[0] = connSig;
						if (!connSame) connList.removeAll();
						if (c != null && !connSame) {
							boolean answering = cat != null && !cat.containsKey("_refused");
							Runnable[] again2 = {null};
							// (a press: ask Gatekeeper, say what it said, then look again)
							java.util.function.BiConsumer<JButton, java.util.concurrent.Callable<Map<String, Object>>> press = (b, call) -> b.addActionListener(ev -> {
								b.setEnabled(false);
								new Thread(() -> {
									String m;
									try { Map<String, Object> r = call.call(); m = !str(r.get("message")).isEmpty() ? str(r.get("message")) : !str(r.get("error")).isEmpty() ? str(r.get("error")) : "Done."; }
									catch (Exception ex) { m = "Couldn't reach the Gatekeeper."; }
									String msg = m;
									SwingUtilities.invokeLater(() -> sub.setText(msg));
									if (again2[0] != null) again2[0].run();
								}, "connection-act").start();
							});
							java.util.function.BiPredicate<String, String> sure = (title, text) -> JOptionPane.showConfirmDialog(f, "<html><div style='width:380px'>" + text + "</div></html>", title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
							// the machine
							JButton wakeB = new JButton("Wake"), sleepB = new JButton("Sleep");
							wakeB.setToolTipText("Wakes the server machine (Wake on LAN: works from the owner's home network)");
							sleepB.setToolTipText("Puts the server machine to sleep. Only while no Minecraft server is running.");
							Object[] mr = row.apply("Server machine", new Component[] {wakeB, sleepB});
							set.accept((JLabel) mr[2], answering ? new String[] {"ok", "Awake"} : new String[] {"off", "Not answering: asleep or off"});
							String[] wakePack = null;
							for (String[] pk : packs) if (pk[4].equals(url) && new ServerControl(Path.of(pk[3])).canWake()) wakePack = pk;
							String[] wp = wakePack;
							wakeB.setVisible(!answering && wp != null);
							sleepB.setVisible(answering && "true".equals(str(cat.get("perm_machine"))));
							if (wp != null) wakeB.addActionListener(ev -> { try { new ServerControl(Path.of(wp[3])).wake(); sub.setText("Wake signal sent: it can take a minute."); } catch (IOException ex) { sub.setText("Couldn't send the wake signal: " + ex.getMessage()); } });
							sleepB.addActionListener(ev -> { if (sure.test("Sleep", "Put the server machine to sleep?")) { ActionListenerOnce.run(sleepB, press, () -> c.helper("POST", "/v1/pc/sleep", "{}")); } });
							connList.add((JPanel) mr[0]);
							if (answering && !cat.containsKey("_old")) {
								// What runs the servers there, as that Gatekeeper says: MCSS ("mcss", and what an older Gatekeeper
								// means by saying nothing), Gatekeeper itself ("builtin"), or both. With no MCSS there, its line
								// stays, greyed, and says so.
								String hostKind = str(cat.get("host")), sys = str(cat.get("system")), gkv = str(cat.get("version"));
								boolean noMcss = hostKind.equals("builtin");
								String noMcssWhy = (sys.isEmpty() || sys.equals("Windows") ? "Not used here" : "Not supported on " + sys) + ": Gatekeeper runs the servers itself";
								if (!gkv.isEmpty()) {
									Object[] gr = row.apply("Gatekeeper", new Component[] {});
									set.accept((JLabel) gr[2], new String[] {"ok", "Answering   \u00b7   version " + gkv + (sys.isEmpty() ? "" : "   \u00b7   on " + sys) + (noMcss ? "   \u00b7   runs the servers itself" : hostKind.equals("both") ? "   \u00b7   runs some servers itself" : "")});
									connList.add((JPanel) gr[0]);
								}
								// MCSS
								String mph = str(cat.get("mcss_phase"));
								boolean mUp = mph.equals("running") || mph.equals("closing"), mBusy = mph.equals("starting") || mph.equals("closing"), force = "force".equals(str(cat.get("close")));
								JButton mb = new JButton(mUp ? (force ? "Force close MCSS" : "Close MCSS") : "Start MCSS");
								mb.setToolTipText(mUp ? "Closes MCSS. A running Minecraft server is stopped first (the world is saved)." : "Starts MCSS (the program that runs the Minecraft servers) on the server machine");
								Object[] cr = row.apply("MCSS (the server software)", new Component[] {mb});
								set.accept((JLabel) cr[2], new String[] {mBusy ? "warn" : mUp ? "ok" : "off", mph.equals("starting") ? "Starting\u2026" : mph.equals("closing") ? "Closing\u2026" : mUp ? "Running" : "Not running"});
								mb.setVisible("true".equals(str(cat.get("perm_mcss"))));
								mb.setEnabled(!mBusy);
								if (noMcss) {
									set.accept((JLabel) cr[2], new String[] {"off", noMcssWhy});
									mb.setText("Start MCSS"); mb.setEnabled(false); mb.setToolTipText(noMcssWhy);
								}
								mb.addActionListener(ev -> { if (!mUp || sure.test("Close MCSS", "Close MCSS? Any Minecraft server that is running is stopped first (the world is saved), and anyone playing is disconnected.")) ActionListenerOnce.run(mb, press, () -> mUp ? c.mcssClose(force) : c.mcssStart()); });
								connList.add((JPanel) cr[0]);
								// each server
								if (cat.get("servers") instanceof List<?> sl) for (Object o : sl) {
									Map<String, Object> s = ServerControl.asMap(o);
									String sid = str(s.get("id")), ph = str(s.get("phase")), snm = str(s.get("name")).isEmpty() ? sid : str(s.get("name"));
									boolean sUp = ph.equals("online") || ph.equals("stopping"), sBusy = ph.equals("starting") || ph.equals("stopping");
									JButton sb = new JButton(sUp ? "Stop server" : "Start server");
									boolean ownRun = noMcss || "true".equals(str(s.get("native")));
									sb.setToolTipText(sUp ? "Stops this Minecraft server: the world is saved first, and anyone playing is disconnected" : ownRun ? "Starts this Minecraft server. Gatekeeper runs it itself." : "Starts this Minecraft server. Gatekeeper starts MCSS first if it isn't running.");
									Object[] sr = row.apply(snm, new Component[] {sb});
									set.accept((JLabel) sr[2], new String[] {sBusy ? "warn" : ph.equals("online") ? "ok" : "off", (ph.equals("online") ? "Online" : ph.equals("starting") ? "Starting\u2026" : ph.equals("stopping") ? "Shutting down\u2026" : ph.equals("nomcss") ? "Off (MCSS isn't running)" : "Off") + (hostKind.equals("both") ? ownRun ? "   \u00b7   run by Gatekeeper itself" : "   \u00b7   run by MCSS" : "")});
									sb.setVisible("true".equals(str(s.get("allowed"))));
									sb.setEnabled(!sBusy);
									sb.addActionListener(ev -> { if (!sUp || sure.test("Stop server", "Stop " + LaunchPopup.esc(snm) + "? The world is saved first, and anyone playing is disconnected.")) ActionListenerOnce.run(sb, press, () -> c.helperAs(sid, "POST", sUp ? "/v1/server/stop" : "/v1/server/start", "{}")); });
									connList.add((JPanel) sr[0]);
								}
							}
							again2[0] = () -> { for (Runnable r : new ArrayList<>(polls)) r.run(); };
						}
						connList.revalidate(); connList.repaint();
						if (c != null && cat == null) sub.setText("This Gatekeeper isn't answering (its machine may be asleep or off). Installed packs can still be started.");
						else if (cat != null && cat.containsKey("_refused")) sub.setText("This Gatekeeper answered, but " + str(cat.get("_refused")) + ".");
						else if (cat != null && rows.isEmpty()) sub.setText("This Gatekeeper looks after no servers yet.");
					};
					if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
				};
				polls.add(poll);
				if (shot != null) poll.run(); else new Thread(poll, "catalog").start();
				cards.show(deck, "servers");
				serverList.revalidate(); serverList.repaint();
				SwingUtilities.invokeLater(() -> Nav.home(f));
			};

			// ---- level 1: the Gatekeepers
			showKeepers[0] = () -> {
				polls.clear();
				keeperList.removeAll();
				head.setText(LaunchPopup.TOOL + " Minecraft Launcher");
				sub.setText(keepers.isEmpty() && !hasLoose ? "No Gatekeeper yet. Add one with the address, link or file its owner gave you." : "Pick a Gatekeeper to see its servers.");
				backB.setVisible(false);
				// This machine's own Gatekeeper (the Host half of this program), when it is set up here: how it is,
				// and its window a button away. It runs in the background whether this launcher is open or not.
				boolean hostUp = dev.gatehouse.Main.keeperDoor() > 0;
				boolean listedHere = false;
				String hereKnown = hereUrl.isEmpty() ? LaunchPopup.readFile(home.resolve("keeper-here.txt")).trim() : hereUrl;
				// Starting it: in the background, as signing in does. When it answers, the window is opened afresh, so the
				// launcher links itself to it again and its servers are listed.
				java.util.function.Consumer<JButton> startKeeper = b -> {
					b.setEnabled(false);
					sub.setText("Starting Gatekeeper\u2026");
					new Thread(() -> {
						String why = dev.gatehouse.Main.startKeeper();
						boolean up = false;
						if (why.isEmpty() && System.getenv("OJ_HOME_DRY") == null) for (int i = 0; i < 40 && !up; i++) { try { Thread.sleep(500); } catch (InterruptedException e) { return; } up = dev.gatehouse.Main.keeperDoor() > 0; }
						boolean answers = up;
						SwingUtilities.invokeLater(() -> {
							b.setEnabled(true);
							if (!why.isEmpty()) sub.setText("Couldn't start Gatekeeper: " + why);
							else if (answers) { cameBack = f.getBounds(); again[0] = "\u0000restart"; f.dispose(); }
							else sub.setText("Gatekeeper was asked to start, but isn't answering yet. Give it a moment, then Restart.");
						});
					}, "start-keeper").start();
				};
				for (String[] k : keepers) if (k[0].equals(hereKnown)) listedHere = true;
				// (when it is in the list below, that line is this machine's: no separate one above it)
				if (!listedHere && (hostUp || dev.gatehouse.Main.keeperDir() != null)) {
					JButton openHost = new JButton("Open Gatekeeper");
					openHost.setToolTipText("Opens the window of the Gatekeeper on this machine: its servers, who may use them, and its settings");
					JButton startHost = new JButton("Start Gatekeeper");
					startHost.setToolTipText("Starts this machine's Gatekeeper in the background, as signing in does. A server set to start with Gatekeeper starts too.");
					startHost.setVisible(!hostUp);
					startHost.addActionListener(ev -> startKeeper.accept(startHost));
					Object[] hr = row.apply("This machine hosts servers", new Component[] {startHost, openHost});
					set.accept((JLabel) hr[2], hostUp ? new String[] {"ok", "Its Gatekeeper is running in the background"} : new String[] {"warn", "Its Gatekeeper is set up but isn't running: Start Gatekeeper starts it"});
					openHost.addActionListener(ev -> { String t = dev.gatehouse.Main.openGatekeeper(); sub.setText(t.isEmpty() ? "Opening Gatekeeper\u2026" : "Couldn't open Gatekeeper: " + t); });
					keeperList.add((JPanel) hr[0]);
				}
				for (String[] k : keepers) {
					LaunchPopup.PlayButton open = new LaunchPopup.PlayButton("Open");
					open.setToolTipText("Shows the servers this Gatekeeper looks after");
					JButton remove = new JButton("Remove");
					remove.setToolTipText("Forgets this Gatekeeper on this device (packs already installed stay)");
					JButton connect = new JButton("Connect\u2026");
					connect.setToolTipText("Walks through getting this device connected to this Gatekeeper: Tailscale, the owner's invite, and pairing");
					connect.setVisible(false);
					connect.addActionListener(ev -> {
						ServerControl cc = client(home, k[0], packs);
						if (Connect.show(f, k[0], k[1], k.length > 2 ? k[2] : "", cc::hello)) showServers.accept(k[0]);
					});
					boolean used = false;
					for (String[] pk : packs) if (pk[4].equals(k[0])) used = true;
					boolean usedHere = used;
					boolean here = k[0].equals(hereKnown);
					JButton startHere = new JButton("Start Gatekeeper");
					startHere.setToolTipText("Starts this machine's Gatekeeper in the background, as signing in does. A server set to start with Gatekeeper starts too.");
					startHere.setVisible(false);
					startHere.addActionListener(ev -> startKeeper.accept(startHere));
					JButton openHostHere = new JButton("Open Gatekeeper");
					openHostHere.setToolTipText("Opens the window of the Gatekeeper on this machine: its servers, who may use them, and its settings");
					openHostHere.setVisible(here);
					openHostHere.addActionListener(ev -> { String t = dev.gatehouse.Main.openGatekeeper(); sub.setText(t.isEmpty() ? "Opening Gatekeeper\u2026" : "Couldn't open Gatekeeper: " + t); });
					Object[] r = row.apply((k[1].isEmpty() ? "Gatekeeper at " + shown(k[0]) : k[1]) + (here ? "  (this machine)" : ""), new Component[] {remove, connect, startHere, openHostHere, open});
					open.addActionListener(ev -> showServers.accept(k[0]));
					remove.addActionListener(ev -> {
						String who = k[1].isEmpty() ? "the Gatekeeper at " + shown(k[0]) : k[1];
						String ask = "<html><div style='width:420px'>Remove " + LaunchPopup.esc(who) + " from this device's list?<br><br>"
								+ "Gatehouse forgets it and this device's link to it. " + (usedHere ? "Its packs stay installed and move to <b>Other packs</b>; each still updates and starts as before. " : "")
								+ "On the owner's side nothing changes: this device stays in their Access Manager until they remove it there. You can add it again later with the same address, link or file.</div></html>";
						if (JOptionPane.showConfirmDialog(f, ask, "Remove Gatekeeper", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
						forgetKeeper(home, keepers, k);
						if (usedHere) { cameBack = f.getBounds(); again[0] = "\u0000restart"; f.dispose(); }   // (the list of packs has changed: the window again, in the same place)
						else showKeepers[0].run();
					});
					keeperList.add((JPanel) r[0]);
					((dev.gatehouse.look.GameCard) r[0]).device(deviceOf(home, k[0]));   // (the kind of machine it last said it is on)
					{ java.awt.image.BufferedImage last = lastPicture(home, k[0]); if (last != null) ((dev.gatehouse.look.GameCard) r[0]).picture(last); }   // (and its own picture, if it has given one)
					ServerControl c = client(home, k[0], packs);
					Runnable poll = () -> {
						Map<String, Object> cat = catalog(c);
						String[] st;
						if (cat == null) st = new String[] {"off", shown(k[0]) + ": not answering (its machine may be asleep or off)"};
						else if (cat.containsKey("_old")) st = new String[] {"ok", shown(k[0]) + ": answering"};
						else if (cat.containsKey("_refused")) st = new String[] {"warn", shown(k[0]) + ": " + str(cat.get("_refused"))};
						else {
							int n = cat.get("servers") instanceof List<?> l ? l.size() : 0, on = 0;
							if (cat.get("servers") instanceof List<?> l) for (Object o : l) if ("true".equals(str(ServerControl.asMap(o).get("online")))) on++;
							st = new String[] {"ok", shown(k[0]) + ": " + n + " server" + (n == 1 ? "" : "s") + (n == 0 ? "" : ", " + on + " online")};
							String nm = str(cat.get("name"));
							if (!nm.isEmpty() && !nm.equals(k[1])) { k[1] = nm; saveKeepers(home, keepers); SwingUtilities.invokeLater(() -> ((JLabel) r[1]).setText(nm)); }
							// (a Gatekeeper from 3.10.0 says what kind of machine it is on: its picture; an older one says nothing, and keeps its initials)
							if (cat.containsKey("device")) { String kind = str(cat.get("device")); rememberDevice(home, k[0], kind); SwingUtilities.invokeLater(() -> ((dev.gatehouse.look.GameCard) r[0]).device(kind)); }
							if (cat.containsKey("icon")) picture(home, c, k[0], "gatekeeper", str(cat.get("icon")), (dev.gatehouse.look.GameCard) r[0]);
						}
						set.accept((JLabel) r[2], st);
						boolean silent = cat == null;
						// (this machine's own Gatekeeper not answering: it isn't running, and can be started from here)
						boolean off = here && silent && dev.gatehouse.Main.keeperDoor() <= 0;
						SwingUtilities.invokeLater(() -> { if (connect.isVisible() != (silent && !off) || startHere.isVisible() != off) { connect.setVisible(silent && !off); startHere.setVisible(off); openHostHere.setVisible(here && !off); keeperList.revalidate(); } });
					};
					polls.add(poll);
					if (shot != null) poll.run(); else new Thread(poll, "catalog").start();
				}
				if (hasLoose) {
					LaunchPopup.PlayButton open = new LaunchPopup.PlayButton("Open");
					Object[] r = row.apply("Other packs", new Component[] {open});
					((JLabel) r[2]).setText("Packs on this device that no Gatekeeper looks after");
					open.addActionListener(ev -> showServers.accept(""));
					keeperList.add((JPanel) r[0]);
				}
				cards.show(deck, "keepers");
				shell[0].light("SERVERS"); atServers[0] = false;
				keeperList.revalidate(); keeperList.repaint();
				SwingUtilities.invokeLater(() -> Nav.home(f));
			};
			backB.addActionListener(ev -> showKeepers[0].run());
			Nav.tag(backB, "back");

			// ---- the bottom: add a Gatekeeper, Gatehouse's own Steam shortcut, Prism Launcher, Quit
			JButton checkB = new JButton("Check for updates");
			checkB.setToolTipText("Looks now for a newer " + LaunchPopup.TOOL + ", and asks the Gatekeepers again");
			JButton addB = new JButton("Add Gatekeeper…");
			addB.setToolTipText("Connects this launcher to another Gatekeeper, with the address, pairing link or pairing file its owner gave you");
			JButton prismB = new LaunchPopup.PlayButton("Open Prism Launcher", LaunchPopup.SLATE, Color.WHITE, 14f), quit = new LaunchPopup.PlayButton("Quit", LaunchPopup.QUIT_RED, Color.WHITE, 14f);
			prismB.setToolTipText("Prism Launcher itself: accounts, other instances, settings");
			quit.setToolTipText("Closes the launcher");
			prismB.addActionListener(ev -> { openPrism[0] = true; f.dispose(); });
			// Restart: the main window again from the top (what's installed is read again, the Gatekeepers are asked again,
			// and a newer Gatehouse is looked for again).
			JButton restartB = new LaunchPopup.PlayButton("Restart", LaunchPopup.YELLOW, LaunchPopup.INK, 14f);
			restartB.setToolTipText("Opens the launcher again from the start, looking for a newer Gatehouse");
			restartB.addActionListener(ev -> { again[0] = "\u0000restart"; f.dispose(); });
			Nav.tag(restartB, "restart");
			Nav.tag(quit, "quit");
			Nav.start();
			quit.addActionListener(ev -> f.dispose());
			addB.addActionListener(ev -> {
				javax.swing.JTextField field = new javax.swing.JTextField(30);
				JButton file = new JButton("Open pairing file…");
				file.addActionListener(e2 -> {
					javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
					fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Gatekeeper pairing file (.gatekeeper)", "gatekeeper", "json"));
					if (fc.showOpenDialog(f) == javax.swing.JFileChooser.APPROVE_OPTION) { String[] got = parsePairing(LaunchPopup.readFile(fc.getSelectedFile().toPath())); field.setText(got == null ? "" : "gatehouse://pair/" + shown(got[0]) + "?name=" + java.net.URLEncoder.encode(got[1], StandardCharsets.UTF_8) + (got[2].isEmpty() ? "" : "&ts=" + java.net.URLEncoder.encode(got[2], StandardCharsets.UTF_8))); }
				});
				JPanel form = new JPanel(new BorderLayout(0, 8));
				form.add(new JLabel("<html><div style='width:420px'>Paste the address or pairing link the Gatekeeper's owner gave you (in Gatekeeper: Status, Share pairing), an invite to a server someone sent you, or open the pairing file they sent. If this device can't reach it yet, the next window walks through getting connected.</div></html>"), BorderLayout.NORTH);
				JPanel line = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); line.add(field); line.add(file);
				form.add(line, BorderLayout.CENTER);
				if (JOptionPane.showConfirmDialog(f, form, "Add Gatekeeper", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				String code = inviteCode(field.getText());
				String[] parsed = parsePairing(field.getText());
				// (a code by itself: for the one Gatekeeper this launcher knows, if it knows just one)
				if (!code.isEmpty() && !field.getText().trim().toLowerCase(java.util.Locale.ROOT).startsWith("gatehouse://")) parsed = keepers.size() == 1 ? new String[] {keepers.get(0)[0], keepers.get(0)[1], ""} : null;
				String[] got = parsed;
				if (got == null) { JOptionPane.showMessageDialog(f, !code.isEmpty() ? "<html><div style='width:380px'>That is an invite's code by itself. Paste the whole invite (it starts with gatehouse://invite/), so the launcher knows which Gatekeeper it is for.</div></html>" : "That doesn't look like a Gatekeeper's address, pairing link, invite or pairing file.", "Add Gatekeeper", JOptionPane.PLAIN_MESSAGE); return; }
				java.util.function.Consumer<String> handIn = url -> new Thread(() -> {
					String told = useInvite(client(home, url, packs), code);
					SwingUtilities.invokeLater(() -> { JOptionPane.showMessageDialog(f, "<html><div style='width:400px'>" + LaunchPopup.esc(told) + "</div></html>", "Invite", JOptionPane.PLAIN_MESSAGE); showServers.accept(url); });
				}, "invite").start();
				for (String[] k : keepers) if (k[0].equals(got[0])) { if (code.isEmpty()) showServers.accept(k[0]); else handIn.accept(k[0]); return; }
				sub.setText("Asking the Gatekeeper at " + shown(got[0]) + "…");
				new Thread(() -> {
					ServerControl c = client(home, got[0], packs);
					String state = null;
					try { state = c.hello(); } catch (IOException | RuntimeException ignored) {}
					String st = state;
					SwingUtilities.invokeLater(() -> {
						keepers.add(new String[] {got[0], got[1], got[2]});
						java.util.Set<String> gone = removedKeepers(home);
						boolean wasRemoved = gone.remove(got[0]);
						if (wasRemoved) saveRemoved(home, gone);
						if (st == null || st.isEmpty()) {
							// It doesn't answer (yet): remembered all the same, and the steps to get connected are shown.
							saveKeepers(home, keepers);
							sub.setText("Pick a Gatekeeper to see its servers.");
							showKeepers[0].run();
							if (Connect.show(f, got[0], got[1], got[2], c::hello)) { if (code.isEmpty()) showServers.accept(got[0]); else handIn.accept(got[0]); }
							return;
						}
						saveKeepers(home, keepers);
						if (wasRemoved && code.isEmpty()) { cameBack = f.getBounds(); again[0] = got[0]; f.dispose(); return; }   // (its packs belong to it again: the window afresh, on its servers)
						if (code.isEmpty()) showServers.accept(got[0]); else handIn.accept(got[0]);
					});
				}, "add-gatekeeper").start();
			});
			// ---- the setup guide: the steps from nothing to playing, one at a time, each looked at every few seconds
			dev.gatehouse.look.Wizard[] guide = {null};
			Runnable leaveGuide = () -> { guide[0].stop(); showKeepers[0].run(); };
			guide[0] = new dev.gatehouse.look.Wizard(() -> {
				List<dev.gatehouse.look.Wizard.Step> steps = new ArrayList<>();
				boolean win = System.getProperty("os.name", "").startsWith("Windows");
				boolean prismOk = Files.exists(Path.of("/.flatpak-info")) || Files.isRegularFile(Path.of(prismFor)) || (!win && !prismFor.contains("/"));
				boolean account = false;
				try { account = ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(instances.toAbsolutePath().getParent().resolve("accounts.json")))).get("accounts") instanceof List<?> l && !l.isEmpty(); } catch (RuntimeException ignored) {}
				List<String[]> known = savedKeepers(home);
				boolean reach = false;
				for (String[] k : known) if (Connect.reaches(k[0])) { reach = true; break; }
				int have = installed(instances).size();
				steps.add(new dev.gatehouse.look.Wizard.Step("welcome", "Welcome", "info", "This guide takes a device from nothing set up to playing on a friend's Minecraft server.\n\nA server's owner runs a program called Gatekeeper on their machine. " + LaunchPopup.TOOL + ", this launcher, connects to it: it shows that owner's servers, installs what each needs, keeps it up to date, and starts the game through Prism Launcher.\n\nEach step says what to do and checks by itself whether it's done. You can leave the guide and come back to it from Options (the wheel at the top right).", ""));
				steps.add(new dev.gatehouse.look.Wizard.Step("prism", "Prism Launcher", prismOk ? "ok" : "todo", "Prism Launcher is the program that actually runs Minecraft; " + LaunchPopup.TOOL + " tells it what to start. Install it from its own site, then press Restart below so " + LaunchPopup.TOOL + " finds it.\n\nThis step is done when Prism Launcher is found on this device.", prismOk ? "" : "Not found where it's usually installed")
						.action("Open Prism Launcher's download page", "open:https://prismlauncher.org/download/"));
				steps.add(new dev.gatehouse.look.Wizard.Step("account", "Your Minecraft account", !prismOk ? "wait" : account ? "ok" : "todo", "You need your own copy of Minecraft: Java Edition, and to sign in to it once in Prism Launcher. Open Prism, click the account picker at the top right, choose Manage Accounts, then Add Microsoft, and sign in with the Microsoft account that owns the game.\n\nThis step is done when Prism Launcher has an account.", account ? "" : "Prism has no account yet")
						.action("Open Prism Launcher", "prism"));
				steps.add(new dev.gatehouse.look.Wizard.Step("gatekeeper", "Add the owner's Gatekeeper", known.isEmpty() ? "todo" : "ok", "Ask the server's owner for their pairing: a link that starts with gatehouse://, a small .gatekeeper file, or just an address. Press Add Gatekeeper and paste the link, or open the file.\n\nThis step is done when a Gatekeeper is on your list.", known.isEmpty() ? "" : known.size() + (known.size() == 1 ? " Gatekeeper" : " Gatekeepers") + " on your list")
						.action("Add Gatekeeper\u2026", "add"));
				steps.add(new dev.gatehouse.look.Wizard.Step("connect", "Connect to it", known.isEmpty() ? "wait" : reach ? "ok" : "todo", "A Gatekeeper is reached over Tailscale, a private network between the owner's machine and the devices they let in. Connect walks through it: installing Tailscale and signing in with your own account, accepting the owner's invite, and reaching their machine. If all of that is done and it still can't be reached, the owner's machine may be asleep or off.\n\nThis step is done when the Gatekeeper answers.", reach ? "" : known.isEmpty() ? "" : "It isn't answering yet")
						.action("Connect\u2026", "connect"));
				steps.add(new dev.gatehouse.look.Wizard.Step("install", "Install a server", !reach && have == 0 ? "wait" : have > 0 ? "ok" : "todo", "Close this guide and open the Gatekeeper on your list: it shows the owner's servers. Press Install beside one. " + LaunchPopup.TOOL + " sets it up in Prism Launcher; the game and any mods download the first time you start it.\n\nThe owner decides what your device may do. Until they allow it you can still install and look, and they see your device waiting in their Gatekeeper.\n\nThis step is done when a server is installed here.", have > 0 ? have + (have == 1 ? " server" : " servers") + " installed" : "")
						.action("Show my Gatekeepers", "keepers"));
				steps.add(new dev.gatehouse.look.Wizard.Step("play", "Play", "info", "Press Launch beside a server. Its update runs, then its page shows Play.\n\nThe button changes with what's in the way, and says what to do: Request whitelist asks the owner to add you to the server's whitelist; Request server asks them to turn the server on; Update needed means " + LaunchPopup.TOOL + " or the owner's Gatekeeper has to be updated first.\n\nThat's everything. This guide stays in Options.", ""));
				return steps;
			}, id -> {
				if (id.startsWith("open:")) Connection.open(id.substring(5));
				else if (id.equals("prism")) { try { new ProcessBuilder(prismFor).start(); } catch (IOException ex) { JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>Prism Launcher couldn't be started from here (" + LaunchPopup.esc(String.valueOf(ex.getMessage())) + "). Open it yourself, from the Start menu or your applications.</div></html>", "Prism Launcher", JOptionPane.PLAIN_MESSAGE); } }
				else if (id.equals("add")) addB.doClick();
				else if (id.equals("keepers")) leaveGuide.run();
				else if (id.equals("connect")) {
					List<String[]> known = savedKeepers(home);
					if (known.isEmpty()) return;
					String[] k = known.get(0);
					Connect.show(f, k[0], k[1], k.length > 2 ? k[2] : "", client(home, k[0], packs)::hello);
				}
			}, leaveGuide, LaunchPopup.class);
			deck.add(dev.gatehouse.look.GameShell.sheet(guide[0]), "wizard");
			openGuide[0] = () -> { head.setText("Setup guide"); sub.setText("From nothing set up to playing, a step at a time."); backB.setVisible(true); cards.show(deck, "wizard"); shell[0].light("OPTIONS"); guide[0].open(); };
			headButtons.add(addB, 0);
			// ---- the other sections' pages
			JPanel peopleList = new JPanel();
			dev.gatehouse.look.GameCard.grid(peopleList);
			JPanel peopleIn = new Fitted();
			peopleIn.add(peopleList, BorderLayout.NORTH);
			JScrollPane peopleSc = new JScrollPane(peopleIn);
			peopleSc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			peopleSc.getVerticalScrollBar().setUnitIncrement(16);
			dev.gatehouse.look.GameShell.clear(peopleSc);
			// ---- chat, beside what this device may do: a room for each Gatekeeper and each of its servers this device is in
			javax.swing.JComboBox<String> chatRooms = new javax.swing.JComboBox<>();
			List<String[]> chatKeys = new ArrayList<>();                         // {Gatekeeper, room}, in the order shown
			Map<String, Long> chatLast = new java.util.concurrent.ConcurrentHashMap<>(), chatSeen = new java.util.concurrent.ConcurrentHashMap<>();
			for (String l : LaunchPopup.readFile(home.resolve("chat-seen.txt")).split("\\R")) { String[] kv = l.split("\t"); if (kv.length == 2) try { chatSeen.put(kv[0], Long.parseLong(kv[1].trim())); } catch (NumberFormatException ignored) {} }
			javax.swing.JTextArea chatLog = new javax.swing.JTextArea();
			chatLog.setEditable(false); chatLog.setLineWrap(true); chatLog.setWrapStyleWord(true);
			javax.swing.JTextField chatLine = new javax.swing.JTextField();
			chatLine.setToolTipText("Write here and press Enter");
			JButton chatSend = new JButton("Send"), chatFiles = new JButton("Files\u2026");
			chatFiles.setToolTipText("The files on offer in this room: save a copy of one, or offer one");
			chatFiles.addActionListener(ev -> {
				int at = chatRooms.getSelectedIndex();
				if (at < 0 || at >= chatKeys.size()) return;
				String[] sel = chatKeys.get(at);
				ServerControl kc = client(home, sel[0], packs);
				String base = sel[0].replaceAll("/+$", "") + "/v1/share", room = java.net.URLEncoder.encode(sel[1], StandardCharsets.UTF_8);
				java.util.function.BiFunction<String, byte[], java.net.HttpURLConnection> ask = (url, body) -> { try { java.net.HttpURLConnection h = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection(); h.setConnectTimeout(3000); h.setReadTimeout(120000); h.setRequestProperty("X-Device", str(kc.deviceId)); h.setRequestProperty("X-Token", str(kc.deviceToken)); if (body != null) { h.setRequestMethod("POST"); h.setDoOutput(true); h.setFixedLengthStreamingMode(body.length); try (java.io.OutputStream o = h.getOutputStream()) { o.write(body); } } return h; } catch (IOException e) { return null; } };
				dev.gatehouse.look.FilesBox.show(chatLog, String.valueOf(chatRooms.getSelectedItem()), "",
					() -> { List<String[]> out = new ArrayList<>(); try { if (kc.helper("GET", "/v1/share/list?room=" + room, null).get("files") instanceof List<?> l) for (Object o : l) { Map<String, Object> x = ServerControl.asMap(o); long n = (long) Double.parseDouble(str(x.get("size")).isEmpty() ? "0" : str(x.get("size"))); out.add(new String[] {str(x.get("id")), str(x.get("name")), n < 1048576 ? (n / 1024 + 1) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0), str(x.get("who")), str(x.get("when"))}); } } catch (IOException | RuntimeException notNow) { /* nothing listed */ } return out; },
					id -> { try { java.net.HttpURLConnection h = ask.apply(base + "?id=" + id, null); if (h == null || h.getResponseCode() != 200) return null; try (java.io.InputStream in = h.getInputStream()) { return in.readAllBytes(); } } catch (IOException e) { return null; } },
					(name, bytes) -> { try { java.net.HttpURLConnection h = ask.apply(base + "?room=" + room + "&name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8), bytes); if (h == null) return "That Gatekeeper didn't answer."; try (java.io.InputStream in = h.getResponseCode() < 400 ? h.getInputStream() : h.getErrorStream()) { return str(ServerControl.asMap(LaunchPopup.parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("message")); } } catch (IOException | RuntimeException e) { return "That Gatekeeper didn't answer."; } });
			});
			JLabel chatSaid = new JLabel("No rooms yet: a Gatekeeper from 3.14.0 on that has let this device onto a server has them.");
			chatSaid.setForeground(new Color(0x8F98A0));
			long[] chatShown = {0};
			String[] chatShowing = {""};
			java.util.function.Function<String, Long> numOf = sv -> { try { return (long) Double.parseDouble(sv); } catch (RuntimeException e) { return 0L; } };
			Runnable chatSave = () -> { StringBuilder sb = new StringBuilder(); for (Map.Entry<String, Long> e : chatSeen.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n'); try { Files.createDirectories(home); Files.writeString(home.resolve("chat-seen.txt"), sb.toString(), StandardCharsets.UTF_8); } catch (IOException ignored) {} };
			// (asked every few seconds: which rooms there are and how far each has got; and, while People is open, what is new in the one chosen)
			java.util.function.Consumer<String> chatRound = text -> {
				List<String[]> found = new ArrayList<>(); List<String> names = new ArrayList<>();
				for (String[] k : keepers) try {
					ServerControl kc = client(home, k[0], packs);
					if (ServerControl.asMap(kc.helper("GET", "/v1/chat/rooms", null)).get("rooms") instanceof List<?> l) for (Object o : l) {
						Map<String, Object> rm = ServerControl.asMap(o);
						found.add(new String[] {k[0], str(rm.get("id"))}); names.add((k[1].isEmpty() ? shown(k[0]) : k[1]) + ": " + str(rm.get("name")));
						chatLast.put(k[0] + "|" + str(rm.get("id")), numOf.apply(str(rm.get("last"))));
					}
				} catch (IOException | RuntimeException notNow) { /* that Gatekeeper isn't answering, or is from before chat */ }
				boolean open = "PEOPLE".equals(shell[0].lit());
				int at = chatRooms.getSelectedIndex();
				String[] sel = at >= 0 && at < chatKeys.size() ? chatKeys.get(at) : null;
				Map<String, Object> got = null;
				if (sel != null && (open || text != null)) try {
					String key = sel[0] + "|" + sel[1];
					long after = key.equals(chatShowing[0]) ? chatShown[0] : 0;
					got = client(home, sel[0], packs).helper("POST", "/v1/chat", "{\"room\":\"" + ServerControl.jsonEsc(sel[1]) + "\",\"after\":" + after + (text == null ? "" : ",\"text\":\"" + ServerControl.jsonEsc(text) + "\"") + "}");
				} catch (IOException | RuntimeException notNow) { got = null; }
				Map<String, Object> r = got;
				SwingUtilities.invokeLater(() -> {
					if (found.size() != chatKeys.size()) {
						String[] keep = sel;
						chatKeys.clear(); chatKeys.addAll(found);
						chatRooms.removeAllItems(); for (String n : names) chatRooms.addItem(n);
						if (keep != null) for (int i = 0; i < chatKeys.size(); i++) if (chatKeys.get(i)[0].equals(keep[0]) && chatKeys.get(i)[1].equals(keep[1])) chatRooms.setSelectedIndex(i);
						chatSaid.setText(found.isEmpty() ? "No rooms yet: a Gatekeeper from 3.14.0 on that has let this device onto a server has them." : " ");
					}
					if (r != null && sel != null) {
						String key = sel[0] + "|" + sel[1];
						if (!key.equals(chatShowing[0])) { chatLog.setText(""); chatShowing[0] = key; }
						StringBuilder add = new StringBuilder();
						if (r.get("messages") instanceof List<?> ms) for (Object o : ms) { Map<String, Object> e = ServerControl.asMap(o); add.append(str(e.get("when"))).append("  ").append(str(e.get("who"))).append(": ").append(str(e.get("text"))).append('\n'); }
						if (add.length() > 0) { chatLog.append(add.toString()); chatLog.setCaretPosition(chatLog.getDocument().getLength()); }
						chatShown[0] = numOf.apply(str(r.get("next")));
						if (!str(r.get("message")).isEmpty()) chatSaid.setText(str(r.get("message"))); else if (!found.isEmpty()) chatSaid.setText(" ");
						if (open) { chatSeen.put(key, chatShown[0]); chatSave.run(); }
					}
					int unread = 0;
					for (Map.Entry<String, Long> e : chatLast.entrySet()) if (e.getValue() > chatSeen.getOrDefault(e.getKey(), 0L)) unread++;
					shell[0].badge("PEOPLE", unread);
				});
			};
			Runnable chatSay = () -> { String tx = chatLine.getText().trim(); if (tx.isEmpty()) return; chatLine.setText(""); new Thread(() -> chatRound.accept(tx), "chat-say").start(); };
			chatSend.addActionListener(ev -> chatSay.run());
			chatLine.addActionListener(ev -> chatSay.run());
			chatRooms.addActionListener(ev -> { if (chatRooms.getSelectedIndex() >= 0) { chatShown[0] = 0; chatShowing[0] = ""; new Thread(() -> chatRound.accept(null), "chat").start(); } });
			if (shot == null) { Thread chat = new Thread(() -> { while (f.isDisplayable() || !f.isVisible()) { try { Thread.sleep(4000); } catch (InterruptedException e) { return; } if (!f.isDisplayable()) return; if (f.getContentPane() == shell[0]) chatRound.accept(null); } }, "chat"); chat.setDaemon(true); chat.start(); }
			JPanel chatTop = new JPanel(new BorderLayout(8, 0)); JLabel chatHead = new JLabel("Chat"); chatHead.setFont(chatHead.getFont().deriveFont(Font.BOLD, 15f));
			chatTop.add(chatHead, BorderLayout.WEST); chatTop.add(chatRooms, BorderLayout.CENTER);
			JPanel chatBottom = new JPanel(new BorderLayout(6, 2)); JPanel chatButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)); chatButtons.add(chatSend); chatButtons.add(chatFiles);
			chatBottom.add(chatLine, BorderLayout.CENTER); chatBottom.add(chatButtons, BorderLayout.EAST); chatBottom.add(chatSaid, BorderLayout.SOUTH);
			JPanel chatBox = new JPanel(new BorderLayout(0, 8)); chatBox.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			chatBox.add(chatTop, BorderLayout.NORTH); chatBox.add(new JScrollPane(chatLog), BorderLayout.CENTER); chatBox.add(chatBottom, BorderLayout.SOUTH);
			javax.swing.JComponent chatSheet = dev.gatehouse.look.GameShell.sheet(chatBox);
			chatSheet.setPreferredSize(new Dimension(460, 300));
			JPanel peopleBoth = new JPanel(new BorderLayout(14, 0)); peopleBoth.setOpaque(false);
			peopleBoth.add(peopleSc, BorderLayout.CENTER); peopleBoth.add(chatSheet, BorderLayout.EAST);
			deck.add(peopleBoth, "people");
			JPanel checkRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 8));
			checkRow.add(checkB);
			JPanel updTop = new JPanel(new BorderLayout());
			updTop.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));
			updTop.add(updating, BorderLayout.CENTER); updTop.add(checkRow, BorderLayout.SOUTH);
			JPanel constPage = new JPanel(new BorderLayout(0, 8));
			constPage.add(updTop, BorderLayout.NORTH); constPage.add(news, BorderLayout.CENTER);
			deck.add(dev.gatehouse.look.GameShell.sheet(constPage), "constellation");
			// What this device may do on each server it has installed, from what its Gatekeeper last said.
			Runnable people = () -> {
				List<Object[]> found = new ArrayList<>();
				for (String[] pk : packs) {
					if (pk[4].isEmpty()) continue;
					ServerControl sc = new ServerControl(Path.of(pk[3]));
					List<String> may = new ArrayList<>();
					if ("true".equals(sc.whitelisted)) may.add("on the whitelist");
					if (sc.permServer) may.add("may start and stop the server");
					if (sc.permMcss) may.add("may start and close MC Server Soft");
					if (sc.permSleep) may.add("may wake the machine and put it to sleep");
					if (sc.permAdmin) may.add("may manage other launchers (Gatekeeper control)");
					String kn = "";
					for (String[] k : keepers) if (k[0].equals(pk[4])) kn = k[1];
					String at = kn.isEmpty() ? "" : "On " + kn + ". ";
					String[] st = !sc.ban.isEmpty() ? new String[] {"failed", at + (sc.ban.equals("blacklist") ? "This launcher is blacklisted here." : "This launcher is suspended here.") + (sc.banReason.isEmpty() ? "" : " The host says: " + sc.banReason)}
							: may.isEmpty() ? new String[] {"off", at + "Nothing beyond playing has been allowed for this device yet. To ask, open the server and use its Request buttons."}
							: new String[] {"ok", at + "This device: " + String.join("; ", may) + "."};
					found.add(new Object[] {pk[1], st});
				}
				Runnable ui = () -> {
					peopleList.removeAll();
					for (Object[] o : found) { Object[] r = row.apply((String) o[0], new Component[0]); set.accept((JLabel) r[2], (String[]) o[1]); peopleList.add((JPanel) r[0]); }
					if (found.isEmpty()) { Object[] r = row.apply("No servers yet", new Component[0]); ((dev.gatehouse.look.GameCard) r[0]).dim(true); ((JLabel) r[2]).setText("Install a server from a Gatekeeper first: what this device may do on it is then listed here."); peopleList.add((JPanel) r[0]); }
					peopleList.revalidate(); peopleList.repaint();
				};
				if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
			};
			// ---- the sections: what each name in the top bar shows
			java.util.function.Consumer<String> pickSection = s -> {
				if (guide[0] != null) guide[0].stop();
				switch (s) {
					case "SERVERS" -> { if (atServers[0] && openUrl[0] != null) showServers.accept(openUrl[0]); else showKeepers[0].run(); }
					case "PEOPLE" -> {
						head.setText("People");
						sub.setText("What this device may do on each server. The host decides, in their Gatekeeper's Access Manager.");
						backB.setVisible(false);
						if (shot != null) people.run(); else { new Thread(people, "people").start(); new Thread(() -> chatRound.accept(null), "chat").start(); }
						cards.show(deck, "people"); shell[0].light("PEOPLE");
					}
					case "CONSTELLATION" -> {
						head.setText("Constellation");
						sub.setText("How this launcher updates, and what changed.");
						backB.setVisible(false);
						cards.show(deck, "constellation"); shell[0].light("CONSTELLATION");
						if (news.getSelectedIndex() == 0) seenNow.run();
					}
					case "MACHINE" -> {
						String u = openUrl[0] != null && !openUrl[0].isEmpty() ? openUrl[0] : keepers.isEmpty() ? null : keepers.get(0)[0];
						if (u != null) showServers.accept(u);   // (asks that Gatekeeper again, which fills this page too)
						else { polls.clear(); connList.removeAll(); Object[] r = row.apply("No Gatekeeper yet", new Component[0]); ((dev.gatehouse.look.GameCard) r[0]).dim(true); ((JLabel) r[2]).setText("Add a Gatekeeper first: its machine, MC Server Soft and servers are then shown here, with what this device may switch on and off."); connList.add((JPanel) r[0]); connList.revalidate(); head.setText("Machine"); }
						sub.setText("The host's machine and its servers: what this device may switch on and off. Open another Gatekeeper under Servers to see its machine.");
						backB.setVisible(false);
						cards.show(deck, "machine"); shell[0].light("MACHINE");
					}
					default -> gear.doClick(0);
				}
				SwingUtilities.invokeLater(() -> Nav.home(f));
			};
			news.addChangeListener(ev -> { if (news.getSelectedIndex() == 0 && "CONSTELLATION".equals(shell[0].lit())) seenNow.run(); });
			shell[0] = new dev.gatehouse.look.GameShell(LaunchPopup.TOOL, new String[] {"SERVERS", "PEOPLE", "CONSTELLATION", "MACHINE", "OPTIONS"}, pickSection);
			shell[0].body(p);
			shell[0].corner("This device: " + ServerControl.systemName());
			// the bottom strip: where this launcher stands, and what the buttons do
			JLabel verL = new JLabel(LaunchPopup.TOOL + " " + ownVersion);
			verL.setFont(verL.getFont().deriveFont(Font.BOLD, 13f));
			String modeIs = updateMode(home);
			JLabel modeL = new JLabel(modeIs.equals("auto") ? "Auto updates" : modeIs.equals("constellation") ? "Constellation updates" : "Updates when you press Update");
			modeL.setForeground(new Color(0x8F98A0));
			for (javax.swing.JRadioButton rb : new javax.swing.JRadioButton[] {modeNormal, modeAuto, modeGroup}) rb.addActionListener(ev -> modeL.setText(modeAuto.isSelected() ? "Auto updates" : modeGroup.isSelected() ? "Constellation updates" : "Updates when you press Update"));
			shell[0].left().add(verL); shell[0].left().add(modeL); shell[0].left().add(gatherPips);
			dev.gatehouse.look.GameShell.Hint backHint = new dev.gatehouse.look.GameShell.Hint("B", "Esc", "Back");
			backHint.setVisible(backB.isVisible());
			backB.addComponentListener(new java.awt.event.ComponentAdapter() {
				@Override public void componentShown(java.awt.event.ComponentEvent e) { backHint.setVisible(true); }
				@Override public void componentHidden(java.awt.event.ComponentEvent e) { backHint.setVisible(false); }
			});
			backB.putClientProperty("navNow", Boolean.TRUE);   // (B goes back at once, as in a game's menu)
			Nav.tag(prismB, "extra");
			JPanel right = shell[0].right();
			right.add(new dev.gatehouse.look.GameShell.Hint("A", "Enter", "Select")); right.add(backHint);
			right.add(Box.createHorizontalStrut(6));
			right.add(new dev.gatehouse.look.GameShell.Glyph("X", "")); right.add(prismB);
			right.add(new dev.gatehouse.look.GameShell.Glyph("Y", "")); right.add(restartB);
			right.add(new dev.gatehouse.look.GameShell.Glyph("Start", "")); right.add(quit);
			List<Path> folders = new ArrayList<>();
			for (String[] e : packs) folders.add(Path.of(e[3]));
			Path launcherAt = SteamUpdate.launcherHome(folders);
			JPanel south = new JPanel(new BorderLayout(0, 10));
			if (launcherAt != null) south.add(LaunchPopup.steamRow(launcherAt, "launcher", "Update " + LaunchPopup.TOOL + "'s Steam config", f, () -> ((LaunchPopup.PlayButton) quit).halo()), BorderLayout.NORTH);
			south.setOpaque(false);
			p.add(south, BorderLayout.SOUTH);
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent ev) { closed.countDown(); } });
			f.setContentPane(shell[0]);
			if (openAt != null && (openAt.isEmpty() ? hasLoose : true)) showServers.accept(openAt);
			else { showKeepers[0].run(); if (fresh && !seenVersion.isEmpty()) pickSection.accept("CONSTELLATION"); }
			if (openAt == null && shot == null && keepers.isEmpty() && packs.isEmpty()) openGuide[0].run();   // (nothing here yet: the guide)
			// Opened directly (not on the way back from a server): look for a newer Gatehouse; with one in place, close
			// and let the shortcut's script open it. (GATEHOUSE_UPDATED: just reopened for that, so not again.)
			// It asks first: the line under the heading says a newer one is there (which, and how big), with Update
			// Gatehouse and Later, and a chime. Looked for when the window opens and again every couple of minutes
			// while it's open, so one published in the meantime shows without reopening anything. Later: not asked
			// again about that one in this window. OJ_GATEHOUSE_NEWER (testing): pretend one was found, "version|bytes".
			String pretend = System.getenv("OJ_GATEHOUSE_NEWER");
			String[][] offered = {null};
			String[] declined = {""};
			ownUpdateLater.addActionListener(ev -> { if (offered[0] != null) declined[0] = offered[0][1]; ownUpdate.setVisible(false); f.validate(); LaunchPopup.iconUpdate = false; LaunchPopup.icons(); });
			ownUpdateGo.addActionListener(ev -> {
				String[] found = offered[0];
				String how = updateHow;
				updateHow = "normal";
				if (found == null) return;
				ownUpdateGo.setEnabled(false); ownUpdateLater.setEnabled(false);
				ownUpdateText.setText("Fetching the newer " + LaunchPopup.TOOL + "\u2026");
				new Thread(() -> {
					boolean ok = Notes.fetch(found);
					// One program: the Gatekeeper on this same machine, if there is one, is updated by the same press.
					// It is given this same file (one fetch for the pair): a Gatekeeper that is running takes it and manages
					// its own update; one that isn't has its file replaced, where this launcher can reach it.
					if (ok && shot == null) dev.gatehouse.Main.pairUpdate(Path.of(found[2]).resolve("extras/launch-popup.jar"), found[1], how);
					SwingUtilities.invokeLater(() -> {
						if (ok) { again[0] = "\u0000relaunch"; f.dispose(); return; }
						ownUpdateText.setText("<html>Couldn't fetch it just now. Nothing was changed; it will be offered again.</html>");
						offered[0] = null;
						ownUpdateLater.setText("Close"); ownUpdateLater.setEnabled(true);
					});
				}, "self-update").start();
			});
			Runnable look = () -> {
				String[] found = pretend != null ? new String[] {"", "pretend", "", pretend.replaceAll("^.*\\|", ""), pretend.replaceAll("\\|.*$", "")} : Notes.newer(instances);
				if (found == null) return;
				if (pretend == null && shot == null) { Thread hand = new Thread(() -> Notes.handOver(found), "hand-over"); hand.setDaemon(true); hand.start(); }
				Runnable ui = () -> {
					if ((!f.isDisplayable() && shot == null) || LaunchPopup.embedded) return;
					if (found[1].equals(declined[0]) || (offered[0] != null && offered[0][1].equals(found[1]) && ownUpdate.isVisible())) return;
					if (!ownUpdateGo.isEnabled() && ownUpdate.isVisible() && !ownUpdateLater.isEnabled()) return;   // (one is being fetched right now)
					offered[0] = found;
					long bytes = 0; try { bytes = Long.parseLong(found[3]); } catch (NumberFormatException ignored) {}
					String have = Notes.version(Notes.own());
					ownUpdateText.setText("<html><b>A " + LaunchPopup.TOOL + " update is ready" + (found[4].isEmpty() || found[4].equals(have) ? "" : ": " + LaunchPopup.esc(found[4])) + ".</b> You have " + LaunchPopup.esc(have) + "." + (bytes > 0 ? " " + Math.max(1, Math.round(bytes / 1024.0)) + " KB to download." : "") + "</html>");
					ownUpdateGo.setEnabled(true); ownUpdateLater.setEnabled(true); ownUpdateLater.setText("Later");
					ownUpdate.setVisible(true);
					shell[0].badge("CONSTELLATION", 1);
					LaunchPopup.iconUpdate = true; LaunchPopup.icons();
					f.validate();
					if (shot == null) { LaunchPopup.chime(); Nav.land(ownUpdateGo); }
				};
				if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
			};
			// Check for updates: looks right now (it looks by itself every so often), offers again one put off with
			// Later, asks the Gatekeeper being shown again, and says what it found.
			checkB.addActionListener(ev -> {
				checkB.setEnabled(false);
				declined[0] = "";
				sub.setText("Looking for a newer " + LaunchPopup.TOOL + "\u2026");
				new Thread(() -> {
					look.run();
					for (Runnable r : new ArrayList<>(polls)) try { r.run(); } catch (RuntimeException ignored) {}
					SwingUtilities.invokeLater(() -> {
						checkB.setEnabled(true);
						sub.setText(ownUpdate.isVisible() ? "A newer " + LaunchPopup.TOOL + " is ready: see the top of this window."
								: "No newer " + LaunchPopup.TOOL + " was found: this is " + Notes.version(Notes.own()) + ". If you expected one, this device may not be reaching where the pack is published (Tailscale or the internet).");
					});
				}, "check-updates").start();
			});
			if (pretend != null && shot != null) look.run();
			else if (shot == null) {
				new Thread(look, "self-update").start();
				new Timer(Notes.pollMillis(), tick -> { if (f.isDisplayable()) new Thread(look, "self-update").start(); else ((Timer) tick.getSource()).stop(); }).start();
			}
			// Every few seconds, while this window is open, each Gatekeeper in the list is told this launcher is open, its
			// version, its update mode, and whether it has seen a newer version: that is what a Gatekeeper's Constellation
			// tab shows. In auto mode this launcher updates as soon as it has seen one. In constellation mode a Gatekeeper
			// that is gathering has it look at once, and one that says "go" has it update, as if Update had been pressed.
			if (shot == null && (System.getenv("OJ_HOME_DRY") == null || System.getenv("OJ_KEEPER_DIR") != null)) {
				Map<String, ServerControl> asked = new java.util.concurrent.ConcurrentHashMap<>();
				String cameFrom = updateSource(instances);
				Thread auto = new Thread(() -> {
					long lookedAt = 0;
					while (f.isDisplayable() || !f.isVisible()) {
						try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
						if (!f.isDisplayable()) return;
						if (LaunchPopup.embedded) continue;
						String mode = updateMode(home);
						boolean go = false, gathering = false;
						Object[] gatherSaid = {null};
						for (String[] k : new ArrayList<>(keepers)) try {
							ServerControl sc = asked.computeIfAbsent(k[0], u -> client(home, u, packs));
							String[] found = offered[0];
							Map<String, Object> r = sc.helper("POST", "/v1/update/state", "{\"mode\":\"" + mode + "\",\"source\":\"" + ServerControl.jsonEsc(cameFrom) + "\",\"install\":\"" + installId(home) + "\",\"info\":\"" + ServerControl.jsonEsc(System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "") + "; Java " + System.getProperty("java.version", "")) + "\",\"auto\":" + mode.equals("constellation") + ",\"own\":\"" + ServerControl.jsonEsc(Notes.version(Notes.own())) + "\",\"sees\":\"" + (found == null ? "" : ServerControl.jsonEsc(found[4].isEmpty() ? "newer" : found[4])) + "\"}");
							if ("go".equals(str(r.get("state")))) go = true;
							if (Notes.cmp(str(r.get("version")).isEmpty() ? "0" : str(r.get("version")), Notes.version(Notes.own()).isEmpty() ? "99999" : Notes.version(Notes.own())) > 0) keeperSaysNewer = true;
							if ("gathering".equals(str(r.get("state")))) gathering = true;
							if (r.get("gather") != null && gatherSaid[0] == null) gatherSaid[0] = r.get("gather");
						} catch (IOException | RuntimeException notNow) { /* that Gatekeeper isn't answering, or is from before this */ }
						Object shown = mode.equals("constellation") ? gatherSaid[0] : null;
						SwingUtilities.invokeLater(() -> gatherPips.set(shown));
						// (a Gatekeeper knows of a newer version than this launcher is: look now, in any mode, not at the next round)
						if (keeperSaysNewer && offered[0] == null && System.currentTimeMillis() - lookedAt > 30_000) { keeperSaysNewer = false; lookedAt = System.currentTimeMillis(); look.run(); }
						if (mode.equals("normal")) continue;
						boolean lookNow = mode.equals("auto") ? System.currentTimeMillis() - lookedAt > 60_000 : (go || gathering);
						if (lookNow && offered[0] == null) { lookedAt = System.currentTimeMillis(); look.run(); }
						boolean update = mode.equals("auto") || go;
						// (the constellation is ready: its own sound, heard out before this launcher closes to update)
						if (go && mode.equals("constellation") && offered[0] != null && ownUpdateGo.isEnabled()) { dev.gatehouse.look.Chime.constellation(); try { Thread.sleep(dev.gatehouse.look.Chime.MILLIS); } catch (InterruptedException e) { return; } }
						if (update && offered[0] != null) SwingUtilities.invokeLater(() -> { if (offered[0] != null && ownUpdateGo.isEnabled() && ownUpdate.isVisible()) { updateHow = mode; sub.setText(mode.equals("auto") ? "Updating by itself (auto mode)\u2026" : "Updating together with your Gatekeeper (constellation mode)\u2026"); ownUpdateGo.doClick(); } });
						else if (gathering) SwingUtilities.invokeLater(() -> sub.setText("A new version is out. Your Gatekeeper is waiting for every open launcher in its constellation to see it, then everything updates together."));
					}
				}, "update-mode");
				auto.setDaemon(true);
				auto.start();
			}
			f.pack();
			if (shot == null) f.setSize(Math.max(f.getWidth(), 1180), Math.max(f.getHeight(), 720)); else f.setSize(1280, 800);   // (as wide as a server's window, so little changes on the way in)
			LaunchPopup.fit(f, false);
			if (shot != null && !shot.isEmpty()) {   // (testing: pictures instead of the window: <file>, and <file>-servers.png for the first Gatekeeper)
				try {
					dev.gatehouse.look.GameShell sh = shell[0];
					java.util.function.Consumer<String> snap = suffix -> {
						f.validate(); f.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(sh.getWidth(), sh.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics(); sh.paint(g); g.dispose();
						try { javax.imageio.ImageIO.write(img, "png", new java.io.File(suffix.isEmpty() ? shot : shot.replaceAll("\\.png$", "") + "-" + suffix + ".png")); } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
					};
					dev.gatehouse.look.GameShell.pad(System.getenv("OJ_SHOT_PAD") != null);
					showKeepers[0].run(); snap.accept("");
					if (!keepers.isEmpty()) {
						showServers.accept(keepers.get(0)[0]);
						if (serverList.getComponentCount() > 0 && serverList.getComponent(0) instanceof dev.gatehouse.look.GameCard gc) { gc.glow(true); side.show(gc); }
						snap.accept("servers");
						pickSection.accept("MACHINE"); snap.accept("connections");
					}
					pickSection.accept("PEOPLE"); snap.accept("people");
					pickSection.accept("CONSTELLATION"); snap.accept("constellation");
					pickSection.accept("OPTIONS"); snap.accept("options");
					cards.show(deck, "wizard"); guide[0].openNow(System.getenv("OJ_WIZARD_STEP")); snap.accept("wizard");
				} catch (java.io.UncheckedIOException ex) { ex.printStackTrace(); }
				f.dispose();
				return;
			}
			Timer timer = new Timer(6000, ev -> { if (f.isDisplayable()) for (Runnable r : new ArrayList<>(polls)) new Thread(r, "catalog").start(); else ((Timer) ev.getSource()).stop(); });
			timer.start();
			java.awt.Rectangle from = cameBack; cameBack = null;
			if (from != null) LaunchPopup.place(f, from); else f.setLocationRelativeTo(null);
			f.setVisible(true);
			LaunchPopup.bringForwardOnce(f);
			SwingUtilities.invokeLater(() -> Nav.home(f));
		});
		closed.await();
	}
}
