package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * A page for friends (R34, 2): what servers this machine has, which are up, and who is playing, with no sign-in. At
 * http://<this machine's Tailscale address>:<Gatekeeper's port>/status, so only those on the owner's Tailscale network
 * can reach it, like everything else of Gatekeeper's. Read-only: it shows names, states and player names, nothing that
 * lets anyone do anything (no addresses, folders, keys or buttons). Off until the owner turns it on (Options).
 */
final class StatusPage {
	private StatusPage() {}

	static boolean on() { return setting("status_page", "false").equals("true"); }

	static void page(HttpExchange x) throws IOException {
		try {
			if (!x.getRequestMethod().equals("GET")) { x.sendResponseHeaders(405, -1); return; }
			if (!on()) { send(x, 404, html("Not shared", "<p class=dim>This Gatekeeper's status page is turned off. Its owner can turn it on in Gatekeeper's Options.</p>")); return; }
			StringBuilder b = new StringBuilder();
			String keep = SERVER.get();
			try {
				for (Object o : servers()) {
					Map<String, Object> s = Json.map(o);
					String id = Json.str(s.get("id"));
					SERVER.set(id);
					if (goneFromMcss(s)) continue;
					String ph = serverPhase();
					boolean up = ph.equals("online");
					java.util.Set<String> who = up ? new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER) : java.util.Set.of();
					if (up) who.addAll(onlineBy.getOrDefault(serverKey(), Map.of()).keySet());
					String state = up ? "Up" : ph.equals("starting") ? "Starting" : ph.equals("stopping") ? "Stopping" : "Down";
					b.append("<section><div class=row><b>").append(esc(Json.str(s.get("name")))).append("</b><span class='st ").append(up ? "up" : ph.equals("starting") || ph.equals("stopping") ? "mid" : "down").append("'>").append(state).append("</span></div>");
					if (up) b.append("<p>").append(who.isEmpty() ? "<span class=dim>Nobody playing right now</span>" : who.size() + " playing: " + esc(String.join(", ", who))).append("</p>");
					b.append("</section>");
				}
			} finally { SERVER.set(keep); }
			String when = java.time.LocalTime.now().withNano(0).toString();
			send(x, 200, html(AutoUpdate.myName(), (b.length() == 0 ? "<p class=dim>No servers here.</p>" : b.toString()) + "<p class=dim>As of " + when + ". This page looks again every 30 seconds.</p>"));
		} catch (RuntimeException e) { send(x, 500, html("Not available", "<p class=dim>The status couldn't be read just now.</p>")); }
	}

	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
	private static String html(String title, String body) {
		return "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><meta http-equiv=refresh content=30>"
				+ "<title>" + esc(title) + "</title><style>:root{color-scheme:dark}body{margin:0;background:#0d141d;color:#e6eef5;font:16px/1.45 system-ui,sans-serif}"
				+ "main{max-width:640px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}section{background:#16202d;border:1px solid #2b3a4c;border-radius:10px;padding:12px 14px;margin:12px 0}"
				+ ".row{display:flex;justify-content:space-between;align-items:center;gap:12px}.st{font-size:13px;font-weight:700;border-radius:999px;padding:2px 10px}"
				+ ".up{background:#1d3b24;color:#8bd46e}.down{background:#3a1f22;color:#ff8a8e}.mid{background:#3a3010;color:#e9b949}.dim{color:#8f98a0}p{margin:6px 0 0}</style></head>"
				+ "<body><main><h1>" + esc(title) + "</h1><p class=dim>Servers on this machine</p>" + body + "</main></body></html>";
	}
	private static void send(HttpExchange x, int code, String page) throws IOException {
		byte[] b = page.getBytes(StandardCharsets.UTF_8);
		x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		x.getResponseHeaders().set("Cache-Control", "no-store");
		x.sendResponseHeaders(code, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}

	/** POST /admin/status-page {on}: turns it on or off. GET: whether it is on, and where. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/status-page")) return false;
		if (x.getRequestMethod().equals("POST")) {
			cfg.put("status_page", ServerTools.yes(body(x).get("on")) ? "true" : "false");
			ServerTools.saveCfg();
			log("status page for friends: " + (on() ? "on" : "off"));
		}
		String bind = setting("bind", "");
		reply(x, 200, m("on", on(), "url", bind.isEmpty() ? "" : "http://" + bind + ":" + port() + "/status"));
		return true;
	}
}
