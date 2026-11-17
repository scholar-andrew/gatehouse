package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

/**
 * How this Gatekeeper updates (host.json "update_mode"; each launcher has the same choice of its own, in its Options):
 *
 *   normal         nothing updates until the owner presses Update Gatekeeper. What it is unless changed.
 *   auto           this Gatekeeper updates itself as soon as it sees a newer version, waiting for nobody (only for
 *                  the servers it runs itself to be off: updating restarts it, and their consoles with it).
 *   constellation  the group goes together. A constellation is this Gatekeeper and every client connected to it.
 *                  When a newer version is out, Gatekeeper
 *                    1. gathers: waits until every open launcher that is in constellation mode too has seen the update
 *                       (at least fifteen seconds, so they have all had a turn; at most ten minutes, after which the
 *                       ones that still haven't are left to themselves);
 *                    2. waits, if it has to, for the servers it runs itself to be off;
 *                    3. gives the go: those launchers are told "go" and update themselves, and a few seconds later
 *                       this Gatekeeper updates too, the way Update Gatekeeper does (checked, undone if it fails).
 *                  A launcher that opens later, still on the old version, is told "go" as soon as it reports.
 *
 * Launchers report in every few seconds while their window is open (POST /v1/update/state): that they are there, their
 * version, their own mode, and whether they have seen the newer version yet. That is also what the Constellation tab
 * shows: every client of this Gatekeeper, its version, its mode, and whether it is open now.
 */
final class AutoUpdate {
	/** A launcher's last report: when, its mode, the newer version it has seen ("" if none yet), its own version, its name. */
	static final Map<String, Object[]> seen = new ConcurrentHashMap<>();
	static volatile long gatherSince = 0, goUntil = 0, notBefore = 0;
	static volatile String status = "";
	private static volatile boolean saidServers = false, stuck = false;

	/** normal, auto or constellation. (A host.json from before the modes had "auto_update": true for what is now constellation.) */
	static String mode() {
		String m = setting("update_mode", setting("auto_update", "false").equals("true") ? "constellation" : "normal");
		return m.equals("auto") || m.equals("constellation") ? m : "normal";
	}
	static boolean newer() { return !newestKeeper.isEmpty() && cmpVersion(newestKeeper, ownVersion()) > 0; }
	static String modeOf(Map<String, Object> b) {
		String m = Json.str(b.get("mode"));
		if (m.equals("auto") || m.equals("constellation") || m.equals("normal")) return m;
		return ServerTools.yes(b.get("auto")) ? "constellation" : "normal";   // (a launcher from before the modes)
	}

	/** A launcher reporting in; what it is told. */
	static Map<String, Object> report(String id, String name, Map<String, Object> b) {
		String theirs = modeOf(b), own = Json.str(b.get("own")).replaceAll("[^\\w.\\-]", "");
		seen.put(id, new Object[] {System.currentTimeMillis(), theirs, Json.str(b.get("sees")).replaceAll("[^\\w.\\-]", ""), own, name});
		// (its mode and version are kept with what else is known of it, so the Constellation tab still has them when
		// the launcher is closed, or this Gatekeeper has restarted; written only when one of them changed)
		try {
			synchronized (HostHelper.class) {
				Map<String, Object> devs = devices();
				Map<String, Object> d = Json.map(devs.get(id));
				if (!d.isEmpty() && !(theirs.equals(Json.str(d.get("update_mode"))) && own.equals(Json.str(d.get("version"))))) { d.put("update_mode", theirs); d.put("version", own); saveDevices(devs); }
			}
		} catch (IOException ignored) {}
		String state = "idle";
		if (theirs.equals("constellation")) {
			if (mode().equals("constellation") && System.currentTimeMillis() < goUntil) state = "go";
			else if (mode().equals("constellation") && newer() && !stuck) state = "gathering";
			else if (!newer() && !own.isEmpty() && cmpVersion(ownVersion(), own) > 0) state = "go";   // (this Gatekeeper is ahead already: catch up)
		}
		return m("state", state, "version", newer() ? newestKeeper : ownVersion(), "mode", mode());
	}
	/** The launchers that are open right now in constellation mode: {name, has it seen the update}. */
	static List<Object[]> open() {
		List<Object[]> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		// (a launcher already on the version that is out has nothing left to see: one that reports to two Gatekeepers
		// updates with whichever goes first, and must not then hold the other one up)
		for (Object[] r : seen.values()) if (now - (Long) r[0] < 25_000 && "constellation".equals(r[1]))
			out.add(new Object[] {r[4], !((String) r[2]).isEmpty() || (!((String) r[3]).isEmpty() && !newestKeeper.isEmpty() && cmpVersion((String) r[3], newestKeeper) >= 0)});
		return out;
	}

