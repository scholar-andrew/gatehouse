package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

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
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import dev.gatehouse.look.Quiet;

/**
 * Chatrooms: one for this Gatekeeper, and one for each of its servers. Messages are kept here, in chat/<room>.json
 * (the newest 500 of each room), so a client that opens later sees what was said.
 *
 * Who may read and write a room:
 *   a server's room     the launcher on this machine, a launcher with Gatekeeper control, and any launcher the owner
 *                       has let onto that server: allowed to start and stop it, or playing as someone on its whitelist
 *   the Gatekeeper's    anyone who may use at least one server's room
 * A launcher that is suspended or blacklisted here is in none. The owner writes as "Host", from Gatekeeper's window
 * or the web panel (an account with "Launchers, players and their permissions"), and can empty a room at the machine.
 *
 *   /v1/chat/rooms  GET             the rooms this launcher is in: [{id, name, last}]  (last: the newest message's number)
 *   /v1/chat        POST            {room, after} -> {messages, next}; with "text": says it first
 *   /admin/chat     POST            the same for the owner, plus {"rooms": true}
 *   /admin/chat/clear POST {room}   empties a room (the machine itself only)
 * A message: {n, when, who, text}, and {file: {id, name, size}} when it offers a file (Share). Nothing but text is taken
 * from a client, at most 500 characters, at most one a second.
 */
final class Chat {
	private Chat() {}
	static final int KEEP = 500;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final Map<String, List<Object>> rooms = new ConcurrentHashMap<>();
	private static final Map<String, Long> lastSaid = new ConcurrentHashMap<>();
	private static final Map<String, long[]> allowedFor = new ConcurrentHashMap<>();   // device|server -> {when asked, 1 or 0}

	private static Path file(String room) { return dir.resolve("chat").resolve(room.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".json"); }
	/** The rooms there are: id -> name. */
	static Map<String, String> all() {
		Map<String, String> out = new LinkedHashMap<>();
		String name = setting("name", "");
		out.put("gatekeeper", name.isEmpty() ? "This Gatekeeper" : name);
		for (Object o : servers()) { Map<String,
				Object> s = Json.map(o); if (!Json.str(s.get("id")).isEmpty()) out.put(Json.str(s.get("id")),
				Json.str(s.get("name")).isEmpty() ? Json.str(s.get("id")) : Json.str(s.get("name"))); }
		return out;
	}
	/** A copy of a room's messages, oldest first (the AI reads it, R65). */
	static synchronized List<Object> recent(String id) { return new ArrayList<>(room(id)); }
	private static synchronized List<Object> room(String id) {
		return rooms.computeIfAbsent(id,
				k -> { try { return new ArrayList<>(Json.list(Json.parse(read(file(k))))); } catch (RuntimeException e) { return new ArrayList<>(); } });
	}
	private static long last(String id) { List<Object> l = room(id); return l.isEmpty() ? 0 : num(Json.map(l.get(l.size() - 1)).get("n")); }
	private static long num(Object o) { try { return (long) Double.parseDouble(Json.str(o)); } catch (RuntimeException e) { return 0; } }

