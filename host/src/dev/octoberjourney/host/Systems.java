package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
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

/**
 * Systems, and how this one stands with each of the others.
 *
 * A system is what one machine has of the program: its Gatehouse (the launcher), its Gatekeeper, or both. Neither
 * needs the other. When both are there and the launcher has linked itself to the Gatekeeper beside it (LocalLink),
 * they are one system under one id: the Gatekeeper's (the id it also signs the history of servers with), which the
 * launcher takes on and gives wherever it reports. A machine with only a launcher goes by the launcher's own id.
 *
 * To this system, every other one is:
 *   a neighbor    in a constellation with it right now: this Gatekeeper's launchers that it has let in, Gatekeepers
 *                 that joined and were allowed in (and their launchers), the Gatekeeper this one joined, and what
 *                 that one counts as its constellation
 *   remembered    one the owner chose to keep, which can only be done while it is a neighbor. It stays remembered
 *                 after the constellation is left, with its name and, when it has a Gatekeeper, that one's address
 *   unknown       anything else: a launcher that has introduced itself here and been let in nowhere
 * A system can be a neighbor and remembered at once. Remembering is one-sided: each side decides for itself, each is
 * shown that the other remembers it, and it gives the other nothing: no permission, no whitelist, no pack.
 *
 * Kept in systems.json beside the settings ("remembered", and "remembered_by" for who was last seen to remember this
 * one). A launcher on a machine with no Gatekeeper keeps its own list (in its own folder) and says what it remembers
 * when it reports.
 *
 *   POST /v1/systems            a launcher, or a Gatekeeper that joined: {machine, name, device, address, gatehouse,
 *                               remembers, systems}; answers the systems of this constellation, for one that is in it
 *   POST /v1/systems/remember   this machine's own launcher only: {id, forget}
 *   GET  /admin/systems, POST /admin/systems/remember {id, forget}   Gatekeeper's own window
 */
final class Systems {
	private Systems() {}
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final Map<String, Map<String, Object>> told = new ConcurrentHashMap<>();   // (device id -> what it last reported, and when)
	private static volatile List<Object> leadRows = new ArrayList<>();
	/** R42: which system (machine) each device this Gatekeeper knows belongs to, as rows() last worked it out. */
	static final Map<String, String> systemOf = new ConcurrentHashMap<>();
	private static volatile long leadAsked = 0, leadHeard = 0;

	static String me() { return History.me(); }
	private static String id(Object o) { String s = Json.str(o).toLowerCase(java.util.Locale.ROOT); return s.matches("[0-9a-f\\-]{16,64}") ? s : ""; }
	private static String word(Object o, int most) { String s = Json.str(o).replaceAll("[\\p{Cntrl}<>]",
			" ").trim(); return s.length() > most ? s.substring(0, most) : s; }
	private static String ownAddress() { String b = setting("bind", ""); return b.isEmpty() ? "" : b + ":" + port(); }

	// ------------------------------------------------------------------ what is kept

	private static Path file() { return dir.resolve("systems.json"); }
	private static synchronized Map<String, Object> store() {
		Map<String, Object> s;
		try { s = Json.map(Json.parse(Files.readString(file(), StandardCharsets.UTF_8))); } catch (IOException | RuntimeException e) { s = new LinkedHashMap<>(); }
		if (!(s.get("remembered") instanceof Map<?, ?>)) s.put("remembered", new LinkedHashMap<String, Object>());
		if (!(s.get("remembered_by") instanceof Map<?, ?>)) s.put("remembered_by", new LinkedHashMap<String, Object>());
		return s;
	}
	private static void save(Map<String, Object> s) { try { Files.writeString(file(), Json.write(s),
			StandardCharsets.UTF_8); } catch (IOException e) { log("systems: couldn't be saved: " + e.getMessage()); } }
	static List<Object> remembers() { return new ArrayList<>(Json.map(store().get("remembered")).keySet()); }

	// ------------------------------------------------------------------ who is here

