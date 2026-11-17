package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * Files passed between the clients of a chatroom, through this Gatekeeper. Someone in a room offers a file: it is
 * kept here (shared/<id>, listed in shared/index.json), the room is told, and anyone in that room can save a copy.
 * Nobody is sent anything: a file reaches a device only when its user chooses to save it.
 *
 * The limits are the owner's (host.json): "share" false turns it off; "share_max_mb" is the biggest one file may be
 * (25 unless set). A file is kept for a day, and all of them together for at most 500 MB: the oldest go first.
 * Gatekeeper never opens or runs what it is given, and gives it back exactly as it came.
 *
 *   /v1/share        POST ?room=&name=  (the file as the body)   offers one        } a launcher in that room (see Chat)
 *   /v1/share/list   GET  ?room=                                  what is on offer  }
 *   /v1/share        GET  ?id=                                    one of them       }
 *   /admin/share, /admin/share/list                               the same for the owner, as "Host"
 */
final class Share {
	private Share() {}
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final long DAY = 24L * 3600 * 1000, ALL = 500L * 1024 * 1024;

	static boolean on() { return !setting("share", "true").equals("false"); }
	static long max() { try { return (long) (Double.parseDouble(setting("share_max_mb", "25")) * 1024 * 1024); } catch (NumberFormatException e) { return 25L * 1024 * 1024; } }
	private static Path folder() { return dir.resolve("shared"); }
	private static long num(Object o) { try { return (long) Double.parseDouble(Json.str(o)); } catch (RuntimeException e) { return 0; } }
	private static String q(HttpExchange x, String key) {
		String raw = x.getRequestURI().getRawQuery();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)" + key + "=([^&]*)").matcher(raw == null ? "" : raw);
		return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : "";
	}

	/** What is kept, oldest first, with what is too old or too much thrown away. */
	private static synchronized List<Object> index() {
		List<Object> l;
		try { l = new ArrayList<>(Json.list(Json.parse(read(folder().resolve("index.json"))))); } catch (RuntimeException e) { l = new ArrayList<>(); }
		long now = System.currentTimeMillis(), total = 0;
		for (Object o : l) total += num(Json.map(o).get("size"));
		boolean changed = false;
		while (!l.isEmpty() && (now - num(Json.map(l.get(0)).get("at")) > DAY || total > ALL)) {
			Map<String, Object> old = Json.map(l.remove(0));
			total -= num(old.get("size"));
			try { Files.deleteIfExists(folder().resolve(Json.str(old.get("id")))); } catch (IOException ignored) {}
			changed = true;
		}
		if (changed) save(l);
		return l;
	}
	private static void save(List<Object> l) { try { Files.createDirectories(folder()); Files.writeString(folder().resolve("index.json"), Json.write(l), StandardCharsets.UTF_8); } catch (IOException ignored) {} }
	private static String size(long n) { return n < 1024 ? n + " bytes" : n < 1024 * 1024 ? (n / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0); }

	private static synchronized Map<String, Object> offer(String room, String who, String key, String name, byte[] b) {
		if (!on()) return m("result", "refused", "message", "This Gatekeeper's owner has turned file sharing off.");
		if (b.length == 0) return m("result", "refused", "message", "That file is empty.");
		if (b.length > max()) return m("result", "refused", "message", "That file is bigger than this Gatekeeper takes (" + size(max()) + " at most).");
		String clean = name.replaceAll("^.*[\\\\/]", "").replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
		if (clean.isEmpty()) clean = "file";
		if (clean.length() > 120) clean = clean.substring(clean.length() - 120);
		String id = java.util.UUID.randomUUID().toString();
		try { Files.createDirectories(folder()); Files.write(folder().resolve(id), b); } catch (IOException e) { return m("result", "failed", "message", "Couldn't keep it: " + e.getMessage()); }
		List<Object> l = index();
		l.add(m("id", id, "room", room, "name", clean, "size", b.length, "who", who, "when", LocalDateTime.now().format(TIME), "at", System.currentTimeMillis()));
		save(l);
		Chat.say(room, who, key + "/file", "offers a file: " + clean + " (" + size(b.length) + "). Files… saves a copy.", m("id", id, "name", clean, "size", b.length));
		log("files: " + who + " offered " + clean + " (" + size(b.length) + ") in " + Chat.all().getOrDefault(room, room));
		return m("result", "ok", "message", "Offered to the room. It is kept for a day.", "id", id);
	}
	private static List<Object> listed(String room) {
		List<Object> out = new ArrayList<>();
		for (Object o : index()) if (room.equals(Json.str(Json.map(o).get("room")))) out.add(o);
		return out;
	}
	private static void give(HttpExchange x, Map<String, Object> e) throws IOException {
		Path f = folder().resolve(Json.str(e.get("id")));
		if (!Files.isRegularFile(f)) { reply(x, 404, m("result", "refused", "message", "That file is no longer kept.")); return; }
		byte[] b = Files.readAllBytes(f);
		x.getResponseHeaders().set("Content-Type", "application/octet-stream");
		x.sendResponseHeaders(200, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}
	private static Map<String, Object> find(String id) { for (Object o : index()) if (id.equals(Json.str(Json.map(o).get("id")))) return Json.map(o); return null; }
	private static byte[] bodyBytes(HttpExchange x) throws IOException { try (InputStream in = x.getRequestBody()) { return in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max() + 1)); } }

	/** A launcher that has said hello. */
	static void launcher(HttpExchange x, String path, String id, Map<String, Object> d) throws IOException {
		if (path.equals("/v1/share/list")) { String room = q(x, "room"); reply(x, 200, m("on", on(), "max", max(), "files", Chat.may(id, d, room) ? listed(room) : new ArrayList<>())); return; }
		if (x.getRequestMethod().equals("GET")) {
			Map<String, Object> e = find(q(x, "id"));
			if (e == null || !Chat.may(id, d, Json.str(e.get("room")))) { reply(x, 404, m("result", "refused", "message", "No such file in a room this launcher is in.")); return; }
			give(x, e); return;
		}
		String room = q(x, "room");
		if (!Chat.may(id, d, room)) { reply(x, 200, m("result", "refused", "message", "This launcher isn't in that room.")); return; }
		String player = Json.str(d.get("player")), name = Json.str(d.get("name"));
		reply(x, 200, offer(room, player.isEmpty() ? (name.isEmpty() ? "A launcher" : name) : player, id, q(x, "name"), bodyBytes(x)));
	}
	/** The owner, from Gatekeeper's own window. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/share/list")) { reply(x, 200, m("on", on(), "max", max(), "files", listed(q(x, "room")))); return true; }
		if (!path.equals("/admin/share")) return false;
		if (x.getRequestMethod().equals("GET")) { Map<String, Object> e = find(q(x, "id")); if (e == null) reply(x, 404, m("result", "refused", "message", "No such file.")); else give(x, e); return true; }
		String room = q(x, "room");
		if (!Chat.all().containsKey(room)) { reply(x, 200, m("result", "refused", "message", "No such room.")); return true; }
		reply(x, 200, offer(room, "Host", "host", q(x, "name"), bodyBytes(x)));
		return true;
	}
}
