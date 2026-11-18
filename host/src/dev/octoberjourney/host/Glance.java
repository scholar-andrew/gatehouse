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
 * card for each machine of its constellation, with the halves of the program it has: its Gatehouse (the launcher: in
 * game and on which server, in the launcher, or offline; version and update mode; its ping to this Gatekeeper), its
 * Gatekeeper (online or not, version, update mode), or both; and the machine's battery, whether it hands out pack
 * updates, and when it was last seen. A launcher and the Gatekeeper beside it are one machine as Systems works it out.
 * Looking only; the panel needs "See the constellation" for it.
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
		Map<String, Map<String, Object>> rows;
		try { rows = Systems.rows(); } catch (RuntimeException e) { rows = new LinkedHashMap<>(); }   // (and Systems.systemOf with it)
		String mine = Systems.me();
		Map<String, Object> con = AutoUpdate.constellation(), g = Json.map(con.get("gatekeeper"));
		String ownBattery = dev.octoberjourney.popup.PackShare.battery();
		Map<String, Object> me = m("name", Json.str(g.get("name")), "system", Json.str(g.get("system")), "version", Json.str(g.get("version")),
				"newest", Json.str(g.get("newest")), "update_ready", AutoUpdate.newer(), "mode", Json.str(g.get("mode")), "status",
				Json.str(g.get("status")), "hands_out", Handout.ownWords(), "requests", asked, "servers", servers, "battery", ownBattery);
		Map<String, Object> devs = devices();
		Map<String, String> online = onlineNow();
		long now = System.currentTimeMillis();
		// One card per machine. This machine's card is the Gatekeeper at the top; its own launcher joins it there.
		Map<String, Map<String, Object>> machines = new LinkedHashMap<>();
		Map<String, String> keeperMachine = new LinkedHashMap<>();   // (a joined Gatekeeper's name -> its machine, for the launchers through it)
		for (Object o : Json.list(con.get("clients"))) {
			Map<String, Object> c = Json.map(o);
			String id = Json.str(c.get("id")), type = Json.str(c.get("type")), via = Json.str(c.get("via"));
			boolean keeper = type.equals("Gatekeeper");
			Map<String, Object> d = Json.map(devs.get(id));
			AutoUpdate.Rep r = id.isEmpty() ? null : AutoUpdate.seen.get(id);
			boolean fresh = r != null && now - r.at < 25_000;
			// which machine
			String key;
			if (Boolean.TRUE.equals(c.get("here"))) key = mine;
			else if (!id.isEmpty()) key = Systems.systemOf.getOrDefault(id, id);
			else if (!Json.str(c.get("local_of")).isEmpty()) key = Systems.systemOf.getOrDefault(Json.str(c.get("local_of")), Json.str(c.get("local_of")));
			else key = Json.str(c.get("install")).isEmpty() ? "via:" + via + ":" + Json.str(c.get("name")) : Json.str(c.get("install"));
			if (keeper) keeperMachine.put(Json.str(c.get("name")), key);
			Map<String, Object> row = rows.get(key);
			Map<String, Object> mc = machines.computeIfAbsent(key, k -> m("key", k, "name", "", "system", "", "here", k.equals(mine), "via", "",
					"gatehouse", "", "gatekeeper", "", "battery", "", "hands_out", "", "last_seen", "", "membership", ""));
			// where it is now
			String where;
			if (keeper) where = Boolean.TRUE.equals(c.get("open")) ? "Online" : "Offline";
			else if (!d.isEmpty()) where = presence(id, d, online);
			else where = !Json.str(c.get("now")).isEmpty() ? Json.str(c.get("now")) : Boolean.TRUE.equals(c.get("open")) ? "In the launcher" : "Offline";
			String player = Json.str(c.get("player"));
			int ping = fresh ? r.ping : num(c.get("ping"));
			String battery = fresh ? r.battery : Json.str(c.get("battery"));
			Map<String, Object> half = m("name", Json.str(c.get("name")), "now", where, "server", where.equals("In game") ? playingOn.getOrDefault(player, "") : "",
					"version", Json.str(c.get("version")), "mode", Json.str(c.get("mode")), "player", player, "ping", where.equals("Offline") ? 0 : ping,
					"system", Json.str(c.get("system")));
			mc.put(keeper ? "gatekeeper" : "gatehouse", half);
			// (the machine goes by its Gatekeeper's name when it has one, as Systems does)
			if (keeper || Json.str(mc.get("name")).isEmpty()) mc.put("name", row != null && !Json.str(row.get("name")).isEmpty() ? Json.str(row.get("name")) : Json.str(c.get("name")));
			if (Json.str(mc.get("system")).isEmpty() || keeper) mc.put("system", Json.str(c.get("system")));
			if (!battery.isEmpty() && !where.equals("Offline") && (Json.str(mc.get("battery")).isEmpty() || !keeper)) mc.put("battery", battery);
			if (!Json.str(c.get("hands_out")).isEmpty()) mc.put("hands_out", Json.str(c.get("hands_out")));
			if (Json.str(mc.get("last_seen")).compareTo(Json.str(c.get("last_seen"))) < 0) mc.put("last_seen", Json.str(c.get("last_seen")));
			if (!via.isEmpty() && Json.str(mc.get("via")).isEmpty()) mc.put("via", via);
			if (!Json.str(c.get("membership")).isEmpty()) mc.put("membership", Json.str(c.get("membership")));
		}
		// (a machine reached both directly and through a joined Gatekeeper: not marked "via")
		for (Map<String, Object> mc : machines.values()) if (mc.get("gatekeeper") instanceof Map || keeperMachine.containsValue(Json.str(mc.get("key")))) mc.put("via", "");
		Map<String, Object> here = machines.remove(mine);
		if (here != null) me.put("gatehouse", here.get("gatehouse"));
		return m("gatekeeper", me, "machines", new ArrayList<>(machines.values()));
	}
	private static int num(Object o) { try { return Math.max(0, (int) Double.parseDouble(Json.str(o))); } catch (RuntimeException e) { return 0; } }
}