	static void start() {
		Thread t = new Thread(() -> {
			long looked = 0;
			while (true) {
				try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
				try {
					String mode = mode(), tag = mode + " update: ";
					if (mode.equals("normal")) { gatherSince = 0; status = ""; continue; }
					long now = System.currentTimeMillis();
					if (now - looked > 60_000) { looked = now; lookForNewer(); }   // (in these modes it looks every minute, not every five)
					if (!newer()) { gatherSince = 0; saidServers = false; stuck = false; status = "Up to date. Looking for a newer one every minute."; continue; }
					if (now < notBefore) continue;
					// (never the same published file twice: if it was put in place and this is still the older version,
					// what was published is at fault, and updating to it again and again would only keep restarting)
					String target = newestKeeper + "|" + newestKeeperSha;
					if (target.equals(read(dir.resolve("auto-update-tried.txt")).trim())) { status = newestKeeper + " was installed by itself already, but this is still " + ownVersion() + ". Not trying that one again by itself: press Update Gatekeeper, or wait for the next version."; gatherSince = 0; stuck = true; continue; }
					stuck = false;
					List<String> waitingFor = new ArrayList<>(), ready = new ArrayList<>();
					if (mode.equals("constellation")) {
						if (gatherSince == 0) { gatherSince = now; saidServers = false; log(tag + newestKeeper + " is out. Waiting for the launchers that are open to see it too."); }
						for (Object[] o : open()) ((Boolean) o[1] ? ready : waitingFor).add((String) o[0]);
						long waited = now - gatherSince;
						if (waited < 15_000 || (!waitingFor.isEmpty() && waited < 600_000)) { status = newestKeeper + " is out. Waiting for " + (waitingFor.isEmpty() ? "the open launchers to report" : String.join(", ", waitingFor) + " to see it") + "."; continue; }
					}
					if (DirectHost.anyTracked()) {
						status = newestKeeper + (mode.equals("constellation") ? " is ready for everyone." : " is out.") + " Waiting for the servers this Gatekeeper runs to be off.";
						if (!saidServers) { saidServers = true; log(tag + status); }
						continue;
					}
					if (mode.equals("constellation")) {
						goUntil = now + 90_000;
						status = "Updating to " + newestKeeper + " now" + (ready.isEmpty() ? "" : ", with " + String.join(", ", ready)) + ".";
						log(tag + status + (waitingFor.isEmpty() ? "" : " Not waited for any longer: " + String.join(", ", waitingFor) + "."));
						Thread.sleep(8000);   // (so every open launcher has asked once and been told to go)
					} else { status = "Updating to " + newestKeeper + " now."; log(tag + status); }
					String[] r = applyUpdate(0);
					log(tag + r[1]);
					if (r[0].equals("ok")) try { java.nio.file.Files.writeString(dir.resolve("auto-update-tried.txt"), target, java.nio.charset.StandardCharsets.UTF_8); } catch (IOException ignored) {}
					gatherSince = 0;
					if (!r[0].equals("ok")) { notBefore = System.currentTimeMillis() + 600_000; status = "The update didn't go through (" + r[1] + "). Trying again in ten minutes."; }
				} catch (InterruptedException e) { return; }
				catch (RuntimeException e) { log("updating by itself: " + e); }
			}
		}, "auto-update");
		t.setDaemon(true);
		t.start();
	}

	/** The constellation: this Gatekeeper, and every launcher that has introduced itself to it, each with its version,
	 *  its update mode and whether it is open now (as it last reported; a launcher from before it reported is "not said"). */
	static Map<String, Object> constellation() {
		String name = setting("name", "");
		if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Gatekeeper"; }
		List<Object> clients = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Object> e : devices().entrySet()) {
			Map<String, Object> d = Json.map(e.getValue());
			Object[] r = seen.get(e.getKey());
			long last = contact.getOrDefault(e.getKey(), 0L);
			boolean open = (r != null && now - (Long) r[0] < 25_000) || now - last < 20_000;
			clients.add(m("id", e.getKey(), "type", "Gatehouse (launcher)", "name", Json.str(d.get("name")), "player", Json.str(d.get("player")), "system", Json.str(d.get("os")),
					"version", r == null ? Json.str(d.get("version")) : (String) r[3], "mode", r == null ? Json.str(d.get("update_mode")) : (String) r[1], "open", open, "last_seen", Json.str(d.get("last_seen")), "here", ServerTools.yes(d.get("local"))));
		}
		return m("gatekeeper", m("type", "Gatekeeper", "name", name, "system", systemName(), "version", ownVersion(), "mode", mode(), "newest", newestKeeper, "status", mode().equals("normal") ? (newer() ? newestKeeper + " is out: press Update Gatekeeper." : "Up to date.") : (status.isEmpty() ? "On." : status)), "clients", clients);
	}

	/** The window's calls: GET /admin/auto (how it stands), POST {mode}; GET /admin/constellation. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/constellation")) { reply(x, 200, constellation()); return true; }
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
