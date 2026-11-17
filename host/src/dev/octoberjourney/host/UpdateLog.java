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
	private static String via(Map<String, Object> e) { return Json.str(e.get("via")); }
	/** The device's name in a line's "client" ("steamdeck / steamdeck (linux)" and "steamdeck" are both "steamdeck"). */
	private static String device(Map<String, Object> e) { return Json.str(e.get("client")).split(" / ")[0].replaceAll("\\s*\\(.*\\)\\s*$", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
	/** Are two lines the same client's same update? 2: yes, by the id its install keeps. 1: very likely, where one of
	 *  them has no id (a client or a Gatekeeper from before ids were sent): both launchers, the same device name, the
	 *  same kind of system where both say, the same versions, within fifteen minutes of each other. 0: no. */
	private static int same(Map<String, Object> a, Map<String, Object> b) {
		if (!Json.str(a.get("from")).equals(Json.str(b.get("from"))) || !Json.str(a.get("to")).equals(Json.str(b.get("to")))) return 0;
		String ia = Json.str(a.get("install")), ib = Json.str(b.get("install"));
		if (!ia.isEmpty() && !ib.isEmpty()) return ia.equals(ib) ? 2 : 0;
		if (!Json.str(a.get("kind")).startsWith("Gatehouse") || !Json.str(b.get("kind")).startsWith("Gatehouse")) return 0;
		if (device(a).isEmpty() || !device(a).equals(device(b))) return 0;
		String sa = Json.str(a.get("system")).toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")[0], sb = Json.str(b.get("system")).toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")[0];
		if (!sa.isEmpty() && !sb.isEmpty() && !sa.equals(sb)) return 0;
		try { if (Math.abs(java.time.Duration.between(LocalDateTime.parse(Json.str(a.get("when")), TIME), LocalDateTime.parse(Json.str(b.get("when")), TIME)).toMinutes()) > 15) return 0; }
		catch (RuntimeException noTime) { /* a line with no time that can be read: the rest decides */ }
		return 1;
	}
	static synchronized void add(Map<String, Object> line) {
		try {
			List<Object> l = new ArrayList<>(all());
			String id = Json.str(line.get("id"));
			for (Object o : l) if (id.equals(Json.str(Json.map(o).get("id")))) return;
			place(l, new java.util.LinkedHashMap<>(line));
			while (l.size() > KEEP) l.remove(0);
			write(l);
		} catch (IOException ignored) {}
	}
	/** Puts a line among the others. The same client's same update, already there by another way (heard from the
	 *  client itself, and handed on by a Gatekeeper that joined), stays one line: the one heard from the client itself
	 *  is kept, and notes the other way in "also". A line matched only by its name takes in one other at most: a
	 *  second of that name is another device (every Steam Deck is called "steamdeck" until it is renamed). */
	private static void place(List<Object> l, Map<String, Object> e) {
		boolean through = !via(e).isEmpty();
		for (int i = l.size() - 1; i >= 0 && i >= l.size() - 400; i--) {
			Map<String, Object> o = Json.map(l.get(i));
			int how = same(e, o);
			if (how == 0) continue;
			boolean oThrough = !via(o).isEmpty();
			if (through == oThrough) { if (how == 2) return; continue; }   // (heard the same way twice: a repeat when the ids say so, else two devices)
			if (through) {
				if (how == 1 && !Json.str(o.get("also")).isEmpty()) continue;
				Map<String, Object> kept = new java.util.LinkedHashMap<>(o);
				kept.put("also", via(e));
				l.set(i, kept);
				return;
			}
			if (how == 1 && !Json.str(e.get("also")).isEmpty()) continue;
			e.put("also", via(o));
			l.remove(i);
			if (how == 2) break;
		}
		l.add(e);
	}

	/** Once, for a log written before doubled lines were made one (updates-tidied.txt says it was done): every line is
	 *  put among the others again by today's rule. And the line this Gatekeeper used to write for a Gatekeeper that
	 *  joined, which said "fetched from: not said", goes where that Gatekeeper's own line for the same update is here.
	 *  Nothing else is changed: what a line says about how an update came about is left as it was written. */
	static synchronized void tidyOnce() {
		java.nio.file.Path done = dir.resolve("updates-tidied.txt");
		if (Files.exists(done)) return;
		try {
			List<Object> was = new ArrayList<>(all()), l = new ArrayList<>();
			for (Object o : was) place(l, new java.util.LinkedHashMap<>(Json.map(o)));
			for (int i = l.size() - 1; i >= 0; i--) {
				Map<String, Object> a = Json.map(l.get(i));
				if (!Json.str(a.get("kind")).equals("Gatekeeper") || !via(a).isEmpty() || !(Json.str(a.get("source")).isEmpty() || Json.str(a.get("source")).equals("not said"))) continue;
				for (Object o : l) {
					Map<String, Object> b = Json.map(o);
					if (b == a || !Json.str(b.get("kind")).equals("Gatekeeper") || via(b).isEmpty() || !device(a).equals(device(b))) continue;
					if (!Json.str(a.get("from")).equals(Json.str(b.get("from"))) || !Json.str(a.get("to")).equals(Json.str(b.get("to")))) continue;
					try { if (Math.abs(java.time.Duration.between(LocalDateTime.parse(Json.str(a.get("when")), TIME), LocalDateTime.parse(Json.str(b.get("when")), TIME)).toMinutes()) > 15) continue; } catch (RuntimeException noTime) { continue; }
					l.remove(i);
					break;
				}
			}
			if (l.size() != was.size()) {
				Files.copy(dir.resolve("updates.json"), dir.resolve("updates-before-tidy.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				write(l);
				log("update log: tidied. " + (was.size() - l.size()) + " doubled line(s) were made one (" + was.size() + " lines before, " + l.size() + " now; the log as it was is kept as updates-before-tidy.json)");
			}
			Files.writeString(done, "1\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException leftForNextTime) { /* tried again at the next start */ }
	}
	/** A place an update was fetched from, in words: GitHub, or the pack server at an address; other words as they are. */
	static String where(String source) {
		if (source == null || source.isBlank()) return "not said";
		java.util.regex.Matcher u = java.util.regex.Pattern.compile("https?://([^/\\s]+)").matcher(source);
		if (!u.find()) return word(source, 160);
		// (a Gatekeeper asked on 127.0.0.1 is the one on the client's own machine: an address that means nothing elsewhere)
		String host = u.group(1);
		boolean same = host.startsWith("127.") || host.startsWith("localhost") || host.startsWith("[::1]");
		String host2 = host, place = host.contains("githubusercontent.com") || host.contains("github.com") ? "GitHub (" + host + ")" : source.contains("/v1/program") ? (same ? "the Gatekeeper on its own machine" : "the Gatekeeper at " + host2) : "the pack server at " + host;
		return word(source.substring(0, u.start()) + place + source.substring(u.end()).replaceAll("^/[\\w./\\-]*", ""), 160);
	}
	static void record(String client, String kind, String player, String system, String from, String to, String mode, String info, String source) { record(client, kind, player, system, from, to, mode, info, source, ""); }
	/** install: the id a client keeps for its install ("" when it has none), so the same client's same update, heard
	 *  of two ways (from it, and through a Gatekeeper that joined), is one line. */
	static void record(String client, String kind, String player, String system, String from, String to, String mode, String info, String source, String install) {
		add(m("id", java.util.UUID.randomUUID().toString(), "when", LocalDateTime.now().format(TIME), "client", word(client, 60), "kind", kind.equals("gatekeeper") ? "Gatekeeper" : "Gatehouse (launcher)",
				"player", word(player, 16), "system", word(system, 40), "from", word(from, 20), "to", word(to, 20), "how", word(mode, 90), "info", word(info, 120), "via", "", "source", where(source), "install", word(install, 64)));
		log("update log: " + client + " went from " + from + " to " + to + " (" + mode + "; fetched from " + where(source) + ")");
	}

	/** Before this Gatekeeper restarts as a newer one: what it was, and how the update came about. */
	static void aboutToUpdate(String cameFrom) {
		try { Files.writeString(dir.resolve("update-how.txt"), ownVersion() + "|" + how.replace("|", "/") + "|" + (cameFrom == null || cameFrom.isEmpty() ? newestKeeperAt : cameFrom), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}
	/** When Gatekeeper starts: if it is a different version from the one that noted it was updating, that is a line. */
	static void ownStart() {
		tidyOnce();
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
					"system", word(e.get("system"), 40), "from", word(e.get("from"), 20), "to", word(e.get("to"), 20), "how", word(e.get("how"), 90), "info", word(e.get("info"), 120), "source", word(e.get("source"), 160), "install", word(e.get("install"), 64),
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
