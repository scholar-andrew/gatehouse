package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * The record of who updated: every time a client of this Gatekeeper's constellation, or this Gatekeeper itself, goes
 * from one version of the program to another, a line is kept in updates.json in Gatekeeper's folder: when, which
 * client and what kind, who plays on it, its system, from which version to which, and how (normal: Update was
 * pressed; auto; constellation).
 *
 * Where the lines come from:
 *   - a launcher: this Gatekeeper sees the version it reports change (AutoUpdate.report);
 *   - this Gatekeeper: it notes how it is about to update before it restarts, and writes the line when it is back;
 *   - a Gatekeeper that joined, and its launchers: that Gatekeeper keeps its own record and hands new lines on to the
 *     one it joined, which files them as having come through it. Each line has an id, so none is filed twice.
 *
 * It is read in Gatekeeper's own window (the Constellation tab), and through the web panel only by an account that
 * was given "See the update log" (the web panel's accounts have passwords the owner sets). The file itself is an
 * ordinary file on this machine: whoever can read this machine's files can read it.
 */
final class UpdateLog {
	static final int KEEP = 5000;
	/** How this Gatekeeper is about to update, set by whatever starts the update (normal unless said otherwise). */
	static volatile String how = "normal";

	static synchronized List<Object> all() { return Json.list(Json.parse(read(dir.resolve("updates.json")))); }
	private static void write(List<Object> l) throws IOException { Files.writeString(dir.resolve("updates.json"), Json.write(l), StandardCharsets.UTF_8); }
	private static String word(Object o, int max) { return clean(Json.str(o), max).replace("<", ""); }

	/** Files a line (unless one with its id is here already). */
	static synchronized void add(Map<String, Object> e) {
		try {
			List<Object> l = new ArrayList<>(all());
			String id = Json.str(e.get("id"));
			for (Object o : l) if (id.equals(Json.str(Json.map(o).get("id")))) return;
			l.add(e);
			while (l.size() > KEEP) l.remove(0);
			write(l);
		} catch (IOException ignored) {}
	}
	/** A place an update was fetched from, in words: GitHub, or the pack server at an address; other words as they are. */
	static String where(String source) {
		if (source == null || source.isBlank()) return "not said";
		java.util.regex.Matcher u = java.util.regex.Pattern.compile("https?://([^/\\s]+)").matcher(source);
		if (!u.find()) return word(source, 160);
		String host = u.group(1), place = host.contains("githubusercontent.com") || host.contains("github.com") ? "GitHub (" + host + ")" : "the pack server at " + host;
		return word(source.substring(0, u.start()) + place + source.substring(u.end()).replaceAll("^/[\\w./\\-]*", ""), 160);
	}
	static void record(String client, String kind, String player, String system, String from, String to, String mode, String info, String source) {
		add(m("id", java.util.UUID.randomUUID().toString(), "when", LocalDateTime.now().format(TIME), "client", word(client, 60), "kind", kind.equals("gatekeeper") ? "Gatekeeper" : "Gatehouse (launcher)",
				"player", word(player, 16), "system", word(system, 40), "from", word(from, 20), "to", word(to, 20), "how", word(mode, 40), "info", word(info, 120), "via", "", "source", where(source)));
		log("update log: " + client + " went from " + from + " to " + to + " (" + mode + "; fetched from " + where(source) + ")");
	}

	/** Before this Gatekeeper restarts as a newer one: what it was, and how the update came about. */
	static void aboutToUpdate() {
		try { Files.writeString(dir.resolve("update-how.txt"), ownVersion() + "|" + how + "|" + newestKeeperAt, StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	/** When Gatekeeper starts: if it is a different version from the one that noted it was updating, that is a line. */
	static void ownStart() {
		String[] was = read(dir.resolve("update-how.txt")).trim().split("\\|", 3);
		try { Files.deleteIfExists(dir.resolve("update-how.txt")); } catch (IOException ignored) {}
		String seen = read(dir.resolve("version-seen.txt")).trim(), now = ownVersion();
		String from = was.length >= 2 && !was[0].isEmpty() ? was[0] : seen;
		if (!from.isEmpty() && !now.isEmpty() && !from.equals(now)) record(AutoUpdate.myName(), "gatekeeper", "", systemName(), from, now, was.length >= 2 ? was[1] : "not through Gatekeeper's own update (its file was replaced)", info(), was.length == 3 ? was[2] : "");
		try { Files.writeString(dir.resolve("version-seen.txt"), now, StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	static String info() { return System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "") + "; Java " + System.getProperty("java.version", ""); }

	// ------------------------------------------------------------------ a member handing its lines on to the Gatekeeper it joined

	/** The lines not handed on yet (at most 40 at a time). */
	static synchronized List<Object> unsent() {
		List<Object> l = all();
		int sent = 0; try { sent = Integer.parseInt(read(dir.resolve("updates-sent.txt")).trim()); } catch (NumberFormatException ignored) {}
		if (sent > l.size()) sent = 0;
		return new ArrayList<>(l.subList(sent, Math.min(l.size(), sent + 40)));
	}
	static synchronized void handedOn(int n) {
		if (n <= 0) return;
		int sent = 0; try { sent = Integer.parseInt(read(dir.resolve("updates-sent.txt")).trim()); } catch (NumberFormatException ignored) {}
		try { Files.writeString(dir.resolve("updates-sent.txt"), String.valueOf(sent + n), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	/** Lines a Gatekeeper that joined hands on: filed as having come through it, every part of them kept as plain words. */
	static void taken(String through, List<Object> lines) {
		int n = 0;
		for (Object o : lines) {
			if (n++ >= 60) break;
			Map<String, Object> e = Json.map(o);
			String id = Json.str(e.get("id"));
			if (!id.matches("[0-9a-fA-F\\-]{16,64}")) continue;
			add(m("id", id, "when", word(e.get("when"), 19), "client", word(e.get("client"), 60), "kind", Json.str(e.get("kind")).equals("Gatekeeper") ? "Gatekeeper" : "Gatehouse (launcher)", "player", word(e.get("player"), 16).replaceAll("\\W", ""),
					"system", word(e.get("system"), 40), "from", word(e.get("from"), 20), "to", word(e.get("to"), 20), "how", word(e.get("how"), 40), "info", word(e.get("info"), 120), "source", word(e.get("source"), 160),
					"via", Json.str(e.get("via")).isEmpty() ? word(through, 60) : word(through, 60) + " (from " + word(e.get("via"), 40) + ")"));
		}
	}

	/** The window's call (and, for an account allowed to, the web panel's): GET /admin/updates, newest first. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/updates")) return false;
		List<Object> l = all(), out = new ArrayList<>();
		for (int i = l.size() - 1; i >= 0 && out.size() < 1000; i--) out.add(l.get(i));
		reply(x, 200, m("entries", out, "total", l.size()));
		return true;
	}
}
