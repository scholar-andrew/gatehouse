package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of a server's mods its players are sent, in the pack Gatekeeper keeps for it (Packs).
 *
 * Gatekeeper reads the jars in the server's mods folder (each one's fabric.mod.json: its id, name, version, and
 * whether it says it is for servers only), asks the mod site which of those exact files it publishes, guesses which
 * ones players need, and shows the list. The host confirms it: a switch for each mod. Nothing reaches the pack until
 * the list is saved, and nothing reaches players until the pack's next version is built.
 *
 * Where a player's copy comes from: the mod's own page when that exact file is published there (the pack then only
 * names the address and the file's SHA-512, and the player's launcher fetches it from the site), and this Gatekeeper
 * otherwise (the server's own file is copied into the pack). Either way it is the same file the server runs: what a
 * player fetches is checked against the SHA-512 of the server's jar.
 *
 * The mod site is Modrinth (api.modrinth.com/v2, read 2026-11-17): POST /version_files {hashes, algorithm: sha512}
 * answers a map of the hashes it knows to versions (id, project_id, environment, files[]: url, filename, size,
 * hashes), leaving out the ones it doesn't; GET /projects?ids=[...] gives each project's slug, title and client_side.
 * No sign-in is needed. What is sent is the files' hashes, nothing about the server or its players. Answers are
 * kept (packs/<id>/lookup.json), so the site is asked about a file once.
 *
 * The pack's mod list (def/source/mods.json) may hold mods put there by hand (client-only ones, changed files, mods
 * for the Steam Deck only). Those are never touched here: only entries marked "from": "server" are added, replaced
 * or removed.
 *
 *   GET  /admin/pack/mods?server=[&again=1]   the list (again: ask the site afresh)
 *   POST /admin/pack/mods {send: {mod id: true|false}}   the host's choices, put into the pack's definition
 */
final class ModList {
	private ModList() {}
	static String site() { return setting("source_modrinth", "https://api.modrinth.com/v2").replaceAll("/+$", ""); }

	/** A jar in the server's mods folder. key: what the host's choice is kept under (the mod's id; the file's name for a jar that says none). */
	record Jar(String file, String sha512, long size, String key, String name, String version, String env, boolean readable) {}

