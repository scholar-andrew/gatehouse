package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import dev.gatehouse.look.Quiet;

/**
 * One Gatekeeper managing another of the same constellation, from its own window. Off unless the owner of the
 * Gatekeeper to be managed says so, at that machine, for that one other Gatekeeper:
 *   - the Gatekeeper that leads lets one that joined manage it: Constellation, click it, "Let it manage this
 *     Gatekeeper" (devices.json "manage" on that Gatekeeper's entry). It then asks with the identity it joined with.
 *   - a Gatekeeper that joined lets the one it joined manage it: Constellation, "Let that Gatekeeper manage this one"
 *     (host.json "lead_manage"). It makes a secret of its own for that ("manage_token") and tells it, with its
 *     address, to the Gatekeeper it joined in its reports; taking the tick away tells it to forget them.
 *
 * What managing is: the calls the web panel allows an account that may See, Start and stop, Console and Launchers
 * and players (WebPanel.ALLOWED): the servers and their state, starting and stopping, a console, players, launchers'
 * requests and permissions, the whitelist, chat. Not: sleep and wake, updating, new servers, settings, backups, web
 * accounts, pictures, ending or uninstalling it, or changing who may manage it. And never anything that names a
 * program or a folder on the machine (WebPanel.MACHINE_ONLY). Every change is written in the managed one's log.
 *
 *   /v1/manage/<call>          the managed Gatekeeper: <call> is what follows /admin/ in its own window's calls
 *   /admin/manage/open  POST   the managing Gatekeeper: opens a window on the other one ({"id": a Gatekeeper that
 *                              joined} or {"lead": true})
 */
final class Manage {
	private Manage() {}
	private static final Set<String> MAY = Set.of("view", "control", "console", "access");
	/** Told by the Gatekeeper this one joined: may this one manage it? */
	static volatile boolean leadLets;

