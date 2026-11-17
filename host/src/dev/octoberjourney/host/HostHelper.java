package dev.octoberjourney.host;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

/**
 * Gatekeeper, on the PC that runs the server. Two modes:
 *
 *   --serve  (started at sign-in by a scheduled task) Listens on the Tailscale address only, for the launch pop-up's
 *            "Start MCSS" and "Close MCSS". Launchers introduce themselves (a device id they made, a secret only they
 *            know, a name); each new one waits until the owner allows it in the panel. Requests are matched to the
 *            secret and, for identification, to the Tailscale device they came from (tailscale whois). Also listens on
 *            127.0.0.1 for the panel.
 *   --panel  The owner's window: the launchers that have asked, with Allow MCSS control / Revoke / Forget, and a log.
 *
 * Its folder (the one its jar is in, C:\Minecraft\GatehouseKeeper once installed; or OJ_HOST_DIR) holds host.json (settings), devices.json (the
 * launchers, with a hash of each secret, never the secret) and host.log. Closing MCSS is refused while the Minecraft
 * server is running, so the world is never cut off mid-save: stop the server first.
 */
public final class HostHelper {
	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	static Path dir;
	static Map<String, Object> cfg;
	/** The program that hosts the Minecraft server on this machine. Everything Gatekeeper does with it goes through
	 *  this one object (see ServerHost); MCSS is the only kind there is so far. */
	static ServerHost host = new McssHost();

	public static void main(String[] args) throws Exception {
		// Its folder: where this program's own file is (tools/install_host.ps1 puts it in its folder), or OJ_HOST_DIR.
		String own;
		try { own = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().toString(); }
		catch (Exception e) { own = "."; }
		dir = Path.of(System.getenv().getOrDefault("OJ_HOST_DIR", own));
		Files.createDirectories(dir);
		cfg = Json.map(Json.parse(read(dir.resolve("host.json"))));
		host = builtin() ? DIRECT : new McssHost();
		String mode = args.length > 0 ? args[0] : "--panel";
		startMinimized = java.util.Arrays.asList(args).contains("--minimized");
		if (mode.equals("--uninstall-command")) {   // (testing: what Options > Uninstall would run; nothing is run)
			System.out.println(String.join(" ", uninstallCommand(Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(), java.util.Arrays.asList(args).contains("--purge"))));
			return;
		}
		if (mode.equals("--serve")) serve();
		else panel();
	}

	static String setting(String key, String def) { Object v = cfg.get(key); return v == null || v.toString().isEmpty() ? def : v.toString(); }
	static int port() { return Integer.parseInt(setting("port", "25570")); }