	private static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
	private static String field(String json, String name) {
		java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
		return mt.find() ? mt.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
	}
	static List<Jar> jars(Path serverDir) throws IOException {
		List<Jar> out = new ArrayList<>();
		Path mods = serverDir.resolve("mods");
		if (!Files.isDirectory(mods)) return out;
		List<Path> files;
		try (java.util.stream.Stream<Path> s = Files.list(mods)) { files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).sorted().toList(); }
		for (Path p : files) {
			String file = p.getFileName().toString(), sha;
			byte[] data = Files.readAllBytes(p);
			try { sha = hex(MessageDigest.getInstance("SHA-512").digest(data)); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
			String id = "", name = "", version = "", env = "";
			boolean readable = false;
			try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
				for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
					if (!e.getName().equals("fabric.mod.json")) continue;
					String json = new String(z.readNBytes(1 << 20), StandardCharsets.UTF_8);
					// (these files are written by hand and not always strict JSON: the few plain fields wanted are read by themselves)
					id = field(json, "id"); name = field(json, "name"); version = field(json, "version"); env = field(json, "environment");
					readable = id.matches("[a-z][a-z0-9_\\-]{1,63}");
					break;
				}
			} catch (IOException | RuntimeException notAZip) { readable = false; }
			out.add(new Jar(file, sha, data.length, readable ? id : file, name.isEmpty() ? (readable ? id : file) : name, version, env, readable));
		}
		return out;
	}

	// ------------------------------------------------------------------ the mod site

	private static Path cacheFile(String id) { return Packs.home(id).resolve("lookup.json"); }
	private static Map<String, Object> cache(String id) {
		try { return Json.map(Json.parse(Files.readString(cacheFile(id), StandardCharsets.UTF_8))); } catch (IOException | RuntimeException e) { return new LinkedHashMap<>(); }
	}
	private static String ask(String method, String url, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000); c.setReadTimeout(25000);
		// (the site asks every program to say what it is; this says the program and its version, and nothing about the machine or its owner)
		c.setRequestProperty("User-Agent", "Gatehouse-Gatekeeper/" + ownVersion() + " (mod list lookup)");
		if (body != null) { c.setRequestMethod(method); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		int code = c.getResponseCode();
		if (code == 429) throw new IOException("the mod site asks for a pause (too many questions this minute)");
		if (code >= 400) throw new IOException("the mod site answered " + code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}
	/** Asks the site about the files it hasn't been asked about (all of them when again), and returns what is known:
	 *  sha512 -> {found, project, version, url, filename, size, env, slug, title, client_side}. Throws when the
	 *  site can't be asked; what was known before is still in the cache. */
	static Map<String, Object> lookup(String id, List<Jar> jars, boolean again) throws IOException {
		Map<String, Object> known = again ? new LinkedHashMap<>() : cache(id);
		List<Object> asking = new ArrayList<>();
		for (Jar j : jars) if (!known.containsKey(j.sha512())) asking.add(j.sha512());
		if (asking.isEmpty()) return known;
		Map<String, Object> got = Json.map(Json.parse(ask("POST", site() + "/version_files", Json.write(m("hashes", asking, "algorithm", "sha512")))));
		List<String> projects = new ArrayList<>();
		for (Object h : asking) {
			String sha = Json.str(h);
			Map<String, Object> v = got.get(sha) instanceof Map<?, ?> ? Json.map(got.get(sha)) : null;
			if (v == null) { known.put(sha, m("found", "false")); continue; }
			Map<String, Object> file = null;
			for (Object f : Json.list(v.get("files"))) { Map<String, Object> fm = Json.map(f); if (sha.equalsIgnoreCase(Json.str(Json.map(fm.get("hashes")).get("sha512")))) file = fm; }
			String url = file == null ? "" : Json.str(file.get("url")), name = file == null ? "" : Json.str(file.get("filename")), size = file == null ? "" : Json.str(file.get("size")).replaceAll("\\.0$", "");
			// (only an answer that names this very file, at an https address, with a size and a plain file name, counts as found)
			if (!url.matches("https://[A-Za-z0-9.\\-]+/[^\\s\"\\\\]+") || !name.matches("[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}\\.jar") || !size.matches("\\d{1,12}") || !Json.str(v.get("project_id")).matches("[A-Za-z0-9]{4,16}") || !Json.str(v.get("id")).matches("[A-Za-z0-9]{4,16}")) { known.put(sha, m("found", "false")); continue; }
			known.put(sha, m("found", "true", "project", Json.str(v.get("project_id")), "version", Json.str(v.get("id")), "url", url, "filename", name, "size", size, "env", Json.str(v.get("environment")), "slug", "", "title", "", "client_side", ""));
			if (!projects.contains(Json.str(v.get("project_id")))) projects.add(Json.str(v.get("project_id")));
		}
		// each project's name and address on the site (a file is still "found" without them: its own name is used then)
		try {
			for (int i = 0; i < projects.size(); i += 40) {
				List<Object> some = new ArrayList<>(projects.subList(i, Math.min(projects.size(), i + 40)));
				for (Object o : Json.list(Json.parse(ask("GET", site() + "/projects?ids=" + java.net.URLEncoder.encode(Json.write(some), StandardCharsets.UTF_8), null)))) {
					Map<String, Object> p = Json.map(o);
					for (Object k : known.values()) { Map<String, Object> e = Json.map(k); if (Json.str(p.get("id")).equals(Json.str(e.get("project")))) { e.put("slug", Json.str(p.get("slug"))); e.put("title", Json.str(p.get("title"))); e.put("client_side", Json.str(p.get("client_side"))); } }
				}
			}
		} catch (IOException | RuntimeException ignored) { /* (names only) */ }
		Files.createDirectories(cacheFile(id).getParent());
		Files.writeString(cacheFile(id), Json.write(known), StandardCharsets.UTF_8);
		return known;
	}

	// ------------------------------------------------------------------ the list

	private static Path choicesFile(String id) { return Packs.def(id).resolve("modlist.json"); }
	private static Map<String, Object> choices(String id) {
		try { return Json.map(Json.parse(Files.readString(choicesFile(id), StandardCharsets.UTF_8))); } catch (IOException | RuntimeException e) { return new LinkedHashMap<>(); }
	}
	/** Gatekeeper's guess for a mod, and why: {"true" or "false", the reason}. */
	static String[] guess(Jar j, Map<String, Object> site) {
		if (!j.readable()) return new String[] {"false", "This file doesn't say what mod it is (it isn't a Fabric mod's jar), so players aren't sent it unless you say so."};
		if (j.env().equals("server")) return new String[] {"false", "The mod's own file says it is for servers only."};
		String env = Json.str(site.get("env")), client = Json.str(site.get("client_side"));
		if (env.contains("server_only") || client.equals("unsupported")) return new String[] {"false", "Its page on the mod site says players don't use it."};
		if (client.equals("optional")) return new String[] {"true", "Its page says players can do without it; sent unless you switch it off."};
		return new String[] {"true", "Players need it to join, as far as the mod says."};
	}
	@SuppressWarnings("unchecked") private static Map<String, Object> src(String id) throws IOException { return (Map<String, Object>) PackBuilder.parse(Packs.def(id).resolve("source/mods.json")); }
	private static boolean fromServer(Map<String, Object> e) { return "server".equals(Json.str(e.get("from"))); }
	/** Is this server jar already in the pack as something put there by hand? */
	private static boolean byHand(Jar j, Map<String, Object> site, List<Object> entries) {
		for (Object o : entries) {
			Map<String, Object> e = Json.map(o);
			if (fromServer(e)) continue;
			if (j.sha512().equalsIgnoreCase(Json.str(e.get("sha512"))) || j.file().equals(Json.str(e.get("filename")))) return true;
			if (!Json.str(site.get("project")).isEmpty() && Json.str(site.get("project")).equals(Json.str(e.get("project")))) return true;
			if (j.readable() && (j.key().equals(Json.str(e.get("slug"))) || (e.get("toggle") instanceof Map<?, ?> && j.key().equals(Json.str(Json.map(e.get("toggle")).get("modid")))))) return true;
		}
		return false;
	}

	static Map<String, Object> state(Map<String, Object> s, boolean again) {
		String id = Json.str(s.get("id"));
		if (!Packs.defined(id)) return m("result", "refused", "message", "This server has no pack yet: make one first.");
		try {
			List<Jar> jars = jars(Path.of(Json.str(s.get("dir"))));
			Map<String, Object> known;
			String trouble = "";
			try { known = lookup(id, jars, again); } catch (IOException | RuntimeException e) { known = cache(id); trouble = "The mod site couldn't be asked (" + e.getMessage() + "). Mods it hasn't been asked about can't be added until it answers: press Look again later."; }
			Map<String, Object> chosen = choices(id);
			List<Object> entries = Json.list(src(id).get("mods")), rows = new ArrayList<>(), gone = new ArrayList<>();
			List<String> here = new ArrayList<>();
			for (Jar j : jars) {
				here.add(j.key());
				boolean asked = known.containsKey(j.sha512());
				Map<String, Object> site = asked ? Json.map(known.get(j.sha512())) : new LinkedHashMap<>();
				boolean found = "true".equals(Json.str(site.get("found"))), hand = byHand(j, site, entries);
				String[] g = guess(j, site);
				String in = "";
				for (Object o : entries) { Map<String, Object> e = Json.map(o); if (fromServer(e) && j.key().equals(Json.str(e.get("from_id")))) in = j.sha512().equalsIgnoreCase(Json.str(e.get("sha512"))) ? "yes" : "older"; }
				rows.add(m("key", j.key(), "file", j.file(), "name", found && !Json.str(site.get("title")).isEmpty() ? Json.str(site.get("title")) : j.name(), "version", j.version(), "mb", String.format(java.util.Locale.ROOT, "%.1f", j.size() / 1048576.0),
						"from", hand ? "" : !asked ? "unknown" : found ? "site" : "gatekeeper", "send", hand ? false : chosen.containsKey(j.key()) ? ServerTools.yes(chosen.get(j.key())) : g[0].equals("true"),
						"guess", g[0].equals("true"), "why", hand ? "Already in the pack, put there by hand: left as it is." : g[1], "chosen", chosen.containsKey(j.key()), "hand", hand, "in_pack", hand ? "hand" : in));
			}
			for (Object o : entries) { Map<String, Object> e = Json.map(o); if (fromServer(e) && !here.contains(Json.str(e.get("from_id")))) gone.add(Json.str(e.get("title"))); }
			int byHandOnly = 0;
			for (Object o : entries) if (!fromServer(Json.map(o))) byHandOnly++;
			return m("result", "ok", "mods", rows, "gone", gone, "by_hand", byHandOnly, "trouble", trouble, "site", site().replaceFirst("^https?://", "").replaceFirst("/.*", ""));
		} catch (IOException | RuntimeException e) { return m("result", "failed", "message", "The server's mods couldn't be read: " + e.getMessage()); }
	}

	/** The host's choices, put into the pack's definition. Returns {result, message, notes (a start for the notes of the pack's next version)}. */
	static synchronized Map<String, Object> apply(Map<String, Object> s, Map<String, Object> send) {
		String id = Json.str(s.get("id"));
		if (!Packs.defined(id)) return m("result", "refused", "message", "This server has no pack yet: make one first.");
		try {
			Path d = Packs.def(id), ownDir = d.resolve("source/files/mods");
			List<Jar> jars = jars(Path.of(Json.str(s.get("dir"))));
			Map<String, Object> known;
			try { known = lookup(id, jars, false); } catch (IOException | RuntimeException e) { known = cache(id); }
			Map<String, Object> chosen = choices(id);
			// (only a choice that differs from Gatekeeper's guess is kept as the host's: the rest go on following the guess)
			for (Map.Entry<String, Object> e : send.entrySet()) {
				if (e.getKey().length() > 160) continue;
				Jar j = null;
				for (Jar one : jars) if (one.key().equals(e.getKey())) j = one;
				boolean guessed = j != null && guess(j, known.containsKey(j.sha512()) ? Json.map(known.get(j.sha512())) : new LinkedHashMap<>())[0].equals("true");
				if (j != null && guessed == ServerTools.yes(e.getValue())) chosen.remove(e.getKey()); else chosen.put(e.getKey(), ServerTools.yes(e.getValue()));
			}
			Map<String, Object> src = src(id);
			List<Object> entries = Json.list(src.get("mods"));
			List<String> added = new ArrayList<>(), removed = new ArrayList<>(), updated = new ArrayList<>(), waiting = new ArrayList<>();
			Map<String, Jar> byKey = new LinkedHashMap<>();
			for (Jar j : jars) byKey.put(j.key(), j);
			List<Path> unused = new ArrayList<>();
			// what the list put there before: kept, replaced by the server's newer file, or taken out
			for (int i = entries.size() - 1; i >= 0; i--) {
				Map<String, Object> e = Json.map(entries.get(i));
				if (!fromServer(e)) continue;
				Jar j = byKey.get(Json.str(e.get("from_id")));
				Map<String, Object> site = j != null && known.containsKey(j.sha512()) ? Json.map(known.get(j.sha512())) : new LinkedHashMap<>();
				boolean wanted = j != null && !byHand(j, site, entries) && (chosen.containsKey(j.key()) ? ServerTools.yes(chosen.get(j.key())) : guess(j, site)[0].equals("true"));
				if (wanted && j.sha512().equalsIgnoreCase(Json.str(e.get("sha512")))) continue;
				if (wanted && !known.containsKey(j.sha512())) { waiting.add(j.name()); continue; }   // (a newer file the site hasn't been asked about: the older one stays for now)
				if (!e.containsKey("url")) unused.add(ownDir.resolve(Json.str(e.get("filename"))));
				entries.remove(i);
				(wanted ? updated : removed).add(Json.str(e.get("title")));
			}
			for (Jar j : jars) {
				Map<String, Object> site = known.containsKey(j.sha512()) ? Json.map(known.get(j.sha512())) : new LinkedHashMap<>();
				if (byHand(j, site, entries)) continue;
				boolean wanted = chosen.containsKey(j.key()) ? ServerTools.yes(chosen.get(j.key())) : guess(j, site)[0].equals("true"), there = false;
				for (Object o : entries) { Map<String, Object> e = Json.map(o); if (fromServer(e) && j.key().equals(Json.str(e.get("from_id")))) there = true; }
				if (!wanted || there) continue;
				if (!known.containsKey(j.sha512())) { waiting.add(j.name()); continue; }
				Map<String, Object> e = new LinkedHashMap<>();
				if ("true".equals(Json.str(site.get("found")))) {
					String slug = Json.str(site.get("slug")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
					if (slug.isEmpty()) slug = j.key().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
					for (Object o : entries) if (slug.equals(Json.str(Json.map(o).get("slug")))) slug = slug + "-" + Json.str(site.get("version")).toLowerCase(java.util.Locale.ROOT);
					e.put("title", Json.str(site.get("title")).isEmpty() ? j.name() : Json.str(site.get("title"))); e.put("slug", slug); e.put("filename", Json.str(site.get("filename"))); e.put("url", Json.str(site.get("url")));
					e.put("sha512", j.sha512()); e.put("project", Json.str(site.get("project"))); e.put("version", Json.str(site.get("version"))); e.put("size", Long.valueOf(Json.str(site.get("size"))));
				} else {
					if (!j.file().matches("[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}")) continue;
					Files.createDirectories(ownDir);
					Files.copy(Path.of(Json.str(s.get("dir"))).resolve("mods").resolve(j.file()), ownDir.resolve(j.file()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					unused.remove(ownDir.resolve(j.file()));
					e.put("title", j.name()); e.put("filename", j.file()); e.put("sha512", j.sha512());
				}
				e.put("from", "server"); e.put("from_id", j.key());
				entries.add(e);
				if (updated.contains(Json.str(e.get("title"))) || updated.contains(j.name())) continue;
				added.add(Json.str(e.get("title")));
			}
			src.put("mods", entries);
			Files.writeString(Packs.def(id).resolve("source/mods.json"), PackBuilder.dumps(src, false) + "\n", StandardCharsets.UTF_8);
			Files.writeString(choicesFile(id), Json.write(chosen), StandardCharsets.UTF_8);
			for (Path p : unused) { boolean used = false; for (Object o : entries) if (p.getFileName().toString().equals(Json.str(Json.map(o).get("filename")))) used = true; if (!used) Files.deleteIfExists(p); }
			List<String> said = new ArrayList<>(), notes = new ArrayList<>();
			if (!added.isEmpty()) { said.add("added " + String.join(", ", added)); notes.add("Added: " + String.join(", ", added)); }
			if (!updated.isEmpty()) { said.add("updated " + String.join(", ", updated)); notes.add("Updated: " + String.join(", ", updated)); }
			if (!removed.isEmpty()) { said.add("removed " + String.join(", ", removed)); notes.add("Removed: " + String.join(", ", removed)); }
			String wait = waiting.isEmpty() ? "" : " Not added yet, because the mod site hasn't answered about them: " + String.join(", ", waiting) + ".";
			if (said.isEmpty()) return m("result", "ok", "message", "Saved. Nothing in the pack changes." + wait, "notes", "");
			log("packs: " + Json.str(s.get("name")) + "'s mod list was changed: " + String.join("; ", said));
			return m("result", "ok", "message", "Saved: " + String.join("; ", said) + ". Players get it with the pack's next version: press New version." + wait, "notes", String.join("\n", notes));
		} catch (IOException | RuntimeException e) { return m("result", "failed", "message", "The list wasn't saved: " + e.getMessage()); }
	}

	static boolean admin(HttpExchange x, String path, Map<String, Object> s) throws IOException {
		if (!path.equals("/admin/pack/mods")) return false;
		if (x.getRequestMethod().equals("POST")) reply(x, 200, apply(s, Json.map(body(x).get("send"))));
		else { String q = x.getRequestURI().getQuery(); reply(x, 200, state(s, q != null && q.contains("again=1"))); }
		return true;
	}
}