	static boolean leadMay() { return setting("lead_manage", "false").equals("true"); }
	/** What a Gatekeeper that joined tells the one it joined about managing it: its address and secret, or nothing. */
	static Map<String, Object> offer() {
		if (!leadMay() || setting("bind", "").isEmpty()) return m();
		if (setting("manage_token", "").isEmpty()) { cfg.put("manage_token",
				WebPanel.random(32)); try { ServerTools.saveCfg(); } catch (IOException e) { return m(); } }
		return m("url", Tls.scheme() + "://" + setting("bind", "") + ":" + port(), "token", setting("manage_token", ""), "fp", Tls.on() ? Tls.fingerprint() : "");
	}
	/** The Gatekeeper that leads keeps (or forgets) what a member said about managing it. */
	static void noted(String id, Map<String, Object> said) {
		if (!said.containsKey("manage")) return;
		Map<String, Object> o = Json.map(said.get("manage"));
		String url = Json.str(o.get("url")), token = Json.str(o.get("token"));
		try {
			synchronized (HostHelper.class) {
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(id));
				if (d.isEmpty() || (url.equals(Json.str(d.get("manage_url"))) && token.equals(Json.str(d.get("manage_token"))))) return;
				if (url.isEmpty() || token.isEmpty() || !url.matches("https?://[A-Za-z0-9.\\-]+:\\d{2,5}")) { d.remove("manage_url"); d.remove("manage_token"); }
				else { d.put("manage_url", url); d.put("manage_token", token); if (url.startsWith("https:")) dev.octoberjourney.popup.Pins.pin(dev.octoberjourney.popup.Pins.hostPort(url), Json.str(o.get("fp"))); }
				saveDevices(devs);
			}
		} catch (IOException ignored) { Quiet.seen(ignored); }
	}

	private static void refuse(HttpExchange x, String why) throws IOException { reply(x, 403, m("result", "refused", "message", why)); }
	/** /v1/manage/<call>. d: the asking Gatekeeper's entry here when it asked with the identity it joined with; null
	 *  when it asked with the secret this Gatekeeper gave the one it joined. */
	static void serve(HttpExchange x, String path, Map<String, Object> d) throws IOException {
		String who;
		if (d == null) {
			String token = x.getRequestHeaders().getFirst("X-Manage");
			if (!leadMay() || setting("manage_token", "").isEmpty() || token == null
					|| !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), setting("manage_token",
					"").getBytes(StandardCharsets.UTF_8))) { refuse(x, "This Gatekeeper isn't letting that one manage it (its Constellation page says)."); return; }
			who = AutoUpdate.leadName.isEmpty() ? "the Gatekeeper this one joined" : AutoUpdate.leadName;
		} else {
			if (!ServerTools.yes(d.get("constellation")) || !ServerTools.yes(d.get("manage"))
					|| !Json.str(d.get("kind")).equals("gatekeeper")) { refuse(x, "This Gatekeeper isn't letting that one manage it (its Constellation page says)."); return; }
			who = who(d);
		}
		String method = x.getRequestMethod(), call = path.substring("/v1/manage/".length()), need = WebPanel.ALLOWED.get(method + " " + call);
		if (need == null || !MAY.contains(need)) { refuse(x, "That is done at that Gatekeeper's own machine."); return; }
		String body = null;
		if (method.equals("POST")) {
			Map<String, Object> b;
			try (InputStream in = x.getRequestBody()) { b = Json.map(Json.parse(new String(in.readNBytes(200_000), StandardCharsets.UTF_8))); }
			for (String k : WebPanel.MACHINE_ONLY) if (b.containsKey(k) && !Json.str(b.get(k)).isEmpty()) { refuse(x, "\"" + k
					+ "\" can only be set at that Gatekeeper's own machine."); return; }
			body = Json.write(b);
		}
		String server = serverIn(x.getRequestURI().getQuery());
		String answer = WebPanel.local(method, "/admin/" + call + (server.isEmpty() ? "" : "?server=" + server), body);
		if (method.equals("POST") && !call.equals("tools/console") && !call.equals("chat")) log("managed by " + who + ": " + call
				+ (server.isEmpty() ? "" : " (" + server + ")"));
		byte[] out = answer.getBytes(StandardCharsets.UTF_8);
		x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		x.sendResponseHeaders(200, out.length);
		try (java.io.OutputStream o = x.getResponseBody()) { o.write(out); }
	}

	/** R74: the Gatekeepers this one may manage: the one it joined (if that one lets it) and each that joined it and lets
	 *  it. Each: {key ("lead" or a launcher id here), name, address, servers, online, answered}; servers and online come
	 *  from asking that Gatekeeper (its overview, which managing may read), a couple of seconds at most, all at once. */
	static List<Object> manageable() {
		List<Object[]> want = new java.util.ArrayList<>();   // {key, name, url, {header, value, ...}}
		if (!setting("constellation_lead", "").isEmpty() && leadLets)
			want.add(new Object[] {"lead", AutoUpdate.leadName.isEmpty() ? "The Gatekeeper this one joined" : AutoUpdate.leadName,
					setting("constellation_lead", ""), new String[] {"X-Device", setting("constellation_id", ""), "X-Token", setting("constellation_token", "")}});
		for (Map.Entry<String, Object> e : devices().entrySet()) {
			Map<String, Object> d = Json.map(e.getValue());
			if (!Json.str(d.get("manage_url")).isEmpty() && !Json.str(d.get("manage_token")).isEmpty())
				want.add(new Object[] {e.getKey(), who(d).replace(" (Gatekeeper)", ""), Json.str(d.get("manage_url")), new String[] {"X-Manage", Json.str(d.get("manage_token"))}});
		}
		List<java.util.concurrent.CompletableFuture<Map<String, Object>>> asks = new java.util.ArrayList<>();
		for (Object[] w : want) asks.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			String url = ((String) w[2]).replaceAll("/+$", "");
			Map<String, Object> out = m("key", w[0], "name", w[1], "address", url.replaceFirst("^https?://", ""), "answered", false, "servers", 0, "online", 0);
			try {
				java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url + "/v1/manage/overview").openConnection();
				c.setConnectTimeout(2000); c.setReadTimeout(2500);
				String[] h = (String[]) w[3];
				for (int i = 0; i + 1 < h.length; i += 2) c.setRequestProperty(h[i], h[i + 1]);
				if (c.getResponseCode() == 200) {
					String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
					List<Object> sv = Json.list(Json.map(Json.parse(body)).get("servers"));
					int on = 0;
					for (Object o : sv) { Map<String, Object> s = Json.map(o); if (ServerTools.yes(s.get("running"))) on += (int) Double.parseDouble(String.valueOf(s.getOrDefault("players", 0))); }
					out.put("answered", true); out.put("servers", sv.size()); out.put("online", on);
					out.put("device", Json.str(Json.map(Json.parse(body)).get("device")));   // (R74: "" from a Gatekeeper older than 3.48.1)
				} else out.put("why", c.getResponseCode() == 403 ? "it no longer lets this one manage it" : "it answered " + c.getResponseCode());
			} catch (IOException | RuntimeException ex) { out.put("why", "it isn't answering"); }
			return out;
		}));
		List<Object> all = new java.util.ArrayList<>();
		for (java.util.concurrent.CompletableFuture<Map<String, Object>> a : asks) all.add(a.join());
		return all;
	}

	/** GET /admin/manage/list (R74) and POST /admin/manage/open: a window of this program, on the other Gatekeeper. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/manage/list") && x.getRequestMethod().equals("GET")) {
			reply(x, 200, m("gatekeepers", manageable(), "name", setting("name", ""), "device", Device.kind(), "icon", Icons.tag("gatekeeper"),
					"joined", !setting("constellation_lead", "").isEmpty(), "address", setting("bind", "").isEmpty() ? "" : setting("bind", "") + ":" + port()));
			return true;
		}
		if (!path.equals("/admin/manage/open") || !x.getRequestMethod().equals("POST")) return false;
		Map<String, Object> b = body(x);
		String url, auth, name;
		if (ServerTools.yes(b.get("lead"))) {
			if (setting("constellation_lead", "").isEmpty() || !leadLets) { reply(x, 200, m("result", "refused", "message",
					"The Gatekeeper this one joined hasn't let this one manage it. Its owner does that on its Constellation page.")); return true; }
			url = setting("constellation_lead", "");
			auth = "X-Device=" + setting("constellation_id", "") + "\nX-Token=" + setting("constellation_token", "");
			name = AutoUpdate.leadName;
		} else {
			Map<String, Object> d = Json.map(devices().get(Json.str(b.get("id"))));
			if (Json.str(d.get("manage_url")).isEmpty() || Json.str(d.get("manage_token")).isEmpty()) { reply(x, 200, m("result",
					"refused", "message", "That Gatekeeper hasn't let this one manage it. Its owner does that on its Constellation page.")); return true; }
			url = Json.str(d.get("manage_url")); auth = "X-Manage=" + Json.str(d.get("manage_token")); name = who(d);
		}
		try {
			String javaExe = ProcessHandle.current().info().command().orElse("javaw");
			String jar = java.nio.file.Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
			ProcessBuilder pb = new ProcessBuilder(List.of(javaExe, "-jar", jar, "--panel"));
			pb.environment().put("OJ_PANEL_REMOTE", url.replaceAll("/+$", ""));
			pb.environment().put("OJ_PANEL_REMOTE_AUTH", auth);
			pb.environment().put("OJ_PANEL_REMOTE_NAME", name.replace(" (Gatekeeper)", ""));
			pb.environment().remove("OJ_HOST_DIR");
			if (System.getenv("OJ_MANAGE_DRY") != null) { reply(x, 200, m("result", "ok", "message", "Would open a window on " + name
					+ " at " + url + ".")); return true; }
			pb.start();
			log("managing: a window was opened on " + name);
			reply(x, 200, m("result", "ok", "message", "Opening a window on " + name.replace(" (Gatekeeper)", "") + "."));
		} catch (Exception e) { reply(x, 200, m("result", "failed", "message", "Couldn't open it: " + e)); }
		return true;
	}
}
