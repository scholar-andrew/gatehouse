package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

/**
 * Updating by itself, together with the launchers that are open. Off unless the owner turns it on in Options
 * (host.json "auto_update"); a launcher takes part only if its own Options say so too.
 *
 * Launchers that have it on report in every few seconds while their window is open (POST /v1/update/state): that
 * they are there, which version they are, and whether they have seen the newer one yet. When a newer program is
 * published, this Gatekeeper:
 *   1. gathers: waits until every launcher that is reporting has seen the update too (at least fifteen seconds, so
 *      they have all had a turn; at most ten minutes, after which the ones that still haven't are left to themselves);
 *   2. waits, if it has to, for the servers it runs itself to be off (updating restarts it, and their consoles with it);
 *   3. gives the go: every reporting launcher is told "go" and updates itself, and a few seconds later this Gatekeeper
 *      updates too, the same way Update Gatekeeper does (checked, and undone if the new one doesn't answer).
 * A launcher that opens later, still on the old version, is told "go" as soon as it reports.
 */
final class AutoUpdate {
	/** A launcher's last report: when, whether it takes part, the newer version it has seen ("" if none yet), its own version, its name. */
	static final Map<String, Object[]> seen = new ConcurrentHashMap<>();
	static volatile long gatherSince = 0, goUntil = 0, notBefore = 0;
	static volatile String status = "";
	private static volatile boolean saidServers = false, stuck = false;

	static boolean on() { return setting("auto_update", "false").equals("true"); }
	static boolean newer() { return !newestKeeper.isEmpty() && cmpVersion(newestKeeper, ownVersion()) > 0; }

	/** A launcher reporting in; what it is told. */
	static Map<String, Object> report(String id, String name, Map<String, Object> b) {
		boolean auto = ServerTools.yes(b.get("auto"));
		String own = Json.str(b.get("own")).replaceAll("[^\\w.\\-]", "");
		seen.put(id, new Object[] {System.currentTimeMillis(), auto, Json.str(b.get("sees")).replaceAll("[^\\w.\\-]", ""), own, name});
		String state = "idle";
		if (on() && auto) {
			if (System.currentTimeMillis() < goUntil) state = "go";
			else if (newer() && !stuck) state = "gathering";
			else if (!own.isEmpty() && cmpVersion(ownVersion(), own) > 0) state = "go";   // (this Gatekeeper has already gone: catch up)
		}
		return m("state", state, "version", newer() ? newestKeeper : ownVersion(), "auto", on());
	}
	/** The launchers that are open right now and take part: {name, has it seen the update}. */
	static List<Object[]> open() {
		List<Object[]> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Object[] r : seen.values()) if (now - (Long) r[0] < 25_000 && (Boolean) r[1]) out.add(new Object[] {r[4], !((String) r[2]).isEmpty()});
		return out;
	}

	static void start() {
		Thread t = new Thread(() -> {
			long looked = 0;
			while (true) {
				try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
				try {
					if (!on()) { gatherSince = 0; status = ""; continue; }
					long now = System.currentTimeMillis();
					if (now - looked > 60_000) { looked = now; lookForNewer(); }   // (with this on, it looks every minute, not every five)
					if (!newer()) { gatherSince = 0; saidServers = false; status = "Up to date. Looking for a newer one every minute."; continue; }
					if (now < notBefore) continue;
					// (never the same published file twice: if it was put in place and this is still the older version,
					// what was published is at fault, and updating to it again and again would only keep restarting)
					String target = newestKeeper + "|" + newestKeeperSha;
					if (target.equals(read(dir.resolve("auto-update-tried.txt")).trim())) { status = newestKeeper + " was installed automatically already, but this is still " + ownVersion() + ". Not trying that one again by itself: press Update Gatekeeper, or wait for the next version."; gatherSince = 0; stuck = true; continue; }
					stuck = false;
					if (gatherSince == 0) { gatherSince = now; saidServers = false; log("auto update: " + newestKeeper + " is out. Waiting for the launchers that are open to see it too."); }
					List<String> waitingFor = new ArrayList<>(), ready = new ArrayList<>();
					for (Object[] o : open()) ((Boolean) o[1] ? ready : waitingFor).add((String) o[0]);
					long waited = now - gatherSince;
					if (waited < 15_000 || (!waitingFor.isEmpty() && waited < 600_000)) { status = newestKeeper + " is out. Waiting for " + (waitingFor.isEmpty() ? "the open launchers to report" : String.join(", ", waitingFor) + " to see it") + "."; continue; }
					if (DirectHost.anyTracked()) {
						status = newestKeeper + " is ready for everyone. Waiting for the servers this Gatekeeper runs to be off.";
						if (!saidServers) { saidServers = true; log("auto update: " + status); }
						continue;
					}
					goUntil = now + 90_000;
					status = "Updating to " + newestKeeper + " now" + (ready.isEmpty() ? "" : ", with " + String.join(", ", ready)) + ".";
					log("auto update: " + status + (waitingFor.isEmpty() ? "" : " Not waited for any longer: " + String.join(", ", waitingFor) + "."));
					Thread.sleep(8000);   // (so every open launcher has asked once and been told to go)
					String[] r = applyUpdate(0);
					log("auto update: " + r[1]);
					if (r[0].equals("ok")) try { java.nio.file.Files.writeString(dir.resolve("auto-update-tried.txt"), target, java.nio.charset.StandardCharsets.UTF_8); } catch (IOException ignored) {}
					gatherSince = 0;
					if (!r[0].equals("ok")) { notBefore = System.currentTimeMillis() + 600_000; status = "The update didn't go through (" + r[1] + "). Trying again in ten minutes."; }
				} catch (InterruptedException e) { return; }
				catch (RuntimeException e) { log("auto update: " + e); }
			}
		}, "auto-update");
		t.setDaemon(true);
		t.start();
	}

	/** The window's calls: GET /admin/auto (how it stands), POST {on}. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/auto")) return false;
		if (x.getRequestMethod().equals("POST")) {
			boolean want = ServerTools.yes(body(x).get("on"));
			cfg.put("auto_update", want ? "true" : "false");
			ServerTools.saveCfg();
			gatherSince = 0; notBefore = 0;
			log("automatic updating turned " + (want ? "on" : "off"));
		}
		List<Object> names = new ArrayList<>();
		for (Object[] o : open()) names.add(o[0]);
		reply(x, 200, m("on", on(), "status", on() ? (status.isEmpty() ? "On." : status) : "Off: you press Update Gatekeeper yourself.", "launchers", names));
		return true;
	}
}
