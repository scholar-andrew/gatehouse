package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * R42: every Gate System at a glance, for the web panel's Systems page (made for a phone). GET /admin/glance: this
 * Gatekeeper (its servers and who is on them, its version, whether an update is ready, what waits on its owner), and a
 * card's worth for each client of its constellation: where it is now (in game, and on which server; in the launcher;
 * offline), its version and update mode, whether it hands out pack updates, its ping to this Gatekeeper, its battery,
 * and when it was last seen. Looking only; the panel needs "See the constellation" for it.
 */
final class Glance {
	private Glance() {}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/glance") || !x.getRequestMethod().equals("GET")) return false;
		reply(x, 200, glance());
		return true;
	}

	static Map<String, Object> glance() {
		// (who is on which server right now, by each server's own log)
		Map<String, String> playingOn = new LinkedHashMap<>();
		List<Object> servers = new ArrayList<>();
		eachServer((sid, s) -> {
			boolean on = serverPhase().equals("online");
			List<Object> who = new ArrayList<>(on ? onlineBy.getOrDefault(serverKey(), Map.of()).keySet() : List.of());
			for (Object p : who) playingOn.put(Json.str(p), Json.str(s.get("name")));
			servers.add(m("id", sid, "name", Json.str(s.get("name")), "running", on, "phase", serverPhase(), "players", who,
					"perf", ServerWatch.summary(sid)));
		});
		int asked;
		synchronized (HostHelper.class) { asked = requests.size(); }
		Map<String, Object> con = AutoUpdate.constellation(), g = Json.map(con.get("gatekeeper"));
		Map<String, Object> me = m("name", Json.str(g.get("name")), "system", Json.str(g.get("system")), "version", Json.str(g.get("version")),
				"newest", Json.str(g.get("newest")), "update_ready", AutoUpdate.newer(), "mode", Json.str(g.get("mode")), "status",
				Json.str(g.get("status")), "hands_out", Handout.ownWords(), "requests", asked, "servers", servers);
		Map<String, Object> devs = devices();
		Map<String, String> online = onlineNow();
		List<Object> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Object o : Json.list(con.get("clients"))) {
			Map<String, Object> c = Json.map(o);
			String id = Json.str(c.get("id"));
			Map<String, Object> d = Json.map(devs.get(id));
			AutoUpdate.Rep r = id.isEmpty() ? null : AutoUpdate.seen.get(id);
			boolean fresh = r != null && now - r.at < 25_000;
			String where = !d.isEmpty() && !"Gatekeeper".equals(Json.str(c.get("type"))) ? presence(id, d, online)
					: Boolean.TRUE.equals(c.get("open")) ? (fresh || "Gatekeeper".equals(Json.str(c.get("type"))) ? "Online" : "In the launcher") : "Offline";
			if ("Gatekeeper".equals(Json.str(c.get("type"))) && !Boolean.TRUE.equals(c.get("open"))) where = "Offline";
			String player = Json.str(c.get("player"));
			out.add(m("name", Json.str(c.get("name")), "type", Json.str(c.get("type")), "here", Boolean.TRUE.equals(c.get("here")),
					"via", Json.str(c.get("via")), "player", player, "system", Json.str(c.get("system")), "version", Json.str(c.get("version")),
					"mode", Json.str(c.get("mode")), "now", where, "server", where.equals("In game") ? playingOn.getOrDefault(player, "") : "",
					"hands_out", Json.str(c.get("hands_out")), "ping", fresh ? r.ping : 0, "battery", fresh ? r.battery : "",
					"last_seen", Json.str(c.get("last_seen")), "membership", Json.str(c.get("membership"))));
		}
		return m("gatekeeper", me, "systems", out);
	}
}
