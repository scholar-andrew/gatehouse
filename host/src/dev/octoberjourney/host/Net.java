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
 */
final class Net {
	private Net() {}

	static String mode() { String n = setting("network", "tailscale"); return n.equals("home") || n.equals("private") ? n : "tailscale"; }
	static boolean tailscale() { return mode().equals("tailscale"); }
	/** The network's name in a sentence: "Tailscale", "your home network", "your private network". */
	static String word() { return switch (mode()) { case "home" -> "your home network"; case "private" -> "your private network"; default -> "Tailscale"; }; }
	/** The web panel needs an encrypted network (Tailscale, or a private one, which encrypts as a rule) until HTTPS. */
	static boolean panelAllowed() { return !mode().equals("home"); }

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
		return switch (mode) { case "tailscale" -> kind.equals("tailscale"); case "home" -> kind.equals("home"); default -> true; };
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/network")) return false;
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			String mode = Json.str(b.get("network")), bind = Json.str(b.get("bind")).trim();
			if (!mode.equals("tailscale") && !mode.equals("home") && !mode.equals("private")) { reply(x, 200, m("result", "refused", "message", "No such network.")); return true; }
			boolean mine = false;
			for (Object o : addresses()) if (Json.str(Json.map(o).get("address")).equals(bind)) mine = true;
			if (!mine) { reply(x, 200, m("result", "refused", "message", bind.isEmpty() ? "Choose one of this machine's addresses." : bind + " isn't an address of this machine (now).")); return true; }
			if (!fits(mode, kind(bind))) { reply(x, 200, m("result", "refused", "message", mode.equals("tailscale")
					? bind + " isn't a Tailscale address (those start 100.)." : bind + " isn't a home-network address (those start 192.168., 10. or 172.16 to 172.31.).")); return true; }
			boolean moved = !bind.equals(setting("bind", "")) || !mode.equals(mode());
			cfg.put("network", mode); cfg.put("bind", bind);
			ServerTools.saveCfg();
			if (moved) {
				log("network: devices reach this Gatekeeper over " + word() + ", at " + bind + ":" + port());
				bindRemote();
			}
			reply(x, 200, m("result", "ok", "message", "Devices reach this Gatekeeper over " + word() + " now, at " + bind + ":" + port() + "."
					+ (moved ? " Launchers paired with the old address need the new pairing (Share pairing)." : "")
					+ (mode.equals("home") ? " The web panel stays off on a home network until Gatekeeper has HTTPS." : "")));
			return true;
		}
		reply(x, 200, m("network", mode(), "bind", setting("bind", ""), "port", port(), "addresses", addresses()));
		return true;
	}
}
