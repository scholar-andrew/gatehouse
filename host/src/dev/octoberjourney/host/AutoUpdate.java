package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

/**
 * How this Gatekeeper updates (host.json "update_mode"; each launcher has the same choice of its own, in its Options),
 * and the constellation it is part of.
 *
 *   normal         nothing updates until the owner presses Update Gatekeeper. What it is unless changed.
 *   auto           this Gatekeeper updates itself as soon as it sees a newer version, waiting for nobody (only for
 *                  the servers it runs itself to be off: updating restarts it, and their consoles with it).
 *   constellation  the group goes together. When a newer version is out, the Gatekeeper that leads
 *                    1. gathers: waits until every open client in constellation mode has seen the update (at least
 *                       fifteen seconds, so they have all had a turn; at most ten minutes, after which the ones that
 *                       still aren't ready are left to themselves);
 *                    2. waits, if it has to, for the servers it runs itself to be off;
 *                    3. gives the go: those clients are told "go" and update themselves, and a few seconds later it
 *                       updates too, the way Update Gatekeeper does (checked, undone if it fails).
 *                  A client that opens later, still on the old version, is told "go" as soon as it reports.
 *
 * A constellation is a Gatekeeper and every client connected to it: its launchers, and other Gatekeepers that have
 * joined it, with their launchers. A Gatekeeper joins one other Gatekeeper's constellation (host.json
 * "constellation_lead") by introducing itself there like a launcher does, and counts as a member once that
 * Gatekeeper's owner has allowed it in (until then it is only listed as having asked). Joining gives no control of
 * anything: a member reports, and in constellation mode it follows the lead's go instead of deciding by itself. It is
 * ready, for the lead, when it has seen the update, its own constellation-mode launchers have, and no server it runs
 * itself is on. If the lead can't be reached, a member carries on by itself.
 *
 * Clients report every few seconds while they are open (POST /v1/update/state): version, mode, whether they have seen
 * the newer version; a Gatekeeper also its own launchers. That is what the Constellation tab shows.
 */
final class AutoUpdate {
	/** A client's last report. */
	static final class Rep {
		long at; String mode = "", sees = "", own = "", name = "", kind = "launcher", system = "";
		/** The id that install of the program gave itself once: the same whichever Gatekeeper it reports to, or through. */
		String install = "";
		boolean busy, waiting;
		List<Object> clients = new ArrayList<>();
	}
	static final Map<String, Rep> seen = new ConcurrentHashMap<>();
	static volatile long gatherSince = 0, goUntil = 0, notBefore = 0;
	static volatile String status = "";
	private static volatile boolean saidServers = false, stuck = false, launchersWaiting = false;
	// (as a member of another Gatekeeper's constellation: how that stands)
	static volatile String leadState = "", leadName = "", leadSaid = "";
	static volatile boolean leadMember = false;
	static volatile long leadHeard = 0;

	/** normal, auto or constellation. (A host.json from before the modes had "auto_update": true for what is now constellation.) */
	static String mode() {
		String m = setting("update_mode", setting("auto_update", "false").equals("true") ? "constellation" : "normal");
		return m.equals("auto") || m.equals("constellation") ? m : "normal";
	}
	static String lead() { return setting("constellation_lead", ""); }
	static boolean following() { return !lead().isEmpty() && leadMember && System.currentTimeMillis() - leadHeard < 120_000; }
	static boolean newer() { return !newestKeeper.isEmpty() && cmpVersion(newestKeeper, ownVersion()) > 0; }
	static String modeOf(Map<String, Object> b) {
		String m = Json.str(b.get("mode"));
		if (m.equals("auto") || m.equals("constellation") || m.equals("normal")) return m;
		return ServerTools.yes(b.get("auto")) ? "constellation" : "normal";   // (a launcher from before the modes)
	}
	static String myName() {
		String name = setting("name", "");
		if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Gatekeeper"; }
		return name;
	}

