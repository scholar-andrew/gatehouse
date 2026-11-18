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
import dev.gatehouse.look.Quiet;

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
		/** What it says of its system (operating system and Java), for the update log. */
		String info = "";
		/** Where it says the version it is on was fetched from ("" when it doesn't know). */
		String source = "";
		boolean busy, waiting;
		List<Object> clients = new ArrayList<>();
		/** R42: how long its last report to this Gatekeeper took (ms, 0 unknown), and its battery ("" when it has none or
		 *  doesn't say): for the web panel's Systems page. */
		int ping; String battery = "";
	}
	static final Map<String, Rep> seen = new ConcurrentHashMap<>();
	static volatile long gatherSince = 0, goUntil = 0, notBefore = 0;
	static volatile String status = "";
	private static volatile boolean saidServers = false, stuck = false, launchersWaiting = false;
	// (as a member of another Gatekeeper's constellation: how that stands)
	static volatile String leadState = "", leadName = "", leadSaid = "";
	/** What the Gatekeeper this one joined last said about the gathering (see gatherFor). */
	static volatile Map<String, Object> leadGather = new java.util.LinkedHashMap<>();
	static volatile boolean leadMember = false;
	static volatile long leadHeard = 0;

	/** normal, auto or constellation. (A host.json from before the modes had "auto_update": true for what is now constellation.) */
	static String mode() {
		String m = setting("update_mode", setting("auto_update", "false").equals("true") ? "constellation" : "normal");
		return m.equals("auto") || m.equals("constellation") ? m : "normal";
	}
	static String lead() { return setting("constellation_lead", ""); }
	/** How an update that starts now came about, in the update log's words. */
	static String howNow(String mode) { return mode + (mode.equals("constellation") && following() ? " (on " + leadName + "'s go)" : ""); }
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

	private static volatile long hinted = 0;
	/** Looks for a newer version now, because someone in the constellation has seen one (at most every ten seconds,
	 *  and not on the caller's own time). */
	static void hint(String from) {
		long now = System.currentTimeMillis();
		if (now - hinted < 10_000) return;
		hinted = now;
		Thread t = new Thread(() -> {
			try {
				lookForNewer();
				if (newer() && !mode().equals("normal")) log(mode() + " update: " + from
						+ " saw a newer version first; this Gatekeeper has now seen " + newestKeeper + " too");
			} catch (RuntimeException ignored) { Quiet.seen(ignored); }
		}, "update-hint");
		t.setDaemon(true);
		t.start();
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
		r.info = clean(Json.str(b.get("info")), 120).replace("<", "");
		r.source = clean(Json.str(b.get("source")), 200).replace("<", "");
		try { r.ping = Math.max(0, Math.min(60_000, (int) Double.parseDouble(Json.str(b.get("ping")).isEmpty() ? "0" : Json.str(b.get("ping"))))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
		r.battery = clean(Json.str(b.get("battery")), 24).replaceAll("[^\\w%,. ]", "");
		if (keeper) for (Object o : Json.list(b.get("clients"))) {   // (its own launchers, for the lead's list: kept as plain words)
			Map<String, Object> c = Json.map(o);
			if (r.clients.size() < 60) r.clients.add(m("name", clean(Json.str(c.get("name")), 40).replace("<", ""), "player",
					clean(Json.str(c.get("player")), 16).replaceAll("\\W", ""), "system", clean(Json.str(c.get("system")), 20).replace("<", ""),
					"version", Json.str(c.get("version")).replaceAll("[^\\w.\\-]", ""), "mode", modeOf(c), "open", ServerTools.yes(c.get("open")), "install", installOf(c),
					// (R42: for the web panel's Systems page: where it is, its ping and battery, and whether it is that Gatekeeper's own machine's launcher)
					"now", List.of("In game", "In the launcher", "Offline").contains(Json.str(c.get("now"))) ? Json.str(c.get("now")) : "",
					"ping", Math.max(0, Math.min(60_000, (int) Double.parseDouble(Json.str(c.get("ping")).matches("\\d+(\\.\\d+)?") ? Json.str(c.get("ping")) : "0"))),
					"battery", clean(Json.str(c.get("battery")), 24).replaceAll("[^\\w%,. ]", ""), "local", ServerTools.yes(c.get("local"))));
		}
		seen.put(id, r);
		// (word of an update travels up: a client that has seen a newer version than this Gatekeeper knows of has it
		// look now, not at its next minute; a member Gatekeeper passes that on to the one it joined the same way)
		if (!r.sees.isEmpty() && !newer()) hint(name);
		// (its kind, mode and version are kept with what else is known of it, so the Constellation tab still has them
		// when the client is closed, or this Gatekeeper has restarted; written only when one of them changed)
		boolean allowed = !keeper;
		try {
			synchronized (HostHelper.class) {
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(id));
				if (!d.isEmpty()) {
					allowed = !keeper || ServerTools.yes(d.get("constellation"));
					String kept = Json.str(d.get("version"));
					if (!(r.mode.equals(Json.str(d.get("update_mode"))) && (r.own.equals(kept) || (!kept.isEmpty() && !r.own.isEmpty()
							&& cmpVersion(r.own, kept) < 0)) && r.install.equals(Json.str(d.get("install")))
							&& r.kind.equals(Json.str(d.get("kind")).isEmpty() ? "launcher" : Json.str(d.get("kind"))))) {
						// (a version it reports that isn't the one it reported before: it has updated, and that goes in the update log.
						// Not for a Gatekeeper: it writes the line for its own update, where it was fetched from included, and hands
						// it on; a second line from here would say the same update twice, and without the source)
						String before = Json.str(d.get("version"));
						// (two launchers reporting as one device, an old one still open beside its updated copy, would
						// otherwise turn the version back and forth every few seconds: only a newer version counts)
						String own = !before.isEmpty() && !r.own.isEmpty() && cmpVersion(r.own, before) < 0 ? before : r.own;
						if (!keeper && !before.isEmpty() && !own.isEmpty()
								&& !before.equals(own)) UpdateLog.record(name.replace(" (Gatekeeper)", ""), r.kind,
								Json.str(d.get("player")), r.system.isEmpty() ? Json.str(d.get("os")) : r.system, before, own, r.mode, r.info, Handout.named(r.source), r.install);
						d.put("update_mode", r.mode); d.put("version", own); if (keeper) d.put("kind", "gatekeeper"); else d.remove("kind");
						if (!r.install.isEmpty()) d.put("install", r.install);
						saveDevices(devs);
						if (keeper && !ServerTools.yes(d.get("constellation"))
								&& !ServerTools.yes(d.get("asked_said"))) { d.put("asked_said",
								true); saveDevices(devs); log("the Gatekeeper " + name + " asks to join this Gatekeeper's constellation: allow it on the Constellation tab"); }
					}
				}
			}
		} catch (IOException ignored) { Quiet.seen(ignored); }
		// (a Gatekeeper that joined hands on the lines of its own update log: they are filed here as having come through it)
		if (keeper && allowed) UpdateLog.taken(name.replace(" (Gatekeeper)", ""), Json.list(b.get("events")));
		String state = "idle";
		if (r.mode.equals("constellation") && allowed) {
			if (mode().equals("constellation") && System.currentTimeMillis() < goUntil) state = "go";
			else if (mode().equals("constellation") && newer() && !stuck) state = "gathering";
			else if (!newer() && !r.own.isEmpty() && cmpVersion(ownVersion(), r.own) > 0) state = "go";   // (this Gatekeeper is ahead already: catch up)
		}
		Map<String, Object> out = m("state", state, "version", newer() ? newestKeeper : ownVersion(), "mode", mode(), "member", allowed, "lead", myName());
		// (R40: what it hands out, and who else does)
		if (!keeper || allowed) try { Map<String, Object> dv = Json.map(devices().get(id)); Handout.heard(id, dv, b); Handout.answer(id, dv, out); } catch (RuntimeException ignored) { Quiet.seen(ignored); }
		if (keeper && allowed) {
			out.put("pack", ownPack());
			Manage.noted(id, b);   // (what it says about this Gatekeeper managing it: its address and secret, or nothing)
			try { out.put("may_manage", ServerTools.yes(Json.map(devices().get(id)).get("manage"))); } catch (Exception ignored) { Quiet.seen(ignored); }
		}
		// (while the constellation is gathering or going, each client is shown who is being waited for)
		if (!state.equals("idle") && newer()) out.put("gather", gatherFor(id));
		return out;
	}

	/** Who the constellation is taking along in this update and how each stands, for the client that asks (id; "" for
	 *  this Gatekeeper's own window): {"pips": [{s: ready | waiting | busy, k: launcher | gatekeeper, n: name, me}]}.
	 *  ready: has seen the update (and, a Gatekeeper, so have its launchers). waiting: hasn't yet. busy: a Gatekeeper
	 *  that is running a server itself. Names are given to this Gatekeeper's own window, to the launcher on this
	 *  machine, to launchers with Gatekeeper control and to Gatekeepers that joined; other launchers get the pips
	 *  without names. A member passes on what the Gatekeeper it joined says, with itself and its own launchers in the
	 *  place of the one pip that Gatekeeper has for it. */
	static Map<String, Object> gatherFor(String id) {
		boolean names = id.isEmpty();
		if (!names) { Map<String, Object> d = Json.map(devices().get(id)); names = ServerTools.yes(d.get("local"))
				|| ServerTools.yes(d.get("admin")) || ServerTools.yes(d.get("constellation")); }
		List<Object> pips = new ArrayList<>();
		long now = System.currentTimeMillis();
		List<Object> mine = new ArrayList<>();   // (this Gatekeeper and its own clients)
		mine.add(m("s", DirectHost.anyTracked() ? "busy" : newer() ? "ready" : "waiting", "k", "gatekeeper", "n", names ? myName() : "",
				"me", id.isEmpty(), "i", setting("constellation_id", "")));
		for (Map.Entry<String, Rep> e : seen.entrySet()) {
			Rep r = e.getValue();
			if (now - r.at >= 25_000 || !r.mode.equals("constellation") || !counts(e.getKey(), r)) continue;
			boolean sawIt = !r.sees.isEmpty() || (!r.own.isEmpty() && !newestKeeper.isEmpty() && cmpVersion(r.own, newestKeeper) >= 0);
			mine.add(m("s", r.busy ? "busy" : sawIt && !r.waiting ? "ready" : "waiting", "k", r.kind, "n", names
					? r.name.replace(" (Gatekeeper)", "") : "", "me", e.getKey().equals(id), "i", r.kind.equals("gatekeeper") ? r.install : ""));
		}
		List<Object> fromLead = following() ? Json.list(leadGather.get("pips")) : new ArrayList<>();
		if (fromLead.isEmpty()) pips.addAll(mine);
		else {
			String self = setting("constellation_id", "");
			boolean placed = false;
			for (Object o : fromLead) {
				Map<String, Object> p = Json.map(o);
				if (!self.isEmpty() && self.equalsIgnoreCase(Json.str(p.get("i")))) { pips.addAll(mine); placed = true; continue; }
				pips.add(m("s", Json.str(p.get("s")), "k", Json.str(p.get("k")), "n", names ? clean(Json.str(p.get("n")), 40).replace("<", "") : "", "me", false, "i", ""));
			}
			if (!placed) pips.addAll(mine);
		}
		return m("pips", pips);
	}
	static String installOf(Map<String, Object> b) { String s = Json.str(b.get("install")); return s.matches("[0-9a-fA-F\\-]{16,64}")
			? s.toLowerCase(java.util.Locale.ROOT) : ""; }
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

	static String leadCall(String path, String body) throws IOException {
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
			boolean fresh = r != null && now - r.at < 25_000;
			out.add(m("name", Json.str(d.get("name")), "player", Json.str(d.get("player")), "system", Json.str(d.get("os")), "version",
					r == null ? Json.str(d.get("version")) : r.own,
					"mode", r == null ? Json.str(d.get("update_mode")) : r.mode, "open", fresh
							|| now - contact.getOrDefault(e.getKey(), 0L) < 20_000,
					"install", r != null && !r.install.isEmpty() ? r.install : Json.str(d.get("install")),
					// (R42: what the lead's web panel shows on this machine's card)
					"now", presence(e.getKey(), d, onlineNow()), "ping", fresh ? r.ping : 0, "battery", fresh ? r.battery : "",
					"local", ServerTools.yes(d.get("local"))));
		}
		return out;
	}
	/** Reports to the Gatekeeper whose constellation this one has joined, and hears how things stand there. */
	static void talkToLead() {
		if (lead().isEmpty()) { leadState = ""; leadSaid = ""; leadMember = false; return; }
		List<Object> handing = UpdateLog.unsent();
		String say = Json.write(m("kind", "gatekeeper", "events", handing, "info", UpdateLog.info(), "install", setting("constellation_id",
				""), "mode", mode(), "own", ownVersion(), "sees", newer() ? newestKeeper : "", "busy", DirectHost.anyTracked(), "waiting",
				launchersWaiting, "system", systemName(), "clients", ownClients(), "manage", Manage.offer(), "share", dev.octoberjourney.popup.PackShare.status(),
				"battery", dev.octoberjourney.popup.PackShare.battery()));   // (R42: this machine's own battery: a Steam Deck's, a laptop's)
		try {
			Map<String, Object> r;
			try { r = Json.map(Json.parse(leadCall("/v1/update/state", say))); }
			catch (IOException first) {
				if (!"unknown".equals(first.getMessage())) throw first;
				// (not known there yet: it introduces itself, as a launcher does, and reports again)
				leadCall("/v1/hello", Json.write(m("id", setting("constellation_id", ""), "token", setting("constellation_token", ""),
						"name", myName() + " (Gatekeeper)", "os", systemName())));
				r = Json.map(Json.parse(leadCall("/v1/update/state", say)));
			}
			leadHeard = System.currentTimeMillis();
			leadState = Json.str(r.get("state")); leadName = Json.str(r.get("lead")); leadMember = ServerTools.yes(r.get("member"));
			leadGather = Json.map(r.get("gather"));
			// (R40: the Gate Systems handing out there, passed on to this one's launchers, and the devices they answer)
			Handout.leadPeers = leadMember ? Json.list(r.get("peers")) : List.of(); Handout.leadAllow = leadMember ? Json.list(r.get("allow")) : List.of();
			Manage.leadLets = leadMember && ServerTools.yes(r.get("may_manage"));
			// (and word travels down: the Gatekeeper this one joined is gathering or going for a version this one hasn't seen yet)
			if ((leadState.equals("gathering") || leadState.equals("go")) && !newer()) hint(leadName);
			if (leadMember) UpdateLog.handedOn(handing.size());   // (taken there: not handed on again)
			// (where that Gatekeeper's machine publishes the program: remembered, and looked at only if this one's owner chose that)
			String pack = Json.str(r.get("pack"));
			if (leadMember && r.containsKey("pack") && !pack.equals(setting("lead_pack",
					""))) { if (pack.isEmpty()) cfg.remove("lead_pack"); else cfg.put("lead_pack", pack); ServerTools.saveCfg(); }
			leadSaid = leadMember ? "Part of " + (leadName.isEmpty() ? "that Gatekeeper" : leadName) + "'s constellation."
					: "Asked to join " + (leadName.isEmpty() ? "that Gatekeeper" : leadName)
					+ "'s constellation: waiting for its owner to allow it (on its Constellation tab).";
		} catch (IOException | RuntimeException e) {
			leadState = ""; leadGather = new java.util.LinkedHashMap<>();
			Handout.leadPeers = List.of(); Handout.leadAllow = List.of();
			leadSaid = "The Gatekeeper at " + lead().replaceFirst("^https?://", "") + " isn't answering" + (leadHeard > 0 ? ""
					: " (or isn't a Gatekeeper of this version)") + ". Until it does, this Gatekeeper decides about updates by itself.";
		}
	}

	static void start() {
		Thread t = new Thread(() -> {
			long looked = 0;
			while (true) {
				try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
				try {
					talkToLead();
					Systems.sync();   // (who else is in the constellation this Gatekeeper joined)
					History.sync();   // (the history of servers: kept in step with this Gatekeeper's servers, and shared with the Gatekeeper it joined)
					String mode = mode(), tag = mode + " update: ";
					if (mode.equals("normal")) { gatherSince = 0; status = ""; launchersWaiting = false; continue; }
					long now = System.currentTimeMillis();
					if (now - looked > 60_000) { looked = now; lookForNewer(); }   // (in these modes it looks every minute, not every five)
					if (!newer()) { gatherSince = 0; saidServers = false; stuck = false; launchersWaiting = false; status = "Up to date. Looking for a newer one every minute."; continue; }
					if (now < notBefore) continue;
					// (never the same published file twice: if it was put in place and this is still the older version,
					// what was published is at fault, and updating to it again and again would only keep restarting)
					String target = newestKeeper + "|" + newestKeeperSha;
					if (target.equals(read(dir.resolve("auto-update-tried.txt")).trim())) { status = newestKeeper
							+ " was installed by itself already, but this is still " + ownVersion()
							+ ". Not trying that one again by itself: press Update Gatekeeper, or wait for the next version."; gatherSince = 0; stuck = true; continue; }
					stuck = false;
					List<String> waitingFor = new ArrayList<>(), ready = new ArrayList<>();
					if (mode.equals("constellation")) {
						if (gatherSince == 0) { gatherSince = now; saidServers = false; log(tag + newestKeeper
								+ " is out. Waiting for the clients that are open to be ready for it too."); }
						for (Object[] o : open()) ((Boolean) o[1] ? ready : waitingFor).add((String) o[0]);
						launchersWaiting = !waitingFor.isEmpty();
						long waited = now - gatherSince;
						if (following()) {
							// (a member: the Gatekeeper it joined says when; this one only tells it whether it is ready)
							if (!leadState.equals("go")) { status = newestKeeper + " is out. " + (waitingFor.isEmpty() ? "Ready"
									: "Waiting for " + String.join(", ", waitingFor)) + "; " + leadName + " gives the go for the whole constellation."; continue; }
						} else if (waited < 15_000 || (!waitingFor.isEmpty() && waited < 600_000)) { status = newestKeeper
								+ " is out. Waiting for " + (waitingFor.isEmpty() ? "the open clients to report" : String.join(", ", waitingFor) + " to be ready") + "."; continue; }
					}
					if (DirectHost.anyTracked()) {
						status = newestKeeper + (mode.equals("constellation") ? " is ready for everyone." : " is out.")
								+ " Waiting for the servers this Gatekeeper runs to be off.";
						if (!saidServers) { saidServers = true; log(tag + status); }
						continue;
					}
					if (mode.equals("constellation")) {
						goUntil = now + 90_000;
						dev.gatehouse.look.Chime.constellation();   // (everyone is ready: the constellation's own sound, here and on every client told to go)
						status = "Updating to " + newestKeeper + " now" + (following() ? ", on " + leadName + "'s go" : "")
								+ (ready.isEmpty() ? "" : ", with " + String.join(", ", ready)) + ".";
						log(tag + status + (waitingFor.isEmpty() ? "" : " Not waited for any longer: " + String.join(", ", waitingFor) + "."));
						Thread.sleep(8000);   // (so every open client has asked once and been told to go)
					} else { status = "Updating to " + newestKeeper + " now."; log(tag + status); }
					UpdateLog.how = howNow(mode);
					String[] r = applyUpdate(0);
					log(tag + r[1]);
					if (r[0].equals("ok")) try { java.nio.file.Files.writeString(dir.resolve("auto-update-tried.txt"), target,
							StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
					gatherSince = 0;
					if (!r[0].equals("ok")) { notBefore = System.currentTimeMillis() + 600_000; status = "The update didn't go through ("
							+ r[1] + "). Trying again in ten minutes."; }
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
			clients.add(m("id", e.getKey(), "type", keeper ? "Gatekeeper" : "Gatehouse (launcher)", "name", nm, "player",
					Json.str(d.get("player")), "system", r != null && !r.system.isEmpty() ? r.system : Json.str(d.get("os")),
					"version", r == null ? Json.str(d.get("version")) : r.own, "mode", r == null ? Json.str(d.get("update_mode")) : r.mode,
							"open", open, "last_seen", Json.str(d.get("last_seen")),
					"here", ServerTools.yes(d.get("local")), "via", "", "hands_out", Handout.words(e.getKey()), "manage", ServerTools.yes(d.get("manage")), "manageable",
							!Json.str(d.get("manage_url")).isEmpty(), "membership", !keeper ? "" : in ? "member" : "asked", "install",
							r != null && !r.install.isEmpty() ? r.install : Json.str(d.get("install"))));
			if (keeper && in && r != null) for (Object o : r.clients) {
				Map<String, Object> c = Json.map(o);
				clients.add(m("id", "", "type", "Gatehouse (launcher)", "name", Json.str(c.get("name")), "player",
						Json.str(c.get("player")), "system", Json.str(c.get("system")), "version", Json.str(c.get("version")), "mode", Json.str(c.get("mode")),
						"open", ServerTools.yes(c.get("open")), "last_seen", "", "here", false, "via", nm, "membership", "", "install", Json.str(c.get("install")),
						"now", Json.str(c.get("now")), "ping", c.get("ping"), "battery", Json.str(c.get("battery")), "local_of", ServerTools.yes(c.get("local")) ? e.getKey() : ""));
			}
		}
		int merged = clients.size();
		clients = withoutDuplicates(clients);
		merged -= clients.size();
		String mode = mode();
		return m("gatekeeper", m("type", "Gatekeeper", "name", myName(), "system", systemName(), "version", ownVersion(), "mode", mode, "newest", newestKeeper,
						"hands_out", Handout.ownWords(), "hand_out", Handout.on(),
						"status", mode.equals("normal") ? (newer() ? newestKeeper + " is out: press Update Gatekeeper." : "Up to date.") : (status.isEmpty() ? "On." : status)),
				"clients", clients, "duplicates", merged, "lead", m("address", lead().replaceFirst("^https?://", ""), "name", leadName,
						"member", leadMember, "said", lead().isEmpty() ? "" : leadSaid,
						"updates", setting("lead_updates", "true").equals("true"), "manage", Manage.leadMay(), "manageable",
								Manage.leadLets, "pack", setting("lead_pack", "").replaceFirst("^https?://", "").replaceFirst("/gatekeeper$", "")));
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
			String id = Json.str(c.get("install")), looks = (Json.str(c.get("name")) + "|" + Json.str(c.get("player")) + "|"
					+ Json.str(c.get("system"))).toLowerCase(java.util.Locale.ROOT);
			Map<String, Object> first = !id.isEmpty() && byId.containsKey(id) ? byId.get(id) : null;
			String how = "id";
			if (first == null && byLooks.containsKey(looks) && (id.isEmpty()
					|| Json.str(byLooks.get(looks).get("install")).isEmpty())) { first = byLooks.get(looks); how = "name"; }
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
				if (!at.find()) { reply(x, 200, m("result", "refused", "message",
						"Give the other Gatekeeper's address, like 100.x.y.z:25570 (it is on its Server status tab, under Share pairing).")); return true; }
				String url = "http://" + at.group(1) + ":" + at.group(2);
				if ((at.group(1).equals(setting("bind", "")) || at.group(1).equals("127.0.0.1"))
						&& Integer.parseInt(at.group(2)) == port()) { reply(x, 200, m("result", "refused", "message", "That is this Gatekeeper itself.")); return true; }
				if (setting("constellation_id", "").isEmpty()) { cfg.put("constellation_id",
						java.util.UUID.randomUUID().toString()); cfg.put("constellation_token", WebPanel.random(32)); }
				cfg.put("constellation_lead", url);
				ServerTools.saveCfg();
				leadHeard = 0; leadMember = false; leadState = ""; leadName = "";
				talkToLead();
				log("constellation: joining the Gatekeeper at " + at.group(1) + ":" + at.group(2) + ". " + leadSaid);
				msg = leadSaid;
			} else if (b.containsKey("lead_manage")) {
				// (the owner's own choice, at this machine: the Gatekeeper this one joined may manage it. See Manage.)
				boolean on = ServerTools.yes(b.get("lead_manage"));
				if (lead().isEmpty()) { reply(x, 200, m("result", "refused", "message", "This Gatekeeper hasn't joined another's constellation.")); return true; }
				if (on) cfg.put("lead_manage", "true"); else { cfg.remove("lead_manage"); cfg.remove("manage_token"); }
				ServerTools.saveCfg();
				log("constellation: the Gatekeeper this one joined " + (on
						? "may now manage this one (its servers, players and launchers; not its settings or the machine)" : "may no longer manage this one"));
				msg = on ? "The Gatekeeper this one joined may now manage it from its own window: servers, players and launchers. Not its settings, updates or the machine." : "It may no longer manage this one.";
			} else if (b.containsKey("manage")) {
				String id = Json.str(b.get("manage"));
				boolean on = ServerTools.yes(b.get("value"));
				synchronized (HostHelper.class) {
					Map<String, Object> devs = devices();
					Map<String, Object> d = Json.map(devs.get(id));
					if (d.isEmpty() || !Json.str(d.get("kind")).equals("gatekeeper")
							|| !ServerTools.yes(d.get("constellation"))) { reply(x, 200, m("result", "refused", "message",
							"Click a Gatekeeper that has joined and been allowed in first.")); return true; }
					if (on) d.put("manage", true); else d.remove("manage");
					saveDevices(devs);
					log("constellation: " + who(d) + (on
							? " may now manage this Gatekeeper (its servers, players and launchers; not its settings or the machine)" : " may no longer manage this Gatekeeper"));
					msg = who(d) + (on ? " may now manage this Gatekeeper from its own window: servers, players and launchers. Not its settings, updates or the machine." : " may no longer manage this Gatekeeper.");
				}
			} else if (b.containsKey("hand_out")) {
				// (R40: this machine handing out pack updates to other Gate Systems; on unless turned off here)
				boolean on = ServerTools.yes(b.get("hand_out"));
				cfg.put("hand_out", on ? "true" : "false");
				ServerTools.saveCfg();
				if (!on) dev.octoberjourney.popup.PackShare.stop();
				log("pack updates: this machine " + (on ? "hands them out to other Gate Systems again" : "no longer hands them out"));
				msg = on ? "This machine hands out pack updates to other Gate Systems again (within a minute)." : "This machine no longer hands out pack updates.";
			} else if (b.containsKey("lead_updates")) {
				// (the owner's own choice, made at this machine: the Gatekeeper it joined may supply this one's program)
				boolean on = ServerTools.yes(b.get("lead_updates"));
				if (lead().isEmpty()) { reply(x, 200, m("result", "refused", "message", "This Gatekeeper hasn't joined another's constellation.")); return true; }
				cfg.put("lead_updates", on ? "true" : "false");   // (on unless turned off: the two Gatekeepers pass the program file between them)
				ServerTools.saveCfg();
				log("constellation: the program file is " + (on
						? "shared with the Gatekeeper this one joined again (taken from it, and handed to it)"
						: "no longer shared with the Gatekeeper this one joined: this one fetches its own"));
				msg = on ? "This Gatekeeper takes updates from the Gatekeeper it joined, and hands on any it finds first."
						: "This Gatekeeper fetches its own updates again, from its own machine's pack server or GitHub.";
				new Thread(() -> { try { lookForNewer(); } catch (RuntimeException ignored) { Quiet.seen(ignored); } }, "look").start();
			} else if (b.containsKey("leave")) {
				cfg.remove("constellation_lead");
				cfg.remove("lead_updates");
				cfg.remove("lead_pack");
				cfg.remove("lead_manage");
				cfg.remove("manage_token");
				ServerTools.saveCfg();
				Manage.leadLets = false;
				leadMember = false; leadState = ""; leadSaid = ""; leadName = "";
				log("constellation: this Gatekeeper is on its own again");
				msg = "This Gatekeeper is on its own again. (The other Gatekeeper still lists it until its owner removes it there.)";
			} else {
				String id = Json.str(b.get("allow"));
				boolean in = ServerTools.yes(b.get("value"));
				synchronized (HostHelper.class) {
					Map<String, Object> devs = devices();
					Map<String, Object> d = Json.map(devs.get(id));
					if (d.isEmpty() || !Json.str(d.get("kind")).equals("gatekeeper")) { reply(x, 200, m("result", "refused", "message",
							"Click a Gatekeeper in the list first.")); return true; }
					if (in) d.put("constellation", true); else d.remove("constellation");
					saveDevices(devs);
					msg = Json.str(d.get("name")).replace(" (Gatekeeper)", "") + (in
							? " is now part of this constellation: in constellation mode it updates on this Gatekeeper's go, and its launchers are listed here." : " is no longer part of this constellation.");
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
		Map<String, Object> out = m("mode", mode, "on", !mode.equals("normal"), "status", mode.equals("normal")
				? "Normal: you press Update Gatekeeper yourself." : (status.isEmpty() ? "On." : status), "launchers", names);
		if (mode.equals("constellation") && newer() && !stuck) out.put("gather", gatherFor(""));
		reply(x, 200, out);
		return true;
	}
}
