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
	final Path dir;
	final String panelUrl, serverId, minecraft, mac, helperUrl;
	final List<String> broadcasts = new ArrayList<>();
	final int wakePort;
	String apiKey, wakeAddress, username;
	/** This launcher's identity for the host helper: an id and a secret it made itself (kept in server-access.json). */
	String deviceId, deviceToken;

	ServerControl(Path dir) {
		this.dir = dir;
		Map<String, Object> c = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(dir.resolve("extras/server-control.json"))));
		Map<String, Object> w = asMap(c.get("wake"));
		panelUrl = str(c.get("panel_url"));
		serverId = str(c.get("mcss_server"));
		minecraft = str(c.get("minecraft"));
		helperUrl = str(c.get("helper_url"));
		mac = str(w.get("mac"));
		if (w.get("broadcasts") instanceof List<?> l) for (Object o : l) broadcasts.add(str(o));
		int port = 9;
		try { port = Integer.parseInt(str(w.get("port"))); } catch (NumberFormatException ignored) {}
		wakePort = port;
		Map<String, Object> a = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(access())));
		apiKey = str(a.get("api_key"));
		wakeAddress = str(a.get("wake_address"));
		username = str(a.get("username"));
		deviceId = str(a.get("device_id"));
		deviceToken = str(a.get("device_token"));
		if (!helperUrl.isEmpty() && (deviceId.isEmpty() || deviceToken.length() < 32)) {
			java.security.SecureRandom r = new java.security.SecureRandom();
			deviceId = java.util.UUID.randomUUID().toString();
			byte[] t = new byte[32]; r.nextBytes(t);
			StringBuilder b = new StringBuilder(); for (byte x : t) b.append(String.format("%02x", x));
			deviceToken = b.toString();
			try { save(apiKey, wakeAddress); } catch (IOException ignored) {}
		}
	}

	boolean configured() { return !panelUrl.isEmpty() || !mac.isEmpty() || !minecraft.isEmpty(); }
	boolean canWake() { return mac.replaceAll("[^0-9A-Fa-f]", "").length() == 12; }
	boolean hasKey() { return !apiKey.isEmpty() && !serverId.isEmpty() && !panelUrl.isEmpty(); }
	Path access() { return dir.resolve("server-access.json"); }

	/** Saves this device's own settings (server-access.json, in the game folder only). */
	void save(String key, String wake) throws IOException {
		apiKey = key.trim(); wakeAddress = wake.trim();
		Files.writeString(access(), "{\"api_key\": \"" + jsonEsc(apiKey) + "\", \"wake_address\": \"" + jsonEsc(wakeAddress)
				+ "\", \"username\": \"" + jsonEsc(username) + "\", \"device_id\": \"" + jsonEsc(str(deviceId))
				+ "\", \"device_token\": \"" + jsonEsc(str(deviceToken)) + "\"}\n", StandardCharsets.UTF_8);
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
	 *  failed); phase: online, starting, stopping, off (the PC is awake, the game server isn't running), asleep, error.
	 *  Ping and players come from the game server itself (empty when it isn't answering). */
	String[] status() {
		String[] slp = minecraft.isEmpty() ? null : serverListPing(minecraft, 2500);
		boolean game = slp != null;
		boolean pc = game || (!panelUrl.isEmpty() && answers(panelHostPort(), 2000));
		String ping = game ? slp[0] : "", players = game ? slp[1] + " of " + slp[2] : "";
		if (hasKey() && pc) {
			try {
				Map<String, Object> srv = asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId, null)));
				int st = -1;
				try { st = (int) Double.parseDouble(str(srv.get("status"))); } catch (NumberFormatException ignored) {}
				switch (st) {
					case 1: return new String[] {"ok", "Online", "online", ping, players};
					case 3: return new String[] {"warn", "Starting\u2026", "starting", ping, players};
					case 2: return new String[] {"warn", "Restarting\u2026", "starting", ping, players};
					case 4: return new String[] {"warn", "Stopping\u2026", "stopping", ping, players};
					case 0: return new String[] {"off", "The server PC is awake, but the Minecraft server is off: press Start server", "off", "", ""};
					default: break;
				}
			} catch (HttpError e) {
				if (e.code == 401 || e.code == 403) return new String[] {"failed", game ? "Online. (Your panel access wasn't accepted: check it in Server config.)" : "Your panel access wasn't accepted: check it in Server config", game ? "online" : "error", ping, players};
			} catch (IOException ignored) { }
		}
		if (game) return new String[] {"ok", "Online", "online", ping, players};
		if (pc) return new String[] {"off", "The server PC is awake, but the Minecraft server isn't answering" + (hasKey() ? "" : " (panel access in Server config lets you start it)"), "off", "", ""};
		return new String[] {"off", "Not answering: the server PC is asleep or off" + (canWake() ? " (Wake server device works from the home network)" : ""), "asleep", "", ""};
	}

	/** Asks MCSS to start the server. */
	void start() throws IOException { api("POST", "/api/v2/servers/" + serverId + "/execute/action", "{\"action\":2}"); }

	/** The server's state as MCSS reports it (0 off, 1 on, 2 restarting, 3 starting, 4 stopping), or -1 if unknown. */
	int statusCode() {
		try { return (int) Double.parseDouble(str(asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId, null))).get("status"))); }
		catch (IOException | RuntimeException e) { return -1; }
	}

	/** The last lines of the server's console log, oldest first. */
	List<String> console(int lines) throws IOException {
		Object o = LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId + "/console?amountOfLines=" + lines + "&reversed=false", null));
		List<String> out = new ArrayList<>();
		if (o instanceof List<?> l) for (Object x : l) out.add(x instanceof Map<?, ?> m ? str(m.get("line") != null ? m.get("line") : m.get("text")) : str(x));
		else throw new IOException("unexpected console reply");
		return out;
	}

	// ------------------------------------------------------------------ MCSS itself, through the host helper

	boolean hasHelper() { return !helperUrl.isEmpty(); }

	/** One call to the host helper on the server PC, as this launcher. */
	Map<String, Object> helper(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(helperUrl.replaceAll("/+$", "") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(60000);
		c.setRequestMethod(method);
		c.setRequestProperty("X-Device", deviceId);
		c.setRequestProperty("X-Token", deviceToken);
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

	/** Introduces this launcher (it then shows in the owner's host panel, waiting until allowed). Returns its state. */
	String hello() throws IOException {
		String name = "", os = System.getProperty("os.name", "");
		try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ignored) {}
		if (new java.io.File("/sys/devices/virtual/dmi/id/product_name").exists()) {
			try { String p = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim(); if (p.equals("Jupiter") || p.equals("Galileo")) os = "Steam Deck"; } catch (IOException ignored) {}
		}
		return str(helper("POST", "/v1/hello", "{\"id\":\"" + jsonEsc(deviceId) + "\",\"token\":\"" + jsonEsc(deviceToken)
				+ "\",\"name\":\"" + jsonEsc(name) + "\",\"os\":\"" + jsonEsc(os) + "\"}").get("state"));
	}

	/** {state (granted, pending, unknown), mcss running, minecraft running}; introduces itself again if forgotten. */
	Map<String, Object> mcssState() throws IOException {
		Map<String, Object> r = helper("GET", "/v1/mcss", null);
		if (str(r.get("_code")).equals("401")) { hello(); r = helper("GET", "/v1/mcss", null); }
		return r;
	}

	Map<String, Object> mcssStart() throws IOException { return helper("POST", "/v1/mcss/start", "{}"); }
	/** force: the button said "Force close MCSS" (MCSS is set to hide when closed, so closing it means ending it). */
	Map<String, Object> mcssClose(boolean force) throws IOException { return helper("POST", "/v1/mcss/close", force ? "{\"force\":true}" : "{}"); }

	/** Asks MCSS to stop the server (action 1: a normal stop, which saves the world; not 3, kill). */
	void stop() throws IOException { api("POST", "/api/v2/servers/" + serverId + "/execute/action", "{\"action\":1}"); }

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
			if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
				java.awt.Desktop.getDesktop().browse(URI.create(panelUrl));
				return true;
			}
		} catch (IOException | RuntimeException ignored) {}
		for (String[] cmd : new String[][] {{"xdg-open", panelUrl}, {"flatpak-spawn", "--host", "xdg-open", panelUrl}, {"rundll32", "url.dll,FileProtocolHandler", panelUrl}}) {
			try {
				Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
				if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() == 0) return true;
			} catch (IOException | RuntimeException | InterruptedException ignored) {}
		}
		return false;
	}

	// ------------------------------------------------------------------ small helpers

	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : new java.util.LinkedHashMap<>(); }
	static String str(Object o) { return o == null ? "" : o.toString(); }
	static String jsonEsc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
