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
 * The October Journey host helper, on the PC that runs the server. Two modes:
 *
 *   --serve  (started at sign-in by a scheduled task) Listens on the Tailscale address only, for the launch pop-up's
 *            "Start MCSS" and "Close MCSS". Launchers introduce themselves (a device id they made, a secret only they
 *            know, a name); each new one waits until the owner allows it in the panel. Requests are matched to the
 *            secret and, for identification, to the Tailscale device they came from (tailscale whois). Also listens on
 *            127.0.0.1 for the panel.
 *   --panel  The owner's window: the launchers that have asked, with Allow MCSS control / Revoke / Forget, and a log.
 *
 * Its folder (default C:\Minecraft\OctoberJourneyHost, or OJ_HOST_DIR) holds host.json (settings), devices.json (the
 * launchers, with a hash of each secret, never the secret) and host.log. Closing MCSS is refused while the Minecraft
 * server is running, so the world is never cut off mid-save: stop the server first.
 */
public final class HostHelper {
	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	static Path dir;
	static Map<String, Object> cfg;

	public static void main(String[] args) throws Exception {
		dir = Path.of(System.getenv().getOrDefault("OJ_HOST_DIR", "C:\\Minecraft\\OctoberJourneyHost"));
		Files.createDirectories(dir);
		cfg = Json.map(Json.parse(read(dir.resolve("host.json"))));
		String mode = args.length > 0 ? args[0] : "--panel";
		if (mode.equals("--serve")) serve();
		else panel();
	}

	static String setting(String key, String def) { Object v = cfg.get(key); return v == null || v.toString().isEmpty() ? def : v.toString(); }
	static int port() { return Integer.parseInt(setting("port", "25570")); }

	// ------------------------------------------------------------------ devices

	/** devices.json: { "<device id>": {name, os, tailscale, ip, token_sha256, granted, first_seen, last_seen} }. */
	static synchronized Map<String, Object> devices() { return Json.map(Json.parse(read(dir.resolve("devices.json")))); }

