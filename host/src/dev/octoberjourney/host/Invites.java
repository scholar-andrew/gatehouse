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
 * Who a server and its pack are for, and invites.
 *
 * Each server has one of three settings (host.json, the server's "visibility"; the owner sets it on the server's
 * Pack tab, at this Gatekeeper's own window only):
 *   private     (what it is unless changed) only launchers the owner let in: this machine's own launcher, one with
 *               Gatekeeper or MCSS control, one allowed to start and stop this server, one playing as someone on its
 *               whitelist, or one that used an invite the owner made
 *   shareable   those, and any launcher that used an invite from a launcher that has the server
 *   public      every launcher that connects to this Gatekeeper
 * A launcher the server is not for isn't told of it in the catalog (so it has no Install), and isn't given its pack.
 * A launcher that is suspended or blacklisted is given neither, whatever the setting.
 *
 * The pack's files are asked for by programs that can't send a launcher's secret (packwiz-installer), so a request
 * for one is matched to launchers by the address it comes from: the address a launcher last spoke to this Gatekeeper
 * from, with its secret. On Tailscale an address belongs to one device and can't be borrowed. This machine itself is
 * always given them.
 *
 * An invite is a code, good for seven days and for any number of launchers. A launcher that has a shareable or public
 * server may make one (POST /v1/invite {server}); so may the owner, for any server. Another launcher connects to this
 * Gatekeeper and hands the code in (POST /v1/invite/accept {code}); the server is then for it too. That is all an
 * invite does: it puts nobody on a whitelist and gives no permission to start, stop or manage anything. An invite
 * from a player stops counting while the server is private; one from the owner always counts.
 */
final class Invites {
	private Invites() {}
	static final long LASTS = 7L * 24 * 3600 * 1000;
	private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final Map<String, long[]> asked = new ConcurrentHashMap<>();   // (address | server | setting -> {when, 1 or 0})
	private static final Map<String, long[]> wrong = new ConcurrentHashMap<>();   // (launcher id -> {wrong codes handed in, since when})

	static String mode(Map<String, Object> s) { String v = Json.str(s.get("visibility")); return v.equals("public") || v.equals("shareable") ? v : "private"; }
	static String says(String mode) { return mode.equals("public") ? "Public: every launcher that connects to this Gatekeeper" : mode.equals("shareable") ? "Shareable: launchers you let in, and anyone they invite" : "Private: only launchers you let in"; }
	private static Map<String, Object> serverById(String id) { for (Object o : servers()) if (id.equals(Json.str(Json.map(o).get("id")))) return Json.map(o); return null; }

	/** Did the owner let this launcher onto the server (apart from invites)? */
	private static boolean letIn(Map<String, Object> d, Map<String, Object> s) {
		if (ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin")) || Boolean.TRUE.equals(d.get("granted"))) return true;
		String keep = SERVER.get();
		try {
			SERVER.set(Json.str(s.get("id")));
			String player = Json.str(d.get("player"));
			return Boolean.TRUE.equals(d.get(serverKey())) || (!player.isEmpty() && whitelisted(player));
		} catch (RuntimeException e) { return false; }
		finally { SERVER.set(keep); }
	}
	/** Is the server (and its pack) for this launcher? */
	static boolean mayHave(Map<String, Object> d, Map<String, Object> s) {
		if (d == null || d.isEmpty() || s == null) return false;
		if (ServerTools.yes(d.get("local"))) return true;
		if (!banOf(d)[0].isEmpty()) return false;
		String mode = mode(s), invited = Json.str(d.get("invited:" + Json.str(s.get("id"))));
		if (mode.equals("public") || letIn(d, s)) return true;
		return invited.equals("host") || (invited.equals("player") && mode.equals("shareable"));
	}
	/** May this launcher invite others to the server? */
	static boolean mayInvite(Map<String, Object> d, Map<String, Object> s) {
		if (!mayHave(d, s)) return false;
		return !mode(s).equals("private") || ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin"));
	}

	/** Is this request for a pack's file from somewhere the pack is for? */
	static boolean packFor(HttpExchange x, String serverId) {
		Map<String, Object> s = serverById(serverId);
		if (s == null) return false;
		java.net.InetAddress from = x.getRemoteAddress().getAddress();
		// (this machine itself. Testing: OJ_TEST_NO_LOCAL makes this machine's own addresses count as any other, so several stand-in launchers can be told apart)
		try { if (System.getenv("OJ_TEST_NO_LOCAL") == null && (from.isLoopbackAddress() || java.net.NetworkInterface.getByInetAddress(from) != null)) return true; } catch (IOException ignored) {}
		// (a pack is many files asked for one after another: the answer for an address is kept for three seconds)
		String ip = from.getHostAddress(), key = ip + "|" + serverId + "|" + mode(s);
		long now = System.currentTimeMillis();
		long[] had = asked.get(key);
		if (had != null && now - had[0] < 3_000) return had[1] == 1;
		boolean ok = false;
		// (public too: only a launcher this Gatekeeper knows, so one that was blacklisted isn't handed it as a stranger)
		for (Object o : devices().values()) { Map<String, Object> d = Json.map(o); if (ip.equals(Json.str(d.get("ip"))) && !Json.str(d.get("token_sha256")).isEmpty() && mayHave(d, s)) ok = true; }
		asked.put(key, new long[] {now, ok ? 1 : 0});
		return ok;
	}

	// ------------------------------------------------------------------ the codes

	private static Path file() { return dir.resolve("invites.json"); }
	private static synchronized List<Object> live() {
		List<Object> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		try { if (Files.isRegularFile(file())) for (Object o : Json.list(Json.parse(Files.readString(file(), StandardCharsets.UTF_8)))) { try { if (Long.parseLong(Json.str(Json.map(o).get("until"))) > now) out.add(Json.map(o)); } catch (NumberFormatException ignored) {} } }
		catch (IOException | RuntimeException ignored) {}
		return out;
	}
	private static void save(List<Object> l) { try { Files.writeString(file(), Json.write(l), StandardCharsets.UTF_8); } catch (IOException e) { log("invites: couldn't be saved: " + e.getMessage()); } }
	private static String tidy(String code) { return code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }
	private static String shown(String code) { return code.substring(0, 5) + "-" + code.substring(5); }
	private static String address() { String b = setting("bind", ""); return (b.isEmpty() ? "127.0.0.1" : b) + ":" + port(); }

	/** Makes an invite to a server. by: "host", or the inviting launcher's id. */
	static synchronized Map<String, Object> make(Map<String, Object> s, String by, String byName) {
		List<Object> l = live();
		int mine = 0;
		for (Object o : l) if (by.equals(Json.str(Json.map(o).get("by")))) mine++;
		if (l.size() >= 400 || (!by.equals("host") && mine >= 20)) return m("result", "refused", "message", "There are already many invites that haven't run out. Use one of those, or wait for them to run out (an invite lasts seven days).");
		java.security.SecureRandom r = new java.security.SecureRandom();
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < 10; i++) code.append(LETTERS.charAt(r.nextInt(LETTERS.length())));
		long until = System.currentTimeMillis() + LASTS;
		l.add(m("code", code.toString(), "server", Json.str(s.get("id")), "by", by, "by_name", byName, "made", LocalDateTime.now().format(TIME), "until", String.valueOf(until)));
		save(l);
		String name = setting("name", "");
		String link = "gatehouse://invite/" + address() + "?code=" + shown(code.toString()) + (name.isEmpty() ? "" : "&name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)) + "&server=" + java.net.URLEncoder.encode(Json.str(s.get("name")), StandardCharsets.UTF_8);
		log("invites: " + byName + " made an invite to " + Json.str(s.get("name")));
		return m("result", "ok", "code", shown(code.toString()), "link", link, "server", Json.str(s.get("name")), "until", LocalDateTime.now().plusDays(7).format(TIME),
				"message", "Send this to the other person. In their Gatehouse: Add Gatekeeper, paste it. It lasts seven days, and they have to be able to reach this Gatekeeper (on its Tailscale network, or shared into it). It doesn't put them on the whitelist or give them any control.");
	}

	/** A launcher hands a code in. */
	static synchronized Map<String, Object> accept(String id, Map<String, Object> d, Map<String, Object> devs, String given) {
		long now = System.currentTimeMillis();
		long[] w = wrong.computeIfAbsent(id, k -> new long[] {0, now});
		if (now - w[1] > 3_600_000) { w[0] = 0; w[1] = now; }
		if (w[0] >= 8) return m("result", "refused", "message", "Too many codes that weren't right. Try again in an hour.");
		if (!banOf(d)[0].isEmpty()) return m("result", "refused", "message", "This launcher is shut out of this Gatekeeper, so an invite can't be used from it.");
		String code = tidy(given);
		Map<String, Object> inv = null;
		for (Object o : live()) if (code.length() == 10 && code.equals(Json.str(Json.map(o).get("code")))) inv = Json.map(o);
		if (inv == null) { w[0]++; return m("result", "refused", "message", "That invite isn't known here: it may have run out (an invite lasts seven days), been taken back, or been mistyped."); }
		Map<String, Object> s = serverById(Json.str(inv.get("server")));
		if (s == null) return m("result", "refused", "message", "The server that invite was for is no longer on this Gatekeeper.");
		boolean host = Json.str(inv.get("by")).equals("host");
		if (!host && mode(s).equals("private")) return m("result", "refused", "message", "The owner has since made " + Json.str(s.get("name")) + " private, so a player's invite to it no longer works. Ask the owner.");
		String key = "invited:" + Json.str(s.get("id"));
		if (!host && Json.str(d.get(key)).equals("host")) return m("result", "ok", "server", Json.str(s.get("name")), "id", Json.str(s.get("id")), "message", Json.str(s.get("name")) + " is already for this launcher.");
		d.put(key, host ? "host" : "player");
		d.put("invited_by:" + Json.str(s.get("id")), Json.str(inv.get("by_name")));
		devs.put(id, d);
		try { saveDevices(devs); } catch (IOException e) { return m("result", "failed", "message", "The Gatekeeper couldn't save that: " + e.getMessage()); }
		asked.clear();
		log("invites: " + who(d) + " used an invite to " + Json.str(s.get("name")) + " made by " + Json.str(inv.get("by_name")));
		return m("result", "ok", "server", Json.str(s.get("name")), "id", Json.str(s.get("id")), "message", Json.str(s.get("name")) + " is now listed for this launcher, with Install. The invite doesn't put you on the server's whitelist: that is the owner's to do.");
	}

	/** Launchers: POST /v1/invite {server}, POST /v1/invite/accept {code}. */
	static void launcher(HttpExchange x, String path, String id, Map<String, Object> d, Map<String, Object> devs) throws IOException {
		Map<String, Object> b = body(x);
		if (path.equals("/v1/invite/accept")) { reply(x, 200, accept(id, d, devs, Json.str(b.get("code")))); return; }
		Map<String, Object> s = serverById(Json.str(b.get("server")));
		if (s == null || !mayHave(d, s)) { reply(x, 200, m("result", "refused", "message", "No such server.")); return; }
		if (!mayInvite(d, s)) { reply(x, 200, m("result", "refused", "message", Json.str(s.get("name")) + " is private: only its owner invites people to it.")); return; }
		reply(x, 200, make(s, id, who(d)));
	}

	/** For the Pack tab: the setting, who came in by invite, and how many invites haven't run out. */
	static Map<String, Object> state(Map<String, Object> s) {
		String sid = Json.str(s.get("id"));
		List<Object> came = new ArrayList<>();
		for (Object o : devices().values()) { Map<String, Object> d = Json.map(o); String how = Json.str(d.get("invited:" + sid)); if (!how.isEmpty()) came.add(m("name", who(d), "by", Json.str(d.get("invited_by:" + sid)), "counts", how.equals("host") || mode(s).equals("shareable") || mode(s).equals("public"))); }
		int codes = 0;
		for (Object o : live()) if (sid.equals(Json.str(Json.map(o).get("server")))) codes++;
		return m("visibility", mode(s), "visibility_says", says(mode(s)), "invited", came, "invites_live", codes);
	}
	static synchronized String[] setMode(Map<String, Object> s, String mode) throws IOException {
		if (!List.of("private", "shareable", "public").contains(mode)) return new String[] {"refused", "Choose private, shareable or public."};
		String was = mode(s);
		s.put("visibility", mode);
		ServerTools.saveCfg();
		asked.clear();
		if (!was.equals(mode)) log("invites: " + Json.str(s.get("name")) + " is now " + mode + " (was " + was + ")");
		return new String[] {"ok", says(mode) + "." + (mode.equals("private") && !was.equals("private") ? " Launchers that only came in by a player's invite, or because it was public, no longer get its pack or see it listed; what they already installed stays on their device." : "")};
	}
	/** Takes back every invite to a server: the codes that haven't run out, and the launchers that came in by one. */
	static synchronized String[] takeBack(Map<String, Object> s) {
		String sid = Json.str(s.get("id"));
		List<Object> keep = new ArrayList<>();
		for (Object o : live()) if (!sid.equals(Json.str(Json.map(o).get("server")))) keep.add(o);
		save(keep);
		Map<String, Object> devs = devices();
		int n = 0;
		for (Object o : devs.values()) { Map<String, Object> d = Json.map(o); if (d.remove("invited:" + sid) != null) n++; d.remove("invited_by:" + sid); }
		try { saveDevices(devs); } catch (IOException e) { return new String[] {"failed", "Couldn't be saved: " + e.getMessage()}; }
		asked.clear();
		log("invites: every invite to " + Json.str(s.get("name")) + " was taken back (" + n + " launcher(s))");
		return new String[] {"ok", "Taken back: " + n + " launcher(s) that came in by invite, and every invite that hadn't run out. Launchers you let in another way are untouched."};
	}
}
