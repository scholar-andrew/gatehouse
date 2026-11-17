package dev.gatehouse;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import dev.gatehouse.look.AppIcon;
import dev.octoberjourney.host.HostHelper;
import dev.octoberjourney.popup.LaunchPopup;

/**
 * Gatehouse is one program with two halves: Play (the launcher players use, "Gatehouse") and Host (the part that
 * looks after the Minecraft servers on a machine, "Gatekeeper"). This is where it starts, and it only decides which
 * half was asked for; each half then runs exactly as it did when the two were separate programs.
 *
 *   --serve, --panel, --minimized, --uninstall-command     the Host half (see HostHelper)
 *   --choose                                               the first screen: Play or Host
 *   --start                                                what a shortcut for the whole program opens: Play when
 *                                                          it is set up here (the Host half runs in the background
 *                                                          and is a button away in it), else Host's window when
 *                                                          only that is, else the first screen
 *   anything else                                          the Play half (see LaunchPopup)
 *   nothing at all                                         the Host half's window when this file is a Gatekeeper's own
 *                                                          (it is called oj-host.jar, or has host.json beside it);
 *                                                          otherwise the first screen
 *
 * The file is published under both of its old names (the pack's extras/launch-popup.jar and gatekeeper/oj-host.jar),
 * the same bytes, so a device with either half keeps updating the way it always did.
 */
public final class Main {
	static final boolean WIN = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	static final Color GREY = new Color(0x8F98A0), GREEN = new Color(0x8BD46E), AMBER = new Color(0xE9B949);

	public static void main(String[] args) throws Exception {
		// Plain (GDI / X11) drawing, not Direct3D or OpenGL: Steam's overlay hooks those in programs Steam started, and
		// drew its pop-ups into the window (over the buttons at the bottom right, leaving a smear when they went). This
		// has to be said before anything of the window system is touched, or it is too late to take effect: it used to
		// be said later, after the look had been set up.
		System.setProperty("sun.java2d.d3d", "false");
		System.setProperty("sun.java2d.opengl", "false");
		System.setProperty("sun.java2d.noddraw", "true");
		String first = args.length > 0 ? args[0] : "";
		if (first.equals("--choose")) { choose(null); return; }
		if (first.equals("--start")) {
			Path script = playScript(), keeper = keeperDir();
			if (script != null) { String t = run(openPlay(script)); if (!t.isEmpty()) choose(null); }
			else if (keeper != null || keeperDoor() > 0) { String t = openGatekeeper(); if (!t.isEmpty()) choose(null); }
			else choose(null);
			return;
		}
		if (first.equals("--build-pack")) { dev.octoberjourney.host.PackBuilder.main(args); return; }   // (building a pack from its definition: see PackBuilder)
		if (List.of("--serve", "--panel", "--minimized", "--uninstall-command").contains(first) || (args.length == 0 && isKeeperFile())) { HostHelper.main(args); return; }
		if (args.length == 0) { choose(null); return; }
		LaunchPopup.main(args);
	}

