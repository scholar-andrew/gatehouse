package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dev.gatehouse.look.Quiet;

/**
 * Waking this machine from outside the home (Wake-on-LAN from the internet).
 *
 * A sleeping machine only hears its wake signal (a "magic packet") inside its own home network, so something there
 * has to pass a request from outside on to it. Two ways, used together:
 *
 *   Router     The owner forwards a UDP port on the home router to the home network (its broadcast address, or this
 *              machine's address with a fixed entry for it in the router). Launchers send the wake signal to the
 *              public name and that port (host.json "wake_router", "name:port"). Any router that allows it works with no
 *              other device; the signal itself carries no secret, so anyone who finds the port could wake the machine.
 *   Relay      Another Gatekeeper at home that stays awake (a laptop, a Raspberry Pi, an old PC: any machine running
 *              Gatekeeper) wakes this one when a launcher asks it to with a ticket. The owner makes a relay code here
 *              (Host setup, Wake) and pastes it into the relay's Gatekeeper, which then tells this machine its address.
 *
 * Tickets: this machine hands each launcher allowed machine wake and sleep (and not shut out) a ticket per relay in
 * GET /v1/mcss: an expiry 30 days on and HMAC-SHA256(that relay's secret, "wake|relay|launcher|expiry"). Launchers
 * remember it, so it works while this machine sleeps. The relay holds only the secret, the network card's address and
 * a name, so it needs no list of launchers, and checks a ticket by itself. A launcher that loses the permission gets
 * no new ticket; the one it has runs out within 30 days (Remove relay, or a new relay code, ends them at once).
 *
 * host.json on this machine: "wake_mac" (the network card that listens, found by itself unless set),
 * "wake_router", "wake_relays" [{id, secret, name, url, fp, made_at, joined_at}].
 * host.json on a relay:      "wake_for" [{id, secret, name, mac, keeper, joined_at, woke_at}].
 *
 *   POST /v1/wake          {relay, launcher, exp, sig}   (on a relay: any device, no identity: the ticket is the proof)
 *   POST /v1/wake/register {relay, url, fp, name, proof} (on this machine: a relay telling it where it is)
 *   GET  /admin/wake/outside                                   the window: everything above
 *   POST /admin/wake/outside {mac} {router} {code: name} {remove_relay: id} {join: code} {remove_for: id} {test_for: id}
 */
final class Wake {
	private Wake() {}

	static final long TICKET_MS = 30L * 24 * 3600_000;

	// ------------------------------------------------------------------ this machine's network card

	static String macOf(String s) { return WebPanel.macOf(s); }

