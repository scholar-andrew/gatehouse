package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R79 (stage 1): how devices reach this Gatekeeper. host.json "network":
 *   tailscale  (the default) Tailscale's private network: "bind" is this machine's Tailscale address (100.64.0.0/10).
 *   home       the home network: "bind" is this machine's address on it (10.x, 172.16-31.x, 192.168.x). Only devices
 *              at home can reach it. The web panel stays off in this mode until Gatekeeper has HTTPS (stage 2): a
 *              home network isn't encrypted the way Tailscale is, and its passwords would cross it readable.
 *   private    another private network the owner runs (ZeroTier, NetBird, Headscale, Radmin VPN...): "bind" is any
 *              address this machine has on it.
 * Whatever the mode, Gatekeeper listens on that one address (and 127.0.0.1 for its own window), nothing else, and
 * nothing is opened to the internet. Chosen at the machine only (Host setup):
 *   GET  /admin/network                 {network, bind, port, addresses: [{address, adapter, kind}]}
 *   POST /admin/network {network, bind}
 *   internet   (stage 3) open to the internet through the owner's router: "bind" is this machine's home-network address,
 *              which the router forwards Gatekeeper's port to; "public" is the name or address players use (a
 *              dynamic DNS name, say). HTTPS is always on in this mode; a new launcher may introduce itself only with
 *              the join key (in the pairing) or an invite's code; requests are limited per address; the web panel and
 *              the status page stay off. Gatekeeper never changes the router: the owner forwards the ports.
 *   POST /admin/network {public}        the public name or address (internet mode)
 *   POST /admin/network {join_key: "new"} a new join key (pairings made before stop working for new launchers)
 */
final class Net {
	private Net() {}