	static Path own() {
		try { return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()); } catch (Exception e) { return Path.of("."); }
	}
	/** Is this file a Gatekeeper's own copy (so that opening it with nothing asked means its window, as before)? */
	static boolean isKeeperFile() {
		Path me = own();
		return System.getenv("OJ_HOST_DIR") != null || (me.getFileName() != null && me.getFileName().toString().equalsIgnoreCase("oj-host.jar")) || (me.getParent() != null && Files.isRegularFile(me.getParent().resolve("host.json")));
	}

	// ------------------------------------------------------------------ where each half is on this device

	/** Prism Launcher's data folders that could hold Gatehouse (OJ_PRISM_ROOT, for testing, replaces them). */
	public static List<Path> prismRoots() {
		List<Path> out = new ArrayList<>();
		String forced = System.getenv("OJ_PRISM_ROOT"), home = System.getProperty("user.home", "");
		if (forced != null && !forced.isEmpty()) { out.add(Path.of(forced)); return out; }
		if (System.getenv("OJ_HOST_DIR") != null) return out;   // (a Gatekeeper under test never touches this device's real launcher)
		if (WIN) { String ad = System.getenv("APPDATA"); if (ad != null) out.add(Path.of(ad, "PrismLauncher")); }
		else { out.add(Path.of(home, ".local/share/PrismLauncher")); out.add(Path.of(home, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")); }
		return out;
	}
	/** The script that opens the Play half as its shortcut does (Gatehouse's own, else one a pack carries), or null. */
	public static Path playScript() {
		String name = WIN ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh";
		for (Path root : prismRoots()) {
			Path own = root.resolve("gatehouse/app/extras").resolve(name);
			if (Files.isRegularFile(own)) return own;
			try (java.util.stream.Stream<Path> s = Files.list(root.resolve("instances"))) {
				for (Path inst : (Iterable<Path>) s.sorted()::iterator) if (Files.isRegularFile(inst.resolve("minecraft/extras").resolve(name))) return inst.resolve("minecraft/extras").resolve(name);
			} catch (IOException | RuntimeException none) { /* no instances there */ }
		}
		return null;
	}
	/** The folder the Host half is installed in (the one this file is in, when it is a Gatekeeper's), or null. OJ_KEEPER_DIR, for testing, replaces the usual places. */
	public static Path keeperDir() {
		String forced = System.getenv("OJ_KEEPER_DIR"), home = System.getProperty("user.home", "");
		List<Path> places = new ArrayList<>();
		if (forced != null && !forced.isEmpty()) places.add(Path.of(forced));
		else {
			if (own().getParent() != null) places.add(own().getParent());
			if (WIN) { places.add(Path.of("C:\\Minecraft\\GatehouseKeeper")); String la = System.getenv("LOCALAPPDATA"); if (la != null) places.add(Path.of(la, "Programs", "GatehouseKeeper")); }
			else places.add(Path.of(home, ".local/share/GatehouseKeeper"));
		}
		for (Path p : places) if (Files.isRegularFile(p.resolve("oj-host.jar")) && Files.isRegularFile(p.resolve("host.json"))) return p;
		return null;
	}
	/** The port that Gatekeeper's window (and nothing off this machine) talks to it on. */
	public static int keeperPort(Path dir) {
		try {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*\"?(\\d+)").matcher(Files.readString(dir.resolve("host.json"), StandardCharsets.UTF_8));
			if (m.find()) return Integer.parseInt(m.group(1));
		} catch (IOException | RuntimeException ignored) {}
		return 25570;
	}
	/** Is that Gatekeeper's background part answering on this machine right now? */
	public static boolean keeperRunning(Path dir) {
		try (java.net.Socket s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress("127.0.0.1", keeperPort(dir)), 500); return true; } catch (IOException e) { return false; }
	}

	/** The port a Gatekeeper on this machine answers its window on right now, or 0. It is asked for, at the port its
	 *  settings name (when its folder can be seen) and at the usual one, because its folder can't always be seen: on
	 *  a Steam Deck the launcher runs inside Prism Launcher's Flatpak, which is shown only its own files, while the
	 *  Gatekeeper is installed outside it. What answers has to say it is a Gatekeeper. */
	public static int keeperDoor() {
		List<Integer> ports = new ArrayList<>();
		String only = System.getenv("OJ_KEEPER_PORT");   // (testing: a stand-in Gatekeeper whose folder is kept out of sight)
		if (only != null && only.matches("[0-9]+")) return askKeeper(Integer.parseInt(only), "GET", "/admin/about", null).isEmpty() ? 0 : Integer.parseInt(only);
		Path dir = keeperDir();
		if (dir != null) ports.add(keeperPort(dir));
		if (System.getenv("OJ_KEEPER_DIR") == null && !ports.contains(25570)) ports.add(25570);   // (a stand-in under test is only asked for at its own port)
		for (int port : ports) if (!askKeeper(port, "GET", "/admin/about", null).isEmpty()) return port;
		return 0;
	}
	/** Asks the Gatekeeper on this machine something; "" when nothing answers there, or what answers isn't a Gatekeeper. */
	public static String askKeeper(int port, String method, String path, String body) {
		try {
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
			c.setConnectTimeout(700); c.setReadTimeout(path.equals("/admin/update") ? 40000 : 4000); c.setRequestMethod(method);
			if (body != null) { c.setDoOutput(true); try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
			try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
				String said = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
				return path.equals("/admin/about") && !said.contains("\"gatekeeper\"") ? "" : c.getResponseCode() < 400 ? (said.isEmpty() ? "{}" : said) : "";
			}
		} catch (IOException | RuntimeException e) { return ""; }
	}
	/** Is the Host half set up on this machine (its folder is here, or it answers)? */
	public static boolean hostHere() { return keeperDir() != null || keeperDoor() > 0; }
	/** Opens Gatekeeper's window: by asking the running Gatekeeper to open it itself (which works from inside a
	 *  sandbox too), else by starting it from its folder. "" when it went, else why not. */
	public static String openGatekeeper() {
		if (System.getenv("OJ_HOME_DRY") != null) { System.out.println("would open Gatekeeper's window (door " + keeperDoor() + ", folder " + keeperDir() + ")"); return ""; }
		int door = keeperDoor();
		// Inside a sandbox on Linux (Prism Launcher's Flatpak: the Steam Deck), the window is shown from here, as a
		// part of this launcher: Gatekeeper is a background service there, and in a game session (the Deck's Game
		// Mode) a window it opens itself is never put on the screen. A window that is open already is switched to.
		if (door > 0 && !WIN && Files.exists(Path.of("/.flatpak-info"))) {
			if (askKeeper(door, "GET", "/admin/window?peek", null).matches("(?s).*\"open\"\\s*:\\s*true.*")) { askKeeper(door, "POST", "/admin/local/open", "{}"); return ""; }
			try {
				ProcessBuilder pb = new ProcessBuilder(java(), "-jar", own().toString(), "--panel");
				pb.environment().put("OJ_PANEL_DOOR", String.valueOf(door));
				pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				return "";
			} catch (IOException e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
		}
		if (door > 0 && askKeeper(door, "POST", "/admin/local/open", "{}").contains("\"ok\"")) return "";
		Path dir = keeperDir();
		if (dir != null) return run(openHost(dir));
		return door > 0 ? "this Gatekeeper is from before it could be asked to open its window: update it, or open it from its own shortcut" : "no Gatekeeper was found on this machine";
	}

	// ------------------------------------------------------------------ one update for both halves on a device

	/** The Host half has just fetched and checked a newer program file: the same file is put where the Play half on
	 *  this device keeps its copies (Gatehouse's own, and any a pack carries), so both halves are the new one together.
	 *  The launcher runs from a copy of its file, so these are never in use. Returns how many were replaced. */
	public static int updatePlayCopies(Path fresh, String source) {
		int done = 0;
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(fresh.toFile())) { if (z.getEntry("dev/gatehouse/Main.class") == null || z.getEntry("gatehouse-notes.json") == null) return 0; }
		catch (IOException | RuntimeException e) { return 0; }
		List<Path> copies = new ArrayList<>();
		for (Path root : prismRoots()) {
			copies.add(root.resolve("gatehouse/app/extras/launch-popup.jar"));
			try (java.util.stream.Stream<Path> s = Files.list(root.resolve("instances"))) { s.forEach(i -> copies.add(i.resolve("minecraft/extras/launch-popup.jar"))); } catch (IOException | RuntimeException none) { /* no instances */ }
		}
		for (Path c : copies) if (Files.isRegularFile(c)) try {
			if (Files.mismatch(c, fresh) < 0) continue;   // (already this one)
			Path part = c.resolveSibling("launch-popup.jar.part");
			Files.copy(fresh, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.move(part, c, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			done++;
			// (where it came from, beside it, for the update log: "<its checksum>|<the place>")
			try {
				StringBuilder sum = new StringBuilder();
				for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fresh))) sum.append(String.format("%02x", b));
				Files.writeString(c.resolveSibling("launch-popup.source.txt"), sum + "|the Gatekeeper on this machine, which fetched it from " + source, StandardCharsets.UTF_8);
			} catch (IOException | java.security.NoSuchAlgorithmException ignored) {}
		} catch (IOException | RuntimeException left) { /* that copy is left as it was; its own update gets it */ }
		return done;
	}
	/** The Play half has just updated itself by its owner's press: the Gatekeeper on this same machine is told to
	 *  update too (it waits for servers it runs itself to be off). Returns what it said, or "" when there is none here. */
	public static String tellKeeperToUpdate() { return tellKeeperToUpdate("normal"); }
	/** how: this launcher's own way of updating just now (normal: Update was pressed; auto; constellation: on the go). */
	public static String tellKeeperToUpdate(String how) {
		if (System.getenv("OJ_HOME_DRY") != null) return "";
		int door = keeperDoor();
		if (door <= 0) return "";
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(askKeeper(door, "POST", "/admin/update", "{\"with_play\":true,\"how\":\"" + (how.equals("auto") || how.equals("constellation") ? how : "normal") + "\"}"));
		return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "asked";
	}
	/** The Play half has just fetched a newer program file: the Host half on this same machine gets that same file, so
	 *  the pair needs it fetched only once.
	 *    - Gatekeeper is running: it is handed the file, and manages its own update with it (waiting, as always, for
	 *      servers it runs itself to be off);
	 *    - Gatekeeper is set up but not running: its file is replaced where this launcher can reach it (the old one is
	 *      kept beside it as oj-host.jar.old). Windows only: elsewhere a launcher can't be sure it isn't running, and
	 *      inside a sandbox it can't see the folder at all.
	 *  Returns what happened, in a few words ("" for nothing). */
	public static String pairUpdate(Path jar, String sha) { return pairUpdate(jar, sha, "normal"); }
	public static String pairUpdate(Path jar, String sha, String how) {
		if (System.getenv("OJ_HOME_DRY") != null && System.getenv("OJ_KEEPER_DIR") == null) return "";
		try {
			int door = keeperDoor();
			if (door > 0) {
				java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + door + "/admin/program").toURL().openConnection();
				byte[] b = Files.readAllBytes(jar);
				c.setConnectTimeout(2000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/java-archive"); c.setRequestProperty("X-Sha256", sha);
				c.setFixedLengthStreamingMode(b.length);
				try (java.io.OutputStream o = c.getOutputStream()) { o.write(b); }
				c.getResponseCode();
				String said = tellKeeperToUpdate(how);
				return said.isEmpty() ? "handed to the Gatekeeper on this machine" : said;
			}
			Path dir = keeperDir();
			if (dir == null || !WIN) return "";
			Path target = dir.resolve("oj-host.jar");
			if (Files.mismatch(target, jar) < 0) return "";
			try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) { if (z.getEntry("dev/octoberjourney/host/HostHelper.class") == null || z.getEntry("gatekeeper-notes.json") == null) return ""; }
			Path part = dir.resolve("oj-host.jar.part");
			Files.copy(jar, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			Files.copy(target, dir.resolve("oj-host.jar.old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			try { Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } finally { Files.deleteIfExists(part); }   // (in use after all: left as it was)
			return "the Gatekeeper on this machine, which isn't running, was updated with it";
		} catch (IOException | RuntimeException e) { return ""; }
	}
	static String java() {
		String j = ProcessHandle.current().info().command().orElse(WIN ? "javaw.exe" : "java");
		return WIN ? j.replaceAll("(?i)java\\.exe$", "javaw.exe") : j;
	}

	/** What starts each half, or sets it up. Nothing here is run when OJ_HOME_DRY is set (testing): it is printed. */
	public static List<String> openPlay(Path script) {
		return WIN ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString()) : List.of("bash", script.toString());
	}
	public static List<String> openHost(Path dir) { return List.of(java(), "-jar", dir.resolve("oj-host.jar").toString(), "--panel"); }
	/** Setting a half up runs Gatehouse's own install script, which this program carries, in a window of its own
	 *  where it says what it does and asks before fetching anything. */
	static List<String> setUp(boolean host) throws IOException {
		String name = WIN ? "gatehouse-install.ps1" : "gatehouse-install.sh";
		Path to = Path.of(System.getProperty("java.io.tmpdir"), "gatehouse-setup", name);
		Files.createDirectories(to.getParent());
		try (InputStream in = Main.class.getResourceAsStream("/" + name)) {
			if (in == null) throw new IOException("this program doesn't carry its install script");
			Files.write(to, in.readAllBytes());
		}
		if (WIN) return List.of("cmd.exe", "/c", "start", "Setting up " + (host ? "Gatekeeper" : "Gatehouse"), "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", to.toString(), host ? "-Gatekeeper" : "-Gatehouse");
		String cmd = "bash '" + to + "' " + (host ? "--gatekeeper" : "") + "; echo; echo 'Done. You can close this window.'; read -r _";
		for (String[] term : new String[][] {{"konsole", "-e"}, {"gnome-terminal", "--"}, {"xfce4-terminal", "-x"}, {"xterm", "-e"}})
			for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
				if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, term[0]))) return List.of(term[0], term[1], "bash", "-c", cmd);
		throw new IOException("no terminal program was found to run it in. Run this in a terminal yourself: bash " + to + (host ? " --gatekeeper" : ""));
	}
	/** Starts the Gatekeeper on this machine in the background, as signing in does: its scheduled task on Windows (or
	 *  the program itself, from its folder, where there is no task), its service on Linux and SteamOS (asked of the
	 *  system outside the sandbox when this runs inside one). "" when it was asked to start, else why not. */
	public static String startKeeper() {
		if (keeperDoor() > 0) return "";
		if (System.getenv("OJ_HOME_DRY") != null) { System.out.println("would start the Gatekeeper on this machine (folder " + keeperDir() + ")"); return ""; }
		try {
			if (WIN) {
				Path dir = keeperDir();
				if (dir == null) return "no Gatekeeper was found on this machine";
				boolean standIn = System.getenv("OJ_KEEPER_DIR") != null;   // (testing: a stand-in folder; the real task is never touched)
				if (!standIn) try {
					Process task = new ProcessBuilder("schtasks", "/run", "/tn", "Gatehouse Keeper").redirectErrorStream(true).start();
					task.getOutputStream().close(); task.getInputStream().readAllBytes();
					if (task.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && task.exitValue() == 0) return "";
				} catch (IOException noTask) { /* started directly instead */ }
				ProcessBuilder direct = new ProcessBuilder(java(), "-jar", dir.resolve("oj-host.jar").toString(), "--serve").directory(dir.toFile());
				if (standIn) direct.environment().put("OJ_HOST_DIR", dir.toString());
				direct.start();
				return "";
			}
			boolean boxed = Files.exists(Path.of("/.flatpak-info"));
			List<String> cmd = boxed ? List.of("flatpak-spawn", "--host", "systemctl", "--user", "start", "gatehouse-keeper") : List.of("systemctl", "--user", "start", "gatehouse-keeper");
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			p.getOutputStream().close();
			String said = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
			if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return "the system didn't answer"; }
			if (p.exitValue() == 0) return "";
			return boxed && (said.contains("org.freedesktop.Flatpak") || said.contains("ServiceUnknown") || said.contains("AccessDenied") || said.isEmpty())
					? "Prism Launcher's sandbox isn't allowed to start things outside it here. Start Gatekeeper from the applications menu, or sign out and in."
					: said.isEmpty() ? "the system refused" : said.length() > 200 ? said.substring(0, 200) : said;
		} catch (IOException e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); return "interrupted"; }
	}
	public static String run(List<String> cmd) {
		if (System.getenv("OJ_HOME_DRY") != null) { System.out.println("would run: " + String.join(" ", cmd)); return ""; }
		try { new ProcessBuilder(cmd).start(); return ""; } catch (IOException e) { return e.getMessage(); }
	}

	// ------------------------------------------------------------------ the first screen: Play or Host

	/** One half's card: what it is, whether it is set up here, and the button that opens it or sets it up. */
	private static JPanel card(String title, String what, String state, Color colour, String button, Runnable press) {
		JPanel p = new JPanel(new BorderLayout(0, 10));
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(16, 16, 16, 16)));
		JLabel t = new JLabel(title);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
		JLabel w = new JLabel("<html><div style='width:250px'>" + what + "</div></html>");
		JLabel s = new JLabel(state);
		s.setForeground(colour);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		for (Component c : new Component[] {t, javax.swing.Box.createVerticalStrut(6), w, javax.swing.Box.createVerticalStrut(10), s}) { if (c instanceof JLabel l) l.setAlignmentX(Component.LEFT_ALIGNMENT); text.add(c); }
		JButton b = new JButton(button);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
		b.addActionListener(e -> press.run());
		p.add(text, BorderLayout.CENTER); p.add(b, BorderLayout.SOUTH);
		return p;
	}

	/** Builds the first screen's two cards as things stand now. said: where a press says what happened. */
	static JPanel cards(Component owner, JLabel said) {
		Path script = playScript(), keeper = keeperDir();
		boolean up = keeperDoor() > 0, hasHost = keeper != null || up;
		JPanel both = new JPanel(new GridLayout(1, 2, 12, 0));
		java.util.function.BiConsumer<Boolean, String> setUpIt = (host, name) -> {
			if (JOptionPane.showConfirmDialog(owner, "<html><div style='width:400px'>Set up " + name + " on this device? Its setup opens in a window of its own, says what it will do, and asks before it fetches anything.</div></html>", "Set up " + name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			String trouble;
			try { trouble = run(setUp(host)); } catch (IOException e) { trouble = e.getMessage(); }
			said.setForeground(trouble.isEmpty() ? GREY : AMBER);
			said.setText("<html><div style='width:600px'>" + (trouble.isEmpty() ? name + "'s setup is open in its own window. When it has finished, this screen shows it as set up." : "Couldn't start the setup: " + trouble) + "</div></html>");
		};
		both.add(card("Play", "<b>Gatehouse.</b> Pick a server, keep its mods up to date, and start the game. For anyone who plays.",
				script != null ? "Set up on this device" : "Not set up on this device yet", script != null ? GREEN : GREY, script != null ? "Open Gatehouse" : "Set up Gatehouse\u2026",
				() -> { if (script == null) { setUpIt.accept(false, "Gatehouse"); return; } String t = run(openPlay(script)); said.setForeground(t.isEmpty() ? GREY : AMBER); said.setText(t.isEmpty() ? "Opening Gatehouse\u2026" : "Couldn't open Gatehouse: " + t); }));
		both.add(card("Host", "<b>Gatekeeper.</b> Looks after the Minecraft servers on this machine: who may join and control them, starting and stopping, backups. For whoever runs the servers.",
				!hasHost ? "Not set up on this device yet" : up ? "Set up here, and running" : "Set up here, but its background part isn't running", !hasHost ? GREY : up ? GREEN : AMBER, hasHost ? "Open Gatekeeper" : "Set up Gatekeeper\u2026",
				() -> { if (!hasHost) { setUpIt.accept(true, "Gatekeeper"); return; } String t = openGatekeeper(); said.setForeground(t.isEmpty() ? GREY : AMBER); said.setText("<html><div style='width:600px'>" + (t.isEmpty() ? "Opening Gatekeeper\u2026" : "Couldn't open Gatekeeper: " + t) + "</div></html>"); }));
		return both;
	}

	/** Shows the first screen: in a window of its own (owner null: the program was opened for it), or over a half's window. */
	public static void choose(Window owner) {
		if (GraphicsEnvironment.isHeadless()) {
			Path s = playScript(), k = keeperDir();
			System.out.println("Play (Gatehouse): " + (s == null ? "not set up" : "set up, " + s));
			int door = keeperDoor();
			System.out.println("Host (Gatekeeper): " + (k == null && door <= 0 ? "not set up" : (k == null ? "answering on this machine (its folder isn't visible from here)" : "set up in " + k) + (door > 0 ? ", running" : ", not running")));
			return;
		}
		Runnable ui = () -> {
			if (owner == null) dev.gatehouse.look.SteamLook.apply();
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
			JLabel head = new JLabel("Gatehouse");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 22f));
			JLabel sub = new JLabel("One program, two halves. Open the one you need on this device; a half that isn't set up here yet can be set up from its button.");
			sub.setForeground(GREY);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(head, BorderLayout.NORTH); top.add(sub, BorderLayout.SOUTH);
			JLabel said = new JLabel(" ");
			said.setForeground(GREY);
			JPanel middle = new JPanel(new BorderLayout());
			middle.add(cards(p, said), BorderLayout.CENTER);
			p.add(top, BorderLayout.NORTH); p.add(middle, BorderLayout.CENTER); p.add(said, BorderLayout.SOUTH);
			String shot = System.getenv("OJ_CHOOSE_PNG");
			if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the screen instead of showing it)
				JFrame t = new JFrame(); t.setContentPane(p); t.pack();
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
				try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot)); } catch (IOException e) { e.printStackTrace(); }
				t.dispose();
				if (owner == null) System.exit(0);
				return;
			}
			// (looked at again every few seconds: a half being set up in its own window turns up here when it is done)
			Timer again = new Timer(4000, e -> { middle.removeAll(); middle.add(cards(p, said), BorderLayout.CENTER); middle.revalidate(); middle.repaint(); });
			if (owner == null) {
				JFrame f = new JFrame("Gatehouse");
				f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
				AppIcon.apply(f, false, 0, false);
				f.setContentPane(p); f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);
				again.start();
			} else {
				JDialog d = new JDialog(owner, "Gatehouse: Play or Host", java.awt.Dialog.ModalityType.MODELESS);
				d.setContentPane(p); d.pack(); d.setLocationRelativeTo(owner);
				d.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { again.stop(); } });
				d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
				d.setVisible(true);
				again.start();
			}
		};
		if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
	}
}
