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
	final String panelUrl, serverId, minecraft, mac;
	final List<String> broadcasts = new ArrayList<>();
	final int wakePort;
	String apiKey, wakeAddress;

	ServerControl(Path dir) {
		this.dir = dir;
		Map<String, Object> c = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(dir.resolve("extras/server-control.json"))));
		Map<String, Object> w = asMap(c.get("wake"));
		panelUrl = str(c.get("panel_url"));
		serverId = str(c.get("mcss_server"));
		minecraft = str(c.get("minecraft"));
		mac = str(w.get("mac"));
		if (w.get("broadcasts") instanceof List<?> l) for (Object o : l) broadcasts.add(str(o));
		int port = 9;
		try { port = Integer.parseInt(str(w.get("port"))); } catch (NumberFormatException ignored) {}
		wakePort = port;
		Map<String, Object> a = asMap(LaunchPopup.parseJson(LaunchPopup.readFile(access())));
		apiKey = str(a.get("api_key"));
		wakeAddress = str(a.get("wake_address"));
	}

	boolean configured() { return !panelUrl.isEmpty() || !mac.isEmpty() || !minecraft.isEmpty(); }
	boolean canWake() { return mac.replaceAll("[^0-9A-Fa-f]", "").length() == 12; }
	boolean hasKey() { return !apiKey.isEmpty() && !serverId.isEmpty() && !panelUrl.isEmpty(); }
	Path access() { return dir.resolve("server-access.json"); }

	/** Saves this device's own settings (server-access.json, in the game folder only). */
	void save(String key, String wake) throws IOException {
		apiKey = key.trim(); wakeAddress = wake.trim();
		Files.writeString(access(), "{\"api_key\": \"" + jsonEsc(apiKey) + "\", \"wake_address\": \"" + jsonEsc(wakeAddress) + "\"}\n", StandardCharsets.UTF_8);
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

	/** Where things stand: {colour key, text}. Colour keys as in the pop-up: ok, warn, off, failed. */
	String[] status() {
		boolean game = !minecraft.isEmpty() && answers(minecraft, 2000);
		boolean pc = game || (!panelUrl.isEmpty() && answers(panelHostPort(), 2000));
		if (hasKey() && pc) {
			try {
				Map<String, Object> srv = asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId, null)));
				int st = -1;
				try { st = (int) Double.parseDouble(str(srv.get("status"))); } catch (NumberFormatException ignored) {}
				String players = "";
				try {
					Map<String, Object> latest = asMap(asMap(LaunchPopup.parseJson(api("GET", "/api/v2/servers/" + serverId + "/stats", null))).get("latest"));
					if (latest.get("playersOnline") != null) players = ", " + str(latest.get("playersOnline")) + " of " + str(latest.get("playerLimit")) + " players";
				} catch (IOException ignored) {}
				switch (st) {
					case 1: return new String[] {"ok", "Online" + players};
					case 3: return new String[] {"warn", "Starting…"};
					case 2: return new String[] {"warn", "Restarting…"};
					case 4: return new String[] {"warn", "Stopping…"};
					case 0: return new String[] {"off", "The server PC is awake, but the Minecraft server is off: press Start server"};
					default: break;
				}
			} catch (HttpError e) {
				if (e.code == 401 || e.code == 403) return new String[] {"failed", game ? "Online. (Your API key wasn't accepted: check it in Server access.)" : "Your API key wasn't accepted: check it in Server access"};
			} catch (IOException ignored) { }
		}
		if (game) return new String[] {"ok", "Online"};
		if (pc) return new String[] {"off", "The server PC is awake, but the Minecraft server isn't answering" + (hasKey() ? "" : " (an API key in Server access lets you start it)")};
		return new String[] {"off", "Not answering: the server PC is asleep or off" + (canWake() ? " (Wake server device works from the home network)" : "")};
	}

	/** Asks MCSS to start the server. */
	void start() throws IOException { api("POST", "/api/v2/servers/" + serverId + "/execute/action", "{\"action\":2}"); }

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