	/** A client reporting in; what it is told. */
	static Map<String, Object> report(String id, String name, Map<String, Object> b) {
		Rep r = new Rep();
		r.at = System.currentTimeMillis(); r.mode = modeOf(b); r.name = name;
		r.own = Json.str(b.get("own")).replaceAll("[^\\w.\\-]", ""); r.sees = Json.str(b.get("sees")).replaceAll("[^\\w.\\-]", "");
		boolean keeper = Json.str(b.get("kind")).equals("gatekeeper");
		r.kind = keeper ? "gatekeeper" : "launcher";
		r.system = clean(Json.str(b.get("system")), 20).replace("<", "");
		r.busy = ServerTools.yes(b.get("busy")); r.waiting = ServerTools.yes(b.get("waiting"));
		r.install = installOf(b);
		if (keeper) for (Object o : Json.list(b.get("clients"))) {   // (its own launchers, for the lead's list: kept as plain words)
			Map<String, Object> c = Json.map(o);
			if (r.clients.size() < 60) r.clients.add(m("name", clean(Json.str(c.get("name")), 40).replace("<", ""), "player", clean(Json.str(c.get("player")), 16).replaceAll("\\W", ""), "system", clean(Json.str(c.get("system")), 20).replace("<", ""),
					"version", Json.str(c.get("version")).replaceAll("[^\\w.\\-]", ""), "mode", modeOf(c), "open", ServerTools.yes(c.get("open")), "install", installOf(c)));
		}
		seen.put(id, r);
		// (its kind, mode and version are kept with what else is known of it, so the Constellation tab still has them
		// when the client is closed, or this Gatekeeper has restarted; written only when one of them changed)
		boolean allowed = !keeper;
		try {
			synchronized (HostHelper.class) {
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(id));
				if (!d.isEmpty()) {
					allowed = !keeper || ServerTools.yes(d.get("constellation"));
					if (!(r.mode.equals(Json.str(d.get("update_mode"))) && r.own.equals(Json.str(d.get("version"))) && r.install.equals(Json.str(d.get("install"))) && r.kind.equals(Json.str(d.get("kind")).isEmpty() ? "launcher" : Json.str(d.get("kind"))))) {
						d.put("update_mode", r.mode); d.put("version", r.own); if (keeper) d.put("kind", "gatekeeper"); else d.remove("kind");
						if (!r.install.isEmpty()) d.put("install", r.install);
						saveDevices(devs);
						if (keeper && !ServerTools.yes(d.get("constellation")) && !ServerTools.yes(d.get("asked_said"))) { d.put("asked_said", true); saveDevices(devs); log("the Gatekeeper " + name + " asks to join this Gatekeeper's constellation: allow it on the Constellation tab"); }
					}
				}
			}
		} catch (IOException ignored) {}
		String state = "idle";
		if (r.mode.equals("constellation") && allowed) {
			if (mode().equals("constellation") && System.currentTimeMillis() < goUntil) state = "go";
			else if (mode().equals("constellation") && newer() && !stuck) state = "gathering";
			else if (!newer() && !r.own.isEmpty() && cmpVersion(ownVersion(), r.own) > 0) state = "go";   // (this Gatekeeper is ahead already: catch up)
		}
		return m("state", state, "version", newer() ? newestKeeper : ownVersion(), "mode", mode(), "member", allowed, "lead", myName());
	}
	static String installOf(Map<String, Object> b) { String s = Json.str(b.get("install")); return s.matches("[0-9a-fA-F\\-]{16,64}") ? s.toLowerCase(java.util.Locale.ROOT) : ""; }
	/** Is that client one this Gatekeeper waits for and takes along (a launcher, or a Gatekeeper that was allowed in)? */
	static boolean counts(String id, Rep r) {
		if (!r.kind.equals("gatekeeper")) return true;
		return ServerTools.yes(Json.map(devices().get(id)).get("constellation"));
	}
	/** The clients that are open right now in constellation mode: {name, is it ready}. */
	static List<Object[]> open() {
		List<Object[]> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		// (a client already on the version that is out has nothing left to see: a launcher that reports to two
		// Gatekeepers updates with whichever goes first, and must not then hold the other one up)
		for (Map.Entry<String, Rep> e : seen.entrySet()) {
			Rep r = e.getValue();
			if (now - r.at >= 25_000 || !r.mode.equals("constellation") || !counts(e.getKey(), r)) continue;
			boolean sawIt = !r.sees.isEmpty() || (!r.own.isEmpty() && !newestKeeper.isEmpty() && cmpVersion(r.own, newestKeeper) >= 0);
			out.add(new Object[] {r.name, sawIt && !r.busy && !r.waiting});
		}
		return out;
	}

	// ------------------------------------------------------------------ as a member of another Gatekeeper's constellation

	private static String leadCall(String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(lead().replaceAll("/+$", "") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(6000); c.setRequestMethod("POST"); c.setDoOutput(true);
		c.setRequestProperty("Content-Type", "application/json");
		c.setRequestProperty("X-Device", setting("constellation_id", "")); c.setRequestProperty("X-Token", setting("constellation_token", ""));
		try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
		int code = c.getResponseCode();
		try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
			String said = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (code == 401) throw new IOException("unknown");
			if (code >= 400) throw new IOException("answered " + code);
			return said;
		}
	}
	/** This Gatekeeper's own launchers, as it tells the constellation it has joined. */
	static List<Object> ownClients() {
		List<Object> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Object> e : devices().entrySet()) {
			Map<String, Object> d = Json.map(e.getValue());
			if (Json.str(d.get("kind")).equals("gatekeeper")) continue;
			Rep r = seen.get(e.getKey());
			out.add(m("name", Json.str(d.get("name")), "player", Json.str(d.get("player")), "system", Json.str(d.get("os")), "version", r == null ? Json.str(d.get("version")) : r.own,
					"mode", r == null ? Json.str(d.get("update_mode")) : r.mode, "open", (r != null && now - r.at < 25_000) || now - contact.getOrDefault(e.getKey(), 0L) < 20_000,
					"install", r != null && !r.install.isEmpty() ? r.install : Json.str(d.get("install"))));
		}
		return out;
	}
	/** Reports to the Gatekeeper whose constellation this one has joined, and hears how things stand there. */
	static void talkToLead() {
		if (lead().isEmpty()) { leadState = ""; leadSaid = ""; leadMember = false; return; }
		String say = Json.write(m("kind", "gatekeeper", "install", setting("constellation_id", ""), "mode", mode(), "own", ownVersion(), "sees", newer() ? newestKeeper : "", "busy", DirectHost.anyTracked(), "waiting", launchersWaiting, "system", systemName(), "clients", ownClients()));
		try {
			Map<String, Object> r;
			try { r = Json.map(Json.parse(leadCall("/v1/update/state", say))); }
			catch (IOException first) {
				if (!"unknown".equals(first.getMessage())) throw first;
				// (not known there yet: it introduces itself, as a launcher does, and reports again)
				leadCall("/v1/hello", Json.write(m("id", setting("constellation_id", ""), "token", setting("constellation_token", ""), "name", myName() + " (Gatekeeper)", "os", systemName())));
				r = Json.map(Json.parse(leadCall("/v1/update/state", say)));
			}
			leadHeard = System.currentTimeMillis();
			leadState = Json.str(r.get("state")); leadName = Json.str(r.get("lead")); leadMember = ServerTools.yes(r.get("member"));
			leadSaid = leadMember ? "Part of " + (leadName.isEmpty() ? "that Gatekeeper" : leadName) + "'s constellation." : "Asked to join " + (leadName.isEmpty() ? "that Gatekeeper" : leadName) + "'s constellation: waiting for its owner to allow it (on its Constellation tab).";
		} catch (IOException | RuntimeException e) {
			leadState = "";
			leadSaid = "The Gatekeeper at " + lead().replaceFirst("^https?://", "") + " isn't answering" + (leadHeard > 0 ? "" : " (or isn't a Gatekeeper of this version)") + ". Until it does, this Gatekeeper decides about updates by itself.";
		}
	}

	static void start() {
		Thread t = new Thread(() -> {
			long looked = 0;
			while (true) {
				try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
				try {
					talkToLead();
					String mode = mode(), tag = mode + " update: ";
					if (mode.equals("normal")) { gatherSince = 0; status = ""; launchersWaiting = false; continue; }
					long now = System.currentTimeMillis();
					if (now - looked > 60_000) { looked = now; lookForNewer(); }   // (in these modes it looks every minute, not every five)
					if (!newer()) { gatherSince = 0; saidServers = false; stuck = false; launchersWaiting = false; status = "Up to date. Looking for a newer one every minute."; continue; }
					if (now < notBefore) continue;
					// (never the same published file twice: if it was put in place and this is still the older version,
					// what was published is at fault, and updating to it again and again would only keep restarting)
					String target = newestKeeper + "|" + newestKeeperSha;
					if (target.equals(read(dir.resolve("auto-update-tried.txt")).trim())) { status = newestKeeper + " was installed by itself already, but this is still " + ownVersion() + ". Not trying that one again by itself: press Update Gatekeeper, or wait for the next version."; gatherSince = 0; stuck = true; continue; }
					stuck = false;
					List<String> waitingFor = new ArrayList<>(), ready = new ArrayList<>();
					if (mode.equals("constellation")) {
						if (gatherSince == 0) { gatherSince = now; saidServers = false; log(tag + newestKeeper + " is out. Waiting for the clients that are open to be ready for it too."); }
						for (Object[] o : open()) ((Boolean) o[1] ? ready : waitingFor).add((String) o[0]);
						launchersWaiting = !waitingFor.isEmpty();
						long waited = now - gatherSince;
						if (following()) {
							// (a member: the Gatekeeper it joined says when; this one only tells it whether it is ready)
							if (!leadState.equals("go")) { status = newestKeeper + " is out. " + (waitingFor.isEmpty() ? "Ready" : "Waiting for " + String.join(", ", waitingFor)) + "; " + leadName + " gives the go for the whole constellation."; continue; }
						} else if (waited < 15_000 || (!waitingFor.isEmpty() && waited < 600_000)) { status = newestKeeper + " is out. Waiting for " + (waitingFor.isEmpty() ? "the open clients to report" : String.join(", ", waitingFor) + " to be ready") + "."; continue; }
					}
					if (DirectHost.anyTracked()) {
						status = newestKeeper + (mode.equals("constellation") ? " is ready for everyone." : " is out.") + " Waiting for the servers this Gatekeeper runs to be off.";
						if (!saidServers) { saidServers = true; log(tag + status); }
						continue;
					}
					if (mode.equals("constellation")) {
						goUntil = now + 90_000;
						status = "Updating to " + newestKeeper + " now" + (following() ? ", on " + leadName + "'s go" : "") + (ready.isEmpty() ? "" : ", with " + String.join(", ", ready)) + ".";
						log(tag + status + (waitingFor.isEmpty() ? "" : " Not waited for any longer: " + String.join(", ", waitingFor) + "."));
						Thread.sleep(8000);   // (so every open client has asked once and been told to go)
					} else { status = "Updating to " + newestKeeper + " now."; log(tag + status); }
					String[] r = applyUpdate(0);
					log(tag + r[1]);
					if (r[0].equals("ok")) try { java.nio.file.Files.writeString(dir.resolve("auto-update-tried.txt"), target, StandardCharsets.UTF_8); } catch (IOException ignored) {}
					gatherSince = 0;
					if (!r[0].equals("ok")) { notBefore = System.currentTimeMillis() + 600_000; status = "The update didn't go through (" + r[1] + "). Trying again in ten minutes."; }
				} catch (InterruptedException e) { return; }
				catch (RuntimeException e) { log("updating by itself: " + e); }
			}
		}, "auto-update");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ what the Constellation tab shows, and its buttons

	/** The constellation as this Gatekeeper knows it: itself, every launcher that has introduced itself to it, every
	 *  Gatekeeper that has joined (or asked to) with the launchers that one told of, and the constellation this
	 *  Gatekeeper has itself joined, if any. */
	static Map<String, Object> constellation() {
		List<Object> clients = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Object> e : devices().entrySet()) {
			Map<String, Object> d = Json.map(e.getValue());
			Rep r = seen.get(e.getKey());
			boolean keeper = Json.str(d.get("kind")).equals("gatekeeper"), in = ServerTools.yes(d.get("constellation"));
			boolean open = (r != null && now - r.at < 25_000) || now - contact.getOrDefault(e.getKey(), 0L) < 20_000;
			String nm = Json.str(d.get("name")).replace(" (Gatekeeper)", "");
			clients.add(m("id", e.getKey(), "type", keeper ? "Gatekeeper" : "Gatehouse (launcher)", "name", nm, "player", Json.str(d.get("player")), "system", r != null && !r.system.isEmpty() ? r.system : Json.str(d.get("os")),
					"version", r == null ? Json.str(d.get("version")) : r.own, "mode", r == null ? Json.str(d.get("update_mode")) : r.mode, "open", open, "last_seen", Json.str(d.get("last_seen")),
					"here", ServerTools.yes(d.get("local")), "via", "", "membership", !keeper ? "" : in ? "member" : "asked", "install", r != null && !r.install.isEmpty() ? r.install : Json.str(d.get("install"))));
			if (keeper && in && r != null) for (Object o : r.clients) {
				Map<String, Object> c = Json.map(o);
				clients.add(m("id", "", "type", "Gatehouse (launcher)", "name", Json.str(c.get("name")), "player", Json.str(c.get("player")), "system", Json.str(c.get("system")), "version", Json.str(c.get("version")), "mode", Json.str(c.get("mode")),
						"open", ServerTools.yes(c.get("open")), "last_seen", "", "here", false, "via", nm, "membership", "", "install", Json.str(c.get("install"))));
			}
		}
		int merged = clients.size();
		clients = withoutDuplicates(clients);
		merged -= clients.size();
		String mode = mode();
		return m("gatekeeper", m("type", "Gatekeeper", "name", myName(), "system", systemName(), "version", ownVersion(), "mode", mode, "newest", newestKeeper,
						"status", mode.equals("normal") ? (newer() ? newestKeeper + " is out: press Update Gatekeeper." : "Up to date.") : (status.isEmpty() ? "On." : status)),
				"clients", clients, "duplicates", merged, "lead", m("address", lead().replaceFirst("^https?://", ""), "name", leadName, "member", leadMember, "said", lead().isEmpty() ? "" : leadSaid));
	}

	/** The same client can be in a constellation more than one way: a launcher paired with this Gatekeeper that is
	 *  also paired with a Gatekeeper that joined is told of twice, once by itself and once through that Gatekeeper.
	 *  Each is listed once. Two entries are the same client when they carry the same install id (which an install of
	 *  the program gives itself once and reports everywhere); for a client from before that id, when its name, player
	 *  and system all match. The entry this Gatekeeper knows directly is the one kept; the ways it is also reached
	 *  go in "also", and "same_by" says how it was recognised ("id" or "name"). */
	static List<Object> withoutDuplicates(List<Object> rows) {
		List<Object> out = new ArrayList<>();
		Map<String, Map<String, Object>> byId = new java.util.HashMap<>(), byLooks = new java.util.HashMap<>();
		// (directly known ones first, so they are the ones kept; the order they are shown in is put back after)
		List<Object> order = new ArrayList<>(rows);
		order.sort(java.util.Comparator.comparing(o -> Json.str(Json.map(o).get("via")).isEmpty() ? 0 : 1));
		java.util.Set<Object> dropped = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Object o : order) {
			Map<String, Object> c = Json.map(o);
			if (!Json.str(c.get("membership")).isEmpty()) continue;   // (a Gatekeeper that joined is a row of its own)
			String id = Json.str(c.get("install")), looks = (Json.str(c.get("name")) + "|" + Json.str(c.get("player")) + "|" + Json.str(c.get("system"))).toLowerCase(java.util.Locale.ROOT);
			Map<String, Object> first = !id.isEmpty() && byId.containsKey(id) ? byId.get(id) : null;
			String how = "id";
			if (first == null && byLooks.containsKey(looks) && (id.isEmpty() || Json.str(byLooks.get(looks).get("install")).isEmpty())) { first = byLooks.get(looks); how = "name"; }
			if (first == null) { if (!id.isEmpty()) byId.put(id, c); if (!Json.str(c.get("name")).isEmpty()) byLooks.put(looks, c); continue; }
			List<Object> also = new ArrayList<>(Json.list(first.get("also")));
			String way = Json.str(c.get("via")).isEmpty() ? "directly" : "through " + Json.str(c.get("via"));
			if (!also.contains(way)) also.add(way);
			first.put("also", also);
			if (!"id".equals(Json.str(first.get("same_by")))) first.put("same_by", how);
			if (ServerTools.yes(c.get("open"))) first.put("open", true);
			if (Json.str(first.get("version")).isEmpty()) first.put("version", Json.str(c.get("version")));
			if (Json.str(first.get("mode")).isEmpty()) first.put("mode", Json.str(c.get("mode")));
			if (Json.str(first.get("install")).isEmpty() && !id.isEmpty()) { first.put("install", id); byId.put(id, first); }
			dropped.add(o);
		}
		for (Object o : rows) if (!dropped.contains(o)) out.add(o);
		return out;
	}

	/** The window's calls: GET /admin/auto (how it stands), POST {mode}; GET /admin/constellation, POST {join: address}
	 *  | {leave: true} | {allow: id, value: true|false}. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/constellation")) {
			if (!x.getRequestMethod().equals("POST")) { reply(x, 200, constellation()); return true; }
			Map<String, Object> b = body(x);
			String msg;
			if (b.containsKey("join")) {
				// (an address, or the pairing link or file text that names it: host:port is all that is taken from it)
				java.util.regex.Matcher at = java.util.regex.Pattern.compile("([A-Za-z0-9.\\-]+):(\\d{2,5})").matcher(Json.str(b.get("join")).replaceFirst("^[a-z]+://(pair/)?", ""));
				if (!at.find()) { reply(x, 200, m("result", "refused", "message", "Give the other Gatekeeper's address, like 100.x.y.z:25570 (it is on its Server status tab, under Share pairing).")); return true; }
				String url = "http://" + at.group(1) + ":" + at.group(2);
				if ((at.group(1).equals(setting("bind", "")) || at.group(1).equals("127.0.0.1")) && Integer.parseInt(at.group(2)) == port()) { reply(x, 200, m("result", "refused", "message", "That is this Gatekeeper itself.")); return true; }
				if (setting("constellation_id", "").isEmpty()) { cfg.put("constellation_id", java.util.UUID.randomUUID().toString()); cfg.put("constellation_token", WebPanel.random(32)); }
				cfg.put("constellation_lead", url);
				ServerTools.saveCfg();
				leadHeard = 0; leadMember = false; leadState = ""; leadName = "";
				talkToLead();
				log("constellation: joining the Gatekeeper at " + at.group(1) + ":" + at.group(2) + ". " + leadSaid);
				msg = leadSaid;
			} else if (b.containsKey("leave")) {
				cfg.remove("constellation_lead"); ServerTools.saveCfg();
				leadMember = false; leadState = ""; leadSaid = ""; leadName = "";
				log("constellation: this Gatekeeper is on its own again");
				msg = "This Gatekeeper is on its own again. (The other Gatekeeper still lists it until its owner removes it there.)";
			} else {
				String id = Json.str(b.get("allow"));
				boolean in = ServerTools.yes(b.get("value"));
				synchronized (HostHelper.class) {
					Map<String, Object> devs = devices();
					Map<String, Object> d = Json.map(devs.get(id));
					if (d.isEmpty() || !Json.str(d.get("kind")).equals("gatekeeper")) { reply(x, 200, m("result", "refused", "message", "Click a Gatekeeper in the list first.")); return true; }
					if (in) d.put("constellation", true); else d.remove("constellation");
					saveDevices(devs);
					msg = Json.str(d.get("name")).replace(" (Gatekeeper)", "") + (in ? " is now part of this constellation: in constellation mode it updates on this Gatekeeper's go, and its launchers are listed here." : " is no longer part of this constellation.");
				}
				log("constellation: " + msg);
			}
			reply(x, 200, m("result", "ok", "message", msg));
			return true;
		}
		if (!path.equals("/admin/auto")) return false;
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			String want = b.containsKey("mode") ? Json.str(b.get("mode")) : ServerTools.yes(b.get("on")) ? "constellation" : "normal";
			if (!want.equals("auto") && !want.equals("constellation")) want = "normal";
			cfg.put("update_mode", want); cfg.remove("auto_update");
			ServerTools.saveCfg();
			gatherSince = 0; notBefore = 0; status = "";
			log("update mode: " + want);
		}
		List<Object> names = new ArrayList<>();
		for (Object[] o : open()) names.add(o[0]);
		String mode = mode();
		reply(x, 200, m("mode", mode, "on", !mode.equals("normal"), "status", mode.equals("normal") ? "Normal: you press Update Gatekeeper yourself." : (status.isEmpty() ? "On." : status), "launchers", names));
		return true;
	}
}
