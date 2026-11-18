package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.gatehouse.look.Quiet;

/**
 * The history of servers: a line for each server a Gatekeeper has made, found it was looking after, built a pack for,
 * or stopped looking after, with which Gatekeeper that was and where it answers. Kept in history.json beside the
 * settings, and never trimmed by what happens to the server itself: a server that is deleted keeps its lines.
 *
 * It is shared through a constellation. A Gatekeeper that joined another hands its lines to that one and takes that
 * one's back (POST /v1/history, as the member it is), so every Gatekeeper of a constellation ends up holding the same
 * history, and one set up later learns of every server and every Gatekeeper that came before it by joining. Lines are
 * only ever added: each has a key made where it was written, and a line already held is not taken twice.
 *
 * What it holds is what a Gatekeeper already tells its constellation: names, kinds, versions, ports and Gatekeeper
 * addresses. No folders, no player names, no keys. Only this machine, a launcher with Gatekeeper control and a
 * Gatekeeper allowed into the constellation are given it.
 *
 *   GET  /admin/history     Gatekeeper's own window: the lines, newest first, and the Gatekeepers they name
 *   POST /v1/history        a Gatekeeper of this constellation: {entries} in, every line held back
 */
final class History {
	private History() {}
	static final int MOST = 5000;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final String[] FIELDS = {"key", "at", "when", "what", "server", "name", "kind", "version", "port", "pack", "gatekeeper",
			"install", "address", "system"};
	private static List<Object> lines;
	private static volatile boolean changed = true;   // (something the Gatekeeper this one joined hasn't been handed yet)
	private static volatile long synced = 0, swept = 0;

	private static Path file() { return dir.resolve("history.json"); }
	private static String word(Object o, int most) { String s = Json.str(o).replaceAll("[\\p{Cntrl}]",
			" ").trim(); return s.length() > most ? s.substring(0, most) : s; }

