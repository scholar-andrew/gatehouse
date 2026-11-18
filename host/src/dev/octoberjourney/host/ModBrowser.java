package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finding and adding mods for a Minecraft (Fabric) server from Modrinth (R57; api.modrinth.com/v2, no sign-in):
 *   GET /search?query=&facets=[["project_type:mod"],["categories:fabric"],["versions:<its version>"]] lists mods that
 *       fit the server, with their title, summary, downloads and whether players need them too;
 *   GET /project/<id>/version?loaders=["fabric"]&game_versions=["<its version>"] gives the newest file that fits.
 * Adding one fetches that file from Modrinth's own file server into the server's mods folder, checked against the
 * SHA-512 Modrinth publishes, together with the mods it says it requires (that aren't there yet). Nothing reaches
 * players until the owner saves the pack's mod list and makes a new version, as with any mod in that folder. What is
 * sent to Modrinth is the search words and the server's Minecraft version, nothing about the machine or its players.
 */
final class ModBrowser {
	private ModBrowser() {}

	private static String get(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000); c.setReadTimeout(25000);
		c.setRequestProperty("User-Agent", "Gatehouse-Gatekeeper/" + ownVersion() + " (mod browser)");
		int code = c.getResponseCode();
		if (code == 429) throw new IOException("Modrinth asks for a pause (too many questions this minute)");
		if (code >= 400) throw new IOException("Modrinth answered " + code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}
	private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

	/** Why mods can't be found for the current server here, or "". */
	static String blocker(Map<String, Object> s) {
		Game g = Game.of(s);
		if (g == null || !g.id().equals("minecraft")) return "Mods from Modrinth are for Minecraft servers.";
		if (!isNative()) return "Gatekeeper adds mods only to servers it runs itself: for an MCSS server, add them in MCSS.";
		Path d = Path.of(Json.str(s.get("dir")));
		if (Packs.fabricLoader(d).isEmpty()) return "This server isn't a Fabric server, and the mod browser finds Fabric mods.";
		if (serverFacts(d)[1].isEmpty()) return "This server's Minecraft version isn't known yet: start it once first.";
		return "";
	}
	static String version(Map<String, Object> s) { return serverFacts(Path.of(Json.str(s.get("dir"))))[1]; }

	/** Mods that fit the server: [{id, slug, title, summary, downloads, players}]. */
	static List<Object> search(Map<String, Object> s, String words) throws IOException {
		String facets = "[[\"project_type:mod\"],[\"categories:fabric\"],[\"versions:" + version(s) + "\"]]";
		Map<String, Object> r = Json.map(Json.parse(get(ModList.site() + "/search?limit=20&query=" + enc(words) + "&facets=" + enc(facets))));
		List<Object> out = new ArrayList<>();
		for (Object o : Json.list(r.get("hits"))) {
			Map<String, Object> h = Json.map(o);
			String sum = Json.str(h.get("description"));
			out.add(m("id", Json.str(h.get("project_id")), "slug", Json.str(h.get("slug")), "title", Json.str(h.get("title")),
					"summary", sum.length() > 180 ? sum.substring(0, 177) + "..." : sum, "downloads", h.get("downloads"),
					"players", !"unsupported".equals(Json.str(h.get("client_side")))));
		}
		return out;
	}

	/** Adds a mod (and the mods it requires) to the server's mods folder: {result, message}. */
	static String[] add(Map<String, Object> s, String project) {
		String why = blocker(s);
		if (!why.isEmpty()) return new String[] {"refused", why};
		if (!project.matches("[A-Za-z0-9]{6,12}")) return new String[] {"refused", "That isn't a Modrinth project."};
		Path mods = Path.of(Json.str(s.get("dir"))).resolve("mods");
		try {
			Files.createDirectories(mods);
			Set<String> have = new LinkedHashSet<>();
			try {   // (the projects the folder's jars are, as Modrinth knows them)
				Map<String, Object> known = ModList.lookup(Json.str(s.get("id")), ModList.jars(Path.of(Json.str(s.get("dir")))), false);
				for (Object v : known.values()) have.add(Json.str(Json.map(v).get("project")));
			} catch (IOException | RuntimeException ignored) { dev.gatehouse.look.Quiet.seen(ignored); }
			List<String> added = new ArrayList<>(), already = new ArrayList<>();
			List<String> todo = new ArrayList<>(List.of(project));
			for (int n = 0; !todo.isEmpty() && n < 12; n++) {
				String p = todo.remove(0);
				if (have.contains(p)) { if (p.equals(project)) already.add(p); continue; }
				String loaders = enc("[\"fabric\"]"), versions = enc("[\"" + version(s) + "\"]");
				List<Object> vs = Json.list(Json.parse(get(ModList.site() + "/project/" + p + "/version?loaders=" + loaders + "&game_versions=" + versions)));
				if (vs.isEmpty()) { if (p.equals(project)) return new String[] {"refused", "Modrinth has no version of it for Fabric " + version(s) + "."}; continue; }
				Map<String, Object> v = Json.map(vs.get(0)), file = null;
				for (Object fo : Json.list(v.get("files"))) { Map<String, Object> f = Json.map(fo); if (file == null || Boolean.TRUE.equals(f.get("primary"))) file = f; }
				if (file == null) continue;
				String url = Json.str(file.get("url")), name = Json.str(file.get("filename")), sha = Json.str(Json.map(file.get("hashes")).get("sha512"));
				if (!(url.startsWith("https://cdn.modrinth.com/") || (!ModList.site().startsWith("https://api.modrinth.com") && url.startsWith(ModList.site().replaceAll("^(https?://[^/]+).*$", "$1") + "/"))) || !name.matches("[\\w .+\\-\\[\\]()]{1,120}\\.jar") || !sha.matches("[0-9a-f]{128}"))
					return new String[] {"failed", "Modrinth's answer about " + name + " didn't look right, so nothing was fetched."};
				fetch(url, mods.resolve(name), sha);
				have.add(p);
				added.add(name);
				for (Object dO : Json.list(v.get("dependencies"))) {
					Map<String, Object> dep = Json.map(dO);
					if ("required".equals(Json.str(dep.get("dependency_type"))) && !Json.str(dep.get("project_id")).isEmpty()) todo.add(Json.str(dep.get("project_id")));
				}
			}
			if (added.isEmpty()) return new String[] {"ok", already.isEmpty() ? "Nothing to add." : "That mod is in the server's mods folder already."};
			log("mod browser: added " + String.join(", ", added) + " to " + serverName());
			return new String[] {"ok", "Added to " + serverName() + "'s mods folder: " + String.join(", ", added) + ". It takes effect when the server starts again; "
					+ "then Mods players get... (to choose which players need) and New version... to send them."};
		} catch (IOException | RuntimeException e) { return new String[] {"failed", "The mod wasn't added: " + e.getMessage()}; }
	}
	/** Fetches a file, checks its SHA-512, and only then puts it in place. */
	private static void fetch(String url, Path to, String sha512) throws IOException {
		Path part = to.resolveSibling(to.getFileName() + ".part");
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-512");
			HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
			c.setConnectTimeout(8000); c.setReadTimeout(60000);
			c.setRequestProperty("User-Agent", "Gatehouse-Gatekeeper/" + ownVersion() + " (mod browser)");
			if (c.getResponseCode() != 200) throw new IOException("Modrinth's file server answered " + c.getResponseCode());
			try (InputStream in = c.getInputStream(); java.io.OutputStream out = Files.newOutputStream(part)) {
				byte[] buf = new byte[65536];
				for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); md.update(buf, 0, n); }
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			if (!sb.toString().equals(sha512)) throw new IOException(to.getFileName() + " didn't match Modrinth's checksum, so it wasn't kept");
			Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
		finally { Files.deleteIfExists(part); }
	}

	/** /admin/modsite/search?server= {words} and /admin/modsite/add?server= {project}. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.startsWith("/admin/modsite/")) return false;
		Map<String, Object> s = server(), b = body(x);
		String why = blocker(s);
		if (path.equals("/admin/modsite/search")) {
			if (!why.isEmpty()) { reply(x, 200, m("result", "refused", "message", why)); return true; }
			try { reply(x, 200, m("result", "ok", "version", version(s), "mods", search(s, Json.str(b.get("words")).strip()))); }
			catch (IOException e) { reply(x, 200, m("result", "failed", "message", "Modrinth couldn't be asked: " + e.getMessage())); }
			return true;
		}
		if (path.equals("/admin/modsite/add")) { String[] r = add(s, Json.str(b.get("project"))); reply(x, 200, m("result", r[0], "message", r[1])); return true; }
		reply(x, 404, m("error", "no such call"));
		return true;
	}
}
