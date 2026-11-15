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
		startMinimized = java.util.Arrays.asList(args).contains("--minimized");
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
		// Once a minute, also with no panel or launcher open, the server's log is read for joins (through the helper's
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
			scanPlayers();
			devs = devices();
			String id = x.getRequestHeaders().getFirst("X-Device"), token = x.getRequestHeaders().getFirst("X-Token");
			Map<String, Object> d = Json.map(devs.get(id == null ? "" : id));
			if (d.isEmpty() || token == null || !sha256(token).equals(d.get("token_sha256"))) { reply(x, 401, m("state", "unknown", "error", "say hello first")); return; }
			// Three separate permissions, each allowed per launcher in the panel: MCSS control ("granted"), PC sleep, and
			// server control (start and stop the Minecraft server through this helper's own MCSS key, so the launcher
			// needs no panel sign-in or key of its own).
			boolean granted = Boolean.TRUE.equals(d.get("granted")), canSleep = Boolean.TRUE.equals(d.get("sleep")), canServer = Boolean.TRUE.equals(d.get("server")) || granted;   // (MCSS control includes server control)
			// A fourth, helper control: the launcher may do what the host panel does (see the launchers and the activity,
			// switch the other three permissions, forget a launcher). Only the panel on this PC gives or removes it, and
			// the helper's MCSS key can't be read or set from a launcher.
			boolean canAdmin = Boolean.TRUE.equals(d.get("admin"));
			if (path.equals("/v1/request") && x.getRequestMethod().equals("POST")) {
				// "Please turn the server on", from any launcher this helper knows (allowed anything or not).
				String[] r = requestServer(d, id);
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
				reply(x, 200, m("clear_access", clear, "state", granted ? "granted" : "pending", "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "close", closeMode(),
						"sleep", canSleep, "server", canServer, "admin", canAdmin, "server_ready", !setting("mcss_api_key", "").isEmpty()));
				return;
			}
			if (path.startsWith("/v1/admin/")) {
				if (!canAdmin) { reply(x, 403, m("error", "this device isn't allowed to manage launchers (the owner allows it in the host panel)")); return; }
				boolean post = x.getRequestMethod().equals("POST");
				if (path.equals("/v1/admin/devices") && !post) {
					Map<String, Object> all = listed();
					reply(x, 200, m("devices", all, "you", id, "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "has_key", !setting("mcss_api_key", "").isEmpty()));
				} else if (path.equals("/v1/admin/log") && !post) {
					String[] lines = read(dir.resolve("host.log")).split("\\R");
					List<Object> last = new ArrayList<>();
					for (int i = Math.max(0, lines.length - 100); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
					reply(x, 200, m("log", last));
				} else if ((path.equals("/v1/admin/perm") || path.equals("/v1/admin/forget") || path.equals("/v1/admin/revoke")) && post) {
					Map<String, Object> b = body(x);
					String tid = Json.str(b.get("id"));
					Map<String, Object> t = Json.map(devs.get(tid));
					if (t.isEmpty()) { reply(x, 404, m("error", "unknown launcher")); return; }
					if (path.endsWith("revoke")) {
						if (Boolean.TRUE.equals(t.get("admin")) && !tid.equals(id)) { reply(x, 403, m("error", "That launcher has helper control: only the host panel on the server PC can remove its access")); return; }
						String[] r = revokeAccess(t);
						log("removed server access from " + who(t) + " (asked by " + who(d) + "): " + r[1]);
						saveDevices(devs);
						reply(x, 200, m("result", r[0], "message", r[1]));
						return;
					}
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

	/** How MCSS takes the helper's key: "yes" (it can see the server), "forbidden" (MCSS knows the key, but it has no
	 *  right to this server), "unknown" (MCSS doesn't know the key), "no" (MCSS isn't answering). */
	static String keyState() {
		try { mcssApi("GET", "/api/v2/servers/" + serverId(), null); return "yes"; }
		catch (IOException e) { return String.valueOf(e.getMessage()).contains("403") ? "forbidden" : String.valueOf(e.getMessage()).contains("401") ? "unknown" : "no"; }
	}

	// ------------------------------------------------------------------ players

	static final String[][] PERMS = {{"server", "server", "server control"}, {"mcss", "granted", "MCSS control"}, {"sleep", "sleep", "PC sleep"}, {"admin", "admin", "helper control"}};
	static long logSeen = -1;

	static Map<String, Object> players() { return Json.map(Json.parse(read(dir.resolve("players.json")))); }

	/** Ties players to launchers from the Minecraft server's own log: each "Name[/address:port] logged in" line names
	 *  a player and the address they joined from, and a launcher that talks to this helper from the same (Tailscale)
	 *  address is that player's device. That's who really joined, not what a device says. A player's device then gets
	 *  what the owner allowed that player (players.json). Only looks again when the log has changed. */
	static synchronized void scanPlayers() {
		try {
			Path lg = serverDir().resolve("logs").resolve("latest.log");
			if (!Files.isRegularFile(lg)) return;
			long stamp = Files.getLastModifiedTime(lg).toMillis() * 31 + Files.size(lg);
			if (stamp == logSeen) return;
			logSeen = stamp;
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
					for (String[] pm : PERMS) if (Boolean.TRUE.equals(pd.get(pm[0])) && !Boolean.TRUE.equals(d.get(pm[1]))) { d.put(pm[1], true); log("allowed " + pm[2] + " for " + who(d) + " (allowed for player " + name + ")"); }
				}
			}
			if (changed) saveDevices(devs);
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
			if (ok) for (String[] pm : PERMS) if (Boolean.TRUE.equals(d.get(pm[1]))) p.put("has_" + pm[0], (p.get("has_" + pm[0]) instanceof Number n ? n.intValue() : 0) + 1);
		}
		for (Map.Entry<String, Object> e : out.entrySet()) {
			Map<String, Object> pd = Json.map(defaults.get(e.getKey()));
			for (String[] pm : PERMS) Json.map(e.getValue()).put("allow_" + pm[0], Boolean.TRUE.equals(pd.get(pm[0])));
		}
		return out;
	}

	/** Allows or removes one permission for a player: on every launcher confirmed as theirs now, and remembered for
	 *  launchers they join from later. A launcher that only says it's theirs gets nothing until the server confirms it. */
	static String[] playerPerm(String name, String perm, boolean v) throws IOException {
		String[] pm = null;
		for (String[] q : PERMS) if (q[0].equals(perm)) pm = q;
		if (pm == null || !name.matches("\\w{1,16}")) return new String[] {"refused", "unknown player or permission"};
		Map<String, Object> defaults = players();
		Map<String, Object> pd = Json.map(defaults.get(name));
		if (!(defaults.get(name) instanceof Map)) defaults.put(name, pd);
		pd.put(perm, v);
		Files.writeString(dir.resolve("players.json"), Json.write(defaults), StandardCharsets.UTF_8);
		Map<String, Object> devs = devices();
		int n = 0, waiting = 0;
		for (Object o : devs.values()) {
			Map<String, Object> d = Json.map(o);
			if (!name.equals(Json.str(d.get("player")))) continue;
			if (!Boolean.TRUE.equals(d.get("player_confirmed"))) { waiting++; continue; }
			d.put(pm[1], v); n++;
		}
		saveDevices(devs);
		String msg = (v ? "Allowed " : "Removed ") + pm[2] + (v ? " for " : " from ") + name + ": " + n + " launcher" + (n == 1 ? "" : "s") + " changed"
				+ (waiting > 0 ? "; " + waiting + " not confirmed yet (" + name + " has to join the server from it once)" : "") + (v ? ". Launchers they join from later get it too." : ".");
		log(msg);
		return new String[] {"ok", msg};
	}

	// ------------------------------------------------------------------ requests for the server

	static boolean startMinimized;
	static final List<Object> requests = new ArrayList<>();   // the last few, newest last: {who, player, time}
	static final Map<String, Long> lastRequest = new java.util.HashMap<>();
	static int requestSeq = 0;
	static volatile long panelSeen = 0;   // when the host panel last asked for its list (it does every few seconds while open)

	/** A launcher asks for the server to be turned on: noted for the host panel, with a chime here and the panel
	 *  brought up minimized (its taskbar button flashes) if it isn't open. One a minute from each launcher. */
	static synchronized String[] requestServer(Map<String, Object> d, String id) {
		long now = System.currentTimeMillis();
		Long last = lastRequest.get(id);
		if (last != null && now - last < 60_000) return new String[] {"refused", "Already asked a moment ago: the owner has been told"};
		if (minecraftRunning()) return new String[] {"refused", "The server is already running"};
		lastRequest.put(id, now);
		requests.add(m("who", who(d), "player", Json.str(d.get("player")), "time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))));
		while (requests.size() > 5) requests.remove(0);
		requestSeq++;
		log("server requested by " + who(d) + (Json.str(d.get("player")).isEmpty() ? "" : " (" + Json.str(d.get("player")) + ")"));
		if (!"false".equals(setting("request_alert", "true"))) { chime(); openPanel(); }
		return new String[] {"ok", "Asked: the owner's PC chimed and shows your request. This turns into Play when the server is online."};
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

	/** Opens the host panel minimized, unless one is open already (an open panel sees the request itself). */
	static void openPanel() {
		if (System.currentTimeMillis() - panelSeen < 8000) return;
		try {
			String java = ProcessHandle.current().info().command().orElse("javaw");
			String jar = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			new ProcessBuilder(java, "-jar", jar, "--panel", "--minimized").start();
		} catch (Exception e) { log("couldn't open the host panel: " + e); }
	}

	static String header(HttpExchange x, String name) {
		String v = x.getRequestHeaders().getFirst(name);
		v = v == null ? "" : v.replaceAll("[^\\x20-\\x7E]", "").trim();
		return v.length() > 60 ? v.substring(0, 60) : v;
	}

	/** How a launcher controls the server, for the panel's list. */
	static String accessText(Map<String, Object> d) {
		String k = Json.str(d.get("access")), s = Json.str(d.get("saved")), u = Json.str(d.get("access_user"));
		String has = s.equals("signin") ? "signed in to the panel" + (u.isEmpty() ? "" : " as " + u) : s.equals("key") ? "its own API key" : s.equals("issued") ? "a key issued by the helper" : "";
		String t = k.isEmpty() ? "Unknown (older launcher)" : k.equals("helper") ? "Through the helper" + (has.isEmpty() ? "" : " (also saved: " + has + ")")
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
			d.remove("token_sha256"); d.remove("issued_pending");
		}
		return all;
	}

	/** Takes a launcher's server control away, whichever way it has it: its permissions here (server and MCSS control)
	 *  go off; a key this helper issued is deleted in MCSS, so it stops working; and the launcher is told to forget
	 *  whatever sign-in or key it has saved. A sign-in or key it got elsewhere still exists in MCSS: only removing that
	 *  account or key there blocks it for good, and the message says so. */
	static String[] revokeAccess(Map<String, Object> d) {
		String saved = Json.str(d.get("saved")), kid = Json.str(d.get("issued_key_id"));
		List<String> did = new ArrayList<>();
		if (Boolean.TRUE.equals(d.get("server")) || Boolean.TRUE.equals(d.get("granted"))) did.add("server and MCSS control switched off here");
		d.put("server", false); d.put("granted", false);
		d.remove("issued_pending");
		if (!kid.isEmpty()) {
			try { mcssApi("DELETE", "/api/v2/keys/" + kid, null); d.remove("issued_key_id"); d.remove("issued_name"); did.add("its issued key deleted in MCSS"); }
			catch (IOException e) { did.add("its issued key could NOT be deleted in MCSS (" + e.getMessage() + "): delete \"" + Json.str(d.get("issued_name")) + "\" there"); }
		}
		d.put("clear_access", true);
		did.add("the launcher is told to forget what it has saved");
		String more = saved.equals("signin") ? " Its panel account" + (Json.str(d.get("access_user")).isEmpty() ? "" : " (" + Json.str(d.get("access_user")) + ")") + " still exists in MCSS: remove or change it there (Users) to block it for good."
				: saved.equals("key") ? " The API key it typed in still exists in MCSS: delete it there (API Keys) to block it for good." : "";
		return new String[] {"ok", Character.toUpperCase(String.join("; ", did).charAt(0)) + String.join("; ", did).substring(1) + "." + more};
	}

	static String[] serverAction(boolean start) {
		if (setting("mcss_api_key", "").isEmpty()) return new String[] {"refused", "The helper has no MCSS key yet: the owner sets it in the host panel (Set MCSS key)"};
		if (mcssProcesses().isEmpty()) return new String[] {"refused", "MCSS isn't running: start it first (Start MCSS)"};
		try {
			mcssApi("POST", "/api/v2/servers/" + serverId() + "/execute/action", "{\"action\":" + (start ? 2 : 1) + "}");
			return new String[] {"ok", start ? "Start requested: the server takes a minute or so to come up" : "Stop requested: the server saves the world and shuts down"};
		} catch (IOException e) {
			return new String[] {"failed", e.getMessage().contains("401") ? "MCSS doesn't know the helper's key (the owner sets it again in the host panel)"
					: e.getMessage().contains("403") ? "The helper's MCSS key isn't allowed to " + (start ? "start" : "stop") + " this server (the owner gives the key that permission in MCSS's panel, under API Keys)"
					: "MCSS said no (" + e.getMessage() + ")"};
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
		if (minecraftRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then sleep the machine"};
		String custom = setting("sleep_command", "");
		List<String> cmd = custom.isEmpty()
				? List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
						"Start-Sleep 5; Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)")
				: List.of("cmd", "/c", custom);
		try { new ProcessBuilder(cmd).start(); }
		catch (IOException e) { return new String[] {"failed", "Couldn't sleep the PC: " + e.getMessage()}; }
		return new String[] {"ok", "The server machine is going to sleep (in about 5 seconds). Wake server machine wakes it again from the home network."};
	}

	/** The panel: GET /admin/devices, POST /admin/grant {id, granted}, POST /admin/perm {id, perm: mcss|sleep, value},
	 *  POST /admin/forget {id}, GET /admin/log. */
	static void admin(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath();
			if (path.equals("/admin/devices")) {
				panelSeen = System.currentTimeMillis();
				Map<String, Object> devs = listed();
				List<Object> asked;
				int seq;
				synchronized (HostHelper.class) { asked = new ArrayList<>(requests); seq = requestSeq; }
				reply(x, 200, m("requests", asked, "request_seq", seq, "devices", devs, "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "close", closeMode(), "port", port(), "bind", setting("bind", "100.91.134.102"),
						"has_key", !setting("mcss_api_key", "").isEmpty(), "key_works", setting("mcss_api_key", "").isEmpty() ? "" : keyState(), "mcss_user", setting("mcss_user", "")));
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
				String[] r = path.endsWith("start") ? startMcss() : closeMcss("true".equals(Json.str(body(x).get("force"))));
				log((path.endsWith("start") ? "start" : "close") + " MCSS from the host panel: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/players")) {
				reply(x, 200, m("players", playerList()));
			} else if (path.equals("/admin/player/perm")) {
				Map<String, Object> b = body(x);
				String[] r = playerPerm(Json.str(b.get("player")), Json.str(b.get("perm")), Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value"))));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/requests/clear")) {
				synchronized (HostHelper.class) { requests.clear(); }
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/server/start")) {
				// From the panel's request banner: MCSS first if it isn't running (it needs a moment before it takes the start).
				String[] r;
				if (mcssProcesses().isEmpty()) { String[] s = startMcss(); r = new String[] {s[0].equals("ok") ? "wait" : s[0], s[0].equals("ok") ? "MCSS is starting: press Start server again in a few seconds" : s[1]}; }
				else r = serverAction(true);
				log("start the Minecraft server from the host panel: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/keytest")) {
				// Troubleshooting: what MCSS answers to the helper's key (status codes and MCSS's own error text; never the key).
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("server_id", serverId());
				for (String q : new String[] {"/api/v2/servers", "/api/v2/servers/" + serverId(), "/api/v2/keys"}) {
					try {
						HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + q).openConnection();
						c.setConnectTimeout(3000); c.setReadTimeout(8000);
						c.setRequestProperty("apiKey", setting("mcss_api_key", ""));
						int code = c.getResponseCode();
						String bodyText = "";
						if (code >= 400) try (InputStream in = c.getErrorStream()) { if (in != null) bodyText = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
						else if (q.equals("/api/v2/servers")) try (InputStream in = c.getInputStream()) {
							String list = new String(in.readAllBytes(), StandardCharsets.UTF_8);
							out.put("servers_listed", Json.list(Json.parse(list)).size());
							out.put("this_server_listed", list.contains(serverId()));
						}
						out.put(q, code + (bodyText.isEmpty() ? "" : " " + (bodyText.length() > 200 ? bodyText.substring(0, 200) : bodyText)) + " " + String.valueOf(c.getHeaderField("WWW-Authenticate")));
					} catch (IOException e) { out.put(q, "error: " + e); }
				}
				reply(x, 200, out);
			} else if (path.equals("/admin/signin")) {
				// The owner signs the helper in to MCSS with a panel account (typed in the host panel on this PC): MCSS's
				// own sign-in hands back a key, and that is what's kept (host.json), never the password.
				Map<String, Object> b = body(x);
				String user = Json.str(b.get("username")).trim();
				try {
					HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + "/auth").openConnection();
					c.setConnectTimeout(3000); c.setReadTimeout(8000); c.setRequestMethod("POST"); c.setDoOutput(true);
					c.setRequestProperty("Content-Type", "application/json");
					try (OutputStream o = c.getOutputStream()) { o.write(Json.write(m("username", user, "password", Json.str(b.get("password")))).getBytes(StandardCharsets.UTF_8)); }
					int code = c.getResponseCode();
					if (code == 401 || code == 403) { log("MCSS sign-in as " + user + " was refused"); reply(x, 200, m("result", "refused", "message", "MCSS didn't accept that username and password")); return; }
					if (code < 200 || code >= 300) { reply(x, 200, m("result", "failed", "message", "MCSS answered " + code)); return; }
					String key;
					try (InputStream in = c.getInputStream()) { key = Json.str(Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("apiKey")); }
					if (key.isEmpty()) { reply(x, 200, m("result", "failed", "message", "MCSS signed in but handed back no key")); return; }
					cfg.put("mcss_api_key", key); cfg.put("mcss_user", user);
					Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8);
					boolean works = serverStatus() >= 0;
					log("the helper signed in to MCSS as " + user + (works ? "" : " (but that account can't see the server)"));
					reply(x, 200, m("result", works ? "ok" : "failed", "message", works ? "Signed in as " + user : "Signed in as " + user + ", but that account can't see the server (give it access to it in MCSS's panel)"));
				} catch (IOException e) {
					reply(x, 200, m("result", "failed", "message", "MCSS's panel isn't answering (is MCSS running?)"));
				}
			} else if (path.equals("/admin/settings")) {
				// The helper's own MCSS key, kept in host.json on this PC only.
				cfg.remove("mcss_user");
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
			// MCSS itself: start it, or close it (how follows MCSS's own X-button setting, as for the launchers).
			JButton mcssRun = new JButton("Start MCSS");
			boolean[] mcssUp = {false}; String[] mcssClose = {"close"};
			JPanel stateRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
			stateRow.add(mcssRun); stateRow.add(state);
			// A launcher asked for the server: a banner here, and the taskbar button flashes until it's looked at.
			JLabel reqText = new JLabel(" ");
			reqText.setFont(reqText.getFont().deriveFont(Font.BOLD, 14f));
			JButton reqStart = new JButton("Start server"), reqClear = new JButton("Dismiss");
			JPanel reqRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
			reqRow.setBackground(new Color(0xFFF1C2));
			reqRow.setBorder(BorderFactory.createLineBorder(new Color(0xE0A91B)));
			reqRow.add(reqText); reqRow.add(reqStart); reqRow.add(reqClear);
			reqRow.setVisible(false);
			int[] seqSeen = {-1};
			JPanel headRows = new JPanel(new BorderLayout(0, 6)); headRows.add(head, BorderLayout.NORTH); headRows.add(reqRow, BorderLayout.SOUTH);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(headRows, BorderLayout.NORTH); top.add(stateRow, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Player", "Tailscale device", "System", "First seen", "Last seen", "Server control", "MCSS control", "PC sleep", "Helper control", "Controls the server"};
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
			JButton serverB = new JButton("Allow server control"), mcssB = new JButton("Allow MCSS control"), sleepB = new JButton("Allow PC sleep"), adminB = new JButton("Allow helper control"), forget = new JButton("Forget"), refreshB = new JButton("Refresh");
			adminB.setToolTipText("Helper control lets that launcher manage the others' permissions from its Server config tab, as this panel does (not the MCSS key, and not helper control itself).");
			for (JButton b : new JButton[] {serverB, mcssB, sleepB, adminB, forget, refreshB}) buttons.add(b);
			Map<String, boolean[]> perms = new java.util.HashMap<>();   // id -> {mcss, sleep, server}, from the last refresh
			// The helper's own MCSS key: needed for "server control" (it starts and stops the server for allowed launchers).
			JLabel keyState = new JLabel(" ");
			JButton keyB = new JButton("Set MCSS key\u2026");
			JButton signB = new JButton("Sign in to MCSS\u2026");
			JPanel keyRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			keyRow.add(signB); keyRow.add(keyB); keyRow.add(keyState);
			// For the selected launcher: an MCSS key of its own, made and handed over by the helper; and taking away
			// whatever lets it control the server.
			JButton revokeB = new JButton("Remove server access");
			revokeB.setToolTipText("Switches off the selected launcher's server and MCSS control, and tells it to forget any sign-in or key it has saved.");
			buttons.add(revokeB);
			java.util.function.BiConsumer<String, String> ask = (path, confirm) -> {
				int row = table.getSelectedRow();
				if (row < 0) { JOptionPane.showMessageDialog(f, "Pick a launcher in the list first."); return; }
				String id = ids.get(row);
				if (JOptionPane.showConfirmDialog(f, "<html><div style='width:380px'>" + confirm + "</div></html>", "October Journey host panel", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", path, "{\"id\":\"" + id + "\"}"))).get("message")); } catch (IOException ex) { msg = "The helper isn't running."; }
					String m2 = "<html><div style='width:420px'>" + msg.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>";
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, m2, "October Journey host panel", JOptionPane.PLAIN_MESSAGE));
				}, "ask").start();
			};
			revokeB.addActionListener(e -> ask.accept("/admin/revoke", "Remove this launcher's server access? Its server and MCSS control are switched off, and it's told to forget any sign-in or key it has saved."));
			JPanel south = new JPanel(new BorderLayout(0, 6));
			south.add(buttons, BorderLayout.NORTH); south.add(keyRow, BorderLayout.SOUTH);
			mid.add(south, BorderLayout.SOUTH);
			// Players: the same permissions, per player. A player is tied to a launcher when the server's log shows them
			// joining from it; allowing a player something sets it on all their launchers, and on ones they join from later.
			String[] pcols = {"Player", "Launchers", "Last joined", "Server control", "MCSS control", "PC sleep", "Helper control"};
			DefaultTableModel pmodel = new DefaultTableModel(pcols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
			JTable ptable = new JTable(pmodel);
			ptable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			ptable.setRowHeight(24);
			List<String> pnames = new ArrayList<>();
			Map<String, boolean[]> pallow = new java.util.HashMap<>();   // player -> allowed {server, mcss, sleep, admin}
			JButton[] pB = {new JButton("Allow server control"), new JButton("Allow MCSS control"), new JButton("Allow PC sleep"), new JButton("Allow helper control")};
			JPanel pbuttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			for (JButton b : pB) pbuttons.add(b);
			JLabel pnote = new JLabel("<html><div style='width:900px'>A player appears once they've joined the server from a device with the launcher (matched by its Tailscale address), or when a launcher says who last played on it (\"not confirmed\" until they join). What you allow a player is set on all their confirmed launchers, and on launchers they join from later. The Launchers tab still changes one launcher at a time.</div></html>");
			pnote.setForeground(new Color(0x6B7280));
			JPanel psouth = new JPanel(new BorderLayout(0, 6)); psouth.add(pbuttons, BorderLayout.NORTH); psouth.add(pnote, BorderLayout.SOUTH);
			JPanel playersTab = new JPanel(new BorderLayout(0, 8));
			playersTab.add(new JScrollPane(ptable), BorderLayout.CENTER); playersTab.add(psouth, BorderLayout.SOUTH);
			javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
			mid.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6)); playersTab.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
			tabs.addTab("Launchers", mid); tabs.addTab("Players", playersTab);
			p.add(tabs, BorderLayout.CENTER);
			JPanel bottom = new JPanel(new BorderLayout(0, 4));
			bottom.add(new JLabel("Activity"), BorderLayout.NORTH);
			bottom.add(new JScrollPane(log), BorderLayout.CENTER);
			p.add(bottom, BorderLayout.SOUTH);
			Runnable refresh = () -> new Thread(() -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/devices", null)));
					List<Object> lg = Json.list(Json.map(Json.parse(call("GET", "/admin/log", null))).get("log"));
					Map<String, Object> pl = Json.map(Json.map(Json.parse(call("GET", "/admin/players", null))).get("players"));
					SwingUtilities.invokeLater(() -> {
						int sel = table.getSelectedRow();
						String keep = sel >= 0 && sel < ids.size() ? ids.get(sel) : null;
						model.setRowCount(0); ids.clear(); perms.clear();
						for (Map.Entry<String, Object> e : Json.map(r.get("devices")).entrySet()) {
							Map<String, Object> d = Json.map(e.getValue());
							ids.add(e.getKey());
							boolean pm = Boolean.TRUE.equals(d.get("granted")), ps = Boolean.TRUE.equals(d.get("sleep")), pv = Boolean.TRUE.equals(d.get("server")), pa = Boolean.TRUE.equals(d.get("admin"));
							perms.put(e.getKey(), new boolean[] {pm, ps, pv, pa});
							model.addRow(new Object[] {d.get("name"), Json.str(d.get("player")).isEmpty() ? "" : Json.str(d.get("player")) + (Boolean.TRUE.equals(d.get("player_confirmed")) ? "" : " (not confirmed)"), d.get("tailscale"), d.get("os"), d.get("first_seen"), d.get("last_seen"),
									pv ? "Allowed" : pm ? "Allowed (with MCSS control)" : "Not allowed", pm ? "Allowed" : "Not allowed", ps ? "Allowed" : "Not allowed", pa ? "Allowed" : "Not allowed", Json.str(d.get("access_text"))});
							if (e.getKey().equals(keep)) table.setRowSelectionInterval(ids.size() - 1, ids.size() - 1);
						}
						int psel = ptable.getSelectedRow();
						String pkeep = psel >= 0 && psel < pnames.size() ? pnames.get(psel) : null;
						pmodel.setRowCount(0); pnames.clear(); pallow.clear();
						for (Map.Entry<String, Object> e : pl.entrySet()) {
							Map<String, Object> q = Json.map(e.getValue());
							int conf = q.get("confirmed") instanceof Number n ? n.intValue() : 0;
							String[] cell = new String[4]; boolean[] al = new boolean[4];
							String[] keys = {"server", "mcss", "sleep", "admin"};
							for (int i = 0; i < 4; i++) {
								al[i] = Boolean.TRUE.equals(q.get("allow_" + keys[i]));
								int has = q.get("has_" + keys[i]) instanceof Number n ? n.intValue() : 0;
								cell[i] = (al[i] ? "Allowed" : "Not allowed") + (conf == 0 ? "" : has == conf ? (al[i] ? "" : " (on all " + conf + " anyway)") : has == 0 ? (al[i] ? " (on none yet)" : "") : " (on " + has + " of " + conf + ")");
							}
							pnames.add(e.getKey()); pallow.put(e.getKey(), al);
							List<Object> dv = Json.list(q.get("devices"));
							StringBuilder ds = new StringBuilder();
							for (Object o : dv) ds.append(ds.length() == 0 ? "" : ", ").append(o);
							pmodel.addRow(new Object[] {e.getKey(), ds.length() == 0 ? "(none yet)" : ds.toString(), Json.str(q.get("last_seen")), cell[0], cell[1], cell[2], cell[3]});
							if (e.getKey().equals(pkeep)) ptable.setRowSelectionInterval(pnames.size() - 1, pnames.size() - 1);
						}
						boolean hasKey = Boolean.TRUE.equals(r.get("has_key"));
						String works = Json.str(r.get("key_works"));
						keyState.setForeground(!hasKey ? new Color(0xB45309) : !works.equals("yes") ? new Color(0xD23C3C) : new Color(0x2E9D48));
						String as = Json.str(r.get("mcss_user"));
						keyState.setText(works.equals("forbidden") ? "MCSS knows the helper's key, but it has no permission for this server: give it the server (view, start, stop) under API Keys in MCSS's panel."
								: works.equals("unknown") ? "MCSS doesn't know the helper's key: set it again, or sign in."
								: !hasKey ? "The helper isn't signed in to MCSS: server control won't work until it is."
								: works.equals("no") ? (as.isEmpty() ? "The helper's MCSS key is set, but MCSS isn't answering with it (MCSS closed, or the key is wrong)." : "Signed in to MCSS as " + as + ", but MCSS isn't answering with it (MCSS closed, or sign in again).")
								: as.isEmpty() ? "The helper's MCSS key is set and working." : "Signed in to MCSS as " + as + ": working.");
						List<Object> asked = Json.list(r.get("requests"));
						int seq = r.get("request_seq") instanceof Number n ? n.intValue() : 0;
						boolean serverUp = Boolean.TRUE.equals(r.get("minecraft"));
						if (asked.isEmpty() || serverUp) {
							if (reqRow.isVisible()) { reqRow.setVisible(false); f.setTitle("October Journey host panel"); }
						} else {
							Map<String, Object> lastAsk = Json.map(asked.get(asked.size() - 1));
							String by = Json.str(lastAsk.get("who")) + (Json.str(lastAsk.get("player")).isEmpty() ? "" : " (" + Json.str(lastAsk.get("player")) + ")");
							reqText.setText(by + " asked for the server to be turned on at " + Json.str(lastAsk.get("time")) + (asked.size() > 1 ? "  (" + asked.size() + " requests)" : ""));
							reqRow.setVisible(true);
							f.setTitle("Server requested by " + by + " \u2013 October Journey host panel");
							if (seq != seqSeen[0]) {   // a new one: flash the taskbar button
								try { if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION_WINDOW)) java.awt.Taskbar.getTaskbar().requestWindowUserAttention(f); }
								catch (RuntimeException ignored) {}
							}
						}
						seqSeen[0] = seq;
						mcssUp[0] = Boolean.TRUE.equals(r.get("mcss")); mcssClose[0] = Json.str(r.get("close"));
						mcssRun.setText(!mcssUp[0] ? "Start MCSS" : mcssClose[0].equals("force") ? "Force close MCSS" : "Close MCSS");
						mcssRun.setEnabled(!mcssUp[0] || !Boolean.TRUE.equals(r.get("minecraft")));
						mcssRun.setToolTipText(mcssUp[0] && Boolean.TRUE.equals(r.get("minecraft")) ? "Stop the Minecraft server first" : null);
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
			String[] pkeys = {"server", "mcss", "sleep", "admin"}, pwords = {"server control", "MCSS control", "PC sleep", "helper control"};
			Runnable plabels = () -> {
				int r = ptable.getSelectedRow();
				boolean[] al = r >= 0 && r < pnames.size() ? pallow.getOrDefault(pnames.get(r), new boolean[4]) : new boolean[4];
				for (int i = 0; i < 4; i++) pB[i].setText((al[i] ? "Remove " : "Allow ") + pwords[i]);
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
						try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/player/perm", Json.write(m("player", name, "perm", pkeys[k], "value", v))))).get("message")); } catch (IOException ex) { msg = "The helper isn't running."; }
						String m2 = msg;
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Players", JOptionPane.PLAIN_MESSAGE));
						refresh.run();
					}, "player-perm").start();
				});
			}
			reqClear.addActionListener(e -> act.accept("/admin/requests/clear", "{}"));
			reqStart.addActionListener(e -> new Thread(() -> {
				String msg;
				try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/server/start", "{}"))).get("message")); } catch (IOException ex) { msg = "The helper isn't running."; }
				String m2 = msg;
				SwingUtilities.invokeLater(() -> state.setText(m2));
				try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
				refresh.run();
			}, "req-start").start());
			mcssRun.addActionListener(e -> {
				boolean up = mcssUp[0], force = mcssClose[0].equals("force");
				if (up && JOptionPane.showConfirmDialog(f, force ? "Force close MCSS? Its X button only hides it, so it is ended instead." : "Close MCSS?", "MCSS", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				mcssRun.setEnabled(false);
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/mcss/close" : "/admin/mcss/start", "{\"force\":" + force + "}"))).get("message")); } catch (IOException ex) { msg = "The helper isn't running."; }
					String m2 = msg;
					SwingUtilities.invokeLater(() -> { state.setText(m2); });
					try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
					refresh.run();
				}, "mcss").start();
			});
			adminB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"admin\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[3] + "}"); });
			signB.addActionListener(e -> {
				javax.swing.JTextField user = new javax.swing.JTextField(24);
				javax.swing.JPasswordField pass = new javax.swing.JPasswordField(24);
				JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
				form.add(new JLabel("<html><div style='width:380px'>Sign the helper in to MCSS with a panel account that can view, start and stop the server. MCSS hands back a key, which is kept on this PC; the password isn't kept.</div></html>"));
				form.add(new JLabel("Username")); form.add(user);
				form.add(new JLabel("Password")); form.add(pass);
				if (JOptionPane.showConfirmDialog(f, form, "Sign in to MCSS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				String body = Json.write(m("username", user.getText().trim(), "password", new String(pass.getPassword())));
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/signin", body))).get("message")); } catch (IOException ex) { msg = "The helper isn't running."; }
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
			if (startMinimized) f.setExtendedState(JFrame.ICONIFIED);   // (opened by the helper for a request: on the taskbar, flashing)
			f.setVisible(true);
		});
	}

	static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("\uFEFF", ""); } catch (IOException e) { return ""; }
	}
}