	// ------------------------------------------------------------------ which system this machine runs
	/** "windows", "linux" or "steamos" (OJ_OS says otherwise, for testing). Gatekeeper does the same things on each;
	 *  what a system can't do is still listed where it would be, as "Not supported on <system>". */
	static final String SYSTEM = whichSystem();
	static final boolean WIN = SYSTEM.equals("windows"), STEAMOS = SYSTEM.equals("steamos");
	static String whichSystem() {
		String forced = System.getenv("OJ_OS");
		if (forced != null && !forced.isBlank()) return forced.trim().toLowerCase(java.util.Locale.ROOT);
		if (System.getProperty("os.name", "").startsWith("Windows")) return "windows";
		for (String line : read(Path.of("/etc/os-release")).split("\\R")) if (line.replace("\"", "").trim().equals("ID=steamos")) return "steamos";
		return "linux";
	}
	static String systemName() { return WIN ? "Windows" : STEAMOS ? "SteamOS" : "Linux"; }
	static String notSupported() { return "Not supported on " + systemName(); }
	/** One of two wordings: Windows', or Linux's and SteamOS's. */
	static String say(String windows, String linux) { return WIN ? windows : linux; }
	/** Does Gatekeeper run the Minecraft servers itself (DirectHost), with no MC Server Soft? Always on Linux and
	 *  SteamOS, where there is no MCSS; on Windows when host.json "host" is "builtin". */
	static boolean builtin() { return !WIN || setting("host", "mcss").equals("builtin"); }
	/** On Windows with MCSS: may Gatekeeper also run servers of its own beside MCSS's (host.json "native_servers")? */
	static boolean nativeToo() { return !builtin() && setting("native_servers", "false").equals("true"); }
	/** May Gatekeeper make and run servers itself on this machine at all? */
	static boolean nativeOk() { return builtin() || nativeToo(); }
	/** Does Gatekeeper run the current server itself (rather than MCSS)? */
	static boolean isNative() { return builtin() || (nativeToo() && ServerTools.yes(server().get("native"))); }
	/** What runs the servers on this machine, as launchers are told: "mcss", "builtin" (Gatekeeper itself), or "both". */
	static String hostKind() { return builtin() ? "builtin" : nativeToo() ? "both" : "mcss"; }
	/** Has MC Server Soft deleted this server? True for a server MCSS runs whose folder is inside MCSS's own servers
	 *  folder, when that folder is still there but the server's is gone, or no longer holds MCSS's file for it (or
	 *  holds another server's: deleted, and a new one made under the same name). */
	static boolean goneFromMcss(Map<String, Object> s) {
		if (builtin() || (nativeToo() && ServerTools.yes(s.get("native")))) return false;
		String exe = setting("mcss_exe", ""), dirText = Json.str(s.get("dir"));
		if (exe.isEmpty() || dirText.isEmpty()) return false;
		try {
			Path all = Path.of(exe).toAbsolutePath().getParent().resolve("servers").normalize(), d = Path.of(dirText).toAbsolutePath().normalize();
			if (!Files.isDirectory(all) || !d.startsWith(all)) return false;
			Map<String, Object> c = Json.map(Json.parse(read(d.resolve("mcss_server_config.json"))));
			String want = Json.str(s.get("mcss_server"));
			return c.isEmpty() || (!want.isEmpty() && !Json.str(c.get("guid")).isEmpty() && !want.equals(Json.str(c.get("guid"))));
		} catch (RuntimeException e) { return false; }
	}
	static final java.util.Set<String> goneSaid = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Notes in the log, once each, the servers MCSS has deleted (and forgets the note when one is back or removed). */
	static void noteGone() {
		java.util.Set<String> now = new java.util.HashSet<>();
		for (Object o : new ArrayList<>(servers())) {
			Map<String, Object> s = Json.map(o);
			if (!goneFromMcss(s)) continue;
			now.add(Json.str(s.get("id")));
			if (goneSaid.add(Json.str(s.get("id")))) log(Json.str(s.get("name")) + " was deleted in MC Server Soft (its folder " + Json.str(s.get("dir")) + " is gone, or is no longer that server's). Launchers are no longer shown it; Remove it from Gatekeeper on the first screen.");
		}
		goneSaid.retainAll(now);
	}
	static final DirectHost DIRECT = new DirectHost();
	/** Whatever runs the current server: Gatekeeper itself, or the host program. */
	static ServerHost sh() { return isNative() ? DIRECT : host; }
	/** The folder Gatekeeper's own servers are in, one folder each (host.json "servers_dir"). */
	static String serversDir() { return setting("servers_dir", WIN ? "C:\\Minecraft\\servers" : System.getProperty("user.home", "") + "/minecraft-servers"); }
	/** What is said where MCSS would be, on a Gatekeeper that runs the servers itself. */
	static String noMcss() { return WIN ? "Not used here: this Gatekeeper runs the servers itself, without MC Server Soft" : notSupported() + ": MC Server Soft is Windows only, so this Gatekeeper runs the servers itself"; }
	static String noMcssShort() { return WIN ? "not used here" : notSupported().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1); }
	/** What MCSS's program file is called here. */
	static String mcssFile() { return WIN ? "mcss.exe" : "mcss"; }
	/** Is that program somewhere on the PATH? */
	static boolean onPath(String program) {
		for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
			if (!d.isEmpty() && Files.isRegularFile(Path.of(d, program))) return true;
		return false;
	}
	/** Tailscale's own command: host.json "tailscale_exe", else where it usually is on this system. */
	static String tailscaleExe() {
		String set = setting("tailscale_exe", "");
		if (!set.isEmpty()) return set;
		if (WIN) return "C:\\Program Files\\Tailscale\\tailscale.exe";
		for (String g : new String[] {"/usr/bin/tailscale", "/usr/local/bin/tailscale", "/usr/sbin/tailscale", "/opt/tailscale/tailscale", System.getProperty("user.home", "") + "/.local/bin/tailscale", "/snap/bin/tailscale"})
			if (Files.isRegularFile(Path.of(g))) return g;
		return "/usr/bin/tailscale";
	}
	/** Can a launcher put this machine to sleep? Not a Steam Deck (asleep, nothing can wake it from another device),
	 *  and not a Linux without systemd. host.json "sleep_command" (testing) counts as yes. */
	static boolean sleepSupported() { return !setting("sleep_command", "").isEmpty() || WIN || (!STEAMOS && onPath("systemctl")); }
	/** What a command says (null when it can't be run or doesn't answer in time). */
	static String commandSays(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			p.getOutputStream().close();
			byte[][] out = {new byte[0]};
			Thread rd = new Thread(() -> { try { out[0] = p.getInputStream().readAllBytes(); } catch (IOException ignored) {} }, "command");
			rd.start();
			if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
			rd.join(2000);
			return new String(out[0], StandardCharsets.UTF_8);
		} catch (Exception e) { return null; }
	}

	// ------------------------------------------------------------------ devices

	/** devices.json: { "<device id>": {name, os, tailscale, ip, token_sha256, granted, first_seen, last_seen} }. */
	static synchronized Map<String, Object> devices() { return Json.map(Json.parse(read(dir.resolve("devices.json")))); }

	static synchronized void saveDevices(Map<String, Object> d) throws IOException {
		Path tmp = dir.resolve("devices.json.tmp");
		Files.writeString(tmp, Json.write(d), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve("devices.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static synchronized void log(String s) {
		String tag = "";
		try { if (cfg != null && servers().size() > 1 && !SERVER.get().isEmpty()) tag = "[" + serverName() + "] "; } catch (RuntimeException ignored) {}
		try { Files.writeString(dir.resolve("host.log"), LocalDateTime.now().format(TIME) + "  " + tag + s + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
		catch (IOException ignored) {}
	}

	/** Notes that came inside this program: "gatekeeper" (its own) or "gatehouse" (the launcher's, as of this build). */
	static Map<String, Object> notesInside(String which) {
		try (InputStream in = HostHelper.class.getResourceAsStream("/" + which + "-notes.json")) { return in == null ? new LinkedHashMap<>() : Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); }
		catch (IOException | RuntimeException e) { return new LinkedHashMap<>(); }
	}
	private static Map<String, Object> gatehouseNotes;
	private static long gatehouseNotesAt;
	/** Gatehouse's notes, for the tab here: the newest from the repository (host.json "gatehouse_notes_url"; looked up
	 *  again after ten minutes), else the copy that came with this Gatekeeper. */
	static synchronized Map<String, Object> gatehouseNotes() {
		if (gatehouseNotes != null && System.currentTimeMillis() - gatehouseNotesAt < 600_000) return gatehouseNotes;
		Map<String, Object> got = new LinkedHashMap<>();
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(setting("gatehouse_notes_url", "https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/source/gatehouse.json")).openConnection();
			c.setConnectTimeout(4000); c.setReadTimeout(6000);
			if (c.getResponseCode() == 200) try (InputStream in = c.getInputStream()) { got = Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); }
		} catch (IOException | RuntimeException ignored) {}
		if (Json.list(got.get("changes")).isEmpty()) { got = notesInside("gatehouse"); got.put("_from", "the copy that came with this Gatekeeper"); } else got.put("_from", "the repository");
		gatehouseNotes = got; gatehouseNotesAt = System.currentTimeMillis();
		return got;
	}

	/** The end of the log for the server being looked at: its own lines (they carry its name) and the lines about the
	 *  machine as a whole (no name); another server's lines are left out. */
	static List<Object> logFor(int max) {
		String[] lines = read(dir.resolve("host.log")).split("\\R");
		String mine = "[" + serverName() + "] ";
		java.util.regex.Pattern tagged = java.util.regex.Pattern.compile("^\\S+ \\S+  (\\[[^\\]]+\\] )");
		List<Object> out = new ArrayList<>();
		for (int i = lines.length - 1; i >= 0 && out.size() < max; i--) {
			if (lines[i].isBlank()) continue;
			java.util.regex.Matcher m = tagged.matcher(lines[i]);
			if (m.find() && !m.group(1).equals(mine)) continue;
			out.add(0, lines[i]);
		}
		return out;
	}
	/** The updates launchers pulled, newest first: for the server being looked at when a server was asked for. */
	static List<Object> pullsFor() {
		List<Object> all = Json.list(Json.parse(read(dir.resolve("pulls.json")))), out = new ArrayList<>();
		String want = SERVER.get().isEmpty() ? "" : Json.str(server().get("id"));
		boolean first = firstServer();
		for (Object o : all) { String sv = Json.str(Json.map(o).get("server")); if (want.isEmpty() || sv.equals(want) || (sv.isEmpty() && first)) out.add(0, o); }
		return out;
	}

	/** A launcher as the log names it: its own name, and the Tailscale device it came from when known. */
	static String who(Map<String, Object> d) {
		String ts = Json.str(d.get("tailscale"));
		return Json.str(d.get("name")) + (ts.isEmpty() ? "" : " / " + ts);
	}

	static String sha256(String s) {
		try {
			StringBuilder b = new StringBuilder();
			for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
			return b.toString();
		} catch (Exception e) { throw new IllegalStateException(e); }
	}

	/** What Tailscale's own command says (null when it isn't installed where expected, or doesn't answer in time). */
	static String tailscaleSays(String... args) {
		try {
			String exe = tailscaleExe();
			if (!Files.isRegularFile(Path.of(exe))) return null;
			List<String> cmd = new ArrayList<>(List.of(exe)); cmd.addAll(List.of(args));
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			byte[][] out = {new byte[0]};
			Thread rd = new Thread(() -> { try { out[0] = p.getInputStream().readAllBytes(); } catch (IOException ignored) {} }, "tailscale");
			rd.start();
			if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
			rd.join(2000);
			return new String(out[0], StandardCharsets.UTF_8);
		} catch (Exception e) { return ""; }
	}

	/** The port in a server folder's server.properties as it is right now ("" when it can't be read). */
	static String portNow(String serverDir) {
		String port = "";
		try { for (String l : read(Path.of(serverDir).resolve("server.properties")).split("\\R")) if (l.startsWith("server-port=") && l.substring(12).trim().matches("\\d+")) port = l.substring(12).trim(); }
		catch (RuntimeException ignored) {}
		return port;
	}

	/** Ports a Minecraft server could be given on this machine: the next ones up from Minecraft's usual 25565 that no
	 *  server MCSS has is set to (chosen in Gatekeeper or not), that Gatekeeper doesn't use itself, and that nothing
	 *  on this machine is listening on right now. */
	static List<String> freePorts(int howMany) {
		java.util.Set<String> taken = new java.util.HashSet<>();
		taken.add(String.valueOf(port()));
		for (Object o : servers()) { taken.add(portNow(Json.str(Json.map(o).get("dir")))); taken.add(Json.str(Json.map(o).get("minecraft")).replaceAll(".*:", "")); }
		try {
			String exe = setting("mcss_exe", "");
			if (!exe.isEmpty()) try (java.util.stream.Stream<Path> s = Files.list(Path.of(exe).toAbsolutePath().getParent().resolve("servers"))) { s.filter(Files::isDirectory).forEach(d -> taken.add(portNow(d.toString()))); }
		} catch (IOException | RuntimeException ignored) {}
		List<String> out = new ArrayList<>();
		for (int p = 25566; p < 25666 && out.size() < howMany; p++) {
			if (taken.contains(String.valueOf(p))) continue;
			try (java.net.ServerSocket probe = new java.net.ServerSocket(p)) { out.add(String.valueOf(p)); } catch (IOException inUse) { /* something is listening there */ }
		}
		return out;
	}

	/** Host setup: everything a machine needs to serve players through Gatekeeper, each step with how it stands now
	 *  ("ok", "todo": not done, "warn": works but worth changing) and, when it isn't ok, what to do. Nothing is changed
	 *  by looking. The invite link players need (host.json "tailscale_share_link") is the owner's to make, in
	 *  Tailscale's admin page; Gatekeeper only carries it in the pairing. */
	static Map<String, Object> checklist() {
		List<Object> steps = new ArrayList<>();
		java.util.function.Consumer<String[]> add = s -> steps.add(m("title", s[0], "state", s[1], "detail", s[2], "how", s.length > 3 ? s[3] : "",
				"action", s.length > 4 ? s[4] : "", "action_label", s.length > 5 ? s[5] : "", "action_ask", s.length > 6 ? s[6] : ""));
		// ---- Gatekeeper itself
		if (outdated()) add.accept(new String[] {"Gatekeeper is up to date", "todo", "This is " + ownVersion() + "; " + newestKeeper + " is out. Until it's updated Gatekeeper starts no server, and launchers aren't offered Play.",
				"Servers tab: Update Gatekeeper. It restarts by itself in a few seconds; running Minecraft servers aren't touched."});
		else add.accept(new String[] {"Gatekeeper is up to date", "ok", ownVersion() + (newestKeeper.isEmpty() ? " (nowhere to compare with could be reached)" : "")});
		// ---- Tailscale on this machine
		String raw = tailscaleSays("status", "--json");
		Map<String, Object> ts = raw == null || raw.isEmpty() ? new LinkedHashMap<>() : Json.map(Json.parse(raw.substring(Math.max(0, raw.indexOf('{')))));
		Map<String, Object> self = Json.map(ts.get("Self"));
		List<String> ips = new ArrayList<>();
		for (Object o : Json.list(self.get("TailscaleIPs"))) ips.add(Json.str(o));
		String state = Json.str(ts.get("BackendState")), tsName = Json.str(self.get("DNSName")).replaceAll("\\.$", "");
		if (raw == null) add.accept(new String[] {"Tailscale is installed", "todo", "Not found on this machine.", say("Install Tailscale from tailscale.com/download and sign in with your account. (Installed somewhere unusual? Put the path of tailscale.exe in host.json as \"tailscale_exe\".)",
				"Install Tailscale from tailscale.com/download/linux, then in a terminal: sudo tailscale up, and sign in on the page it gives." + (STEAMOS ? " Tailscale doesn't officially support SteamOS, and a SteamOS update can remove it." : "") + " (Installed somewhere unusual? Put the path of the tailscale program in host.json as \"tailscale_exe\".)")});
		else add.accept(new String[] {"Tailscale is installed", "ok", ""});
		if (raw != null) {
			if (state.equals("Running") && !ips.isEmpty()) add.accept(new String[] {"Tailscale is connected", "ok", ips.get(0) + (tsName.isEmpty() ? "" : "  (" + tsName + ")")});
			else add.accept(new String[] {"Tailscale is connected", "todo", state.isEmpty() ? "Tailscale didn't answer." : "Tailscale says: " + state + ".", say("Open Tailscale (its icon by the clock) and sign in or connect.", "In a terminal: sudo tailscale up, and sign in on the page it gives.")});
			if (state.equals("Running")) {
				String exp = Json.str(self.get("KeyExpiry"));
				if (exp.isEmpty()) add.accept(new String[] {"This machine stays signed in to Tailscale", "ok", "Key expiry is off for it."});
				else add.accept(new String[] {"This machine stays signed in to Tailscale", "warn", "Its sign-in runs out on " + exp.replaceAll("T.*$", "") + "; players lose the server then, until you sign in again.",
						"In Tailscale's admin page (login.tailscale.com): Machines, this machine's \u2026 menu, Disable key expiry."});
				String prefs = WIN ? tailscaleSays("debug", "prefs") : null;
				if (!WIN) {
					// (on Linux Tailscale is a system service: it runs before anyone signs in when that service is enabled)
					String early = "Tailscale runs before anyone signs in", service = onPath("systemctl") ? commandSays("systemctl", "is-enabled", "tailscaled") : null;
					if (service == null) add.accept(new String[] {early, "na", "Gatekeeper can't see here whether Tailscale starts with the machine."});
					else if (service.trim().startsWith("enabled")) add.accept(new String[] {early, "ok", "Its service starts with the machine."});
					else add.accept(new String[] {early, "warn", "Its service isn't set to start with the machine" + (service.isBlank() ? "" : " (" + service.trim().split("\\R")[0] + ")") + ": after a restart, players can't reach this machine until Tailscale is started.",
							"In a terminal: sudo systemctl enable --now tailscaled"});
				}
				if (prefs != null && prefs.contains("\"ForceDaemon\"")) {
					if (prefs.replaceAll("\\s", "").contains("\"ForceDaemon\":true")) add.accept(new String[] {"Tailscale runs before anyone signs in to Windows", "ok", "Run unattended is on."});
					else add.accept(new String[] {"Tailscale runs before anyone signs in to Windows", "warn", "Off: after a restart or a wake, players can't reach this machine until you sign in to Windows.",
							"Tailscale's icon by the clock: Preferences, Run unattended."});
				}
			}
		}
		// ---- what this system can't do, said where it would otherwise be
		if (!sleepSupported()) add.accept(new String[] {"Launchers can put this machine to sleep", "na", STEAMOS ? "A Steam Deck that is asleep can't be woken from another device, so Gatekeeper doesn't put it to sleep. Keep it plugged in and awake while it serves."
				: "Gatekeeper sleeps a Linux machine through systemd (systemctl suspend), which isn't on this one."});
		// ---- Gatekeeper's own address
		String bind = setting("bind", "");
		if (bind.isEmpty()) add.accept(new String[] {"Gatekeeper listens on the Tailscale address", "todo", "No address chosen yet.", "Servers tab: Server setup. It fills the address in when Tailscale is connected."});
		else if (!ips.isEmpty() && !ips.contains(bind)) add.accept(new String[] {"Gatekeeper listens on the Tailscale address", "warn", "It listens on " + bind + ", but Tailscale now gives this machine " + ips.get(0) + ".", "Servers tab: Server setup, and save the new address. Paired launchers need the new pairing."});
		else add.accept(new String[] {"Gatekeeper listens on the Tailscale address", remote != null ? "ok" : "todo", bind + ":" + port() + (remote != null ? "" : " (not answering yet)"), remote != null ? "" : "It keeps trying; Tailscale may still be starting."});
		// ---- the servers' host: MCSS, or Gatekeeper itself
		String exe = setting("mcss_exe", "");
		if (builtin()) {
			if (!WIN) add.accept(new String[] {"MC Server Soft", "na", "MCSS is Windows only, so Gatekeeper runs the Minecraft servers itself here."});
			Path all = Path.of(serversDir());
			if (Files.isDirectory(all)) add.accept(new String[] {"The servers folder is found", "ok", all.toString()});
			else add.accept(new String[] {"The servers folder is found", "todo", "There's no folder at " + all + ".", "New server (first screen) makes that folder and a server in it. Servers you already have: put each in a folder of its own inside it, or point Server setup at where they are."});
			List<String> cant = new ArrayList<>();
			for (Object o : servers()) { String why = DirectHost.blocker(Json.map(o)); if (!why.isEmpty()) cant.add(Json.str(Json.map(o).get("name")) + ": " + why); }
			if (!servers().isEmpty()) {
				if (cant.isEmpty()) add.accept(new String[] {"Each server can be started", "ok", "Each has its program, and Minecraft's EULA is accepted for it."});
				else add.accept(new String[] {"Each server can be started", "todo", String.join(". ", cant) + ".", "Gatekeeper downloads nothing and accepts nothing for you: put the server's program in its folder, and accept the EULA yourself in its eula.txt. This line turns green by itself."});
			}
		} else {
		if (exe.isEmpty() || !Files.isRegularFile(Path.of(exe))) add.accept(new String[] {"MCSS is found", "todo", exe.isEmpty() ? "Gatekeeper doesn't know where MCSS is." : "There's no file at " + exe + ".", "Install MC Server Soft (mcserversoft.com) if it isn't yet, make a server in it, then: Servers tab, Server setup."});
		else add.accept(new String[] {"MCSS is found", "ok", exe});
		if (host.hasAccess()) add.accept(new String[] {"Gatekeeper can use MCSS", "ok", "It has its own key for MCSS."});
		else add.accept(new String[] {"Gatekeeper can use MCSS", "todo", "No key yet, so it can't start or stop servers or read their state.", "Open a server here, then Server Status: sign in to MCSS once. Gatekeeper makes its own key; your password isn't kept. (MCSS's web API has to be on: MCSS, Options, Web API.)"});
		}
		// ---- the servers
		List<Object> servers = servers();
		if (servers.isEmpty()) add.accept(new String[] {"Servers are chosen", "todo", "None yet.", "Servers tab: Server setup, and tick the servers players should see."});
		else {
			List<String> names = new ArrayList<>(), open = new ArrayList<>(), offline = new ArrayList<>(), moved = new ArrayList<>(), openLists = new ArrayList<>();
			Map<String, List<String>> byPort = new LinkedHashMap<>();
			for (Object o : servers) {
				Map<String, Object> s = Json.map(o);
				String name = Json.str(s.get("name"));
				names.add(name);
				// (the port as the server's own file has it now, so a change shows here at once; Gatekeeper's record of it is
				// from the last Server setup)
				String recorded = Json.str(s.get("minecraft")).replaceAll(".*:", ""), now = portNow(Json.str(s.get("dir")));
				if (now.isEmpty()) now = recorded;
				if (!now.equals(recorded)) moved.add(name + " (now " + now + ", Gatekeeper still has " + recorded + ")");
				byPort.computeIfAbsent(now, k -> new ArrayList<>()).add(name);
				String props = read(Path.of(Json.str(s.get("dir"))).resolve("server.properties"));
				if (!props.isEmpty()) {
					if (!props.replace(" ", "").contains("white-list=true")) {
						open.add(name);
						int listed = Json.list(Json.parse(read(Path.of(Json.str(s.get("dir"))).resolve("whitelist.json")))).size();
						openLists.add(name + " has " + (listed == 0 ? "nobody" : listed + (listed == 1 ? " player" : " players")) + " on its whitelist");
					}
					if (props.replace(" ", "").contains("online-mode=false")) offline.add(name);
				}
			}
			add.accept(new String[] {"Servers are chosen", "ok", String.join(", ", names)});
			List<String> deleted = new ArrayList<>();
			for (Object o : servers()) if (goneFromMcss(Json.map(o))) deleted.add(Json.str(Json.map(o).get("name")));
			if (!deleted.isEmpty()) add.accept(new String[] {"Every chosen server still exists", "warn", "Deleted in MCSS, but still listed here: " + String.join(", ", deleted) + ".", "Servers tab: press Remove from Gatekeeper beside it. Launchers are already no longer shown it."});
			List<String> clash = new ArrayList<>();
			for (Map.Entry<String, List<String>> e : byPort.entrySet()) if (e.getValue().size() > 1) clash.add(String.join(" and ", e.getValue()) + " (port " + e.getKey() + ")");
			if (!clash.isEmpty()) {
				List<String> free = freePorts(3);
				String pick = free.isEmpty() ? "any port from 25566 up that nothing else uses" : free.size() == 1 ? free.get(0) : String.join(", ", free.subList(0, free.size() - 1)) + " or " + free.get(free.size() - 1);
				add.accept(new String[] {"Each server has its own port", "warn", "These share a port, so only one of them can run at a time: " + String.join("; ", clash) + ".",
						"Leave the one players already use where it is, and give the other " + pick + (free.isEmpty() ? "" : " (free on this machine: no MCSS server is set to " + (free.size() == 1 ? "it" : "them") + " and nothing is listening there)")
						+ ". In MCSS: that server's server.properties, server-port, then restart that server. This line turns green by itself when the ports differ."});
			}
			else if (servers.size() > 1) add.accept(new String[] {"Each server has its own port", "ok", ""});
			if (!moved.isEmpty()) add.accept(new String[] {"Gatekeeper has each server's current port", "warn", "A port was changed since Server setup: " + String.join("; ", moved) + ". Launchers are still told the old one.",
					"Servers tab: Server setup, then Save. It reads the ports again."});
			if (!open.isEmpty()) add.accept(new String[] {"Only whitelisted players can join", "warn", "The whitelist is off for " + String.join(", ", open) + ": anyone who can reach this machine can join.", "The button turns it on: a running server is told in its console, one that's off gets it in its settings file. Then only players on the whitelist can join; you add them in that server's Access Manager.",
					"whitelist_on", "Turn the whitelist on", "Turn the whitelist on for " + String.join(" and ", open) + "? Right now " + String.join("; ", openLists) + ". After this, only players on a server's whitelist can join it (you add them in its Access Manager)."});
			else add.accept(new String[] {"Only whitelisted players can join", "ok", ""});
			if (!offline.isEmpty()) add.accept(new String[] {"Players are checked against their Minecraft accounts", "warn", "online-mode is off for " + String.join(", ", offline) + ": anyone can join under any name.", "Set online-mode=true in that server's server.properties unless you mean it to be off."});
		}
		// ---- seen from outside
		int reached = 0; String last = "";
		for (Object o : devices().values()) { String ls = Json.str(Json.map(o).get("last_seen")); if (!ls.isEmpty()) { reached++; if (ls.compareTo(last) > 0) last = ls; } }
		if (reached > 0) add.accept(new String[] {"A launcher has reached this Gatekeeper", "ok", reached + " so far; the latest on " + last + "."});
		else add.accept(new String[] {"A launcher has reached this Gatekeeper", "todo", "None yet. Everything above can look right from here and still be blocked from outside.",
				"Pair a launcher on another device (Servers tab: Share pairing) and open it. If it can't connect: " + say("Windows Firewall has to allow Java for Gatekeeper on private networks (Windows asks the first time; otherwise Windows Security, Firewall, Allow an app).", "a firewall on this machine, if it has one, has to let Gatekeeper's port in on the Tailscale network (most desktop Linux systems, and SteamOS, have none switched on).")});
		// ---- the invite players need
		String share = setting("tailscale_share_link", "");
		if (!share.isEmpty()) add.accept(new String[] {"Players have a way into Tailscale", "ok", "Your invite link goes out with the pairing link and file."});
		else add.accept(new String[] {"Players have a way into Tailscale", "todo", "No invite link yet: a player's device has to be able to reach this machine over Tailscale.",
				"In Tailscale's admin page (login.tailscale.com): Machines, this machine's \u2026 menu, Share, and make a reusable invite link. That shares only this machine, not the rest of your network. Paste the link below."});
		String machine = "";
		try { machine = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ignored) {}
		return m("steps", steps, "share_link", share, "name", setting("name", ""), "machine", machine, "java", System.getProperty("java.version", ""), "checked", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
	}

	/** The Tailscale device a request came from, as Tailscale names it (empty if unknown). */
	static String tailscaleName(String ip) {
		try {
			Process p = new ProcessBuilder(tailscaleExe(), "whois", "--json", ip).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(5, TimeUnit.SECONDS);
			Map<String, Object> node = Json.map(Json.map(Json.parse(out)).get("Node"));
			String name = Json.str(node.get("ComputedName"));
			if (name.isEmpty()) name = Json.str(node.get("Name")).replaceAll("\\..*$", "");
			Map<String, Object> hi = Json.map(node.get("Hostinfo"));
			String os = Json.str(hi.get("OS"));
			return name.isEmpty() ? "" : name + (os.isEmpty() ? "" : " (" + os + ")");
		} catch (Exception e) { return ""; }
	}

	// ------------------------------------------------------------------ MCSS

	/** Is the server being looked at up, coming up or going down? (Sleeping the machine and closing MCSS wait for it.) */
	static boolean minecraftRunning() {
		String p = serverPhase();
		return p.equals("online") || p.equals("starting") || p.equals("stopping");
	}

	/** Something Gatekeeper set going and is waiting to see finish: "starting" or "stopping" for a server (under its
	 *  key + "|server"), "starting" or "closing" for MCSS (under "mcss"), each good until a time. */
	static final Map<String, String> awaited = new java.util.concurrent.ConcurrentHashMap<>();
	static final Map<String, Long> awaitedUntil = new java.util.concurrent.ConcurrentHashMap<>();
	static void expect(String key, String what, long ms) { awaited.put(key, what); awaitedUntil.put(key, System.currentTimeMillis() + ms); phaseSeen.clear(); }
	static String expected(String key) {
		Long u = awaitedUntil.get(key);
		if (u == null) return "";
		if (System.currentTimeMillis() > u) { awaited.remove(key); awaitedUntil.remove(key); return ""; }
		return awaited.getOrDefault(key, "");
	}
	static void met(String key) { awaited.remove(key); awaitedUntil.remove(key); }
	static final Map<String, Object[]> phaseSeen = new java.util.concurrent.ConcurrentHashMap<>();   // (each server's last reading, kept a second or two)

	/** What the server being looked at is doing: "online", "starting", "stopping", "off" (MCSS is running, it isn't) or
	 *  "nomcss" (MCSS isn't running). MCSS says, for that one server (two servers can share a port, so a port that
	 *  answers proves nothing about which one it is). Where MCSS can't be asked (Gatekeeper not signed in), the server's
	 *  own world lock says whether it's running. Between Gatekeeper asking for a start or stop and MCSS showing it, it
	 *  reads as starting or stopping. */
	static String serverPhase() {
		String key = serverKey();
		Object[] c = phaseSeen.get(key);
		if (c != null && System.currentTimeMillis() - (Long) c[0] < 1500) return (String) c[1];
		String e = expected(key + "|server"), p;
		if (!sh().running()) p = e.equals("starting") ? "starting" : "nomcss";
		else {
			int st = sh().hasAccess() ? sh().serverStatus() : -1;
			if (st == 1) p = e.equals("stopping") ? "stopping" : "online";
			else if (st == 2 || st == 3) p = "starting";
			else if (st == 4) p = "stopping";
			else if (st == 0) p = e.equals("starting") ? "starting" : "off";
			else p = worldInUse() ? (e.equals("stopping") ? "stopping" : "online") : e.equals("starting") ? "starting" : "off";
		}
		if ((p.equals("online") && e.equals("starting")) || ((p.equals("off") || p.equals("nomcss")) && e.equals("stopping"))) met(key + "|server");
		phaseSeen.put(key, new Object[] {System.currentTimeMillis(), p});
		return p;
	}
	/** Is the server's world open right now? A running server holds a lock on session.lock in its world folder. */
	static boolean worldInUse() {
		String level = "world";
		for (String l : read(serverDir().resolve("server.properties")).split("\\R")) if (l.startsWith("level-name=") && !l.substring(11).isBlank()) level = l.substring(11).trim();
		Path lock = serverDir().resolve(level).resolve("session.lock");
		if (!Files.isRegularFile(lock)) return false;
		try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock fl = ch.tryLock()) { return fl == null; }
		catch (IOException | java.nio.channels.OverlappingFileLockException ex) { return true; }
	}
	/** What MCSS itself is doing: "running", "starting" (opened, not answering Gatekeeper yet), "closing" or "off". */
	static String mcssPhase() {
		boolean up = host.running();
		String e = expected("mcss");
		if (e.equals("closing")) { if (!up) { met("mcss"); return "off"; } return "closing"; }
		if (e.equals("starting")) { if (up && (!host.hasAccess() || host.serverStatus() >= 0)) { met("mcss"); return "running"; } return "starting"; }
		return up ? "running" : "off";
	}
	/** The words for a phase, for a status line. */
	static String phaseWords(String p) {
		switch (p) {
			case "online": return "running";
			case "starting": return "starting\u2026";
			case "stopping": return "shutting down\u2026";
			case "nomcss": return "off (MCSS isn't running)";
			default: return "off";
		}
	}

	/** Closes MCSS, whatever it takes: with a Minecraft server running it stops each one first (the world is saved),
	 *  waits in the background until they're down (up to 3 minutes), then closes MCSS. Returns at once with what is
	 *  happening; how it ended goes in the log. */
	static String[] closeMcssFully(boolean force, String by) {
		if (builtin()) return new String[] {"refused", noMcss()};
		if (!host.running()) return new String[] {"ok", "MCSS isn't running"};
		List<String> up = new ArrayList<>();
		String keep = SERVER.get();
		try {
			List<Object> l = servers();
			for (int i = 0; i < Math.max(1, l.size()); i++) {
				String id = l.isEmpty() ? "" : Json.str(Json.map(l.get(i)).get("id"));
				SERVER.set(id);
				if (!isNative() && minecraftRunning()) up.add(id);   // (the servers MCSS runs; Gatekeeper's own carry on)
			}
			if (up.isEmpty()) { expect("mcss", "closing", 60_000); String[] r = host.close(force); if (!r[0].equals("ok")) met("mcss"); return r; }
			if (host.closeMode().equals("force") && !force) return host.close(false);   // (it says why it needs the force close)
			if (!host.hasAccess()) return new String[] {"refused", "A Minecraft server is running and Gatekeeper isn't signed in to MCSS, so it can't stop it safely first: stop the server in MCSS, then close it"};
			for (String id : up) { SERVER.set(id); sh().serverAction(false); expect(serverKey() + "|server", "stopping", 200_000); }
		} finally { SERVER.set(keep); }
		expect("mcss", "closing", 260_000);
		Thread t = new Thread(() -> {
			try {
				boolean down = false;
				for (int i = 0; i < 180 && !down; i++) {
					Thread.sleep(1000);
					down = true;
					for (String id : up) { SERVER.set(id); String p = serverPhase(); if (p.equals("online") || p.equals("starting") || p.equals("stopping")) down = false; }
				}
				if (!down) { met("mcss"); log("close MCSS (asked by " + by + "): the Minecraft server didn't stop within 3 minutes, so MCSS was left open"); return; }
				Thread.sleep(1500);
				String[] r = host.close(force);
				if (!r[0].equals("ok")) met("mcss");
				log("close MCSS (asked by " + by + "), after stopping the Minecraft server: " + r[1]);
			} catch (InterruptedException ignored) {}
		}, "close-mcss");
		t.setDaemon(true);
		t.start();
		return new String[] {"ok", "The Minecraft server is running: it's being stopped first (the world is saved), then MCSS closes. A minute or so in all."};
	}

	// ------------------------------------------------------------------ keeping up to date
	//
	// Each build of the pack publishes the Gatekeeper it was built with beside it: <source>/gatekeeper/oj-host.jar and
	// gatekeeper.json ({version, sha256, size, gatehouse: the launcher's version in that same build}). A running
	// Gatekeeper looks there every five minutes: the pack server on this machine if there is one (host.json
	// "pack_port", 8467), then GitHub; or the places listed in host.json "update_sources".
	//   - A newer Gatekeeper: its window offers to update. Fetched, checked against that sha256, put beside the
	//     running one as oj-host.jar.new; a short PowerShell command then waits for Gatekeeper to end, swaps the file
	//     and starts Gatekeeper again (Windows locks a running program's file). Minecraft servers aren't touched.
	//   - While Gatekeeper is behind it starts no server, and tells launchers that joining is blocked (they don't
	//     offer Play). A launcher that is itself behind the published Gatehouse is told the same, for itself.
	//   - Nothing can be reached, or host.json "require_current" is false: nothing is blocked.
	static volatile String newestKeeper = "", newestKeeperSha = "", newestKeeperAt = "", newestHouse = "";
	static String ownVersion() { return Json.str(notesInside("gatekeeper").get("version")); }
	static int cmpVersion(String a, String b) {
		String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = 0, q = 0;
			try { if (i < x.length) p = Integer.parseInt(x[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException ignored) {}
			try { if (i < y.length) q = Integer.parseInt(y[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException ignored) {}
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}
	static List<String> updateSources() {
		List<String> out = new ArrayList<>();
		for (Object o : Json.list(cfg.get("update_sources"))) { String s = Json.str(o).trim().replaceAll("/+$", ""); if (!s.isEmpty()) out.add(s); }
		if (!out.isEmpty()) return out;
		String bind = setting("bind", "");
		if (!bind.isEmpty()) out.add("http://" + bind + ":" + setting("pack_port", "8467") + "/gatekeeper");
		out.add("https://raw.githubusercontent.com/scholar-andrew/gatehouse/main/dist/gatekeeper");
		return out;
	}
	static byte[] fetchBytes(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(4000); c.setReadTimeout(20000);
		if (c.getResponseCode() != 200) throw new IOException("answered " + c.getResponseCode());
		try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
	}
	static String sha256(byte[] b) {
		try { StringBuilder sb = new StringBuilder(); for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x)); return sb.toString(); }
		catch (java.security.NoSuchAlgorithmException e) { return ""; }
	}
	/** Did the last look reach a place where Gatekeeper is published? */
	static volatile boolean lookReached = false;
	static synchronized void lookForNewer() {
		lookReached = false;
		for (String src : updateSources()) try {
			Map<String, Object> mf = Json.map(Json.parse(new String(fetchBytes(src + "/gatekeeper.json"), StandardCharsets.UTF_8)));
			String v = Json.str(mf.get("version")), sha = Json.str(mf.get("sha256"));
			if (v.isEmpty() || sha.isEmpty()) continue;
			if (!v.equals(newestKeeper) && cmpVersion(v, ownVersion()) > 0) log("a newer Gatekeeper is published: " + v + " (this is " + ownVersion() + "). Servers aren't started until it's updated.");
			newestKeeper = v; newestKeeperSha = sha; newestKeeperAt = src; newestHouse = Json.str(mf.get("gatehouse"));
			lookReached = true;
			return;
		} catch (IOException | RuntimeException next) { /* the next place */ }
	}
	static boolean requireCurrent() { return !setting("require_current", "true").equals("false"); }
	static boolean outdated() { return requireCurrent() && !newestKeeper.isEmpty() && cmpVersion(newestKeeper, ownVersion()) > 0; }
	/** Why this launcher may not join right now ("": it may): Gatekeeper is behind, or that launcher's Gatehouse is. */
	static String blockedFor(HttpExchange x) {
		if (outdated()) return "This server's Gatekeeper is out of date (" + ownVersion() + "; " + newestKeeper + " is out). Its owner has to update it before anyone can start or join the server.";
		String theirs = header(x, "X-Gatehouse");
		if (requireCurrent() && !newestHouse.isEmpty() && !theirs.isEmpty() && cmpVersion(newestHouse, theirs) > 0)
			return "Gatehouse on this device is out of date (" + theirs + "; " + newestHouse + " is out). Update it on its main window (Update Gatehouse, or Restart there), then come back.";
		return "";
	}
	/** Linux and SteamOS: the script that takes over once this Gatekeeper has ended, doing what the Windows one does
	 *  (see applyUpdate): nothing of this Gatekeeper left running, the old program kept, the new one put in place and
	 *  started (through its systemd user service where it has one, else directly), asked whether it answers, and the
	 *  old one put back and started when it doesn't. Written beside Gatekeeper's settings as update-restart.sh, so what
	 *  runs can be read. The service is made with KillMode=process (install-keeper.sh), so this script isn't ended
	 *  with the Gatekeeper that started it. */
	static Path linuxUpdateScript(Path jar, Path fresh, String javaExe, long alsoWait) throws IOException {
		java.util.function.Function<String, String> s = v -> "'" + v.replace("'", "'\\''") + "'";
		String unit = setting("update_restart", "task").equals("task") ? setting("unit", "gatehouse-keeper") + ".service" : "";
		// (the window's own display, so it can be opened again from here: a service doesn't always know of one)
		StringBuilder display = new StringBuilder();
		if (alsoWait > 0) try {
			for (String kv : new String(Files.readAllBytes(Path.of("/proc/" + alsoWait + "/environ")), StandardCharsets.UTF_8).split("\0"))
				for (String k : new String[] {"DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"})
					if (kv.startsWith(k + "=")) display.append("export ").append(k).append('=').append(s.apply(kv.substring(k.length() + 1))).append('\n');
		} catch (IOException | RuntimeException ignored) {}
		String sh = "#!/bin/bash\n"
				+ "# Written by Gatekeeper when it updates itself: puts the new Gatekeeper in place and starts it again.\n"
				+ "jar=" + s.apply(jar.toString()) + "; new=" + s.apply(fresh.toString()) + "; old=\"$jar.old\"; log=" + s.apply(dir.resolve("host.log").toString()) + "\n"
				+ "java=" + s.apply(javaExe) + "; work=" + s.apply(dir.toString()) + "; folder=" + s.apply(jar.getParent().toString()) + "; port=" + port() + "; unit=" + s.apply(unit) + "\n"
				+ display
				+ "say() { echo \"$(date '+%Y-%m-%d %H:%M:%S')  update: $1\" >> \"$log\"; }\n"
				+ "# this Gatekeeper's own processes of one kind (--serve or --panel): only ones started from this very file\n"
				+ "mine() { local p c; for p in /proc/[0-9]*; do c=\"$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null)\"; case \"$c\" in *\"$jar\"*\"$1\"*) echo \"${p#/proc/}\" ;; esac; done; }\n"
				+ "answers() {\n"
				+ "  local until=$((SECONDS + $1))\n"
				+ "  while [ \"$SECONDS\" -lt \"$until\" ]; do\n"
				+ "    if command -v curl >/dev/null 2>&1; then curl -fs -o /dev/null -m 2 \"http://127.0.0.1:$port/admin/update\" && return 0\n"
				+ "    elif (exec 3<>\"/dev/tcp/127.0.0.1/$port\") 2>/dev/null; then return 0; fi\n"
				+ "    sleep 0.4\n"
				+ "  done\n"
				+ "  return 1\n"
				+ "}\n"
				+ "has_unit() { [ -n \"$unit\" ] && command -v systemctl >/dev/null 2>&1 && systemctl --user cat \"$unit\" >/dev/null 2>&1; }\n"
				+ "stop_it() { has_unit && systemctl --user stop \"$unit\" >/dev/null 2>&1; local p; for p in $(mine --serve); do kill -9 \"$p\" 2>/dev/null; done; }\n"
				+ "start_it() {\n"
				+ "  local i\n"
				+ "  if has_unit; then\n"
				+ "    systemctl --user reset-failed \"$unit\" >/dev/null 2>&1; systemctl --user start \"$unit\" >/dev/null 2>&1\n"
				+ "    for i in 1 2 3 4 5 6 7 8 9 10 11 12; do [ -n \"$(mine --serve)\" ] && break; sleep 0.5; done\n"
				+ "  fi\n"
				+ "  if [ -z \"$(mine --serve)\" ]; then (cd \"$work\" && nohup \"$java\" -jar \"$jar\" --serve >/dev/null 2>&1 &); fi\n"
				+ "}\n"
				+ "for p in " + ProcessHandle.current().pid() + (alsoWait > 0 ? " " + alsoWait : "") + "; do i=0; while kill -0 \"$p\" 2>/dev/null && [ \"$i\" -lt 50 ]; do sleep 0.5; i=$((i + 1)); done; done\n"
				+ "had_window=" + (alsoWait > 0 ? "1" : "0") + "; [ -n \"$(mine --panel)\" ] && had_window=1\n"
				+ "stop_it; for p in $(mine --panel); do kill -9 \"$p\" 2>/dev/null; done\n"
				+ "sleep 0.5\n"
				+ "cp -f \"$jar\" \"$old\"\n"
				+ "ok=0; mv -f \"$new\" \"$jar\" && ok=1\n"
				+ "if [ \"$ok\" = 0 ]; then say 'oj-host.jar could not be replaced; the Gatekeeper that was running starts again'; rm -f \"$new\"; fi\n"
				+ "start_it\n"
				+ "if [ \"$ok\" = 1 ]; then\n"
				+ "  if answers 30; then say 'the new Gatekeeper is in place and answering'; rm -f \"$old\"\n"
				+ "  else\n"
				+ "    say 'the new Gatekeeper did not answer within 30 seconds: going back to the one before'\n"
				+ "    stop_it; sleep 1; cp -f \"$old\" \"$jar\"; start_it\n"
				+ "    if answers 30; then say 'the Gatekeeper from before is running again (the update was undone)'; rm -f \"$old\"\n"
				+ "    else say 'Gatekeeper is not answering: start it from the applications menu (Gatekeeper)'; fi\n"
				+ "  fi\n"
				+ "elif ! answers 30; then say 'Gatekeeper is not answering: start it from the applications menu (Gatekeeper)'; fi\n"
				+ "if [ \"$had_window\" = 1 ]; then (cd \"$folder\" && nohup \"$java\" -jar \"$jar\" --panel >/dev/null 2>&1 &); fi\n";
		Path script = dir.resolve("update-restart.sh");
		Files.writeString(script, sh, StandardCharsets.UTF_8);
		return script;
	}

	/** Fetches the published Gatekeeper, checks it, and arranges for it to take this one's place. Gatekeeper ends a
	 *  moment after this returns ok. "alsoWait": the window's process, which has to end too (it holds the file open),
	 *  and is opened again afterwards. */
	/** An update was asked for while a server Gatekeeper runs itself was on: it is applied when none is. */
	static volatile boolean updateWaiting = false;
	static String[] applyUpdate(long alsoWait) {
		lookForNewer();
		if (newestKeeper.isEmpty() || cmpVersion(newestKeeper, ownVersion()) <= 0) return new String[] {"ok", "This is already the newest Gatekeeper (" + ownVersion() + ")."};
		if (DirectHost.anyTracked()) {
			// (asked for, so it is done as soon as it can be: when no server Gatekeeper runs itself is on)
			updateWaiting = true;
			return new String[] {"refused", "A Minecraft server that this Gatekeeper runs is on. Updating restarts Gatekeeper, and the server's console would be lost with it, so the update waits: Gatekeeper updates by itself as soon as that server is off (or stop the server and press Update)."};
		}
		updateWaiting = false;
		try {
			Path jar = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(jar)) return new String[] {"refused", "Gatekeeper isn't running from a file it can replace."};
			byte[] b = fetchBytes(newestKeeperAt + "/oj-host.jar");
			if (!sha256(b).equalsIgnoreCase(newestKeeperSha)) return new String[] {"failed", "What was fetched doesn't match what was published (its checksum differs). Nothing was changed."};
			Path fresh = jar.resolveSibling("oj-host.jar.new");
			Files.write(fresh, b);
			// (the checksum says it is what was published; this says what was published is a Gatekeeper that can start)
			boolean sound = false;
			try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(fresh.toFile())) { sound = z.getEntry("dev/octoberjourney/host/HostHelper.class") != null && z.getEntry("META-INF/MANIFEST.MF") != null && z.getEntry("gatekeeper-notes.json") != null; }
			catch (IOException | RuntimeException notAJar) { sound = false; }
			if (!sound) { Files.deleteIfExists(fresh); return new String[] {"failed", "What was published isn't a Gatekeeper that can run. Nothing was changed."}; }
			// One program: the Play half on this machine, if there is one, gets the same file now (it is never in use).
			int play = dev.gatehouse.Main.updatePlayCopies(fresh);
			if (play > 0) log("update: the Gatehouse launcher on this machine was updated with it (" + play + (play == 1 ? " copy)" : " copies)"));
			String javaExe = ProcessHandle.current().info().command().orElse("javaw"), q = "'", folder = jar.getParent().toString();
			java.util.function.Function<String, String> s = v -> q + v.replace("'", "''") + q;
			// The command that takes over once this Gatekeeper has ended. It makes sure nothing of this Gatekeeper still
			// holds the file (only processes started from this very jar), keeps the old jar, puts the new one in place,
			// starts Gatekeeper (through its scheduled task, checking that it really came up; else directly), and then
			// asks it whether it answers. If the new one doesn't answer within 30 seconds, the old jar goes back and is
			// started instead. Everything it does is noted in host.log.
			if (!WIN) {
				Path sh = linuxUpdateScript(jar, fresh, javaExe, alsoWait);
				// (in a session of its own where that can be had, so it outlives this Gatekeeper)
				new ProcessBuilder(onPath("setsid") ? List.of("setsid", "bash", sh.toString()) : List.of("bash", sh.toString()))
						.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				log("updating to Gatekeeper " + newestKeeper + " (from " + newestKeeperAt + "): ending now, and starting again as the new one");
				Thread endNow = new Thread(() -> { try { Thread.sleep(900); } catch (InterruptedException ignored) {} System.exit(0); }, "update");
				endNow.setDaemon(true); endNow.start();
				return new String[] {"ok", "Fetched and checked. Gatekeeper is restarting as " + newestKeeper + "."};
			}
			boolean task = setting("update_restart", "task").equals("task");
			String ps = "$ErrorActionPreference = 'SilentlyContinue'\n"
					+ "$jar = " + s.apply(jar.toString()) + "; $new = " + s.apply(fresh.toString()) + "; $old = $jar + '.old'; $log = " + s.apply(dir.resolve("host.log").toString()) + "\n"
					+ "$java = " + s.apply(javaExe) + "; $work = " + s.apply(dir.toString()) + "; $folder = " + s.apply(folder) + "; $port = " + port() + "\n"
					+ "function Say($t) { Add-Content -LiteralPath $log -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  update: ' + $t) }\n"
					+ "function Mine($what) { @(Get-CimInstance Win32_Process -Filter \"Name='javaw.exe' or Name='java.exe'\" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($jar) -and $_.CommandLine.Contains($what) }) }\n"
					+ "function Answers($secs) { $until = (Get-Date).AddSeconds($secs); while ((Get-Date) -lt $until) { try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 ('http://127.0.0.1:' + $port + '/admin/update'); if ($r.StatusCode -eq 200) { return $true } } catch { }; Start-Sleep -Milliseconds 400 }; return $false }\n"
					+ "function Start-It {\n"
					+ (task ? "  schtasks /run /tn " + s.apply(setting("task", "Gatehouse Keeper")) + " 2>$null | Out-Null\n  for ($i = 0; $i -lt 12 -and -not (Mine '--serve'); $i++) { Start-Sleep -Milliseconds 500 }\n" : "")
					+ "  if (-not (Mine '--serve')) { Start-Process $java -ArgumentList '-jar', ('\"' + $jar + '\"'), '--serve' -WorkingDirectory $work }\n}\n"
					+ "foreach ($p in " + ProcessHandle.current().pid() + (alsoWait > 0 ? ", " + alsoWait : "") + ") { Wait-Process -Id $p -Timeout 25 }\n"
					+ "$hadWindow = " + (alsoWait > 0 ? "$true" : "$false") + "; if (Mine '--panel') { $hadWindow = $true }\n"
					+ "Mine '--serve' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }; Mine '--panel' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\n"
					+ "Start-Sleep -Milliseconds 500\n"
					+ "Copy-Item -LiteralPath $jar -Destination $old -Force\n"
					+ "$ok = $false\n"
					+ "for ($i = 0; $i -lt 40 -and -not $ok; $i++) { try { Move-Item -LiteralPath $new -Destination $jar -Force -ErrorAction Stop; $ok = $true } catch { Start-Sleep -Milliseconds 500 } }\n"
					+ "if (-not $ok) { Say 'oj-host.jar could not be replaced; the Gatekeeper that was running starts again'; Remove-Item -LiteralPath $new -Force }\n"
					+ "Start-It\n"
					+ "if ($ok) {\n"
					+ "  if (Answers 30) { Say 'the new Gatekeeper is in place and answering'; Remove-Item -LiteralPath $old -Force }\n"
					+ "  else {\n"
					+ "    Say 'the new Gatekeeper did not answer within 30 seconds: going back to the one before'\n"
					+ "    Mine '--serve' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\n"
					+ "    Start-Sleep -Seconds 1; Copy-Item -LiteralPath $old -Destination $jar -Force\n"
					+ "    Start-It\n"
					+ "    if (Answers 30) { Say 'the Gatekeeper from before is running again (the update was undone)'; Remove-Item -LiteralPath $old -Force } else { Say 'Gatekeeper is not answering: start it from the Start menu (Gatekeeper)' }\n"
					+ "  }\n"
					+ "} elseif (-not (Answers 30)) { Say 'Gatekeeper is not answering: start it from the Start menu (Gatekeeper)' }\n"
					+ "if ($hadWindow) { Start-Process $java -ArgumentList '-jar', ('\"' + $jar + '\"'), '--panel' -WorkingDirectory $folder }\n";
			// (kept as a file beside Gatekeeper's settings, so what runs can be read: update-restart.ps1)
			Path script = dir.resolve("update-restart.ps1");
			Files.writeString(script, "# Written by Gatekeeper when it updates itself: puts the new Gatekeeper in place and starts it again.\r\n" + ps.replace("\n", "\r\n"), StandardCharsets.UTF_8);
			new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			log("updating to Gatekeeper " + newestKeeper + " (from " + newestKeeperAt + "): ending now, and starting again as the new one");
			Thread end = new Thread(() -> { try { Thread.sleep(900); } catch (InterruptedException ignored) {} System.exit(0); }, "update");
			end.setDaemon(true); end.start();
			return new String[] {"ok", "Fetched and checked. Gatekeeper is restarting as " + newestKeeper + "."};
		} catch (Exception e) { return new String[] {"failed", "Couldn't update: " + e.getMessage() + ". Nothing was changed."}; }
	}

	/** Turns the whitelist on for every chosen server that has it off. A server that's running is told in its console
	 *  ("whitelist on": it takes effect at once and the server saves the setting itself); one that's off has
	 *  white-list=true written into its server.properties. */
	static String[] whitelistOn() {
		List<String> done = new ArrayList<>(), not = new ArrayList<>();
		String keep = SERVER.get();
		try {
			for (Object o : servers()) {
				Map<String, Object> sv = Json.map(o);
				SERVER.set(Json.str(sv.get("id")));
				Path props = serverDir().resolve("server.properties");
				String t = read(props), name = serverName();
				if (t.isEmpty() || t.replace(" ", "").contains("white-list=true")) continue;
				if (minecraftRunning()) {
					String[] r = sh().command("whitelist on");
					if (r[0].equals("ok")) {
						for (int i = 0; i < 12 && !read(props).replace(" ", "").contains("white-list=true"); i++) try { Thread.sleep(300); } catch (InterruptedException ignored) {}
						done.add(name + " (told in its console; in effect now)");
						continue;
					}
					not.add(name + " is running and couldn't be told: " + r[1]);
					continue;
				}
				String nl = t.contains("\r\n") ? "\r\n" : "\n";
				String out = t.matches("(?s).*(^|\\R)\\s*white-list\\s*=.*") ? t.replaceAll("(?m)^\\s*white-list\\s*=.*$", "white-list=true") : t + (t.endsWith("\n") ? "" : nl) + "white-list=true" + nl;
				try { Files.writeString(props, out, StandardCharsets.UTF_8); done.add(name + " (in its settings file; in effect when it next starts)"); }
				catch (IOException e) { not.add(name + ": couldn't write its server.properties (" + e.getMessage() + ")"); }
			}
		} finally { SERVER.set(keep); }
		if (done.isEmpty() && not.isEmpty()) return new String[] {"ok", "The whitelist is already on for every server."};
		String msg = (done.isEmpty() ? "" : "Whitelist on for " + String.join("; ", done) + ". ") + (not.isEmpty() ? "" : "Not done: " + String.join("; ", not) + ".");
		log("whitelist turned on from Host setup: " + msg);
		return new String[] {not.isEmpty() ? "ok" : done.isEmpty() ? "failed" : "ok", msg.trim()};
	}

	// ------------------------------------------------------------------ Gatekeeper

	static void serve() throws Exception {
		log("helper starting (port " + port() + ")");
		Thread newer = new Thread(() -> { while (true) { try { lookForNewer(); } catch (RuntimeException ignored) {} try { Thread.sleep(300_000); } catch (InterruptedException e) { return; } } }, "newer");
		newer.setDaemon(true); newer.start();
		// Once a minute, also with no panel or launcher open, the server's log is read for joins (through Gatekeeper's
		// own local call, so it takes its turn with the other requests): the log starts afresh when the server restarts.
		Thread watch = new Thread(() -> {
			while (true) {
				try { Thread.sleep(60_000); call("GET", "/admin/players", null); } catch (InterruptedException e) { return; } catch (Exception ignored) {}
			}
		}, "players");
		watch.setDaemon(true);
		watch.start();
		// The panel's side, on this PC only.
		HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
		local.createContext("/admin/", HostHelper::admin);
		local.createContext("/v1/", HostHelper::launcher);   // (the launcher on this same machine reaches Gatekeeper here, with or without Tailscale)
		local.start();
		bindRemote();
		// (for the servers Gatekeeper runs itself: their tasks by the clock, and the ones set to start with it)
		ServerTools.schedule();
		ServerTools.autostart();
		// (an update that had to wait for a server to be off: done when it is)
		Thread waiting = new Thread(() -> {
			while (true) {
				try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
				try { if (updateWaiting && !DirectHost.anyTracked()) { String[] r = applyUpdate(0); log("update that was waiting for the servers to be off: " + r[1]); } } catch (RuntimeException ignored) {}
			}
		}, "update-waiting");
		waiting.setDaemon(true); waiting.start();
		// (once a minute: has MCSS deleted a server Gatekeeper still lists?)
		Thread gone = new Thread(() -> { while (true) { try { noteGone(); Thread.sleep(60_000); } catch (InterruptedException e) { return; } catch (RuntimeException ignored) {} } }, "deleted-in-mcss");
		gone.setDaemon(true); gone.start();
	}

	static volatile HttpServer remote;
	static volatile int bindTurn = 0;
	/** The launchers' side, on this machine's Tailscale address only (host.json "bind"). Tailscale may still be starting
	 *  at sign-in, and a new install has no address until Server setup in Gatekeeper gives one: retried every
	 *  10 seconds. Called again when the address changes: the old listener stops and the new one starts. */
	static synchronized void bindRemote() {
		int turn = ++bindTurn;
		HttpServer old = remote;
		remote = null;
		if (old != null) old.stop(0);
		new Thread(() -> {
			boolean said = false;
			while (turn == bindTurn) {
				String bind = setting("bind", "");
				if (bind.isEmpty()) { if (!said) log("no Tailscale address yet (host.json \"bind\"): open Gatekeeper, Status, Server setup"); said = true; }
				else try {
					HttpServer r = HttpServer.create(new InetSocketAddress(bind, port()), 0);
					r.createContext("/v1/", HostHelper::launcher);
					r.createContext("/panel", WebPanel::web);   // (the web panel: off unless the owner turned it on)
					r.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
					r.start();
					if (turn != bindTurn) { r.stop(0); return; }
					remote = r;
					log("listening on " + bind + ":" + port());
					return;
				} catch (IOException ignored) {}
				try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
			}
		}, "bind").start();
	}

	// ------------------------------------------------------------------ server setup: tying Gatekeeper to what's on this machine

	/** What Server setup shows: MCSS's program (as asked, else as set, else looked for in the usual places), the
	 *  Minecraft servers found in its servers folder (each has MCSS's own mcss_server_config.json, with its name and id;
	 *  the port is from server.properties) and whether Gatekeeper looks after each already, and this machine's
	 *  Tailscale addresses. */
	static Map<String, Object> setupScan(String exe) {
		String home = System.getProperty("user.home", "");
		if (builtin()) {
			// No MCSS: the servers are the folders in one folder (asked, else as set), each with a server's files in it.
			String folder = exe.isEmpty() ? serversDir() : exe, trouble = "";
			List<Object> have = new ArrayList<>();
			List<Path> dirs = new ArrayList<>();
			if (!Files.isDirectory(Path.of(folder))) trouble = "There's no folder at " + folder + " yet. New server (on Gatekeeper's first screen) makes it, with a server in it; or Browse to where your servers already are.";
			else try (java.util.stream.Stream<Path> s = Files.list(Path.of(folder))) { s.filter(p -> Files.isDirectory(p) && DirectHost.looksLikeServer(p)).sorted().forEach(dirs::add); }
			catch (IOException e) { trouble = "That folder can't be read."; }
			for (Path d : dirs) {
				String port = "25565", name = d.getFileName().toString(), id = "";
				for (String l : read(d.resolve("server.properties")).split("\\R")) if (l.startsWith("server-port=") && l.substring(12).trim().matches("\\d+")) port = l.substring(12).trim();
				boolean tracked = false;
				for (Object o : servers()) if (samePath(Json.str(Json.map(o).get("dir")), d)) { tracked = true; id = Json.str(Json.map(o).get("id")); if (!Json.str(Json.map(o).get("name")).isEmpty()) name = Json.str(Json.map(o).get("name")); }
				have.add(m("name", name, "id", id, "dir", d.toString(), "minecraft", "127.0.0.1:" + port, "mcss_server", "", "tracked", tracked));
			}
			if (have.isEmpty() && trouble.isEmpty()) trouble = "No servers in that folder yet: each server is a folder of its own inside it, with the server's program (its jar) in it.";
			return m("mcss_exe", folder, "servers", have, "problem", trouble, "addresses", tailscaleAddresses(), "bind", setting("bind", ""), "port", String.valueOf(port()), "host", "builtin");
		}
		if (exe.isEmpty()) exe = setting("mcss_exe", "");
		if (exe.isEmpty() && !WIN) for (String d : new String[] {home + "/MCSS", home + "/mcss", home + "/Documents/MCSS", home + "/Desktop/MCSS", home + "/Downloads/MCSS", home + "/Minecraft/MCSS", "/opt/mcss", "/opt/MCSS"})
			for (String n : new String[] {"mcss", "MCSS"}) if (exe.isEmpty() && Files.isRegularFile(Path.of(d, n))) exe = Path.of(d, n).toString();
		if (exe.isEmpty() && WIN) for (String g : new String[] {"C:\\Minecraft\\MCSS\\mcss.exe", "C:\\MCSS\\mcss.exe", home + "\\MCSS\\mcss.exe", home + "\\Documents\\MCSS\\mcss.exe", home + "\\Desktop\\MCSS\\mcss.exe", home + "\\Downloads\\MCSS\\mcss.exe"})
			if (Files.isRegularFile(Path.of(g))) { exe = g; break; }
		List<Object> found = new ArrayList<>();
		String problem = "";
		if (exe.isEmpty()) problem = "MCSS wasn't found in the usual places: use Browse to pick " + mcssFile() + (WIN ? "." : " (the program in MCSS's folder; MCSS's Linux build is the one for this machine).");
		else if (!Files.isRegularFile(Path.of(exe))) problem = "There's no file at that path.";
		else {
			List<Path> dirs = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(Path.of(exe).toAbsolutePath().getParent().resolve("servers"))) { s.filter(Files::isDirectory).sorted().forEach(dirs::add); }
			catch (IOException e) { problem = "There's no servers folder beside that " + mcssFile() + "."; }
			for (Path d : dirs) {
				Map<String, Object> c = Json.map(Json.parse(read(d.resolve("mcss_server_config.json"))));
				if (c.isEmpty()) continue;
				String port = "25565";
				for (String l : read(d.resolve("server.properties")).split("\\R")) if (l.startsWith("server-port=") && l.substring(12).trim().matches("\\d+")) port = l.substring(12).trim();
				String name = Json.str(c.get("name")).isEmpty() ? d.getFileName().toString() : Json.str(c.get("name")), id = "";
				boolean tracked = false;
				for (Object o : servers()) if (samePath(Json.str(Json.map(o).get("dir")), d)) { tracked = true; id = Json.str(Json.map(o).get("id")); if (!Json.str(Json.map(o).get("name")).isEmpty()) name = Json.str(Json.map(o).get("name")); }
				found.add(m("name", name, "id", id, "dir", d.toString(), "minecraft", "127.0.0.1:" + port, "mcss_server", Json.str(c.get("guid")), "tracked", tracked));
			}
			if (found.isEmpty() && problem.isEmpty() && !nativeToo()) problem = "MCSS has no servers yet: make one in MCSS first, then look again.";
		}
		// (and, where Gatekeeper may run servers of its own beside MCSS's, the ones in its servers folder)
		if (nativeToo() && Files.isDirectory(Path.of(serversDir()))) try (java.util.stream.Stream<Path> s = Files.list(Path.of(serversDir()))) {
			for (Path d : (Iterable<Path>) s.filter(q -> Files.isDirectory(q) && DirectHost.looksLikeServer(q)).sorted()::iterator) {
				String name = d.getFileName().toString(), id = "";
				boolean tracked = false;
				for (Object o : servers()) if (samePath(Json.str(Json.map(o).get("dir")), d)) { tracked = true; id = Json.str(Json.map(o).get("id")); if (!Json.str(Json.map(o).get("name")).isEmpty()) name = Json.str(Json.map(o).get("name")); }
				found.add(m("name", name, "id", id, "dir", d.toString(), "minecraft", "127.0.0.1:" + (portNow(d.toString()).isEmpty() ? "25565" : portNow(d.toString())), "mcss_server", "", "tracked", tracked, "native", true));
			}
		} catch (IOException | RuntimeException ignored) {}
		List<Object> addrs = new ArrayList<>();
		try {
			for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
				for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
					byte[] b = a.getAddress();
					if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) addrs.add(a.getHostAddress());   // (100.64.0.0/10: Tailscale)
				}
		} catch (IOException | RuntimeException ignored) {}
		return m("mcss_exe", exe, "servers", found, "problem", problem, "addresses", addrs, "bind", setting("bind", ""), "port", String.valueOf(port()));
	}
	/** This machine's addresses on Tailscale's network (100.64.0.0/10). */
	static List<Object> tailscaleAddresses() {
		List<Object> addrs = new ArrayList<>();
		try {
			for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
				for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
					byte[] b = a.getAddress();
					if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) addrs.add(a.getHostAddress());
				}
		} catch (IOException | RuntimeException ignored) {}
		return addrs;
	}
	static boolean samePath(String a, Path b) {
		try { return !a.isEmpty() && Path.of(a).toAbsolutePath().normalize().toString().equalsIgnoreCase(b.toAbsolutePath().normalize().toString()); } catch (RuntimeException e) { return false; }
	}

	/** Saves Server setup: MCSS's program, this machine's Tailscale address, and the servers Gatekeeper looks after.
	 *  Servers it already had keep their place and their id (permissions are filed under them); new ones go after. */
	static synchronized String[] setupSave(Map<String, Object> b) {
		String exe = Json.str(b.get("mcss_exe")).trim(), bind = Json.str(b.get("bind")).trim();
		if (!exe.isEmpty() && builtin() && !Files.isDirectory(Path.of(exe))) return new String[] {"refused", "There's no folder at " + exe};
		if (!exe.isEmpty() && !builtin() && !Files.isRegularFile(Path.of(exe))) return new String[] {"refused", "There's no file at " + exe};
		if (!bind.isEmpty() && !bind.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return new String[] {"refused", "The Tailscale address should look like 100.x.y.z"};
		List<Object> picked = new ArrayList<>(), keep = new ArrayList<>();
		for (Object o : Json.list(b.get("servers"))) if (Files.isDirectory(Path.of(Json.str(Json.map(o).get("dir"))))) picked.add(o);
		if (picked.isEmpty()) return new String[] {"refused", "Tick at least one server."};
		java.util.Set<String> used = new java.util.HashSet<>();
		for (Object o : servers()) {
			Map<String, Object> have = Json.map(o);
			for (Object q : picked) if (samePath(Json.str(have.get("dir")), Path.of(Json.str(Json.map(q).get("dir"))))) {
				if (Json.str(have.get("mcss_server")).isEmpty()) have.put("mcss_server", Json.str(Json.map(q).get("mcss_server")));
				have.put("minecraft", Json.str(Json.map(q).get("minecraft")));
				keep.add(have); used.add(Json.str(have.get("id")));
			}
		}
		int added = 0;
		for (Object q : picked) {
			Map<String, Object> s = Json.map(q);
			boolean had = false;
			for (Object k : keep) if (samePath(Json.str(Json.map(k).get("dir")), Path.of(Json.str(s.get("dir"))))) had = true;
			if (had) continue;
			String base = Json.str(s.get("name")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""), id = base.isEmpty() ? "server" : base;
			for (int n = 2; used.contains(id); n++) id = base + "-" + n;
			used.add(id);
			keep.add(m("id", id, "name", Json.str(s.get("name")), "dir", Json.str(s.get("dir")), "minecraft", Json.str(s.get("minecraft")), "mcss_server", Json.str(s.get("mcss_server")), "native", builtin() || ServerTools.yes(s.get("native"))));
			added++;
		}
		boolean moved = !bind.isEmpty() && !bind.equals(setting("bind", ""));
		if (!exe.isEmpty()) cfg.put(builtin() ? "servers_dir" : "mcss_exe", exe);
		if (!bind.isEmpty()) cfg.put("bind", bind);
		cfg.put("servers", keep);
		try { Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8); }
		catch (IOException e) { return new String[] {"refused", "Couldn't save host.json: " + e.getMessage()}; }
		log("server setup: " + keep.size() + " server" + (keep.size() == 1 ? "" : "s") + (added > 0 ? " (" + added + " new)" : "") + (moved ? ", address " + bind : ""));
		if (moved) bindRemote();
		return new String[] {"ok", "Saved: Gatekeeper looks after " + keep.size() + " server" + (keep.size() == 1 ? "" : "s") + (setting("bind", "").isEmpty() ? ". It still needs this machine's Tailscale address before launchers can reach it." : ". Launchers pair with " + setting("bind", "") + ":" + port() + ".")};
	}

	/** Starts the Minecraft server, whatever it takes: with MCSS running, it asks MCSS to start it; with MCSS closed,
	 *  it starts MCSS and carries on in the background, starting the server as soon as MCSS answers (up to 2 minutes).
	 *  Returns at once with what is happening; how it ended goes in the log. */
	static String[] startServerFully(String by) {
		if (outdated()) return new String[] {"refused", "Gatekeeper is out of date (" + ownVersion() + "; " + newestKeeper + " is out), and starts no server until it's updated: the owner does that in Gatekeeper's window."};
		if (goneFromMcss(server())) return new String[] {"refused", serverName() + " was deleted in MC Server Soft, so there is nothing to start. Its owner removes it from Gatekeeper on the first screen."};
		if (!sh().hasAccess()) return new String[] {"refused", "Gatekeeper isn't signed in to MCSS yet (Status tab: Sign in to MCSS)"};
		if (sh().running()) {
			int st = sh().serverStatus();
			if (st == 1) return new String[] {"ok", "The server is already on"};
			if (st == 3) return new String[] {"ok", "The server is already starting"};
			String[] r = sh().serverAction(true);
			if (r[0].equals("ok")) expect(serverKey() + "|server", "starting", 240_000);
			return r;
		}
		String[] s = sh().start();
		if (!s[0].equals("ok")) return s;
		expect("mcss", "starting", 120_000); expect(serverKey() + "|server", "starting", 300_000);
		String which = SERVER.get();
		Thread t = new Thread(() -> {
			SERVER.set(which);
			try {
				for (int i = 0; i < 120 && sh().serverStatus() < 0; i++) Thread.sleep(1000);
				if (sh().serverStatus() < 0) { log("start " + serverName() + " (asked by " + by + "): MCSS started, but it isn't answering Gatekeeper (if its sign-in ended, sign in again on the Status tab)"); return; }
				Thread.sleep(1500);
				String[] r = sh().serverStatus() == 1 || sh().serverStatus() == 3 ? new String[] {"ok", "it was already coming up"} : sh().serverAction(true);
				log("start " + serverName() + " (asked by " + by + "), after starting MCSS: " + r[1]);
			} catch (InterruptedException ignored) {}
		}, "start-server");
		t.setDaemon(true);
		t.start();
		return new String[] {"ok", "MCSS wasn't running: it's starting now, and the server starts as soon as MCSS is ready (a minute or two in all)"};
	}

	/** What a server's folder says about it: {"vanilla" or "modded", its Minecraft version or ""}. Modded: it has mods
	 *  (a mods folder with jars in it) or a mod loader's files (Fabric, Forge, NeoForge, Quilt). The version is the one
	 *  the server last said it started as (its own log), else the one its versions folder is named for. */
	static String[] serverFacts(Path dir) {
		boolean modded = false;
		String version = "";
		try {
			Path mods = dir.resolve("mods");
			if (Files.isDirectory(mods)) try (java.util.stream.Stream<Path> s = Files.list(mods)) { modded = s.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")); }
			if (!modded && Files.isDirectory(dir)) try (java.util.stream.Stream<Path> s = Files.list(dir)) { modded = s.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches("(fabric|forge|neoforge|quilt).*\\.(jar|properties)")); }
			if (!modded) modded = Files.isDirectory(dir.resolve("libraries/net/fabricmc")) || Files.isDirectory(dir.resolve("libraries/net/minecraftforge")) || Files.isDirectory(dir.resolve("libraries/net/neoforged"));
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("Starting minecraft server version ([\\w.\\-]+)").matcher(read(dir.resolve("logs").resolve("latest.log")));
			while (m.find()) version = m.group(1);
			if (version.isEmpty() && Files.isDirectory(dir.resolve("versions"))) try (java.util.stream.Stream<Path> s = Files.list(dir.resolve("versions"))) { version = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().reduce((a, b) -> b).orElse(""); }
		} catch (IOException | RuntimeException ignored) {}
		return new String[] {modded ? "modded" : "vanilla", version};
	}

	static void reply(HttpExchange x, int code, Map<String, Object> body) throws IOException {
		byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
		x.getResponseHeaders().set("Content-Type", "application/json");
		x.sendResponseHeaders(code, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}

	static Map<String, Object> body(HttpExchange x) throws IOException {
		try (InputStream in = x.getRequestBody()) { return Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); }
	}

	static Map<String, Object> m(Object... kv) {
		Map<String, Object> o = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
		return o;
	}

	/** Launchers: POST /v1/hello {id, token, name, os}; GET /v1/mcss; POST /v1/mcss/start; POST /v1/mcss/close.
	 *  Every call carries the id and token (in the body for hello, headers X-Device / X-Token otherwise). */
	static void launcher(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath(), ip = x.getRemoteAddress().getAddress().getHostAddress();
			Map<String, Object> devs = devices();
			if (path.equals("/v1/hello") && x.getRequestMethod().equals("POST")) {
				Map<String, Object> b = body(x);
				String id = Json.str(b.get("id")), token = Json.str(b.get("token"));
				if (!id.matches("[0-9a-fA-F-]{16,64}") || token.length() < 32) { reply(x, 400, m("error", "bad hello")); return; }
				Map<String, Object> d = Json.map(devs.get(id));
				if (!d.isEmpty() && !sha256(token).equals(d.get("token_sha256"))) { reply(x, 403, m("state", "mismatch", "error", "this device id is already known with another secret")); log("rejected hello for " + id + " from " + ip + ": wrong secret"); return; }
				boolean fresh = d.isEmpty();
				if (fresh) { d.put("token_sha256", sha256(token)); d.put("granted", false); d.put("first_seen", LocalDateTime.now().format(TIME)); }
				d.put("name", Json.str(b.get("name")).replaceAll("[^\\w .()\\-]", "").trim());
				d.put("os", Json.str(b.get("os")).replaceAll("[^\\w .()\\-]", "").trim());
				d.put("ip", ip);
				String ts = tailscaleName(ip);
				if (!ts.isEmpty()) d.put("tailscale", ts);
				d.put("last_seen", LocalDateTime.now().format(TIME));
				contact.put(id, System.currentTimeMillis());
				devs.put(id, d);
				saveDevices(devs);
				if (fresh) log("new launcher asked for MCSS control: " + who(d) + " (" + ip + ")");
				reply(x, 200, m("state", Boolean.TRUE.equals(d.get("granted")) ? "granted" : "pending"));
				return;
			}
			SERVER.set(header(x, "X-Server"));
			scanPlayers();
			devs = devices();
			String id = x.getRequestHeaders().getFirst("X-Device"), token = x.getRequestHeaders().getFirst("X-Token");
			Map<String, Object> d = Json.map(devs.get(id == null ? "" : id));
			if (d.isEmpty() || token == null || !sha256(token).equals(d.get("token_sha256"))) { reply(x, 401, m("state", "unknown", "error", "say hello first")); return; }
			contact.put(id, System.currentTimeMillis());
			// Three separate permissions, each allowed per launcher in the panel: MCSS control ("granted"), PC sleep, and
			// server control (start and stop the Minecraft server through this helper's own MCSS key, so the launcher
			// needs no panel sign-in or key of its own).
			boolean granted = Boolean.TRUE.equals(d.get("granted")), canSleep = Boolean.TRUE.equals(d.get("sleep")), canServer = Boolean.TRUE.equals(d.get(serverKey())) || granted;   // (MCSS control includes server control)
			// A fourth, Gatekeeper control: the launcher may do what Gatekeeper does (see the launchers and the activity,
			// switch the other three permissions, forget a launcher). Only the panel on this PC gives or removes it, and
			// Gatekeeper's MCSS key can't be read or set from a launcher.
			boolean canAdmin = Boolean.TRUE.equals(d.get("admin"));
			// A launcher that is shut out: blacklisted, it is told nothing but that; suspended, it still sees how things
			// are but may do nothing. Either way it can still ask to be let back in (/v1/request).
			String[] ban = banOf(d);
			// (this machine's own launcher is allowed everything, on every server, whatever is written down for it)
			if (ServerTools.yes(d.get("local"))) { granted = true; canSleep = true; canServer = true; canAdmin = true; }
			if (!ban[0].isEmpty()) { granted = false; canSleep = false; canServer = false; canAdmin = false; }
			if (ban[0].equals("blacklist") && !path.equals("/v1/request")) {
				if (path.equals("/v1/mcss")) reply(x, 200, m("state", "pending", "ban", ban[0], "ban_scope", ban[1], "ban_reason", ban[2], "requests", requestStates(id, false), "request_reasons", requestStates(id, true)));
				else reply(x, 403, m("error", "this launcher is blacklisted"));
				return;
			}
			if (path.equals("/v1/about") && x.getRequestMethod().equals("GET")) {
				// Which Gatekeeper this is and what changed in it, for the Gatekeeper updates tab in a launcher.
				reply(x, 200, notesInside("gatekeeper"));
				return;
			}
			if (path.equals("/v1/phase") && x.getRequestMethod().equals("GET")) {
				// What this server and MCSS are doing right now, for any launcher Gatekeeper knows (not a blacklisted one).
				reply(x, 200, m("phase", serverPhase(), "mcss_phase", mcssPhase(), "blocked", blockedFor(x)));
				return;
			}
			if (path.equals("/v1/catalog") && x.getRequestMethod().equals("GET")) {
				// What this Gatekeeper looks after, for a launcher's menu: its own name, and each Minecraft server with the
				// address to join, whether it's on right now, and where its pack's profile is (for a launcher to install it).
				List<Object> list = new ArrayList<>();
				String keep = SERVER.get();
				try {
					for (Object o : servers()) {
						Map<String, Object> s = Json.map(o);
						SERVER.set(Json.str(s.get("id")));
						if (goneFromMcss(s)) continue;   // (deleted in MCSS: not shown to launchers)
						String port = Json.str(s.get("minecraft")).replaceAll(".*:", "");
						String[] facts = serverFacts(Path.of(Json.str(s.get("dir"))));
						list.add(m("id", Json.str(s.get("id")), "name", Json.str(s.get("name")), "address", (x.getLocalAddress().getAddress().isLoopbackAddress() || setting("bind", "").isEmpty() ? "127.0.0.1" : setting("bind", "")) + ":" + (port.isEmpty() ? "25565" : port),
								"online", serverPhase().equals("online"), "phase", serverPhase(), "profile", Json.str(s.get("profile")), "allowed", Boolean.TRUE.equals(d.get(serverKey())) || granted,
								"kind", facts[0], "minecraft_version", facts[1], "native", isNative()));
					}
				} finally { SERVER.set(keep); }
				String name = setting("name", "");
				if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Gatekeeper"; }
				reply(x, 200, m("name", name, "servers", list, "suspended", ban[0].equals("suspend"), "perm_machine", canSleep, "perm_mcss", granted, "mcss_phase", mcssPhase(), "close", host.closeMode(),
						"host", hostKind(), "system", systemName(), "version", ownVersion()));   // (what runs the servers here, so a launcher doesn't show an MCSS that isn't there)
				return;
			}
			if (path.equals("/v1/pulled") && x.getRequestMethod().equals("POST")) {
				// A launcher says it has just fetched an update from this machine (not from GitHub): what and how big.
				Map<String, Object> b = body(x);
				java.util.function.Function<String, Long> num = k -> { try { return (long) Double.parseDouble(Json.str(b.get(k))); } catch (NumberFormatException e) { return 0L; } };
				Map<String, Object> rec = m("time", LocalDateTime.now().format(TIME), "id", id, "name", Json.str(d.get("name")), "player", Json.str(d.get("player")), "os", Json.str(d.get("os")),
						"from", clean(Json.str(b.get("from")), 24), "to", clean(Json.str(b.get("to")), 24), "pack", clean(Json.str(b.get("pack")), 60), "server", Json.str(server().get("id")),
						"bytes", num.apply("bytes"), "local_bytes", num.apply("local_bytes"), "files", num.apply("files"), "mods", num.apply("mods"));
				synchronized (HostHelper.class) {
					List<Object> all = Json.list(Json.parse(read(dir.resolve("pulls.json"))));
					all.add(rec);
					while (all.size() > 1000) all.remove(0);
					Files.writeString(dir.resolve("pulls.json"), Json.write(all), StandardCharsets.UTF_8);
				}
				log(who(d) + " pulled an update from this machine: " + Json.str(rec.get("pack")) + " " + (Json.str(rec.get("from")).isEmpty() ? "" : Json.str(rec.get("from")) + " to ") + Json.str(rec.get("to")) + ", " + (num.apply("bytes") / 100_000 / 10.0) + " MB");
				reply(x, 200, m("ok", true));
				return;
			}
			if (path.equals("/v1/request") && x.getRequestMethod().equals("POST")) {
				// A request for the owner, from any launcher Gatekeeper knows (allowed anything or not): the server turned on
				// (the default), a permission, the whitelist, or to be let back in.
				Map<String, Object> rb = body(x);
				String[] r = request(d, id, Json.str(rb.get("type")).isEmpty() ? "server" : Json.str(rb.get("type")), Json.str(rb.get("message")));
				reply(x, 200, m("result", r[0], "message", r[1]));
				return;
			}
			if (path.equals("/v1/mcss") && x.getRequestMethod().equals("GET")) {
				// How this launcher controls the server, as it says itself: "helper" (through this helper), or with what it
				// has saved: "signin" (a panel account), "key" (an API key from before sign-in was the only way), "none".
				// Kept for the panel. One thing can be waiting for it here: the owner's order to forget what it has saved (handed
				// over until the launcher says it has it), and the owner's order to forget what it has saved.
				String kind = header(x, "X-Access"), saved = header(x, "X-Saved"), user = header(x, "X-Access-User");
				boolean changed = false;
				// Who plays on it, as the launcher says (the account the game last started as). Only a hint, shown as "not
				// confirmed": a player is tied to a launcher for real when the server's log shows them joining from it.
				String claimed = header(x, "X-Player");
				if (claimed.matches("\\w{1,16}") && !Boolean.TRUE.equals(d.get("player_confirmed")) && !claimed.equals(d.get("player"))) { d.put("player", claimed); changed = true; }
				if (!kind.isEmpty() && !(kind.equals(d.get("access")) && saved.equals(d.get("saved")) && user.equals(Json.str(d.get("access_user"))))) {
					d.put("access", kind); d.put("saved", saved); d.put("access_user", user); changed = true;
				}
				boolean clear = Boolean.TRUE.equals(d.get("clear_access"));
				if (clear && saved.equals("none")) { d.remove("clear_access"); clear = false; changed = true; log(who(d) + " forgot its saved server access"); }
				if (changed) saveDevices(devs);
				reply(x, 200, m("clear_access", clear, "host", hostKind(), "system", systemName(), "state", granted ? "granted" : "pending", "mcss", host.running(), "minecraft", minecraftRunning(), "close", host.closeMode(),
						"sleep", canSleep, "server", canServer, "admin", canAdmin, "server_ready", host.hasAccess(),
						"request", minecraftRunning() ? "" : requestState.getOrDefault(id + "|server", ""),
						"perm", m("machine", ban[0].isEmpty() ? permState(d, "machine") : "none", "mcss", ban[0].isEmpty() ? permState(d, "mcss") : "none",
								"server", !ban[0].isEmpty() ? "none" : permState(d, "mcss").equals("allowed") ? "allowed" : permState(d, "server")),
						"ban", ban[0], "ban_scope", ban[1], "ban_reason", ban[2],
						"whitelisted", Json.str(d.get("player")).isEmpty() ? "unknown" : whitelisted(Json.str(d.get("player"))) ? "yes" : "no",
						"requests", requestStates(id, false), "request_reasons", requestStates(id, true)));
				return;
			}
			if (path.startsWith("/v1/admin/")) {
				if (!canAdmin) { reply(x, 403, m("error", "this device isn't allowed to manage launchers (the owner allows it in Gatekeeper)")); return; }
				boolean post = x.getRequestMethod().equals("POST");
				if (path.equals("/v1/admin/devices") && !post) {
					Map<String, Object> all = listed();
					reply(x, 200, m("devices", all, "you", id, "mcss", host.running(), "minecraft", minecraftRunning(), "has_key", host.hasAccess()));
				} else if (path.equals("/v1/admin/pulls") && !post) {
					reply(x, 200, m("pulls", pullsFor()));
				} else if (path.equals("/v1/admin/log") && !post) {
					reply(x, 200, m("log", logFor(100)));
				} else if ((path.equals("/v1/admin/whitelist") || path.equals("/v1/admin/kick") || path.equals("/v1/admin/ban")) && post) {
					Map<String, Object> b = body(x);
					String player = Json.str(b.get("player")), kind = Json.str(b.get("kind")), reason = clean(Json.str(b.get("reason")), 200);
					String[] r;
					if (path.endsWith("whitelist")) r = setWhitelisted(player, Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value"))));
					else if (path.endsWith("kick")) r = kick(player, reason);
					else if (!kind.isEmpty() && !kind.equals("blacklist") && !kind.equals("suspend")) r = new String[] {"refused", "unknown kind"};
					else if (!player.isEmpty()) {
						boolean keeper = false;
						for (Object o : devs.values()) if (player.equals(Json.str(Json.map(o).get("player"))) && Boolean.TRUE.equals(Json.map(o).get("admin"))) keeper = true;
						r = keeper && !kind.isEmpty() ? new String[] {"refused", "That player has a launcher with Gatekeeper control: only Gatekeeper on the server PC can do that"} : banPlayer(player, kind, reason);
					} else {
						String tid = Json.str(b.get("id"));
						Map<String, Object> tt = Json.map(devs.get(tid));
						if (tt.isEmpty()) r = new String[] {"refused", "unknown launcher"};
						else if (!kind.isEmpty() && (tid.equals(id) || Boolean.TRUE.equals(tt.get("admin")))) r = new String[] {"refused", "That launcher has Gatekeeper control: only Gatekeeper on the server PC can do that"};
						else { if (kind.isEmpty()) { tt.remove("ban"); tt.remove("ban_reason"); } else { tt.put("ban", kind); tt.put("ban_reason", reason); } saveDevices(devs); r = new String[] {"ok", who(tt) + (kind.isEmpty() ? " is no longer blacklisted or suspended." : " is " + (kind.equals("blacklist") ? "blacklisted" : "suspended") + " (this device).")}; }
					}
					log(path.substring(10) + " " + (player.isEmpty() ? Json.str(b.get("id")) : player.replaceAll("[^\\w]", "")) + ": " + r[1] + " (asked by " + who(d) + ")");
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else if (path.equals("/v1/admin/players") && !post) {
					reply(x, 200, m("players", playerList()));
				} else if (path.equals("/v1/admin/player/perm") && post) {
					Map<String, Object> b = body(x);
					String perm = Json.str(b.get("perm"));
					if (perm.equals("admin")) { reply(x, 403, m("error", "Gatekeeper control is only given or removed in Gatekeeper on the server PC")); return; }
					String[] r = playerPerm(Json.str(b.get("player")), perm, stateOf(b));
					log("(asked by " + who(d) + ")");
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else if ((path.equals("/v1/admin/perm") || path.equals("/v1/admin/forget") || path.equals("/v1/admin/revoke")) && post) {
					Map<String, Object> b = body(x);
					String tid = Json.str(b.get("id"));
					Map<String, Object> t = Json.map(devs.get(tid));
					if (t.isEmpty()) { reply(x, 404, m("error", "unknown launcher")); return; }
					if (path.endsWith("revoke")) {
						if (Boolean.TRUE.equals(t.get("admin")) && !tid.equals(id)) { reply(x, 403, m("error", "That launcher has Gatekeeper control: only Gatekeeper on the server PC can remove its access")); return; }
						String[] r = revokeAccess(t);
						log("removed server access from " + who(t) + " (asked by " + who(d) + "): " + r[1]);
						saveDevices(devs);
						reply(x, 200, m("result", r[0], "message", r[1]));
						return;
					}
					if (path.endsWith("forget")) {
						if (tid.equals(id)) { reply(x, 403, m("error", "This is the launcher you're using: forget it in Gatekeeper on the server PC")); return; }
						if (Boolean.TRUE.equals(t.get("admin"))) { reply(x, 403, m("error", "That launcher has Gatekeeper control: only Gatekeeper on the server PC can forget it")); return; }
						devs.remove(tid); log("forgot " + who(t) + " (asked by " + who(d) + ")");
					} else {
						String perm = Json.str(b.get("perm"));
						String st = stateOf(b);
						if (perm.equals("admin") || devKey(perm) == null) { reply(x, 403, m("error", perm.equals("admin") ? "Gatekeeper control is only given or removed in Gatekeeper on the server PC" : "unknown permission")); return; }
						setPermState(t, perm, st);
						log(permName(perm) + " for " + who(t) + ": " + (st.equals("none") ? "not allowed" : st) + " (asked by " + who(d) + ")");
					}
					saveDevices(devs);
					reply(x, 200, m("ok", true));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.startsWith("/v1/server")) {
				if (!canServer) { reply(x, 403, m("error", "this device isn't allowed to control the server (the owner allows it in Gatekeeper)")); return; }
				if (path.equals("/v1/server") && x.getRequestMethod().equals("GET")) reply(x, 200, m("status", sh().serverStatus()));
				else if (path.equals("/v1/server/log") && x.getRequestMethod().equals("GET")) reply(x, 200, m("lines", serverLog(80)));
				else if ((path.equals("/v1/server/start") || path.equals("/v1/server/stop")) && x.getRequestMethod().equals("POST")) {
					String[] r = path.endsWith("start") ? startServerFully(who(d)) : sh().serverAction(false);
					if (path.endsWith("stop") && r[0].equals("ok")) expect(serverKey() + "|server", "stopping", 200_000);
					log((path.endsWith("start") ? "start" : "stop") + " the Minecraft server asked by " + who(d) + ": " + r[1]);
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.equals("/v1/pc/sleep") && x.getRequestMethod().equals("POST")) {
				if (!canSleep) { reply(x, 403, m("error", "this device isn't allowed to sleep the PC (the owner allows it in Gatekeeper)")); return; }
				String[] r = sleepPc();
				log("sleep the PC asked by " + who(d) + ": " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
				return;
			}
			if (!granted) { reply(x, 403, m("state", "pending", "error", "not allowed yet: the owner allows this device in Gatekeeper")); return; }
			String[] r;
			if (path.equals("/v1/mcss/start") && x.getRequestMethod().equals("POST")) { boolean was = host.running(); r = host.start(); if (!was && r[0].equals("ok")) expect("mcss", "starting", 120_000); }
			else if (path.equals("/v1/mcss/close") && x.getRequestMethod().equals("POST")) r = closeMcssFully("true".equals(Json.str(body(x).get("force"))), who(d));
			else { reply(x, 404, m("error", "no such call")); return; }
			log((path.endsWith("start") ? "start" : "close") + " MCSS asked by " + who(d) + ": " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1], "mcss", host.running()));
		} catch (Exception e) {
			log("error: " + e);
			try { reply(x, 500, m("error", "Gatekeeper had a problem")); } catch (IOException ignored) {}
		}
	}

	// ------------------------------------------------------------------ the Minecraft server, through MCSS's API

	/** The server's folder (host.json "server_dir"), and its id in MCSS (from mcss_server_config.json there). */
	/** The Minecraft server this helper looks after: the first entry of host.json "servers" (each: name, dir,
	 *  minecraft, mcss_server), which tools/install_host.ps1 fills in from the server profile. Nothing about a server is
	 *  fixed here; a host.json from before the list existed may still give server_dir / minecraft / mcss_server itself. */
	static List<Object> servers() { return Json.list(cfg.get("servers")); }
	/** Which of them the request being answered is about: its "id", as the launcher's profile names it (X-Server) or
	 *  Gatekeeper picks it (?server=). Empty or unknown: the first one. */
	static final ThreadLocal<String> SERVER = ThreadLocal.withInitial(() -> "");
	static Map<String, Object> server() {
		List<Object> l = servers();
		String want = SERVER.get();
		if (!want.isEmpty()) for (Object o : l) if (want.equals(Json.str(Json.map(o).get("id")))) return Json.map(o);
		return l.isEmpty() ? new LinkedHashMap<>() : Json.map(l.get(0));
	}
	static boolean firstServer() {
		List<Object> l = servers();
		return l.isEmpty() || Json.str(server().get("id")).equals(Json.str(Json.map(l.get(0)).get("id")));
	}
	static String serverName() { String n = Json.str(server().get("name")); return n.isEmpty() ? "the server" : n; }
	/** Where "server control" for the current server is kept, on a launcher or a player: "server" for the first server
	 *  (as it always was), "server:<id>" for any other. The other permissions are about the machine, not one server. */
	static String serverKey() { return firstServer() ? "server" : "server:" + Json.str(server().get("id")); }
	static String dkey(String[] pm) { return pm[0].equals("server") ? serverKey() : pm[1]; }
	static String pkey(String[] pm) { return pm[0].equals("server") ? serverKey() : pm[0]; }
	/** Is any of this machine's Minecraft servers running? (Sleeping the machine and closing the host program wait for all.) */
	static boolean anyServerRunning() {
		String keep = SERVER.get();
		try {
			if (servers().isEmpty()) return minecraftRunning();
			for (Object o : servers()) { SERVER.set(Json.str(Json.map(o).get("id"))); if (minecraftRunning()) return true; }
			return false;
		} finally { SERVER.set(keep); }
	}
	static String serverSetting(String key, String legacy, String def) {
		String v = Json.str(server().get(key));
		return !v.isEmpty() ? v : setting(legacy, def);
	}
	static Path serverDir() { return Path.of(serverSetting("dir", "server_dir", "")); }
	// ------------------------------------------------------------------ players

	static final String[][] PERMS = {{"server", "server", "server control"}, {"mcss", "granted", "MCSS control"}, {"sleep", "sleep", "Machine wake/sleep"}, {"admin", "admin", "Gatekeeper control"}};
	static long logSeen = -1;

	static Map<String, Object> players() { return Json.map(Json.parse(read(dir.resolve("players.json")))); }

	/** Ties players to launchers from the Minecraft server's own log: each "Name[/address:port] logged in" line names
	 *  a player and the address they joined from, and a launcher that talks to this helper from the same (Tailscale)
	 *  address is that player's device. That's who really joined, not what a device says. A player's device then gets
	 *  what the owner allowed that player (players.json). Only looks again when the log has changed. */
	static synchronized void scanPlayers() {
		String keep = SERVER.get();
		try {
			if (servers().isEmpty()) scanPlayersHere();
			for (Object o : servers()) { SERVER.set(Json.str(Json.map(o).get("id"))); scanPlayersHere(); }
		} finally { SERVER.set(keep); }
	}
	static final Map<String, Long> logSeenBy = new java.util.HashMap<>();
	/** When each launcher last spoke to Gatekeeper (its window asks every few seconds while it's open); not kept on disk. */
	static final Map<String, Long> contact = new java.util.concurrent.ConcurrentHashMap<>();
	/** Who is on each server by its own log, and the address they joined from: a "logged in" line puts a player in, a
	 *  "left the game" or "lost connection" line takes them out, and the server stopping empties it. */
	static final Map<String, Map<String, String>> onlineBy = new java.util.concurrent.ConcurrentHashMap<>();

	/** The players in a game right now, with the address each joined from: across the servers that are running. */
	static Map<String, String> onlineNow() {
		Map<String, String> out = new LinkedHashMap<>();
		String keep = SERVER.get();
		try {
			List<Object> l = servers();
			for (int i = 0; i < Math.max(1, l.size()); i++) {
				if (!l.isEmpty()) SERVER.set(Json.str(Json.map(l.get(i)).get("id")));
				if (minecraftRunning()) out.putAll(onlineBy.getOrDefault(serverKey(), Map.of()));
			}
		} finally { SERVER.set(keep); }
		return out;
	}
	/** Where a launcher's device is right now: "In game" (its player is on a server, having joined from this device),
	 *  "In the launcher" (its window spoke to Gatekeeper within the last 20 seconds), or "Offline". */
	static String presence(String id, Map<String, Object> d, Map<String, String> online) {
		String player = Json.str(d.get("player"));
		if (!player.isEmpty() && Json.str(d.get("ip")).equals(online.get(player))) return "In game";
		Long c = contact.get(id);
		return c != null && System.currentTimeMillis() - c < 20_000 ? "In the launcher" : "Offline";
	}
	private static void scanPlayersHere() {
		try {
			Path lg = serverDir().resolve("logs").resolve("latest.log");
			if (!Files.isRegularFile(lg)) return;
			long stamp = Files.getLastModifiedTime(lg).toMillis() * 31 + Files.size(lg);
			if (Long.valueOf(stamp).equals(logSeenBy.get(serverKey()))) return;
			logSeenBy.put(serverKey(), stamp);
			String day = java.time.LocalDate.ofInstant(Files.getLastModifiedTime(lg).toInstant(), java.time.ZoneId.systemDefault()).toString();
			Map<String, Object> devs = devices(), defaults = players();
			boolean changed = false;
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\[([0-9:]+)\\].*?: (\\w{1,16})\\[/([0-9a-fA-F.:]+):\\d+\\] logged in").matcher(read(lg));
			while (m.find()) {
				String when = day + " " + m.group(1), name = m.group(2), ip = m.group(3);
				for (Object v : devs.values()) {
					Map<String, Object> d = Json.map(v);
					if (!ip.equals(Json.str(d.get("ip")))) continue;
					boolean fresh = !name.equals(Json.str(d.get("player"))) || !Boolean.TRUE.equals(d.get("player_confirmed"));
					if (!fresh && when.equals(Json.str(d.get("player_seen")))) continue;
					d.put("player", name); d.put("player_confirmed", true); d.put("player_seen", when);
					changed = true;
					if (!fresh) continue;
					log("player " + name + " joined from " + who(d) + ": that launcher is now theirs");
					Map<String, Object> pd = Json.map(defaults.get(name));
					for (String[] pm : PERMS) if (Boolean.TRUE.equals(pd.get(pkey(pm))) && !Boolean.TRUE.equals(d.get(dkey(pm))) && !Boolean.TRUE.equals(Json.map(d.get("deny")).get(dkey(pm)))) { d.put(dkey(pm), true); log("allowed " + pm[2] + " for " + who(d) + " (allowed for player " + name + ")"); }
				}
			}
			if (changed) saveDevices(devs);
			Map<String, String> on = new LinkedHashMap<>();
			java.util.regex.Pattern in = java.util.regex.Pattern.compile(": (\\w{1,16})\\[/([0-9a-fA-F.:]+):\\d+\\] logged in"), gone = java.util.regex.Pattern.compile(": (\\w{1,16}) (left the game|lost connection)");
			for (String line : read(lg).split("\\R")) {
				java.util.regex.Matcher a = in.matcher(line), b = gone.matcher(line);
				if (a.find()) on.put(a.group(1), a.group(2));
				else if (b.find()) on.remove(b.group(1));
				else if (line.contains("Stopping server") || line.contains("Stopping the server")) on.clear();
			}
			onlineBy.put(serverKey(), on);
		} catch (Exception e) {
			log("couldn't read the server log for players: " + e);
		}
	}

	/** The players for the panel: each with their launchers, when they last joined, and for each permission how many
	 *  of their confirmed launchers have it and whether it's allowed for the player (so for launchers yet to come). */
	static Map<String, Object> playerList() {
		scanPlayers();
		Map<String, Object> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER), defaults = players();
		for (String name : defaults.keySet()) out.put(name, m("devices", new ArrayList<Object>(), "confirmed", 0, "unconfirmed", 0, "last_seen", ""));
		for (Object v : devices().values()) {
			Map<String, Object> d = Json.map(v);
			String name = Json.str(d.get("player"));
			if (name.isEmpty()) continue;
			Map<String, Object> p = Json.map(out.get(name));
			if (p.isEmpty()) { p = m("devices", new ArrayList<Object>(), "confirmed", 0, "unconfirmed", 0, "last_seen", ""); out.put(name, p); }
			boolean ok = Boolean.TRUE.equals(d.get("player_confirmed"));
			Json.list(p.get("devices")).add(Json.str(d.get("name")) + (ok ? "" : " (not confirmed)"));
			p.put(ok ? "confirmed" : "unconfirmed", ((Number) p.get(ok ? "confirmed" : "unconfirmed")).intValue() + 1);
			if (ok && Json.str(d.get("player_seen")).compareTo(Json.str(p.get("last_seen"))) > 0) p.put("last_seen", Json.str(d.get("player_seen")));
			if (ok) for (String[] pm : PERMS) if (Boolean.TRUE.equals(d.get(dkey(pm)))) p.put("has_" + pm[0], (p.get("has_" + pm[0]) instanceof Number n ? n.intValue() : 0) + 1);
		}
		Map<String, String> onlineHere = onlineNow();
		// Everyone on the server's whitelist is listed too, with or without a launcher, so they can be ticked off again.
		List<Object> wl = whitelist();
		for (Object o : wl) { String n = Json.str(Json.map(o).get("name")); if (!n.isEmpty() && !out.containsKey(n)) out.put(n, m("devices", new ArrayList<Object>(), "confirmed", 0, "unconfirmed", 0, "last_seen", "")); }
		for (Map.Entry<String, Object> e : out.entrySet()) {
			boolean listed = false;
			for (Object o : wl) if (e.getKey().equalsIgnoreCase(Json.str(Json.map(o).get("name")))) listed = true;
			Json.map(e.getValue()).put("whitelisted", listed);
			Map<String, Object> pdx = Json.map(defaults.get(e.getKey()));
			for (String pn : new String[] {"machine", "mcss", "server", "admin"}) Json.map(e.getValue()).put("state_" + pn, permState(pdx, pn));
			Json.map(e.getValue()).put("ban", Json.str(pdx.get("ban"))); Json.map(e.getValue()).put("ban_reason", Json.str(pdx.get("ban_reason")));
			// Right now: in a game, or with a launcher open on one of their devices, or neither.
			String now = onlineHere.containsKey(e.getKey()) ? "In game" : "Offline";
			if (now.equals("Offline")) for (Map.Entry<String, Object> dv : devices().entrySet())
				if (e.getKey().equals(Json.str(Json.map(dv.getValue()).get("player"))) && presence(dv.getKey(), Json.map(dv.getValue()), onlineHere).equals("In the launcher")) now = "In the launcher";
			Json.map(e.getValue()).put("now", now);
			Map<String, Object> pd = Json.map(defaults.get(e.getKey()));
			for (String[] pm : PERMS) Json.map(e.getValue()).put("allow_" + pm[0], Boolean.TRUE.equals(pd.get(pkey(pm))));
		}
		return out;
	}

	/** Sets one permission for a player (allowed, denied or none): on every launcher confirmed as theirs now, and
	 *  remembered for launchers they join from later. A launcher that is denied that same thing itself keeps its own
	 *  setting, whatever the player is given. A launcher that only says it's theirs gets nothing until the server
	 *  confirms it. */
	static String[] playerPerm(String name, String perm, String state) throws IOException {
		if (perm.equals("sleep")) perm = "machine";
		if (devKey(perm) == null || !name.matches("\\w{1,16}")) return new String[] {"refused", "unknown player or permission"};
		Map<String, Object> defaults = players();
		Map<String, Object> pd = Json.map(defaults.get(name));
		if (!(defaults.get(name) instanceof Map)) defaults.put(name, pd);
		setPermState(pd, perm, state);
		savePlayers(defaults);
		Map<String, Object> devs = devices();
		int n = 0, waiting = 0, kept = 0;
		for (Object o : devs.values()) {
			Map<String, Object> d = Json.map(o);
			if (!name.equals(Json.str(d.get("player")))) continue;
			if (!Boolean.TRUE.equals(d.get("player_confirmed"))) { waiting++; continue; }
			if (!state.equals("denied") && permState(d, perm).equals("denied")) { kept++; continue; }
			setPermState(d, perm, state); n++;
		}
		saveDevices(devs);
		String msg = permName(perm) + " for " + name + ": " + (state.equals("none") ? "not allowed" : state) + ". " + n + " launcher" + (n == 1 ? "" : "s") + " changed"
				+ (kept > 0 ? "; " + kept + " kept its own Denied" : "")
				+ (waiting > 0 ? "; " + waiting + " not confirmed yet (" + name + " has to join the server from it once)" : "") + (state.equals("allowed") ? ". Launchers they join from later get it too." : ".");
		log(msg);
		return new String[] {"ok", msg};
	}

	// ------------------------------------------------------------------ what a launcher may do: three states, and bans

	/** Where each permission is kept on a launcher (and, for a player, in players.json): machine (wake and sleep the
	 *  server machine), mcss (start and close MCSS; it includes server), server (start and stop the Minecraft server
	 *  being looked at), admin (Gatekeeper control). */
	static String devKey(String perm) { return perm.equals("machine") || perm.equals("sleep") ? "sleep" : perm.equals("mcss") ? "granted" : perm.equals("server") ? serverKey() : perm.equals("admin") ? "admin" : null; }
	static String permName(String perm) { return perm.equals("machine") || perm.equals("sleep") ? "machine wake and sleep" : perm.equals("mcss") ? "MCSS control" : perm.equals("server") ? "server control" : "Gatekeeper control"; }
	/** A permission is one of three things: "allowed", "denied" (refused on purpose: nothing given to the player's other
	 *  devices or to the player as a whole changes it), or "none" (simply not allowed). */
	static String permState(Map<String, Object> holder, String perm) {
		String k = devKey(perm);
		if (k == null) return "none";
		if (Boolean.TRUE.equals(Json.map(holder.get("deny")).get(k))) return "denied";
		return Boolean.TRUE.equals(holder.get(k)) ? "allowed" : "none";
	}
	static void setPermState(Map<String, Object> holder, String perm, String state) {
		String k = devKey(perm);
		Map<String, Object> deny = Json.map(holder.get("deny"));
		if (state.equals("denied")) deny.put(k, true); else deny.remove(k);
		if (deny.isEmpty()) holder.remove("deny"); else holder.put("deny", deny);
		holder.put(k, state.equals("allowed"));
	}
	static String stateOf(Map<String, Object> body) {
		String s = Json.str(body.get("state"));
		if (s.equals("allowed") || s.equals("denied") || s.equals("none")) return s;
		return Boolean.TRUE.equals(body.get("value")) || "true".equals(Json.str(body.get("value"))) ? "allowed" : "none";
	}

	/** Whether a launcher is shut out: {kind, scope, reason}. kind: "blacklist" (the launcher still updates the game
	 *  but is told nothing about the machine, MCSS or the server), "suspend" (it sees them but can't start the game or
	 *  do anything), or "". scope: "machine" (this launcher) or "user" (the player it belongs to, on every device). */
	static String[] banOf(Map<String, Object> d) {
		String k = Json.str(d.get("ban"));
		if (!k.isEmpty()) return new String[] {k, "machine", Json.str(d.get("ban_reason"))};
		String player = Json.str(d.get("player"));
		if (!player.isEmpty()) {
			Map<String, Object> pd = Json.map(players().get(player));
			if (!Json.str(pd.get("ban")).isEmpty()) return new String[] {Json.str(pd.get("ban")), "user", Json.str(pd.get("ban_reason"))};
		}
		return new String[] {"", "", ""};
	}
	static String banText(String[] ban, String player) {
		if (ban[0].isEmpty()) return "";
		return (ban[0].equals("blacklist") ? "Blacklisted" : "Suspended") + (ban[1].equals("user") ? " (player " + player + ")" : " (this device)");
	}
	static String clean(String s, int max) { s = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim(); return s.length() > max ? s.substring(0, max) : s; }
	static void savePlayers(Map<String, Object> all) throws IOException { Files.writeString(dir.resolve("players.json"), Json.write(all), StandardCharsets.UTF_8); }

	/** Kicks a player from the running server (its own "kick" command). */
	static String[] kick(String name, String reason) {
		if (!name.matches("\\w{1,16}")) return new String[] {"refused", "That isn't a Minecraft name"};
		if (!minecraftRunning()) return new String[] {"refused", "The server isn't running, so nobody is on it"};
		String[] r = sh().command("kick " + name + (clean(reason, 120).isEmpty() ? "" : " " + clean(reason, 120)));
		return r[0].equals("ok") ? new String[] {"ok", "Asked the server to kick " + name} : r;
	}

	/** Blacklists or suspends a player on every device (kind "blacklist" or "suspend"), or lifts it (""). They are
	 *  kicked if they're on, and taken off the server's whitelist so the server itself keeps them out; lifting it puts
	 *  them back on if this is what took them off. */
	static String[] banPlayer(String name, String kind, String reason) throws IOException {
		if (!name.matches("\\w{1,16}")) return new String[] {"refused", "That isn't a Minecraft name"};
		Map<String, Object> all = players();
		Map<String, Object> pd = Json.map(all.get(name));
		if (!(all.get(name) instanceof Map)) all.put(name, pd);
		List<String> did = new ArrayList<>();
		if (kind.isEmpty()) {
			pd.remove("ban"); pd.remove("ban_reason");
			if (Boolean.TRUE.equals(pd.remove("wl_restore"))) { String[] w = setWhitelisted(name, true); did.add(w[0].equals("ok") ? "back on the whitelist" : "not put back on the whitelist (" + w[1] + ")"); }
			savePlayers(all);
			return new String[] {"ok", name + " is no longer blacklisted or suspended" + (did.isEmpty() ? "" : "; " + String.join("; ", did)) + "."};
		}
		pd.put("ban", kind); pd.put("ban_reason", clean(reason, 200));
		if (minecraftRunning()) { String[] k = kick(name, kind.equals("blacklist") ? "Blacklisted" : "Suspended"); if (k[0].equals("ok")) did.add("kicked if they were on"); }
		if (whitelisted(name)) { String[] w = setWhitelisted(name, false); if (w[0].equals("ok")) { pd.put("wl_restore", true); did.add("taken off the whitelist"); } else did.add("still on the whitelist (" + w[1] + ")"); }
		savePlayers(all);
		return new String[] {"ok", name + " is " + (kind.equals("blacklist") ? "blacklisted" : "suspended") + " on every device" + (did.isEmpty() ? "" : "; " + String.join("; ", did)) + "."};
	}

	// ------------------------------------------------------------------ requests from launchers

	static boolean startMinimized;
	static final List<Object> requests = new ArrayList<>();   // the last few, newest last: {who, player, time}
	static final Map<String, Long> lastRequest = new java.util.HashMap<>();
	/** What became of each launcher's last request: "asked" (waiting for the owner) or "denied" (dismissed in the panel). */
	static final Map<String, String> requestState = new java.util.concurrent.ConcurrentHashMap<>();
	static int requestSeq = 0;
	static volatile long panelSeen = 0;   // when Gatekeeper last asked for its list (it does every few seconds while open)

	/** A launcher asks for the server to be turned on: noted for Gatekeeper, with a chime here and the panel
	 *  brought up minimized (its taskbar button flashes) if it isn't open. One a minute from each launcher. */
	static final Map<String, Long> requestTime = new java.util.concurrent.ConcurrentHashMap<>();
	static final Map<String, String> requestReason = new java.util.concurrent.ConcurrentHashMap<>();
	static final List<String> REQUEST_TYPES = List.of("server", "perm:machine", "perm:mcss", "perm:server", "whitelist", "unblacklist", "unsuspend");

	static String requestWords(String type) {
		return switch (type) {
			case "server" -> "the server to be turned on";
			case "perm:machine" -> "permission to wake and sleep the server machine";
			case "perm:mcss" -> "permission to start and close MCSS";
			case "perm:server" -> "permission to start and stop the Minecraft server";
			case "whitelist" -> "a place on the whitelist";
			case "unblacklist" -> "the blacklist to be lifted";
			case "unsuspend" -> "the suspension to be lifted";
			default -> type;
		};
	}

	/** A launcher asks for something: noted for Gatekeeper, with a chime here and the panel brought up minimized
	 *  (its taskbar button flashes) if it isn't open. One a minute of each kind from each launcher. A launcher that is
	 *  shut out may only ask to be let back in. */
	static synchronized String[] request(Map<String, Object> d, String id, String type, String message) {
		if (!REQUEST_TYPES.contains(type)) return new String[] {"refused", "unknown request"};
		String[] ban = banOf(d);
		if (!ban[0].isEmpty() && !type.equals(ban[0].equals("blacklist") ? "unblacklist" : "unsuspend")) return new String[] {"refused", "This launcher is " + (ban[0].equals("blacklist") ? "blacklisted" : "suspended")};
		if (ban[0].isEmpty() && (type.equals("unblacklist") || type.equals("unsuspend"))) return new String[] {"refused", "This launcher isn't blacklisted or suspended"};
		String player = Json.str(d.get("player"));
		if (type.equals("server") && minecraftRunning()) return new String[] {"refused", "The server is already running"};
		if (type.startsWith("perm:") && permState(d, type.substring(5)).equals("allowed")) return new String[] {"refused", "This device already has that permission"};
		if (type.equals("whitelist")) {
			if (player.isEmpty()) return new String[] {"refused", "The owner's PC doesn't know your Minecraft name yet: start the game once, then ask again"};
			if (whitelisted(player)) return new String[] {"refused", player + " is already on the whitelist"};
		}
		long now = System.currentTimeMillis();
		String key = id + "|" + type;
		Long last = lastRequest.get(key);
		if (last != null && now - last < 60_000) return new String[] {"refused", "Already asked a moment ago: the owner has been told"};
		lastRequest.put(key, now);
		requestState.put(key, "asked"); requestTime.put(key, now); requestReason.remove(key);
		requests.removeIf(o -> id.equals(Json.str(Json.map(o).get("id"))) && type.equals(Json.str(Json.map(o).get("type"))));
		requests.add(m("id", id, "type", type, "who", who(d), "player", player, "server", servers().size() > 1 ? serverName() : "", "server_id", Json.str(server().get("id")),
				"time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), "message", clean(message, 300)));
		while (requests.size() > 12) requests.remove(0);
		requestSeq++;
		log(who(d) + (player.isEmpty() ? "" : " (" + player + ")") + " asked for " + requestWords(type) + (clean(message, 300).isEmpty() ? "" : ": \"" + clean(message, 300) + "\""));
		if (!"false".equals(setting("request_alert", "true"))) { chime(); openPanel(); }
		return new String[] {"ok", "Asked: the owner's PC chimed and shows your request."};
	}

	/** The owner's answer to one request (found by launcher id and kind). Granting does the thing asked for; dismissing
	 *  tells the launcher it was denied, with the owner's reason if one was given. */
	static synchronized String[] answerRequest(String id, String type, boolean grant, String reason) throws IOException {
		Map<String, Object> req = null;
		for (Object o : requests) if (id.equals(Json.str(Json.map(o).get("id"))) && type.equals(Json.str(Json.map(o).get("type")))) req = Json.map(o);
		if (req == null) return new String[] {"refused", "That request is gone"};
		String key = id + "|" + type, keep = SERVER.get();
		String[] r = {"ok", "Dismissed"};
		try {
			SERVER.set(Json.str(req.get("server_id")));
			if (grant) {
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(id));
				String player = Json.str(req.get("player"));
				if (type.equals("server")) {
					r = startServerFully("the owner, for " + who(d));
				} else if (type.startsWith("perm:")) {
					if (d.isEmpty()) return new String[] {"refused", "That launcher is gone"};
					setPermState(d, type.substring(5), "allowed"); saveDevices(devs);
					r = new String[] {"ok", "Allowed " + permName(type.substring(5)) + " for " + who(d)};
				} else if (type.equals("whitelist")) r = setWhitelisted(player, true);
				else {   // unblacklist, unsuspend: whichever ban applies is lifted
					if (!Json.str(d.get("ban")).isEmpty()) { d.remove("ban"); d.remove("ban_reason"); saveDevices(devs); r = new String[] {"ok", who(d) + " is no longer blacklisted or suspended"}; }
					if (!player.isEmpty() && !Json.str(Json.map(players().get(player)).get("ban")).isEmpty()) r = banPlayer(player, "", "");
				}
				if (!r[0].equals("ok")) return r;
			}
			requestState.put(key, grant ? "granted" : "denied"); requestTime.put(key, System.currentTimeMillis());
			if (!grant && !clean(reason, 200).isEmpty()) requestReason.put(key, clean(reason, 200)); else requestReason.remove(key);
			requests.remove(req);
			log("request from " + Json.str(req.get("who")) + " for " + requestWords(type) + ": " + (grant ? "granted (" + r[1] + ")" : "dismissed" + (clean(reason, 200).isEmpty() ? "" : ": \"" + clean(reason, 200) + "\"")));
			return r;
		} finally { SERVER.set(keep); }
	}

	/** What became of this launcher's recent requests, for its next status reply: {kind: asked | granted | denied} and
	 *  the reasons given with dismissals. Forgotten after ten minutes. */
	static Map<String, Object> requestStates(String id, boolean reasons) {
		Map<String, Object> out = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		for (String type : REQUEST_TYPES) {
			String key = id + "|" + type;
			Long at = requestTime.get(key);
			if (at == null || now - at > 600_000 || !requestState.containsKey(key)) continue;
			if (!reasons) out.put(type, requestState.get(key));
			else if (requestReason.containsKey(key)) out.put(type, requestReason.get(key));
		}
		return out;
	}

	/** A short, soft three-note chime (made here, no sound file): E, G sharp, B, each fading out. */
	static void chime() {
		Thread t = new Thread(() -> {
			try {
				float rate = 44100f;
				double[] notes = {659.25, 830.61, 987.77};
				int step = (int) (rate * 0.16), tail = (int) (rate * 0.9);
				double[] mix = new double[step * (notes.length - 1) + tail];
				for (int n = 0; n < notes.length; n++)
					for (int i = 0; i < tail; i++) {
						double time = i / rate, env = Math.min(1, time / 0.008) * Math.exp(-time * 4.2);
						mix[n * step + i] += env * (Math.sin(2 * Math.PI * notes[n] * time) + 0.25 * Math.sin(4 * Math.PI * notes[n] * time));
					}
				byte[] pcm = new byte[mix.length * 2];
				for (int i = 0; i < mix.length; i++) { int v = (int) (mix[i] * 0.22 * 32767); pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8); }
				javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(rate, 16, 1, true, false);
				try (javax.sound.sampled.SourceDataLine line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt)) {
					line.open(fmt); line.start(); line.write(pcm, 0, pcm.length); line.drain();
				}
			} catch (Exception | LinkageError e) { log("no chime: " + e); }
		}, "chime");
		t.setDaemon(true);
		t.start();
	}

	/** Opens Gatekeeper minimized, unless one is open already (an open panel sees the request itself). */
	static void openPanel() {
		if (System.currentTimeMillis() - panelSeen < 8000) return;
		try {
			String java = ProcessHandle.current().info().command().orElse("javaw");
			String jar = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			new ProcessBuilder(java, "-jar", jar, "--panel", "--minimized").start();
		} catch (Exception e) { log("couldn't open Gatekeeper: " + e); }
	}

	// ------------------------------------------------------------------ the server's whitelist

	/** The current server's whitelist, as its own file has it (whitelist.json in its folder: uuid and name each). */
	static List<Object> whitelist() { return Json.list(Json.parse(read(serverDir().resolve("whitelist.json")))); }
	static boolean whitelisted(String name) {
		for (Object o : whitelist()) if (name.equalsIgnoreCase(Json.str(Json.map(o).get("name")))) return true;
		return false;
	}

	/** Puts a player on the current server's whitelist, or takes them off. While the server runs, the server does it
	 *  itself (the console commands "whitelist add" / "whitelist remove": it finds the account and saves its file).
	 *  While it's off, the file is changed here; adding then needs the player's id, which the server's own record of
	 *  accounts it has seen (usercache.json) has for anyone who has joined or tried to. */
	static String[] setWhitelisted(String name, boolean on) {
		if (!name.matches("\\w{1,16}")) return new String[] {"refused", "That isn't a Minecraft name"};
		if (whitelisted(name) == on) return new String[] {"ok", name + (on ? " is already on the whitelist" : " isn't on the whitelist")};
		try {
			if (minecraftRunning()) {
				String[] r = sh().command("whitelist " + (on ? "add " : "remove ") + name);
				if (!r[0].equals("ok")) return r;
				for (int i = 0; i < 12 && whitelisted(name) != on; i++) Thread.sleep(300);
				return whitelisted(name) == on ? new String[] {"ok", (on ? "Added " + name + " to" : "Removed " + name + " from") + " the whitelist of " + serverName()}
						: new String[] {"failed", "The server was asked, but its whitelist hasn't changed" + (on ? " (it may not know an account called " + name + ")" : "")};
			}
			List<Object> wl = whitelist();
			if (on) {
				String uuid = "", proper = name;
				for (Object o : Json.list(Json.parse(read(serverDir().resolve("usercache.json"))))) {
					if (name.equalsIgnoreCase(Json.str(Json.map(o).get("name")))) { uuid = Json.str(Json.map(o).get("uuid")); proper = Json.str(Json.map(o).get("name")); }
				}
				if (uuid.isEmpty()) return new String[] {"refused", "The server is off, and it hasn't seen " + name + " before, so their account id isn't known here: start the server and tick them again"};
				wl.add(m("uuid", uuid, "name", proper));
			} else wl.removeIf(o -> name.equalsIgnoreCase(Json.str(Json.map(o).get("name"))));
			Files.writeString(serverDir().resolve("whitelist.json"), Json.write(wl), StandardCharsets.UTF_8);
			return new String[] {"ok", (on ? "Added " + name + " to" : "Removed " + name + " from") + " the whitelist of " + serverName() + " (the server is off: it reads it when it starts)"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't change the whitelist: " + e.getMessage()}; }
	}

	static String header(HttpExchange x, String name) {
		String v = x.getRequestHeaders().getFirst(name);
		v = v == null ? "" : v.replaceAll("[^\\x20-\\x7E]", "").trim();
		return v.length() > 60 ? v.substring(0, 60) : v;
	}

	/** How a launcher controls the server, for the panel's list. */
	static String accessText(Map<String, Object> d) {
		String k = Json.str(d.get("access")), s = Json.str(d.get("saved")), u = Json.str(d.get("access_user"));
		String has = s.equals("signin") ? "signed in to the panel" + (u.isEmpty() ? "" : " as " + u) : s.equals("key") ? "its own API key" : s.equals("issued") ? "a key issued by Gatekeeper" : "";
		String t = k.isEmpty() ? "Unknown (older launcher)" : k.equals("helper") ? "Through Gatekeeper" + (has.isEmpty() ? "" : " (also saved: " + has + ")")
				: has.isEmpty() ? "None" : Character.toUpperCase(has.charAt(0)) + has.substring(1);
		if (Boolean.TRUE.equals(d.get("clear_access"))) t += "; told to forget what it has saved";
		return t;
	}

	/** The launchers for a list: with how each controls the server in words, without the secrets. */
	static Map<String, Object> listed() {
		scanPlayers();
		Map<String, Object> all = devices();
		for (Object v : all.values()) {
			Map<String, Object> d = Json.map(v);
			d.put("access_text", accessText(d));
			d.put("server", Boolean.TRUE.equals(d.get(serverKey())));   // (as shown: for the server being looked at)
			for (String pn : new String[] {"machine", "mcss", "server", "admin"}) d.put("state_" + pn, permState(d, pn));
			d.put("ban_text", banText(banOf(d), Json.str(d.get("player"))));
			d.put("ban_kind", banOf(d)[0]); d.put("ban_scope", banOf(d)[1]);
		}
		Map<String, String> online = onlineNow();
		for (Map.Entry<String, Object> en : all.entrySet()) {
			Map<String, Object> d = Json.map(en.getValue());
			d.put("now", presence(en.getKey(), d, online));
			d.remove("token_sha256"); d.remove("issued_pending");
		}
		return all;
	}

	/** Takes a launcher's server control away, whichever way it has it: its permissions here (server and MCSS control)
	 *  go off; a key this helper issued is deleted in MCSS, so it stops working; and the launcher is told to forget
	 *  whatever sign-in or key it has saved. A sign-in or key it got elsewhere still exists in MCSS: only removing that
	 *  account or key there blocks it for good, and the message says so. */
	static String[] revokeAccess(Map<String, Object> d) {
		String saved = Json.str(d.get("saved"));
		List<String> did = new ArrayList<>();
		if (Boolean.TRUE.equals(d.get(serverKey())) || Boolean.TRUE.equals(d.get("granted"))) did.add("server and MCSS control switched off here");
		d.put(serverKey(), false); d.put("granted", false);
		d.remove("issued_pending");
		d.put("clear_access", true);
		did.add("the launcher is told to forget what it has saved");
		String more = saved.equals("signin") ? " Its panel account" + (Json.str(d.get("access_user")).isEmpty() ? "" : " (" + Json.str(d.get("access_user")) + ")") + " still exists in MCSS: remove or change it there (Users) to block it for good."
				: saved.equals("key") ? " The API key it typed in still exists in MCSS: delete it there (API Keys) to block it for good." : "";
		return new String[] {"ok", Character.toUpperCase(String.join("; ", did).charAt(0)) + String.join("; ", did).substring(1) + "." + more};
	}

	/** The end of the server's own log file (logs/latest.log), oldest first: how a shutdown went is read from it. */
	static List<Object> serverLog(int lines) {
		String[] all = read(serverDir().resolve("logs").resolve("latest.log")).split("\\R");
		List<Object> out = new ArrayList<>();
		for (int i = Math.max(0, all.length - lines); i < all.length; i++) if (!all[i].isBlank()) out.add(all[i]);
		return out;
	}

	/** Puts this PC to sleep a few seconds from now (so the reply gets out first). Refused while the Minecraft server
	 *  runs: players would be left on a frozen server. MCSS can stay open; it carries on when the PC wakes.
	 *  host.json "sleep_command" replaces the sleep itself (testing). */
	static String[] sleepPc() {
		if (anyServerRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then sleep the machine"};
		if (!sleepSupported()) return new String[] {"refused", notSupported() + ": Gatekeeper doesn't put this machine to sleep" + (STEAMOS ? " (asleep, a Steam Deck can't be woken from another device)" : "")};
		String custom = setting("sleep_command", "");
		List<String> cmd = !custom.isEmpty() ? (WIN ? List.of("cmd", "/c", custom) : List.of("sh", "-c", custom))
				: WIN ? List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
						"Start-Sleep 5; Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)")
				: List.of("sh", "-c", "sleep 5; systemctl suspend");
		try { new ProcessBuilder(cmd).start(); }
		catch (IOException e) { return new String[] {"failed", "Couldn't sleep the PC: " + e.getMessage()}; }
		return new String[] {"ok", "The server machine is going to sleep (in about 5 seconds). Wake server machine wakes it again from the home network."};
	}

	/** The panel: GET /admin/devices, POST /admin/grant {id, granted}, POST /admin/perm {id, perm: mcss|sleep, value},
	 *  POST /admin/forget {id}, GET /admin/log. */
	/** The server a call's query names (?server=<id>, the first if it is there twice), or "". */
	static String serverIn(String query) {
		if (query != null) for (String part : query.split("&")) if (part.startsWith("server=")) return part.substring(7).replaceAll("[^\\w.-]", "");
		return "";
	}
	static void admin(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath();
			String query = x.getRequestURI().getQuery();
			SERVER.set(serverIn(query));
			if (path.equals("/admin/devices")) {
				panelSeen = System.currentTimeMillis();
				Map<String, Object> devs = listed();
				List<Object> asked;
				int seq;
				synchronized (HostHelper.class) { asked = new ArrayList<>(requests); seq = requestSeq; }
				List<Object> names = new ArrayList<>();
				for (Object o : servers()) names.add(m("id", Json.str(Json.map(o).get("id")), "name", Json.str(Json.map(o).get("name"))));
				reply(x, 200, m("servers", names, "server_id", Json.str(server().get("id")), "requests", asked, "request_seq", seq, "devices", devs, "mcss", host.running(), "minecraft", minecraftRunning(), "server_phase", serverPhase(), "mcss_phase", mcssPhase(), "close", host.closeMode(), "port", port(), "bind", setting("bind", ""),
						"has_key", host.hasAccess(), "key_works", !host.hasAccess() ? "" : host.accessState(), "mcss_user", host.user()));
			} else if (path.equals("/admin/revoke")) {
				// Revoke: everything that lets it control the server is taken away.
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(Json.str(body(x).get("id"))));
				if (d.isEmpty()) { reply(x, 404, m("error", "unknown device")); return; }
				String[] r = revokeAccess(d);
				log("removed server access from " + who(d) + ": " + r[1]);
				saveDevices(devs);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/mcss/start") || path.equals("/admin/mcss/close")) {
				// MCSS itself, from the panel on this PC: the same start and close the launchers ask for.
				boolean wasUp = host.running();
				String[] r = path.endsWith("start") ? host.start() : closeMcssFully("true".equals(Json.str(body(x).get("force"))), "the owner");
				if (path.endsWith("start") && !wasUp && r[0].equals("ok")) expect("mcss", "starting", 120_000);
				log((path.endsWith("start") ? "start" : "close") + " MCSS from Gatekeeper: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/ban")) {
				// Blacklist or suspend (kind), or lift it (kind empty): a launcher by its id, or a player on every device.
				Map<String, Object> b = body(x);
				String kind = Json.str(b.get("kind")), reason = clean(Json.str(b.get("reason")), 200), player = Json.str(b.get("player"));
				if (!kind.isEmpty() && !kind.equals("blacklist") && !kind.equals("suspend")) { reply(x, 400, m("error", "unknown kind")); return; }
				String[] r;
				if (!player.isEmpty()) r = banPlayer(player, kind, reason);
				else {
					Map<String, Object> devs = devices();
					Map<String, Object> d = Json.map(devs.get(Json.str(b.get("id"))));
					if (d.isEmpty()) { reply(x, 404, m("error", "unknown device")); return; }
					if (kind.isEmpty()) { d.remove("ban"); d.remove("ban_reason"); } else { d.put("ban", kind); d.put("ban_reason", reason); }
					saveDevices(devs);
					String on = Json.str(d.get("player"));
					if (!kind.isEmpty() && !on.isEmpty() && minecraftRunning()) kick(on, kind.equals("blacklist") ? "Blacklisted" : "Suspended");
					r = new String[] {"ok", who(d) + (kind.isEmpty() ? " is no longer blacklisted or suspended." : " is " + (kind.equals("blacklist") ? "blacklisted" : "suspended") + " (this device only: the server itself can't tell devices apart, so the player can still join from another).")};
				}
				log(r[1] + (reason.isEmpty() ? "" : " Reason: \"" + reason + "\""));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/kick")) {
				Map<String, Object> b = body(x);
				String[] r = kick(Json.str(b.get("player")), Json.str(b.get("reason")));
				log("kick " + Json.str(b.get("player")).replaceAll("[^\\w]", "") + ": " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/request/answer")) {
				Map<String, Object> b = body(x);
				String[] r = answerRequest(Json.str(b.get("id")), Json.str(b.get("type")), Boolean.TRUE.equals(b.get("grant")) || "true".equals(Json.str(b.get("grant"))), Json.str(b.get("reason")));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/whitelist")) {
				Map<String, Object> b = body(x);
				boolean on = Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value")));
				String[] r = setWhitelisted(Json.str(b.get("player")), on);
				log("whitelist " + (on ? "add " : "remove ") + Json.str(b.get("player")).replaceAll("[^\\w]", "") + ": " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/players")) {
				reply(x, 200, m("players", playerList()));
			} else if (path.equals("/admin/player/perm")) {
				Map<String, Object> b = body(x);
				String[] r = playerPerm(Json.str(b.get("player")), Json.str(b.get("perm")), stateOf(b));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/requests/clear")) {
				// Dismiss: each launcher that asked is told its request was denied (it sees it the next time it asks how
				// things are, every few seconds while its window is open).
				synchronized (HostHelper.class) {
					for (Object o : requests) {
						String rk = Json.str(Json.map(o).get("id")) + "|" + Json.str(Json.map(o).get("type"));
						requestState.put(rk, "denied"); requestTime.put(rk, System.currentTimeMillis());
						log("request from " + Json.str(Json.map(o).get("who")) + " for " + requestWords(Json.str(Json.map(o).get("type"))) + ": dismissed");
					}
					requests.clear();
				}
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/server/stop")) {
				String[] r = sh().serverAction(false);
				if (r[0].equals("ok")) expect(serverKey() + "|server", "stopping", 200_000);
				log("stop the Minecraft server from Gatekeeper: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/server/start")) {
				// From the panel's request banner: MCSS first if it isn't running (it needs a moment before it takes the start).
				String[] r;
				r = startServerFully("the owner");
				log("start the Minecraft server from Gatekeeper: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/keytest")) {
				// Troubleshooting: what the host program answers to Gatekeeper's access (never the access itself).
				reply(x, 200, sh().diagnose());
			} else if (path.equals("/admin/signin")) {
				// The owner signs Gatekeeper in to the host program with an account there (typed in Gatekeeper on this
				// PC). What it hands back is kept; the password never is.
				Map<String, Object> b = body(x);
				String[] r = host.signIn(Json.str(b.get("username")).trim(), Json.str(b.get("password")));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/setup/scan")) {
				Map<String, Object> scan = new LinkedHashMap<>(setupScan(Json.str(body(x).get("mcss_exe")).trim()));
				scan.put("share_link", setting("tailscale_share_link", ""));   // (for the pairing)
				scan.put("name", setting("name", ""));
				reply(x, 200, scan);
			} else if (path.equals("/admin/wizard")) {
				reply(x, 200, m("steps", wizardSteps()));
			} else if (path.equals("/admin/update")) {
				if (x.getRequestMethod().equals("POST")) {
					long pid = 0; try { pid = (long) Double.parseDouble(Json.str(body(x).get("panel_pid"))); } catch (NumberFormatException ignored) {}
					String[] r = applyUpdate(pid);
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else {
					String uq = x.getRequestURI().getQuery();
					if (uq != null && (uq.equals("1") || uq.startsWith("1&"))) lookForNewer();   // (?1: look now)
					reply(x, 200, m("own", ownVersion(), "newest", newestKeeper, "outdated", outdated(), "gatehouse", newestHouse, "from", newestKeeperAt, "reached", lookReached));
				}
			} else if (path.equals("/admin/whitelist/on")) {
				String[] r = whitelistOn();
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/checklist")) {
				if (x.getRequestMethod().equals("POST")) {
					Map<String, Object> asked = body(x);
					if (asked.containsKey("name")) {
						// The name launchers list this Gatekeeper under (empty: this machine's own name again).
						String nm = Json.str(asked.get("name")).replaceAll("[\\p{Cntrl}]", " ").trim();
						if (nm.length() > 40) { reply(x, 200, m("result", "refused", "message", "Keep the name to 40 characters or fewer.")); return; }
						if (nm.isEmpty()) cfg.remove("name"); else cfg.put("name", nm);
						try { Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8); }
						catch (IOException e) { reply(x, 200, m("result", "refused", "message", "Couldn't save host.json: " + e.getMessage())); return; }
						log(nm.isEmpty() ? "name in launchers: this machine's own name again" : "name in launchers: " + nm);
						reply(x, 200, m("result", "ok", "message", nm.isEmpty() ? "Launchers list this Gatekeeper under this machine's name again." : "Launchers now list this Gatekeeper as \"" + nm + "\" (they pick it up within a few seconds when open)."));
						return;
					}
					// The invite link players use to reach this machine over Tailscale: one of Tailscale's own, or none.
					String link = Json.str(asked.get("share_link")).trim();
					if (!link.isEmpty() && !link.matches("https://login\\.tailscale\\.com/[A-Za-z0-9/_?=&.%-]+")) { reply(x, 200, m("result", "refused", "message", "That isn't a Tailscale invite link (it starts with https://login.tailscale.com/).")); return; }
					if (link.isEmpty()) cfg.remove("tailscale_share_link"); else cfg.put("tailscale_share_link", link);
					try { Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8); }
					catch (IOException e) { reply(x, 200, m("result", "refused", "message", "Couldn't save host.json: " + e.getMessage())); return; }
					log(link.isEmpty() ? "Tailscale invite link removed from the pairing" : "Tailscale invite link saved for the pairing");
					reply(x, 200, m("result", "ok", "message", link.isEmpty() ? "Removed: pairings no longer carry an invite link." : "Saved: the pairing link and file now carry it."));
				} else reply(x, 200, checklist());
			} else if (path.equals("/admin/setup")) {
				String[] r = setupSave(body(x));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/settings")) {
				// Gatekeeper's own key for the host program, kept on this PC only.
				host.setKey(Json.str(body(x).get("mcss_api_key")).trim());
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/grant") || path.equals("/admin/forget") || path.equals("/admin/perm")) {
				Map<String, Object> b = body(x), devs = devices();
				String id = Json.str(b.get("id"));
				Map<String, Object> d = Json.map(devs.get(id));
				if (d.isEmpty()) { reply(x, 404, m("error", "unknown device")); return; }
				if (path.endsWith("forget")) { devs.remove(id); log("forgot " + who(d)); }
				else if (path.endsWith("perm")) {
					String perm = Json.str(b.get("perm")), st = stateOf(b);
					if (devKey(perm) == null) { reply(x, 400, m("error", "unknown permission")); return; }
					setPermState(d, perm, st);
					log(permName(perm) + " for " + who(d) + ": " + (st.equals("none") ? "not allowed" : st));
				}
				else { boolean g = Boolean.TRUE.equals(b.get("granted")) || "true".equals(Json.str(b.get("granted"))); d.put("granted", g); log((g ? "allowed MCSS control for " : "revoked MCSS control from ") + who(d)); }
				saveDevices(devs);
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/about")) {
				reply(x, 200, m("gatekeeper", notesInside("gatekeeper"), "gatehouse", gatehouseNotes(), "system", systemName()));
			} else if (path.equals("/admin/overview")) {
				// The window's first screen: MCSS on this machine, and each server it runs.
				List<Object> list = new ArrayList<>();
				String keep = SERVER.get();
				try {
					for (Object o : servers()) {
						Map<String, Object> s = Json.map(o);
						SERVER.set(Json.str(s.get("id")));
						boolean on = serverPhase().equals("online");
						list.add(m("id", Json.str(s.get("id")), "name", Json.str(s.get("name")), "running", on, "phase", serverPhase(), "players", on ? onlineBy.getOrDefault(serverKey(), Map.of()).size() : 0,
								"port", Json.str(s.get("minecraft")).replaceAll(".*:", ""), "dir", Json.str(s.get("dir")), "native", isNative(), "gone", goneFromMcss(s)));
					}
				} finally { SERVER.set(keep); }
				reply(x, 200, m("mcss_exe", builtin() ? serversDir() : setting("mcss_exe", ""), "mcss", host.running(), "mcss_phase", mcssPhase(), "has_key", host.hasAccess(), "servers", list, "host", builtin() ? "builtin" : "mcss", "native_ok", nativeOk(), "native_folder", serversDir()));
			} else if (path.equals("/admin/pulls")) {
				reply(x, 200, m("pulls", pullsFor()));
			} else if (path.equals("/admin/log")) {
				reply(x, 200, m("log", logFor(200)));
			} else if (!ServerTools.admin(x, path) && !WebPanel.admin(x, path) && !LocalLink.admin(x, path)) reply(x, 404, m("error", "no such call"));
		} catch (Exception e) {
			try { reply(x, 500, m("error", e.toString())); } catch (IOException ignored) {}
		}
	}

	/** The setup guide's steps for a machine that starts with nothing, in the order to do them, each with how it stands
	 *  now (from the same looks as Host setup, and a few more: is MCSS there, has it a server, does its web API
	 *  answer). Each: key, title, state (ok, todo, warn, info, wait), text, detail, actions ([label, what to do]). */
	static List<Object> wizardSteps() {
		Map<String, Map<String, Object>> have = new LinkedHashMap<>();
		for (Object o : Json.list(checklist().get("steps"))) have.put(Json.str(Json.map(o).get("title")), Json.map(o));
		java.util.function.BiFunction<String, String, String> st = (title, missing) -> have.containsKey(title) ? Json.str(have.get(title).get("state")) : missing;
		java.util.function.Function<String, String> dt = title -> have.containsKey(title) ? Json.str(have.get(title).get("detail")) : "";
		List<Object> out = new ArrayList<>();
		java.util.function.Function<Object[], Map<String, Object>> add = s -> {
			List<Object> acts = new ArrayList<>();
			for (int i = 5; i + 1 < s.length; i += 2) acts.add(List.of(s[i], s[i + 1]));
			Map<String, Object> step = m("key", s[0], "title", s[1], "state", s[2], "text", s[3], "detail", s[4], "actions", acts);
			out.add(step);
			return step;
		};
		Map<String, Object> scan = setupScan("");
		String exe = Json.str(scan.get("mcss_exe"));
		boolean mcss = builtin() ? Files.isDirectory(Path.of(serversDir())) : !exe.isEmpty() && Files.isRegularFile(Path.of(exe));
		int inMcss = Json.list(scan.get("servers")).size();
		boolean api = builtin();
		if (mcss && !builtin()) try {
			HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + "/api/v2").openConnection();
			c.setConnectTimeout(1500); c.setReadTimeout(2000);
			c.getResponseCode(); api = true;   // (any answer at all: it is on)
		} catch (IOException | RuntimeException off) { api = false; }
		boolean ts = st.apply("Tailscale is installed", "todo").equals("ok"), tsOn = st.apply("Tailscale is connected", "wait").equals("ok");

		add.apply(new Object[] {"welcome", "Welcome", "info", "This guide takes a machine from nothing to a Minecraft server your friends can join with the Gatehouse launcher.\n\nThree things are involved. Tailscale is a private network between this machine and the devices you let in, so nothing is opened to the internet." + (builtin() ? " Gatekeeper, this program, runs the Minecraft servers itself on this machine, and stands between them and the launchers: " : " MC Server Soft (MCSS) runs the Minecraft servers. Gatekeeper, this program, sits between them: ") + "it lets the launchers you allow see, start and stop your servers.\n\nEach step says what to do and checks by itself whether it's done. You can leave the guide and come back to it from Options (the wheel at the top right).", ""});
		add.apply(new Object[] {"tailscale", "Install Tailscale", ts ? "ok" : "todo", "Tailscale makes the private network. Download it from its own site, install it, and sign in with an account (Google, Microsoft, GitHub and others work; it's free for personal use)." + (STEAMOS ? "\n\nTailscale doesn't officially support SteamOS: it can be installed (Desktop Mode, Konsole), but a SteamOS update can remove it, and then this step shows as not done again." : "") + "\n\nThis step is done when Tailscale is installed on this machine.", dt.apply("Tailscale is installed"),
				"Open Tailscale's download page", say("open:https://tailscale.com/download", "open:https://tailscale.com/download/linux")});
		add.apply(new Object[] {"tailscale_on", "Sign in to Tailscale", !ts ? "wait" : tsOn ? "ok" : "todo", say("Open Tailscale (its icon is by the clock), sign in, and make sure it says Connected.", "In a terminal, run: sudo tailscale up, and sign in on the page it gives.") + "\n\nThis step is done when this machine has a Tailscale address.", dt.apply("Tailscale is connected")});
		add.apply(new Object[] {"tailscale_stay", "Keep this machine signed in", !tsOn ? "wait" : st.apply("This machine stays signed in to Tailscale", "todo"), "By default Tailscale signs a machine out after some months, and players would lose the server until you signed in again. For a server, turn that off: in Tailscale's admin page, under Machines, open this machine's \u2026 menu and choose Disable key expiry.\n\n" + say("In Tailscale's own menu by the clock, also turn on Run unattended (under Preferences), so players can reach the machine before anyone has signed in to Windows.", "Tailscale's service should also start with the machine, so players can reach it after a restart. Host setup shows whether it does; in a terminal: sudo systemctl enable --now tailscaled"), dt.apply("This machine stays signed in to Tailscale"),
				"Open Tailscale's admin page", "open:https://login.tailscale.com/admin/machines"});
		if (builtin()) add.apply(new Object[] {"mcss", "Install MC Server Soft", "na", "MC Server Soft is a Windows program, and Gatekeeper is set to do without it: on this machine Gatekeeper runs the Minecraft servers itself. There is nothing to install for this step.", noMcssShort().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + noMcssShort().substring(1)});
		else add.apply(new Object[] {"mcss", "Install MC Server Soft", mcss ? "ok" : "todo", "MC Server Soft (MCSS) is the program that runs the Minecraft servers. Download it from its site and unpack it somewhere permanent, for example " + say("C:\\Minecraft\\MCSS", "an MCSS folder in your home folder (this machine needs MCSS's Linux build)") + ". It has no installer: the folder is the program.\n\nIf Gatekeeper doesn't find it by itself, use Server setup and Browse to " + mcssFile() + ".\n\nThis step is done when Gatekeeper knows where " + mcssFile() + " is.", mcss ? exe : "",
				"Open MCSS's site", "open:https://www.mcserversoft.com/", "Server setup\u2026", "setup"});
		if (builtin()) add.apply(new Object[] {"mcss_server", "Make a server", inMcss > 0 ? "ok" : "todo", "Press New server: give it a name, pick its kind (Vanilla is the plain game; Paper takes plugins; Fabric and Forge take mods) and the Minecraft version, and Gatekeeper fetches the server's program from its makers, checks it and sets it up in a folder of its own in " + serversDir() + ". It asks you to agree to Minecraft's EULA, which is yours to do.\n\nA server you already have: put its folder in there instead (or point Server setup at where it is).\n\nThis step is done when there is at least one server folder.", inMcss > 0 ? inMcss + (inMcss == 1 ? " server folder" : " server folders") + " in " + serversDir() : mcss ? "No server folders in " + serversDir() + " yet" : "There's no folder at " + serversDir() + " yet",
				"New server\u2026", "newserver", "Server setup\u2026", "setup"});
		else add.apply(new Object[] {"mcss_server", "Make a server in MCSS", !mcss ? "wait" : inMcss > 0 ? "ok" : "todo", "In MCSS: Add Server, give it a name, pick the server type and Minecraft version (Vanilla is the plain game; Fabric or Forge are for mods), and create it. Start it once in MCSS and accept Minecraft's EULA when asked, so its files are made, then stop it again.\n\nThis step is done when MCSS has at least one server.", inMcss > 0 ? inMcss + (inMcss == 1 ? " server" : " servers") + " in MCSS" : ""});
		if (builtin()) add.apply(new Object[] {"mcss_api", "Turn on MCSS's web API", "na", "Gatekeeper runs the servers itself on this machine, so there is no MCSS web API to turn on.", noMcssShort().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + noMcssShort().substring(1)});
		else add.apply(new Object[] {"mcss_api", "Turn on MCSS's web API", !mcss ? "wait" : api ? "ok" : "todo", "Gatekeeper talks to MCSS through MCSS's own web API, on this machine only. In MCSS: Options, then Web API (or Web Panel), switch it on, and leave the port at 25560. MCSS has to be running for this.\n\nThis step is done when MCSS answers on that port.", api ? "MCSS answers" : mcss ? "No answer on " + setting("mcss_panel", "http://127.0.0.1:25560").replaceFirst("^https?://", "") + " (is MCSS running?)" : ""});
		String addr = st.apply("Gatekeeper listens on the Tailscale address", "todo"), chosen = st.apply("Servers are chosen", "todo");
		add.apply(new Object[] {"setup", "Choose the servers", inMcss == 0 || !tsOn ? "wait" : addr.equals("ok") && chosen.equals("ok") ? "ok" : "todo", "Server setup tells Gatekeeper where MCSS is, which address to listen on (this machine's Tailscale address, filled in for you), and which of MCSS's servers players should see: tick them and Save.\n\nThis step is done when Gatekeeper listens on the Tailscale address and at least one server is chosen.", dt.apply("Servers are chosen").isEmpty() ? dt.apply("Gatekeeper listens on the Tailscale address") : dt.apply("Servers are chosen"),
				"Server setup\u2026", "setup"});
		if (builtin()) {
			add.apply(new Object[] {"mcss_key", "Let Gatekeeper use MCSS", "na", "Gatekeeper runs the servers itself on this machine, so there is nothing to sign in to.", noMcssShort().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + noMcssShort().substring(1)});
			add.apply(new Object[] {"eula", "Accept Minecraft's EULA", !chosen.equals("ok") ? "wait" : st.apply("Each server can be started", "todo"), "Minecraft's server won't run until you have accepted Minecraft's End User Licence Agreement, and that is yours to do: Gatekeeper never accepts it for you.\n\nA server made with New server has it already, because you ticked the box there. For one you brought yourself: read the EULA, then tick the box under Manage, Settings (or put the line eula=true in eula.txt in the server's folder).\n\nThis step is done when every chosen server has its program and its EULA accepted.", dt.apply("Each server can be started"),
					"Open Minecraft's EULA", "open:https://aka.ms/MinecraftEULA"});
		} else add.apply(new Object[] {"mcss_key", "Let Gatekeeper use MCSS", !chosen.equals("ok") || !api ? "wait" : st.apply("Gatekeeper can use MCSS", "todo"), "Gatekeeper needs MCSS's permission to start and stop servers. Go to Servers, open one of your servers, and on its Server Status tab choose Sign in to MCSS. You sign in once with your MCSS web panel user; Gatekeeper then makes a key of its own and your password isn't kept.\n\nThis step is done when Gatekeeper has a key that MCSS accepts.", dt.apply("Gatekeeper can use MCSS"),
				"Go to Servers", "servers"});
		if (have.containsKey("Each server has its own port") || have.containsKey("Gatekeeper has each server's current port")) {
			String ports = st.apply("Each server has its own port", "ok"), moved = st.apply("Gatekeeper has each server's current port", "ok");
			add.apply(new Object[] {"ports", "Give each server its own port", ports.equals("ok") && moved.equals("ok") ? "ok" : "warn", (have.containsKey("Each server has its own port") && !ports.equals("ok") ? Json.str(have.get("Each server has its own port").get("how")) + "\n\n" : "") + (!moved.equals("ok") ? Json.str(have.get("Gatekeeper has each server's current port").get("how")) + "\n\n" : "") + "Two servers on one machine can't both use the same port at once. This step is done when every chosen server has a port of its own and Gatekeeper knows it.",
					!ports.equals("ok") ? dt.apply("Each server has its own port") : dt.apply("Gatekeeper has each server's current port"), "Server setup\u2026", "setup"});
		}
		add.apply(new Object[] {"whitelist", "Turn the whitelist on", !chosen.equals("ok") ? "wait" : st.apply("Only whitelisted players can join", "todo"), "With the whitelist on, only players you have allowed can join a server, even if someone else gets onto your Tailscale network. Gatekeeper adds a player to it when you allow them in Access Manager.\n\nHost setup has a button that turns it on for you. This step is done when every chosen server has its whitelist on.", dt.apply("Only whitelisted players can join"),
				"Go to Host setup", "hostsetup"});
		add.apply(new Object[] {"invite", "Make an invite for players", !tsOn ? "wait" : st.apply("Players have a way into Tailscale", "todo"), "A player's device has to be able to reach this machine over Tailscale. Share just this machine with them: in Tailscale's admin page, under Machines, open this machine's \u2026 menu, choose Share, and make a reusable invite link. That gives access to this one machine, not the rest of your network.\n\nPaste the link into Host setup (Tailscale invite link for players) and Save. It then travels with the pairing you hand out.\n\nThis step is done when an invite link is saved.", dt.apply("Players have a way into Tailscale"),
				"Open Tailscale's admin page", "open:https://login.tailscale.com/admin/machines", "Go to Host setup", "hostsetup"});
		add.apply(new Object[] {"pairing", "Invite a player", !addr.equals("ok") ? "wait" : st.apply("A launcher has reached this Gatekeeper", "todo"), "Share pairing gives you a link and a small file. Send either to a player. In their Gatehouse launcher they press Add Gatekeeper and paste it; Gatehouse then walks them through Tailscale and your invite.\n\nA new launcher can do nothing until you allow it: it appears under Servers, in a server's Access Manager, where you choose what it may do and whitelist the player.\n\nThis step is done when a launcher has reached this Gatekeeper. If one can't, Windows Firewall has to allow Java for Gatekeeper on private networks.", dt.apply("A launcher has reached this Gatekeeper"),
				"Share pairing\u2026", "pairing"});
		boolean all = true;
		for (Object o : out) { String s = Json.str(Json.map(o).get("state")); if (s.equals("todo") || s.equals("wait")) all = false; }
		add.apply(new Object[] {"done", all ? "All set" : "Finishing", "info", all ? "Everything a server needs is in place. Players you have paired and allowed can start the server from Gatehouse and play.\n\nGatekeeper keeps itself up to date (it says so at the top of Servers when a newer one is out), and Host setup keeps watching these same things. This guide stays in Options." : "Some steps above aren't done yet: the ones marked in red. You can finish them in any order that their own text allows, and this guide stays in Options.", ""});
		return out;
	}

	/** What Options > Uninstall runs: Gatekeeper's own install script (carried inside this program, written out beside
	 *  its settings as install-keeper.ps1), with -Uninstall, in a PowerShell window of its own that stays open until
	 *  Enter is pressed. Null when the script isn't in this program. */
	static List<String> uninstallCommand(Path keeperDir, boolean purge) throws IOException {
		if (!WIN) {
			// Linux and SteamOS: install-keeper.sh, run here (no window of its own; what it says is shown in Options).
			Path sh = dir.resolve("install-keeper.sh");
			try (InputStream in = HostHelper.class.getResourceAsStream("/install-keeper.sh")) { if (in != null) Files.write(sh, in.readAllBytes()); }
			if (!Files.isRegularFile(sh)) return null;
			List<String> cmd = new ArrayList<>(List.of("bash", sh.toString(), "--uninstall", "--yes", "--from-panel", "--dir=" + keeperDir, "--unit=" + setting("unit", "gatehouse-keeper")));
			if (purge) cmd.add("--purge");
			return cmd;
		}
		Path script = dir.resolve("install-keeper.ps1");
		try (InputStream in = HostHelper.class.getResourceAsStream("/install-keeper.ps1")) { if (in != null) Files.write(script, in.readAllBytes()); }
		if (!Files.isRegularFile(script)) return null;
		String q = "'";
		String ps = "& " + q + script.toString().replace("'", "''") + q + " -Uninstall -Yes" + (purge ? " -Purge" : "") + " -Dir " + q + keeperDir.toString().replace("'", "''") + q + " -TaskName " + q + setting("task", "Gatehouse Keeper").replace("'", "''") + q
				+ "; Write-Host ''; Read-Host 'Press Enter to close'";
		return List.of("cmd.exe", "/c", "start", "Removing Gatekeeper", "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
	}

	// ------------------------------------------------------------------ the panel

	/** The server Gatekeeper is looking at (its pick, when the machine has more than one). */
	static volatile String panelServer = "";
	/** What the window's icon shows as waiting: launchers' requests (a red circle with how many), and an update (a green one). */
	static volatile int iconMessages = 0;
	static volatile boolean iconUpdate = false;
	/** A panel that is as wide as the window it scrolls in, never wider: what doesn't fit is shortened, not pushed off the edge. */
	static final class FitWidth extends JPanel implements javax.swing.Scrollable {
		FitWidth(java.awt.LayoutManager l) { super(l); }
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
		@Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return 120; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
	static String call(String method, String path, String body) throws IOException {
		if (!panelServer.isEmpty() && !path.contains("server=")) path += (path.contains("?") ? "&" : "?") + "server=" + panelServer;
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + path).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(5000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	static void panel() throws Exception {
		if (GraphicsEnvironment.isHeadless()) { System.out.println(call("GET", "/admin/devices", null)); return; }
		SwingUtilities.invokeAndWait(() -> {
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = new JFrame("Gatekeeper");
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 10));
			p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
			JLabel head = new JLabel("Gatekeeper");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
			JLabel state = new JLabel(" ");
			// MCSS itself: start it, or close it (how follows MCSS's own X-button setting, as for the launchers).
			JButton mcssRun = new JButton("Start MCSS");
			mcssRun.setToolTipText("Starts MCSS on this machine, or closes it (only once the Minecraft server is stopped).");
			boolean[] mcssUp = {false}; String[] mcssClose = {"close"};
			JPanel stateRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
			stateRow.add(mcssRun); stateRow.add(state);
			// A launcher asked for the server: a banner here, and the taskbar button flashes until it's looked at.
			// One line for each thing a launcher is asking for, each with its own answer: grant it, or dismiss it (with a
			// reason for the launcher to show, if you like).
			JPanel reqRow = new JPanel();
			reqRow.setLayout(new javax.swing.BoxLayout(reqRow, javax.swing.BoxLayout.Y_AXIS));
			reqRow.setVisible(false);
			String[] reqShown = {""};
			Runnable[] refreshLater = {() -> {}};
			int[] seqSeen = {-1};
			// With more than one server on this machine: which one the panel is looking at (server control, the
			// Minecraft server's status and its Start/Stop are per server; the rest is about the machine).
			javax.swing.JComboBox<String> serverPick = new javax.swing.JComboBox<>();
			List<String> serverIds = new ArrayList<>();
			JPanel pickRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			pickRow.add(new JLabel("Server:")); pickRow.add(serverPick);
			pickRow.setVisible(false);
			// (the wheel: Options)
			JButton gear = new JButton(dev.gatehouse.look.SteamLook.gear(18));
			gear.setToolTipText("Options");
			JPanel headRight = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			headRight.add(pickRow); headRight.add(gear);
			JPanel headLine = new JPanel(new BorderLayout()); headLine.add(head, BorderLayout.WEST); headLine.add(headRight, BorderLayout.EAST);
			JPanel headRows = new JPanel(new BorderLayout(0, 6)); headRows.add(headLine, BorderLayout.NORTH); headRows.add(reqRow, BorderLayout.SOUTH);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(headRows, BorderLayout.NORTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Player", "Tailscale device", "System", "First seen", "Last seen", "Server control", "MCSS control", "Machine wake/sleep", "Gatekeeper control", "Controls the server", "Status", "Now"};
			DefaultTableModel model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
			JTable table = new JTable(model);
			table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			table.setRowHeight(24);
			List<String> ids = new ArrayList<>();
			JTextArea log = new JTextArea(8, 80);
			log.setEditable(false);
			log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JPanel mid = new JPanel(new BorderLayout(0, 8));
			JScrollPane ts = new JScrollPane(table); ts.setPreferredSize(new Dimension(1180, 200));
			mid.add(ts, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			JButton serverB = new JButton("Allow server control"), mcssB = new JButton("Allow MCSS control"), sleepB = new JButton("Allow machine wake/sleep"), adminB = new JButton("Allow Gatekeeper control"), forget = new JButton("Forget"), refreshB = new JButton("Refresh");
			serverB.setToolTipText("Server control: the selected launcher may start and stop this Minecraft server (through Gatekeeper; it needs no MCSS account).");
			mcssB.setToolTipText(builtin() ? "MCSS control: " + noMcssShort() + ". Use Server control instead." : "MCSS control: the selected launcher may start and close MCSS itself. It includes server control.");
			if (builtin()) { mcssB.setEnabled(false); mcssB.setText("MCSS control: " + noMcssShort()); }
			sleepB.setToolTipText(sleepSupported() ? "Machine wake/sleep: the selected launcher may wake this machine and put it to sleep (sleep only while the Minecraft server is stopped)." : "Machine wake/sleep: " + notSupported().toLowerCase(java.util.Locale.ROOT).replace(systemName().toLowerCase(java.util.Locale.ROOT), systemName()) + ".");
			if (!sleepSupported()) { sleepB.setEnabled(false); sleepB.setText("Machine wake/sleep: " + notSupported().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1)); }
			forget.setToolTipText("Takes the selected launcher off this list, with everything it was allowed. It can introduce itself again later and starts with nothing.");
			refreshB.setToolTipText("Reads the list again now (it also refreshes by itself every few seconds).");
			adminB.setToolTipText("Gatekeeper control lets that launcher manage the others' permissions from its Server config tab, as this panel does (not the MCSS key, and not Gatekeeper control itself).");
			for (JButton b : new JButton[] {serverB, mcssB, sleepB, adminB, forget, refreshB}) buttons.add(b);
			Map<String, boolean[]> perms = new java.util.HashMap<>();   // id -> {mcss, sleep, server}, from the last refresh
			// Gatekeeper's own MCSS key: needed for "server control" (it starts and stops the server for allowed launchers).
			JLabel keyState = new JLabel(" ");
			JButton keyB = new JButton("Set MCSS key\u2026");
			JButton signB = new JButton("Sign in to MCSS\u2026");
			JPanel keyRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			keyRow.add(signB); keyRow.add(keyB); keyRow.add(keyState);
			if (builtin()) { signB.setEnabled(false); keyB.setEnabled(false); signB.setText("Sign in to MCSS: " + noMcssShort()); keyB.setText("Set MCSS key: " + noMcssShort()); mcssRun.setEnabled(false); mcssRun.setText("Start MCSS: " + noMcssShort()); }
			// For the selected launcher: an MCSS key of its own, made and handed over by Gatekeeper; and taking away
			// whatever lets it control the server.
			JButton revokeB = new JButton("Remove server access");
			revokeB.setToolTipText("Switches off the selected launcher's server and MCSS control, and tells it to forget any sign-in or key it has saved.");
			buttons.add(revokeB);
			java.util.function.BiConsumer<String, String> ask = (path, confirm) -> {
				int row = table.getSelectedRow();
				if (row < 0) { JOptionPane.showMessageDialog(f, "Pick a launcher in the list first."); return; }
				String id = ids.get(row);
				if (JOptionPane.showConfirmDialog(f, "<html><div style='width:380px'>" + confirm + "</div></html>", "Gatekeeper", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", path, "{\"id\":\"" + id + "\"}"))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
					String m2 = "<html><div style='width:420px'>" + msg.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>";
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, m2, "Gatekeeper", JOptionPane.PLAIN_MESSAGE));
				}, "ask").start();
			};
			revokeB.addActionListener(e -> ask.accept("/admin/revoke", "Remove this launcher's server access? Its server and MCSS control are switched off, and it's told to forget any sign-in or key it has saved."));
			JPanel south = new JPanel(new BorderLayout(0, 6));
			south.add(buttons, BorderLayout.NORTH);
			mid.add(south, BorderLayout.SOUTH);
			// Players: the same permissions, per player. A player is tied to a launcher when the server's log shows them
			// joining from it; allowing a player something sets it on all their launchers, and on ones they join from later.
			String[] pcols = {"Player", "Whitelisted", "Launchers", "Last joined", "Server control", "MCSS control", "Machine wake/sleep", "Gatekeeper control", "Status", "Now"};
			// The Whitelisted column is a tick-box: ticking or unticking a player changes the server's whitelist.
			boolean[] filling = {false};
			DefaultTableModel pmodel = new DefaultTableModel(pcols, 0) {
				@Override public boolean isCellEditable(int r, int c) { return c == 1; }
				@Override public Class<?> getColumnClass(int c) { return c == 1 ? Boolean.class : Object.class; }
			};
			JTable ptable = new JTable(pmodel);
			ptable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			ptable.setRowHeight(24);
			List<String> pnames = new ArrayList<>();
			Map<String, boolean[]> pallow = new java.util.HashMap<>();   // player -> allowed {server, mcss, sleep, admin}
			JButton[] pB = {new JButton("Allow server control"), new JButton("Allow MCSS control"), new JButton("Allow machine wake/sleep"), new JButton("Allow Gatekeeper control")};
			JPanel pbuttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			for (JButton b : pB) pbuttons.add(b);
			JLabel pnote = new JLabel("<html><div style='width:900px'>A player appears once they've joined the server from a device with the launcher (matched by its Tailscale address), or when a launcher says who last played on it (\"not confirmed\" until they join). Click a permission to set it: white is not allowed, green allowed, red denied. What you set for a player is set on all their confirmed launchers (and on launchers they join from later), except a launcher that is itself Denied that thing. The Whitelisted box puts a player on the server's whitelist or takes them off (a name that is \"not confirmed\" is only what a launcher said, so check it before ticking). The Launchers tab still changes one launcher at a time.</div></html>");
			pnote.setForeground(new Color(0x8F98A0));
			JPanel psouth = new JPanel(new BorderLayout(0, 6)); psouth.add(pbuttons, BorderLayout.NORTH); psouth.add(pnote, BorderLayout.SOUTH);
			JPanel playersTab = new JPanel(new BorderLayout(0, 8));
			playersTab.add(new JScrollPane(ptable), BorderLayout.CENTER); playersTab.add(psouth, BorderLayout.SOUTH);
			javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
			// Access: who may do what, one launcher (device) at a time or a player with all their devices.
			javax.swing.JRadioButton byDevice = new javax.swing.JRadioButton("By launcher (one device)", true), byPlayer = new javax.swing.JRadioButton("By player (all their devices)");
			javax.swing.ButtonGroup how = new javax.swing.ButtonGroup(); how.add(byDevice); how.add(byPlayer);
			JPanel pick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
			JLabel pickHead = new JLabel("Access Manager:"); pickHead.setFont(pickHead.getFont().deriveFont(Font.BOLD));
			pick.add(pickHead); pick.add(byDevice); pick.add(byPlayer);
			java.awt.CardLayout cards = new java.awt.CardLayout();
			JPanel deck = new JPanel(cards);
			deck.add(mid, "device"); deck.add(playersTab, "player");
			byDevice.addActionListener(e -> cards.show(deck, "device"));
			byPlayer.addActionListener(e -> cards.show(deck, "player"));
			JPanel accessTab = new JPanel(new BorderLayout(0, 4));
			accessTab.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
			accessTab.add(pick, BorderLayout.NORTH); accessTab.add(deck, BorderLayout.CENTER);
			// Status: Gatekeeper, MCSS, the Minecraft server, and everything that was asked of Gatekeeper.
			JLabel mcssState = new JLabel(" "), serverState = new JLabel(" ");
			JButton serverRun = new JButton("Start server");
			serverRun.setToolTipText("Starts the Minecraft server (starting MCSS first if it isn't running), or stops it: the world is saved first.");
			boolean[] serverUpNow = {false};
			JPanel statusTop = new JPanel();
			statusTop.setLayout(new javax.swing.BoxLayout(statusTop, javax.swing.BoxLayout.Y_AXIS));
			java.util.function.BiConsumer<String, javax.swing.JComponent[]> section = (title, rows) -> {
				JLabel h = new JLabel(title);
				h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
				h.setBorder(BorderFactory.createEmptyBorder(statusTop.getComponentCount() == 0 ? 0 : 10, 0, 2, 0));
				h.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); statusTop.add(h);
				for (javax.swing.JComponent c : rows) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); statusTop.add(c); }
			};
			JPanel mcssRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)); mcssRow.add(mcssRun); mcssRow.add(mcssState);
			JPanel serverRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)); serverRow.add(serverRun); serverRow.add(serverState);
			JPanel helperRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)); helperRow.add(state);
			JButton setupB = new JButton("Server setup\u2026"), pairB = new JButton("Share pairing\u2026");
			setupB.setToolTipText("Which MCSS and which Minecraft servers on this machine Gatekeeper looks after, and this machine's Tailscale address.");
			pairB.setToolTipText("Ways to connect a launcher to this Gatekeeper: its address, a pairing link to paste, or a pairing file to send.");
			JLabel pairNote = new JLabel(" ");
			pairNote.setForeground(new Color(0x8F98A0));
			JPanel setupRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)); setupRow.add(setupB); setupRow.add(pairB); setupRow.add(pairNote);
			// Share pairing: what to give someone so their launcher can find this Gatekeeper. The address by itself, a
			// link that carries its name too (pasted into the launcher's Add Gatekeeper), or the same as a small file to
			// send. None of them lets a launcher do anything: it still introduces itself and waits to be allowed.
			String[] shareLink = {"", ""};   // (the invite link, and this Gatekeeper's name, as the running Gatekeeper has them)
			pairB.addActionListener(e -> {
				String addr, name;
				try {
					Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup/scan", "{}")));
					if (Json.str(r.get("bind")).isEmpty()) { pairNote.setText("No Tailscale address yet: use Server setup first."); return; }
					addr = Json.str(r.get("bind")) + ":" + Json.str(r.get("port"));
					shareLink[0] = Json.str(r.get("share_link"));
					shareLink[1] = Json.str(r.get("name"));
				} catch (Exception ex) { pairNote.setText("Couldn't ask Gatekeeper: is it running?"); return; }
				name = shareLink[1];
				if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ex) { name = "Gatekeeper"; }
				// (with the Tailscale invite link from Host setup, when there is one: "ts" in the link, "tailscale_share" in the file)
				String share = shareLink[0];
				String link = "gatehouse://pair/" + addr + "?name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + (share.isEmpty() ? "" : "&ts=" + java.net.URLEncoder.encode(share, StandardCharsets.UTF_8)), nm = name;
				javax.swing.JTextField addrF = new javax.swing.JTextField(addr, 34), linkF = new javax.swing.JTextField(link, 34);
				addrF.setEditable(false); linkF.setEditable(false);
				JButton copyA = new JButton("Copy"), copyL = new JButton("Copy"), save = new JButton("Save pairing file\u2026");
				JLabel said = new JLabel(" ");
				said.setForeground(new Color(0x8BD46E));
				java.util.function.BiConsumer<String, String> clip = (text, what) -> { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null); said.setText(what + " copied."); };
				copyA.addActionListener(ev -> clip.accept(addr, "Address"));
				copyL.addActionListener(ev -> clip.accept(link, "Pairing link"));
				save.setToolTipText("A small file to send to someone: they open it from Add Gatekeeper in their launcher");
				save.addActionListener(ev -> {
					javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
					fc.setSelectedFile(new java.io.File(nm.replaceAll("[^A-Za-z0-9 ._-]", "").trim() + ".gatekeeper"));
					if (fc.showSaveDialog(f) != javax.swing.JFileChooser.APPROVE_OPTION) return;
					try { Files.writeString(fc.getSelectedFile().toPath(), Json.write(share.isEmpty() ? m("gatehouse_pairing", 1, "name", nm, "address", addr) : m("gatehouse_pairing", 1, "name", nm, "address", addr, "tailscale_share", share)) + "\n", StandardCharsets.UTF_8); said.setText("Saved " + fc.getSelectedFile().getName() + "."); }
					catch (IOException ex) { said.setForeground(new Color(0xFF7B72)); said.setText("Couldn't save: " + ex.getMessage()); }
				});
				JPanel form = new JPanel();
				form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
				java.util.function.Function<String, JLabel> lab = s -> { JLabel l = new JLabel(s); l.setFont(l.getFont().deriveFont(Font.BOLD)); l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0)); return l; };
				JPanel ra = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); ra.add(addrF); ra.add(copyA);
				JPanel rl = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); rl.add(linkF); rl.add(copyL);
				JPanel rs = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); rs.add(save); rs.add(said);
				JLabel about = new JLabel("<html><div style='width:460px'>Give one of these to someone whose launcher should connect to this Gatekeeper (\"" + nm.replace("<", "&lt;") + "\"). In their Gatehouse launcher: Add Gatekeeper, then paste it or open the file. "
						+ (share.isEmpty() ? "Their device has to be able to reach this machine over Tailscale (Host setup has a place for the invite link that lets it)" : "The link and the file carry your Tailscale invite, so their device can reach this machine")
						+ ", and it can do nothing here until you allow it in Access Manager.</div></html>");
				for (javax.swing.JComponent c : new javax.swing.JComponent[] {about, lab.apply("Address"), ra, lab.apply("Pairing link (carries this Gatekeeper's name)"), rl, lab.apply("Pairing file"), rs}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); }
				String shot = System.getenv("OJ_PANEL_PNG");
				if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the form instead of the dialog)
					JFrame tf = new JFrame(); form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); tf.setContentPane(form); tf.pack();
					java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(form.getWidth(), form.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D g = img.createGraphics(); form.paint(g); g.dispose();
					try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + "-pairing.png")); } catch (IOException ex) { ex.printStackTrace(); }
					tf.dispose();
					return;
				}
				JOptionPane.showMessageDialog(f, form, "Share pairing", JOptionPane.PLAIN_MESSAGE);
			});
			// Server setup: MCSS's program, this machine's Tailscale address, and a tick for each Minecraft server MCSS has.
			Runnable setup = () -> {
				javax.swing.JTextField exeF = new javax.swing.JTextField(36);
				javax.swing.JComboBox<String> addrF = new javax.swing.JComboBox<>();
				addrF.setEditable(true);
				JPanel list = new JPanel();
				list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
				JLabel problem = new JLabel(" ");
				problem.setForeground(new Color(0xFF7B72));
				List<Object[]> rows = new ArrayList<>();   // {its tick-box, the server as found}
				Runnable scan = () -> {
					try {
						Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup/scan", Json.write(m("mcss_exe", exeF.getText().trim())))));
						exeF.setText(Json.str(r.get("mcss_exe")));
						list.removeAll(); rows.clear();
						boolean any = false;
						for (Object o : Json.list(r.get("servers"))) if (Boolean.TRUE.equals(Json.map(o).get("tracked"))) any = true;
						for (Object o : Json.list(r.get("servers"))) {
							Map<String, Object> s = Json.map(o);
							javax.swing.JCheckBox cb = new javax.swing.JCheckBox(Json.str(s.get("name")) + "   (port " + Json.str(s.get("minecraft")).replaceAll(".*:", "") + ", " + Json.str(s.get("dir")) + ")", Boolean.TRUE.equals(s.get("tracked")) || !any);
							rows.add(new Object[] {cb, s}); list.add(cb);
						}
						problem.setText(Json.str(r.get("problem")).isEmpty() ? " " : Json.str(r.get("problem")));
						if (addrF.getItemCount() == 0) {
							if (!Json.str(r.get("bind")).isEmpty()) addrF.addItem(Json.str(r.get("bind")));
							for (Object a : Json.list(r.get("addresses"))) if (!Json.str(a).equals(Json.str(r.get("bind")))) addrF.addItem(Json.str(a));
							if (addrF.getItemCount() == 0) problem.setText((problem.getText().isBlank() ? "" : problem.getText() + " ") + "Tailscale isn't running on this machine: start it, then look again (or type its 100.x.y.z address).");
						}
						list.revalidate(); list.repaint();
						java.awt.Window w = SwingUtilities.getWindowAncestor(list);
						if (w != null) w.pack();
					} catch (Exception ex) { problem.setText("Couldn't ask Gatekeeper: is it running? " + say("(Task Scheduler, Gatehouse Keeper)", "(systemctl --user status gatehouse-keeper)")); }
				};
				JButton browse = new JButton("Browse\u2026"), again = new JButton("Look again");
				browse.addActionListener(ev -> {
					javax.swing.JFileChooser fc = new javax.swing.JFileChooser(!exeF.getText().isBlank() ? exeF.getText() : WIN ? "C:\\" : System.getProperty("user.home", "/"));
					if (builtin()) fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
					else if (WIN) fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MCSS (mcss.exe)", "exe"));
					if (fc.showOpenDialog(f) == javax.swing.JFileChooser.APPROVE_OPTION) { exeF.setText(fc.getSelectedFile().getAbsolutePath()); scan.run(); }
				});
				again.addActionListener(ev -> scan.run());
				JPanel form = new JPanel();
				form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
				JPanel exeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); exeRow.add(exeF); exeRow.add(browse); exeRow.add(again);
				JPanel addrRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); addrRow.add(addrF);
				java.util.function.Function<String, JLabel> subHead = hs -> { JLabel l = new JLabel(hs); l.setFont(l.getFont().deriveFont(Font.BOLD)); l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0)); return l; };
				for (javax.swing.JComponent c : new javax.swing.JComponent[] {subHead.apply(builtin() ? "The folder your Minecraft servers are in (one folder each)" : "MCSS on this machine (" + mcssFile() + ")"), exeRow,
						subHead.apply("This machine's Tailscale address (launchers reach Gatekeeper on it)"), addrRow,
						subHead.apply(builtin() ? "Minecraft servers in that folder (tick the ones Gatekeeper looks after)" : "Minecraft servers in MCSS (tick the ones Gatekeeper looks after)"), list, problem}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); }
				scan.run();
				String shot = System.getenv("OJ_PANEL_PNG");
				if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the form instead of the dialog)
					JFrame t = new JFrame(); form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); t.setContentPane(form); t.pack();
					java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(form.getWidth(), form.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D g = img.createGraphics(); form.paint(g); g.dispose();
					try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + "-setup.png")); } catch (IOException ex) { ex.printStackTrace(); }
					t.dispose();
					return;
				}
				if (JOptionPane.showConfirmDialog(f, form, "Server setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				List<Object> picked = new ArrayList<>();
				for (Object[] row : rows) if (((javax.swing.JCheckBox) row[0]).isSelected()) picked.add(row[1]);
				String msg;
				try {
					Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup", Json.write(m("mcss_exe", exeF.getText().trim(), "bind", Json.str(addrF.getEditor().getItem()).trim(), "servers", picked)))));
					msg = Json.str(r.get("message"));
				} catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
				JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + msg + "</div></html>", "Server setup", JOptionPane.PLAIN_MESSAGE);
			};
			setupB.addActionListener(e -> setup.run());
			section.accept("Gatekeeper", new javax.swing.JComponent[] {helperRow, setupRow});
			section.accept(builtin() ? "MCSS (the server software): " + noMcssShort() : "MCSS (the server software)", new javax.swing.JComponent[] {mcssRow, keyRow});
			section.accept("Minecraft server", new javax.swing.JComponent[] {serverRow});
			JPanel activity = new JPanel(new BorderLayout(0, 4));
			JLabel actHead = new JLabel("Activity"); actHead.setFont(actHead.getFont().deriveFont(Font.BOLD, 14f));
			activity.add(actHead, BorderLayout.NORTH);
			log.setRows(14);
			activity.add(new JScrollPane(log), BorderLayout.CENTER);
			JPanel statusTab = new JPanel(new BorderLayout(0, 10));
			statusTab.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
			statusTab.add(statusTop, BorderLayout.NORTH); statusTab.add(activity, BorderLayout.CENTER);
			// Updates Pulled: each update a launcher fetched from this machine: who, when, what, and how big.
			DefaultTableModel pullModel = new DefaultTableModel(new String[] {"When", "Launcher", "Player", "System", "Update", "Files", "Mods", "Size", "From this machine"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
			JTable pullTable = new JTable(pullModel);
			pullTable.setRowHeight(26);
			pullTable.getColumnModel().getColumn(0).setPreferredWidth(150); pullTable.getColumnModel().getColumn(4).setPreferredWidth(220);
			javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
			right.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
			for (int ci = 5; ci <= 8; ci++) pullTable.getColumnModel().getColumn(ci).setCellRenderer(right);
			JLabel[] pullTiles = {new JLabel("0"), new JLabel("0 MB"), new JLabel("0 MB"), new JLabel("\u2013")};
			String[] pullTitles = {"Downloads recorded", "Total size", "Last 7 days", "Latest"};
			JPanel tiles = new JPanel(new java.awt.GridLayout(1, 4, 10, 0));
			for (int ti = 0; ti < 4; ti++) {
				JPanel tile = new JPanel(new BorderLayout(0, 2));
				tile.setBackground(new Color(0x141D29)); tile.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
				JLabel cap = new JLabel(pullTitles[ti]); cap.setForeground(new Color(0x8F98A0));
				pullTiles[ti].setFont(pullTiles[ti].getFont().deriveFont(Font.BOLD, ti == 3 ? 15f : 22f));
				tile.add(cap, BorderLayout.NORTH); tile.add(pullTiles[ti], BorderLayout.CENTER);
				tiles.add(tile);
			}
			JLabel pullNote = new JLabel("<html><div style='width:900px'>Each time a launcher fetches an update from this machine (not from GitHub), it says so here. Size is the whole download for that update; the part that came from this machine is in the last column (mods the pack only lists come from Modrinth).</div></html>");
			pullNote.setForeground(new Color(0x8F98A0));
			JPanel pullsTab = new JPanel(new BorderLayout(0, 10));
			pullsTab.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 6));
			pullsTab.add(tiles, BorderLayout.NORTH); pullsTab.add(new JScrollPane(pullTable), BorderLayout.CENTER); pullsTab.add(pullNote, BorderLayout.SOUTH);
			java.util.function.Function<Long, String> mb = bytes -> bytes < 1_000_000 ? Math.max(bytes == 0 ? 0 : 1, Math.round(bytes / 1000.0)) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6);
			java.util.function.Consumer<List<Object>> fillPulls = all -> {
						pullModel.setRowCount(0);
						long total = 0, week = 0;
						String cutoff = LocalDateTime.now().minusDays(7).format(TIME);
						for (Object o : all) {
							Map<String, Object> r = Json.map(o);
							long bytes = 0, local = 0;
							try { bytes = (long) Double.parseDouble(Json.str(r.get("bytes"))); local = (long) Double.parseDouble(Json.str(r.get("local_bytes"))); } catch (NumberFormatException ignored) {}
							total += bytes; if (Json.str(r.get("time")).compareTo(cutoff) >= 0) week += bytes;
							String upd = (Json.str(r.get("pack")).isEmpty() ? "" : Json.str(r.get("pack")) + " ") + (Json.str(r.get("from")).isEmpty() ? "first install, " : Json.str(r.get("from")) + " \u2192 ") + Json.str(r.get("to"));
							pullModel.addRow(new Object[] {r.get("time"), r.get("name"), r.get("player"), r.get("os"), upd, Json.str(r.get("files")).replaceAll("\\.0$", ""), Json.str(r.get("mods")).replaceAll("\\.0$", ""), mb.apply(bytes), mb.apply(local)});
						}
						pullTiles[0].setText(String.valueOf(all.size())); pullTiles[1].setText(mb.apply(total)); pullTiles[2].setText(mb.apply(week));
						pullTiles[3].setText(all.isEmpty() ? "\u2013" : Json.str(Json.map(all.get(0)).get("name")) + ", " + Json.str(Json.map(all.get(0)).get("time")));
			};
			Runnable loadPulls = () -> new Thread(() -> {
				try {
					List<Object> all = Json.list(Json.map(Json.parse(call("GET", "/admin/pulls", null))).get("pulls"));
					SwingUtilities.invokeLater(() -> fillPulls.accept(all));
				} catch (Exception ignored) {}
			}, "pulls").start();
			new Timer(5000, e -> { if (pullsTab.isShowing()) loadPulls.run(); }).start();
			pullsTab.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && pullsTab.isShowing()) loadPulls.run(); });
			tabs.addTab("Access Manager", accessTab); tabs.addTab("Server Status", statusTab); tabs.addTab("Updates Pulled", pullsTab);
			// The window's three levels: MCSS on this machine, the servers it runs (both on the first screen), and one
			// server's own pages (the tabs). Open goes in; "Servers" at the top right comes back.
			java.awt.CardLayout levels = new java.awt.CardLayout();
			JPanel stack = new JPanel(levels);
			JPanel overview = new JPanel(new BorderLayout(0, 10));
			overview.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			JPanel overList = new JPanel();
			overList.setLayout(new javax.swing.BoxLayout(overList, javax.swing.BoxLayout.Y_AXIS));
			JPanel overTop = new FitWidth(new BorderLayout()); overTop.add(overList, BorderLayout.NORTH);
			JScrollPane overScroll = new JScrollPane(overTop); overScroll.setBorder(null);
			overview.add(overScroll, BorderLayout.CENTER);
			javax.swing.JTabbedPane firstTabs = new javax.swing.JTabbedPane();
			JPanel ownNotes = new JPanel(new BorderLayout()), launcherNotes = new JPanel(new BorderLayout());
			// A newer Gatekeeper: said at the top of the first screen, with Update Gatekeeper. Asked every minute (the
			// running Gatekeeper looks at the published one every five), and a chime when it first shows.
			JPanel ownUpdate = new JPanel(new BorderLayout(10, 0));
			ownUpdate.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0), BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x3D6FD6)), BorderFactory.createEmptyBorder(8, 12, 8, 8))));
			JLabel ownUpdateText = new JLabel(" ");
			JButton ownUpdateGo = new JButton("Update Gatekeeper");
			ownUpdateGo.setToolTipText("Fetches the newer Gatekeeper, checks it, and restarts Gatekeeper as it (a few seconds). Running Minecraft servers aren't touched.");
			ownUpdate.add(ownUpdateText, BorderLayout.CENTER); ownUpdate.add(ownUpdateGo, BorderLayout.EAST);
			ownUpdate.setVisible(false);
			JPanel serversTab = new JPanel(new BorderLayout());
			serversTab.add(ownUpdate, BorderLayout.NORTH); serversTab.add(overview, BorderLayout.CENTER);
			// Check for updates: looks right now (Gatekeeper looks by itself every five minutes) and says what it found.
			JButton checkNow = new JButton("Check for updates");
			checkNow.setToolTipText("Looks now for a newer Gatekeeper, instead of waiting for the next look (every five minutes)");
			JLabel checkSaid = new JLabel(" ");
			checkSaid.setForeground(new Color(0x8F98A0));
			// (Gatehouse is one program with two halves; this is the Host half. The first screen has a button for each.)
			JButton halves = new JButton("Play or Host\u2026");
			halves.setToolTipText("Gatehouse's first screen: open the Play half (the launcher) on this device, or set it up");
			halves.addActionListener(ev -> dev.gatehouse.Main.choose(f));
			JPanel checkRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6)); checkRow.add(checkNow); checkRow.add(halves); checkRow.add(checkSaid);
			serversTab.add(checkRow, BorderLayout.SOUTH);
			checkNow.addActionListener(ev -> {
				checkNow.setEnabled(false);
				checkSaid.setText("Looking\u2026");
				new Thread(() -> {
					String said;
					boolean behind = false;
					Map<String, Object> u = new LinkedHashMap<>();
					try {
						u = Json.map(Json.parse(call("GET", "/admin/update?1", null)));
						behind = Boolean.TRUE.equals(u.get("outdated")) || "true".equals(Json.str(u.get("outdated")));
						boolean reached = Boolean.TRUE.equals(u.get("reached")) || "true".equals(Json.str(u.get("reached")));
						String at = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
						said = !reached ? "Looked at " + at + ": couldn't reach where Gatekeeper is published (this machine's pack server, or GitHub). This is " + Json.str(u.get("own")) + "."
								: behind ? "Looked at " + at + ": Gatekeeper " + Json.str(u.get("newest")) + " is out. Update Gatekeeper is at the top."
								: "Looked at " + at + ": up to date. This is Gatekeeper " + Json.str(u.get("own")) + ", the newest published.";
					} catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
					String s2 = said; boolean b2 = behind; Map<String, Object> u2 = u;
					SwingUtilities.invokeLater(() -> {
						checkSaid.setText(s2);
						checkNow.setEnabled(true);
						if (b2 && ownUpdateGo.isEnabled()) ownUpdateText.setText("<html><b>A Gatekeeper update is ready: " + Json.str(u2.get("newest")) + ".</b> This is " + Json.str(u2.get("own")) + ". Until it's updated, Gatekeeper starts no server and launchers aren't offered Play.</html>");
						if (b2 || ownUpdateGo.isEnabled()) ownUpdate.setVisible(b2);
						iconUpdate = b2; dev.gatehouse.look.AppIcon.apply(f, true, iconMessages, iconUpdate);
					});
				}, "check-updates").start();
			});
			Runnable askUpdate = () -> new Thread(() -> {
				try {
					Map<String, Object> u = Json.map(Json.parse(call("GET", "/admin/update", null)));
					boolean behind = Boolean.TRUE.equals(u.get("outdated")) || "true".equals(Json.str(u.get("outdated")));
					SwingUtilities.invokeLater(() -> {
						if (behind && !ownUpdate.isVisible()) chime();
						iconUpdate = behind; dev.gatehouse.look.AppIcon.apply(f, true, iconMessages, iconUpdate);
						if (behind && ownUpdateGo.isEnabled()) ownUpdateText.setText("<html><b>A Gatekeeper update is ready: " + Json.str(u.get("newest")) + ".</b> This is " + Json.str(u.get("own")) + ". Until it's updated, Gatekeeper starts no server and launchers aren't offered Play.</html>");
						ownUpdate.setVisible(behind);
					});
				} catch (Exception ignored) {}
			}, "update").start();
			ownUpdateGo.addActionListener(ev -> {
				ownUpdateGo.setEnabled(false);
				ownUpdateText.setText("Fetching and checking the newer Gatekeeper\u2026");
				new Thread(() -> {
					String result = "failed", msg;
					try { Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/update", Json.write(m("panel_pid", ProcessHandle.current().pid()))))); result = Json.str(r.get("result")); msg = Json.str(r.get("message")); }
					catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
					String res = result, said = msg;
					SwingUtilities.invokeLater(() -> {
						ownUpdateText.setText("<html>" + said.replace("<", "&lt;") + (res.equals("ok") ? " This window closes and opens again by itself." : "") + "</html>");
						if (res.equals("ok")) new javax.swing.Timer(1200, e2 -> System.exit(0)).start(); else ownUpdateGo.setEnabled(true);
					});
				}, "update").start();
			});
			if (System.getenv("OJ_PANEL_PNG") == null) { askUpdate.run(); new javax.swing.Timer(60_000, ev -> { if (!f.isDisplayable()) ((javax.swing.Timer) ev.getSource()).stop(); else askUpdate.run(); }).start(); }
			else try {
				Map<String, Object> u = Json.map(Json.parse(call("GET", "/admin/update?1", null)));
				if (Boolean.TRUE.equals(u.get("outdated")) || "true".equals(Json.str(u.get("outdated")))) { ownUpdateText.setText("<html><b>A Gatekeeper update is ready: " + Json.str(u.get("newest")) + ".</b> This is " + Json.str(u.get("own")) + ". Until it's updated, Gatekeeper starts no server and launchers aren't offered Play.</html>"); ownUpdate.setVisible(true); }
			} catch (Exception ignored) {}
			firstTabs.addTab("Servers", serversTab); firstTabs.addTab("Gatekeeper updates", ownNotes); firstTabs.addTab("Gatehouse updates", launcherNotes);
			// Host setup: the steps this machine needs to serve players, each with how it stands, and the invite link.
			JPanel hostSetup = new JPanel(new BorderLayout(0, 8));
			hostSetup.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
			JPanel stepList = new JPanel();
			stepList.setLayout(new javax.swing.BoxLayout(stepList, javax.swing.BoxLayout.Y_AXIS));
			JPanel stepTop = new JPanel(new BorderLayout()); stepTop.add(stepList, BorderLayout.NORTH);
			JScrollPane stepScroll = new JScrollPane(stepTop); stepScroll.setBorder(null); stepScroll.getVerticalScrollBar().setUnitIncrement(16);
			javax.swing.JTextField shareF = new javax.swing.JTextField(40);
			JButton shareSave = new JButton("Save"), recheck = new JButton("Check again");
			JLabel shareSaid = new JLabel(" ");
			shareSaid.setForeground(new Color(0x8F98A0));
			JLabel checkedLab = new JLabel(" ");
			checkedLab.setForeground(new Color(0x8F98A0));
			shareF.setToolTipText("The reusable invite link you made in Tailscale's admin page for sharing this machine. Kept on this PC; it goes out only in pairings you hand over.");
			JPanel shareRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			JLabel shareLab = new JLabel("Tailscale invite link for players:"); shareLab.setFont(shareLab.getFont().deriveFont(Font.BOLD));
			shareRow.add(shareLab); shareRow.add(shareF); shareRow.add(shareSave); shareRow.add(recheck);
			// This Gatekeeper's name: what launchers list it under (otherwise this machine's own name).
			javax.swing.JTextField nameF = new javax.swing.JTextField(24);
			JButton nameSave = new JButton("Save name");
			nameF.setToolTipText("What connected launchers call this Gatekeeper in their list. Empty: this machine's own name. Launchers that are open pick a change up within a few seconds.");
			JLabel nameLab = new JLabel("Name shown in launchers:"); nameLab.setFont(nameLab.getFont().deriveFont(Font.BOLD));
			JLabel nameHint = new JLabel(" "); nameHint.setForeground(new Color(0x8F98A0));
			JPanel nameRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			nameRow.add(nameLab); nameRow.add(nameF); nameRow.add(nameSave); nameRow.add(nameHint);
			JPanel fieldRows = new JPanel();
			fieldRows.setLayout(new javax.swing.BoxLayout(fieldRows, javax.swing.BoxLayout.Y_AXIS));
			nameRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); shareRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			fieldRows.add(nameRow); fieldRows.add(javax.swing.Box.createVerticalStrut(4)); fieldRows.add(shareRow);
			JPanel shareBox = new JPanel(new BorderLayout(0, 2)); shareBox.add(checkedLab, BorderLayout.NORTH); shareBox.add(fieldRows, BorderLayout.CENTER); shareBox.add(shareSaid, BorderLayout.SOUTH);
			hostSetup.add(stepScroll, BorderLayout.CENTER); hostSetup.add(shareBox, BorderLayout.SOUTH);
			// The list looks again by itself every few seconds while this tab is open, so a change made elsewhere (in
			// MCSS, in Tailscale, in a file) shows here without pressing anything. The line at the bottom says when it
			// last looked; a step that has just turned to Done says so for a minute, and the list is only redrawn when
			// something changed.
			Runnable[] stepReload = {() -> {}};
			Map<String, String> stepWas = new LinkedHashMap<>();
			Map<String, Long> stepDoneAt = new LinkedHashMap<>();
			String[] drawn = {""};
			java.util.function.Consumer<Map<String, Object>> fillSteps = r -> {
				int todo = 0, warn = 0;
				long nowMs = System.currentTimeMillis();
				StringBuilder sig = new StringBuilder();
				for (Object o : Json.list(r.get("steps"))) {
					Map<String, Object> s = Json.map(o);
					String title = Json.str(s.get("title")), st = Json.str(s.get("state")), was = stepWas.get(title);
					if (was != null && !was.equals("ok") && st.equals("ok")) stepDoneAt.put(title, nowMs);
					if (!st.equals("ok")) stepDoneAt.remove(title);
					stepWas.put(title, st);
					sig.append(title).append('|').append(st).append('|').append(Json.str(s.get("detail"))).append('|').append(Json.str(s.get("how"))).append('|').append(stepDoneAt.containsKey(title) && nowMs - stepDoneAt.get(title) < 60_000).append('|').append(Json.str(s.get("action"))).append('|').append(Json.str(s.get("action_ask"))).append('\n');
				}
				String checked = Json.str(r.get("checked"));
				boolean same = sig.toString().equals(drawn[0]);
				drawn[0] = sig.toString();
				if (!same) stepList.removeAll();
				for (Object o : Json.list(r.get("steps"))) {
					Map<String, Object> s = Json.map(o);
					String st = Json.str(s.get("state"));
					if (st.equals("todo")) todo++; else if (st.equals("warn")) warn++;
					if (same) continue;
					boolean na = st.equals("na");
					boolean justDone = stepDoneAt.containsKey(Json.str(s.get("title"))) && nowMs - stepDoneAt.get(Json.str(s.get("title"))) < 60_000;
					String colour = st.equals("ok") ? "#3FB950" : st.equals("warn") ? "#E3B341" : na ? "#8F98A0" : "#F85149", word = st.equals("ok") ? (justDone ? "<b>Done just now</b>" : "Done") : st.equals("warn") ? "Worth changing" : na ? notSupported() : "To do";
					java.util.function.Function<String, String> esc = t -> t.replace("&", "&amp;").replace("<", "&lt;");
					JLabel row = new JLabel("<html><div style='width:860px'><span style='color:" + colour + "'>\u25CF</span>&nbsp; <b>" + esc.apply(Json.str(s.get("title"))) + "</b> &nbsp;<span style='color:" + colour + "'>" + word + "</span>"
							+ (Json.str(s.get("detail")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;" + esc.apply(Json.str(s.get("detail"))))
							+ (Json.str(s.get("how")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;<span style='color:#8F98A0'>" + esc.apply(Json.str(s.get("how"))) + "</span>") + "</div></html>");
					row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0)); row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
					stepList.add(row);
					// A step Gatekeeper can do itself has a button for it (it asks first, and says what it did).
					String action = Json.str(s.get("action")), actionAsk = Json.str(s.get("action_ask"));
					if (action.equals("whitelist_on")) {
						JButton does = new JButton(Json.str(s.get("action_label")));
						does.addActionListener(ev -> {
							if (JOptionPane.showConfirmDialog(f, "<html><div style='width:440px'>" + actionAsk.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>", "Host setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
							does.setEnabled(false);
							new Thread(() -> {
								String said;
								try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/whitelist/on", "{}"))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
								String msg = said;
								SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); stepReload[0].run(); });
							}, "whitelist-on").start();
						});
						JPanel br = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 22, 0));
						br.add(does); br.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
						stepList.add(br);
					}
				}
				if (!shareF.isFocusOwner()) shareF.setText(Json.str(r.get("share_link")));
				if (!nameF.isFocusOwner()) nameF.setText(Json.str(r.get("name")));
				nameHint.setText(Json.str(r.get("name")).isEmpty() && !Json.str(r.get("machine")).isEmpty() ? "(empty: listed as \"" + Json.str(r.get("machine")) + "\")" : " ");
				firstTabs.setTitleAt(3, todo > 0 ? "Host setup (" + todo + " to do)" : "Host setup");
				checkedLab.setText((todo + warn == 0 ? "Everything is done. " : (todo > 0 ? todo + " to do" : "") + (todo > 0 && warn > 0 ? ", " : "") + (warn > 0 ? warn + " worth changing" : "") + ". ")
						+ (checked.isEmpty() ? "" : "Last looked at " + checked + "; ") + "it looks again every 5 seconds while this tab is open.");
				if (!same) { stepList.revalidate(); stepList.repaint(); }
			};
			Runnable loadSteps = () -> new Thread(() -> {
				try { Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/checklist", null))); SwingUtilities.invokeLater(() -> fillSteps.accept(r)); }
				catch (Exception ex) { SwingUtilities.invokeLater(() -> shareSaid.setText("Couldn't ask Gatekeeper: is it running?")); }
			}, "checklist").start();
			stepReload[0] = loadSteps;
			recheck.addActionListener(ev -> { shareSaid.setText("Checking\u2026"); loadSteps.run(); shareSaid.setText(" "); });
			shareSave.addActionListener(ev -> new Thread(() -> {
				String said;
				try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/checklist", Json.write(m("share_link", shareF.getText().trim()))))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
				String msg = said;
				SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); f.getRootPane().requestFocusInWindow(); loadSteps.run(); });
			}, "share-link").start());
			nameSave.addActionListener(ev -> new Thread(() -> {
				String said;
				try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/checklist", Json.write(m("name", nameF.getText().trim()))))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
				String msg = said;
				SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); f.getRootPane().requestFocusInWindow(); loadSteps.run(); });
			}, "name").start());
			firstTabs.addTab("Host setup", hostSetup);
			firstTabs.addTab("Web panel", PanelUi.tab(f));
			firstTabs.addChangeListener(ev -> { if (firstTabs.getSelectedIndex() == 3) loadSteps.run(); });
			new javax.swing.Timer(5000, ev -> { if (!f.isDisplayable()) ((javax.swing.Timer) ev.getSource()).stop(); else if (firstTabs.getSelectedIndex() == 3 && hostSetup.isShowing()) loadSteps.run(); }).start();
			if (System.getenv("OJ_PANEL_PNG") == null) loadSteps.run();   // (so the tab's title says what's left to do)
			else try { fillSteps.accept(Json.map(Json.parse(call("GET", "/admin/checklist", null)))); } catch (Exception ignored) {}
			// A program's notes as a scrolling list, newest on top.
			java.util.function.BiConsumer<JPanel, Object[]> fillNotes = (panel, what) -> {
				Map<String, Object> notes = Json.map(what[0]);
				JPanel list = new JPanel();
				list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
				list.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
				String v = Json.str(notes.get("version"));
				JLabel now = new JLabel(v.isEmpty() ? "No notes to show." : what[1] + " " + v + (Json.str(notes.get("_from")).isEmpty() ? " is what's running here." : " is the newest (from " + Json.str(notes.get("_from")) + ")."));
				now.setForeground(new Color(0x8F98A0)); now.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				list.add(now);
				for (Object o : Json.list(notes.get("changes"))) {
					Map<String, Object> ch = Json.map(o);
					JLabel h = new JLabel(Json.str(ch.get("version")) + (Json.str(ch.get("date")).isEmpty() ? "" : " (" + Json.str(ch.get("date")) + ")"));
					h.setFont(h.getFont().deriveFont(Font.BOLD, 14f)); h.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0)); h.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
					list.add(h);
					for (Object n : Json.list(ch.get("notes"))) {
						JLabel l = new JLabel("<html><div style='width:820px'>\u2022 " + String.valueOf(n).replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
						l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0)); l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
						list.add(l);
					}
				}
				JPanel top2 = new JPanel(new BorderLayout()); top2.add(list, BorderLayout.NORTH);
				JScrollPane sp = new JScrollPane(top2); sp.setBorder(null); sp.getVerticalScrollBar().setUnitIncrement(16);
				panel.removeAll(); panel.add(sp, BorderLayout.CENTER); panel.revalidate(); panel.repaint();
			};
			fillNotes.accept(ownNotes, new Object[] {notesInside("gatekeeper"), "Gatekeeper"});
			fillNotes.accept(launcherNotes, new Object[] {notesInside("gatehouse"), "Gatehouse"});
			// (Gatehouse's newest notes are fetched by the running Gatekeeper; asked for when that tab is opened)
			firstTabs.addChangeListener(ev -> { if (firstTabs.getSelectedIndex() == 2) new Thread(() -> {
				try { Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/about", null))); if (!Json.map(r.get("gatehouse")).isEmpty()) SwingUtilities.invokeLater(() -> fillNotes.accept(launcherNotes, new Object[] {r.get("gatehouse"), "Gatehouse"})); } catch (Exception ignored) {}
			}, "about").start(); });
			String ownV = Json.str(notesInside("gatekeeper").get("version"));
			if (!ownV.isEmpty()) f.setTitle("Gatekeeper " + ownV);
			stack.add(firstTabs, "overview"); stack.add(tabs, "server");
			p.add(stack, BorderLayout.CENTER);
			JButton backB = new JButton("\u2039 Servers");
			backB.setToolTipText("Back to MCSS and the list of servers on this machine");
			backB.setVisible(false);
			// ---- Options (the wheel at the top right): which Gatekeeper this is, where it lives, and removing it.
			JPanel options = new JPanel();
			options.setLayout(new javax.swing.BoxLayout(options, javax.swing.BoxLayout.Y_AXIS));
			options.setBorder(BorderFactory.createEmptyBorder(8, 14, 10, 14));
			java.util.function.BiConsumer<String, Boolean> optLine = (text, bold) -> {
				JLabel l = new JLabel("<html><div style='width:760px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
				if (bold) { l.setFont(l.getFont().deriveFont(Font.BOLD, 15f)); l.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0)); } else l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
				l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				options.add(l);
			};
			Path programDir;
			try { programDir = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(); } catch (Exception ex) { programDir = dir; }
			Path keeperDir = programDir;
			JButton optBack = new JButton("\u2039 Back");
			optBack.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			options.add(optBack);
			optLine.accept("About", true);
			optLine.accept("Gatekeeper " + Json.str(notesInside("gatekeeper").get("version")) + " on " + systemName() + ", running from " + keeperDir + ". Its settings, its list of launchers and its log are in " + dir + ".", false);
			optLine.accept("Setup guide", true);
			optLine.accept("The steps from a machine with nothing on it to a server your friends can join, each with a check for whether it's done.", false);
			JButton guideB = new JButton("Open the setup guide");
			JPanel guideRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
			guideRow.add(guideB); guideRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			options.add(guideRow);
			optLine.accept("Uninstall", true);
			optLine.accept("Removes Gatekeeper from this machine: it is stopped, and " + say("its start-up task, its Start menu shortcut", "its start-up service, its entry in the applications menu") + " and its program are taken away. MC Server Soft, your Minecraft servers and their worlds are not touched; launchers simply stop getting an answer from here. If Gatekeeper is in your Steam library, remove it there afterwards.", false);
			javax.swing.JCheckBox forgetSettings = new javax.swing.JCheckBox("Also remove its settings: who is allowed to do what, its key for MCSS, and its log");
			forgetSettings.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			JButton uninstall = new JButton("Uninstall Gatekeeper\u2026");
			uninstall.setToolTipText("Asks you to type confirm first");
			uninstall.setForeground(new Color(0xFF7B72));
			JPanel uninstallRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 8));
			uninstallRow.add(uninstall); uninstallRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			JLabel uninstallSaid = new JLabel(" "); uninstallSaid.setForeground(new Color(0x8F98A0)); uninstallSaid.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			options.add(forgetSettings); options.add(uninstallRow); options.add(uninstallSaid);
			uninstall.addActionListener(ev -> {
				javax.swing.JTextField typed = new javax.swing.JTextField(16);
				JPanel askConfirm = new JPanel(new BorderLayout(0, 8));
				askConfirm.add(new JLabel("<html><div style='width:400px'>This removes Gatekeeper from this machine" + (forgetSettings.isSelected() ? ", with its settings" : "") + ". Type <b>confirm</b> to go ahead.</div></html>"), BorderLayout.NORTH);
				askConfirm.add(typed, BorderLayout.CENTER);
				if (JOptionPane.showConfirmDialog(f, askConfirm, "Uninstall Gatekeeper", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				if (!typed.getText().trim().equalsIgnoreCase("confirm")) { uninstallSaid.setText("Nothing was removed: the word typed wasn't \"confirm\"."); return; }
				try {
					// Its own install script (carried inside this program, written out beside its settings), with -Uninstall,
					// in a window of its own so what it did can be read. It stops Gatekeeper, this window included.
					List<String> cmd = uninstallCommand(keeperDir, forgetSettings.isSelected());
					if (cmd == null) { uninstallSaid.setText(say("The uninstall script isn't here. Use gatehouse-install.ps1 -Uninstall -Gatekeeper instead.", "The uninstall script isn't here. Use install-keeper.sh --uninstall instead.")); return; }
					if (System.getenv("OJ_UNINSTALL_DRY") != null) { uninstallSaid.setText("<html><div style='width:760px'>would run: " + String.join(" ", cmd).replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"); System.out.println("would run: " + String.join(" ", cmd)); return; }
					if (!WIN) {
						// (no window of its own here: the script's answer is shown, and this window stays until it's closed)
						uninstall.setEnabled(false);
						uninstallSaid.setText("Removing Gatekeeper\u2026");
						new Thread(() -> {
							String out = commandSays(cmd.toArray(new String[0]));
							String told = out == null ? "The uninstall script didn't finish." : out.trim();
							SwingUtilities.invokeLater(() -> uninstallSaid.setText("<html><div style='width:760px'>" + told.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "<br><br>You can close this window.</div></html>"));
						}, "uninstall").start();
						return;
					}
					new ProcessBuilder(cmd).start();
					uninstallSaid.setText("Removing Gatekeeper in its own window. This one closes now.");
					new javax.swing.Timer(1500, e2 -> System.exit(0)).start();
				} catch (IOException | RuntimeException ex) { uninstallSaid.setText("Couldn't start the uninstall: " + ex.getMessage()); }
			});
			JPanel optionsTop = new JPanel(new BorderLayout()); optionsTop.add(options, BorderLayout.NORTH);
			stack.add(new JScrollPane(optionsTop), "options");
			String[] optionsFrom = {"overview", "Gatekeeper"};
			gear.addActionListener(ev -> { optionsFrom[0] = backB.isVisible() ? "server" : "overview"; optionsFrom[1] = head.getText(); head.setText("Gatekeeper \u2013 Options"); pickRow.setVisible(false); levels.show(stack, "options"); });
			optBack.addActionListener(ev -> { head.setText(optionsFrom[1]); pickRow.setVisible(true); levels.show(stack, optionsFrom[0]); });
			// ---- the setup guide: the steps one at a time, asked of the running Gatekeeper, with buttons that do them
			dev.gatehouse.look.Wizard[] guide = {null};
			Runnable leaveGuide = () -> { guide[0].stop(); head.setText("Gatekeeper"); pickRow.setVisible(true); backB.setVisible(false); levels.show(stack, "overview"); };
			guide[0] = new dev.gatehouse.look.Wizard(() -> {
				List<dev.gatehouse.look.Wizard.Step> steps = new ArrayList<>();
				try {
					for (Object o : Json.list(Json.map(Json.parse(call("GET", "/admin/wizard", null))).get("steps"))) {
						Map<String, Object> s = Json.map(o);
						dev.gatehouse.look.Wizard.Step step = new dev.gatehouse.look.Wizard.Step(Json.str(s.get("key")), Json.str(s.get("title")), Json.str(s.get("state")), Json.str(s.get("text")), Json.str(s.get("detail")));
						for (Object a : Json.list(s.get("actions"))) step.action(Json.str(Json.list(a).get(0)), Json.str(Json.list(a).get(1)));
						steps.add(step);
					}
				} catch (Exception ex) { throw new RuntimeException(ex); }
				return steps;
			}, id -> {
				if (id.startsWith("open:")) dev.gatehouse.look.Links.openOrShow(f, id.substring(5));
				else if (id.equals("setup")) setupB.doClick();
				else if (id.equals("newserver")) ServerToolsUi.newServer(f, null);
				else if (id.equals("pairing")) pairB.doClick();
				else if (id.equals("hostsetup")) { leaveGuide.run(); firstTabs.setSelectedIndex(3); }
				else if (id.equals("servers")) { leaveGuide.run(); firstTabs.setSelectedIndex(0); }
			}, leaveGuide, HostHelper.class);
			stack.add(guide[0], "wizard");
			Runnable openGuide = () -> { head.setText("Gatekeeper \u2013 Setup guide"); pickRow.setVisible(false); levels.show(stack, "wizard"); guide[0].open(); };
			guideB.addActionListener(ev -> openGuide.run());
			// (the first time Gatekeeper's window is opened on a machine that still has steps to do, it opens on the guide)
			if (System.getenv("OJ_PANEL_PNG") == null && !Files.exists(dir.resolve("setup-guide-shown.txt"))) new Thread(() -> {
				try {
					boolean open = false;
					for (Object o : Json.list(Json.map(Json.parse(call("GET", "/admin/wizard", null))).get("steps"))) if (Json.str(Json.map(o).get("state")).equals("todo")) open = true;
					Files.writeString(dir.resolve("setup-guide-shown.txt"), "The setup guide was shown once by itself; it stays in Options.\r\n", StandardCharsets.UTF_8);
					if (open) SwingUtilities.invokeLater(openGuide);
				} catch (Exception ignored) {}
			}, "guide").start();
			Runnable refresh = () -> new Thread(() -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/devices", null)));
					List<Object> lg = Json.list(Json.map(Json.parse(call("GET", "/admin/log", null))).get("log"));
					Map<String, Object> pl = Json.map(Json.map(Json.parse(call("GET", "/admin/players", null))).get("players"));
					SwingUtilities.invokeLater(() -> {
						int sel = table.getSelectedRow();
						String keep = sel >= 0 && sel < ids.size() ? ids.get(sel) : null;
						model.setRowCount(0); ids.clear(); perms.clear();
						java.util.function.Function<String, String> word = st -> st.equals("allowed") ? "Allowed" : st.equals("denied") ? "Denied" : "Not allowed";
						for (Map.Entry<String, Object> e : Json.map(r.get("devices")).entrySet()) {
							Map<String, Object> d = Json.map(e.getValue());
							ids.add(e.getKey());
							boolean pm = Boolean.TRUE.equals(d.get("granted")), ps = Boolean.TRUE.equals(d.get("sleep")), pv = Boolean.TRUE.equals(d.get("server")), pa = Boolean.TRUE.equals(d.get("admin"));
							perms.put(e.getKey(), new boolean[] {pm, ps, pv, pa});
							model.addRow(new Object[] {d.get("name"), Json.str(d.get("player")).isEmpty() ? "" : Json.str(d.get("player")) + (Boolean.TRUE.equals(d.get("player_confirmed")) ? "" : " (not confirmed)"), d.get("tailscale"), d.get("os"), d.get("first_seen"), d.get("last_seen"),
									word.apply(Json.str(d.get("state_server"))).equals("Not allowed") && pm ? "Allowed (with MCSS control)" : word.apply(Json.str(d.get("state_server"))),
									word.apply(Json.str(d.get("state_mcss"))), word.apply(Json.str(d.get("state_machine"))), word.apply(Json.str(d.get("state_admin"))), Json.str(d.get("access_text")), Json.str(d.get("ban_text")), Json.str(d.get("now"))});
							if (e.getKey().equals(keep)) table.setRowSelectionInterval(ids.size() - 1, ids.size() - 1);
						}
						int psel = ptable.getSelectedRow();
						String pkeep = psel >= 0 && psel < pnames.size() ? pnames.get(psel) : null;
						filling[0] = true;
						pmodel.setRowCount(0); pnames.clear(); pallow.clear();
						for (Map.Entry<String, Object> e : pl.entrySet()) {
							Map<String, Object> q = Json.map(e.getValue());
							int conf = q.get("confirmed") instanceof Number n ? n.intValue() : 0;
							String[] cell = new String[4]; boolean[] al = new boolean[4];
							String[] keys = {"server", "mcss", "sleep", "admin"};
							for (int i = 0; i < 4; i++) {
								al[i] = Boolean.TRUE.equals(q.get("allow_" + keys[i]));
								int has = q.get("has_" + keys[i]) instanceof Number n ? n.intValue() : 0;
								String pst = Json.str(q.get("state_" + (keys[i].equals("sleep") ? "machine" : keys[i])));
								cell[i] = (pst.equals("denied") ? "Denied" : al[i] ? "Allowed" : "Not allowed") + (pst.equals("denied") || conf == 0 ? "" : has == conf ? (al[i] ? "" : " (on all " + conf + " anyway)") : has == 0 ? (al[i] ? " (on none yet)" : "") : " (on " + has + " of " + conf + ")");
							}
							pnames.add(e.getKey()); pallow.put(e.getKey(), al);
							List<Object> dv = Json.list(q.get("devices"));
							StringBuilder ds = new StringBuilder();
							for (Object o : dv) ds.append(ds.length() == 0 ? "" : ", ").append(o);
							pmodel.addRow(new Object[] {e.getKey(), Boolean.TRUE.equals(q.get("whitelisted")), ds.length() == 0 ? "(none yet)" : ds.toString(), Json.str(q.get("last_seen")), cell[0], cell[1], cell[2], cell[3],
									Json.str(q.get("ban")).isEmpty() ? "" : (Json.str(q.get("ban")).equals("blacklist") ? "Blacklisted" : "Suspended") + (Json.str(q.get("ban_reason")).isEmpty() ? "" : ": " + Json.str(q.get("ban_reason"))), Json.str(q.get("now"))});
							if (e.getKey().equals(pkeep)) ptable.setRowSelectionInterval(pnames.size() - 1, pnames.size() - 1);
						}
						filling[0] = false;
						boolean hasKey = Boolean.TRUE.equals(r.get("has_key"));
						String works = Json.str(r.get("key_works"));
						keyState.setForeground(!hasKey ? new Color(0xE9B949) : !works.equals("yes") ? new Color(0xFF7B72) : new Color(0x8BD46E));
						String as = Json.str(r.get("mcss_user"));
						if (builtin()) keyState.setForeground(new Color(0x8F98A0));
						keyState.setText(builtin() ? "Nothing to sign in to: Gatekeeper runs the servers itself."
								: works.equals("forbidden") ? "MCSS knows Gatekeeper's key, but it has no permission for this server: give it the server (view, start, stop) under API Keys in MCSS's panel."
								: works.equals("unknown") ? "MCSS doesn't know Gatekeeper's key: set it again, or sign in."
								: !hasKey ? "Gatekeeper isn't signed in to MCSS: server control won't work until it is."
								: works.equals("no") ? (as.isEmpty() ? "Gatekeeper's MCSS key is set, but MCSS isn't answering with it (MCSS closed, or the key is wrong)." : "Signed in to MCSS as " + as + ", but MCSS isn't answering with it (MCSS closed, or sign in again).")
								: as.isEmpty() ? "Gatekeeper's MCSS key is set and working." : "Signed in to MCSS as " + as + ": working.");
						List<Object> sv = Json.list(r.get("servers"));
						if (sv.size() != serverIds.size()) {
							serverIds.clear(); serverPick.removeAllItems();
							for (Object o : sv) { serverIds.add(Json.str(Json.map(o).get("id"))); serverPick.addItem(Json.str(Json.map(o).get("name"))); }
							int at = Math.max(0, serverIds.indexOf(Json.str(r.get("server_id"))));
							if (!serverIds.isEmpty()) serverPick.setSelectedIndex(at);
							pickRow.setVisible(sv.size() > 1);
						}
						List<Object> asked = Json.list(r.get("requests"));
						iconMessages = asked.size(); dev.gatehouse.look.AppIcon.apply(f, true, iconMessages, iconUpdate);   // (the red circle: requests waiting)
						int seq = r.get("request_seq") instanceof Number n ? n.intValue() : 0;
						if (asked.isEmpty()) {
							if (reqRow.isVisible()) { reqRow.setVisible(false); reqRow.removeAll(); reqShown[0] = ""; f.setTitle("Gatekeeper"); }
						} else {
							String sig = Json.write(asked);
							if (!sig.equals(reqShown[0])) {   // (rebuilt only when the requests change, so a button isn't replaced under the mouse)
								reqShown[0] = sig;
								reqRow.removeAll();
								for (Object o : asked) {
									Map<String, Object> q = Json.map(o);
									String type = Json.str(q.get("type")), rid = Json.str(q.get("id")), msg = Json.str(q.get("message"));
									String by = Json.str(q.get("who")) + (Json.str(q.get("player")).isEmpty() ? "" : " (" + Json.str(q.get("player")) + ")");
									JLabel text = new JLabel("<html><b>" + (by + " asks for " + requestWords(type) + (Json.str(q.get("server")).isEmpty() ? "" : " (" + Json.str(q.get("server")) + ")")).replace("&", "&amp;").replace("<", "&lt;")
											+ "</b>, " + Json.str(q.get("time")) + (msg.isEmpty() ? "" : " &nbsp;&ldquo;" + msg.replace("&", "&amp;").replace("<", "&lt;") + "&rdquo;") + "</html>");
									JButton yes = new JButton(type.equals("server") ? "Start server" : type.startsWith("perm:") ? "Allow" : type.equals("whitelist") ? "Add to whitelist" : type.equals("unblacklist") ? "Lift blacklist" : "Lift suspension");
									JButton no = new JButton("Dismiss\u2026");
									java.util.function.BiConsumer<Boolean, String> answer = (grant, reason) -> new Thread(() -> {
										String res = "failed", m2;
										try { Map<String, Object> rr = Json.map(Json.parse(call("POST", "/admin/request/answer", Json.write(m("id", rid, "type", type, "grant", grant, "reason", reason))))); res = Json.str(rr.get("result")); m2 = Json.str(rr.get("message")); }
										catch (IOException ex) { m2 = "Gatekeeper isn't running."; }
										String shown = m2; boolean tell = !res.equals("ok");
										SwingUtilities.invokeLater(() -> { if (tell) JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + shown + "</div></html>", "Gatekeeper", JOptionPane.PLAIN_MESSAGE); refreshLater[0].run(); });
									}, "answer").start();
									yes.addActionListener(ev -> answer.accept(true, ""));
									no.addActionListener(ev -> {
										Object why = JOptionPane.showInputDialog(f, "<html><div style='width:380px'>Dismiss this request? You can give a reason for the launcher to show (or leave it empty).</div></html>", "Dismiss", JOptionPane.PLAIN_MESSAGE, null, null, "");
										if (why != null) answer.accept(false, why.toString());
									});
									JPanel line = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 3));
									line.setBackground(new Color(0x3A3218));
									line.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, new Color(0xE0A91B)));
									line.add(text); line.add(yes); line.add(no);
									line.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
									reqRow.add(line);
								}
								reqRow.setVisible(true);
								reqRow.revalidate(); reqRow.repaint();
							}
							Map<String, Object> lastAsk = Json.map(asked.get(asked.size() - 1));
							f.setTitle(asked.size() + (asked.size() == 1 ? " request" : " requests") + " (" + Json.str(lastAsk.get("who")) + ") \u2013 Gatekeeper");
							if (seq != seqSeen[0]) {   // a new one: flash the taskbar button
								try { if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW)) java.awt.Taskbar.getTaskbar().requestWindowUserAttention(f); }
								catch (RuntimeException ignored) {}
							}
						}
						seqSeen[0] = seq;
						mcssUp[0] = Boolean.TRUE.equals(r.get("mcss")); mcssClose[0] = Json.str(r.get("close"));
						mcssRun.setText(builtin() ? "Start MCSS: " + noMcssShort() : !mcssUp[0] ? "Start MCSS" : mcssClose[0].equals("force") ? "Force close MCSS" : "Close MCSS");
						String mph = Json.str(r.get("mcss_phase")), sph = Json.str(r.get("server_phase"));
						mcssRun.setEnabled(!builtin() && !mph.equals("starting") && !mph.equals("closing"));
						mcssRun.setToolTipText(mcssUp[0] && Boolean.TRUE.equals(r.get("minecraft")) ? "Closes MCSS. A Minecraft server is running: it's stopped first (the world is saved), then MCSS closes." : "Starts MCSS on this machine, or closes it.");
						state.setForeground(new Color(0x8BD46E));
						state.setText("Running on " + r.get("bind") + ":" + r.get("port") + " (launchers reach it over Tailscale).");
						boolean mBusy = mph.equals("starting") || mph.equals("closing"), sBusy = sph.equals("starting") || sph.equals("stopping");
						mcssState.setForeground(mBusy ? new Color(0xE9B949) : Boolean.TRUE.equals(r.get("mcss")) ? new Color(0x8BD46E) : new Color(0x8F98A0));
						if (builtin()) mcssState.setForeground(new Color(0x8F98A0));
						mcssState.setText(builtin() ? noMcss() + "." : mph.equals("starting") ? "MCSS is starting\u2026" : mph.equals("closing") ? "MCSS is closing\u2026 (after the Minecraft server has stopped)" : Boolean.TRUE.equals(r.get("mcss")) ? "MCSS is running" : "MCSS isn't running");
						serverUpNow[0] = sph.equals("online") || sph.equals("stopping");
						serverState.setForeground(sBusy ? new Color(0xE9B949) : sph.equals("online") ? new Color(0x8BD46E) : new Color(0x8F98A0));
						serverState.setText("The Minecraft server is " + phaseWords(sph));
						serverRun.setText(serverUpNow[0] ? "Stop server" : "Start server");
						serverRun.setEnabled(!sBusy);
						StringBuilder sb = new StringBuilder();
						for (Object l : lg) sb.append(l).append('\n');
						if (!log.getText().equals(sb.toString())) { log.setText(sb.toString()); log.setCaretPosition(log.getDocument().getLength()); }
					});
				} catch (IOException e) {
					SwingUtilities.invokeLater(() -> { state.setForeground(new Color(0xFF7B72)); state.setText("Gatekeeper isn't running (it starts when you sign in to Windows)."); });
				}
			}, "refresh").start();
			java.util.function.BiConsumer<String, String> act = (what, body) -> new Thread(() -> {
				try { call("POST", what, body); } catch (IOException e) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "Gatekeeper isn't running.")); }
				refresh.run();
			}, "act").start();
			java.util.function.Supplier<String> chosen = () -> {
				int r = table.getSelectedRow();
				if (r < 0) { JOptionPane.showMessageDialog(f, "Pick a launcher in the list first."); return null; }
				return ids.get(r);
			};
			// Each button flips its permission for the selected launcher, and says which way it will go.
			Runnable labels = () -> {
				int r = table.getSelectedRow();
				boolean[] pp = r >= 0 && r < ids.size() ? perms.getOrDefault(ids.get(r), new boolean[4]) : new boolean[4];
				adminB.setText(pp[3] ? "Remove Gatekeeper control" : "Allow Gatekeeper control");
				serverB.setText(pp[2] ? "Remove server control" : "Allow server control");
				mcssB.setText(builtin() ? "MCSS control: " + noMcssShort() : pp[0] ? "Remove MCSS control" : "Allow MCSS control");
				sleepB.setText(!sleepSupported() ? "Machine wake/sleep: " + notSupported().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1) : pp[1] ? "Remove PC sleep" : "Allow machine wake/sleep");
			};
			table.getSelectionModel().addListSelectionListener(e -> labels.run());
			model.addTableModelListener(e -> SwingUtilities.invokeLater(labels));
			mcssB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"mcss\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[0] + "}"); });
			sleepB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"sleep\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[1] + "}"); });
			serverB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"server\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[2] + "}"); });
			String[] pkeys = {"server", "mcss", "sleep", "admin"}, pwords = {"server control", "MCSS control", "Machine wake/sleep", "Gatekeeper control"};
			pmodel.addTableModelListener(e -> {
				if (filling[0] || e.getType() != javax.swing.event.TableModelEvent.UPDATE || e.getColumn() != 1) return;
				int row = e.getFirstRow();
				if (row < 0 || row >= pnames.size()) return;
				String name = pnames.get(row);
				boolean on = Boolean.TRUE.equals(pmodel.getValueAt(row, 1));
				new Thread(() -> {
					String res = "failed", msg;
					try { Map<String, Object> rr = Json.map(Json.parse(call("POST", "/admin/whitelist", Json.write(m("player", name, "value", on))))); res = Json.str(rr.get("result")); msg = Json.str(rr.get("message")); }
					catch (IOException ex) { msg = "Gatekeeper isn't running."; }
					String m2 = msg; boolean bad = !res.equals("ok");
					SwingUtilities.invokeLater(() -> { if (bad) JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Whitelist", JOptionPane.PLAIN_MESSAGE); });
					refresh.run();
				}, "whitelist").start();
			});
			Runnable plabels = () -> {
				int r = ptable.getSelectedRow();
				boolean[] al = r >= 0 && r < pnames.size() ? pallow.getOrDefault(pnames.get(r), new boolean[4]) : new boolean[4];
				for (int i = 0; i < 4; i++) pB[i].setText(i == 2 && !sleepSupported() ? "Machine wake/sleep: " + notSupported().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1) : i == 1 && builtin() ? "MCSS control: " + noMcssShort() : (al[i] ? "Remove " : "Allow ") + pwords[i]);
			};
			ptable.getSelectionModel().addListSelectionListener(e -> plabels.run());
			pmodel.addTableModelListener(e -> SwingUtilities.invokeLater(plabels));
			for (int i = 0; i < 4; i++) {
				int k = i;
				pB[i].addActionListener(e -> {
					int r = ptable.getSelectedRow();
					if (r < 0 || r >= pnames.size()) { JOptionPane.showMessageDialog(f, "Pick a player in the list first."); return; }
					String name = pnames.get(r);
					boolean v = !pallow.getOrDefault(name, new boolean[4])[k];
					new Thread(() -> {
						String msg;
						try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/player/perm", Json.write(m("player", name, "perm", pkeys[k], "value", v))))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
						String m2 = msg;
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Players", JOptionPane.PLAIN_MESSAGE));
						refresh.run();
					}, "player-perm").start();
				});
			}
			refreshLater[0] = refresh;
			// ---- the first screen: MCSS, then its servers
			pickRow.removeAll(); pickRow.add(backB);   // (the list of servers replaces the drop-down that picked one)
			boolean[] inServer = {false};
			java.util.function.BiFunction<String, String, Object[]> overRow = (title, button) -> {
				JPanel r = new JPanel(new BorderLayout(14, 0));
				r.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
				r.setBackground(new Color(0x141D29));
				JLabel n = new JLabel(title); n.setFont(n.getFont().deriveFont(Font.BOLD, 16f));
				JLabel st = new JLabel(" ");
				JPanel text = new JPanel(new BorderLayout(0, 4)); text.setOpaque(false);
				text.add(n, BorderLayout.NORTH); text.add(st, BorderLayout.SOUTH);
				JButton b = button == null ? null : new JButton(button);
				r.add(text, BorderLayout.CENTER);
				if (b != null) { JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 6)); east.setOpaque(false); east.add(b); r.add(east, BorderLayout.EAST); }
				r.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				r.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 84));
				return new Object[] {r, st, b};
			};
			@SuppressWarnings("unchecked") java.util.function.Consumer<Map<String, Object>>[] fillOverviewRef = new java.util.function.Consumer[1];
			String[] overShown = {""};
			java.util.function.Consumer<Map<String, Object>> fillOverview = r -> {
				// (rebuilt only when something on it changed, so a button isn't replaced under the mouse mid-click)
				String overSig = Json.write(r);
				if (overSig.equals(overShown[0]) && overList.getComponentCount() > 0) return;
				overShown[0] = overSig;
				overList.removeAll();
				JLabel h1 = new JLabel(builtin() ? "What runs the servers" : "MCSS on this machine"); h1.setFont(h1.getFont().deriveFont(Font.BOLD, 14f)); h1.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0)); h1.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				overList.add(h1);
				boolean up = Boolean.TRUE.equals(r.get("mcss"));
				String exe = Json.str(r.get("mcss_exe"));
				Object[] mr = overRow.apply(builtin() ? "Gatekeeper itself, from " + exe : exe.isEmpty() ? "MCSS (not set up yet: Server setup, on a server's Status tab)" : exe, null);
				String mph0 = Json.str(r.get("mcss_phase"));
				((JLabel) mr[1]).setText((mph0.equals("starting") ? "Starting\u2026" : mph0.equals("closing") ? "Closing\u2026" : up ? "Running" : "Not running") + (Boolean.TRUE.equals(r.get("has_key")) ? "" : ". Gatekeeper isn't signed in to it yet."));
				((JLabel) mr[1]).setForeground(up ? new Color(0x8BD46E) : new Color(0x8F98A0));
				if (builtin()) { ((JLabel) mr[1]).setText("MC Server Soft: " + noMcssShort()); ((JLabel) mr[1]).setForeground(new Color(0x8F98A0)); }
				overList.add((JPanel) mr[0]);
				JLabel h2 = new JLabel(builtin() || Boolean.TRUE.equals(r.get("native_ok")) ? "Servers" : "Servers in this MCSS"); h2.setFont(h2.getFont().deriveFont(Font.BOLD, 14f)); h2.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0)); h2.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				overList.add(h2);
				List<Object> sv = Json.list(r.get("servers"));
				if (sv.isEmpty()) { JLabel none = new JLabel("None yet. Gatekeeper opens Server setup for you; it's also on a server's Status tab."); none.setForeground(new Color(0x8F98A0)); overList.add(none); }
				for (Object o : sv) {
					Map<String, Object> s = Json.map(o);
					boolean on = Boolean.TRUE.equals(s.get("running"));
					Object[] sr = overRow.apply(Json.str(s.get("name")), "Open");
					String players = Json.str(s.get("players")).replaceAll("\\.0$", "");
					String ph = Json.str(s.get("phase"));
					boolean nat = Boolean.TRUE.equals(s.get("native"));
					((JLabel) sr[1]).setText((on ? "Running, " + players + " playing" : ph.equals("starting") ? "Starting\u2026" : ph.equals("stopping") ? "Shutting down\u2026" : "Off") + "   \u00b7   port " + Json.str(s.get("port")) + (Json.str(r.get("host")).equals("mcss") ? nat ? "   \u00b7   run by Gatekeeper itself" : "   \u00b7   run by MCSS" : "") + "   \u00b7   " + Json.str(s.get("dir")));
					((JLabel) sr[1]).setForeground(on ? new Color(0x8BD46E) : ph.equals("starting") || ph.equals("stopping") ? new Color(0xE9B949) : new Color(0x8F98A0));
					((JButton) sr[2]).setToolTipText("Access Manager, Server Status and Updates Pulled for this server");
					String id = Json.str(s.get("id")), nm = Json.str(s.get("name"));
					((JButton) sr[2]).addActionListener(ev -> { panelServer = id; inServer[0] = true; head.setText("Gatekeeper \u2013 " + nm); backB.setVisible(true); levels.show(stack, "server"); refresh.run(); });
					JButton manageB = new JButton("Manage\u2026");
					manageB.setEnabled(nat);
					manageB.setToolTipText(nat ? "Its console, settings, game settings, backups and scheduled tasks" : ServerTools.HERE_MCSS);
					manageB.addActionListener(ev -> ServerToolsUi.manage(f, id, nm));
					if (Boolean.TRUE.equals(s.get("gone"))) {
						// MCSS has deleted it: said so, and Manage's place is taken by the one thing left to do with it.
						((JLabel) sr[1]).setText("Deleted in MC Server Soft: its folder is gone. Launchers are no longer shown it.   \u00b7   " + Json.str(s.get("dir")));
						((JLabel) sr[1]).setForeground(new Color(0xFF7B72));
						for (java.awt.event.ActionListener al : manageB.getActionListeners()) manageB.removeActionListener(al);
						manageB.setText("Remove from Gatekeeper"); manageB.setEnabled(true);
						manageB.setToolTipText("Takes it off Gatekeeper's list. There are no files left to delete.");
						manageB.addActionListener(ev -> {
							if (JOptionPane.showConfirmDialog(f, "Remove " + nm + " from Gatekeeper? MCSS has already deleted it.", "Remove from Gatekeeper", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
							new Thread(() -> {
								String msg;
								try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/tools/delete?server=" + id, "{\"files\":false}"))).get("message")); } catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
								String m2 = msg;
								try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview", null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + m2 + "</div></html>", "Remove from Gatekeeper", JOptionPane.PLAIN_MESSAGE); }); } catch (Exception ignored) {}
							}, "remove-gone").start();
						});
					}
					((JButton) sr[2]).getParent().add(javax.swing.Box.createHorizontalStrut(8)); ((JButton) sr[2]).getParent().add(manageB);
					overList.add((JPanel) sr[0]); overList.add(javax.swing.Box.createVerticalStrut(8));
				}
				JButton setupHere = new JButton("Server setup…");
				setupHere.setToolTipText("Which MCSS and which of its servers Gatekeeper looks after, and this machine's Tailscale address");
				setupHere.addActionListener(ev -> setup.run());
				JButton newHere = new JButton("New server\u2026");
				boolean natOk = Boolean.TRUE.equals(r.get("native_ok"));
				newHere.setEnabled(natOk);
				newHere.setToolTipText(natOk ? "Makes a Minecraft server from nothing: Gatekeeper fetches its program and sets it up" : ServerTools.HERE_MCSS);
				newHere.addActionListener(ev -> ServerToolsUi.newServer(f, () -> new Thread(() -> { try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview", null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); }); } catch (Exception ignored) {} }, "overview").start()));
				JPanel setupLine = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 8)); setupLine.add(newHere); setupLine.add(setupHere); setupLine.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				if (Json.str(r.get("host")).equals("mcss")) {
					// Windows with MCSS: Gatekeeper may run servers of its own as well, if the owner says so.
					javax.swing.JCheckBox own = new javax.swing.JCheckBox("Gatekeeper may also run servers itself, beside MCSS's", natOk);
					own.setToolTipText("New server then makes servers in " + Json.str(r.get("native_folder")) + " that Gatekeeper runs itself, with Manage for each. MCSS's own servers stay MCSS's.");
					own.addActionListener(ev -> new Thread(() -> {
						String msg;
						try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/tools/native", Json.write(m("on", own.isSelected()))))).get("message")); } catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
						String m2 = msg;
						try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview", null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + m2 + "</div></html>", "Servers run by Gatekeeper", JOptionPane.PLAIN_MESSAGE); }); } catch (Exception ignored) {}
					}, "native").start());
					setupLine.add(own);
					if (!natOk) { JLabel inMcss = new JLabel("Until then, New server and Manage are done in MC Server Soft"); inMcss.setForeground(new Color(0x8F98A0)); setupLine.add(inMcss); }
				}
				overList.add(setupLine);
				overList.revalidate(); overList.repaint();
			};
			fillOverviewRef[0] = fillOverview;
			Runnable loadOverview = () -> new Thread(() -> {
				try { Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/overview", null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverview.accept(r); }); } catch (Exception ignored) {}
			}, "overview").start();
			backB.addActionListener(e -> { inServer[0] = false; head.setText("Gatekeeper"); backB.setVisible(false); levels.show(stack, "overview"); loadOverview.run(); });
			new Timer(3000, e -> { if (!inServer[0]) loadOverview.run(); }).start();
			loadOverview.run();
			serverPick.addActionListener(e -> {
				int i = serverPick.getSelectedIndex();
				String id = i >= 0 && i < serverIds.size() ? serverIds.get(i) : "";
				if (!id.equals(panelServer) && serverIds.size() > 1) { panelServer = id; refresh.run(); }
			});
			serverRun.addActionListener(e -> {
				boolean up = serverUpNow[0];
				if (up && JOptionPane.showConfirmDialog(f, "Stop the Minecraft server? Anyone playing is disconnected (the world is saved first).", "Minecraft server", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/server/stop" : "/admin/server/start", "{}"))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
					String m2 = msg;
					SwingUtilities.invokeLater(() -> serverState.setText(m2));
					try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
					refresh.run();
				}, "server-run").start();
			});
			// ---- the Access Manager's tables: colours, a menu on each permission, and shutting someone out
			// A permission is white (not allowed), green (allowed) or red (denied); a click on it offers the three.
			javax.swing.table.DefaultTableCellRenderer paint = new javax.swing.table.DefaultTableCellRenderer() {
				@Override public java.awt.Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
					java.awt.Component c = super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
					String s = String.valueOf(v);
					boolean perm = s.startsWith("Allowed") || s.startsWith("Denied") || s.startsWith("Not allowed");
					c.setBackground(s.startsWith("Allowed") ? new Color(0xC8EFD0) : s.startsWith("Denied") ? new Color(0xF6C4C4) : perm ? Color.WHITE
							: s.startsWith("Blacklisted") || s.startsWith("Suspended") ? new Color(0xF6C4C4) : sel ? tb.getSelectionBackground() : tb.getBackground());
					c.setForeground(perm || s.startsWith("Blacklisted") || s.startsWith("Suspended") ? Color.BLACK : sel ? tb.getSelectionForeground() : tb.getForeground());
					// (Where they are right now: green in a game, blue with the launcher open, grey otherwise.)
					if (s.equals("In game")) c.setForeground(new Color(0x8BD46E)); else if (s.equals("In the launcher")) c.setForeground(new Color(0x66C0F4)); else if (s.equals("Offline")) c.setForeground(new Color(0x8F98A0));
					return c;
				}
			};
			table.setDefaultRenderer(Object.class, paint);
			ptable.setDefaultRenderer(Object.class, paint);
			java.util.function.BiConsumer<String, String> post = (path, body) -> new Thread(() -> {
				String res = "ok", msg = "";
				try { Map<String, Object> rr = Json.map(Json.parse(call("POST", path, body))); res = Json.str(rr.get("result")); msg = Json.str(rr.get("message")); if (!Json.str(rr.get("error")).isEmpty()) { res = "failed"; msg = Json.str(rr.get("error")); } }
				catch (IOException ex) { res = "failed"; msg = "Gatekeeper isn't running."; }
				String m2 = msg; boolean tell = !msg.isEmpty() && (!res.equals("ok") || path.contains("ban") || path.contains("kick"));
				SwingUtilities.invokeLater(() -> { if (tell) JOptionPane.showMessageDialog(f, "<html><div style='width:440px'>" + m2 + "</div></html>", "Gatekeeper", JOptionPane.PLAIN_MESSAGE); refresh.run(); });
			}, "post").start();
			String[] devPerm = {"server", "mcss", "machine", "admin"};   // the launchers table's columns 6 to 9, the players table's 4 to 7
			java.awt.event.MouseAdapter menus = new java.awt.event.MouseAdapter() {
				@Override public void mouseClicked(java.awt.event.MouseEvent ev) {
					JTable tb = (JTable) ev.getSource();
					boolean players = tb == ptable;
					int row = tb.rowAtPoint(ev.getPoint()), col = tb.columnAtPoint(ev.getPoint()), first = players ? 4 : 6;
					if (row < 0 || col < first || col > first + 3) return;
					String who = players ? (row < pnames.size() ? pnames.get(row) : null) : (row < ids.size() ? ids.get(row) : null);
					if (who == null) return;
					String perm = devPerm[col - first];
					javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
					for (String[] st : new String[][] {{"allowed", "Allowed"}, {"none", "Not allowed"}, {"denied", "Denied (stays denied whatever the player is given)"}}) {
						javax.swing.JMenuItem it = new javax.swing.JMenuItem(st[1]);
						it.addActionListener(a2 -> post.accept(players ? "/admin/player/perm" : "/admin/perm", Json.write(players ? m("player", who, "perm", perm, "state", st[0]) : m("id", who, "perm", perm, "state", st[0]))));
						menu.add(it);
					}
					menu.show(tb, ev.getX(), ev.getY());
				}
			};
			table.addMouseListener(menus);
			ptable.addMouseListener(menus);
			// Shutting out a launcher (this device only) or a player (every device), and letting them back.
			java.util.function.BiConsumer<Boolean, String> shutOut = (players, kind) -> {
				int row = (players ? ptable : table).getSelectedRow();
				if (row < 0 || row >= (players ? pnames.size() : ids.size())) { JOptionPane.showMessageDialog(f, players ? "Pick a player in the list first." : "Pick a launcher in the list first."); return; }
				String who = players ? pnames.get(row) : ids.get(row);
				String reason = "";
				if (!kind.isEmpty()) {
					String what = kind.equals("blacklist") ? "Blacklist" : kind.equals("suspend") ? "Suspend" : "Kick";
					String explain = kind.equals("blacklist") ? "Blacklisted: the launcher still updates the game, but is told nothing about the machine, MCSS or the server, and can only ask to be let back."
							: kind.equals("suspend") ? "Suspended: the launcher can't start the game or use any control until it's lifted, and can only ask for that."
							: "Kicks them from the server now; they can come straight back.";
					Object why = JOptionPane.showInputDialog(f, "<html><div style='width:400px'>" + what + (players ? " this player (on every device)? " : " this launcher (this device only)? ") + explain + "<br><br>Reason (optional; they see it):</div></html>", what, JOptionPane.PLAIN_MESSAGE, null, null, "");
					if (why == null) return;
					reason = why.toString();
				}
				if (kind.equals("kick")) post.accept("/admin/kick", Json.write(m("player", who, "reason", reason)));
				else post.accept("/admin/ban", Json.write(players ? m("player", who, "kind", kind, "reason", reason) : m("id", who, "kind", kind, "reason", reason)));
			};
			JButton dSuspend = new JButton("Suspend\u2026"), dBlack = new JButton("Blacklist\u2026"), dLift = new JButton("Lift");
			dSuspend.setToolTipText("Suspends the selected launcher: it still updates the game and sees the server's status, but can't start the game or use any control. It can ask to be let back.");
			dBlack.setToolTipText("Blacklists the selected launcher: as suspended, and it is shown nothing about the machine, MCSS or the server. It can ask to be let back.");
			dSuspend.addActionListener(e -> shutOut.accept(false, "suspend")); dBlack.addActionListener(e -> shutOut.accept(false, "blacklist")); dLift.addActionListener(e -> shutOut.accept(false, ""));
			dLift.setToolTipText("Lifts a suspension or blacklisting of the selected launcher");
			for (JButton b : new JButton[] {dSuspend, dBlack, dLift}) buttons.add(b);
			JButton pKick = new JButton("Kick\u2026"), pSuspend = new JButton("Suspend\u2026"), pBlack = new JButton("Blacklist\u2026"), pLift = new JButton("Lift");
			pKick.setToolTipText("Kicks the selected player off the Minecraft server now. They can join again.");
			pSuspend.setToolTipText("Suspends the selected player on every device: kicked, taken off the whitelist until lifted, and their launchers can't start the game or use any control.");
			pBlack.setToolTipText("Blacklists the selected player on every device: as suspended, and their launchers are shown nothing about the machine, MCSS or the server.");
			String[] pTips = {"Server control for this player: every confirmed device of theirs may start and stop this Minecraft server.", "MCSS control for this player: every confirmed device of theirs may start and close MCSS (it includes server control).",
					"Machine wake/sleep for this player: every confirmed device of theirs may wake this machine and put it to sleep.", "Gatekeeper control for this player: their devices may manage the others' access from the launcher's Remote Gatekeeper tab."};
			for (int i = 0; i < pB.length; i++) pB[i].setToolTipText(pTips[i]);
			if (builtin()) { pB[1].setEnabled(false); pB[1].setToolTipText("MCSS control: " + noMcssShort() + ". Use Server control instead."); }
			if (!sleepSupported()) { pB[2].setEnabled(false); pB[2].setToolTipText("Machine wake/sleep: " + notSupported().substring(0, 1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1) + "."); }
			pKick.addActionListener(e -> shutOut.accept(true, "kick")); pSuspend.addActionListener(e -> shutOut.accept(true, "suspend")); pBlack.addActionListener(e -> shutOut.accept(true, "blacklist")); pLift.addActionListener(e -> shutOut.accept(true, ""));
			pLift.setToolTipText("Lifts a suspension or blacklisting of the selected player");
			for (JButton b : new JButton[] {pKick, pSuspend, pBlack, pLift}) pbuttons.add(b);
			// The buttons, in three sets: what the launcher (or player) may do with the server, shutting someone out, and
			// the list itself.
			revokeB.setText("Remove server accesses");
			java.util.function.BiFunction<String, JButton[], JPanel> set = (title, bs) -> {
				JPanel g = new JPanel();
				dev.gatehouse.look.WrapLayout.on(g, 6, 2);   // (a set wider than the window puts its own buttons in rows too)
				javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), title);
				tb.setTitleColor(new Color(0x8F98A0));
				g.setBorder(tb);
				for (JButton b : bs) g.add(b);
				return g;
			};
			buttons.removeAll();
			dev.gatehouse.look.WrapLayout.on(buttons, 6, 1);
			for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {sleepB, mcssB, serverB, adminB, revokeB}), set.apply("Administrative actions", new JButton[] {dSuspend, dBlack, dLift}), set.apply("List", new JButton[] {refreshB, forget}))) {
				buttons.add(g);
			}
			pbuttons.removeAll();
			dev.gatehouse.look.WrapLayout.on(pbuttons, 6, 1);
			for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {pB[2], pB[1], pB[0], pB[3]}), set.apply("Administrative actions", new JButton[] {pKick, pSuspend, pBlack, pLift}))) {
				pbuttons.add(g);
			}
			mcssRun.addActionListener(e -> {
				boolean up = mcssUp[0], force = mcssClose[0].equals("force");
				if (up && JOptionPane.showConfirmDialog(f, force ? "Force close MCSS? Its X button only hides it, so it is ended instead." : "Close MCSS?", "MCSS", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				mcssRun.setEnabled(false);
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/mcss/close" : "/admin/mcss/start", "{\"force\":" + force + "}"))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
					String m2 = msg;
					SwingUtilities.invokeLater(() -> { mcssState.setText(m2); });
					try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
					refresh.run();
				}, "mcss").start();
			});
			adminB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"admin\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[3] + "}"); });
			signB.addActionListener(e -> {
				javax.swing.JTextField user = new javax.swing.JTextField(24);
				javax.swing.JPasswordField pass = new javax.swing.JPasswordField(24);
				JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
				form.add(new JLabel("<html><div style='width:380px'>Sign Gatekeeper in to MCSS with a panel account that can view, start and stop the server. MCSS hands back a key, which is kept on this PC; the password isn't kept.</div></html>"));
				form.add(new JLabel("Username")); form.add(user);
				form.add(new JLabel("Password")); form.add(pass);
				if (JOptionPane.showConfirmDialog(f, form, "Sign in to MCSS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				String body = Json.write(m("username", user.getText().trim(), "password", new String(pass.getPassword())));
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/signin", body))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
					String m2 = msg;
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, m2, "Sign in to MCSS", JOptionPane.PLAIN_MESSAGE));
					refresh.run();
				}, "signin").start();
			});
			keyB.addActionListener(e -> {
				javax.swing.JPasswordField key = new javax.swing.JPasswordField(36);
				JButton paste = new JButton("Paste");
				paste.addActionListener(ev -> {
					try { Object c = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); if (c != null) key.setText(c.toString().strip()); }
					catch (Exception ignored) {}
				});
				JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); row.add(key); row.add(paste);
				JPanel form = new JPanel(new BorderLayout(0, 6));
				form.add(new JLabel("<html><div style='width:420px'>An MCSS API key for Gatekeeper (make it in MCSS's web panel, under API Keys, with permission to view, start and stop the server). It's kept in host.json on this PC only. Leave empty to clear it.</div></html>"), BorderLayout.NORTH);
				form.add(row, BorderLayout.CENTER);
				if (JOptionPane.showConfirmDialog(f, form, "Set MCSS key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				act.accept("/admin/settings", Json.write(m("mcss_api_key", new String(key.getPassword()).trim())));
			});
			forget.addActionListener(e -> { String id = chosen.get(); if (id != null && JOptionPane.showConfirmDialog(f, "Forget this launcher? It can ask again later.", "Forget", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act.accept("/admin/forget", "{\"id\":\"" + id + "\"}"); });
			refreshB.addActionListener(e -> refresh.run());
			new Timer(3000, e -> refresh.run()).start();
			refresh.run();
			f.setContentPane(p);
			f.pack();
			dev.gatehouse.look.AppIcon.apply(f, true, 0, false);
			if (System.getenv("OJ_PANEL_PNG") == null) {
				java.awt.Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
				int w = Math.min(1240, scr.width), h = Math.min(800, scr.height);
				try {
					Map<String, Object> was = Json.map(Json.parse(read(dir.resolve("window.json"))));
					int ww = (int) Double.parseDouble(Json.str(was.get("w"))), wh = (int) Double.parseDouble(Json.str(was.get("h")));
					if (ww >= 700 && wh >= 480) { w = Math.min(ww, scr.width); h = Math.min(wh, scr.height); }
				} catch (RuntimeException ignored) {}
				f.setSize(w, h);
				f.setMinimumSize(new Dimension(Math.min(900, scr.width), Math.min(560, scr.height)));
				f.addComponentListener(new java.awt.event.ComponentAdapter() {
					@Override public void componentResized(java.awt.event.ComponentEvent e) {
						if ((f.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0 || (f.getExtendedState() & JFrame.ICONIFIED) != 0) return;
						try { Files.writeString(dir.resolve("window.json"), Json.write(m("w", f.getWidth(), "h", f.getHeight())), StandardCharsets.UTF_8); } catch (IOException ignored) {}
					}
				});
			}
			// Testing: OJ_PANEL_PNG=<file> saves a picture of each tab (<file>-access.png, -players.png, -status.png) after
			// the first readings, instead of showing the window.
			String shot = System.getenv("OJ_PANEL_PNG");
			if (shot != null && !shot.isEmpty()) {
				Timer once = new Timer(3500, e -> {
					setup.run();
					pairB.doClick();
					try {   // (the first screen: <file>-overview.png; then into the first server for the rest)
						fillOverview.accept(Json.map(Json.parse(call("GET", "/admin/overview", null))));
						p.validate();
						java.awt.image.BufferedImage oi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D og = oi.createGraphics(); p.paint(og); og.dispose();
						javax.imageio.ImageIO.write(oi, "png", new java.io.File(shot + "-overview.png"));
						firstTabs.setSelectedIndex(1); p.validate();
						java.awt.image.BufferedImage ni = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D ng = ni.createGraphics(); p.paint(ng); ng.dispose();
						javax.imageio.ImageIO.write(ni, "png", new java.io.File(shot + "-notes.png"));
						firstTabs.setSelectedIndex(3); p.validate();
						java.awt.image.BufferedImage hi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D hg = hi.createGraphics(); p.paint(hg); hg.dispose();
						javax.imageio.ImageIO.write(hi, "png", new java.io.File(shot + "-hostsetup.png"));
						firstTabs.setSelectedIndex(4); p.validate();
						java.awt.image.BufferedImage wpi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D wpg = wpi.createGraphics(); p.paint(wpg); wpg.dispose();
						javax.imageio.ImageIO.write(wpi, "png", new java.io.File(shot + "-webpanel.png"));
						gear.doClick(0); p.validate();
						java.awt.image.BufferedImage gi2 = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D gg2 = gi2.createGraphics(); p.paint(gg2); gg2.dispose();
						javax.imageio.ImageIO.write(gi2, "png", new java.io.File(shot + "-options.png"));
						optBack.doClick(0);
						levels.show(stack, "wizard"); guide[0].openNow(System.getenv("OJ_WIZARD_STEP")); p.validate();
						java.awt.image.BufferedImage wi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D wg = wi.createGraphics(); p.paint(wg); wg.dispose();
						javax.imageio.ImageIO.write(wi, "png", new java.io.File(shot + "-wizard.png"));
						levels.show(stack, "overview");
						firstTabs.setSelectedIndex(0);
					} catch (IOException ex) { ex.printStackTrace(); }
					inServer[0] = true; backB.setVisible(true); levels.show(stack, "server");
					try {
						// (the Updates Pulled tab, filled in directly: <file>-pulls.png)
						tabs.setSelectedIndex(2);
						try { fillPulls.accept(Json.list(Json.map(Json.parse(call("GET", "/admin/pulls", null))).get("pulls"))); } catch (Exception ignored) {}
						p.validate();
						java.awt.image.BufferedImage pi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D pg = pi.createGraphics(); p.paint(pg); pg.dispose();
						javax.imageio.ImageIO.write(pi, "png", new java.io.File(shot + "-pulls.png"));
						for (int i = 0; i < 3; i++) {
							tabs.setSelectedIndex(i == 2 ? 1 : 0);
							if (i == 1) byPlayer.doClick(); else if (i == 0) byDevice.doClick();
							p.validate();
							java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
							java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
							javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + (i == 0 ? "-access.png" : i == 1 ? "-players.png" : "-status.png")));
						}
					} catch (IOException ex) { ex.printStackTrace(); }
					ServerToolsUi.shots(shot, serverIds.isEmpty() ? "" : serverIds.get(0));
					System.exit(0);
				});
				once.setRepeats(false);
				once.start();
				return;
			}
			f.setLocationRelativeTo(null);
			if (startMinimized) f.setExtendedState(JFrame.ICONIFIED);   // (opened by Gatekeeper for a request: on the taskbar, flashing)
			f.setVisible(true);
			// A new install knows no server yet (or no address): Server setup opens by itself.
			if (!startMinimized && (servers().isEmpty() || setting("bind", "").isEmpty())) SwingUtilities.invokeLater(setup);
		});
	}

	static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("\uFEFF", ""); } catch (IOException e) { return ""; }
	}
}