	/** The id this Gatekeeper's install keeps in the history (made the first time it is needed). */
	static synchronized String me() {
		String id = setting("history_id", "");
		if (!id.matches("[0-9a-f\\-]{16,64}")) {
			id = java.util.UUID.randomUUID().toString();
			cfg.put("history_id", id);
			try { ServerTools.saveCfg(); } catch (IOException ignored) { Quiet.seen(ignored); }
		}
		return id;
	}
	static synchronized List<Object> all() {
		if (lines == null) {
			lines = new ArrayList<>();
			try { if (Files.isRegularFile(file())) for (Object o : Json.list(Json.parse(Files.readString(file(), StandardCharsets.UTF_8)))) lines.add(Json.map(o)); }
			catch (IOException | RuntimeException e) { log("history: the file couldn't be read (" + e.getMessage()
					+ "); it is left as it is, and nothing is written over it"); lines = null; return new ArrayList<>(); }
		}
		return lines;
	}
	private static void save() {
		try { Path tmp = file().resolveSibling("history.json.new"); Files.writeString(tmp, Json.write(lines),
				StandardCharsets.UTF_8); Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
		catch (IOException e) { log("history: couldn't be saved: " + e.getMessage()); }
	}
	private static boolean holds(String key) { for (Object o : lines) if (key.equals(Json.str(Json.map(o).get("key")))) return true; return false; }
	private static String ownAddress() { String b = setting("bind", ""); return b.isEmpty() ? "" : b + ":" + port(); }

	/** Writes a line about one of this Gatekeeper's servers. what: made, found, pack, removed. */
	static synchronized void record(String what, Map<String, Object> s, String pack) {
		all();
		if (lines == null) return;
		long now = System.currentTimeMillis();
		String id = Json.str(s.get("id")), kind = "", version = "";
		try { String[] f = serverFacts(Path.of(Json.str(s.get("dir")))); kind = Packs.fabricLoader(Path.of(Json.str(s.get("dir")))).isEmpty() ? f[0] : "fabric"; version = f[1]; } catch (RuntimeException ignored) { Quiet.seen(ignored); }
		lines.add(m("key", me() + ":" + Long.toString(now, 36) + ":" + Integer.toString(lines.size(), 36), "at", String.valueOf(now),
				"when", LocalDateTime.now().format(TIME), "what", what,
				"server", word(id, 64), "name", word(s.get("name"), 60), "kind", kind, "version", word(version, 32), "port",
						word(Json.str(s.get("minecraft")).replaceAll(".*:", ""), 5), "pack", word(pack, 24),
				"gatekeeper", word(AutoUpdate.myName(), 60), "install", me(), "address", ownAddress(), "system", word(systemName(), 40)));
		save();
		changed = true;
	}

	/** Keeps the history in step with the servers this Gatekeeper looks after: one it hasn't written about yet is
	 *  "found", and one it wrote about that it no longer looks after is "removed". */
	static synchronized void sweep() {
		long now = System.currentTimeMillis();
		if (now - swept < 60_000) return;
		swept = now;
		all();
		if (lines == null) return;
		String mine = me();
		Map<String, Map<String, Object>> last = new LinkedHashMap<>();   // (server id -> this Gatekeeper's newest made / found / removed line for it)
		for (Object o : lines) { Map<String, Object> e = Json.map(o); if (mine.equals(Json.str(e.get("install")))
				&& !Json.str(e.get("what")).equals("pack")) last.put(Json.str(e.get("server")), e); }
		List<String> here = new ArrayList<>();
		for (Object o : new ArrayList<>(servers())) {
			Map<String, Object> s = Json.map(o);
			String id = Json.str(s.get("id"));
			if (id.isEmpty()) continue;
			here.add(id);
			Map<String, Object> e = last.get(id);
			if (e == null || Json.str(e.get("what")).equals("removed")) record("found", s, "");
		}
		for (Map.Entry<String, Map<String, Object>> e : last.entrySet())
			if (!here.contains(e.getKey()) && !Json.str(e.getValue().get("what")).equals("removed")) record("removed", m("id", e.getKey(),
					"name", e.getValue().get("name"), "minecraft", e.getValue().get("port"), "dir", ""), "");
	}

	/** Takes lines another Gatekeeper holds: the ones not held yet, with only the fields a line has. */
	static synchronized int merge(List<Object> theirs) {
		all();
		if (lines == null) return 0;
		int took = 0;
		for (Object o : theirs) {
			if (lines.size() >= MOST) break;
			Map<String, Object> e = Json.map(o);
			String key = word(e.get("key"), 120);
			if (!key.matches("[0-9a-f\\-]{16,64}:[0-9a-z]{1,16}:[0-9a-z]{1,8}") || holds(key) || !Json.str(e.get("at")).matches("\\d{10,16}")) continue;
			Map<String, Object> line = new LinkedHashMap<>();
			for (String f : FIELDS) line.put(f, word(e.get(f), f.equals("key") ? 120 : 64));
			lines.add(line);
			took++;
		}
		if (took > 0) {
			lines.sort(java.util.Comparator.comparing(o -> Json.str(Json.map(o).get("at"))));
			save();
			changed = true;   // (and what was taken is handed on to the Gatekeeper this one joined, if it has one)
		}
		return took;
	}

	/** With the Gatekeeper this one joined: hands over what is held and takes what that one holds. When something
	 *  changed here, and otherwise every ten minutes. Asked for often; does nothing most of the time. */
	static void sync() {
		try {
			sweep();
			if (AutoUpdate.lead().isEmpty()) return;
			long now = System.currentTimeMillis();
			if (now - synced < (changed ? 20_000 : 600_000)) return;
			synced = now;
			List<Object> mine;
			synchronized (History.class) { mine = new ArrayList<>(all()); }
			Map<String, Object> r = Json.map(Json.parse(AutoUpdate.leadCall("/v1/history", Json.write(m("entries", mine)))));
			if (!Json.str(r.get("result")).equals("ok")) return;   // (not a member there yet, or an older Gatekeeper: tried again later)
			changed = false;
			int took = merge(Json.list(r.get("entries")));
			if (took > 0) { changed = false; log("history: " + took + " line(s) about servers taken from the Gatekeeper this one joined"); }
		} catch (IOException | RuntimeException ignored) { /* (not answering: next time) */ }
	}

	/** POST /v1/history from a Gatekeeper of this constellation (d: its record here). */
	static void take(HttpExchange x, String who, Map<String, Object> d) throws IOException {
		boolean trusted = ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin")) || ServerTools.yes(d.get("constellation"));
		if (!trusted) { reply(x, 200, m("result", "refused", "message",
				"Only this machine, a launcher with Gatekeeper control, or a Gatekeeper allowed into this constellation is given the history of servers.")); return; }
		int took = merge(Json.list(body(x).get("entries")));
		if (took > 0) log("history: " + took + " line(s) about servers taken from " + who);
		List<Object> out;
		synchronized (History.class) { out = new ArrayList<>(all()); }
		reply(x, 200, m("result", "ok", "entries", out));
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/history")) return false;
		sweep();
		List<Object> newest;
		synchronized (History.class) { newest = new ArrayList<>(all()); }
		java.util.Collections.reverse(newest);
		// the Gatekeepers the lines name: each with where it answered last, how many servers it has written about, and when it last wrote
		Map<String, Map<String, Object>> keepers = new LinkedHashMap<>();
		Map<String, java.util.Set<String>> had = new LinkedHashMap<>();
		for (Object o : newest) {
			Map<String, Object> e = Json.map(o);
			String in = Json.str(e.get("install"));
			had.computeIfAbsent(in, k -> new java.util.TreeSet<>()).add(Json.str(e.get("server")));
			if (!keepers.containsKey(in)) keepers.put(in, m("install", in, "name", e.get("gatekeeper"), "address", e.get("address"),
					"system", e.get("system"), "last", e.get("when"), "this", in.equals(me())));
		}
		List<Object> list = new ArrayList<>();
		for (Map<String, Object> k : keepers.values()) { k.put("servers", String.valueOf(had.get(Json.str(k.get("install"))).size())); list.add(k); }
		reply(x, 200, m("entries", newest, "gatekeepers", list, "shared", !AutoUpdate.lead().isEmpty()));
		return true;
	}
}
