package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

/**
 * Gatekeeper's web panel: a page, served on this machine's Tailscale address only (the same listener launchers use),
 * where someone with an account signs in and controls Gatekeeper from a browser.
 *
 * Accounts (users.json) are made, changed and removed only in Gatekeeper's window on this machine, never through
 * the web. A password is never kept: only a salted PBKDF2-HMAC-SHA256 hash of it. Signing in gives the browser a
 * session cookie (HttpOnly, SameSite=Strict, for /panel only); Gatekeeper keeps only a hash of it, in memory, so
 * every session ends when Gatekeeper restarts. Five wrong passwords for an account from one address lock that pair
 * out for five minutes.
 *
 * What a signed-in account may do is the window's own calls (/admin/...), handed through one by one from the table
 * below, each needing one of the account's permissions. Calls that could run a program of the caller's choosing on
 * this machine (an own start command, a Java path, an installer or jar by path), Gatekeeper's access to MCSS, Server
 * setup and the accounts themselves are not in the table at all: those stay at the machine.
 *
 * It also sends Wake-on-LAN "magic packets" to other devices on this machine's own network (host.json
 * "wake_devices": name and MAC address), which wakes a sleeping PC whose network adapter is set to listen for them.
 */
final class WebPanel {
	/** An account's permissions, and what each is called. An admin account has all of them, and the admin-only calls. */
	static final String[][] PERMS = {{"view", "See servers, players and the log"}, {"control", "Start, stop and restart servers"}, {"console", "Read and type in a server's console, kick"},
			{"manage", "Server settings, backups and tasks"}, {"access", "Launchers, players and their permissions"}, {"machine", "Sleep this machine, wake other devices"}};
	/** The window's calls the panel hands through: "METHOD path" -> the permission it needs ("admin": admin accounts only). */
	static final Map<String, String> ALLOWED = new LinkedHashMap<>();
	static {
		for (String c : new String[] {"GET overview", "GET players", "GET log", "GET about", "GET checklist", "GET tools/server"}) ALLOWED.put(c, "view");
		for (String c : new String[] {"POST server/start", "POST server/stop", "POST tools/action", "POST mcss/start", "POST mcss/close"}) ALLOWED.put(c, "control");
		for (String c : new String[] {"POST tools/console", "POST tools/command", "POST kick"}) ALLOWED.put(c, "console");
		for (String c : new String[] {"POST tools/server", "POST tools/properties", "POST tools/backup", "POST tools/task", "POST whitelist/on"}) ALLOWED.put(c, "manage");
		for (String c : new String[] {"GET devices", "POST request/answer", "POST requests/clear", "POST perm", "POST forget", "POST revoke", "POST ban", "POST whitelist", "POST player/perm"}) ALLOWED.put(c, "access");
		for (String c : new String[] {"GET wake", "POST wake", "POST sleep"}) ALLOWED.put(c, "machine");
		for (String c : new String[] {"GET update", "POST update", "GET tools/new", "POST tools/new", "POST tools/versions"}) ALLOWED.put(c, "admin");
	}
	/** Things in a call's body that only the machine's own window may set (they name a program or a place on its disk). */
	static final List<String> MACHINE_ONLY = List.of("start", "java", "backup_dir", "file", "add", "remove");

	static final SecureRandom RANDOM = new SecureRandom();
	static final int ROUNDS = 210_000;
	static final long IDLE = 2 * 3600_000L, LONGEST = 24 * 3600_000L;

	static final class Session { String user, csrf, from; long began = System.currentTimeMillis(), last = began; }
	static final Map<String, Session> sessions = new ConcurrentHashMap<>();   // (SHA-256 of the cookie -> who)
	static final Map<String, long[]> wrong = new ConcurrentHashMap<>();       // (account|address -> {how many, locked until})

	static boolean on() { return setting("web_panel", "false").equals("true"); }
	static String address() { return setting("bind", "").isEmpty() ? "" : "http://" + setting("bind", "") + ":" + port() + "/panel/"; }

	// ------------------------------------------------------------------ accounts

