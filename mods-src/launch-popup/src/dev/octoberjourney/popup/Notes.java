package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import dev.gatehouse.look.Quiet;

/** Notes about the programs themselves, kept apart from any pack's notes. There are four kinds of notes in all:
 *    Gatehouse (this launcher)       its own, inside this program (source/gatehouse.json); shown on the main window
 *    Gatekeeper                      its own, asked of each Gatekeeper (GET /v1/about); shown beside that Gatekeeper's servers
 *    a pack (the game on a device)   the pack's, shown in that server's launcher under Client updates
 *    a server                        the server's, shown in that server's launcher under Server updates
 *  Also here: Gatehouse fetching a newer copy of itself when it's opened directly. */
final class Notes {
	private Notes() {}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	/** Gatehouse's own version and notes: {"version": ..., "changes": [{version, date, notes: [...]}, ...]}, newest first. */
	static Map<String, Object> own() {
		try (InputStream in = Notes.class.getResourceAsStream("/gatehouse-notes.json")) {
			return in == null ? new java.util.LinkedHashMap<>() : ServerControl.asMap(LaunchPopup.parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) { return new java.util.LinkedHashMap<>(); }
	}
	static String version(Map<String, Object> notes) { return str(notes.get("version")); }
	/** Version numbers compared part by part ("2.10.0" is after "2.9.1"): below zero when a is the older. */
	static int cmp(String a, String b) {
		String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = 0, q = 0;
			try { if (i < x.length) p = Integer.parseInt(x[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
			try { if (i < y.length) q = Integer.parseInt(y[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}
	/** Is what a source serves (the "gatehouse" version in its update-sizes.json) older than this launcher? A source
	 *  can lag behind another (GitHub's copy is cached for some minutes): a launcher never "updates" backwards. */
	static boolean servedIsOlder(Map<String, Object> sizes) {
		String served = str(sizes.get("gatehouse")), mine = version(own());
		return !served.isEmpty() && !mine.isEmpty() && cmp(served, mine) < 0;
	}

	/** Gatehouse's own home when it was installed by itself, with no pack (gatehouse-install): <Prism's
	 *  folder>/gatehouse/app, laid out like the part of a pack the launcher needs (extras/launch-popup.jar, the
	 *  scripts beside it, and pack-sources.txt: where to look for a newer launcher). Packs may come and go around it. */
	static Path app(Path instances) { return instances.toAbsolutePath().getParent().resolve("gatehouse").resolve("app"); }
	/** The files of that home besides the launcher itself, kept the same as what the source serves. */
	static final String[] APP_FILES = {"gatehouse-launcher.ps1", "gatehouse-launcher.sh", "plain-launch.ps1", "plain-launch.sh",
			"launch-wrapper.exe", "launch-wrapper.sh"};
	private static void refreshApp(String base, Path app) {
		try {
			Map<String, Object> files = ServerControl.asMap(ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(base + "/update-sizes.json"), StandardCharsets.UTF_8))).get("files"));
			for (String f : APP_FILES) try {
				String want = str(ServerControl.asMap(files.get("extras/" + f)).get("hash"));
				Path dst = app.resolve("extras").resolve(f);
				if (want.isEmpty() || (Files.isRegularFile(dst) && want.equals(LaunchPopup.sha256(Files.readAllBytes(dst))))) continue;
				byte[] b = LaunchPopup.fetchBytes(base + "/extras/" + f);
				if (!want.equals(LaunchPopup.sha256(b))) continue;
				Files.write(dst, b);
				if (f.endsWith(".sh")) dst.toFile().setExecutable(true, false);
			} catch (Exception next) { /* that one next time */ }
		} catch (Exception ignored) { Quiet.seen(ignored); }
	}

	/** After this launcher updated itself, when nothing started it that would open the new one (a shortcut script
	 *  from before it reopened more than once): the new one is started from here. True when it was. */
	static boolean reopen(Path instances, String[] args) {
		try {
			Path newest = null;
			List<Path> jars = new ArrayList<>(List.of(app(instances).resolve("extras/launch-popup.jar")));
			try (java.util.stream.Stream<Path> s = Files.list(instances)) { s.map(d -> d.resolve("minecraft/extras/launch-popup.jar")).forEach(jars::add); } catch (IOException ignored) { Quiet.seen(ignored); }
			for (Path j : jars) if (Files.isRegularFile(j) && (newest == null
					|| Files.getLastModifiedTime(j).compareTo(Files.getLastModifiedTime(newest)) > 0)) newest = j;
			String javaExe = ProcessHandle.current().info().command().orElse("");
			if (newest == null || javaExe.isEmpty()) return false;
			Path run = newest;
			if (System.getProperty("os.name", "").startsWith("Windows")) {   // (from a copy, under the name this one isn't using)
				Path me = Path.of(Notes.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				run = instances.toAbsolutePath().getParent().resolve(me.getFileName().toString().equals("gatehouse-launcher-run-2.jar")
						? "gatehouse-launcher-run.jar" : "gatehouse-launcher-run-2.jar");
				Files.copy(newest, run, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			List<String> cmd = new ArrayList<>(List.of(javaExe, "-jar", run.toString()));
			for (int i = 0; i < args.length; i++) { if (args[i].equals("--play")) { i++; continue; } cmd.add(args[i]); }
			ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
			pb.environment().put("GATEHOUSE_UPDATED", "1");
			pb.start();
			return true;
		} catch (Exception e) { return false; }
	}

	/** A scrolling list of a program's notes, newest on top; "none" is said when there are none to show. */
	static JComponent panel(Map<String, Object> notes, String title, String none) {
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
		List<Component> rows = new ArrayList<>();
		String v = version(notes);
		if (!v.isEmpty()) { JLabel now = new JLabel(title + " " + v
				+ " is what's running."); now.setForeground(new Color(0x8F98A0)); now.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0)); rows.add(now); }
		if (notes.get("changes") instanceof List<?> l) for (Object o : l) {
			Map<String, Object> c = ServerControl.asMap(o);
			rows.add(LaunchPopup.heading(str(c.get("version")) + (str(c.get("date")).isEmpty() ? "" : " (" + str(c.get("date")) + ")")));
			if (c.get("notes") instanceof List<?> ns) for (Object n : ns) rows.add(LaunchPopup.text("• " + n));
		}
		if (rows.size() <= (v.isEmpty() ? 0
				: 1)) { JLabel e = new JLabel(none); e.setForeground(new Color(0x8F98A0)); e.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0)); rows.add(e); }
		for (Component r : rows) { ((JComponent) r).setAlignmentX(Component.LEFT_ALIGNMENT); list.add(r); }
		JPanel top = new JPanel(new BorderLayout());
		top.add(list, BorderLayout.NORTH);
		JScrollPane sc = new JScrollPane(top);
		sc.setBorder(null);
		sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sc.getVerticalScrollBar().setUnitIncrement(16);
		return sc;
	}

	/** Looks for a newer Gatehouse where the installed packs get their updates (each pack's pack-sources.txt, in order;
	 *  the launcher is part of every pack, as extras/launch-popup.jar, with its hash in update-sizes.json), and if there
	 *  is one, fetches it, checks it against that hash, and puts it in place of the newest copy here (the one the
	 *  Steam shortcut's script runs). True when a newer one is now in place: the launcher should close and be reopened.
	 *  Nothing found, nothing reachable, or anything going wrong: false, and nothing is changed.
	 *  (The main window does this in two steps, newer() then fetch(), asking in between.) */
	static boolean selfUpdate(Path instances) {
		String[] found = newer(instances);
		return found != null && fetch(found);
	}

	/** How often an open window looks again for something newer (OJ_UPDATE_POLL_MS: testing). */
	static int pollMillis() {
		try { return Math.max(500, Integer.parseInt(System.getenv("OJ_UPDATE_POLL_MS"))); } catch (RuntimeException e) { return 120_000; }
	}

	private static boolean deck() {
		try { String p = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim(); return p.equals("Jupiter")
				|| p.equals("Galileo"); } catch (IOException | RuntimeException e) { return false; }
	}

	/** A pack's update, looked for while that server's launcher is open: what the pack's source serves now against
	 *  what's installed in this game folder. Null when nothing could be reached (or this isn't a pack); else
	 *  {the pack version served, how many files would be downloaded, how many bytes}. Nothing is changed. */
	static String[] packNewer(Path dir) {
		try {
			if (!Files.isRegularFile(dir.resolve("pack-sources.txt"))) return null;
			for (String line : Files.readAllLines(dir.resolve("pack-sources.txt"), StandardCharsets.UTF_8)) {
				String src = line.strip().replaceAll("/+$", "");
				if (src.isEmpty() || src.startsWith("#")) continue;
				for (String variant : deck() ? new String[] {"deck", "main"} : new String[] {"main"}) try {
					String json = new String(LaunchPopup.fetchBytes(src + "/" + variant + "/update-sizes.json"), StandardCharsets.UTF_8);
					long[] n = LaunchPopup.pending(dir, json);
					if (n == null) continue;
					return new String[] {str(ServerControl.asMap(LaunchPopup.parseJson(json)).get("version")), String.valueOf(n[1]), String.valueOf(n[0])};
				} catch (IOException | RuntimeException next) { /* the next place */ }
			}
		} catch (Exception ignored) { Quiet.seen(ignored); }
		return null;
	}

	/** The Gatekeepers this launcher knows ({address, this device's id there, its token there}). Each holds the newest
	 *  program file it has heard of and gives it out (/v1/program), so they are asked before any place outside. */
	static volatile List<String[]> peers = List.of();
	private static final java.util.Set<String> handed = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static String post(String[] peer, String path, byte[] body, String type, String sha) throws IOException {
		java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(peer[0].replaceAll("/+$", "") + path).toURL().openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
		c.setRequestProperty("Content-Type", type); c.setRequestProperty("X-Device", peer[1]); c.setRequestProperty("X-Token", peer[2]);
		if (!sha.isEmpty()) c.setRequestProperty("X-Sha256", sha);
		c.setFixedLengthStreamingMode(body.length);
		try (java.io.OutputStream o = c.getOutputStream()) { o.write(body); }
		try (java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? ""
				: new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	/** This launcher has seen a newer version at a place outside before its Gatekeepers have it: it raises its hand
	 *  with each (only one that trusts this launcher with it says go: the one on this machine, or one where it has
	 *  Gatekeeper control), fetches the file once, and hands it over. Once for each version. */
	static void handOver(String[] found) {
		if (found == null || found.length < 5 || found[4].isEmpty() || found[0].contains("/v1/program") || !handed.add(found[4])) return;
		List<String[]> go = new ArrayList<>();
		for (String[] p : peers) try {
			if ("true".equals(str(ServerControl.asMap(LaunchPopup.parseJson(post(p, "/v1/program/hand", ("{\"version\":\""
					+ ServerControl.jsonEsc(found[4]) + "\"}").getBytes(StandardCharsets.UTF_8), "application/json", ""))).get("go")))) go.add(p);
		} catch (IOException | RuntimeException notNow) { /* that one isn't answering */ }
		if (go.isEmpty()) return;
		try {
			byte[] jar = LaunchPopup.fetchBytes(found[0] + "/extras/launch-popup.jar");
			if (!found[1].equals(LaunchPopup.sha256(jar))) return;
			for (String[] p : go) try { post(p, "/v1/program", jar, "application/java-archive",
					found[1]); } catch (IOException | RuntimeException notNow) { /* it fetches its own */ }
		} catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException e) { handed.remove(found[4]); }
	}

	/** The fetching half: {where the pack is served, the hash wanted, the pack folder here to put it in, ...} from newer(). */
	/** How far the fetch of a newer Gatehouse has come (for the bar under its offer). */
	static final dev.gatehouse.look.Download.State FETCHING = new dev.gatehouse.look.Download.State();
	static boolean fetch(String[] found) {
		try {
			byte[] jar = LaunchPopup.fetchBytes(found[0] + "/extras/launch-popup.jar", FETCHING);
			if (!found[1].equals(LaunchPopup.sha256(jar))) return false;
			// (R24: the release signature, from this launcher's Gatekeepers, else GitHub. One that doesn't fit: not taken)
			List<String> sigAt = new ArrayList<>();
			for (String[] p : peers) sigAt.add(p[0].replaceAll("/+$", "") + "/v1/program/sig/");
			String signed = dev.gatehouse.Signed.check(jar, found[1], sigAt, null);
			if (System.getenv("OJ_DEBUG") != null) System.out.println("self-update: " + dev.gatehouse.Signed.words(signed));
			if (signed.equals("bad")) return false;
			Path dst = Path.of(found[2]).resolve("extras/launch-popup.jar"), tmp = Path.of(found[2]).resolve("extras/launch-popup.jar.part");
			Files.write(tmp, jar);
			try { Files.move(tmp, dst,
					java.nio.file.StandardCopyOption.REPLACE_EXISTING); } finally { Files.deleteIfExists(tmp); }   // (in use, as when run straight from there on Windows: left as it was)
			// (where this launcher file came from, beside it: a Gatekeeper's update log says it. "<its checksum>|<the place>")
			try { Files.writeString(dst.resolveSibling("launch-popup.source.txt"), found[1] + "|" + found[0],
					StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
			// (Gatehouse's own home: the scripts beside the launcher are brought up to date with it)
			if (Path.of(found[2]).getFileName().toString().equals("app") && Path.of(found[2]).getParent() != null
					&& Path.of(found[2]).getParent().getFileName().toString().equals("gatehouse")) refreshApp(found[0], Path.of(found[2]));
			return true;
		} catch (Exception e) { if (System.getenv("OJ_DEBUG") != null) e.printStackTrace(); return false; }
	}

	/** The looking half: null when this is the newest Gatehouse there is (or nothing could be reached), else
	 *  {where the pack is served, the hash wanted, the pack folder here to put it in, its size in bytes or "",
	 *  its version or ""}. Nothing is fetched or changed. */
	static String[] newer(Path instances) {
		try {
			Path me = Path.of(Notes.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(me)) return null;
			String mine = LaunchPopup.sha256(Files.readAllBytes(me));
			boolean deck = false;
			try { String p = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim(); deck = p.equals("Jupiter")
					|| p.equals("Galileo"); } catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
			List<Path> packs = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(instances)) { s.filter(Files::isDirectory).map(d -> d.resolve("minecraft")).filter(m -> Files.isRegularFile(m.resolve("pack-sources.txt")) && Files.isRegularFile(m.resolve("extras/launch-popup.jar"))).forEach(packs::add); }
			catch (IOException noInstances) { /* none yet */ }
			// (Gatehouse's own home counts like a pack here: it has the launcher and says where a newer one is found)
			Path own = app(instances);
			if (Files.isRegularFile(own.resolve("pack-sources.txt")) && Files.isRegularFile(own.resolve("extras/launch-popup.jar"))) packs.add(own);
			if (packs.isEmpty()) return null;
			// (the newest copy among them is the one that gets run)
			Path target = packs.get(0);
			for (Path p : packs) if (Files.getLastModifiedTime(p.resolve("extras/launch-popup.jar")).compareTo(Files.getLastModifiedTime(target.resolve("extras/launch-popup.jar"))) > 0) target = p;
			// (a Gatekeeper that already holds a newer program than this one: taken from there, with nothing asked outside)
			String mineV = version(own());
			for (String[] peer : peers) try {
				String base = peer[0].replaceAll("/+$", "") + "/v1/program/main";
				Map<String, Object> sizes = ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(base
						+ "/update-sizes.json"), StandardCharsets.UTF_8)));
				Map<String, Object> entry = ServerControl.asMap(ServerControl.asMap(sizes.get("files")).get("extras/launch-popup.jar"));
				String want = str(entry.get("hash")), v = str(sizes.get("gatehouse"));
				if (!want.isEmpty() && !want.equals(mine) && !v.isEmpty() && !mineV.isEmpty() && cmp(v,
						mineV) > 0) return new String[] {base, want, target.toString(), str(entry.get("size")).replaceAll("\\..*$", ""), v};
			} catch (IOException | RuntimeException next) { /* that Gatekeeper isn't answering, or is from before this */ }
			List<String> tried = new ArrayList<>();
			for (Path p : packs) for (String line : Files.readAllLines(p.resolve("pack-sources.txt"), StandardCharsets.UTF_8)) {
				String src = line.strip().replaceAll("/+$", "");
				if (src.isEmpty() || src.startsWith("#") || tried.contains(src)) continue;
				tried.add(src);
				for (String variant : deck ? new String[] {"deck", "main"} : new String[] {"main"}) try {
					String base = src + "/" + variant;
					Map<String, Object> sizes = ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(base
							+ "/update-sizes.json"), StandardCharsets.UTF_8)));
					Map<String, Object> entry = ServerControl.asMap(ServerControl.asMap(sizes.get("files")).get("extras/launch-popup.jar"));
					String want = str(entry.get("hash"));
					if (want.isEmpty() || servedIsOlder(sizes)) continue;   // (nothing there, or an older one than this: the next place)
					if (want.equals(mine)) return null;   // (this is the newest there is)
					return new String[] {base, want, target.toString(), str(entry.get("size")).replaceAll("\\..*$", ""), str(sizes.get("gatehouse"))};
				} catch (IOException | RuntimeException next) { if (System.getenv("OJ_DEBUG") != null) next.printStackTrace(); /* the next place */ }
			}
		} catch (Exception e) { if (System.getenv("OJ_DEBUG") != null) e.printStackTrace(); }
		return null;
	}
}
