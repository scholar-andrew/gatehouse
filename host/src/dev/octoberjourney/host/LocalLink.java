package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * The launcher on this same machine (the Play half of the program, beside this Host half) linking itself to this
 * Gatekeeper. It asks through the window's own door (/admin/local/link, which only answers on this machine), so it
 * needs no pairing link, no request for the owner to answer, and no Tailscale: whoever uses this machine already has
 * the whole of it. Such a launcher is marked "local": it is allowed everything here, on every server, and the
 * Minecraft account it plays as is put on each server's whitelist.
 *
 * Only that one launcher gets this. A launcher on another device is never marked local, whoever plays on it: it
 * introduces itself and waits to be allowed, as always.
 */
final class LocalLink {
	/** GET: where this Gatekeeper is reached (for the launcher to find the pairing it may already have).
	 *  POST {id, token, player, uuid}: marks that launcher (which has said hello with that id and secret) as this
	 *  machine's own. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/local/open")) {   // (the launcher on this machine asks for Gatekeeper's window: Gatekeeper opens it itself)
			panelSeen = 0;
			openPanelNow();
			reply(x, 200, m("result", "ok", "message", "Opening Gatekeeper's window."));
			return true;
		}
		if (!path.equals("/admin/local/link")) return false;
		String bind = setting("bind", ""), name = setting("name", "");
		if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Gatekeeper"; }
		if (!x.getRequestMethod().equals("POST")) { reply(x, 200, m("bind_url", bind.isEmpty() ? "" : "http://" + bind + ":" + port(), "port", port(), "name", name, "version", ownVersion())); return true; }
		Map<String, Object> b = body(x);
		String id = Json.str(b.get("id")), token = Json.str(b.get("token")), player = Json.str(b.get("player")), uuid = Json.str(b.get("uuid")).replace("-", "").toLowerCase(java.util.Locale.ROOT);
		String[] r = link(id, token, player, uuid);
		reply(x, 200, m("result", r[0], "message", r[1], "name", name));
		return true;
	}

	static synchronized String[] link(String id, String token, String player, String uuid) throws IOException {
		Map<String, Object> devs = devices();
		Map<String, Object> d = Json.map(devs.get(id));
		if (d.isEmpty() || token.length() < 32 || !sha256(token).equals(d.get("token_sha256"))) return new String[] {"refused", "That launcher hasn't introduced itself to this Gatekeeper yet."};
		boolean was = ServerTools.yes(d.get("local"));
		d.put("local", true);
		// (written down as well as held to be so, so the Access Manager shows what it is allowed)
		d.put("granted", true); d.put("sleep", true); d.put("admin", true);
		List<Object> all = servers();
		for (int i = 0; i < Math.max(1, all.size()); i++) d.put(i == 0 ? "server" : "server:" + Json.str(Json.map(all.get(i)).get("id")), true);
		boolean named = player.matches("\\w{1,16}");
		if (named) { d.put("player", player); d.put("player_confirmed", true); d.put("player_seen", LocalDateTime.now().format(TIME)); }
		saveDevices(devs);
		List<String> listed = new ArrayList<>(), not = new ArrayList<>();
		if (named) {
			String keep = SERVER.get();
			try {
				for (Object o : all) {
					SERVER.set(Json.str(Json.map(o).get("id")));
					if (goneFromMcss(Json.map(o)) || whitelisted(player)) continue;
					String[] w = whitelist(player, uuid);
					(w[0].equals("ok") ? listed : not).add(serverName() + (w[0].equals("ok") ? "" : " (" + w[1] + ")"));
				}
			} finally { SERVER.set(keep); }
		}
		if (!was) log("the launcher on this machine (" + who(d) + ") linked itself to this Gatekeeper: it is allowed everything here" + (named ? ", and plays as " + player : ""));
		if (!listed.isEmpty()) log(player + " (this machine's own launcher) was put on the whitelist of " + String.join(", ", listed));
		if (!not.isEmpty()) log(player + " (this machine's own launcher) couldn't be put on the whitelist of " + String.join(", ", not));
		return new String[] {"ok", "Linked: this machine's launcher is allowed everything here" + (listed.isEmpty() ? "" : ", and " + player + " is now on the whitelist of " + String.join(", ", listed)) + "."};
	}

	/** Puts a player on the current server's whitelist. With the server off and the player never seen there, the
	 *  account id the launcher gave (from Prism's own account list) is what the whitelist file needs. */
	static String[] whitelist(String player, String uuid) {
		String[] r = setWhitelisted(player, true);
		if (r[0].equals("ok") || minecraftRunning() || !uuid.matches("[0-9a-f]{32}")) return r;
		try {
			List<Object> wl = new ArrayList<>(HostHelper.whitelist());
			wl.add(m("uuid", uuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"), "name", player));
			Files.writeString(serverDir().resolve("whitelist.json"), Json.write(wl), StandardCharsets.UTF_8);
			return new String[] {"ok", "added"};
		} catch (IOException | RuntimeException e) { return new String[] {"failed", "its whitelist file couldn't be written: " + e.getMessage()}; }
	}
}
