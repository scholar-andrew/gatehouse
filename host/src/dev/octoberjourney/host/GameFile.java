package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A game described by a file rather than by code (R34 stage 2; docs/game-definitions.md): the shared adapter most
 * dedicated-server games fit. A definition says how its server's folder is recognised, how it is started and stopped,
 * the lines that say it is up and who joined or left, its settings, who may join (a file of Steam IDs, a file of
 * names, a password, or nothing), its world folder, its Steam app ids (for SteamCMD and for players' Play), and the
 * port Steam's server query answers on. Definitions are read from Gatekeeper's own folder, games/<id>.json.
 */
class GameFile implements Game {
	final Map<String, Object> def;
	private final Pattern ready, joined, left, stopping, lag;

	GameFile(Map<String, Object> def) {
		this.def = def;
		ready = re("ready"); joined = re("joined"); left = re("left"); stopping = re("stopping"); lag = re("lag");
	}
	private Pattern re(String k) { String s = Json.str(def.get(k)); return s.isEmpty() ? null : Pattern.compile(s); }
	private Map<String, Object> sub(String k) { return Json.map(def.get(k)); }
	private int num(Object o) { try { return (int) Double.parseDouble(Json.str(o)); } catch (RuntimeException e) { return 0; } }

	/** A definition from a file, or null when it isn't one (an id, a name and a way to start are needed). */
	static GameFile load(Path f) {
		try {
			Map<String, Object> d = Json.map(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
			String id = Json.str(d.get("id"));
			if (!id.matches("[a-z0-9][a-z0-9_\\-]{0,30}") || id.equals("minecraft") || Json.str(d.get("name")).isEmpty() || Json.map(d.get("start")).isEmpty()) return null;
			return new GameFile(d);
		} catch (IOException | RuntimeException e) { return null; }
	}

	@Override public String id() { return Json.str(def.get("id")); }
	@Override public String name() { return Json.str(def.get("name")); }
	@Override public int steamServerApp() { return num(def.get("steam_server_app")); }
	@Override public int steamGameApp() { return num(def.get("steam_game_app")); }
	@Override public String join() { return Json.str(def.get("join")); }
	@Override public String defaultPort() { return Json.str(sub("port").get("default")).replaceAll("\\.0$", ""); }
	@Override public String allowWord() { String w = Json.str(sub("allow").get("word")); return w.isEmpty() ? "list of players allowed to join" : w; }
	@Override public String allowKind() { String k = Json.str(sub("allow").get("kind")); return k.isEmpty() ? "none" : k; }
	@Override public String termsUrl() { return Json.str(sub("terms").get("url")); }
	@Override public String termsName() { return Json.str(sub("terms").get("name")); }

	// ------------------------------------------------------------------ its server's folder and program

	@Override public boolean looksLikeServer(Path d) {
		for (Object o : Json.list(def.get("server_files"))) if (Files.exists(d.resolve(Json.str(o)))) return true;
		return false;
	}
	/** Accepted when the definition names no terms, or the owner ticked them (gatekeeper-terms.txt in the folder). */
	@Override public boolean termsAccepted(Path d) { return termsUrl().isEmpty() || Files.isRegularFile(d.resolve("gatekeeper-terms.txt")); }
	/** The start command for this system, with {port}, {name}, {world}, {password}, {dir} filled in from the server's entry. */
	@Override public List<String> launch(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		String custom = Json.str(s.get("start"));
		if (!custom.isEmpty()) return WIN ? List.of("cmd", "/c", custom) : List.of("sh", "-c", custom);
		List<Object> tokens = Json.list(sub("start").get(WIN ? "windows" : "linux"));
		if (tokens.isEmpty()) return null;
		Path program = d.resolve(Json.str(tokens.get(0)));
		if (!Files.isRegularFile(program)) return null;
		Map<String, Object> set = Json.map(s.get("settings"));
		String port = Json.str(s.get("minecraft")).replaceAll(".*:", "");
		List<String> out = new ArrayList<>();
		out.add(program.toString());
		for (int i = 1; i < tokens.size(); i++) out.add(Json.str(tokens.get(i)).replace("{port}", port.isEmpty() ? defaultPort() : port)
				.replace("{name}", Json.str(s.get("name"))).replace("{world}", Json.str(set.getOrDefault("world", "world")))
				.replace("{password}", Json.str(set.get("password"))).replace("{dir}", d.toString()));
		return out;
	}
	@Override public String blocker(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		if (!Files.isDirectory(d)) return "its folder is gone (" + d + ")";
		if (launch(s) == null) return "there is no " + name() + " server program in " + d + (steamServerApp() > 0 ? " (New server in Gatekeeper installs it with SteamCMD)" : "");
		if (!termsAccepted(d)) return name() + "'s terms for running its server aren't accepted for it yet (" + termsUrl() + "): tick them under Manage, Settings";
		if (allowKind().equals("password") && Json.str(Json.map(s.get("settings")).get("password")).length() < 5) return "it needs a password of at least 5 characters (Manage, Settings), so only the players you give it to can join";
		return "";
	}

	// ------------------------------------------------------------------ its console and its log

	@Override public boolean readyLine(String line) { return ready != null && ready.matcher(line).find(); }
	/** "" means it is asked to end (as closing its window would), which these servers save on. */
	@Override public String stopCommand() { return Json.str(def.get("stop_command")); }
	/** What the definition names, else what the server prints, which Gatekeeper keeps in gatekeeper-console.log. */
	@Override public Path log(Path d) { String f = Json.str(def.get("log")); return d.resolve(f.isEmpty() ? CONSOLE_LOG : f); }
	@Override public boolean keepsConsole() { return Json.str(def.get("log")).isEmpty(); }
	@Override public String[] joined(String line) {
		if (joined == null) return null;
		Matcher m = joined.matcher(line);
		if (!m.find()) return null;
		return new String[] {group(m, "name"), group(m, "address"), group(m, "time")};
	}
	@Override public String left(String line) { if (left == null) return null; Matcher m = left.matcher(line); return m.find() ? group(m, "name") : null; }
	private static String group(Matcher m, String g) { try { String v = m.group(g); return v == null ? "" : v; } catch (IllegalArgumentException e) { return ""; } }
	@Override public boolean stoppingLine(String line) { return stopping != null && stopping.matcher(line).find(); }
	@Override public int lagIn(String t) { if (lag == null) return 0; int n = 0; Matcher m = lag.matcher(t); while (m.find()) n++; return n; }
	@Override public String[] facts(Path d) { return new String[] {"vanilla", ""}; }
	@Override public boolean worldInUse(Path d) { return false; }

	// ------------------------------------------------------------------ its settings

	private Path settingsFile(Path d) { String f = Json.str(sub("settings").get("file")); return f.isEmpty() ? null : d.resolve(f); }
	@Override public Map<String, String> settings(Path d) {
		Map<String, String> out = new LinkedHashMap<>();
		Path f = settingsFile(d);
		if (f != null) for (String l : read(f).split("\\R")) { int eq = l.indexOf('='); if (eq > 0 && !l.startsWith("#") && !l.startsWith(";")) out.put(l.substring(0, eq).trim(), l.substring(eq + 1)); }
		return out;
	}
	@Override public void setSettings(Path d, Map<String, String> set) throws IOException {
		Path f = settingsFile(d);
		if (f == null) throw new IOException(name() + " servers keep their settings in Gatekeeper (Manage, Settings), not a file");
		String t = read(f), nl = t.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>(t.isEmpty() ? List.of() : List.of(t.split("\\R")));
		for (Map.Entry<String, String> e : set.entrySet()) {
			boolean had = false;
			for (int i = 0; i < lines.size(); i++) if (lines.get(i).replaceAll("\\s*=.*", "").trim().equals(e.getKey())) { lines.set(i, e.getKey() + "=" + e.getValue()); had = true; }
			if (!had) lines.add(e.getKey() + "=" + e.getValue());
		}
		Files.writeString(f, String.join(nl, lines) + nl, StandardCharsets.UTF_8);
	}
	@Override public String port(Path d) {
		String key = Json.str(sub("port").get("setting"));
		if (!key.isEmpty()) { String v = settings(d).getOrDefault(key, "").trim(); if (v.matches("\\d+")) return v; }
		for (Object o : servers()) if (samePath(Json.str(Json.map(o).get("dir")), d)) { String p = Json.str(Json.map(o).get("minecraft")).replaceAll(".*:", ""); if (p.matches("\\d+")) return p; }
		return "";
	}
	/** Steam's server query port for the server in that folder: the definition's "query_port", a number or +n from its game port. */
	@Override public int queryPort(Path d) {
		String q = Json.str(def.get("query_port")).replaceAll("\\.0$", "");
		if (q.isEmpty()) return 0;
		try {
			if (q.startsWith("+")) { String p = port(d); return p.isEmpty() ? 0 : Integer.parseInt(p) + Integer.parseInt(q.substring(1)); }
			return Integer.parseInt(q);
		} catch (NumberFormatException e) { return 0; }
	}

	// ------------------------------------------------------------------ who may join

	/** Steam IDs (17 digits) for a Steam ID list; a name of up to 32 letters, digits, spaces, _ or - otherwise. */
	@Override public boolean validPlayer(String name) {
		return name != null && (allowKind().equals("steam_ids") ? name.matches("\\d{17}") || name.matches("[\\w \\-]{1,32}") : name.matches("[\\w \\-]{1,32}"));
	}
	private Path allowFile(Path d) { String f = Json.str(sub("allow").get("file")); return f.isEmpty() ? null : d.resolve(f); }
	/** Its list as {name, id}: a Steam ID list's entries are named by the player who has that Steam account on a launcher. */
	@Override public List<Object> allowList(Path d) {
		List<Object> out = new ArrayList<>();
		Path f = allowFile(d);
		String kind = allowKind();
		if (f == null || !(kind.equals("steam_ids") || kind.equals("names"))) return out;
		for (String l : read(f).split("\\R")) {
			String v = l.trim();
			if (v.isEmpty() || v.startsWith("/") || v.startsWith("#")) continue;
			if (kind.equals("steam_ids")) { if (v.matches("\\d{17}")) out.add(m("name", playerOfSteamId(v), "id", v)); }
			else out.add(m("name", v, "id", v));
		}
		return out;
	}
	/** The player name a launcher with that Steam account plays as, else the Steam ID itself. */
	static String playerOfSteamId(String id) {
		for (Object v : devices().values()) { Map<String, Object> dv = Json.map(v); if (id.equals(Json.str(dv.get("steam_id")))) { String p = Json.str(dv.get("player")); if (!p.isEmpty()) return p; String sn = Json.str(dv.get("steam_name")); if (!sn.isEmpty()) return sn; } }
		return id;
	}
	/** The Steam ID for a player (a launcher that plays as them, or whose Steam account has that name), or "". */
	static String steamIdOf(String name) {
		if (name.matches("\\d{17}")) return name;
		for (Object v : devices().values()) { Map<String, Object> dv = Json.map(v); if ((name.equalsIgnoreCase(Json.str(dv.get("player"))) || name.equalsIgnoreCase(Json.str(dv.get("steam_name")))) && Json.str(dv.get("steam_id")).matches("\\d{17}")) return Json.str(dv.get("steam_id")); }
		return "";
	}
	@Override public Boolean allowListInForce(Path d) {
		String kind = allowKind();
		if (kind.equals("steam_ids") || kind.equals("names")) return Boolean.TRUE;   // (a list is always in force: only those on it can join)
		return null;
	}
	@Override public String[] setAllowed(String name, boolean on) {
		String kind = allowKind();
		if (kind.equals("password")) return new String[] {"refused", name() + " servers have no list of players: whoever has the password can join (Manage, Settings)"};
		if (!(kind.equals("steam_ids") || kind.equals("names"))) return new String[] {"refused", name() + " servers have no list of players to change"};
		if (!validPlayer(name)) return new String[] {"refused", "That isn't a player name or a Steam ID"};
		Path f = allowFile(serverDir());
		String entry = kind.equals("steam_ids") ? steamIdOf(name) : name;
		if (entry.isEmpty()) return new String[] {"refused", "No launcher of " + name + " has said its Steam account yet: they open Gatehouse once with Steam signed in, then tick them again"};
		try {
			List<String> lines = new ArrayList<>(List.of(read(f).split("\\R")));
			lines.removeIf(String::isBlank);
			boolean had = lines.stream().anyMatch(l -> l.trim().equalsIgnoreCase(entry));
			if (had == on) return new String[] {"ok", name + (on ? " is already on the " : " isn't on the ") + allowWord()};
			if (on) lines.add(entry); else lines.removeIf(l -> l.trim().equalsIgnoreCase(entry));
			Files.createDirectories(f.getParent());
			Files.writeString(f, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
			return new String[] {"ok", (on ? "Added " + name + " to" : "Removed " + name + " from") + " the " + allowWord() + " of " + serverName()
					+ (minecraftRunning() ? " (it reads it again when it next starts)" : "")};
		} catch (IOException | RuntimeException e) { return new String[] {"failed", "Couldn't change the " + allowWord() + ": " + e.getMessage()}; }
	}
	@Override public String[] allowWithId(String name, String id) { return setAllowed(name, true); }
	@Override public String[] allowListOn() { return null; }

	// ------------------------------------------------------------------ backups

	@Override public String worldFolder(Path d) { String w = Json.str(def.get("world")); return w.isEmpty() ? "" : w; }
	@Override public List<String> beforeBackup() { return Json.list(def.get("save_commands")).stream().map(Json::str).toList(); }
	@Override public boolean savedLine(String line) { Pattern p = re("saved"); return p == null || p.matcher(line).find(); }
	@Override public List<String> afterBackup() { return List.of(); }
}
