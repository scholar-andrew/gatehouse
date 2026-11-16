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

	// ------------------------------------------------------------------ Gatekeeper

	static void serve() throws Exception {
		log("helper starting (port " + port() + ")");
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
		local.start();
		bindRemote();
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
		if (exe.isEmpty()) exe = setting("mcss_exe", "");
		if (exe.isEmpty()) for (String g : new String[] {"C:\\Minecraft\\MCSS\\mcss.exe", "C:\\MCSS\\mcss.exe", home + "\\MCSS\\mcss.exe", home + "\\Documents\\MCSS\\mcss.exe", home + "\\Desktop\\MCSS\\mcss.exe", home + "\\Downloads\\MCSS\\mcss.exe"})
			if (Files.isRegularFile(Path.of(g))) { exe = g; break; }
		List<Object> found = new ArrayList<>();
		String problem = "";
		if (exe.isEmpty()) problem = "MCSS wasn't found in the usual places: use Browse to pick mcss.exe.";
		else if (!Files.isRegularFile(Path.of(exe))) problem = "There's no file at that path.";
		else {
			List<Path> dirs = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(Path.of(exe).toAbsolutePath().getParent().resolve("servers"))) { s.filter(Files::isDirectory).sorted().forEach(dirs::add); }
			catch (IOException e) { problem = "There's no servers folder beside that mcss.exe."; }
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
			if (found.isEmpty() && problem.isEmpty()) problem = "MCSS has no servers yet: make one in MCSS first, then look again.";
		}
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
	static boolean samePath(String a, Path b) {
		try { return !a.isEmpty() && Path.of(a).toAbsolutePath().normalize().toString().equalsIgnoreCase(b.toAbsolutePath().normalize().toString()); } catch (RuntimeException e) { return false; }
	}

	/** Saves Server setup: MCSS's program, this machine's Tailscale address, and the servers Gatekeeper looks after.
	 *  Servers it already had keep their place and their id (permissions are filed under them); new ones go after. */
	static synchronized String[] setupSave(Map<String, Object> b) {
		String exe = Json.str(b.get("mcss_exe")).trim(), bind = Json.str(b.get("bind")).trim();
		if (!exe.isEmpty() && !Files.isRegularFile(Path.of(exe))) return new String[] {"refused", "There's no file at " + exe};
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
			keep.add(m("id", id, "name", Json.str(s.get("name")), "dir", Json.str(s.get("dir")), "minecraft", Json.str(s.get("minecraft")), "mcss_server", Json.str(s.get("mcss_server"))));
			added++;
		}
		boolean moved = !bind.isEmpty() && !bind.equals(setting("bind", ""));
		if (!exe.isEmpty()) cfg.put("mcss_exe", exe);
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
		if (!host.hasAccess()) return new String[] {"refused", "Gatekeeper isn't signed in to MCSS yet (Status tab: Sign in to MCSS)"};
		if (host.running()) {
			int st = host.serverStatus();
			if (st == 1) return new String[] {"ok", "The server is already on"};
			if (st == 3) return new String[] {"ok", "The server is already starting"};
			return host.serverAction(true);
		}
		String[] s = host.start();
		if (!s[0].equals("ok")) return s;
		String which = SERVER.get();
		Thread t = new Thread(() -> {
			SERVER.set(which);
			try {
				for (int i = 0; i < 120 && host.serverStatus() < 0; i++) Thread.sleep(1000);
				if (host.serverStatus() < 0) { log("start " + serverName() + " (asked by " + by + "): MCSS started, but it isn't answering Gatekeeper (if its sign-in ended, sign in again on the Status tab)"); return; }
				Thread.sleep(1500);
				String[] r = host.serverStatus() == 1 || host.serverStatus() == 3 ? new String[] {"ok", "it was already coming up"} : host.serverAction(true);
				log("start " + serverName() + " (asked by " + by + "), after starting MCSS: " + r[1]);
			} catch (InterruptedException ignored) {}
		}, "start-server");
		t.setDaemon(true);
		t.start();
		return new String[] {"ok", "MCSS wasn't running: it's starting now, and the server starts as soon as MCSS is ready (a minute or two in all)"};
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
			// A fourth, Gatekeeper control: the launcher may do what Gatekeeper does (see the launchers and the activity,
			// switch the other three permissions, forget a launcher). Only the panel on this PC gives or removes it, and
			// Gatekeeper's MCSS key can't be read or set from a launcher.
			boolean canAdmin = Boolean.TRUE.equals(d.get("admin"));
			// A launcher that is shut out: blacklisted, it is told nothing but that; suspended, it still sees how things
			// are but may do nothing. Either way it can still ask to be let back in (/v1/request).
			String[] ban = banOf(d);
			if (!ban[0].isEmpty()) { granted = false; canSleep = false; canServer = false; canAdmin = false; }
			if (ban[0].equals("blacklist") && !path.equals("/v1/request")) {
				if (path.equals("/v1/mcss")) reply(x, 200, m("state", "pending", "ban", ban[0], "ban_scope", ban[1], "ban_reason", ban[2], "requests", requestStates(id, false), "request_reasons", requestStates(id, true)));
				else reply(x, 403, m("error", "this launcher is blacklisted"));
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
				reply(x, 200, m("clear_access", clear, "state", granted ? "granted" : "pending", "mcss", host.running(), "minecraft", minecraftRunning(), "close", host.closeMode(),
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
				} else if (path.equals("/v1/admin/log") && !post) {
					String[] lines = read(dir.resolve("host.log")).split("\\R");
					List<Object> last = new ArrayList<>();
					for (int i = Math.max(0, lines.length - 100); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
					reply(x, 200, m("log", last));
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
				if (path.equals("/v1/server") && x.getRequestMethod().equals("GET")) reply(x, 200, m("status", host.serverStatus()));
				else if (path.equals("/v1/server/log") && x.getRequestMethod().equals("GET")) reply(x, 200, m("lines", serverLog(80)));
				else if ((path.equals("/v1/server/start") || path.equals("/v1/server/stop")) && x.getRequestMethod().equals("POST")) {
					String[] r = path.endsWith("start") ? startServerFully(who(d)) : host.serverAction(false);
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
			if (path.equals("/v1/mcss/start") && x.getRequestMethod().equals("POST")) r = host.start();
			else if (path.equals("/v1/mcss/close") && x.getRequestMethod().equals("POST")) r = host.close("true".equals(Json.str(body(x).get("force"))));
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
			Map<String, Object> pdx = Json.map(defaults.get(e.getKey()));
			for (String pn : new String[] {"machine", "mcss", "server", "admin"}) Json.map(e.getValue()).put("state_" + pn, permState(pdx, pn));
			Json.map(e.getValue()).put("ban", Json.str(pdx.get("ban"))); Json.map(e.getValue()).put("ban_reason", Json.str(pdx.get("ban_reason")));
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
		String[] r = host.command("kick " + name + (clean(reason, 120).isEmpty() ? "" : " " + clean(reason, 120)));
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
				String[] r = host.serverAction(false);
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
				reply(x, 200, host.diagnose());
			} else if (path.equals("/admin/signin")) {
				// The owner signs Gatekeeper in to the host program with an account there (typed in Gatekeeper on this
				// PC). What it hands back is kept; the password never is.
				Map<String, Object> b = body(x);
				String[] r = host.signIn(Json.str(b.get("username")).trim(), Json.str(b.get("password")));
				reply(x, 200, m("result", r[0], "message", r[1]));
			} else if (path.equals("/admin/setup/scan")) {
				reply(x, 200, setupScan(Json.str(body(x).get("mcss_exe")).trim()));
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

	/** The server Gatekeeper is looking at (its pick, when the machine has more than one). */
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
			JPanel headLine = new JPanel(new BorderLayout()); headLine.add(head, BorderLayout.WEST); headLine.add(pickRow, BorderLayout.EAST);
			JPanel headRows = new JPanel(new BorderLayout(0, 6)); headRows.add(headLine, BorderLayout.NORTH); headRows.add(reqRow, BorderLayout.SOUTH);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(headRows, BorderLayout.NORTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Player", "Tailscale device", "System", "First seen", "Last seen", "Server control", "MCSS control", "Machine wake/sleep", "Gatekeeper control", "Controls the server", "Status"};
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
			mcssB.setToolTipText("MCSS control: the selected launcher may start and close MCSS itself. It includes server control.");
			sleepB.setToolTipText("Machine wake/sleep: the selected launcher may wake this machine and put it to sleep (sleep only while the Minecraft server is stopped).");
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
			String[] pcols = {"Player", "Whitelisted", "Launchers", "Last joined", "Server control", "MCSS control", "Machine wake/sleep", "Gatekeeper control", "Status"};
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
			JButton setupB = new JButton("Server setup\u2026"), pairB = new JButton("Copy pairing address");
			setupB.setToolTipText("Which MCSS and which Minecraft servers on this machine Gatekeeper looks after, and this machine's Tailscale address.");
			pairB.setToolTipText("The address a launcher is given to pair with this Gatekeeper (in its Server config tab).");
			JLabel pairNote = new JLabel(" ");
			pairNote.setForeground(new Color(0x8F98A0));
			JPanel setupRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2)); setupRow.add(setupB); setupRow.add(pairB); setupRow.add(pairNote);
			pairB.addActionListener(e -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup/scan", "{}")));
					if (Json.str(r.get("bind")).isEmpty()) { pairNote.setText("No Tailscale address yet: use Server setup first."); return; }
					String addr = Json.str(r.get("bind")) + ":" + Json.str(r.get("port"));
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(addr), null);
					pairNote.setText("Copied " + addr + ". In the launcher: Server config, Gatekeeper, Pair.");
				} catch (Exception ex) { pairNote.setText("Couldn't ask Gatekeeper: is it running?"); }
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
					} catch (Exception ex) { problem.setText("Couldn't ask Gatekeeper: is it running? (Task Scheduler, Gatehouse Keeper)"); }
				};
				JButton browse = new JButton("Browse\u2026"), again = new JButton("Look again");
				browse.addActionListener(ev -> {
					javax.swing.JFileChooser fc = new javax.swing.JFileChooser(exeF.getText().isBlank() ? "C:\\" : exeF.getText());
					fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MCSS (mcss.exe)", "exe"));
					if (fc.showOpenDialog(f) == javax.swing.JFileChooser.APPROVE_OPTION) { exeF.setText(fc.getSelectedFile().getAbsolutePath()); scan.run(); }
				});
				again.addActionListener(ev -> scan.run());
				JPanel form = new JPanel();
				form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
				JPanel exeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); exeRow.add(exeF); exeRow.add(browse); exeRow.add(again);
				JPanel addrRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); addrRow.add(addrF);
				java.util.function.Function<String, JLabel> subHead = hs -> { JLabel l = new JLabel(hs); l.setFont(l.getFont().deriveFont(Font.BOLD)); l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0)); return l; };
				for (javax.swing.JComponent c : new javax.swing.JComponent[] {subHead.apply("MCSS on this machine (mcss.exe)"), exeRow,
						subHead.apply("This machine's Tailscale address (launchers reach Gatekeeper on it)"), addrRow,
						subHead.apply("Minecraft servers in MCSS (tick the ones Gatekeeper looks after)"), list, problem}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); }
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
			tabs.addTab("Access Manager", accessTab); tabs.addTab("Status", statusTab);
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
						java.util.function.Function<String, String> word = st -> st.equals("allowed") ? "Allowed" : st.equals("denied") ? "Denied" : "Not allowed";
						for (Map.Entry<String, Object> e : Json.map(r.get("devices")).entrySet()) {
							Map<String, Object> d = Json.map(e.getValue());
							ids.add(e.getKey());
							boolean pm = Boolean.TRUE.equals(d.get("granted")), ps = Boolean.TRUE.equals(d.get("sleep")), pv = Boolean.TRUE.equals(d.get("server")), pa = Boolean.TRUE.equals(d.get("admin"));
							perms.put(e.getKey(), new boolean[] {pm, ps, pv, pa});
							model.addRow(new Object[] {d.get("name"), Json.str(d.get("player")).isEmpty() ? "" : Json.str(d.get("player")) + (Boolean.TRUE.equals(d.get("player_confirmed")) ? "" : " (not confirmed)"), d.get("tailscale"), d.get("os"), d.get("first_seen"), d.get("last_seen"),
									word.apply(Json.str(d.get("state_server"))).equals("Not allowed") && pm ? "Allowed (with MCSS control)" : word.apply(Json.str(d.get("state_server"))),
									word.apply(Json.str(d.get("state_mcss"))), word.apply(Json.str(d.get("state_machine"))), word.apply(Json.str(d.get("state_admin"))), Json.str(d.get("access_text")), Json.str(d.get("ban_text"))});
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
									Json.str(q.get("ban")).isEmpty() ? "" : (Json.str(q.get("ban")).equals("blacklist") ? "Blacklisted" : "Suspended") + (Json.str(q.get("ban_reason")).isEmpty() ? "" : ": " + Json.str(q.get("ban_reason")))});
							if (e.getKey().equals(pkeep)) ptable.setRowSelectionInterval(pnames.size() - 1, pnames.size() - 1);
						}
						filling[0] = false;
						boolean hasKey = Boolean.TRUE.equals(r.get("has_key"));
						String works = Json.str(r.get("key_works"));
						keyState.setForeground(!hasKey ? new Color(0xE9B949) : !works.equals("yes") ? new Color(0xFF7B72) : new Color(0x8BD46E));
						String as = Json.str(r.get("mcss_user"));
						keyState.setText(works.equals("forbidden") ? "MCSS knows Gatekeeper's key, but it has no permission for this server: give it the server (view, start, stop) under API Keys in MCSS's panel."
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
						mcssRun.setText(!mcssUp[0] ? "Start MCSS" : mcssClose[0].equals("force") ? "Force close MCSS" : "Close MCSS");
						mcssRun.setEnabled(!mcssUp[0] || !Boolean.TRUE.equals(r.get("minecraft")));
						mcssRun.setToolTipText(mcssUp[0] && Boolean.TRUE.equals(r.get("minecraft")) ? "Stop the Minecraft server first" : "Starts MCSS on this machine, or closes it (only once the Minecraft server is stopped).");
						state.setForeground(new Color(0x8BD46E));
						state.setText("Running on " + r.get("bind") + ":" + r.get("port") + " (launchers reach it over Tailscale).");
						mcssState.setForeground(Boolean.TRUE.equals(r.get("mcss")) ? new Color(0x8BD46E) : new Color(0x8F98A0));
						mcssState.setText(Boolean.TRUE.equals(r.get("mcss")) ? "MCSS is running" : "MCSS isn't running");
						serverUpNow[0] = Boolean.TRUE.equals(r.get("minecraft"));
						serverState.setForeground(serverUpNow[0] ? new Color(0x8BD46E) : new Color(0x8F98A0));
						serverState.setText(serverUpNow[0] ? "The Minecraft server is running" : "The Minecraft server is off");
						serverRun.setText(serverUpNow[0] ? "Stop server" : "Start server");
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
				mcssB.setText(pp[0] ? "Remove MCSS control" : "Allow MCSS control");
				sleepB.setText(pp[1] ? "Remove PC sleep" : "Allow machine wake/sleep");
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
						try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/player/perm", Json.write(m("player", name, "perm", pkeys[k], "value", v))))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
						String m2 = msg;
						SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "<html><div style='width:420px'>" + m2 + "</div></html>", "Players", JOptionPane.PLAIN_MESSAGE));
						refresh.run();
					}, "player-perm").start();
				});
			}
			refreshLater[0] = refresh;
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
			pKick.addActionListener(e -> shutOut.accept(true, "kick")); pSuspend.addActionListener(e -> shutOut.accept(true, "suspend")); pBlack.addActionListener(e -> shutOut.accept(true, "blacklist")); pLift.addActionListener(e -> shutOut.accept(true, ""));
			pLift.setToolTipText("Lifts a suspension or blacklisting of the selected player");
			for (JButton b : new JButton[] {pKick, pSuspend, pBlack, pLift}) pbuttons.add(b);
			// The buttons, in three sets: what the launcher (or player) may do with the server, shutting someone out, and
			// the list itself.
			revokeB.setText("Remove server accesses");
			java.util.function.BiFunction<String, JButton[], JPanel> set = (title, bs) -> {
				JPanel g = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
				javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), title);
				tb.setTitleColor(new Color(0x8F98A0));
				g.setBorder(tb);
				for (JButton b : bs) g.add(b);
				return g;
			};
			buttons.removeAll();
			buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.X_AXIS));
			for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {sleepB, mcssB, serverB, adminB, revokeB}), set.apply("Administration actions", new JButton[] {dSuspend, dBlack, dLift}), set.apply("List", new JButton[] {refreshB, forget}))) {
				g.setAlignmentY(java.awt.Component.TOP_ALIGNMENT); buttons.add(g); buttons.add(javax.swing.Box.createHorizontalStrut(6));
			}
			buttons.add(javax.swing.Box.createHorizontalGlue());
			pbuttons.removeAll();
			pbuttons.setLayout(new javax.swing.BoxLayout(pbuttons, javax.swing.BoxLayout.X_AXIS));
			for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {pB[2], pB[1], pB[0], pB[3]}), set.apply("Administration actions", new JButton[] {pKick, pSuspend, pBlack, pLift}))) {
				g.setAlignmentY(java.awt.Component.TOP_ALIGNMENT); pbuttons.add(g); pbuttons.add(javax.swing.Box.createHorizontalStrut(6));
			}
			pbuttons.add(javax.swing.Box.createHorizontalGlue());
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
			// Testing: OJ_PANEL_PNG=<file> saves a picture of each tab (<file>-access.png, -players.png, -status.png) after
			// the first readings, instead of showing the window.
			String shot = System.getenv("OJ_PANEL_PNG");
			if (shot != null && !shot.isEmpty()) {
				Timer once = new Timer(3500, e -> {
					setup.run();
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