	static synchronized Map<String, Object> users() { return Json.map(Json.map(Json.parse(read(dir.resolve("users.json")))).get("users")); }
	static synchronized void saveUsers(Map<String, Object> u) throws IOException { Files.writeString(dir.resolve("users.json"), Json.write(m("users", u)), StandardCharsets.UTF_8); }
	static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
	static byte[] unhex(String s) { byte[] b = new byte[s.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16); return b; }
	static String hash(char[] password, byte[] salt, int rounds) {
		try { return hex(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new javax.crypto.spec.PBEKeySpec(password, salt, rounds, 256)).getEncoded()); }
		catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
	}
	static String sha(String s) { try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
	static String random(int bytes) { byte[] b = new byte[bytes]; RANDOM.nextBytes(b); return hex(b); }

	/** Makes, changes or removes an account (from the window on this machine only). A password is needed for a new
	 *  account and optional afterwards; changing it, disabling or removing the account ends its sessions. */
	static synchronized String[] saveUser(Map<String, Object> b) throws IOException {
		String name = Json.str(b.get("name")).trim(), password = Json.str(b.get("password"));
		if (!name.matches("[A-Za-z0-9._-]{2,32}")) return new String[] {"refused", "An account name is 2 to 32 letters, digits, dots, dashes or underscores."};
		Map<String, Object> all = users();
		String key = null;
		for (String k : all.keySet()) if (k.equalsIgnoreCase(name)) key = k;
		if (ServerTools.yes(b.get("remove"))) {
			if (key == null) return new String[] {"refused", "There's no account called " + name};
			all.remove(key); saveUsers(all); endSessions(key);
			log("web panel: account " + key + " removed");
			return new String[] {"ok", "Removed " + key + ". It is signed out everywhere."};
		}
		Map<String, Object> u = key == null ? new LinkedHashMap<>() : Json.map(all.get(key));
		if (key == null && password.isEmpty()) return new String[] {"refused", "A new account needs a password."};
		if (!password.isEmpty()) {
			if (password.length() < 10) return new String[] {"refused", "Use a password of at least 10 characters."};
			if (password.equalsIgnoreCase(name)) return new String[] {"refused", "The password can't be the account's name."};
			byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
			u.put("salt", hex(salt)); u.put("rounds", ROUNDS); u.put("hash", hash(password.toCharArray(), salt, ROUNDS));
		}
		if (key == null) u.put("made", LocalDateTime.now().format(TIME));
		if (b.containsKey("admin") || key == null) u.put("admin", ServerTools.yes(b.get("admin")));
		if (b.containsKey("enabled") || key == null) u.put("enabled", !b.containsKey("enabled") || ServerTools.yes(b.get("enabled")));
		if (b.containsKey("perms") || key == null) {
			List<Object> perms = new ArrayList<>();
			for (String[] p : PERMS) if (Json.list(b.get("perms")).contains(p[0])) perms.add(p[0]);
			u.put("perms", perms);
		}
		String as = key == null ? name : key;
		all.put(as, u); saveUsers(all);
		if (!password.isEmpty() || !ServerTools.yes(u.get("enabled"))) endSessions(as);
		log("web panel: account " + as + (key == null ? " made" : " changed") + (password.isEmpty() ? "" : " (password set)") + ": " + (ServerTools.yes(u.get("admin")) ? "admin" : "may " + Json.list(u.get("perms"))) + (ServerTools.yes(u.get("enabled")) ? "" : ", disabled"));
		return new String[] {"ok", (key == null ? "Made " : "Saved ") + as + "."};
	}
	static void endSessions(String user) { sessions.values().removeIf(s -> s.user.equalsIgnoreCase(user)); }
	static boolean may(Map<String, Object> u, String perm) { return ServerTools.yes(u.get("admin")) || (!perm.equals("admin") && Json.list(u.get("perms")).contains(perm)); }

	// ------------------------------------------------------------------ waking other devices

	static List<Object> wakeDevices() { return Json.list(cfg.get("wake_devices")); }
	static String macOf(String s) { String h = s.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT); return h.length() == 12 ? String.join(":", h.split("(?<=\\G..)")) : ""; }
	/** Sends a device its Wake-on-LAN packet, on every network this machine is on that has a broadcast address. */
	static String[] wake(String name) {
		Map<String, Object> dev = null;
		for (Object o : wakeDevices()) if (Json.str(Json.map(o).get("name")).equalsIgnoreCase(name)) dev = Json.map(o);
		if (dev == null) return new String[] {"refused", "There's no device called " + name + " to wake (they are added in Gatekeeper's window, Web panel tab)"};
		byte[] mac = unhex(macOf(Json.str(dev.get("mac"))).replace(":", "")), packet = new byte[102];
		for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
		for (int i = 6; i < 102; i++) packet[i] = mac[(i - 6) % 6];
		int sent = 0;
		try {
			for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback()) continue;
				for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
					if (ia.getBroadcast() == null) continue;
					try (java.net.DatagramSocket s = new java.net.DatagramSocket(0, ia.getAddress())) {
						s.setBroadcast(true);
						for (int port : new int[] {9, 7}) s.send(new java.net.DatagramPacket(packet, packet.length, ia.getBroadcast(), port));
						sent++;
					} catch (IOException ignored) {}
				}
			}
		} catch (IOException ignored) {}
		if (sent == 0) return new String[] {"failed", "This machine is on no network a wake signal can be sent on."};
		return new String[] {"ok", "Wake signal sent to " + Json.str(dev.get("name")) + " (" + macOf(Json.str(dev.get("mac"))) + "). It only arrives on this machine's own network, and only wakes a device set up to listen for it; give it half a minute."};
	}
	static synchronized String[] saveWake(Map<String, Object> b) throws IOException {
		List<Object> list = new ArrayList<>(wakeDevices());
		if (b.containsKey("remove")) {
			String gone = Json.str(b.get("remove"));
			if (!list.removeIf(o -> Json.str(Json.map(o).get("name")).equalsIgnoreCase(gone))) return new String[] {"refused", "There's no device called " + gone};
			cfg.put("wake_devices", list); ServerTools.saveCfg();
			return new String[] {"ok", "Removed " + gone + "."};
		}
		Map<String, Object> add = Json.map(b.get("add"));
		String name = clean(Json.str(add.get("name")), 40), mac = macOf(Json.str(add.get("mac")));
		if (name.isEmpty()) return new String[] {"refused", "Give the device a name."};
		if (mac.isEmpty()) return new String[] {"refused", "A MAC address is twelve hex digits, like 1C:2D:3E:4F:5A:6B (on that device: Settings, Network, its adapter's Physical address)."};
		list.removeIf(o -> Json.str(Json.map(o).get("name")).equalsIgnoreCase(name));
		list.add(m("name", name, "mac", mac));
		cfg.put("wake_devices", list); ServerTools.saveCfg();
		log("web panel: device to wake saved: " + name + " (" + mac + ")");
		return new String[] {"ok", "Saved " + name + "."};
	}

	// ------------------------------------------------------------------ the window's own calls (this machine only)

	static boolean admin(HttpExchange x, String path) throws IOException {
		boolean post = x.getRequestMethod().equals("POST");
		if (path.equals("/admin/panel")) {
			if (post) {
				Map<String, Object> b = body(x);
				boolean want = ServerTools.yes(b.get("on"));
				cfg.put("web_panel", want ? "true" : "false"); ServerTools.saveCfg();
				if (!want) sessions.clear();
				log("web panel turned " + (want ? "on" : "off"));
				reply(x, 200, m("result", "ok", "message", want ? (address().isEmpty() ? "On. It needs this machine's Tailscale address first (Server setup)." : "On: " + address() + " (from a device on your Tailscale network).") : "Off. Everyone is signed out."));
				return true;
			}
			List<Object> list = new ArrayList<>();
			for (Map.Entry<String, Object> e : users().entrySet()) {
				Map<String, Object> u = Json.map(e.getValue());
				int in = 0; for (Session s : sessions.values()) if (s.user.equals(e.getKey())) in++;
				list.add(m("name", e.getKey(), "admin", ServerTools.yes(u.get("admin")), "enabled", ServerTools.yes(u.get("enabled")), "perms", Json.list(u.get("perms")), "made", Json.str(u.get("made")), "last_in", Json.str(u.get("last_in")), "signed_in", in));
			}
			List<Object> perms = new ArrayList<>();
			for (String[] p : PERMS) perms.add(m("id", p[0], "name", p[1]));
			reply(x, 200, m("on", on(), "address", address(), "users", list, "perms", perms, "wake_devices", wakeDevices()));
		} else if (path.equals("/admin/panel/user")) {
			String[] r = saveUser(body(x));
			reply(x, 200, m("result", r[0], "message", r[1]));
		} else if (path.equals("/admin/wake")) {
			if (!post) { reply(x, 200, m("wake_devices", wakeDevices())); return true; }
			Map<String, Object> b = body(x);
			String[] r = b.containsKey("send") ? wake(Json.str(b.get("send"))) : saveWake(b);
			if (b.containsKey("send")) log("wake " + clean(Json.str(b.get("send")), 40) + ": " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1]));
		} else if (path.equals("/admin/sleep")) {
			String[] r = sleepPc();
			log("sleep this machine: " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1]));
		} else return false;
		return true;
	}

	// ------------------------------------------------------------------ the web side: /panel/...

	static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
		x.getResponseHeaders().set("Content-Type", type);
		x.getResponseHeaders().set("Cache-Control", "no-store");
		x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		x.getResponseHeaders().set("X-Frame-Options", "DENY");
		x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
		x.sendResponseHeaders(code, body.length);
		try (OutputStream o = x.getResponseBody()) { o.write(body); }
	}
	static void json(HttpExchange x, int code, Map<String, Object> body) throws IOException { send(x, code, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8)); }
	static String cookie(HttpExchange x) {
		for (String h : x.getRequestHeaders().getOrDefault("Cookie", List.of())) for (String part : h.split(";")) if (part.trim().startsWith("gk_session=")) return part.trim().substring(11);
		return "";
	}
	static Session session(HttpExchange x) {
		String c = cookie(x);
		if (c.isEmpty()) return null;
		Session s = sessions.get(sha(c));
		long now = System.currentTimeMillis();
		if (s == null) return null;
		Map<String, Object> u = Json.map(users().get(s.user));
		if (now - s.last > IDLE || now - s.began > LONGEST || u.isEmpty() || !ServerTools.yes(u.get("enabled"))) { sessions.remove(sha(c)); return null; }
		s.last = now;
		return s;
	}
	static Map<String, Object> who(Session s) {
		Map<String, Object> u = Json.map(users().get(s.user));
		List<Object> perms = new ArrayList<>();
		for (String[] p : PERMS) if (may(u, p[0])) perms.add(p[0]);
		String name = setting("name", "");
		if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Gatekeeper"; }
		return m("user", s.user, "admin", ServerTools.yes(u.get("admin")), "perms", perms, "csrf", s.csrf, "gatekeeper", name, "version", ownVersion(), "system", systemName(), "host", nativeOk() ? "builtin" : "mcss");
	}

	static void web(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
			String from = x.getRemoteAddress().getAddress().getHostAddress();
			if (!on()) { send(x, 404, "text/plain; charset=utf-8", "The web panel is off. Its owner turns it on in Gatekeeper's window (Web panel tab).".getBytes(StandardCharsets.UTF_8)); return; }
			if (method.equals("GET") && (path.equals("/panel") || path.equals("/panel/"))) { page(x, "panel.html", "text/html; charset=utf-8"); return; }
			if (method.equals("GET") && path.equals("/panel/panel.js")) { page(x, "panel.js", "text/javascript; charset=utf-8"); return; }
			if (method.equals("GET") && path.equals("/panel/panel.css")) { page(x, "panel.css", "text/css; charset=utf-8"); return; }
			if (path.equals("/panel/login") && method.equals("POST")) { login(x, from); return; }
			Session s = session(x);
			if (s == null) { json(x, 401, m("result", "signin", "message", "Sign in first.")); return; }
			if (path.equals("/panel/me") && method.equals("GET")) { json(x, 200, who(s)); return; }
			// (anything that changes something must carry the session's own token: another site can't know it)
			if (!method.equals("GET") && !MessageDigest.isEqual(s.csrf.getBytes(StandardCharsets.UTF_8), header(x, "X-Panel-Token").getBytes(StandardCharsets.UTF_8))) { json(x, 403, m("result", "refused", "message", "That request didn't come from the panel's own page.")); return; }
			if (path.equals("/panel/logout") && method.equals("POST")) {
				sessions.remove(sha(cookie(x)));
				x.getResponseHeaders().add("Set-Cookie", "gk_session=; Path=/panel; Max-Age=0; HttpOnly; SameSite=Strict");
				json(x, 200, m("result", "ok")); return;
			}
			if (!path.startsWith("/panel/api/")) { json(x, 404, m("result", "refused", "message", "No such page.")); return; }
			String call = path.substring(11), need = ALLOWED.get(method + " " + call);
			Map<String, Object> u = Json.map(users().get(s.user));
			if (need == null) { json(x, 403, m("result", "refused", "message", "That can only be done in Gatekeeper's own window, at the machine.")); return; }
			if (!may(u, need)) { json(x, 403, m("result", "refused", "message", "Your account isn't allowed to do that (" + need + ").")); return; }
			String body = null;
			if (method.equals("POST")) {
				Map<String, Object> b;
				try (InputStream in = x.getRequestBody()) { b = Json.map(Json.parse(new String(in.readNBytes(200_000), StandardCharsets.UTF_8))); }
				for (String k : MACHINE_ONLY) if (b.containsKey(k) && !Json.str(b.get(k)).isEmpty()) { json(x, 403, m("result", "refused", "message", "\"" + k + "\" can only be set in Gatekeeper's own window, at the machine.")); return; }
				if (call.equals("tools/new") && List.of("forge", "file").contains(Json.str(b.get("kind")))) { json(x, 403, m("result", "refused", "message", "A server from an installer or a jar on the machine's disk is made in Gatekeeper's own window.")); return; }
				body = Json.write(b);
			}
			String query = x.getRequestURI().getQuery(), server = serverIn(query);
			String answer = local(method, "/admin/" + call + (server.isEmpty() ? call.equals("update") && "1".equals(query) ? "?1" : "" : "?server=" + server), body);
			if (method.equals("POST") && !call.equals("tools/console") && !call.equals("tools/versions")) {
				Map<String, Object> said = Json.map(Json.parse(answer));
				log("web panel, " + s.user + ": " + call + (server.isEmpty() ? "" : " (" + server + ")") + (Json.str(said.get("message")).isEmpty() ? "" : ": " + clean(Json.str(said.get("message")), 160)));
			}
			send(x, 200, "application/json; charset=utf-8", answer.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			try { json(x, 500, m("result", "failed", "message", "Gatekeeper couldn't do that: " + e)); } catch (IOException ignored) {}
		}
	}
	static String local(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(path.startsWith("/admin/update") ? 180_000 : 30000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}
	static void page(HttpExchange x, String name, String type) throws IOException {
		try (InputStream in = WebPanel.class.getResourceAsStream("/web/" + name)) {
			if (in == null) { send(x, 404, "text/plain; charset=utf-8", "missing".getBytes(StandardCharsets.UTF_8)); return; }
			send(x, 200, type, in.readAllBytes());
		}
	}

	static void login(HttpExchange x, String from) throws IOException {
		Map<String, Object> b;
		try (InputStream in = x.getRequestBody()) { b = Json.map(Json.parse(new String(in.readNBytes(4000), StandardCharsets.UTF_8))); }
		String name = Json.str(b.get("user")).trim(), password = Json.str(b.get("password"));
		String key = null;
		Map<String, Object> all = users();
		for (String k : all.keySet()) if (k.equalsIgnoreCase(name)) key = k;
		String pair = name.toLowerCase(Locale.ROOT) + "|" + from;
		long now = System.currentTimeMillis();
		long[] w = wrong.computeIfAbsent(pair, k -> new long[2]);
		if (wrong.size() > 2000) wrong.entrySet().removeIf(e -> e.getValue()[1] < now - 600_000 && e.getValue() != w);
		if (w[1] > now) { json(x, 429, m("result", "refused", "message", "Too many wrong passwords: wait " + Math.max(1, (w[1] - now) / 60_000 + 1) + " minutes, then try again.")); return; }
		Map<String, Object> u = key == null ? new LinkedHashMap<>() : Json.map(all.get(key));
		// (an account that isn't there costs the same time as one that is, so the answer doesn't say which it was)
		byte[] salt = u.isEmpty() ? new byte[16] : unhex(Json.str(u.get("salt")));
		int rounds = ROUNDS; try { if (!u.isEmpty()) rounds = (int) Double.parseDouble(Json.str(u.get("rounds"))); } catch (NumberFormatException ignored) {}
		boolean right = MessageDigest.isEqual(hash(password.toCharArray(), salt, rounds).getBytes(StandardCharsets.UTF_8), Json.str(u.get("hash")).getBytes(StandardCharsets.UTF_8)) && !u.isEmpty() && ServerTools.yes(u.get("enabled"));
		if (!right) {
			w[0]++;
			if (w[0] >= 5) { w[0] = 0; w[1] = now + 300_000; }
			log("web panel: sign-in refused for \"" + clean(name, 32) + "\" from " + from + (tailscaleName(from).isEmpty() ? "" : " (" + tailscaleName(from) + ")") + (w[1] > now ? "; that pair is locked out for 5 minutes" : ""));
			json(x, 401, m("result", "refused", "message", "That name and password don't match an account here."));
			return;
		}
		wrong.remove(pair);
		if (sessions.size() > 200) sessions.entrySet().removeIf(e -> now - e.getValue().last > IDLE);
		String token = random(32);
		Session s = new Session();
		s.user = key; s.csrf = random(16); s.from = from;
		sessions.put(sha(token), s);
		synchronized (WebPanel.class) { Map<String, Object> again = users(); Map<String, Object> me = Json.map(again.get(key)); if (!me.isEmpty()) { me.put("last_in", LocalDateTime.now().format(TIME)); saveUsers(again); } }
		log("web panel: " + key + " signed in from " + from + (tailscaleName(from).isEmpty() ? "" : " (" + tailscaleName(from) + ")"));
		x.getResponseHeaders().add("Set-Cookie", "gk_session=" + token + "; Path=/panel; Max-Age=86400; HttpOnly; SameSite=Strict");
		json(x, 200, who(s));
	}
}