	/** Is this launcher part of this Gatekeeper's constellation: let in somewhere, not just known? */
	static boolean inside(Map<String, Object> d) {
		if (ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin")) || Boolean.TRUE.equals(d.get("granted"))) return true;
		if (Json.str(d.get("kind")).equals("gatekeeper")) return ServerTools.yes(d.get("constellation"));
		for (Object o : servers()) if (Invites.belongs(d, Json.map(o))) return true;
		return false;
	}
	private static Map<String, Object> row(String sid, String name, String device) {
		return m("id", sid, "name", name, "device", device, "gatehouse", false, "gatekeeper", false, "address", "", "neighbor", false,
				"unknown", false, "this", false, "via", "", "remembers", new ArrayList<>(), "open", false, "last_seen", "", "halves", new ArrayList<>());
	}
	@SuppressWarnings("unchecked") private static void addAll(Map<String, Object> row, Object remembers) { for (Object o
			: Json.list(remembers)) if (!id(o).isEmpty() && !((List<Object>) row.get("remembers")).contains(id(o))
			&& ((List<Object>) row.get("remembers")).size() < 300) ((List<Object>) row.get("remembers")).add(id(o)); }

	/** Every system this Gatekeeper knows of, by id, as it stands to this one. */
	static Map<String, Map<String, Object>> rows() {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		String mine = me();
		long now = System.currentTimeMillis();
		Map<String, Object> self = row(mine, AutoUpdate.myName(), Device.kind());
		self.put("gatekeeper", true); self.put("address", ownAddress()); self.put("this", true); self.put("open", true);
		addAll(self, remembers());
		out.put(mine, self);
		Map<String, Object> devs = devices();
		self.put("halves", new ArrayList<Object>(halves(devs)));
		// The same machine under another id: a launcher goes by its own id until it has heard, from the Gatekeeper
		// beside it, which system it is part of. The Gatekeeper already knows which launcher is its machine's own
		// (LocalLink), and one that joined says which is its own ("halves"), so those ids are read as the system's.
		Map<String, String> same = new LinkedHashMap<>();
		for (String h : halves(devs)) same.put(h, mine);
		// (and the Gatekeeper this one joined says the same of itself and of the others that joined it)
		if (AutoUpdate.leadMember && now - leadHeard < 180_000) for (Object o : leadRows) { Map<String,
				Object> c = Json.map(o); if (!id(c.get("id")).isEmpty()) for (Object h : Json.list(c.get("halves"))) if (!id(h).isEmpty()
				&& !same.containsKey(id(h))) same.put(id(h), id(c.get("id"))); }
		for (Map.Entry<String, Object> e : devs.entrySet()) {
			Map<String, Object> d = Json.map(e.getValue()), t = told.get(e.getKey());
			if (!Json.str(d.get("kind")).equals("gatekeeper") || t == null) continue;
			String sid = id(d.get("machine"));
			if (!sid.isEmpty()) for (Object h : Json.list(t.get("halves"))) if (!id(h).isEmpty() && !same.containsKey(id(h))) same.put(id(h), sid);
		}
		for (Map.Entry<String, Object> e : devs.entrySet()) {
			Map<String, Object> d = Json.map(e.getValue()), t = told.get(e.getKey());
			boolean keeper = Json.str(d.get("kind")).equals("gatekeeper");
			if (ServerTools.yes(d.get("local"))
					&& !keeper) {   // (this machine's own launcher: the other half of this system, and the systems it meets at other Gatekeepers are this system's neighbors too)
				self.put("gatehouse", true);
				systemOf.put(e.getKey(), mine);
				if (t != null) subs(out, mine, mine, "this machine's launcher", t, same);
				continue;
			}
			String sid = id(d.get("machine"));
			if (sid.isEmpty()) sid = id(d.get("install"));
			if (sid.isEmpty()) sid = id(e.getKey());
			if (!keeper) { if (same.containsKey(id(d.get("install")))) sid = same.get(id(d.get("install"))); else if (same.containsKey(id(e.getKey()))) sid = same.get(id(e.getKey())); else sid = same.getOrDefault(sid, sid); }
			if (sid.isEmpty()) continue;
			systemOf.put(e.getKey(), sid);
			if (sid.equals(mine)) { if (!keeper) self.put("gatehouse", true); continue; }
			boolean in = inside(d) && banOf(d)[0].isEmpty();
			Map<String, Object> r = out.computeIfAbsent(sid, k -> row(k, "", ""));
			String nm = word(Json.str(d.get("name")).replace(" (Gatekeeper)", ""), 60);
			if (Json.str(r.get("name")).isEmpty() || keeper) r.put("name", nm);
			if (keeper && t != null) r.put("halves", Json.list(t.get("halves")));
			if (keeper) { r.put("gatekeeper", true); if (t != null && !Json.str(t.get("address")).isEmpty()) r.put("address",
					Json.str(t.get("address"))); if (t != null && ServerTools.yes(t.get("gatehouse"))) r.put("gatehouse", true); }
			else r.put("gatehouse", true);
			if (t != null && !Json.str(t.get("device")).isEmpty()) r.put("device", Json.str(t.get("device")));
			if (Json.str(r.get("device")).isEmpty()) r.put("device", word(d.get("os"), 20));
			if (in) r.put("neighbor", true);
			if (now - contact.getOrDefault(e.getKey(), 0L) < 30_000) r.put("open", true);
			if (t != null) addAll(r, t.get("remembers"));
			// (a Gatekeeper that joined also tells of the systems on its side: its own launchers)
			if (HostHelper.seenAt(r, "last_seen") < HostHelper.seenAt(d, "last_seen")) { r.put("last_seen", Json.str(d.get("last_seen"))); r.put("last_seen_at", HostHelper.seenAt(d, "last_seen")); }
			if (keeper && in && t != null) subs(out, mine, sid, nm, t, same);
		}
		// what the Gatekeeper this one joined counts as its constellation (while this one is a member there, and it answered lately)
		if (AutoUpdate.leadMember && now - leadHeard < 180_000) for (Object o : leadRows) {
			Map<String, Object> c = Json.map(o);
			String cid = same.getOrDefault(id(c.get("id")), id(c.get("id")));
			if (cid.isEmpty()) continue;
			if (cid.equals(mine)) { if (ServerTools.yes(c.get("gatehouse"))) self.put("gatehouse", true); continue; }
			Map<String, Object> cr = out.computeIfAbsent(cid, k -> row(k, word(c.get("name"), 60), word(c.get("device"), 20)));
			if (Json.str(cr.get("name")).isEmpty()) cr.put("name", word(c.get("name"), 60));
			if (ServerTools.yes(c.get("gatehouse"))) cr.put("gatehouse", true);
			// (a system with a Gatekeeper goes by the name its owner gave that Gatekeeper, not its launcher's machine name)
			if (ServerTools.yes(c.get("gatekeeper"))) { if (!ServerTools.yes(cr.get("gatekeeper")) && !word(c.get("name"),
					60).isEmpty()) cr.put("name", word(c.get("name"), 60)); cr.put("gatekeeper",
					true); if (Json.str(cr.get("address")).isEmpty()) cr.put("address", word(c.get("address"), 60)); }
			cr.put("neighbor", true);
			if (ServerTools.yes(c.get("open"))) cr.put("open", true);
			addAll(cr, c.get("remembers"));
		}
		// how each stands to this system; and the remembered ones that aren't here now
		Map<String, Object> store = store(), kept = Json.map(store.get("remembered")), by = Json.map(store.get("remembered_by"));
		boolean changed = false;
		for (Map<String, Object> r : out.values()) {
			if (ServerTools.yes(r.get("this"))) continue;
			String sid = Json.str(r.get("id"));
			boolean neighbor = ServerTools.yes(r.get("neighbor")), theyDo = Json.list(r.get("remembers")).contains(mine);
			r.put("unknown", !neighbor && !kept.containsKey(sid));
			if (neighbor) {   // (seen now: what is kept about it is brought up to date)
				if (theyDo && !by.containsKey(sid)) { by.put(sid, m("name", r.get("name"), "since", dev.gatehouse.look.Times.oldText(dev.gatehouse.look.Times.now()), "since_at", dev.gatehouse.look.Times.now())); changed = true; }
				if (!theyDo && by.containsKey(sid)) { by.remove(sid); changed = true; }
				if (kept.containsKey(sid)) { Map<String, Object> k = Json.map(kept.get(sid)); String sig = Json.str(k.get("name")) + "|"
						+ Json.str(k.get("address")) + "|" + Json.str(k.get("device")); fill(k, r); if (!sig.equals(Json.str(k.get("name"))
						+ "|" + Json.str(k.get("address")) + "|" + Json.str(k.get("device")))) changed = true; }
			}
			r.put("remembered", kept.containsKey(sid));
			r.put("remembers_you", by.containsKey(sid));
		}
		for (Map.Entry<String, Object> e : kept.entrySet()) if (!out.containsKey(e.getKey())) {
			Map<String, Object> k = Json.map(e.getValue()), r = row(e.getKey(), Json.str(k.get("name")), Json.str(k.get("device")));
			r.put("gatehouse", ServerTools.yes(k.get("gatehouse")));
			r.put("gatekeeper", ServerTools.yes(k.get("gatekeeper")));
			r.put("address", Json.str(k.get("address")));
			r.put("remembered", true);
			r.put("remembers_you", by.containsKey(e.getKey()));
			r.put("since", Json.str(k.get("since")));
			r.put("since_at", HostHelper.seenAt(k, "since"));
			r.put("last_seen", Json.str(k.get("last_seen")));
			r.put("last_seen_at", HostHelper.seenAt(k, "last_seen"));
			out.put(e.getKey(), r);
		}
		if (changed) save(store);
		return out;
	}
	/** The ids this machine's own launcher goes by: they all mean this system. */
	private static List<String> halves(Map<String, Object> devs) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Object> e : devs.entrySet()) {
			Map<String, Object> d = Json.map(e.getValue());
			if (!ServerTools.yes(d.get("local")) || Json.str(d.get("kind")).equals("gatekeeper")) continue;
			for (String h : new String[] {id(e.getKey()), id(d.get("install"))}) if (!h.isEmpty() && !out.contains(h)) out.add(h);
		}
		return out;
	}
	private static void subs(Map<String, Map<String, Object>> out, String mine, String sid, String through, Map<String, Object> t, Map<String, String> same) {
		for (Object o : Json.list(t.get("systems"))) {
			Map<String, Object> c = Json.map(o);
			String cid = same.getOrDefault(id(c.get("id")), id(c.get("id")));
			if (cid.isEmpty() || cid.equals(mine) || cid.equals(sid)) continue;
			Map<String, Object> cr = out.computeIfAbsent(cid, k -> row(k, word(c.get("name"), 60), word(c.get("device"), 20)));
			if (Json.str(cr.get("name")).isEmpty()) cr.put("name", word(c.get("name"), 60));
			if (ServerTools.yes(c.get("gatehouse"))) cr.put("gatehouse", true);
			// (a system with a Gatekeeper goes by the name its owner gave that Gatekeeper, not its launcher's machine name)
			if (ServerTools.yes(c.get("gatekeeper"))) { if (!ServerTools.yes(cr.get("gatekeeper")) && !word(c.get("name"),
					60).isEmpty()) cr.put("name", word(c.get("name"), 60)); cr.put("gatekeeper",
					true); if (Json.str(cr.get("address")).isEmpty()) cr.put("address", word(c.get("address"), 60)); }
			if (!ServerTools.yes(cr.get("neighbor")) && Json.str(cr.get("via")).isEmpty()) cr.put("via", through);
			cr.put("neighbor", true);
			addAll(cr, c.get("remembers"));
		}
	}
	private static void fill(Map<String, Object> k, Map<String, Object> r) {
		k.put("name", r.get("name")); k.put("device", r.get("device")); k.put("gatehouse", r.get("gatehouse")); k.put("gatekeeper", r.get("gatekeeper"));
		if (!Json.str(r.get("address")).isEmpty()) k.put("address", r.get("address"));
		long day = dev.gatehouse.look.Times.now() / 86_400_000 * 86_400_000;   // (to the day, UTC: this is written often, and saved only when it changes)
		k.put("last_seen", dev.gatehouse.look.Times.oldText(day).substring(0, 10)); k.put("last_seen_at", day);
	}
	static String status(Map<String, Object> r) {
		if (ServerTools.yes(r.get("this"))) return "This system";
		boolean n = ServerTools.yes(r.get("neighbor")), k = ServerTools.yes(r.get("remembered"));
		return (n && k ? "Neighbor, remembered" : n ? "Neighbor" : k ? "Remembered" : "Unknown") + (ServerTools.yes(r.get("remembers_you"))
				? " (remembers this system)" : "");
	}

	/** Remembers a system (only one that is a neighbor right now), or forgets one. */
	static synchronized String[] remember(String sid, boolean forget) {
		sid = id(sid);
		Map<String, Object> store = store(), kept = Json.map(store.get("remembered"));
		if (forget) {
			Map<String, Object> was = Json.map(kept.remove(sid));
			if (was.isEmpty()) return new String[] {"refused", "That system isn't remembered."};
			save(store);
			log("systems: " + Json.str(was.get("name")) + " is no longer remembered");
			return new String[] {"ok", Json.str(was.get("name")) + " is no longer remembered."};
		}
		Map<String, Object> r = rows().get(sid);
		if (r == null || ServerTools.yes(r.get("this"))) return new String[] {"refused", "No such system."};
		if (!ServerTools.yes(r.get("neighbor"))) return new String[] {"refused",
				"A system can be remembered while it is in a constellation with this one. " + Json.str(r.get("name")) + " isn't right now."};
		if (kept.containsKey(sid)) return new String[] {"ok", Json.str(r.get("name")) + " is already remembered."};
		if (kept.size() >= 300) return new String[] {"refused", "There are already 300 remembered systems: forget one first."};
		Map<String, Object> k = m("since", dev.gatehouse.look.Times.oldText(dev.gatehouse.look.Times.now()), "since_at", dev.gatehouse.look.Times.now());
		fill(k, r);
		kept.put(sid, k);
		save(store);
		log("systems: " + Json.str(r.get("name")) + " is now remembered");
		return new String[] {"ok", Json.str(r.get("name"))
				+ " is remembered. It stays in this list after the constellation is left. It gives that system nothing: no permission, no whitelist, no pack."};
	}

	// ------------------------------------------------------------------ told and telling

	/** What another client is told: the systems of this constellation other than its own. */
	private static List<Object> forOthers(String asking) {
		List<Object> out = new ArrayList<>();
		for (Map<String, Object> r : rows().values()) {
			if (Json.str(r.get("id")).equals(asking)) continue;
			if (!ServerTools.yes(r.get("this")) && !ServerTools.yes(r.get("neighbor"))) continue;
			out.add(m("id", r.get("id"), "name", r.get("name"), "device", r.get("device"), "gatehouse", r.get("gatehouse"), "gatekeeper",
					r.get("gatekeeper"), "address", r.get("address"), "open", r.get("open"), "remembers", r.get("remembers"), "halves", r.get("halves")));
		}
		return out;
	}
	/** POST /v1/systems and /v1/systems/remember. */
	static void launcher(HttpExchange x, String path, String devId, Map<String, Object> d, Map<String, Object> devs) throws IOException {
		Map<String, Object> b = body(x);
		boolean local = ServerTools.yes(d.get("local"));
		if (path.equals("/v1/systems/remember")) {
			if (!local) { reply(x, 200, m("result", "refused", "message", "Only this machine's own launcher changes what this system remembers.")); return; }
			String[] r = remember(Json.str(b.get("id")), ServerTools.yes(b.get("forget")));
			reply(x, 200, m("result", r[0], "message", r[1]));
			return;
		}
		// what it says of itself. This machine's own launcher goes by this system's id whatever it says.
		String machine = local ? me() : id(b.get("machine"));
		List<Object> theirs = new ArrayList<>();
		for (Object o : Json.list(b.get("systems"))) { if (theirs.size() >= 60) break; Map<String,
				Object> c = Json.map(o); if (!id(c.get("id")).isEmpty()) theirs.add(m("id", id(c.get("id")), "name", word(c.get("name"),
				60), "device", word(c.get("device"), 20), "gatehouse", ServerTools.yes(c.get("gatehouse")), "gatekeeper",
				ServerTools.yes(c.get("gatekeeper")), "address", word(c.get("address"), 60), "remembers", idList(c.get("remembers")))); }
		told.put(devId, m("device", word(b.get("device"), 20), "address", word(b.get("address"), 60).replaceAll("[^0-9A-Za-z.:\\-\\[\\]]",
				""), "gatehouse", ServerTools.yes(b.get("gatehouse")), "remembers", local ? new ArrayList<>() : idList(b.get("remembers")),
				"systems", theirs, "halves", idList(b.get("halves"))));
		if (!machine.isEmpty() && !machine.equals(Json.str(d.get("machine")))) { d.put("machine", machine); devs.put(devId, d); saveDevices(devs); }
		if (!banOf(d)[0].isEmpty() || !inside(d)) { reply(x, 200, m("result", "refused", "systems", new ArrayList<>(), "message",
				"This launcher isn't part of this Gatekeeper's constellation: its owner hasn't let it in anywhere.")); return; }
		Map<String, Object> out = m("result", "ok", "me", m("id", me(), "name", AutoUpdate.myName()), "you_are_local", local, "systems", forOthers(machine));
		if (local) { List<Object> all = new ArrayList<>(); for (Map<String, Object> r
				: rows().values()) if (!ServerTools.yes(r.get("this"))) { r.put("status", status(r)); all.add(r); } out.put("standing",
				all); }   // (the same system: how each stands to it, remembered ones that aren't here included)
		reply(x, 200, out);
	}
	private static List<Object> idList(Object o) { List<Object> out = new ArrayList<>(); for (Object v : Json.list(o)) if (!id(v).isEmpty()
			&& out.size() < 300) out.add(id(v)); return out; }

	/** With the Gatekeeper this one joined: says what this system is and remembers, and hears who else is there. */
	static void sync() {
		if (AutoUpdate.lead().isEmpty()) { leadRows = new ArrayList<>(); return; }
		long now = System.currentTimeMillis();
		if (now - leadAsked < 30_000) return;
		leadAsked = now;
		try {
			List<Object> mine = new ArrayList<>();   // (this side's own systems: the launchers this Gatekeeper has let in)
			String self = me();
			boolean launcherHere = false;
			for (Map<String, Object> r : rows().values()) {
				if (ServerTools.yes(r.get("this"))) { launcherHere = ServerTools.yes(r.get("gatehouse")); continue; }
				if (!ServerTools.yes(r.get("neighbor")) || !Json.str(r.get("via")).isEmpty()) continue;
				boolean fromLead = false;
				for (Object o : leadRows) if (Json.str(r.get("id")).equals(Json.str(Json.map(o).get("id")))) fromLead = true;
				if (!fromLead && mine.size() < 60) mine.add(m("id", r.get("id"), "name", r.get("name"), "device", r.get("device"),
						"gatehouse", r.get("gatehouse"), "gatekeeper", r.get("gatekeeper"), "address", r.get("address"), "remembers", r.get("remembers")));
			}
			Map<String, Object> r = Json.map(Json.parse(AutoUpdate.leadCall("/v1/systems", Json.write(m("machine", self, "name",
					AutoUpdate.myName(), "device", Device.kind(), "address", ownAddress(), "gatehouse", launcherHere, "remembers",
					remembers(), "systems", mine, "halves", new ArrayList<Object>(halves(devices())))))));
			if (Json.str(r.get("result")).equals("ok")) { leadRows = Json.list(r.get("systems")); leadHeard = now; }
			else leadRows = new ArrayList<>();
		} catch (IOException | RuntimeException ignored) { /* (not answering, or from before systems: next time) */ }
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/systems") && !x.getRequestMethod().equals("POST")) {
			List<Object> all = new ArrayList<>();
			for (Map<String, Object> r : rows().values()) { r.put("status", status(r)); all.add(r); }
			reply(x, 200, m("systems", all, "me", me(), "lead", !AutoUpdate.lead().isEmpty()));
			return true;
		}
		if (path.equals("/admin/systems/remember") && x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			String[] r = remember(Json.str(b.get("id")), ServerTools.yes(b.get("forget")));
			reply(x, 200, m("result", r[0], "message", r[1]));
			return true;
		}
		return false;
	}
}