	static synchronized void saveDevices(Map<String, Object> d) throws IOException {
		Path tmp = dir.resolve("devices.json.tmp");
		Files.writeString(tmp, Json.write(d), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve("devices.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static synchronized void log(String s) {
		try { Files.writeString(dir.resolve("host.log"), LocalDateTime.now().format(TIME) + "  " + s + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
		catch (IOException ignored) {}
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

	/** The Tailscale device a request came from, as Tailscale names it (empty if unknown). */
	static String tailscaleName(String ip) {
		try {
			Process p = new ProcessBuilder(setting("tailscale_exe", "C:\\Program Files\\Tailscale\\tailscale.exe"), "whois", "--json", ip).redirectErrorStream(true).start();
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

	static Path mcssExe() { return Path.of(setting("mcss_exe", "C:\\Minecraft\\MCSS\\mcss.exe")); }

	static List<ProcessHandle> mcssProcesses() {
		List<ProcessHandle> out = new ArrayList<>();
		String want = mcssExe().toAbsolutePath().toString();
		ProcessHandle.allProcesses().forEach(h -> h.info().command().ifPresent(c -> { if (c.equalsIgnoreCase(want)) out.add(h); }));
		return out;
	}

	static boolean minecraftRunning() {
		String hp = setting("minecraft", "127.0.0.1:25565");
		int c = hp.lastIndexOf(':');
		try (Socket s = new Socket()) { s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 1500); return true; }
		catch (IOException | RuntimeException e) { return false; }
	}

	static String[] startMcss() {
		if (!mcssProcesses().isEmpty()) return new String[] {"ok", "MCSS is already running"};
		try {
			Path exe = mcssExe();
			List<String> cmd = new ArrayList<>(List.of(exe.toString()));
			String extra = setting("mcss_args", "");   // (testing: arguments for a stand-in program)
			if (!extra.isEmpty()) cmd.addAll(List.of(extra.split(" ")));
			new ProcessBuilder(cmd).directory(exe.getParent().toFile()).start();
			for (int i = 0; i < 20 && mcssProcesses().isEmpty(); i++) Thread.sleep(500);
			return mcssProcesses().isEmpty() ? new String[] {"failed", "MCSS didn't start"} : new String[] {"ok", "MCSS started"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't start MCSS: " + e.getMessage()}; }
	}

	/** MCSS's own "when MCSS is closed" setting (mcssClosePreference in resources/mcss_config.json): HideToTaskbar,
	 *  MinimizeToTaskbar, or an exit choice. Only an exit choice makes a close request quit MCSS properly. */
	static String closePreference() {
		Path c = mcssExe().getParent().resolve("resources").resolve("mcss_config.json");
		return Json.str(Json.map(Json.parse(read(c))).get("mcssClosePreference"));
	}

	/** How a close works with MCSS's current setting: "close" (its X button exits MCSS, so it's asked to close) or
	 *  "force" (its X button only hides it, so closing means ending it). The launcher labels its button by this. */
	static String closeMode() {
		String pref = closePreference();
		return pref.contains("Hide") || pref.contains("Minimize") ? "force" : "close";
	}

	/** Closes MCSS: refused while the Minecraft server runs. With MCSS's exit setting it asks the window to close
	 *  (exactly as its X button, which then quits MCSS the way the tray menu's Close does) and waits up to 30 seconds,
	 *  never forcing. With a hide setting it ends MCSS, but only when the launcher asked for a force close (its button
	 *  said "Force close MCSS"). Only the configured mcss.exe is touched. */
	static String[] closeMcss(boolean force) {
		List<ProcessHandle> ps = mcssProcesses();
		if (ps.isEmpty()) return new String[] {"ok", "MCSS isn't running"};
		if (minecraftRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then close MCSS"};
		if (closeMode().equals("force")) {
			if (!force) return new String[] {"refused", "MCSS is set to hide when closed (" + closePreference() + "), so closing it means force-closing it: use Force close MCSS. "
					+ "(To close it properly instead, set MCSS's close option to exit MCSS, in MCSS's settings on the server PC.)"};
			for (ProcessHandle h : ps) h.destroyForcibly();
			try { for (int i = 0; i < 20 && !mcssProcesses().isEmpty(); i++) Thread.sleep(250); } catch (InterruptedException ignored) {}
			return mcssProcesses().isEmpty() ? new String[] {"ok", "MCSS force-closed (its X button is set to hide it). The Minecraft server was already stopped, so the world is safe."}
					: new String[] {"failed", "MCSS wouldn't close"};
		}
		try {
			for (ProcessHandle h : ps) {
				// Windows: ask the window to close, like its X button (taskkill without /F sends WM_CLOSE; never /F).
				new ProcessBuilder("taskkill", "/PID", String.valueOf(h.pid())).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
			}
			for (int i = 0; i < 60 && !mcssProcesses().isEmpty(); i++) Thread.sleep(500);
			return mcssProcesses().isEmpty() ? new String[] {"ok", "MCSS closed properly"}
					: new String[] {"failed", "MCSS was asked to close but is still open after 30 seconds (it may be asking something on the server PC's screen). It wasn't forced."};
		} catch (Exception e) { return new String[] {"failed", "Couldn't close MCSS: " + e.getMessage()}; }
	}

	// ------------------------------------------------------------------ the helper

	static void serve() throws Exception {
		log("helper starting (port " + port() + ")");
		// The panel's side, on this PC only.
		HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
		local.createContext("/admin/", HostHelper::admin);
		local.start();
		// The launchers' side, on the Tailscale address only. Tailscale may still be starting at sign-in: retried.
		String bind = setting("bind", "100.91.134.102");
		while (true) {
			try {
				HttpServer remote = HttpServer.create(new InetSocketAddress(bind, port()), 0);
				remote.createContext("/v1/", HostHelper::launcher);
				remote.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
				remote.start();
				log("listening on " + bind + ":" + port());
				break;
			} catch (IOException e) { Thread.sleep(10_000); }
		}
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
				devs.put(id, d);
				saveDevices(devs);
				if (fresh) log("new launcher asked for MCSS control: " + who(d) + " (" + ip + ")");
				reply(x, 200, m("state", Boolean.TRUE.equals(d.get("granted")) ? "granted" : "pending"));
				return;
			}
			String id = x.getRequestHeaders().getFirst("X-Device"), token = x.getRequestHeaders().getFirst("X-Token");
			Map<String, Object> d = Json.map(devs.get(id == null ? "" : id));
			if (d.isEmpty() || token == null || !sha256(token).equals(d.get("token_sha256"))) { reply(x, 401, m("state", "unknown", "error", "say hello first")); return; }
			// Three separate permissions, each allowed per launcher in the panel: MCSS control ("granted"), PC sleep, and
			// server control (start and stop the Minecraft server through this helper's own MCSS key, so the launcher
			// needs no panel sign-in or key of its own).
			boolean granted = Boolean.TRUE.equals(d.get("granted")), canSleep = Boolean.TRUE.equals(d.get("sleep")), canServer = Boolean.TRUE.equals(d.get("server"));
			// A fourth, helper control: the launcher may do what the host panel does (see the launchers and the activity,
			// switch the other three permissions, forget a launcher). Only the panel on this PC gives or removes it, and
			// the helper's MCSS key can't be read or set from a launcher.
			boolean canAdmin = Boolean.TRUE.equals(d.get("admin"));
			if (path.equals("/v1/mcss") && x.getRequestMethod().equals("GET")) {
				reply(x, 200, m("state", granted ? "granted" : "pending", "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "close", closeMode(),
						"sleep", canSleep, "server", canServer, "admin", canAdmin, "server_ready", !setting("mcss_api_key", "").isEmpty()));
				return;
			}
			if (path.startsWith("/v1/admin/")) {
				if (!canAdmin) { reply(x, 403, m("error", "this device isn't allowed to manage launchers (the owner allows it in the host panel)")); return; }
				boolean post = x.getRequestMethod().equals("POST");
				if (path.equals("/v1/admin/devices") && !post) {
					Map<String, Object> all = devices();
					for (Object v : all.values()) Json.map(v).remove("token_sha256");
					reply(x, 200, m("devices", all, "you", id, "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "has_key", !setting("mcss_api_key", "").isEmpty()));
				} else if (path.equals("/v1/admin/log") && !post) {
					String[] lines = read(dir.resolve("host.log")).split("\\R");
					List<Object> last = new ArrayList<>();
					for (int i = Math.max(0, lines.length - 100); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
					reply(x, 200, m("log", last));
				} else if ((path.equals("/v1/admin/perm") || path.equals("/v1/admin/forget")) && post) {
					Map<String, Object> b = body(x);
					String tid = Json.str(b.get("id"));
					Map<String, Object> t = Json.map(devs.get(tid));
					if (t.isEmpty()) { reply(x, 404, m("error", "unknown launcher")); return; }
					if (path.endsWith("forget")) {
						if (tid.equals(id)) { reply(x, 403, m("error", "This is the launcher you're using: forget it in the host panel on the server PC")); return; }
						if (Boolean.TRUE.equals(t.get("admin"))) { reply(x, 403, m("error", "That launcher has helper control: only the host panel on the server PC can forget it")); return; }
						devs.remove(tid); log("forgot " + who(t) + " (asked by " + who(d) + ")");
					} else {
						String perm = Json.str(b.get("perm"));
						boolean v = Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value")));
						String key = perm.equals("mcss") ? "granted" : perm.equals("sleep") ? "sleep" : perm.equals("server") ? "server" : null;
						if (key == null) { reply(x, 403, m("error", perm.equals("admin") ? "Helper control is only given or removed in the host panel on the server PC" : "unknown permission")); return; }
						t.put(key, v);
						log((v ? "allowed " : "removed ") + (perm.equals("mcss") ? "MCSS control" : perm.equals("sleep") ? "PC sleep" : "server control") + (v ? " for " : " from ") + who(t) + " (asked by " + who(d) + ")");
					}
					saveDevices(devs);
					reply(x, 200, m("ok", true));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.startsWith("/v1/server")) {
				if (!canServer) { reply(x, 403, m("error", "this device isn't allowed to control the server (the owner allows it in the host panel)")); return; }
				if (path.equals("/v1/server") && x.getRequestMethod().equals("GET")) reply(x, 200, m("status", serverStatus()));
				else if (path.equals("/v1/server/log") && x.getRequestMethod().equals("GET")) reply(x, 200, m("lines", serverLog(80)));
				else if ((path.equals("/v1/server/start") || path.equals("/v1/server/stop")) && x.getRequestMethod().equals("POST")) {
					String[] r = serverAction(path.endsWith("start"));
					log((path.endsWith("start") ? "start" : "stop") + " the Minecraft server asked by " + who(d) + ": " + r[1]);
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.equals("/v1/pc/sleep") && x.getRequestMethod().equals("POST")) {
				if (!canSleep) { reply(x, 403, m("error", "this device isn't allowed to sleep the PC (the owner allows it in the host panel)")); return; }
				String[] r = sleepPc();
				log("sleep the PC asked by " + who(d) + ": " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
				return;
			}
			if (!granted) { reply(x, 403, m("state", "pending", "error", "not allowed yet: the owner allows this device in the host panel")); return; }
			String[] r;
			if (path.equals("/v1/mcss/start") && x.getRequestMethod().equals("POST")) r = startMcss();
			else if (path.equals("/v1/mcss/close") && x.getRequestMethod().equals("POST")) r = closeMcss("true".equals(Json.str(body(x).get("force"))));
			else { reply(x, 404, m("error", "no such call")); return; }
			log((path.endsWith("start") ? "start" : "close") + " MCSS asked by " + who(d) + ": " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1], "mcss", !mcssProcesses().isEmpty()));
		} catch (Exception e) {
			log("error: " + e);
			try { reply(x, 500, m("error", "the helper had a problem")); } catch (IOException ignored) {}
		}
	}

	// ------------------------------------------------------------------ the Minecraft server, through MCSS's API

	/** The server's folder (host.json "server_dir"), and its id in MCSS (from mcss_server_config.json there). */
	static Path serverDir() { return Path.of(setting("server_dir", "C:\\Minecraft\\MCSS\\servers\\octoberserver")); }
	static String serverId() {
		String id = setting("mcss_server", "");
		return !id.isEmpty() ? id : Json.str(Json.map(Json.parse(read(serverDir().resolve("mcss_server_config.json")))).get("guid"));
	}

	/** One call to MCSS's own API on this PC, with the helper's key (host.json "mcss_api_key", set in the panel). */
	static String mcssApi(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(8000); c.setRequestMethod(method);
		c.setRequestProperty("apiKey", setting("mcss_api_key", ""));
		if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		int code = c.getResponseCode();
		if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	/** MCSS's state for the server: 0 off, 1 on, 2 restarting, 3 starting, 4 stopping; -1 unknown (no key, MCSS closed). */
	static int serverStatus() {
		try { return (int) Double.parseDouble(Json.str(Json.map(Json.parse(mcssApi("GET", "/api/v2/servers/" + serverId(), null))).get("status"))); }
		catch (IOException | RuntimeException e) { return -1; }
	}

	static String[] serverAction(boolean start) {
		if (setting("mcss_api_key", "").isEmpty()) return new String[] {"refused", "The helper has no MCSS key yet: the owner sets it in the host panel (Set MCSS key)"};
		if (mcssProcesses().isEmpty()) return new String[] {"refused", "MCSS isn't running: start it first (Start MCSS)"};
		try {
			mcssApi("POST", "/api/v2/servers/" + serverId() + "/execute/action", "{\"action\":" + (start ? 2 : 1) + "}");
			return new String[] {"ok", start ? "Start requested: the server takes a minute or so to come up" : "Stop requested: the server saves the world and shuts down"};
		} catch (IOException e) {
			return new String[] {"failed", e.getMessage().contains("401") || e.getMessage().contains("403") ? "MCSS didn't accept the helper's key (the owner checks it in the host panel)" : "MCSS said no (" + e.getMessage() + ")"};
		}
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
		if (minecraftRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then sleep the PC"};
		String custom = setting("sleep_command", "");
		List<String> cmd = custom.isEmpty()
				? List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
						"Start-Sleep 5; Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)")
				: List.of("cmd", "/c", custom);
		try { new ProcessBuilder(cmd).start(); }
		catch (IOException e) { return new String[] {"failed", "Couldn't sleep the PC: " + e.getMessage()}; }
		return new String[] {"ok", "The server PC is going to sleep (in about 5 seconds). Wake server device wakes it again from the home network."};
	}

	/** The panel: GET /admin/devices, POST /admin/grant {id, granted}, POST /admin/perm {id, perm: mcss|sleep, value},
	 *  POST /admin/forget {id}, GET /admin/log. */
	static void admin(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath();
			if (path.equals("/admin/devices")) {
				Map<String, Object> devs = devices();
				for (Object v : devs.values()) Json.map(v).remove("token_sha256");
				reply(x, 200, m("devices", devs, "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "port", port(), "bind", setting("bind", "100.91.134.102"),
						"has_key", !setting("mcss_api_key", "").isEmpty(), "key_works", setting("mcss_api_key", "").isEmpty() ? "" : (serverStatus() >= 0 ? "yes" : "no")));
			} else if (path.equals("/admin/settings")) {
				// The helper's own MCSS key, kept in host.json on this PC only.
				cfg.put("mcss_api_key", Json.str(body(x).get("mcss_api_key")).trim());
				Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8);
				log(setting("mcss_api_key", "").isEmpty() ? "the helper's MCSS key was cleared" : "the helper's MCSS key was set");
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/grant") || path.equals("/admin/forget") || path.equals("/admin/perm")) {
				Map<String, Object> b = body(x), devs = devices();
				String id = Json.str(b.get("id"));
				Map<String, Object> d = Json.map(devs.get(id));
				if (d.isEmpty()) { reply(x, 404, m("error", "unknown device")); return; }
				if (path.endsWith("forget")) { devs.remove(id); log("forgot " + who(d)); }
				else if (path.endsWith("perm")) {
					String perm = Json.str(b.get("perm"));
					boolean v = Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value")));
					if (perm.equals("sleep")) { d.put("sleep", v); log((v ? "allowed PC sleep for " : "removed PC sleep from ") + who(d)); }
					else if (perm.equals("mcss")) { d.put("granted", v); log((v ? "allowed MCSS control for " : "removed MCSS control from ") + who(d)); }
					else if (perm.equals("server")) { d.put("server", v); log((v ? "allowed server control for " : "removed server control from ") + who(d)); }
					else if (perm.equals("admin")) { d.put("admin", v); log((v ? "allowed helper control for " : "removed helper control from ") + who(d)); }
					else { reply(x, 400, m("error", "unknown permission")); return; }
				}
				else { boolean g = Boolean.TRUE.equals(b.get("granted")) || "true".equals(Json.str(b.get("granted"))); d.put("granted", g); log((g ? "allowed MCSS control for " : "revoked MCSS control from ") + who(d)); }
				saveDevices(devs);
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/log")) {
				String[] lines = read(dir.resolve("host.log")).split("\\R");
				List<Object> last = new ArrayList<>();
				for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
				reply(x, 200, m("log", last));
			} else reply(x, 404, m("error", "no such call"));
		} catch (Exception e) {
			try { reply(x, 500, m("error", e.toString())); } catch (IOException ignored) {}
		}
	}

	// ------------------------------------------------------------------ the panel

	static String call(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + path).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(5000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	static void panel() throws Exception {
		if (GraphicsEnvironment.isHeadless()) { System.out.println(call("GET", "/admin/devices", null)); return; }
		SwingUtilities.invokeAndWait(() -> {
			try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
			JFrame f = new JFrame("October Journey host panel");
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 10));
			p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
			JLabel head = new JLabel("Launchers that asked to control MCSS");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
			JLabel state = new JLabel(" ");
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(head, BorderLayout.NORTH); top.add(state, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Tailscale device", "System", "First seen", "Last seen", "Server control", "MCSS control", "PC sleep", "Helper control"};
			DefaultTableModel model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
			JTable table = new JTable(model);
			table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			table.setRowHeight(24);
			List<String> ids = new ArrayList<>();
			JTextArea log = new JTextArea(8, 80);
			log.setEditable(false);
			log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JPanel mid = new JPanel(new BorderLayout(0, 8));
			JScrollPane ts = new JScrollPane(table); ts.setPreferredSize(new Dimension(940, 200));
			mid.add(ts, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			JButton serverB = new JButton("Allow server control"), mcssB = new JButton("Allow MCSS control"), sleepB = new JButton("Allow PC sleep"), adminB = new JButton("Allow helper control"), forget = new JButton("Forget"), refreshB = new JButton("Refresh");
			adminB.setToolTipText("Helper control lets that launcher manage the others' permissions from its Server config tab, as this panel does (not the MCSS key, and not helper control itself).");
			for (JButton b : new JButton[] {serverB, mcssB, sleepB, adminB, forget, refreshB}) buttons.add(b);
			Map<String, boolean[]> perms = new java.util.HashMap<>();   // id -> {mcss, sleep, server}, from the last refresh
			// The helper's own MCSS key: needed for "server control" (it starts and stops the server for allowed launchers).
			JLabel keyState = new JLabel(" ");
			JButton keyB = new JButton("Set MCSS key\u2026");
			JPanel keyRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			keyRow.add(keyB); keyRow.add(keyState);
			JPanel south = new JPanel(new BorderLayout(0, 6));
			south.add(buttons, BorderLayout.NORTH); south.add(keyRow, BorderLayout.SOUTH);
			mid.add(south, BorderLayout.SOUTH);
			p.add(mid, BorderLayout.CENTER);
			JPanel bottom = new JPanel(new BorderLayout(0, 4));
			bottom.add(new JLabel("Activity"), BorderLayout.NORTH);
			bottom.add(new JScrollPane(log), BorderLayout.CENTER);
			p.add(bottom, BorderLayout.SOUTH);
			Runnable refresh = () -> new Thread(() -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/devices", null)));
					List<Object> lg = Json.list(Json.map(Json.parse(call("GET", "/admin/log", null))).get("log"));
					SwingUtilities.invokeLater(() -> {
						int sel = table.getSelectedRow();
						String keep = sel >= 0 && sel < ids.size() ? ids.get(sel) : null;
						model.setRowCount(0); ids.clear(); perms.clear();
						for (Map.Entry<String, Object> e : Json.map(r.get("devices")).entrySet()) {
							Map<String, Object> d = Json.map(e.getValue());
							ids.add(e.getKey());
							boolean pm = Boolean.TRUE.equals(d.get("granted")), ps = Boolean.TRUE.equals(d.get("sleep")), pv = Boolean.TRUE.equals(d.get("server")), pa = Boolean.TRUE.equals(d.get("admin"));
							perms.put(e.getKey(), new boolean[] {pm, ps, pv, pa});
							model.addRow(new Object[] {d.get("name"), d.get("tailscale"), d.get("os"), d.get("first_seen"), d.get("last_seen"),
									pv ? "Allowed" : "Not allowed", pm ? "Allowed" : "Not allowed", ps ? "Allowed" : "Not allowed", pa ? "Allowed" : "Not allowed"});
							if (e.getKey().equals(keep)) table.setRowSelectionInterval(ids.size() - 1, ids.size() - 1);
						}
						boolean hasKey = Boolean.TRUE.equals(r.get("has_key"));
						String works = Json.str(r.get("key_works"));
						keyState.setForeground(!hasKey ? new Color(0xB45309) : works.equals("no") ? new Color(0xD23C3C) : new Color(0x2E9D48));
						keyState.setText(!hasKey ? "The helper has no MCSS key: server control won't work until it's set (make one in MCSS's panel, under API Keys)."
								: works.equals("no") ? "The helper's MCSS key is set, but MCSS isn't answering with it (MCSS closed, or the key is wrong)."
								: "The helper's MCSS key is set and working.");
						state.setForeground(new Color(0x2E9D48));
						state.setText("Helper running on " + r.get("bind") + ":" + r.get("port") + ".  MCSS " + (Boolean.TRUE.equals(r.get("mcss")) ? "is running" : "isn't running")
								+ ";  Minecraft server " + (Boolean.TRUE.equals(r.get("minecraft")) ? "is running" : "is off") + ".");
						StringBuilder sb = new StringBuilder();
						for (Object l : lg) sb.append(l).append('\n');
						if (!log.getText().equals(sb.toString())) { log.setText(sb.toString()); log.setCaretPosition(log.getDocument().getLength()); }
					});
				} catch (IOException e) {
					SwingUtilities.invokeLater(() -> { state.setForeground(new Color(0xD23C3C)); state.setText("The helper isn't running (it starts when you sign in to Windows)."); });
				}
			}, "refresh").start();
			java.util.function.BiConsumer<String, String> act = (what, body) -> new Thread(() -> {
				try { call("POST", what, body); } catch (IOException e) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "The helper isn't running.")); }
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
				adminB.setText(pp[3] ? "Remove helper control" : "Allow helper control");
				serverB.setText(pp[2] ? "Remove server control" : "Allow server control");
				mcssB.setText(pp[0] ? "Remove MCSS control" : "Allow MCSS control");
				sleepB.setText(pp[1] ? "Remove PC sleep" : "Allow PC sleep");
			};
			table.getSelectionModel().addListSelectionListener(e -> labels.run());
			model.addTableModelListener(e -> SwingUtilities.invokeLater(labels));
			mcssB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"mcss\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[0] + "}"); });
			sleepB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"sleep\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[1] + "}"); });
			serverB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"server\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[2] + "}"); });
			adminB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"admin\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[3] + "}"); });
			keyB.addActionListener(e -> {
				javax.swing.JPasswordField key = new javax.swing.JPasswordField(36);
				JButton paste = new JButton("Paste");
				paste.addActionListener(ev -> {
					try { Object c = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); if (c != null) key.setText(c.toString().strip()); }
					catch (Exception ignored) {}
				});
				JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); row.add(key); row.add(paste);
				JPanel form = new JPanel(new BorderLayout(0, 6));
				form.add(new JLabel("<html><div style='width:420px'>An MCSS API key for the helper (make it in MCSS's web panel, under API Keys, with permission to view, start and stop the server). It's kept in host.json on this PC only. Leave empty to clear it.</div></html>"), BorderLayout.NORTH);
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
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("\uFEFF", ""); } catch (IOException e) { return ""; }
	}
}
