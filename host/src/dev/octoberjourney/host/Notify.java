package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import dev.gatehouse.look.Quiet;

/**
 * Phone notifications (R34, 3) through ntfy (https://ntfy.sh, or a server of the owner's own): a friend joins a server,
 * a server goes down, an update needs the owner, Gatekeeper froze and restarted. Off until the owner turns it on in
 * Options. The owner installs the ntfy app and subscribes to this Gatekeeper's topic.
 *
 * <p>What ntfy says of itself (checked 2026-11-18, docs.ntfy.sh/faq): free with no sign-up "as long as you don't abuse
 * it"; best-effort uptime; a burst of 60 messages, then one every 10 seconds; messages kept 12 hours; Android delivery
 * goes through Google's Firebase. A topic has no password: its name is the secret, so a long random one is made here.
 * What is sent: the server's and player's names and a sentence; nothing else (no addresses, no keys).
 *
 * <p>host.json: ntfy_on, ntfy_url (default https://ntfy.sh), ntfy_topic, ntfy_join / ntfy_down / ntfy_update ("true" or
 * "false", all on by default once it is on).
 */
final class Notify {
	private Notify() {}

	private static final java.util.concurrent.ExecutorService OUT = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "notify"); t.setDaemon(true); return t; });
	private static final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
	static volatile String lastResult = "";

	static boolean on() { return setting("ntfy_on", "false").equals("true") && !setting("ntfy_topic", "").isEmpty(); }
	static boolean wants(String kind) { return on() && !setting("ntfy_" + kind, "true").equals("false"); }

	/** A friend joined a server. */
	static void joined(String player, String server) { if (wants("join")) send(player + " joined " + server, player + " is playing on "
			+ server + ".", "video_game", 3); }
	/** A server went down without being asked to. */
	static void down(String server, String what) { if (wants("down")) send(server + " went down", what, "warning", 4); }
	/** R42: someone wrote in a chat room (not the host). At most one a minute for each room, so a busy room doesn't
	 *  fill the phone (or ntfy's limits). */
	static void chat(String room, String who, String text) {
		if (!wants("chat")) return;
		long now = System.currentTimeMillis();
		Long was = chatSaid.get(room);
		if (was != null && now - was < 60_000) return;
		chatSaid.put(room, now);
		send(who + " in " + room, text.length() > 140 ? text.substring(0, 140) + "\u2026" : text, "speech_balloon", 3);
	}
	private static final Map<String, Long> chatSaid = new java.util.concurrent.ConcurrentHashMap<>();
	/** Something about updating needs the owner. */
	static void update(String title, String what) { if (wants("update")) send(title, what, "arrow_up", 3); }

	/** A Gate System updated (a launcher, this Gatekeeper, or one that joined): a line of the update log. Those that come
	 *  within a minute of each other (a constellation updating together) go as one message; the same client's same
	 *  update heard twice is told once. */
	static synchronized void updated(String client, String kind, String from, String to, String install) {
		if (!wants("updated") || to.isEmpty()) return;
		String key = (install.isEmpty() ? client : install) + "|" + to;
		if (!toldUpdates.add(key)) return;
		if (toldUpdates.size() > 500) toldUpdates.clear();
		pendingUpdates.add(new String[] {client, kind, from, to});
		if (pendingUpdates.size() == 1) LATER.schedule(Notify::sendUpdates, 60, java.util.concurrent.TimeUnit.SECONDS);
	}
	private static synchronized void sendUpdates() {
		if (pendingUpdates.isEmpty()) return;
		java.util.List<String[]> l = new java.util.ArrayList<>(pendingUpdates);
		pendingUpdates.clear();
		java.util.Set<String> tos = new java.util.LinkedHashSet<>();
		for (String[] u : l) tos.add(u[3]);
		String title = l.size() == 1 ? l.get(0)[0] + " updated to " + l.get(0)[3]
				: l.size() + " Gate Systems updated" + (tos.size() == 1 ? " to " + tos.iterator().next() : "");
		StringBuilder body = new StringBuilder();
		for (String[] u : l) body.append(body.length() == 0 ? "" : "\n").append(u[0]).append(" (").append(u[1].equals("gatekeeper") || u[1].equals("Gatekeeper")
				? "Gatekeeper" : "Gatehouse").append("): ").append(u[2].isEmpty() ? "" : u[2] + " \u2192 ").append(u[3]);
		send(title, body.toString(), "white_check_mark", 2);
	}
	private static final java.util.Set<String> toldUpdates = new java.util.HashSet<>();
	private static final java.util.List<String[]> pendingUpdates = new java.util.ArrayList<>();
	private static final java.util.concurrent.ScheduledExecutorService LATER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "notify-updates"); t.setDaemon(true); return t; });
	// Kept across a restart: Gatekeeper often restarts (for its own update) inside the minute it gathers updates for, and
	// what it held was lost. When it ends, what it holds is written to notify-pending.json; when it starts, it is read
	// back and sent a minute later, together with whatever comes in meanwhile (its own update among them).
	static { Runtime.getRuntime().addShutdownHook(new Thread(Notify::keepForRestart, "notify-keep")); }
	private static synchronized void keepForRestart() {
		try {
			if (dir == null) return;
			java.nio.file.Path f = dir.resolve("notify-pending.json");
			if (pendingUpdates.isEmpty()) { java.nio.file.Files.deleteIfExists(f); return; }
			java.util.List<Object> l = new java.util.ArrayList<>();
			for (String[] u : pendingUpdates) l.add(java.util.List.of(u[0], u[1], u[2], u[3]));
			java.nio.file.Files.writeString(f, Json.write(m("at", System.currentTimeMillis(), "updates", l, "told", new java.util.ArrayList<Object>(toldUpdates))), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
	}
	static synchronized void restore() {
		try {
			java.nio.file.Path f = dir.resolve("notify-pending.json");
			if (!java.nio.file.Files.isRegularFile(f)) return;
			Map<String, Object> s = Json.map(Json.parse(java.nio.file.Files.readString(f, StandardCharsets.UTF_8)));
			java.nio.file.Files.deleteIfExists(f);
			// (only from a restart just now: held for longer than ten minutes, the moment has passed)
			double at = 0; try { at = Double.parseDouble(Json.str(s.get("at"))); } catch (NumberFormatException e) { Quiet.seen(e); }
			if (System.currentTimeMillis() - (long) at > 600_000) return;
			for (Object o : Json.list(s.get("told"))) toldUpdates.add(Json.str(o));
			for (Object o : Json.list(s.get("updates"))) { java.util.List<Object> u = Json.list(o); if (u.size() == 4) pendingUpdates.add(new String[] {
					Json.str(u.get(0)), Json.str(u.get(1)), Json.str(u.get(2)), Json.str(u.get(3))}); }
			if (!pendingUpdates.isEmpty()) LATER.schedule(Notify::sendUpdates, 60, java.util.concurrent.TimeUnit.SECONDS);
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
	}

	/** Queued and sent in the background, in order (never more than 30 waiting, and two seconds apart). */
	static void send(String title, String body, String tags, int priority) {
		if (queued.get() > 30) return;
		queued.incrementAndGet();
		String url = setting("ntfy_url", "https://ntfy.sh").replaceAll("/+$", "") + "/" + setting("ntfy_topic", "");
		OUT.submit(() -> {
			try { lastResult = post(url, title, body, tags, priority); Thread.sleep(2000); }
			catch (InterruptedException ignored) { Quiet.seen(ignored); }
			finally { queued.decrementAndGet(); }
		});
	}
	private static String post(String url, String title, String body, String tags, int priority) {
		try {
			HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
			c.setConnectTimeout(5000); c.setReadTimeout(10_000); c.setRequestMethod("POST"); c.setDoOutput(true);
			// (headers carry only plain characters: the title is cut to them; the message itself goes as UTF-8)
			c.setRequestProperty("Title", title.replaceAll("[^\\x20-\\x7E]", "").trim());
			c.setRequestProperty("Tags", tags); c.setRequestProperty("Priority", String.valueOf(priority));
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			c.setFixedLengthStreamingMode(b.length);
			try (OutputStream o = c.getOutputStream()) { o.write(b); }
			int code = c.getResponseCode();
			return code == 200 ? "sent" : "ntfy answered " + code + (code == 429 ? " (too many at once: it takes one every 10 seconds)" : "");
		} catch (IOException | RuntimeException e) { return "couldn't reach " + url.replaceAll("/[^/]*$", "") + " (" + e.getMessage() + ")"; }
	}

	/** A topic no one would guess: "gatehouse-" and 24 random letters and digits. */
	static String newTopic() {
		String abc = "abcdefghijkmnpqrstuvwxyz23456789";
		java.security.SecureRandom r = new java.security.SecureRandom();
		StringBuilder s = new StringBuilder("gatehouse-");
		for (int i = 0; i < 24; i++) s.append(abc.charAt(r.nextInt(abc.length())));
		return s.toString();
	}

	/** GET /admin/notify: the settings. POST /admin/notify: change them. POST /admin/notify/test: a test message (its result
	 *  comes back). POST /admin/notify/topic: a new topic. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.startsWith("/admin/notify")) return false;
		if (path.equals("/admin/notify/topic") && x.getRequestMethod().equals("POST")) { set("ntfy_topic", newTopic()); }
		else if (path.equals("/admin/notify/test") && x.getRequestMethod().equals("POST")) {
			if (setting("ntfy_topic", "").isEmpty()) set("ntfy_topic", newTopic());
			String url = setting("ntfy_url", "https://ntfy.sh").replaceAll("/+$", "") + "/" + setting("ntfy_topic", "");
			String r = post(url, "Gatekeeper test", "Notifications from " + AutoUpdate.myName() + " reach this phone.", "white_check_mark", 3);
			lastResult = r;
			reply(x, 200, m("result", r.equals("sent") ? "ok" : "failed", "message", r.equals("sent") ? "Sent: it should be on your phone now." : r));
			return true;
		}
		else if (path.equals("/admin/notify") && x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			for (String k : new String[] {"on", "join", "down", "update", "chat", "updated"}) if (b.containsKey(k)) set("ntfy_" + k, ServerTools.yes(b.get(k)) ? "true" : "false");
			if (b.containsKey("url")) { String u = Json.str(b.get("url")).trim(); set("ntfy_url",
					u.matches("https?://[A-Za-z0-9.\\-:]+(/[\\w.\\-/]*)?") ? u.replaceAll("/+$", "") : "https://ntfy.sh"); }
			if (setting("ntfy_on", "false").equals("true") && setting("ntfy_topic", "").isEmpty()) set("ntfy_topic", newTopic());
		}
		else if (path.equals("/admin/notify/keep") && x.getRequestMethod().equals("POST")) {   // (crash watch: start this server again when it goes down)
			Map<String, Object> b = body(x);
			String id = Json.str(b.get("server"));
			for (Object o : servers()) { Map<String, Object> s = Json.map(o); if (id.equals(Json.str(s.get("id")))) { s.put("keep_online",
					ServerTools.yes(b.get("on")) ? "crash" : "none"); log("crash watch: " + Json.str(s.get("name"))
					+ (ServerTools.yes(b.get("on")) ? " is started again when it goes down" : " is left off when it goes down")); } }
			ServerTools.saveCfg();
		}
		java.util.List<Object> keep = new java.util.ArrayList<>();
		for (Object o : servers()) { Map<String, Object> s = Json.map(o); keep.add(m("id", Json.str(s.get("id")), "name",
				Json.str(s.get("name")), "keep", Json.str(s.get("keep_online")).equals("crash"))); }
		reply(x, 200, m("on", setting("ntfy_on", "false").equals("true"), "url", setting("ntfy_url", "https://ntfy.sh"), "topic", setting("ntfy_topic", ""),
				"join", !setting("ntfy_join", "true").equals("false"), "down", !setting("ntfy_down", "true").equals("false"), "update",
						!setting("ntfy_update", "true").equals("false"), "chat", !setting("ntfy_chat", "true").equals("false"),
				"updated", !setting("ntfy_updated", "true").equals("false"), "last", lastResult,
				"servers", keep));
		return true;
	}
	private static void set(String k, String v) { cfg.put(k,
			v); try { ServerTools.saveCfg(); } catch (IOException e) { log("couldn't save notification settings: " + e); } }
}
