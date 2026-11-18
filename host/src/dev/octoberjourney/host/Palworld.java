package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Palworld (R34 stage 3; docs.palworldgame.com): its dedicated server described like any file-defined game (SteamCMD
 * app 2394010, PalServer.exe / PalServer.sh, -port, a password), plus what only Palworld has:
 *   - its settings: one line, OptionSettings=(Key=Value,...), in Pal/Saved/Config/WindowsServer (or LinuxServer)/
 *     PalWorldSettings.ini, made from DefaultPalWorldSettings.ini;
 *   - its REST API, which Gatekeeper turns on for itself (RESTAPIEnabled, on the game port + 1, with an admin password
 *     of its own making): who is on (their Steam IDs tie them to their launchers), saving the world, and a clean
 *     shutdown. Palworld says the API isn't for the internet: Gatekeeper only ever calls it on this machine, and its
 *     password is long and random.
 */
final class Palworld extends GameFile {
	static final String ID = "palworld";
	Palworld() { super(definition()); }
	private static Map<String, Object> definition() {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("id", ID); d.put("name", "Palworld");
		d.put("steam_server_app", 2394010); d.put("steam_game_app", 1623730);
		d.put("server_files", List.of("PalServer.exe", "PalServer.sh"));
		d.put("start", m("windows", List.of("PalServer.exe", "-port={port}", "-players=16"), "linux", List.of("PalServer.sh", "-port={port}", "-players=16")));
		d.put("port", m("default", "8211"));
		d.put("allow", m("kind", "password", "word", "password"));
		d.put("world", "Pal/Saved/SaveGames");
		d.put("join", "steam://run/{app}");   // (Palworld joins by address from its own menu: Gatehouse copies the address)
		d.put("stop_command", "");
		return d;
	}

	// ------------------------------------------------------------------ PalWorldSettings.ini

