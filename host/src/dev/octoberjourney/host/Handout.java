package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.gatehouse.look.Quiet;
import dev.octoberjourney.popup.PackShare;

/**
 * R40, step 3: Gate Systems handing out pack updates to each other. This Gatekeeper hands out the packs on its own machine
 * (always, unless its owner turns it off on the Constellation tab: host.json "hand_out"), and is the one that tells its
 * launchers which Gate Systems hand out right now: each open launcher says, every few seconds, what it hands out (see
 * AutoUpdate.report), and is answered with the others, the devices it may answer (this Gatekeeper's devices, by their
 * Tailscale addresses) and its own address. A Gatekeeper that joined another's constellation does the same towards that
 * one, and passes on what it hears from there.
 */
final class Handout {
	private Handout() {}

	/** Who hands out right now: client id -> {base ("http://address:port/pack"), packs {variant: version}, when}. */
	static final Map<String, Object[]> sharing = new ConcurrentHashMap<>();
	/** What the Gatekeeper this one joined said: its Gate Systems that hand out, and the devices they answer. */
	static volatile List<Object> leadPeers = List.of(), leadAllow = List.of();

	static boolean on() { return setting("hand_out", "true").equals("true"); }

	/** A client's report: what it hands out, from the address it speaks from. */
	static void heard(String id, Map<String, Object> d, Map<String, Object> b) {
		Map<String, Object> s = Json.map(b.get("share"));
		String ip = Json.str(d.get("ip")), port = Json.str(s.get("port")).replaceAll("\\.0$", "");
		Map<String, Object> packs = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : Json.map(s.get("packs")).entrySet()) if (e.getKey().matches("[\\w.\\-]{1,40}")) packs.put(e.getKey(), Json.str(e.getValue()).replaceAll("[^\\w.\\-+]", ""));
		if (packs.isEmpty() || !port.matches("\\d{2,5}") || !ip.matches("[0-9a-fA-F.:]+") || ip.startsWith("127.")) { sharing.remove(id); return; }
		sharing.put(id, new Object[] {"http://" + (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port + "/pack", packs, System.currentTimeMillis()});
	}

	/** Added to the answer a client gets: the Gate Systems handing out (not itself), the devices it may answer, its own
	 *  address, and whether it is on this Gatekeeper's machine (then it leaves handing out to this Gatekeeper). */
	static void answer(String id, Map<String, Object> d, Map<String, Object> out) {
		String ip = Json.str(d.get("ip"));
		boolean local = ServerTools.yes(d.get("local")) || ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals(setting("bind", ""));
		out.put("you", ip);
		out.put("local", local);
		out.put("allow", allow());
		out.put("peers", peers(id));
	}

	/** The Gate Systems handing out right now, newest packs only, without the one asking. */
	static List<Object> peers(String asking) {
		List<Object[]> all = new ArrayList<>();
		Object[] askers = null;
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Object[]> e : sharing.entrySet()) {
			if (now - (long) e.getValue()[2] > 25_000) { sharing.remove(e.getKey()); continue; }
			all.add(e.getValue());
			if (e.getKey().equals(asking)) askers = e.getValue();
		}
		Map<String, Object> own = PackShare.status();
		if (!own.isEmpty() && !setting("bind", "").isEmpty()) all.add(new Object[] {"http://" + setting("bind", "") + ":" + own.get("port") + "/pack", own.get("packs"), now});
		for (Object o : leadPeers) { Map<String, Object> p = Json.map(o); all.add(new Object[] {Json.str(p.get("base")), Json.map(p.get("packs")), now}); }
		// (only those with the newest version of some pack: one that is behind would only be asked in vain)
		Map<String, String> newest = new LinkedHashMap<>();
		for (Object[] a : all) for (Map.Entry<String, Object> p : Json.map(a[1]).entrySet()) {
			String v = Json.str(p.getValue());
			if (!newest.containsKey(p.getKey()) || cmpVersion(v, newest.get(p.getKey())) > 0) newest.put(p.getKey(), v);
		}
		List<Object> out = new ArrayList<>();
		Set<String> bases = new LinkedHashSet<>();
		for (Object[] a : all) {
			if (a == askers) continue;   // (not itself; but its version counted above)
			boolean current = false;
			for (Map.Entry<String, Object> p : Json.map(a[1]).entrySet()) if (Json.str(p.getValue()).equals(newest.get(p.getKey()))) current = true;
			if (current && bases.add((String) a[0])) out.add(m("base", a[0], "packs", a[1]));
		}
		return out;
	}

	/** The devices this Gatekeeper knows, by the Tailscale address each last spoke from (and those the Gatekeeper this one
	 *  joined knows): the only ones a Gate System it lists answers. */
	static List<Object> allow() {
		Set<String> ips = new LinkedHashSet<>();
		for (Object o : devices().values()) { String ip = Json.str(Json.map(o).get("ip")); if (ip.matches("[0-9a-fA-F.:]+") && !ip.startsWith("127.")) ips.add(ip); }
		for (Object o : leadAllow) { String ip = Json.str(o); if (ip.matches("[0-9a-fA-F.:]+")) ips.add(ip); }
		return new ArrayList<>(ips);
	}

	/** What a client hands out, in words for the Constellation tab ("" for nothing). */
	static String words(String id) {
		Object[] s = sharing.get(id);
		if (s == null || System.currentTimeMillis() - (long) s[2] > 25_000) return "";
		List<String> v = new ArrayList<>();
		for (Map.Entry<String, Object> p : Json.map(s[1]).entrySet()) v.add(Json.str(p.getValue()));
		return "hands out " + String.join(", ", new LinkedHashSet<>(v));
	}
	static String ownWords() {
		Map<String, Object> own = PackShare.status();
		if (own.isEmpty()) return on() ? "" : "not handing out (turned off)";
		List<String> v = new ArrayList<>();
		for (Object o : Json.map(own.get("packs")).values()) v.add(Json.str(o));
		return "hands out " + String.join(", ", new LinkedHashSet<>(v));
	}

	/** A source as a client reports it, with the addresses of the devices this Gatekeeper knows put as their names. */
	static String named(String source) {
		if (source == null || source.isEmpty()) return source;
		String out = source;
		for (Object o : devices().values()) {
			Map<String, Object> d = Json.map(o);
			String ip = Json.str(d.get("ip")), nm = Json.str(d.get("name"));
			if (ip.matches("[0-9a-fA-F.:]+") && !ip.startsWith("127.") && !nm.isEmpty()) out = out.replace(ip, nm);
		}
		return out;
	}

	/** This Gatekeeper's own handing out: kept going (or stopped) every 20 seconds. */
	static void start() {
		Thread t = new Thread(() -> {
			while (true) {
				try {
					List<Path> folders = new ArrayList<>();
					for (Path root : dev.gatehouse.Main.prismRoots()) if (Files.isDirectory(root.resolve("instances"))) folders.add(root.resolve("instances"));
					String bind = setting("bind", "");
					List<String> ips = new ArrayList<>();
					for (Object o : allow()) ips.add(Json.str(o));
					if (on() && !bind.isEmpty() && !folders.isEmpty()) PackShare.keep(bind, ips, folders);
					else PackShare.stop();
				} catch (RuntimeException e) { Quiet.seen(e); }
				try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
			}
		}, "hand-out");
		t.setDaemon(true);
		t.start();
	}
}