	/** The network card a wake signal reaches: the one set in Host setup, else the adapter of this machine's
	 *  home-network address (the one Gatekeeper is bound to when that is a home address). Empty if none is found. */
	static String mac() {
		String set = macOf(setting("wake_mac", ""));
		return set.isEmpty() ? detected() : set;
	}
	/** (looking through the network adapters takes a moment, and launchers ask every few seconds: kept a minute) */
	private static volatile Object[] cache = {0L, "", List.of()};
	static String detected() { look(); return (String) cache[1]; }
	@SuppressWarnings("unchecked")
	static List<Object> broadcasts() { look(); return new ArrayList<>((List<Object>) cache[2]); }
	private static void look() {
		long now = System.currentTimeMillis();
		if (now - (Long) cache[0] < 60_000) return;
		cache = new Object[] {now, findMac(), findBroadcasts()};
	}
	private static String findMac() {
		String bind = setting("bind", ""), best = "", any = "";
		try {
			for (NetworkInterface n : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
				byte[] hw = n.getHardwareAddress();
				if (hw == null || hw.length != 6) continue;
				for (InetAddress a : java.util.Collections.list(n.getInetAddresses())) {
					String ip = a.getHostAddress();
					if (!Net.kind(ip).equals("home")) continue;
					String mac = macOf(WebPanel.hex(hw));
					if (ip.equals(bind)) best = mac;
					if (any.isEmpty()) any = mac;
				}
			}
		} catch (IOException | RuntimeException e) { Quiet.seen(e); }
		return best.isEmpty() ? any : best;
	}
	/** This machine's home networks' broadcast addresses (for launchers at home). */
	private static List<Object> findBroadcasts() {
		List<Object> out = new ArrayList<>();
		try {
			for (NetworkInterface n : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp() || n.isLoopback()) continue;
				for (java.net.InterfaceAddress ia : n.getInterfaceAddresses()) {
					if (ia.getBroadcast() == null || !Net.kind(ia.getAddress().getHostAddress()).equals("home")) continue;
					String b = ia.getBroadcast().getHostAddress();
					if (!out.contains(b)) out.add(b);
				}
			}
		} catch (IOException | RuntimeException e) { Quiet.seen(e); }
		return out;
	}
	/** "name:port" launchers send the wake signal to from outside, or empty. */
	static String router() { return setting("wake_router", ""); }

	// ------------------------------------------------------------------ tickets

	static String hmac(String secret, String text) {
		try {
			javax.crypto.Mac h = javax.crypto.Mac.getInstance("HmacSHA256");
			h.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return WebPanel.hex(h.doFinal(text.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
	}
	static String signed(String secret, String relay, String launcher, long exp) { return hmac(secret, "wake|" + relay + "|" + launcher + "|" + exp); }

	static List<Object> relays() { return Json.list(cfg.get("wake_relays")); }
	static List<Object> wakeFor() { return Json.list(cfg.get("wake_for")); }

	/** What a launcher is told about waking this machine (GET /v1/mcss "wake"), or null when it may not wake it. */
	static Map<String, Object> forLauncher(String launcher, boolean allowed) {
		String mac = mac();
		if (!allowed || mac.isEmpty()) return null;
		long exp = System.currentTimeMillis() + TICKET_MS;
		List<Object> rs = new ArrayList<>();
		for (Object o : relays()) {
			Map<String, Object> r = Json.map(o);
			if (Json.str(r.get("url")).isEmpty()) continue;   // (a code made but not yet used)
			rs.add(m("relay", Json.str(r.get("id")), "name", Json.str(r.get("name")), "url", Json.str(r.get("url")), "fp", Json.str(r.get("fp")),
					"exp", String.valueOf(exp), "sig", signed(Json.str(r.get("secret")), Json.str(r.get("id")), launcher, exp)));
		}
		return m("mac", mac, "broadcasts", broadcasts(), "router", router(), "relays", rs);
	}

	// ------------------------------------------------------------------ the magic packet

	/** Sends the wake signal for a network card on every network this machine has a broadcast address on, to UDP 9
	 *  and 7. Returns how many networks it went out on. */
	static int send(String mac) {
		byte[] hw = WebPanel.unhex(macOf(mac).replace(":", "")), packet = new byte[102];
		if (hw.length != 6) return 0;
		for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
		for (int i = 6; i < 102; i++) packet[i] = hw[(i - 6) % 6];
		String test = System.getenv("OJ_WAKE_TEST");   // (testing: send it there instead, host:port)
		if (test != null && test.contains(":")) {
			int c = test.lastIndexOf(':');
			try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
				s.send(new java.net.DatagramPacket(packet, packet.length, InetAddress.getByName(test.substring(0, c)), Integer.parseInt(test.substring(c + 1))));
				return 1;
			} catch (IOException | RuntimeException e) { Quiet.seen(e); return 0; }
		}
		int sent = 0;
		try {
			for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp() || ni.isLoopback()) continue;
				for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
					if (ia.getBroadcast() == null) continue;
					try (java.net.DatagramSocket s = new java.net.DatagramSocket(0, ia.getAddress())) {
						s.setBroadcast(true);
						for (int port : new int[] {9, 7}) s.send(new java.net.DatagramPacket(packet, packet.length, ia.getBroadcast(), port));
						sent++;
					} catch (IOException ignored) { Quiet.seen(ignored); }
				}
			}
		} catch (IOException ignored) { Quiet.seen(ignored); }
		return sent;
	}

	// ------------------------------------------------------------------ as a relay: POST /v1/wake

	private static final Map<String, Long> lastWake = new ConcurrentHashMap<>();
	private static final Map<String, long[]> badTries = new ConcurrentHashMap<>();

	static void relayWake(HttpExchange x, String ip) throws IOException {
		Map<String, Object> b = body(x);
		String relay = Json.str(b.get("relay")), launcher = Json.str(b.get("launcher")), sig = Json.str(b.get("sig")).toLowerCase(java.util.Locale.ROOT);
		long exp;
		try { exp = Long.parseLong(Json.str(b.get("exp"))); } catch (NumberFormatException e) { exp = 0; }
		long now = System.currentTimeMillis(), minute = now / 60_000;
		long[] bad = badTries.get(ip);
		if (bad != null && bad[0] == minute && bad[1] >= 10) { reply(x, 429, m("result", "refused", "message", "Too many wrong tickets: wait a minute.")); return; }
		Map<String, Object> entry = null;
		for (Object o : wakeFor()) if (Json.str(Json.map(o).get("id")).equals(relay)) entry = Json.map(o);
		boolean ok = entry != null && launcher.matches("[0-9a-fA-F-]{16,64}") && exp > now && exp <= now + TICKET_MS + 3600_000
				&& java.security.MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
						signed(Json.str(entry.get("secret")), relay, launcher, exp).getBytes(StandardCharsets.UTF_8));
		if (!ok) {
			badTries.compute(ip, (k, v) -> v == null || v[0] != minute ? new long[] {minute, 1} : new long[] {minute, v[1] + 1});
			if (badTries.size() > 5000) badTries.clear();
			log("wake relay: refused a wake request from " + ip + (entry == null ? " (no such machine here)" : exp <= now ? " (its ticket ran out)" : " (a ticket that doesn't fit)"));
			reply(x, 403, m("result", "refused", "message", entry == null ? "This Gatekeeper doesn't wake that machine (any more)."
					: exp <= now ? "That wake ticket ran out: open Gatehouse while the machine is awake to get a new one." : "That wake ticket isn't right."));
			return;
		}
		String name = Json.str(entry.get("name"));
		Long before = lastWake.get(relay);
		if (before != null && now - before < 15_000) { reply(x, 200, m("result", "ok", "message", name + " was just sent the wake signal: give it a minute.")); return; }
		lastWake.put(relay, now);
		int n = send(Json.str(entry.get("mac")));
		synchronized (Wake.class) { entry.put("woke_at", now); try { ServerTools.saveCfg(); } catch (IOException e) { Quiet.seen(e); } }
		log("wake relay: woke " + name + " (" + macOf(Json.str(entry.get("mac"))) + ") for launcher " + launcher.substring(0, 8) + " from " + ip);
		reply(x, 200, n == 0 ? m("result", "failed", "message", "The relay is on no network it can send a wake signal on.")
				: m("result", "ok", "message", "The relay at home sent " + name + " the wake signal: give it a minute."));
	}

	// ------------------------------------------------------------------ this machine: a relay saying where it is

	static void register(HttpExchange x, String ip) throws IOException {
		Map<String, Object> b = body(x);
		String id = Json.str(b.get("relay")), url = Json.str(b.get("url")).trim().replaceAll("/+$", ""), fp = Json.str(b.get("fp")).trim(),
				name = clean(Json.str(b.get("name")), 40), proof = Json.str(b.get("proof")).toLowerCase(java.util.Locale.ROOT);
		synchronized (Wake.class) {
			List<Object> list = new ArrayList<>(relays());
			Map<String, Object> r = null;
			for (Object o : list) if (Json.str(Json.map(o).get("id")).equals(id)) r = Json.map(o);
			if (r == null || !java.security.MessageDigest.isEqual(proof.getBytes(StandardCharsets.UTF_8),
					hmac(Json.str(r.get("secret")), "register|" + id + "|" + url + "|" + fp).getBytes(StandardCharsets.UTF_8))) {
				log("wake: refused a relay sign-up from " + ip);
				reply(x, 403, m("result", "refused", "message", "That relay code isn't one this Gatekeeper made (or it was removed)."));
				return;
			}
			if (!url.matches("(?i)https?://[a-z0-9.\\-\\[\\]:]+")) { reply(x, 200, m("result", "refused", "message", "That isn't an address.")); return; }
			r.put("url", url); r.put("fp", fp); r.put("name", name.isEmpty() ? "relay" : name); r.put("joined_at", System.currentTimeMillis());
			cfg.put("wake_relays", list); ServerTools.saveCfg();
			log("wake: " + Json.str(r.get("name")) + " at " + url + " is now a wake relay for this machine (sign-up from " + ip + ")");
		}
		reply(x, 200, m("result", "ok", "name", setting("name", ""), "mac", mac()));
	}

	// ------------------------------------------------------------------ relay codes

	/** A relay code: everything the relay needs, as one line to paste. It holds the relay's secret: treat it like a password. */
	static String newCode(String label) throws IOException {
		String mac = mac();
		if (mac.isEmpty()) throw new IOException("This machine's network card isn't known: fill it in above first.");
		byte[] s = new byte[32]; new java.security.SecureRandom().nextBytes(s);
		String id = java.util.UUID.randomUUID().toString(), secret = WebPanel.hex(s);
		synchronized (Wake.class) {
			List<Object> list = new ArrayList<>(relays());
			list.add(m("id", id, "secret", secret, "name", clean(label, 40), "url", "", "fp", "", "made_at", System.currentTimeMillis()));
			cfg.put("wake_relays", list); ServerTools.saveCfg();
		}
		List<Object> doors = new ArrayList<>();
		String bind = setting("bind", ""), pub = Net.reachable();
		doors.add(Tls.scheme() + "://" + bind + ":" + port());
		if (!pub.equals(bind)) doors.add(Tls.scheme() + "://" + pub + ":" + port());
		String name = setting("name", "");
		if (name.isEmpty()) try { name = InetAddress.getLocalHost().getHostName(); } catch (IOException e) { Quiet.seen(e); }
		Map<String, Object> code = m("id", id, "secret", secret, "mac", mac, "name", name, "doors", doors, "fp", Tls.on() ? Tls.fingerprint() : "");
		log("wake: made a relay code" + (label.isBlank() ? "" : " for " + clean(label, 40)));
		return "gatehouse-wake:" + Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(code).getBytes(StandardCharsets.UTF_8));
	}

	/** On the relay: takes a relay code, tells the sleeping machine where this relay is, and keeps what it needs. */
	static String[] join(String code) {
		code = code.trim();
		if (!code.startsWith("gatehouse-wake:")) return new String[] {"refused", "That isn't a relay code (it starts gatehouse-wake:)."};
		Map<String, Object> c;
		try { c = Json.map(Json.parse(new String(Base64.getUrlDecoder().decode(code.substring("gatehouse-wake:".length())), StandardCharsets.UTF_8))); }
		catch (RuntimeException e) { return new String[] {"refused", "That relay code is damaged: copy it again."}; }
		String id = Json.str(c.get("id")), secret = Json.str(c.get("secret")), mac = macOf(Json.str(c.get("mac"))), name = clean(Json.str(c.get("name")), 40),
				fp = Json.str(c.get("fp"));
		if (id.isEmpty() || secret.length() < 32 || mac.isEmpty()) return new String[] {"refused", "That relay code is incomplete: copy it again."};
		if (mac.equals(mac())) return new String[] {"refused", "That code is for this machine itself: paste it into the Gatekeeper of another machine at home that stays awake."};
		String myUrl = Tls.scheme() + "://" + Net.reachable() + ":" + port(), myFp = Tls.on() ? Tls.fingerprint() : "";
		String myName = setting("name", "");
		if (myName.isEmpty()) try { myName = InetAddress.getLocalHost().getHostName(); } catch (IOException e) { Quiet.seen(e); }
		String body = Json.write(m("relay", id, "url", myUrl, "fp", myFp, "name", myName, "proof", hmac(secret, "register|" + id + "|" + myUrl + "|" + myFp)));
		String said = "", where = "";
		for (Object d : Json.list(c.get("doors"))) {
			String door = Json.str(d);
			try {
				if (door.startsWith("https:") && !fp.isEmpty()) dev.octoberjourney.popup.Pins.pin(dev.octoberjourney.popup.Pins.hostPort(door), fp);
				HttpURLConnection h = (HttpURLConnection) new java.net.URL(door + "/v1/wake/register").openConnection();
				h.setConnectTimeout(4000); h.setReadTimeout(8000); h.setRequestMethod("POST"); h.setDoOutput(true);
				h.setRequestProperty("Content-Type", "application/json");
				try (OutputStream o = h.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
				int code2 = h.getResponseCode();
				try (InputStream in = code2 < 400 ? h.getInputStream() : h.getErrorStream()) {
					Map<String, Object> r = Json.map(Json.parse(in == null ? "{}" : new String(in.readAllBytes(), StandardCharsets.UTF_8)));
					if (code2 == 200 && "ok".equals(Json.str(r.get("result")))) { where = door; break; }
					said = Json.str(r.get("message"));
				}
			} catch (IOException | RuntimeException e) { said = "nothing answered at " + door; }
		}
		if (where.isEmpty()) return new String[] {"failed", "Couldn't tell " + (name.isEmpty() ? "that machine" : name) + " about this relay"
				+ (said.isEmpty() ? "" : " (" + said + ")") + ". It has to be awake, and this machine has to reach it (the same home network, or Tailscale)."};
		synchronized (Wake.class) {
			List<Object> list = new ArrayList<>(wakeFor());
			list.removeIf(o -> Json.str(Json.map(o).get("id")).equals(id) || macOf(Json.str(Json.map(o).get("mac"))).equals(mac));
			list.add(m("id", id, "secret", secret, "name", name.isEmpty() ? mac : name, "mac", mac, "keeper", where, "joined_at", System.currentTimeMillis()));
			cfg.put("wake_for", list);
			try { ServerTools.saveCfg(); } catch (IOException e) { return new String[] {"failed", "Couldn't save: " + e.getMessage()}; }
		}
		log("wake relay: this Gatekeeper now wakes " + (name.isEmpty() ? mac : name) + " (" + mac + ") for its launchers");
		return new String[] {"ok", "This Gatekeeper now wakes " + (name.isEmpty() ? mac : name) + " for its launchers. Keep this machine awake and Gatekeeper running"
				+ (Net.internet() ? ", and forward its port " + port() + " in your router too (a different port from the other machine's)" : "")
				+ ". Launchers pick the relay up the next time they talk to " + (name.isEmpty() ? "that machine" : name) + " while it's awake."};
	}

	// ------------------------------------------------------------------ the window: /admin/wake/outside

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/wake/outside")) return false;
		if (!x.getRequestMethod().equals("POST")) {
			List<Object> rs = new ArrayList<>(), fs = new ArrayList<>();
			for (Object o : relays()) { Map<String, Object> r = Json.map(o); rs.add(m("id", r.get("id"), "name", r.get("name"), "url", r.get("url"),
					"made_at", r.get("made_at"), "joined_at", r.get("joined_at"))); }
			for (Object o : wakeFor()) { Map<String, Object> r = Json.map(o); fs.add(m("id", r.get("id"), "name", r.get("name"), "mac", r.get("mac"),
					"joined_at", r.get("joined_at"), "woke_at", r.get("woke_at"))); }
			reply(x, 200, m("mac", mac(), "mac_set", macOf(setting("wake_mac", "")), "mac_detected", detected(), "router", router(),
					"public", setting("public_host", ""), "broadcasts", broadcasts(), "relays", rs, "wake_for", fs, "port", port(),
					"network", Net.mode(), "reachable", Tls.scheme() + "://" + Net.reachable() + ":" + port()));
			return true;
		}
		Map<String, Object> b = body(x);
		String[] r;
		if (b.containsKey("mac")) {
			String raw = Json.str(b.get("mac")).trim(), mac = macOf(raw);
			if (!raw.isEmpty() && mac.isEmpty()) r = new String[] {"refused", "A network card's address is twelve hex digits, like 1C:2D:3E:4F:5A:6B."};
			else { cfg.put("wake_mac", mac); ServerTools.saveCfg(); log("wake: network card " + (mac.isEmpty() ? "found by itself (" + detected() + ")" : "set to " + mac));
				r = new String[] {"ok", mac.isEmpty() ? "Gatekeeper finds the network card by itself" + (detected().isEmpty() ? ", but found none on a home network." : ": " + detected() + ".") : "Network card set to " + mac + "."}; }
		} else if (b.containsKey("router")) {
			String raw = Json.str(b.get("router")).trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^[a-z]+://", "");
			if (raw.isEmpty()) { cfg.remove("wake_router"); ServerTools.saveCfg(); log("wake: no router wake address"); r = new String[] {"ok", "No wake through the router."}; }
			else {
				if (!raw.contains(":")) raw = raw + ":9";
				int c = raw.lastIndexOf(':');
				String host = raw.substring(0, c), p = raw.substring(c + 1);
				int pn = -1;
				try { pn = Integer.parseInt(p); } catch (NumberFormatException e) { Quiet.seen(e); }
				if (pn < 1 || pn > 65535 || !host.matches("[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?)+"))
					r = new String[] {"refused", "That isn't a name and port like myhome.duckdns.org:9."};
				else { cfg.put("wake_router", host + ":" + pn); ServerTools.saveCfg(); log("wake: launchers also send the wake signal to " + host + ":" + pn);
					r = new String[] {"ok", "Launchers allowed to wake this machine send the wake signal to " + host + ":" + pn + " too. In your router, forward UDP port " + pn
							+ " to " + (broadcasts().isEmpty() ? "this home network's broadcast address" : broadcasts().get(0)) + " (or to this machine's address, if the router keeps a fixed entry for it)."}; }
			}
		} else if (b.containsKey("code")) {
			try { r = new String[] {"ok", newCode(Json.str(b.get("code")))}; } catch (IOException e) { r = new String[] {"refused", e.getMessage()}; }
		} else if (b.containsKey("remove_relay")) {
			String id = Json.str(b.get("remove_relay"));
			synchronized (Wake.class) {
				List<Object> list = new ArrayList<>(relays());
				boolean gone = list.removeIf(o -> Json.str(Json.map(o).get("id")).equals(id));
				cfg.put("wake_relays", list); ServerTools.saveCfg();
				r = gone ? new String[] {"ok", "Removed. Launchers get no new tickets for that relay, and its secret is forgotten here; remove this machine on the relay too."} : new String[] {"refused", "No such relay."};
			}
			if (r[0].equals("ok")) log("wake: a relay was removed");
		} else if (b.containsKey("join")) {
			r = join(Json.str(b.get("join")));
		} else if (b.containsKey("remove_for")) {
			String id = Json.str(b.get("remove_for"));
			synchronized (Wake.class) {
				List<Object> list = new ArrayList<>(wakeFor());
				boolean gone = list.removeIf(o -> Json.str(Json.map(o).get("id")).equals(id));
				cfg.put("wake_for", list); ServerTools.saveCfg();
				r = gone ? new String[] {"ok", "This Gatekeeper no longer wakes that machine."} : new String[] {"refused", "No such machine."};
			}
			if (r[0].equals("ok")) log("wake relay: stopped waking a machine");
		} else if (b.containsKey("test_for")) {
			Map<String, Object> e = null;
			for (Object o : wakeFor()) if (Json.str(Json.map(o).get("id")).equals(Json.str(b.get("test_for")))) e = Json.map(o);
			if (e == null) r = new String[] {"refused", "No such machine."};
			else { int n = send(Json.str(e.get("mac"))); log("wake relay: sent " + Json.str(e.get("name")) + " the wake signal (from this window)");
				r = n == 0 ? new String[] {"failed", "This machine is on no network a wake signal can be sent on."}
						: new String[] {"ok", "Sent " + Json.str(e.get("name")) + " the wake signal. If it sleeps, it should wake within half a minute."}; }
		} else r = new String[] {"refused", "unknown"};
		reply(x, 200, m("result", r[0], "message", r[1]));
		return true;
	}

	/** For the Steps list: a short line on how this machine can be woken from outside. */
	static String summary() {
		int joined = 0;
		for (Object o : relays()) if (!Json.str(Json.map(o).get("url")).isEmpty()) joined++;
		List<String> ways = new ArrayList<>();
		if (!router().isEmpty()) ways.add("through the router (" + router() + ")");
		if (joined > 0) ways.add(joined == 1 ? "through a relay" : "through " + joined + " relays");
		return ways.isEmpty() ? "" : String.join(" and ", ways);
	}
}
