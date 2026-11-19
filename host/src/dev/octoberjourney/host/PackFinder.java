package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R83: help choosing a modpack, in a server's Pack tab, from whichever AI this Gatekeeper has set up and switched on
 * (Ollama or the built-in one); with none, it isn't offered. The owner says what they want in their own words; the AI
 * turns that into a few searches; Modrinth (api.modrinth.com/v2, no sign-in) lists the Fabric modpacks that match,
 * with their summary, downloads, Minecraft versions and when they last changed; and the AI reads those facts (only
 * those) and picks the few that fit best, each with why, its pros and its cons, then says which it would choose.
 * Nothing is installed or changed: each pick has a link to its Modrinth page.
 *
 * Sent to Modrinth: the search words and a Minecraft version. Sent to the AI: the owner's words and Modrinth's facts.
 *
 *   POST /admin/pack/finder?server=  {wish}  ->  {result, picks:[{title, slug, summary, downloads, updated, versions,
 *                                                     why, pros, cons}], advice, searched}
 */
final class PackFinder {
	private PackFinder() {}

	/** Why the helper can't be used here now, or "". */
	static String blocker() {
		if (!Ai.on()) return "The modpack helper uses this Gatekeeper's AI, which is switched off: Options, AI helper.";
		String r = Ai.ready();
		return r.isEmpty() ? "" : "The modpack helper uses this Gatekeeper's AI, which isn't ready: " + r;
	}

	private static String get(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000); c.setReadTimeout(25000);
		c.setRequestProperty("User-Agent", "Gatehouse-Gatekeeper/" + ownVersion() + " (modpack helper)");
		int code = c.getResponseCode();
		if (code == 429) throw new IOException("Modrinth asks for a pause (too many questions this minute)");
		if (code >= 400) throw new IOException("Modrinth answered " + code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}
	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

	/** The JSON object in a model's answer (it may wrap it in words or a code block), or an empty map. */
	static Map<String, Object> jsonIn(String answer) {
		int a = answer.indexOf('{'), b = answer.lastIndexOf('}');
		if (a < 0 || b <= a) return new LinkedHashMap<>();
		try { return Json.map(Json.parse(answer.substring(a, b + 1))); } catch (RuntimeException e) { return new LinkedHashMap<>(); }
	}

