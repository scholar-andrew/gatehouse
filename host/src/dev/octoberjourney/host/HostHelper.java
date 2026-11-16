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
 * Gatehouse Keeper, on the PC that runs the server. Two modes:
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
	/** The program that hosts the Minecraft server on this machine. Everything the Keeper does with it goes through
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

	static boolean minecraftRunning() {
		String hp = serverSetting("minecraft", "minecraft", "127.0.0.1:25565");
		int c = hp.lastIndexOf(':');
		try (Socket s = new Socket()) { s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 1500); return true; }
		catch (IOException | RuntimeException e) { return false; }
	}

	// ------------------------------------------------------------------ the Keeper

	static void serve() throws Exception {
		log("helper starting (port " + port() + ")");
		// Once a minute, also with no panel or launcher open, the server's log is read for joins (through the Keeper's
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
		String bind = setting("bind", "");
		if (bind.isEmpty()) { log("host.json has no \"bind\" (this machine's Tailscale address): run tools/install_host.ps1"); return; }
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
			SERVER.set(header(x, "X-Server"));
			scanPlayers();
			devs = devices();
			String id = x.getRequestHeaders().getFirst("X-Device"), token = x.getRequestHeaders().getFirst("X-Token");
			Map<String, Object> d = Json.map(devs.get(id == null ? "" : id));
			if (d.isEmpty() || token == null || !sha256(token).equals(d.get("token_sha256"))) { reply(x, 401, m("state", "unknown", "error", "say hello first")); return; }
			// Three separate permissions, each allowed per launcher in the panel: MCSS control ("granted"), PC sleep, and
			// server control (start and stop the Minecraft server through this helper's own MCSS key, so the launcher
			// needs no panel sign-in or key of its own).
			boolean granted = Boolean.TRUE.equals(d.get("granted")), canSleep = Boolean.TRUE.equals(d.get("sleep")), canServer = Boolean.TRUE.equals(d.get(serverKey())) || granted;   // (MCSS control includes server control)
			// A fourth, Keeper control: the launcher may do what the control panel does (see the launchers and the activity,
			// switch the other three permissions, forget a launcher). Only the panel on this PC gives or removes it, and
			// the Keeper's MCSS key can't be read or set from a launcher.
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
				reply(x, 200, m("clear_access", clear, "state", granted ? "granted" : "pending", "mcss", host.running(), "minecraft", minecraftRunning(), "close", host.closeMode(),
						"sleep", canSleep, "server", canServer, "admin", canAdmin, "server_ready", host.hasAccess(),
						"request", minecraftRunning() ? "" : requestState.getOrDefault(id, "")));
				return;
			}
			if (path.startsWith("/v1/admin/")) {
				if (!canAdmin) { reply(x, 403, m("error", "this device isn't allowed to manage launchers (the owner allows it in the control panel)")); return; }
				boolean post = x.getRequestMethod().equals("POST");
				if (path.equals("/v1/admin/devices") && !post) {
					Map<String, Object> all = listed();
					reply(x, 200, m("devices", all, "you", id, "mcss", host.running(), "minecraft", minecraftRunning(), "has_key", host.hasAccess()));
				} else if (path.equals("/v1/admin/log") && !post) {
					String[] lines = read(dir.resolve("host.log")).split("\\R");
					List<Object> last = new ArrayList<>();
					for (int i = Math.max(0, lines.length - 100); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
					reply(x, 200, m("log", last));
				} else if (path.equals("/v1/admin/players") && !post) {
					reply(x, 200, m("players", playerList()));
				} else if (path.equals("/v1/admin/player/perm") && post) {
					Map<String, Object> b = body(x);
					String perm = Json.str(b.get("perm"));
					if (perm.equals("admin")) { reply(x, 403, m("error", "Keeper control is only given or removed in the control panel on the server PC")); return; }
					String[] r = playerPerm(Json.str(b.get("player")), perm, Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value"))));
					log("(asked by " + who(d) + ")");
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else if ((path.equals("/v1/admin/perm") || path.equals("/v1/admin/forget") || path.equals("/v1/admin/revoke")) && post) {
					Map<String, Object> b = body(x);
					String tid = Json.str(b.get("id"));
					Map<String, Object> t = Json.map(devs.get(tid));
					if (t.isEmpty()) { reply(x, 404, m("error", "unknown launcher")); return; }
					if (path.endsWith("revoke")) {
						if (Boolean.TRUE.equals(t.get("admin")) && !tid.equals(id)) { reply(x, 403, m("error", "That launcher has Keeper control: only the control panel on the server PC can remove its access")); return; }
						String[] r = revokeAccess(t);
						log("removed server access from " + who(t) + " (asked by " + who(d) + "): " + r[1]);
						saveDevices(devs);
						reply(x, 200, m("result", r[0], "message", r[1]));
						return;
					}
					if (path.endsWith("forget")) {
						if (tid.equals(id)) { reply(x, 403, m("error", "This is the launcher you're using: forget it in the control panel on the server PC")); return; }
						if (Boolean.TRUE.equals(t.get("admin"))) { reply(x, 403, m("error", "That launcher has Keeper control: only the control panel on the server PC can forget it")); return; }
						devs.remove(tid); log("forgot " + who(t) + " (asked by " + who(d) + ")");
					} else {
						String perm = Json.str(b.get("perm"));
						boolean v = Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value")));
						String key = perm.equals("mcss") ? "granted" : perm.equals("sleep") ? "sleep" : perm.equals("server") ? serverKey() : null;
						if (key == null) { reply(x, 403, m("error", perm.equals("admin") ? "Keeper control is only given or removed in the control panel on the server PC" : "unknown permission")); return; }
						t.put(key, v);
						log((v ? "allowed " : "removed ") + (perm.equals("mcss") ? "MCSS control" : perm.equals("sleep") ? "PC sleep" : "server control") + (v ? " for " : " from ") + who(t) + " (asked by " + who(d) + ")");
					}
					saveDevices(devs);
					reply(x, 200, m("ok", true));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.startsWith("/v1/server")) {
				if (!canServer) { reply(x, 403, m("error", "this device isn't allowed to control the server (the owner allows it in the control panel)")); return; }
				if (path.equals("/v1/server") && x.getRequestMethod().equals("GET")) reply(x, 200, m("status", host.serverStatus()));
				else if (path.equals("/v1/server/log") && x.getRequestMethod().equals("GET")) reply(x, 200, m("lines", serverLog(80)));
				else if ((path.equals("/v1/server/start") || path.equals("/v1/server/stop")) && x.getRequestMethod().equals("POST")) {
					String[] r = host.serverAction(path.endsWith("start"));
					log((path.endsWith("start") ? "start" : "stop") + " the Minecraft server asked by " + who(d) + ": " + r[1]);
					reply(x, 200, m("result", r[0], "message", r[1]));
				} else reply(x, 404, m("error", "no such call"));
				return;
			}
			if (path.equals("/v1/pc/sleep") && x.getRequestMethod().equals("POST")) {
				if (!canSleep) { reply(x, 403, m("error", "this device isn't allowed to sleep the PC (the owner allows it in the control panel)")); return; }
				String[] r = sleepPc();
				log("sleep the PC asked by " + who(d) + ": " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
				return;
			}
			if (!granted) { reply(x, 403, m("state", "pending", "error", "not allowed yet: the owner allows this device in the control panel")); return; }
			String[] r;
			if (path.equals("/v1/mcss/start") && x.getRequestMethod().equals("POST")) r = host.start();
			else if (path.equals("/v1/mcss/close") && x.getRequestMethod().equals("POST")) r = host.close("true".equals(Json.str(body(x).get("force"))));
			else { reply(x, 404, m("error", "no such call")); return; }
			log((path.endsWith("start") ? "start" : "close") + " MCSS asked by " + who(d) + ": " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1], "mcss", host.running()));
		} catch (Exception e) {
			log("error: " + e);
			try { reply(x, 500, m("error", "the Keeper had a problem")); } catch (IOException ignored) {}
		}
	}

	// ------------------------------------------------------------------ the Minecraft server, through MCSS's API

	/** The server's folder (host.json "server_dir"), and its id in MCSS (from mcss_server_config.json there). */
	/** The Minecraft server this helper looks after: the first entry of host.json "servers" (each: name, dir,
	 *  minecraft, mcss_server), which tools/install_host.ps1 fills in from the server profile. Nothing about a server is
	 *  fixed here; a host.json from before the list existed may still give server_dir / minecraft / mcss_server itself. */
	static List<Object> servers() { return Json.list(cfg.get("servers")); }
	/** Which of them the request being answered is about: its "id", as the launcher's profile names it (X-Server) or
	 *  the control panel picks it (?server=). Empty or unknown: the first one. */
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

	static final String[][] PERMS = {{"server", "server", "server control"}, {"mcss", "granted", "MCSS control"}, {"sleep", "sleep", "PC sleep"}, {"admin", "admin", "Keeper control"}};
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
					for (String[] pm : PERMS) if (Boolean.TRUE.equals(pd.get(pkey(pm))) && !Boolean.TRUE.equals(d.get(dkey(pm)))) { d.put(dkey(pm), true); log("allowed " + pm[2] + " for " + who(d) + " (allowed for player " + name + ")"); }
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
			if (ok) for (String[] pm : PERMS) if (Boolean.TRUE.equals(d.get(dkey(pm)))) p.put("has_" + pm[0], (p.get("has_" + pm[0]) instanceof Number n ? n.intValue() : 0) + 1);
		}
		// Everyone on the server's whitelist is listed too, with or without a launcher, so they can be ticked off again.
		List<Object> wl = whitelist();
		for (Object o : wl) { String n = Json.str(Json.map(o).get("name")); if (!n.isEmpty() && !out.containsKey(n)) out.put(n, m("devices", new ArrayList<Object>(), "confirmed", 0, "unconfirmed", 0, "last_seen", "")); }
		for (Map.Entry<String, Object> e : out.entrySet()) {
			boolean listed = false;
			for (Object o : wl) if (e.getKey().equalsIgnoreCase(Json.str(Json.map(o).get("name")))) listed = true;
			Json.map(e.getValue()).put("whitelisted", listed);
			Map<String, Object> pd = Json.map(defaults.get(e.getKey()));
			for (String[] pm : PERMS) Json.map(e.getValue()).put("allow_" + pm[0], Boolean.TRUE.equals(pd.get(pkey(pm))));
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
		pd.put(pkey(pm), v);
		Files.writeString(dir.resolve("players.json"), Json.write(defaults), StandardCharsets.UTF_8);
		Map<String, Object> devs = devices();
		int n = 0, waiting = 0;
		for (Object o : devs.values()) {
			Map<String, Object> d = Json.map(o);
			if (!name.equals(Json.str(d.get("player")))) continue;
			if (!Boolean.TRUE.equals(d.get("player_confirmed"))) { waiting++; continue; }
			d.put(dkey(pm), v); n++;
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
	/** What became of each launcher's last request: "asked" (waiting for the owner) or "denied" (dismissed in the panel). */
	static final Map<String, String> requestState = new java.util.concurrent.ConcurrentHashMap<>();
	static int requestSeq = 0;
	static volatile long panelSeen = 0;   // when the control panel last asked for its list (it does every few seconds while open)

	/** A launcher asks for the server to be turned on: noted for the control panel, with a chime here and the panel
	 *  brought up minimized (its taskbar button flashes) if it isn't open. One a minute from each launcher. */
	static synchronized String[] requestServer(Map<String, Object> d, String id) {
		long now = System.currentTimeMillis();
		Long last = lastRequest.get(id);
		if (last != null && now - last < 60_000) return new String[] {"refused", "Already asked a moment ago: the owner has been told"};
		if (minecraftRunning()) return new String[] {"refused", "The server is already running"};
		lastRequest.put(id, now);
		requestState.put(id, "asked");
		requests.add(m("id", id, "who", who(d), "player", Json.str(d.get("player")), "server", servers().size() > 1 ? serverName() : "", "time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))));
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

	/** Opens the control panel minimized, unless one is open already (an open panel sees the request itself). */
	static void openPanel() {
		if (System.currentTimeMillis() - panelSeen < 8000) return;
		try {
			String java = ProcessHandle.current().info().command().orElse("javaw");
			String jar = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			new ProcessBuilder(java, "-jar", jar, "--panel", "--minimized").start();
		} catch (Exception e) { log("couldn't open the control panel: " + e); }
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
				String[] r = host.command("whitelist " + (on ? "add " : "remove ") + name);
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
		String has = s.equals("signin") ? "signed in to the panel" + (u.isEmpty() ? "" : " as " + u) : s.equals("key") ? "its own API key" : s.equals("issued") ? "a key issued by the Keeper" : "";
		String t = k.isEmpty() ? "Unknown (older launcher)" : k.equals("helper") ? "Through the Keeper" + (has.isEmpty() ? "" : " (also saved: " + has + ")")
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
			String query = x.getRequestURI().getQuery();
			SERVER.set(query != null && query.startsWith("server=") ? query.substring(7).replaceAll("[^\\w.-]", "") : "");
			if (path.equals("/admin/devices")) {
				panelSeen = System.currentTimeMillis();
				Map<String, Object> devs = listed();
				List<Object> asked;
				int seq;
				synchronized (HostHelper.class) { asked = new ArrayList<>(requests); seq = requestSeq; }
				List<Object> names = new ArrayList<>();
				for (Object o : servers()) names.add(m("id", Json.str(Json.map(o).get("id")), "name", Json.str(Json.map(o).get("name"))));
				reply(x, 200, m("servers", names, "server_id", Json.str(server().get("id")), "requests", asked, "request_seq", seq, "devices", devs, "mcss", host.running(), "minecraft", minecraftRunning(), "close", host.closeMode(), "port", port(), "bind", setting("bind", ""),
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
				String[] r = path.endsWith("start") ? host.start() : host.close("true".equals(Json.str(body(x).get("force"))));
				log((path.endsWith("start") ? "start" : "close") + " MCSS from the control panel: " + r[1]);
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
				String[] r = playerPerm(Json.str(b.get("player")), Json.str(b.get("perm")), Boolean.TRUE.equals(b.get("value")) || "true".equals(Json.str(b.get("value"))));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/requests/clear")) {
				// Dismiss: each launcher that asked is told its request was denied (it sees it the next time it asks how
				// things are, every few seconds while its window is open).
				synchronized (HostHelper.class) {
					for (Object o : requests) { String rid = Json.str(Json.map(o).get("id")); if (!rid.isEmpty()) requestState.put(rid, "denied"); log("request to turn the server on, from " + Json.str(Json.map(o).get("who")) + ": dismissed"); }
					requests.clear();
				}
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/server/stop")) {
				String[] r = host.serverAction(false);
				log("stop the Minecraft server from the control panel: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/server/start")) {
				// From the panel's request banner: MCSS first if it isn't running (it needs a moment before it takes the start).
				String[] r;
				if (!host.running()) { String[] s = host.start(); r = new String[] {s[0].equals("ok") ? "wait" : s[0], s[0].equals("ok") ? "MCSS is starting: press Start server again in a few seconds" : s[1]}; }
				else r = host.serverAction(true);
				log("start the Minecraft server from the control panel: " + r[1]);
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/keytest")) {
				// Troubleshooting: what the host program answers to the Keeper's access (never the access itself).
				reply(x, 200, host.diagnose());
			} else if (path.equals("/admin/signin")) {
				// The owner signs the Keeper in to the host program with an account there (typed in the control panel on this
				// PC). What it hands back is kept; the password never is.
				Map<String, Object> b = body(x);
				String[] r = host.signIn(Json.str(b.get("username")).trim(), Json.str(b.get("password")));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/settings")) {
				// The Keeper's own key for the host program, kept on this PC only.
				host.setKey(Json.str(body(x).get("mcss_api_key")).trim());
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
					else if (perm.equals("server")) { d.put(serverKey(), v); log((v ? "allowed server control for " : "removed server control from ") + who(d)); }
					else if (perm.equals("admin")) { d.put("admin", v); log((v ? "allowed Keeper control for " : "removed Keeper control from ") + who(d)); }
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

	/** The server the control panel is looking at (its pick, when the machine has more than one). */
	static volatile String panelServer = "";
	static String call(String method, String path, String body) throws IOException {
		if (!panelServer.isEmpty()) path += (path.contains("?") ? "&" : "?") + "server=" + panelServer;
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + path).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(5000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	static void panel() throws Exception {
		if (GraphicsEnvironment.isHeadless()) { System.out.println(call("GET", "/admin/devices", null)); return; }
		SwingUtilities.invokeAndWait(() -> {
			try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
			JFrame f = new JFrame("Gatehouse control panel");
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 10));
			p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
			JLabel head = new JLabel("Gatehouse Keeper");
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
			// With more than one server on this machine: which one the panel is looking at (server control, the
			// Minecraft server's status and its Start/Stop are per server; the rest is about the machine).
			javax.swing.JComboBox<String> serverPick = new javax.swing.JComboBox<>();
			List<String> serverIds = new ArrayList<>();
			JPanel pickRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			pickRow.add(new JLabel("Server:")); pickRow.add(serverPick);
			pickRow.setVisible(false);
			JPanel headLine = new JPanel(new BorderLayout()); headLine.add(head, BorderLayout.WEST); headLine.add(pickRow, BorderLayout.EAST);
			JPanel headRows = new JPanel(new BorderLayout(0, 6)); headRows.add(headLine, BorderLayout.NORTH); headRows.add(reqRow, BorderLayout.SOUTH);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(headRows, BorderLayout.NORTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Player", "Tailscale device", "System", "First seen", "Last seen", "Server control", "MCSS control", "PC sleep", "Keeper control", "Controls the server"};
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
			JButton serverB = new JButton("Allow server control"), mcssB = new JButton("Allow MCSS control"), sleepB = new JButton("Allow PC sleep"), adminB = new JButton("Allow Keeper control"), forget = new JButton("Forget"), refreshB = new JButton("Refresh");
			adminB.setToolTipText("Keeper control lets that launcher manage the others' permissions from its Server config tab, as this panel does (not the MCSS key, and not Keeper control itself).");
			for (JButton b : new JButton[] {serverB, mcssB, sleepB, adminB, forget, refreshB}) buttons.add(b);
			Map<String, boolean[]> perms = new java.util.HashMap<>();   // id -> {mcss, sleep, server}, from the last refresh
			// The Keeper's own MCSS key: needed for "server control" (it starts and stops the server for allowed launchers).
			JLabel keyState = new JLabel(" ");
			JButton keyB = new JButton("Set MCSS key\u2026");
			JButton signB = new JButton("Sign in to MCSS\u2026");
			JPanel keyRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			keyRow.add(signB); keyRow.add(keyB); keyRow.add(keyState);
			// For the selected launcher: an MCSS key of its own, made and handed over by the Keeper; and taking away
			// whatever lets it control the server.
			JButton revokeB = new JButton("Remove server access");
			revokeB.setToolTipText("Switches off the selected launcher's server and MCSS control, and tells it to forget any sign-in or key it has saved.");
			buttons.add(revokeB);
			java.util.function.BiConsumer<String, String> ask = (path, confirm) -> {
				int row = table.getSelectedRow();
				if (row < 0) { JOptionPane.showMessageDialog(f, "Pick a launcher in the list first."); return; }
				String id = ids.get(row);
				if (JOptionPane.showConfirmDialog(f, "<html><div style='width:380px'>" + confirm + "</div></html>", "Gatehouse control panel", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", path, "{\"id\":\"" + id + "\"}"))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
					String m2 = "<html><div style='width:420px'>" + msg.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>";
					SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, m2, "Gatehouse control panel", JOptionPane.PLAIN_MESSAGE));
				}, "ask").start();
			};
			revokeB.addActionListener(e -> ask.accept("/admin/revoke", "Remove this launcher's server access? Its server and MCSS control are switched off, and it's told to forget any sign-in or key it has saved."));
			JPanel south = new JPanel(new BorderLayout(0, 6));
			south.add(buttons, BorderLayout.NORTH);
			mid.add(south, BorderLayout.SOUTH);
			// Players: the same permissions, per player. A player is tied to a launcher when the server's log shows them
			// joining from it; allowing a player something sets it on all their launchers, and on ones they join from later.
			String[] pcols = {"Player", "Whitelisted", "Launchers", "Last joined", "Server control", "MCSS control", "PC sleep", "Keeper control"};
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
			JButton[] pB = {new JButton("Allow server control"), new JButton("Allow MCSS control"), new JButton("Allow PC sleep"), new JButton("Allow Keeper control")};
			JPanel pbuttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			for (JButton b : pB) pbuttons.add(b);
			JLabel pnote = new JLabel("<html><div style='width:900px'>A player appears once they've joined the server from a device with the launcher (matched by its Tailscale address), or when a launcher says who last played on it (\"not confirmed\" until they join). What you allow a player is set on all their confirmed launchers, and on launchers they join from later. The Whitelisted box puts a player on the server's whitelist or takes them off (a name that is \"not confirmed\" is only what a launcher said, so check it before ticking). The Launchers tab still changes one launcher at a time.</div></html>");
			pnote.setForeground(new Color(0x6B7280));
			JPanel psouth = new JPanel(new BorderLayout(0, 6)); psouth.add(pbuttons, BorderLayout.NORTH); psouth.add(pnote, BorderLayout.SOUTH);
			JPanel playersTab = new JPanel(new BorderLayout(0, 8));
			playersTab.add(new JScrollPane(ptable), BorderLayout.CENTER); playersTab.add(psouth, BorderLayout.SOUTH);
			javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
			// Access: who may do what, one launcher (device) at a time or a player with all their devices.
			javax.swing.JRadioButton byDevice = new javax.swing.JRadioButton("By launcher (one device)", true), byPlayer = new javax.swing.JRadioButton("By player (all their devices)");
			javax.swing.ButtonGroup how = new javax.swing.ButtonGroup(); how.add(byDevice); how.add(byPlayer);
			JPanel pick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
			JLabel pickHead = new JLabel("Manage access:"); pickHead.setFont(pickHead.getFont().deriveFont(Font.BOLD));
			pick.add(pickHead); pick.add(byDevice); pick.add(byPlayer);
			java.awt.CardLayout cards = new java.awt.CardLayout();
			JPanel deck = new JPanel(cards);
			deck.add(mid, "device"); deck.add(playersTab, "player");
			byDevice.addActionListener(e -> cards.show(deck, "device"));
			byPlayer.addActionListener(e -> cards.show(deck, "player"));
			JPanel accessTab = new JPanel(new BorderLayout(0, 4));
			accessTab.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
			accessTab.add(pick, BorderLayout.NORTH); accessTab.add(deck, BorderLayout.CENTER);
			// Status: the Keeper, MCSS, the Minecraft server, and everything that was asked of the Keeper.
			JLabel mcssState = new JLabel(" "), serverState = new JLabel(" ");
			JButton serverRun = new JButton("Start server");
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
			section.accept("Gatehouse Keeper", new javax.swing.JComponent[] {helperRow});
			section.accept("MCSS (the server software)", new javax.swing.JComponent[] {mcssRow, keyRow});
			section.accept("Minecraft server", new javax.swing.JComponent[] {serverRow});
			JPanel activity = new JPanel(new BorderLayout(0, 4));
			JLabel actHead = new JLabel("Activity"); actHead.setFont(actHead.getFont().deriveFont(Font.BOLD, 14f));
			activity.add(actHead, BorderLayout.NORTH);
			log.setRows(14);
			activity.add(new JScrollPane(log), BorderLayout.CENTER);
			JPanel statusTab = new JPanel(new BorderLayout(0, 10));
			statusTab.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
			statusTab.add(statusTop, BorderLayout.NORTH); statusTab.add(activity, BorderLayout.CENTER);
			tabs.addTab("Access", accessTab); tabs.addTab("Status", statusTab);
			p.add(tabs, BorderLayout.CENTER);
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
								cell[i] = (al[i] ? "Allowed" : "Not allowed") + (conf == 0 ? "" : has == conf ? (al[i] ? "" : " (on all " + conf + " anyway)") : has == 0 ? (al[i] ? " (on none yet)" : "") : " (on " + has + " of " + conf + ")");
							}
							pnames.add(e.getKey()); pallow.put(e.getKey(), al);
							List<Object> dv = Json.list(q.get("devices"));
							StringBuilder ds = new StringBuilder();
							for (Object o : dv) ds.append(ds.length() == 0 ? "" : ", ").append(o);
							pmodel.addRow(new Object[] {e.getKey(), Boolean.TRUE.equals(q.get("whitelisted")), ds.length() == 0 ? "(none yet)" : ds.toString(), Json.str(q.get("last_seen")), cell[0], cell[1], cell[2], cell[3]});
							if (e.getKey().equals(pkeep)) ptable.setRowSelectionInterval(pnames.size() - 1, pnames.size() - 1);
						}
						filling[0] = false;
						boolean hasKey = Boolean.TRUE.equals(r.get("has_key"));
						String works = Json.str(r.get("key_works"));
						keyState.setForeground(!hasKey ? new Color(0xB45309) : !works.equals("yes") ? new Color(0xD23C3C) : new Color(0x2E9D48));
						String as = Json.str(r.get("mcss_user"));
						keyState.setText(works.equals("forbidden") ? "MCSS knows the Keeper's key, but it has no permission for this server: give it the server (view, start, stop) under API Keys in MCSS's panel."
								: works.equals("unknown") ? "MCSS doesn't know the Keeper's key: set it again, or sign in."
								: !hasKey ? "The Keeper isn't signed in to MCSS: server control won't work until it is."
								: works.equals("no") ? (as.isEmpty() ? "The Keeper's MCSS key is set, but MCSS isn't answering with it (MCSS closed, or the key is wrong)." : "Signed in to MCSS as " + as + ", but MCSS isn't answering with it (MCSS closed, or sign in again).")
								: as.isEmpty() ? "The Keeper's MCSS key is set and working." : "Signed in to MCSS as " + as + ": working.");
						List<Object> sv = Json.list(r.get("servers"));
						if (sv.size() != serverIds.size()) {
							serverIds.clear(); serverPick.removeAllItems();
							for (Object o : sv) { serverIds.add(Json.str(Json.map(o).get("id"))); serverPick.addItem(Json.str(Json.map(o).get("name"))); }
							int at = Math.max(0, serverIds.indexOf(Json.str(r.get("server_id"))));
							if (!serverIds.isEmpty()) serverPick.setSelectedIndex(at);
							pickRow.setVisible(sv.size() > 1);
						}
						List<Object> asked = Json.list(r.get("requests"));
						int seq = r.get("request_seq") instanceof Number n ? n.intValue() : 0;
						boolean serverUp = Boolean.TRUE.equals(r.get("minecraft"));
						if (asked.isEmpty() || serverUp) {
							if (reqRow.isVisible()) { reqRow.setVisible(false); f.setTitle("Gatehouse control panel"); }
						} else {
							Map<String, Object> lastAsk = Json.map(asked.get(asked.size() - 1));
							String by = Json.str(lastAsk.get("who")) + (Json.str(lastAsk.get("player")).isEmpty() ? "" : " (" + Json.str(lastAsk.get("player")) + ")");
							reqText.setText(by + " asked for " + (Json.str(lastAsk.get("server")).isEmpty() ? "the server" : Json.str(lastAsk.get("server"))) + " to be turned on at " + Json.str(lastAsk.get("time")) + (asked.size() > 1 ? "  (" + asked.size() + " requests)" : ""));
							reqRow.setVisible(true);
							f.setTitle("Server requested by " + by + " \u2013 Gatehouse control panel");
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
						state.setText("Running on " + r.get("bind") + ":" + r.get("port") + " (launchers reach it over Tailscale).");
						mcssState.setForeground(Boolean.TRUE.equals(r.get("mcss")) ? new Color(0x2E9D48) : new Color(0x6B7280));
						mcssState.setText(Boolean.TRUE.equals(r.get("mcss")) ? "MCSS is running" : "MCSS isn't running");
						serverUpNow[0] = Boolean.TRUE.equals(r.get("minecraft"));
						serverState.setForeground(serverUpNow[0] ? new Color(0x2E9D48) : new Color(0x6B7280));
						serverState.setText(serverUpNow[0] ? "The Minecraft server is running" : "The Minecraft server is off");
						serverRun.setText(serverUpNow[0] ? "Stop server" : "Start server");
						StringBuilder sb = new StringBuilder();
						for (Object l : lg) sb.append(l).append('\n');
						if (!log.getText().equals(sb.toString())) { log.setText(sb.toString()); log.setCaretPosition(log.getDocument().getLength()); }
					});
				} catch (IOException e) {
					SwingUtilities.invokeLater(() -> { state.setForeground(new Color(0xD23C3C)); state.setText("The Keeper isn't running (it starts when you sign in to Windows)."); });
				}
			}, "refresh").start();
			java.util.function.BiConsumer<String, String> act = (what, body) -> new Thread(() -> {
				try { call("POST", what, body); } catch (IOException e) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "The Keeper isn't running.")); }
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
				adminB.setText(pp[3] ? "Remove Keeper control" : "Allow Keeper control");
				serverB.setText(pp[2] ? "Remove server control" : "Allow server control");
				mcssB.setText(pp[0] ? "Remove MCSS control" : "Allow MCSS control");
				sleepB.setText(pp[1] ? "Remove PC sleep" : "Allow PC sleep");
			};
			table.getSelectionModel().addListSelectionListener(e -> labels.run());
			model.addTableModelListener(e -> SwingUtilities.invokeLater(labels));
			mcssB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"mcss\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[0] + "}"); });
			sleepB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"sleep\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[1] + "}"); });
			serverB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id + "\",\"perm\":\"server\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[2] + "}"); });
			String[] pkeys = {"server", "mcss", "sleep", "admin"}, pwords = {"server control", "MCSS control", "PC sleep", "Keeper control"};
			pmodel.addTableModelListener(e -> {
				if (filling[0] || e.getType() != javax.swing.event.TableModelEvent.UPDATE || e.getColumn() != 1) return;
				int row = e.getFirstRow();
				if (row < 0 || row >= pnames.size()) return;
				String name = pnames.get(row);
				boolean on = Boolean.TRUE.equals(pmodel.getValueAt(row, 1));
				new Thread(() -> {
					String res = "failed", msg;
					try { Map<String, Object> rr = Json.map(Json.parse(call("POST", "/admin/whitelist", Json.write(m("player", name, "value", on))))); res = Json.str(rr.get("result")); msg = Json.str(rr.get("message")); }
					catch (IOException ex) { msg = "The Keeper isn't running."; }
					String m2 = msg; boolean bad = !res.equals("ok");
					SwingUtilities.invokeLater(() -> { if (bad) JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Whitelist", JOptionPane.PLAIN_MESSAGE); });
					refresh.run();
				}, "whitelist").start();
			});
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
						try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/player/perm", Json.write(m("player", name, "perm", pkeys[k], "value", v))))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
						String m2 = msg;
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Players", JOptionPane.PLAIN_MESSAGE));
						refresh.run();
					}, "player-perm").start();
				});
			}
			reqClear.addActionListener(e -> act.accept("/admin/requests/clear", "{}"));
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
					try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/server/stop" : "/admin/server/start", "{}"))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
					String m2 = msg;
					SwingUtilities.invokeLater(() -> serverState.setText(m2));
					try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
					refresh.run();
				}, "server-run").start();
			});
			reqStart.addActionListener(e -> new Thread(() -> {
				String msg;
				try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/server/start", "{}"))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
				String m2 = msg;
				SwingUtilities.invokeLater(() -> { serverState.setText(m2); tabs.setSelectedIndex(1); });
				try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
				refresh.run();
			}, "req-start").start());
			mcssRun.addActionListener(e -> {
				boolean up = mcssUp[0], force = mcssClose[0].equals("force");
				if (up && JOptionPane.showConfirmDialog(f, force ? "Force close MCSS? Its X button only hides it, so it is ended instead." : "Close MCSS?", "MCSS", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
				mcssRun.setEnabled(false);
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/mcss/close" : "/admin/mcss/start", "{\"force\":" + force + "}"))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
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
				form.add(new JLabel("<html><div style='width:380px'>Sign the Keeper in to MCSS with a panel account that can view, start and stop the server. MCSS hands back a key, which is kept on this PC; the password isn't kept.</div></html>"));
				form.add(new JLabel("Username")); form.add(user);
				form.add(new JLabel("Password")); form.add(pass);
				if (JOptionPane.showConfirmDialog(f, form, "Sign in to MCSS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				String body = Json.write(m("username", user.getText().trim(), "password", new String(pass.getPassword())));
				new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/signin", body))).get("message")); } catch (IOException ex) { msg = "The Keeper isn't running."; }
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
				form.add(new JLabel("<html><div style='width:420px'>An MCSS API key for the Keeper (make it in MCSS's web panel, under API Keys, with permission to view, start and stop the server). It's kept in host.json on this PC only. Leave empty to clear it.</div></html>"), BorderLayout.NORTH);
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
			// Testing: OJ_PANEL_PNG=<file> saves a picture of each tab (<file>-access.png, -players.png, -status.png) after
			// the first readings, instead of showing the window.
			String shot = System.getenv("OJ_PANEL_PNG");
			if (shot != null && !shot.isEmpty()) {
				Timer once = new Timer(3500, e -> {
					try {
						for (int i = 0; i < 3; i++) {
							tabs.setSelectedIndex(i == 2 ? 1 : 0);
							if (i == 1) byPlayer.doClick(); else if (i == 0) byDevice.doClick();
							p.validate();
							java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
							java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
							javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + (i == 0 ? "-access.png" : i == 1 ? "-players.png" : "-status.png")));
						}
					} catch (IOException ex) { ex.printStackTrace(); }
					System.exit(0);
				});
				once.setRepeats(false);
				once.start();
				return;
			}
			f.setLocationRelativeTo(null);
			if (startMinimized) f.setExtendedState(JFrame.ICONIFIED);   // (opened by the Keeper for a request: on the taskbar, flashing)
			f.setVisible(true);
		});
	}

	static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("\uFEFF", ""); } catch (IOException e) { return ""; }
	}
}