	static String mode() { String n = setting("network", "tailscale"); return n.equals("home") || n.equals("private") || n.equals("internet") ? n : "tailscale"; }
	static boolean internet() { return mode().equals("internet"); }
	/** The name or address others use to reach this Gatekeeper: the public one on the internet, else its own. */
	static String reachable() { String p = setting("public_host", ""); return internet() && !p.isEmpty() ? p : setting("bind", ""); }
	/** The key a new launcher has to bring (internet mode), made the first time it is needed. */
	static synchronized String joinKey() {
		String k = setting("join_key", "");
		if (k.isEmpty()) { k = java.util.HexFormat.of().formatHex(rnd()); cfg.put("join_key", k); try { ServerTools.saveCfg(); } catch (IOException e) { dev.gatehouse.look.Quiet.seen(e); } }
		return k;
	}
	private static byte[] rnd() { byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b); return b; }
	/** A request from this address too many times this minute? (internet mode: 240 a minute, 20 introductions) */
	private static final java.util.Map<String, long[]> counts = new java.util.concurrent.ConcurrentHashMap<>();
	static boolean tooMany(String ip, boolean hello) {
		if (!internet()) return false;
		long minute = System.currentTimeMillis() / 60_000;
		long[] c = counts.compute(ip + (hello ? "|hello" : ""), (k, v) -> v == null || v[0] != minute ? new long[] {minute, 1} : new long[] {minute, v[1] + 1});
		if (counts.size() > 5000) counts.clear();
		return c[1] > (hello ? 20 : 240);
	}
	static boolean tailscale() { return mode().equals("tailscale"); }
	/** The network's name in a sentence: "Tailscale", "your home network", "your private network". */
	static String word() { return switch (mode()) { case "home" -> "your home network"; case "private" -> "your private network"; case "internet" -> "the internet"; default -> "Tailscale"; }; }
	/** The web panel needs an encrypted network (Tailscale, or a private one, which encrypts as a rule) until HTTPS. */
	static boolean panelAllowed() { return !internet() && (!mode().equals("home") || Tls.on()); }   // (never facing the internet)

	/** What kind of address an IPv4 address is: "tailscale", "home" or "other". */
	static String kind(String a) {
		String[] p = a.split("\\.");
		if (p.length != 4) return "other";
		int x, y;
		try { x = Integer.parseInt(p[0]); y = Integer.parseInt(p[1]); } catch (NumberFormatException e) { return "other"; }
		if (x == 100 && y >= 64 && y <= 127) return "tailscale";
		if (x == 10 || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168)) return "home";
		return "other";
	}
	/** This machine's IPv4 addresses (not 127.x, not 169.254.x), each with its adapter's name and kind. */
	static List<Object> addresses() {
		List<Object> out = new ArrayList<>();
		try {
			for (NetworkInterface n : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp() || n.isLoopback()) continue;
				for (InetAddress a : java.util.Collections.list(n.getInetAddresses())) {
					if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
					String ip = a.getHostAddress();
					out.add(m("address", ip, "adapter", n.getDisplayName() == null ? n.getName() : n.getDisplayName(), "kind", kind(ip)));
				}
			}
		} catch (IOException | RuntimeException e) { dev.gatehouse.look.Quiet.seen(e); }
		return out;
	}
	/** Which of the modes an address may be used for. */
	static boolean fits(String mode, String kind) {
		return switch (mode) { case "tailscale" -> kind.equals("tailscale"); case "home", "internet" -> kind.equals("home"); default -> true; };
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/network")) return false;
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			if (b.containsKey("public")) {   // (internet mode: the name or address players use)
				String p = Json.str(b.get("public")).trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^[a-z]+://", "").replaceFirst("[:/].*$", "");
				if (!p.isEmpty() && !p.matches("[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?)+")) { reply(x, 200, m("result", "refused", "message", "That isn't a name or address like myhome.duckdns.org or 203.0.113.5.")); return true; }
				cfg.put("public_host", p); ServerTools.saveCfg();
				log("network: the public name is " + (p.isEmpty() ? "(none)" : p));
				reply(x, 200, m("result", "ok", "message", p.isEmpty() ? "No public name." : "Players reach this Gatekeeper at " + p + ":" + port() + " once your router forwards that port. Make a new pairing (Share pairing)."));
				return true;
			}
			if (b.containsKey("join_key")) { cfg.remove("join_key"); joinKey(); log("network: a new join key; pairings made before no longer let new launchers in");
				reply(x, 200, m("result", "ok", "message", "A new join key: pairings made before no longer let new launchers in (those already in stay in). Share pairing gives the new one.")); return true; }
			if (b.containsKey("https") || b.containsKey("renew")) {   // (R79 stage 2: HTTPS with this Gatekeeper's own certificate)
				try {
					if (ServerTools.yes(b.get("renew"))) Tls.renew();
					boolean on = b.containsKey("https") ? ServerTools.yes(b.get("https")) : Tls.on();
					if (!on && internet()) { reply(x, 200, m("result", "refused", "message", "Facing the internet HTTPS stays on. Choose another network first to turn it off.")); return true; }
					if (on) Tls.context();   // (made now, so a problem shows here rather than in the log)
					cfg.put("https", on ? "true" : "false");
					ServerTools.saveCfg();
					log("https: " + (on ? "on, certificate " + Tls.fingerprint() : "off"));
					bindRemote();
					reply(x, 200, m("result", "ok", "message", (on ? "HTTPS is on: launchers need the new pairing (Share pairing), which carries this certificate's fingerprint. Launchers older than 3.50.0 can't use it."
							: "HTTPS is off: plain HTTP again, so launchers need the new pairing.") + (ServerTools.yes(b.get("renew")) ? " A new certificate was made." : "")));
				} catch (IOException e) { reply(x, 200, m("result", "failed", "message", "HTTPS couldn't be turned on: " + e.getMessage())); }
				return true;
			}
			String mode = Json.str(b.get("network")), bind = Json.str(b.get("bind")).trim();
			if (!mode.equals("tailscale") && !mode.equals("home") && !mode.equals("private") && !mode.equals("internet")) { reply(x, 200, m("result", "refused", "message", "No such network.")); return true; }
			boolean mine = false;
			for (Object o : addresses()) if (Json.str(Json.map(o).get("address")).equals(bind)) mine = true;
			if (!mine) { reply(x, 200, m("result", "refused", "message", bind.isEmpty() ? "Choose one of this machine's addresses." : bind + " isn't an address of this machine (now).")); return true; }
			if (!fits(mode, kind(bind))) { reply(x, 200, m("result", "refused", "message", mode.equals("tailscale")
					? bind + " isn't a Tailscale address (those start 100.)." : bind + " isn't a home-network address (those start 192.168., 10. or 172.16 to 172.31.).")); return true; }
			boolean moved = !bind.equals(setting("bind", "")) || !mode.equals(mode());
			cfg.put("network", mode); cfg.put("bind", bind);
			if (mode.equals("internet")) { cfg.put("https", "true"); joinKey(); }   // (always HTTPS facing the internet)
			ServerTools.saveCfg();
			if (moved) {
				log("network: devices reach this Gatekeeper over " + word() + ", at " + bind + ":" + port());
				bindRemote();
			}
			reply(x, 200, m("result", "ok", "message", "Devices reach this Gatekeeper over " + word() + " now, at " + bind + ":" + port() + "."
					+ (moved ? " Launchers paired with the old address need the new pairing (Share pairing)." : "")
					+ (mode.equals("home") ? " The web panel stays off on a home network unless HTTPS is on." : "")
					+ (mode.equals("internet") ? " HTTPS is on, the web panel and status page are off, and new launchers need the pairing's join key. Next: your router forwards port " + port() + " (and Minecraft's) to " + bind + ", and the public name (below) is filled in." : "")));
			return true;
		}
		reply(x, 200, m("network", mode(), "bind", setting("bind", ""), "port", port(), "addresses", addresses(), "https", Tls.on(), "fingerprint", Tls.on() ? Tls.fingerprint() : "", "public", setting("public_host", "")));
		return true;
	}
}