	static synchronized String say(String roomId, String who, String key, String text) { return say(roomId, who, key, text, null); }
	static synchronized String say(String roomId, String who, String key, String text, Map<String, Object> file) {
		String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
		if (t.isEmpty()) return "Nothing to say.";
		if (t.length() > 500) t = t.substring(0, 500);
		long now = System.currentTimeMillis();
		Long was = lastSaid.get(key);
		if (was != null && now - was < 1000) return "One message a second at most.";
		lastSaid.put(key, now);
		List<Object> l = room(roomId);
		// (R100: "at" is the moment itself, in milliseconds since 1970 UTC, which each device shows in its own local time;
		// "when" stays for older launchers, in this machine's time)
		Map<String, Object> msg = m("n", last(roomId) + 1, "at", now, "when", LocalDateTime.now().format(TIME), "who", who.length() > 40
				? who.substring(0, 40) : who, "text", t);
		if (file != null) msg.put("file", file);
		l.add(msg);
		if (!key.startsWith("host")) Notify.chat(Json.str(all().get(roomId)), msg.get("who").toString(), file != null ? "offered a file: " + Json.str(file.get("name")) : t);   // (R42)
		Ai.heard(roomId, msg.get("who").toString(), t);   // (R60: "@ai ..." is answered in the room when the helper is on)
		while (l.size() > KEEP) l.remove(0);
		try { Files.createDirectories(file(roomId).getParent()); Files.writeString(file(roomId), Json.write(l),
				StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
		return "";
	}
	private static Map<String, Object> since(String roomId, long after) {
		List<Object> out = new ArrayList<>();
		List<Object> l = room(roomId);
		synchronized (Chat.class) { for (Object o : l) if (num(Json.map(o).get("n")) > after) out.add(o); }
		while (out.size() > 200) out.remove(0);
		return m("messages", out, "next", last(roomId));
	}

	private static boolean onServer(String id, Map<String, Object> d, String server) {
		String key = id + "|" + server;
		long[] had = allowedFor.get(key);
		long now = System.currentTimeMillis();
		if (had != null && now - had[0] < 30_000) return had[1] == 1;
		boolean ok;
		try {
			ok = HostHelper.onServer(server, () -> {
				String player = Json.str(d.get("player"));
				return Boolean.TRUE.equals(d.get(serverKey())) || (!player.isEmpty() && whitelisted(player));
			});
		} catch (RuntimeException e) { ok = false; }
		allowedFor.put(key, new long[] {now, ok ? 1 : 0});
		return ok;
	}
	static boolean may(String id, Map<String, Object> d, String roomId) {
		if (!all().containsKey(roomId)) return false;
		if (ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin"))) return true;
		if (!Json.str(d.get("ban")).isEmpty()) return false;
		if (!roomId.equals("gatekeeper")) return onServer(id, d, roomId);
		for (String s : all().keySet()) if (!s.equals("gatekeeper") && onServer(id, d, s)) return true;
		return false;
	}
	private static List<Object> roomsFor(String id, Map<String, Object> d) {
		List<Object> out = new ArrayList<>();
		for (Map.Entry<String, String> e : all().entrySet()) if (d == null || may(id, d, e.getKey())) out.add(m("id", e.getKey(), "name",
				e.getKey().equals("gatekeeper") ? e.getValue() + " (everyone here)" : e.getValue(), "last", last(e.getKey())));
		return out;
	}

	/** A launcher that has said hello: /v1/chat/rooms and /v1/chat. */
	static void launcher(HttpExchange x, String path, String id, Map<String, Object> d) throws IOException {
		if (path.equals("/v1/chat/rooms")) { reply(x, 200, m("rooms", roomsFor(id, d))); return; }
		Map<String, Object> b = body(x);
		String roomId = Json.str(b.get("room"));
		if (!may(id, d, roomId)) { reply(x, 200, m("result", "refused", "message", "This launcher isn't in that room.", "messages",
				new ArrayList<>(), "next", 0)); return; }
		String said = "";
		if (b.containsKey("text")) { String player = Json.str(d.get("player")), name = Json.str(d.get("name")); said = say(roomId,
				player.isEmpty() ? (name.isEmpty() ? "A launcher" : name) : player, id, Json.str(b.get("text"))); }
		Map<String, Object> out = since(roomId, num(b.get("after")));
		out.put("result", said.isEmpty() ? "ok" : "refused"); out.put("message", said);
		reply(x, 200, out);
	}
	/** The owner: Gatekeeper's own window, and the web panel. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/chat/clear") && x.getRequestMethod().equals("POST")) {
			String roomId = Json.str(body(x).get("room"));
			if (!all().containsKey(roomId)) { reply(x, 200, m("result", "refused", "message", "No such room.")); return true; }
			synchronized (Chat.class) { room(roomId).clear(); try { Files.deleteIfExists(file(roomId)); } catch (IOException ignored) { Quiet.seen(ignored); } }
			log("chat: the room " + all().get(roomId) + " was emptied");
			reply(x, 200, m("result", "ok", "message", "Emptied."));
			return true;
		}
		if (!path.equals("/admin/chat")) return false;
		Map<String, Object> b = x.getRequestMethod().equals("POST") ? body(x) : new LinkedHashMap<>();
		String roomId = Json.str(b.get("room"));
		if (roomId.isEmpty() || !all().containsKey(roomId)) { reply(x, 200, m("result", "ok", "rooms", roomsFor("", null), "messages",
				new ArrayList<>(), "next", 0)); return true; }
		String said = b.containsKey("text") ? say(roomId, "Host", "host", Json.str(b.get("text"))) : "";
		Map<String, Object> out = since(roomId, num(b.get("after")));
		out.put("result", said.isEmpty() ? "ok" : "refused"); out.put("message", said); out.put("rooms", roomsFor("", null));
		reply(x, 200, out);
		return true;
	}
}