	/** The searches for a wish: the AI's, else the wish's own words. {queries..., version} (version may be ""). */
	static List<String> searches(String wish, String[] version) {
		String ans = Ai.ask("You turn what someone wants from a Minecraft modpack into searches for Modrinth's modpack search. "
				+ "Answer only with JSON: {\"queries\": [two to four short searches of one to three words each], \"version\": \"a Minecraft version if they named one, else empty\"}.",
				"What they want: " + wish, 120);
		Map<String, Object> j = jsonIn(ans);
		List<String> qs = new ArrayList<>();
		for (Object o : Json.list(j.get("queries"))) { String q = Json.str(o).strip(); if (!q.isEmpty() && q.length() <= 60 && qs.size() < 4 && !qs.contains(q)) qs.add(q); }
		String v = Json.str(j.get("version")).strip();
		if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) version[0] = v;
		if (qs.isEmpty()) qs.add(wish.length() > 60 ? wish.substring(0, 60) : wish);
		return qs;
	}

	/** Modrinth's Fabric modpacks for the searches, best first, at most eight. */
	static List<Map<String, Object>> packs(List<String> queries, String version) throws IOException {
		Map<String, Map<String, Object>> found = new LinkedHashMap<>();
		String facets = "[[\"project_type:modpack\"],[\"categories:fabric\"]" + (version.isEmpty() ? "" : ",[\"versions:" + version + "\"]") + "]";
		for (String q : queries) {
			Map<String, Object> r = Json.map(Json.parse(get(ModList.site() + "/search?limit=6&index=relevance&query=" + enc(q) + "&facets=" + enc(facets))));
			for (Object o : Json.list(r.get("hits"))) {
				Map<String, Object> h = Json.map(o);
				String id = Json.str(h.get("project_id"));
				if (id.isEmpty() || found.containsKey(id)) continue;
				List<Object> vs = Json.list(h.get("versions"));
				List<String> recent = new ArrayList<>();
				for (int i = vs.size() - 1; i >= 0 && recent.size() < 3; i--) { String g = Json.str(vs.get(i)); if (g.matches("\\d+\\.\\d+(\\.\\d+)?")) recent.add(g); }
				String sum = Json.str(h.get("description"));
				found.put(id, m("title", Json.str(h.get("title")), "slug", Json.str(h.get("slug")), "summary", sum.length() > 220 ? sum.substring(0, 217) + "..." : sum,
						"downloads", h.get("downloads"), "updated", Json.str(h.get("date_modified")).replaceAll("T.*", ""), "versions", String.join(", ", recent),
						"categories", String.join(", ", Json.list(h.get("display_categories")).stream().map(Json::str).toList())));
				if (found.size() >= 8) break;
			}
			if (found.size() >= 8) break;
		}
		return new ArrayList<>(found.values());
	}

	/** Has the AI weigh the packs up for the wish: their picks (with why, pros, cons) and its advice. */
	static Map<String, Object> weigh(String wish, List<Map<String, Object>> packs, String serverVersion) {
		StringBuilder facts = new StringBuilder();
		for (int i = 0; i < packs.size(); i++) {
			Map<String, Object> p = packs.get(i);
			long dl = 0; try { dl = (long) Double.parseDouble(Json.str(p.get("downloads"))); } catch (RuntimeException ignored) { dev.gatehouse.look.Quiet.seen(ignored); }
			facts.append(i + 1).append(". ").append(Json.str(p.get("title"))).append(": ").append(Json.str(p.get("summary")))
					.append(" | downloads ").append(dl).append(" | last changed ").append(Json.str(p.get("updated")))
					.append(" | Minecraft ").append(Json.str(p.get("versions"))).append(" | ").append(Json.str(p.get("categories"))).append("\n");
		}
		String ans = Ai.ask("You help someone choose a Minecraft modpack for their server. Use only the facts given about each pack; don't invent features. "
				+ "Answer only with JSON: {\"picks\": [up to four of the best fits: {\"n\": its number, \"why\": \"one sentence on why it fits\", \"pros\": \"short\", \"cons\": \"short\"}], "
				+ "\"advice\": \"one or two sentences: which you would choose and why\"}.",
				"What they want: " + wish + (serverVersion.isEmpty() ? "" : "\nTheir server runs Minecraft " + serverVersion + " now (another version means a new server).")
				+ "\nThe packs:\n" + facts, 420, 6144);
		Map<String, Object> j = jsonIn(ans);
		List<Object> picks = new ArrayList<>();
		for (Object o : Json.list(j.get("picks"))) {
			Map<String, Object> pk = Json.map(o);
			int n; try { n = (int) Double.parseDouble(Json.str(pk.get("n"))); } catch (RuntimeException e) { continue; }
			if (n < 1 || n > packs.size()) continue;
			Map<String, Object> p = new LinkedHashMap<>(packs.get(n - 1));
			p.put("why", Json.str(pk.get("why"))); p.put("pros", Json.str(pk.get("pros"))); p.put("cons", Json.str(pk.get("cons")));
			if (picks.stream().noneMatch(x -> Json.str(Json.map(x).get("slug")).equals(Json.str(p.get("slug"))))) picks.add(p);
		}
		String advice = Json.str(j.get("advice"));
		if (picks.isEmpty()) {   // (the AI's answer wasn't usable: the packs as found, and what it said, if anything)
			for (Map<String, Object> p : packs) picks.add(p);
			if (advice.isEmpty()) advice = ans.isEmpty() ? "The AI didn't answer, so these are Modrinth's best matches for the searches, without its notes." : ans.length() > 600 ? ans.substring(0, 600) + "..." : ans;
		}
		return m("picks", picks, "advice", advice);
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/pack/finder")) return false;
		String why = blocker();
		if (!why.isEmpty()) { reply(x, 200, m("result", "refused", "message", why)); return true; }
		Map<String, Object> b = body(x);
		String wish = Json.str(b.get("wish")).strip();
		if (wish.isEmpty()) { reply(x, 200, m("result", "refused", "message", "Say what you want from a modpack first.")); return true; }
		if (wish.length() > 600) wish = wish.substring(0, 600);
		String serverVersion = "";
		try { Map<String, Object> s = server(); if (s != null && !Json.str(s.get("dir")).isEmpty()) serverVersion = serverFacts(java.nio.file.Path.of(Json.str(s.get("dir"))))[1]; }
		catch (RuntimeException ignored) { dev.gatehouse.look.Quiet.seen(ignored); }
		try {
			String[] version = {""};
			List<String> qs = searches(wish, version);
			List<Map<String, Object>> packs = packs(qs, version[0]);
			if (packs.isEmpty() && !version[0].isEmpty()) packs = packs(qs, "");
			if (packs.isEmpty()) { reply(x, 200, m("result", "ok", "picks", List.of(), "advice", "Modrinth has no Fabric modpacks for those searches (" + String.join(", ", qs) + "). Try other words.", "searched", qs)); return true; }
			Map<String, Object> r = weigh(wish, packs, serverVersion);
			log("modpack helper: searched Modrinth for " + String.join(", ", qs) + "; " + Json.list(r.get("picks")).size() + " picks");
			reply(x, 200, m("result", "ok", "picks", r.get("picks"), "advice", r.get("advice"), "searched", qs));
		} catch (IOException e) { reply(x, 200, m("result", "failed", "message", "Modrinth couldn't be asked: " + e.getMessage())); }
		return true;
	}
}
