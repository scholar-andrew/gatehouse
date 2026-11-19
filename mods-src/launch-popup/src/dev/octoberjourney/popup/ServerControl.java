package dev.octoberjourney.popup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.gatehouse.look.Quiet;

/**
 * The Server tab's controls: whether the server answers, waking its PC (Wake on LAN), starting the Minecraft server
 * through the MCSS web panel's API, and opening the panel in a browser.
 *
 * Shared settings come with the pack (extras/server-control.json, from installer/config.json "server_control"): the
 * panel's address, the server's MCSS id, the game's address, and the PC's network card and home broadcast addresses
 * for waking. Each device's own settings are in server-access.json in the game folder and never leave it: the
 * player's MCSS API key (made for them in the panel, with the permissions the owner chose) and an optional internet
 * wake address (host:port of a router that passes the wake signal on to the home network).
 */
final class ServerControl {
	static { Pins.launcher(); }   // (R79: Gatekeepers with HTTPS of their own are checked by fingerprint)
	final Path dir;
	final String panelUrl, serverId, minecraft, mac;
	/** Where Gatekeeper is: the address this device was paired with (Server config, kept in server-access.json),
	 *  else the one the pack names. */
	volatile String helperUrl;
	final String packHelperUrl;
	volatile String keeperUrl = "";
	/** This launcher's id and secret for each Gatekeeper it has talked to, by address: a Gatekeeper is never shown the secret
	 *  made for another one. */
	final Map<String, String[]> identities = new java.util.LinkedHashMap<>();
	final List<String> broadcasts = new ArrayList<>();
	final int wakePort;
	String apiKey, wakeAddress, username;
	/** This launcher's identity for Gatekeeper: an id and a secret it made itself (kept in server-access.json). */
	String deviceId, deviceToken;
	/** The profile's name for its server on the host machine (extras/server-control.json "server"), sent to the host
	 *  helper so a machine with several servers knows which one this pack means. Empty: its first. */
	String serverKey = "";
	/** What the owner has allowed this launcher in Gatekeeper, as Gatekeeper last said (remembered in
	 *  server-access.json, so it's known while the server machine is asleep and Gatekeeper can't be asked). */
	boolean permMcss, permSleep, permServer, permAdmin;
	/** What became of this launcher's last request for the server to be turned on, as Gatekeeper says: "asked",
	 *  "denied" (the owner dismissed it), or empty. */
	volatile String requestStatus = "";
	/** What MCSS is doing, as Gatekeeper last said: "running", "starting", "closing", "off" (or empty: not told). */
	volatile String mcssPhase = "";
	/** Why the Gatekeeper won't have this device join right now ("": it may): the Gatekeeper is out of date, or this
	 *  Gatehouse is. It says so with what it reports about the server (/v1/phase). */
	volatile String blocked = "";
	private static final String OWN_VERSION = Notes.version(Notes.own());
	/** Whether this launcher is shut out, as Gatekeeper says (remembered, so it holds while the server machine can't be
	 *  asked): "blacklist" (told nothing about the machine, MCSS or the server), "suspend" (may not start the game or
	 *  use any control), or empty. Scope "machine" (this device) or "user" (the player, on every device). */
	volatile String ban = "", banScope = "", banReason = "";
	/** Is this device's player on the server's whitelist: "yes", "no", or "unknown" (Gatekeeper doesn't know the name
	 *  yet, or couldn't be asked). */
	volatile String whitelisted = "unknown";
	/** Each permission's state ("allowed", "denied", "none") for machine, mcss and server; and what became of this
	 *  launcher's recent requests, by kind ("asked", "granted", "denied"), with the owner's reason for a dismissal. */
	final Map<String, String> permStates = new java.util.concurrent.ConcurrentHashMap<>(), reqStates = new java.util.concurrent.ConcurrentHashMap<>(),
			reqReasons = new java.util.concurrent.ConcurrentHashMap<>();
	String permState(String perm) {
		String s = permStates.get(perm);
		if (s != null) return s;
		return (perm.equals("machine") ? permSleep : perm.equals("mcss") ? permMcss : permServer) ? "allowed" : "none";
	}

