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
import dev.gatehouse.look.Quiet;

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
	static long max() { try { return (long) (Double.parseDouble(setting("share_max_mb",
			"25")) * 1024 * 1024); } catch (NumberFormatException e) { return 25L * 1024 * 1024; } }
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
			try { Files.deleteIfExists(folder().resolve(Json.str(old.get("id")))); } catch (IOException ignored) { Quiet.seen(ignored); }
			changed = true;
		}
		if (changed) save(l);
		return l;
	}
	private static void save(List<Object> l) { try { Files.createDirectories(folder()); Files.writeString(folder().resolve("index.json"),
			Json.write(l), StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); } }
	private static String size(long n) { return n < 1024 ? n + " bytes" : n < 1024 * 1024 ? (n / 1024) + " KB"
			: String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0); }

	private static synchronized Map<String, Object> offer(String room, String who, String key, String name, byte[] b) {
		if (!on()) return m("result", "refused", "message", "This Gatekeeper's owner has turned file sharing off.");
		if (b.length == 0) return m("result", "refused", "message", "That file is empty.");
		if (b.length > max()) return m("result", "refused", "message", "That file is bigger than this Gatekeeper takes (" + size(max()) + " at most).");
		String clean = name.replaceAll("^.*[\\\\/]", "").replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
		if (clean.isEmpty()) clean = "file";
		if (clean.length() > 120) clean = clean.substring(clean.length() - 120);
		String id = java.util.UUID.randomUUID().toString();
		try { Files.createDirectories(folder()); Files.write(folder().resolve(id), b); } catch (IOException e) { return m("result",
				"failed", "message", "Couldn't keep it: " + e.getMessage()); }
		List<Object> l = index();
		l.add(m("id", id, "room", room, "name", clean, "size", b.length, "who", who, "when", LocalDateTime.now().format(TIME), "at", System.currentTimeMillis()));
		save(l);
		Chat.say(room, who, key + "/file", "offers a file: " + clean + " (" + size(b.length) + "). Files… saves a copy.", m("id", id,
				"name", clean, "size", b.length));
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
	private static Map<String, Object> find(String id) { for (Object o
			: index()) if (id.equals(Json.str(Json.map(o).get("id")))) return Json.map(o); return null; }
	private static byte[] bodyBytes(HttpExchange x) throws IOException { try (InputStream in = x.getRequestBody()) { return in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max() + 1)); } }

	/** R42: a file for the web panel (its account may use chat). A picture (PNG, JPEG, GIF or WebP, by its first bytes)
	 *  is sent as that picture, so the page can show it; R62: a video or a sound (MP4/M4A/MOV, WebM/MKV, Ogg, MP3, WAV,
	 *  FLAC, by its first bytes) as that, in parts when the browser asks (Range: so it can seek, and Safari plays it at
	 *  all); anything else only as a download, never shown in the page. */
	static void panel(HttpExchange x, String id) throws IOException {
		Map<String, Object> e = id.isEmpty() ? null : find(id);
		Path f = e == null ? null : folder().resolve(Json.str(e.get("id")));
		if (f == null || !Files.isRegularFile(f)) { reply(x, 404, m("result", "refused", "message", "That file is no longer kept.")); return; }
		byte[] b = Files.readAllBytes(f);
		String type = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' ? "image/png"
				: b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 ? "image/jpeg"
				: b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' ? "image/gif"
				: b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P' ? "image/webp" : "";
		String name = Json.str(e.get("name")).replaceAll("[^\\w .\\-]", "_");
		x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		x.getResponseHeaders().set("Cache-Control", "no-store");
		x.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; sandbox");
		String media = type.isEmpty() ? mediaType(b, name) : "";
		if (!media.isEmpty()) { sendMedia(x, b, media); return; }
		if (type.isEmpty()) { x.getResponseHeaders().set("Content-Type", "application/octet-stream");
			x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + (name.isEmpty() ? "file" : name) + "\""); }
		else x.getResponseHeaders().set("Content-Type", type);
		x.sendResponseHeaders(200, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}
	/** What kind of video or sound the bytes are (by their first bytes; the name only tells sound from video), or "". */
	static String mediaType(byte[] b, String name) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		if (b.length > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return n.matches(".*\\.(m4a|aac)$") ? "audio/mp4" : "video/mp4";
		if (b.length > 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) return "video/webm";
		if (b.length > 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return n.endsWith(".ogv") ? "video/ogg" : "audio/ogg";
		if (b.length > 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "audio/flac";
		if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "audio/wav";
		if (b.length > 3 && ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0))) return "audio/mpeg";
		return "";
	}
	/** A video or a sound, whole or the part asked for (one "bytes=a-b" range). */
	private static void sendMedia(HttpExchange x, byte[] b, String type) throws IOException {
		x.getResponseHeaders().set("Content-Type", type);
		x.getResponseHeaders().set("Accept-Ranges", "bytes");
		String r = x.getRequestHeaders().getFirst("Range");
		java.util.regex.Matcher mr = r == null ? null : java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(r.strip());
		if (mr == null || !mr.matches() || (mr.group(1).isEmpty() && mr.group(2).isEmpty())) {
			x.sendResponseHeaders(200, b.length);
			try (OutputStream o = x.getResponseBody()) { o.write(b); }
			return;
		}
		long len = b.length, from, to;
		try {
			if (mr.group(1).isEmpty()) { from = Math.max(0, len - Long.parseLong(mr.group(2))); to = len - 1; }
			else { from = Long.parseLong(mr.group(1)); to = mr.group(2).isEmpty() ? len - 1 : Math.min(len - 1, Long.parseLong(mr.group(2))); }
		} catch (NumberFormatException ex) { from = len; to = -1; }
		if (from >= len || to < from) {
			x.getResponseHeaders().set("Content-Range", "bytes */" + len);
			x.sendResponseHeaders(416, -1);
			x.close();
			return;
		}
		x.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + len);
		x.sendResponseHeaders(206, to - from + 1);
		try (OutputStream o = x.getResponseBody()) { o.write(b, (int) from, (int) (to - from + 1)); }
	}
	/** A launcher that has said hello. */
	static void launcher(HttpExchange x, String path, String id, Map<String, Object> d) throws IOException {
		if (path.equals("/v1/share/list")) { String room = q(x, "room"); reply(x, 200, m("on", on(), "max", max(), "files", Chat.may(id, d,
				room) ? listed(room) : new ArrayList<>())); return; }
		if (x.getRequestMethod().equals("GET")) {
			Map<String, Object> e = find(q(x, "id"));
			if (e == null || !Chat.may(id, d, Json.str(e.get("room")))) { reply(x, 404, m("result", "refused", "message",
					"No such file in a room this launcher is in.")); return; }
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
		if (x.getRequestMethod().equals("GET")) { Map<String, Object> e = find(q(x, "id")); if (e == null) reply(x, 404, m("result",
				"refused", "message", "No such file.")); else give(x, e); return true; }
		String room = q(x, "room");
		if (!Chat.all().containsKey(room)) { reply(x, 200, m("result", "refused", "message", "No such room.")); return true; }
		reply(x, 200, offer(room, "Host", "host", q(x, "name"), bodyBytes(x)));
		return true;
	}
}