	static Path settingsFile(Path d) { return d.resolve("Pal/Saved/Config").resolve(WIN ? "WindowsServer" : "LinuxServer").resolve("PalWorldSettings.ini"); }
	/** OptionSettings=(...) as an ordered map (values as written: "quoted" strings keep their quotes). */
	static Map<String, String> options(String ini) {
		Map<String, String> out = new LinkedHashMap<>();
		int at = ini.indexOf("OptionSettings=(");
		if (at < 0) return out;
		int i = at + "OptionSettings=(".length(), depth = 0, start = i;
		boolean quoted = false;
		for (; i < ini.length(); i++) {
			char c = ini.charAt(i);
			if (c == '"') quoted = !quoted;
			else if (!quoted && c == '(') depth++;
			else if (!quoted && c == ')') { if (depth == 0) { put(out, ini.substring(start, i)); break; } depth--; }
			else if (!quoted && c == ',' && depth == 0) { put(out, ini.substring(start, i)); start = i + 1; }
		}
		return out;
	}
	private static void put(Map<String, String> out, String pair) { int eq = pair.indexOf('='); if (eq > 0) out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim()); }
	static String write(Map<String, String> o) {
		StringBuilder b = new StringBuilder("[/Script/Pal.PalGameWorldSettings]\r\nOptionSettings=(");
		boolean first = true;
		for (Map.Entry<String, String> e : o.entrySet()) { if (!first) b.append(','); b.append(e.getKey()).append('=').append(e.getValue()); first = false; }
		return b.append(")\r\n").toString();
	}
	static String quote(String s) { return "\"" + s.replace("\"", "").replace("\\", "") + "\""; }
	static String unquote(String s) { return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s; }

	@Override public Map<String, String> settings(Path d) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : options(read(settingsFile(d))).entrySet()) if (!e.getKey().equals("AdminPassword")) out.put(e.getKey(), unquote(e.getValue()));
		return out;
	}
	@Override public void setSettings(Path d, Map<String, String> set) throws IOException {
		Path f = settingsFile(d);
		Map<String, String> o = options(read(f));
		if (o.isEmpty()) throw new IOException("its settings file isn't there yet (" + f + ")");
		for (Map.Entry<String, String> e : set.entrySet()) {
			if (e.getKey().equals("AdminPassword") || e.getKey().startsWith("RESTAPI")) continue;   // (Gatekeeper's own)
			String was = o.get(e.getKey());
			o.put(e.getKey(), was != null && was.startsWith("\"") ? quote(e.getValue()) : e.getValue());
		}
		Files.writeString(f, write(o), StandardCharsets.UTF_8);
	}

	/** After SteamCMD has installed it: its settings made from Palworld's defaults, with the server's name and password,
	 *  and the REST API on for Gatekeeper (port + 1, a random admin password). */
	@Override public void prepare(Path d, Map<String, Object> s) throws IOException {
		Map<String, String> o = options(read(d.resolve("DefaultPalWorldSettings.ini")));
		if (o.isEmpty()) throw new IOException("Palworld's DefaultPalWorldSettings.ini wasn't in what SteamCMD installed");
		String port = Json.str(s.get("minecraft")).replaceAll(".*:", "");
		o.put("ServerName", quote(Json.str(s.get("name"))));
		o.put("ServerPassword", quote(Json.str(Json.map(s.get("settings")).get("password"))));
		o.put("AdminPassword", quote(WebPanel.random(24)));
		o.put("PublicPort", port);
		o.put("RESTAPIEnabled", "True");
		o.put("RESTAPIPort", String.valueOf(Integer.parseInt(port) + 1));
		o.put("RCONEnabled", "False");
		Path f = settingsFile(d);
		Files.createDirectories(f.getParent());
		Files.writeString(f, write(o), StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ its REST API (this machine only)

	/** Calls the server's REST API: {code, body}; code 0 when it doesn't answer. */
	static String[] api(Path d, String method, String path, String body) {
		Map<String, String> o = options(read(settingsFile(d)));
		if (!"True".equalsIgnoreCase(o.getOrDefault("RESTAPIEnabled", ""))) return new String[] {"0", ""};
		try {
			HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + o.getOrDefault("RESTAPIPort", "8212") + "/v1/api/" + path).openConnection();
			c.setRequestMethod(method); c.setConnectTimeout(1500); c.setReadTimeout(5000);
			c.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(("admin:" + unquote(o.getOrDefault("AdminPassword", ""))).getBytes(StandardCharsets.UTF_8)));
			if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); }
			int code = c.getResponseCode();
			String text;
			try (java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) { text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
			return new String[] {String.valueOf(code), text};
		} catch (IOException | RuntimeException e) { return new String[] {"0", ""}; }
	}
	/** Up: its REST API answers. */
	@Override public boolean answers(Path d) { return api(d, "GET", "info", null)[0].equals("200"); }
	@Override public boolean saveNow(Path d) { return api(d, "POST", "save", "{}")[0].equals("200"); }
	/** Saves, then shuts down in five seconds, saying so to anyone on. */
	@Override public boolean stopItself(Path d) {
		api(d, "POST", "save", "{}");
		return api(d, "POST", "shutdown", "{\"waittime\":5,\"message\":\"The server is stopping in 5 seconds.\"}")[0].equals("200");
	}
	/** {name, Steam ID or ""} of everyone on, or null when it doesn't answer. */
	@Override public List<String[]> playersNow(Path d) {
		String[] r = api(d, "GET", "players", null);
		if (!r[0].equals("200")) return null;
		List<String[]> out = new ArrayList<>();
		for (Object o : Json.list(Json.map(Json.parse(r[1])).get("players"))) {
			Map<String, Object> p = Json.map(o);
			String id = Json.str(p.get("userId")).replaceFirst("^steam_", "");
			out.add(new String[] {Json.str(p.get("name")), id.matches("\\d{17}") ? id : ""});
		}
		return out;
	}
	@Override public String[] facts(Path d) { return new String[] {"vanilla", ""}; }
}