	/** Asks the owner for something through Gatekeeper (a chime and a line in Gatekeeper there): "server"
	 *  (turn the server on), "perm:machine", "perm:mcss", "perm:server", "whitelist", "unblacklist" or "unsuspend",
	 *  with an optional message. Any launcher Gatekeeper knows may ask. */
	Map<String, Object> request(String type, String message) throws IOException {
		String body = "{\"type\": \"" + jsonEsc(type) + "\", \"message\": \"" + jsonEsc(message == null ? "" : message) + "\"}";
		Map<String, Object> r = helper("POST", "/v1/request", body);
		if (str(r.get("_code")).equals("401")) { hello(); r = helper("POST", "/v1/request", body); }
		return r;
	}
	/** The server panel refused the sign-in or key saved here (signed out there, expired, or no access to this server). */
	volatile boolean accessRejected;


	/** A request Gatekeeper (or MCSS behind it) turned down, with its reason. */
	static final class Refused extends IOException {
		Refused(String why) { super(why); }
	}

	ServerControl(Path dir) {
		this.dir = dir;
		Map<String, Object> c = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(dir.resolve("extras/server-control.json"))));
		Map<String, Object> w = asMap(c.get("wake"));
		panelUrl = str(c.get("panel_url"));
		serverId = str(c.get("mcss_server"));
		minecraft = str(c.get("minecraft"));
		packHelperUrl = str(c.get("helper_url"));
		if (packHelperUrl.startsWith("https:")) Pins.pin(Pins.hostPort(packHelperUrl), str(c.get("helper_fp")));   // (R79)
		mac = str(w.get("mac"));
		serverKey = str(c.get("server")).replaceAll("[^\\w.-]", "");
		if (w.get("broadcasts") instanceof List<?> l) for (Object o : l) broadcasts.add(str(o));
		int port = 9;
		try { port = Integer.parseInt(str(w.get("port"))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
		wakePort = port;
		Map<String, Object> a = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(access())));
		apiKey = str(a.get("api_key"));
		wakeAddress = str(a.get("wake_address"));
		username = str(a.get("username"));
		deviceId = str(a.get("device_id"));
		deviceToken = str(a.get("device_token"));
		ban = str(a.get("ban")); banScope = str(a.get("ban_scope")); banReason = str(a.get("ban_reason"));
		permMcss = "true".equals(str(a.get("perm_mcss")));
		permSleep = "true".equals(str(a.get("perm_sleep")));
		permServer = "true".equals(str(a.get("perm_server")));
		permAdmin = "true".equals(str(a.get("perm_admin")));
		keeperUrl = str(a.get("keeper_url"));
		helperUrl = keeperUrl.isEmpty() ? packHelperUrl : keeperUrl;
		for (Map.Entry<String, Object> e : asMap(a.get("identities")).entrySet()) identities.put(e.getKey(),
				new String[] {str(asMap(e.getValue()).get("id")), str(asMap(e.getValue()).get("token"))});
		// (An id and secret saved before pairing existed belong to the pack's Gatekeeper.)
		if (!deviceId.isEmpty() && deviceToken.length() >= 32 && !packHelperUrl.isEmpty()
				&& !identities.containsKey(packHelperUrl)) identities.put(packHelperUrl, new String[] {deviceId, deviceToken});
		identity();
	}

	/** This launcher's id and secret for Gatekeeper in use: the ones it has for that address, else new ones. */
	private void identity() {
		if (helperUrl.isEmpty()) return;
		String[] have = identities.get(helperUrl);
		if (have == null || have[0].isEmpty() || have[1].length() < 32) {
			java.security.SecureRandom r = new java.security.SecureRandom();
			byte[] t = new byte[32]; r.nextBytes(t);
			StringBuilder b = new StringBuilder(); for (byte x : t) b.append(String.format("%02x", x));
			have = new String[] {java.util.UUID.randomUUID().toString(), b.toString()};
			identities.put(helperUrl, have);
		}
		boolean changed = !have[0].equals(deviceId) || !have[1].equals(deviceToken);
		deviceId = have[0]; deviceToken = have[1];
		if (changed) try { save(apiKey, wakeAddress); } catch (IOException ignored) { Quiet.seen(ignored); }
	}

	/** Pairs this launcher with a Gatekeeper by its address (the owner copies it from Gatekeeper: Status,
	 *  Copy pairing address), or with an empty address goes back to Gatekeeper the pack names. The address has to
	 *  answer as a Gatekeeper before it's kept. What the old Gatekeeper allowed doesn't carry over: this launcher introduces
	 *  itself to the new one and waits for its owner. Returns {"ok" or "failed", what to tell the player}. */
	String[] pair(String address) {
		String url = address.trim().replaceAll("/+$", "");
		if (!url.isEmpty()) {
			if (!url.matches("(?i)https?://.*")) url = "http://" + url;
			try {
				URI u = URI.create(url);
				if (u.getHost() == null) return new String[] {"failed", "That doesn't look like an address (for example 100.101.102.103:25570)."};
				if (u.getPort() < 0) url = u.getScheme() + "://" + u.getHost() + ":25570";
			} catch (RuntimeException e) { return new String[] {"failed", "That doesn't look like an address (for example 100.101.102.103:25570)."}; }
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(url + "/v1/mcss").openConnection();
				c.setConnectTimeout(4000); c.setReadTimeout(5000);
				int code = c.getResponseCode();
				String body;
				try (java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) { body = in == null ? ""
						: new String(in.readAllBytes(), StandardCharsets.UTF_8); }
				if (!(body.trim().startsWith("{") && (code == 200 || code == 401 || code == 403))) return new String[] {"failed",
						"Something answers at " + url + ", but it isn't Gatekeeper."};
			} catch (IOException | RuntimeException e) { return new String[] {"failed", "Nothing answers at " + url
					+ ". Is the server machine awake, and is this device in its Tailscale network? (Connection, Network diagnosis)"}; }
		} else if (packHelperUrl.isEmpty() && keeperUrl.isEmpty()) return new String[] {"failed", "Type Gatekeeper's address first."};
		String before = helperUrl;
		keeperUrl = url.equals(packHelperUrl) ? "" : url;
		helperUrl = keeperUrl.isEmpty() ? packHelperUrl : keeperUrl;
		if (!helperUrl.equals(before)) {
			permMcss = false; permSleep = false; permServer = false; permAdmin = false;
			ban = ""; banScope = ""; banReason = ""; whitelisted = "unknown";
			permStates.clear(); reqStates.clear(); reqReasons.clear();
		}
		identity();
		try { save(apiKey, wakeAddress); } catch (IOException e) { return new String[] {"failed", "Couldn't save: " + e.getMessage()}; }
		if (helperUrl.isEmpty()) return new String[] {"ok", "Not paired with a Gatekeeper now."};
		try { hello(); } catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		return new String[] {"ok", (keeperUrl.isEmpty() ? "Back with the pack's Gatekeeper, " : "Paired with Gatekeeper at ")
				+ helperLabel() + ". Its owner decides what this device may do. Press Restart to carry on with it."};
	}
	/** Gatekeeper's address as shown: host and port. */
	String helperLabel() { return helperUrl.replaceFirst("(?i)^https?://", "").replaceAll("/+$", ""); }

	boolean configured() { return !panelUrl.isEmpty() || !mac.isEmpty() || !minecraft.isEmpty(); }
	boolean canWake() { return mac.replaceAll("[^0-9A-Fa-f]", "").length() == 12; }
	boolean hasKey() { return !apiKey.isEmpty() && !serverId.isEmpty() && !panelUrl.isEmpty(); }
	/** Server control through Gatekeeper: the owner allowed it, so no panel sign-in or key is needed here. */
	boolean viaHelper() { return hasHelper() && permServer; }
	/** Start and stop are possible: through Gatekeeper, or with this device's own panel access. */
	boolean canControlServer() { return viaHelper() || (hasKey() && !accessRejected); }
	/** The Server controls and Server config tabs are shown once the owner has allowed this launcher anything (or it
	 *  has its own panel access); always when there's no Gatekeeper to ask. */
	boolean showServerTabs() { return !hasHelper() || permMcss || permSleep || permServer || permAdmin || hasKey(); }
	Path access() { return dir.resolve("server-access.json"); }

	private String player;
	/** The Minecraft account the game last started as on this device, from the game's own log ("Setting user: Name";
	 *  nothing else is read). Told to Gatekeeper as a hint of whose device this is; Gatekeeper only counts it as
	 *  confirmed once the server sees that player join from here. Empty if the game hasn't run yet. */
	String player() {
		if (player == null) {
			player = "";
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("Setting user: (\\w{1,16})").matcher(LaunchPopup.readFile(dir.resolve("logs").resolve("latest.log")));
			while (m.find()) player = m.group(1);
		}
		return player;
	}

	/** What's saved on this device for the server panel: "signin" (the
	 *  key from signing in with a panel account), "key" (an API key typed in), or "none". */
	String saved() { return !hasKey() ? "none" : !username.isEmpty() ? "signin" : "key"; }
	/** How Start and Stop go from this device right now: "helper" when the owner allowed it there (that comes first),
	 *  otherwise with what's saved. Gatekeeper is told both, so the owner can see it in Gatekeeper. */
	String kind() { return viaHelper() ? "helper" : saved(); }
	/** The same, in words for the player. */
	String accessText() {
		String has = switch (saved()) {
			case "signin" -> "your panel sign-in (" + username + ")";
			case "key" -> "an API key saved on this device";
			default -> "";
		};
		if (viaHelper()) return "through Gatekeeper, allowed by the owner" + (has.isEmpty() ? " (nothing saved on this device)" : " ("
				+ has + " is also saved, but not used)");
		if (!has.isEmpty() && accessRejected) return "none right now: the server panel no longer accepts " + has
				+ " (sign in again under Server config, or ask the owner)";
		return has.isEmpty() ? "none (ask the owner, or sign in under Server config)" : "with " + has;
	}

	/** Saves this device's own settings (server-access.json, in the game folder only). */
	void save(String key, String wake) throws IOException {
		if (!key.trim().equals(apiKey)) accessRejected = false;
		apiKey = key.trim(); wakeAddress = wake.trim();
		StringBuilder ids = new StringBuilder();
		for (Map.Entry<String, String[]> e : identities.entrySet())
			ids.append(ids.length() == 0 ? ""
					: ", ").append('"').append(jsonEsc(e.getKey())).append("\": {\"id\": \"").append(jsonEsc(e.getValue()[0])).append("\", \"token\": \"").append(jsonEsc(e.getValue()[1])).append("\"}");
		Files.writeString(access(), "{\"api_key\": \"" + jsonEsc(apiKey) + "\", \"wake_address\": \"" + jsonEsc(wakeAddress)
				+ "\", \"username\": \"" + jsonEsc(username) + "\", \"device_id\": \"" + jsonEsc(str(deviceId))
				+ "\", \"device_token\": \"" + jsonEsc(str(deviceToken)) + "\", \"perm_mcss\": " + permMcss + ", \"perm_sleep\": " + permSleep
				+ ", \"perm_server\": " + permServer + ", \"perm_admin\": " + permAdmin
				+ ", \"ban\": \"" + jsonEsc(ban) + "\", \"ban_scope\": \"" + jsonEsc(banScope) + "\", \"ban_reason\": \"" + jsonEsc(banReason) + "\""
				+ ", \"keeper_url\": \"" + jsonEsc(keeperUrl) + "\", \"identities\": {" + ids + "}}\n", StandardCharsets.UTF_8);
	}

	/** Signs in to the panel with the player's own account, as the panel's web page does (POST /auth), and keeps the
	 *  key it hands back (a session key, used like an API key). The password is sent once and never saved. */
	void signIn(String user, String password, String wake) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(panelUrl.replaceAll("/+$", "") + "/auth").openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(8000);
		c.setRequestMethod("POST");
		c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "application/json");
		try (OutputStream o = c.getOutputStream()) {
			o.write(("{\"username\":\"" + jsonEsc(user) + "\",\"password\":\"" + jsonEsc(password) + "\"}").getBytes(StandardCharsets.UTF_8));
		}
		int code = c.getResponseCode();
		if (code < 200 || code >= 300) throw new HttpError(code);
		String body;
		try (InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
		String key = str(asMap(LaunchPopup.parseJson(body)).get("apiKey"));
		if (key.isEmpty()) throw new IOException("the panel didn't hand back a key");
		username = user.trim();
		save(key, wake);
	}

	// ------------------------------------------------------------------ wake

	/** Sends the wake signal (a "magic packet": 6 x FF, then the network card's address 16 times) to each home
	 *  broadcast address, and to the internet wake address if this device has one. Returns where it went. */
	List<String> wake() throws IOException {
		byte[] m = new byte[6];
		String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
		for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		byte[] packet = new byte[6 + 16 * 6];
		for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
		for (int i = 0; i < 16; i++) System.arraycopy(m, 0, packet, 6 + i * 6, 6);
		List<String> sent = new ArrayList<>();
		IOException last = null;
		try (DatagramSocket s = new DatagramSocket()) {
			s.setBroadcast(true);
			List<String> targets = new ArrayList<>();
			for (String b : broadcasts) { targets.add(b + ":" + wakePort); if (wakePort != 7) targets.add(b + ":7"); }
			if (!wakeAddress.isEmpty()) targets.add(wakeAddress.contains(":") ? wakeAddress : wakeAddress + ":" + wakePort);
			String test = System.getenv("OJ_WAKE_TEST");   // testing: also send to this host:port
			if (test != null && !test.isEmpty()) targets.add(test);
			for (String t : targets) {
				int c = t.lastIndexOf(':');
				try {
					s.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(t.substring(0, c)), Integer.parseInt(t.substring(c + 1))));
					sent.add(t);
				} catch (IOException | RuntimeException e) { last = e instanceof IOException io ? io : new IOException(e.getMessage()); }
			}
		}
		if (sent.isEmpty() && last != null) throw last;
		return sent;
	}

	// ------------------------------------------------------------------ status

	/** True when something accepts connections at host:port within the time given. */
	static boolean answers(String hostPort, int ms) {
		int c = hostPort.lastIndexOf(':');
		if (c < 0) return false;
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(hostPort.substring(0, c), Integer.parseInt(hostPort.substring(c + 1))), ms);
			return true;
		} catch (IOException | RuntimeException e) { return false; }
	}

	String helperHostPort() {
		try { URI u = URI.create(helperUrl); return u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 80); } catch (RuntimeException e) { return ""; }
	}

	String panelHostPort() {
		try { URI u = URI.create(panelUrl); return u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 80); } catch (RuntimeException e) { return ""; }
	}

	/** Minecraft's own server list ping (as the multiplayer screen does): {ping ms, players online, player limit,
	 *  version}, or null when the game server doesn't answer. */
	static String[] serverListPing(String hostPort, int ms) {
		int c = hostPort.lastIndexOf(':');
		if (c < 0) return null;
		String host = hostPort.substring(0, c);
		int port;
		try { port = Integer.parseInt(hostPort.substring(c + 1)); } catch (NumberFormatException e) { return null; }
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), ms);
			s.setSoTimeout(ms);
			java.io.DataOutputStream out = new java.io.DataOutputStream(s.getOutputStream());
			java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
			java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream h = new java.io.DataOutputStream(hs);
			h.writeByte(0x00); varInt(h, -1); byte[] hb = host.getBytes(StandardCharsets.UTF_8); varInt(h, hb.length); h.write(hb); h.writeShort(port); varInt(h, 1);
			varInt(out, hs.size()); out.write(hs.toByteArray());
			varInt(out, 1); out.writeByte(0x00);   // status request
			readVarInt(in); readVarInt(in);
			byte[] js = new byte[readVarInt(in)]; in.readFully(js);
			Map<String, Object> st = asMap(LaunchPopup.parseJson(new String(js, StandardCharsets.UTF_8)));
			Map<String, Object> pl = asMap(st.get("players"));
			long sent = System.nanoTime();
			varInt(out, 9); out.writeByte(0x01); out.writeLong(sent);   // ping
			readVarInt(in); readVarInt(in); in.readLong();
			long rtt = (System.nanoTime() - sent) / 1_000_000;
			return new String[] {String.valueOf(rtt), str(pl.get("online")), str(pl.get("max")), str(asMap(st.get("version")).get("name"))};
		} catch (IOException | RuntimeException e) { return null; }
	}

	private static void varInt(java.io.DataOutputStream o, int v) throws IOException {
		while (true) { if ((v & ~0x7F) == 0) { o.writeByte(v); return; } o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
	}

	private static int readVarInt(java.io.DataInputStream in) throws IOException {
		int v = 0, n = 0; byte b;
		do { b = in.readByte(); v |= (b & 0x7F) << (7 * n++); if (n > 5) throw new IOException("bad varint"); } while ((b & 0x80) != 0);
		return v;
	}

	/** Where things stand: {colour key, text, phase, ping ms, players}. Colour keys as in the pop-up (ok, warn, off,
	 *  failed); phase: online, starting, stopping, off (MCSS is running, the game server isn't), nomcss (the PC is awake
	 *  (its Gatekeeper answers) but MCSS isn't running), asleep (nothing on the PC answers), error.
	 *  Ping and players come from the game server itself (empty when it isn't answering). */
	String[] status() {
		String[] slp = minecraft.isEmpty() ? null : serverListPing(minecraft, 2500);
		boolean game = slp != null;
		boolean panel = game || (!panelUrl.isEmpty() && answers(panelHostPort(), 2000));
		// MCSS (and its panel) can be closed while the PC is on: Gatekeeper, which runs whenever the owner is
		// signed in, then still answers.
		boolean pc = panel || (hasHelper() && answers(helperHostPort(), 2000));
		String ping = game ? slp[0] : "", players = game ? slp[1] + " of " + slp[2] : "";
		// Gatekeeper knows what this one server is doing (MCSS tells it), including while it's starting or shutting
		// down. The game port alone can't say: two servers on one machine may share it.
		if (hasHelper() && pc && !ban.equals("blacklist")) try {
			Map<String, Object> ph = helper("GET", "/v1/phase", null);
			if ("200".equals(str(ph.get("_code")))) {
				mcssPhase = str(ph.get("mcss_phase"));
				blocked = str(ph.get("blocked"));
				switch (str(ph.get("phase"))) {
					case "online": return new String[] {"ok", "Online", "online", ping, players};
					case "starting": return new String[] {"warn", "Starting\u2026", "starting", "", ""};
					case "stopping": return new String[] {"warn", "Shutting down\u2026", "stopping", "", ""};
					case "off": return new String[] {"off", "The server machine is awake, but the server is off"
							+ (canControlServer() ? ": press Start server" : ""), "off", "", ""};
					case "nomcss": return new String[] {"off", mcssPhase.equals("starting") ? "MCSS is starting\u2026"
							: "The server machine is awake, but MCSS isn't running" + (viaHelper() ? ": Start server starts it too" : ""), "nomcss", "", ""};
					default: break;
				}
			}
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		if (viaHelper() && pc) {
			String[] r = phase(statusCode(), ping, players);
			if (r != null) return r;
		} else if (hasKey() && panel) {
			try {
				Map<String, Object> srv = asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId, null)));
				accessRejected = false;
				int st = -1;
				try { st = (int) Double.parseDouble(str(srv.get("status"))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
				switch (st) {
					case 1: return new String[] {"ok", "Online", "online", ping, players};
					case 3: return new String[] {"warn", "Starting\u2026", "starting", ping, players};
					case 2: return new String[] {"warn", "Restarting\u2026", "starting", ping, players};
					case 4: return new String[] {"warn", "Stopping\u2026", "stopping", ping, players};
					case 0: return new String[] {"off", "The server machine is awake, but the server is off: press Start server", "off", "", ""};
					default: break;
				}
			} catch (HttpError e) {
				// The panel turned this device's saved sign-in or key down. That's about this device's access, not the
				// server: what the server is doing is still told from the game port below, and the access line says so.
				if (e.code == 401 || e.code == 403) accessRejected = true;
			} catch (IOException ignored) { Quiet.seen(ignored); }
		}
		if (game) return new String[] {"ok", "Online", "online", ping, players};
		if (panel) return new String[] {"off", "The server machine is awake, but the server isn't answering"
				+ (canControlServer() ? "" : " (panel access in Server config lets you start it)"), "off", "", ""};
		if (pc) return new String[] {"off", "The server machine is awake, but MCSS isn't running: start it with Start MCSS below, then the server", "nomcss", "", ""};
		return new String[] {"off", "Not answering: the server machine is asleep or off" + (canWake()
				? " (Wake server machine works from the home network)" : ""), "asleep", "", ""};
	}

	private static String[] phase(int st, String ping, String players) {
		switch (st) {
			case 1: return new String[] {"ok", "Online", "online", ping, players};
			case 3: return new String[] {"warn", "Starting\u2026", "starting", ping, players};
			case 2: return new String[] {"warn", "Restarting\u2026", "starting", ping, players};
			case 4: return new String[] {"warn", "Stopping\u2026", "stopping", ping, players};
			case 0: return new String[] {"off", "The server machine is awake, but the server is off: press Start server", "off", "", ""};
			default: return null;
		}
	}

	/** Start or stop: through Gatekeeper when the owner allowed this launcher server control (Gatekeeper uses its
	 *  own MCSS key), otherwise with this device's own panel access. */
	private void serverAction(boolean start) throws IOException {
		if (viaHelper()) {
			Map<String, Object> r = helper("POST", "/v1/server/" + (start ? "start" : "stop"), "{}");
			if (!"ok".equals(str(r.get("result")))) throw new Refused(str(r.get("message")).isEmpty() ? str(r.get("error")) : str(r.get("message")));
			return;
		}
		api("POST", "/api/v2/servers/" + serverId + "/execute/action", "{\"action\":" + (start ? 2 : 1) + "}");
	}

	/** Asks MCSS to start the server. */
	void start() throws IOException { serverAction(true); }

	/** The server's state as MCSS reports it (0 off, 1 on, 2 restarting, 3 starting, 4 stopping), or -1 if unknown. */
	int statusCode() {
		if (viaHelper()) {
			try { return (int) Double.parseDouble(str(helper("GET", "/v1/server", null).get("status"))); }
			catch (IOException | RuntimeException e) { return -1; }
		}
		try { return (int) Double.parseDouble(str(asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId, null))).get("status"))); }
		catch (IOException | RuntimeException e) { return -1; }
	}

	/** The last lines of the server's console log, oldest first. */
	List<String> console(int lines) throws IOException {
		if (viaHelper()) {
			List<String> out = new ArrayList<>();
			if (helper("GET", "/v1/server/log", null).get("lines") instanceof List<?> l) for (Object x : l) out.add(str(x));
			else throw new IOException("Gatekeeper sent no log");
			return out;
		}
		Object o = LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId + "/console?amountOfLines=" + lines + "&reversed=false", null));
		List<String> out = new ArrayList<>();
		if (o instanceof List<?> l) for (Object x : l) out.add(x instanceof Map<?, ?> m ? str(m.get("line") != null ? m.get("line") : m.get("text")) : str(x));
		else throw new IOException("unexpected console reply");
		return out;
	}

	// ------------------------------------------------------------------ MCSS itself, through Gatekeeper

	boolean hasHelper() { return !helperUrl.isEmpty(); }

	/** One call to Gatekeeper on the server machine, as this launcher. */
	Map<String, Object> helper(String method, String path, String body) throws IOException { return helperAs(serverKey, method, path, body); }
	/** The same, about a named server of that Gatekeeper (the launcher's menu, which isn't tied to one server). */
	Map<String, Object> helperAs(String serverKey, String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(helperUrl.replaceAll("/+$", "") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(60000);
		c.setRequestMethod(method);
		c.setRequestProperty("X-Device", deviceId);
		c.setRequestProperty("X-Token", deviceToken);
		if (!serverKey.isEmpty()) c.setRequestProperty("X-Server", serverKey);   // (which of the machine's servers this pack is for)
		c.setRequestProperty("X-Access", kind());
		if (!OWN_VERSION.isEmpty()) c.setRequestProperty("X-Gatehouse",
				OWN_VERSION);   // (which Gatehouse this is: a Gatekeeper holds launchers to the published one)
		c.setRequestProperty("X-Saved", saved());
		if (saved().equals("signin")) c.setRequestProperty("X-Access-User", username.replaceAll("[^\\x20-\\x7E]", "?"));
		if (!player().isEmpty()) c.setRequestProperty("X-Player", player());
		if (body != null) {
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
		}
		int code = c.getResponseCode();
		InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
		Map<String, Object> r = asMap(LaunchPopup.parseJson(in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		r.put("_code", String.valueOf(code));
		return r;
	}

	/** This device's operating system, as Gatekeeper lists it: "Windows 10", "macOS", or for Linux the system's own
	 *  name with "(linux)" after it, like "SteamOS (linux)". (Inside a Flatpak the system's own name is in
	 *  /run/host/os-release; /etc/os-release there is the Flatpak runtime's.) */
	static String systemName() {
		String os = System.getProperty("os.name", "");
		if (os.startsWith("Windows")) return os;
		if (os.startsWith("Mac")) return "macOS";
		for (String f : new String[] {"/run/host/os-release", "/etc/os-release"}) {
			try {
				for (String l : Files.readAllLines(Path.of(f), StandardCharsets.UTF_8)) if (l.startsWith("NAME=")) {
					String n = l.substring(5).trim().replaceAll("^\"|\"$", "");
					if (!n.isEmpty()) return n.toLowerCase(java.util.Locale.ROOT).contains("linux") ? n : n + " (linux)";
				}
			} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		}
		return os.isEmpty() ? "Unknown" : os.equalsIgnoreCase("Linux") ? "Linux" : os + " (linux)";
	}

	/** Introduces this launcher (it then shows in the owner's Gatekeeper, waiting until allowed). Returns its state. */
	String hello() throws IOException {
		String name = "", os = systemName();
		try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ignored) { Quiet.seen(ignored); }
		return str(helper("POST", "/v1/hello", "{\"id\":\"" + jsonEsc(deviceId) + "\",\"token\":\"" + jsonEsc(deviceToken)
				+ "\",\"name\":\"" + jsonEsc(name) + "\",\"os\":\"" + jsonEsc(os) + "\"}").get("state"));
	}

	/** {state (granted, pending, unknown), mcss running, minecraft running}; introduces itself again if forgotten. */
	Map<String, Object> mcssState() throws IOException {
		Map<String, Object> r = helper("GET", "/v1/mcss", null);
		if (str(r.get("_code")).equals("401")) { hello(); r = helper("GET", "/v1/mcss", null); }
		if (str(r.get("_code")).equals("200")) {
			boolean m = "granted".equals(str(r.get("state"))), sl = "true".equals(str(r.get("sleep"))),
					sv = "true".equals(str(r.get("server"))), ad = "true".equals(str(r.get("admin")));
			if (m != permMcss || sl != permSleep || sv != permServer || ad != permAdmin) {
				permMcss = m; permSleep = sl; permServer = sv; permAdmin = ad;
				try { save(apiKey, wakeAddress); } catch (IOException ignored) { Quiet.seen(ignored); }
			}
			requestStatus = str(r.get("request"));
			// Shut out or not, the whitelist, each permission's state, and what became of this launcher's requests.
			String nb = str(r.get("ban")), ns = str(r.get("ban_scope")), nr = str(r.get("ban_reason"));
			boolean banChanged = !nb.equals(ban) || !ns.equals(banScope) || !nr.equals(banReason);
			ban = nb; banScope = ns; banReason = nr;
			if (banChanged) try { save(apiKey, wakeAddress); } catch (IOException ignored) { Quiet.seen(ignored); }
			whitelisted = r.containsKey("whitelisted") ? str(r.get("whitelisted")) : "unknown";
			for (Object[] pair : new Object[][] {{permStates, r.get("perm")}, {reqStates, r.get("requests")}, {reqReasons, r.get("request_reasons")}}) {
				@SuppressWarnings("unchecked") Map<String, String> into = (Map<String, String>) pair[0];
				into.clear();
				for (Map.Entry<String, Object> e : asMap(pair[1]).entrySet()) into.put(e.getKey(), str(e.getValue()));
			}
			// From the owner, through Gatekeeper: the order to forget whatever sign-in or key is saved here.
			if ("true".equals(str(r.get("clear_access"))) && !apiKey.isEmpty()) {
				try { username = ""; save("", wakeAddress); } catch (IOException ignored) { Quiet.seen(ignored); }
			}
		}
		return r;
	}

	/** Asks Gatekeeper to tell the owner this device wants the server turned on (a chime and Gatekeeper on the
	 *  server machine). Any launcher Gatekeeper knows may ask; no permission is needed. */
	Map<String, Object> requestServer() throws IOException { return request("server", ""); }

	/** Asks Gatekeeper to put the server machine to sleep (needs the owner's separate "PC sleep" permission). */
	Map<String, Object> sleepPc() throws IOException { return helper("POST", "/v1/pc/sleep", "{}"); }

	Map<String, Object> mcssStart() throws IOException { return helper("POST", "/v1/mcss/start", "{}"); }
	/** force: the button said "Force close MCSS" (MCSS is set to hide when closed, so closing it means ending it). */
	Map<String, Object> mcssClose(boolean force) throws IOException { return helper("POST", "/v1/mcss/close", force ? "{\"force\":true}" : "{}"); }

	/** Asks MCSS to stop the server (action 1: a normal stop, which saves the world; not 3, kill). */
	void stop() throws IOException { serverAction(false); }

	static final class HttpError extends IOException {
		final int code;
		HttpError(int code) { super("HTTP " + code); this.code = code; }
	}

	/** One call to the MCSS API with this device's key (sent only to the panel's own address). */
	String api(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(panelUrl.replaceAll("/+$", "") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(5000);
		c.setRequestMethod(method);
		c.setRequestProperty("apiKey", apiKey);
		c.setRequestProperty("Accept", "application/json");
		if (body != null) {
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
		}
		int code = c.getResponseCode();
		if (code < 200 || code >= 300) throw new HttpError(code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	// ------------------------------------------------------------------ the panel in a browser

	/** Opens the panel in the default browser. Returns false if nothing could open it (the address is then shown). */
	boolean openPanel() {
		try {
			if (dev.gatehouse.look.Links.open(panelUrl)) {

				return true;
			}
		} catch (RuntimeException ignored) { Quiet.seen(ignored); }
		for (String[] cmd : new String[][] {{"xdg-open", panelUrl}, {"flatpak-spawn", "--host", "xdg-open", panelUrl}, {"rundll32",
				"url.dll,FileProtocolHandler", panelUrl}}) {
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() == 0) return true;
			} catch (IOException | RuntimeException | InterruptedException ignored) { Quiet.seen(ignored); }
		}
		return false;
	}

	// ------------------------------------------------------------------ small helpers

	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : new java.util.LinkedHashMap<>(); }
	static String str(Object o) { return o == null ? "" : o.toString(); }
	static String jsonEsc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
