package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.gatehouse.look.Quiet;

/**
 * The packs this Gatekeeper keeps, builds and hands to launchers: one for a server, under packs/<server id>/ beside
 * the settings.
 *   def/        the pack's definition (PackBuilder says what is in one), plus art-auto (the Steam pictures made here
 *               for the slots the owner chose none for: PackArt) and, where it was brought in with one, mmc-pack.json
 *   built/      the pack as launchers fetch it: main and deck (PackBuilder writes them)
 *   pack.json   what was built last, when, and with which program
 *
 * Launchers fetch a pack with what they already have: the sync scripts and packwiz-installer ask plain files of a
 * "pack source", and Install reads a profile and a few files from a "repository". Gatekeeper is both, at
 *   GET /v1/pack/<server id>/profile.json          the profile Install reads (this address as its repository and
 *                                                   its one pack source)
 *   GET /v1/pack/<server id>/clients/<file>        what Install puts in the game folder
 *   GET /v1/pack/<server id>/main|deck/<file>      the pack itself
 * No secret can be asked for these (packwiz-installer can't send one), so a request is matched to launchers by the
 * address it comes from, and answered only when the server is for one of them (Invites: private, shareable, public).
 * Like everything of Gatekeeper's they are only reachable on this machine and on its Tailscale address.
 *
 * Gatekeeper's own window (never the web panel or another Gatekeeper: these name folders on this machine, or change
 * what players are sent):
 *   GET  /admin/pack?server=            how the server's pack stands
 *   POST /admin/pack/start              makes a pack for a Fabric server: its versions, no mods yet
 *   POST /admin/pack/import {folder}    brings in a pack's definition from a folder on this machine
 *   POST /admin/pack/build              builds it again as it is
 *   POST /admin/pack/release {notes}    a new version with those notes, then builds
 *   GET, POST /admin/pack/art?slot=     a Steam picture: the one in use; a chosen one (the file as the body), or none
 *   GET, POST /admin/pack/mods          which of the server's mods players are sent (ModList)
 *   POST /admin/pack/visibility {mode}  who the server and its pack are for: private, shareable or public (Invites)
 *   POST /admin/pack/invite             an invite from the owner; POST /admin/pack/invites/takeback takes them all back
 */
final class Packs {
	private Packs() {}
	static final int MAX_ART = 12 * 1024 * 1024;
	static final String BOOTSTRAP = "packwiz-installer-bootstrap.jar";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	static boolean okId(String id) { return id.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}"); }
	static Path home(String id) { return dir.resolve("packs").resolve(id); }
	static Path def(String id) { return home(id).resolve("def"); }
	static Path built(String id) { return home(id).resolve("built"); }
	static boolean defined(String id) { return okId(id) && Files.isRegularFile(def(id).resolve("source/mods.json")); }
	static boolean has(String id) { return okId(id) && Files.isRegularFile(built(id).resolve("main/pack.toml")); }
	/** Where launchers fetch a server's pack from: this Gatekeeper, at the address launchers reach it. */
	static String base(String id) { String b = Net.reachable(); return (b.isEmpty() ? "http" : Tls.scheme()) + "://" + (b.isEmpty() ? "127.0.0.1" : b) + ":" + port() + "/v1/pack/" + id; }
	private static String[] no(String why) { return new String[] {"refused", why}; }
	private static Map<String, Object> info(String id) {
		try { return Json.map(Json.parse(Files.readString(home(id).resolve("pack.json"),
				StandardCharsets.UTF_8))); } catch (IOException | RuntimeException e) { return new LinkedHashMap<>(); }
	}
	@SuppressWarnings("unchecked") private static Map<String,
			Object> obj(Path f) throws IOException { Object o = PackBuilder.parse(f); if (o instanceof Map<?, ?> mm) return (Map<String,
			Object>) mm; throw new IOException(f.getFileName() + " isn't what was expected"); }
	@SuppressWarnings("unchecked") private static List<Object> arr(Path f) throws IOException { Object o = PackBuilder.parse(f); if (o instanceof List<?> l) return (List<Object>) l; throw new IOException(f.getFileName() + " isn't what was expected"); }
	private static void write(Path f, Object o) throws IOException { Files.createDirectories(f.getParent()); Files.writeString(f,
			PackBuilder.dumps(o, false) + "\n", StandardCharsets.UTF_8); }

	/** The Fabric loader a server's folder runs ("" when it isn't a Fabric server): the client's has to be the same. */
	static String fabricLoader(Path serverDir) {
		Path l = serverDir.resolve("libraries/net/fabricmc/fabric-loader");
		if (!Files.isDirectory(l)) return "";
		try (java.util.stream.Stream<Path> s = Files.list(l)) { return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(v -> v.matches("[\\w.+\\-]{1,32}")).sorted().reduce((a, b) -> b).orElse(""); }
		catch (IOException e) { return ""; }
	}
	private static String gameAddress(Map<String, Object> s) {
		String b = Net.reachable(), p = Json.str(s.get("minecraft")).replaceAll(".*:", "");
		return (b.isEmpty() ? "127.0.0.1" : b) + ":" + (p.isEmpty() ? "25565" : p);
	}

	/** Makes a pack for a Fabric server this Gatekeeper looks after: its Minecraft and loader versions, and no mods
	 *  yet (players get an instance that can join; mods are added to its list afterwards). */
	static synchronized String[] start(Map<String, Object> s) {
		String id = Json.str(s.get("id")), name = Json.str(s.get("name"));
		if (!okId(id)) return no("No such server.");
		if (defined(id)) return no(name + " already has a pack.");
		Path sd = Path.of(Json.str(s.get("dir")));
		String loader = fabricLoader(sd), version = serverFacts(sd)[1];
		if (loader.isEmpty()) return no("Not supported for this kind of server yet: Gatekeeper builds packs for Fabric servers. (A plain server needs no pack: launchers connect to it as it is.)");
		if (version.isEmpty()) return no("Gatekeeper doesn't know this server's Minecraft version yet: start the server once, then try again.");
		try {
			Path d = def(id);
			write(d.resolve("source/mods.json"), m("pack", m("name", name, "author", AutoUpdate.myName(), "version", "1.0.0", "minecraft",
					version, "fabric", loader), "mods", new ArrayList<>()));
			write(d.resolve("source/changelog.json"), new ArrayList<>(List.of(m("version", "1.0.0", "date", LocalDate.now().toString(),
					"notes", new ArrayList<>(List.of("The first version of this pack."))))));
			write(d.resolve("config.json"), m("pack_name", name, "instance_id", id, "instance_name", name + " " + version + " (Fabric)"));
		} catch (IOException e) { return new String[] {"failed", "The pack couldn't be made: " + e.getMessage()}; }
		log("packs: a pack was made for " + name + " (Minecraft " + version + ", Fabric loader " + loader + ")");
		return build(s, "made");
	}

	private static void copyDir(Path from, Path to, String skip) throws IOException {
		if (!Files.isDirectory(from)) return;
		List<Path> all;
		try (java.util.stream.Stream<Path> st = Files.walk(from)) { all = st.filter(Files::isRegularFile).toList(); }
		for (Path p : all) {
			String rel = from.relativize(p).toString().replace('\\', '/');
			if (rel.equals(skip)) continue;
			Path t = to.resolve(rel);
			Files.createDirectories(t.getParent());
			Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}
	/** Brings in a definition from a folder on this machine: either a definition as PackBuilder describes one, or the
	 *  folder October Journey was built from before (source/, installer/config.json, installer/art, server/, and
	 *  clients/mmc-pack.json). What the server's pack was before is kept beside it (def-before) until the next one. */
	static synchronized String[] bringIn(Map<String, Object> s, String folder) {
		String id = Json.str(s.get("id")), name = Json.str(s.get("name"));
		if (!okId(id)) return no("No such server.");
		Path f;
		try { f = Path.of(folder).toAbsolutePath().normalize(); } catch (RuntimeException e) { return no("That isn't a folder."); }
		if (!Files.isRegularFile(f.resolve("source/mods.json"))
				|| !Files.isRegularFile(f.resolve("source/changelog.json"))) return no("That folder doesn't hold a pack's definition (no source/mods.json and source/changelog.json in it).");
		boolean repo = Files.isRegularFile(f.resolve("installer/config.json"));
		if (!repo && !Files.isRegularFile(f.resolve("config.json"))) return no("That folder has no config.json for the pack.");
		try {
			Path fresh = home(id).resolve("def-new"), d = def(id), before = home(id).resolve("def-before");
			PackBuilder.wipe(fresh);
			Files.createDirectories(fresh.resolve("source"));
			Files.copy(f.resolve("source/mods.json"), fresh.resolve("source/mods.json"));
			Files.copy(f.resolve("source/changelog.json"), fresh.resolve("source/changelog.json"));
			copyDir(f.resolve("source/files"), fresh.resolve("source/files"), "launch-popup.jar");
			Files.copy(f.resolve(repo ? "installer/config.json" : "config.json"), fresh.resolve("config.json"));
			copyDir(f.resolve(repo ? "installer/art" : "art"), fresh.resolve("art"), "");
			copyDir(f.resolve("server"), fresh.resolve("server"), "");
			Path mmc = f.resolve(repo ? "clients/mmc-pack.json" : "mmc-pack.json");
			if (Files.isRegularFile(mmc)) Files.copy(mmc, fresh.resolve("mmc-pack.json"));
			obj(fresh.resolve("source/mods.json"));
			arr(fresh.resolve("source/changelog.json"));
			obj(fresh.resolve("config.json"));   // (readable, or nothing is replaced)
			PackBuilder.wipe(before);
			if (Files.exists(d)) Files.move(d, before);
			Files.move(fresh, d);
		} catch (IOException e) { return new String[] {"failed", "The pack wasn't brought in: " + e.getMessage() + ". What was there is unchanged."}; }
		log("packs: " + name + "'s pack was brought in from " + f);
		return build(s, "brought in");
	}

	/** Builds a server's pack from its definition and puts it where launchers fetch it. */
	static synchronized String[] build(Map<String, Object> s, String why) {
		String id = Json.str(s.get("id")), name = Json.str(s.get("name"));
		if (!defined(id)) return no(name + " has no pack yet.");
		try {
			Path d = def(id), h = home(id);
			// what the pack says about where it comes from and what it joins is this Gatekeeper's to say
			Map<String, Object> config = Files.isRegularFile(d.resolve("config.json")) ? obj(d.resolve("config.json")) : new LinkedHashMap<>();
			String before = PackBuilder.dumps(config, false);
			Map<String, Object> pack = Json.map(obj(d.resolve("source/mods.json")).get("pack"));
			if (Json.str(config.get("pack_name")).isEmpty()) config.put("pack_name", name);
			if (!Json.str(config.get("instance_id")).matches("[A-Za-z0-9._-]{1,64}")) config.put("instance_id", id);
			if (Json.str(config.get("instance_name")).isEmpty()) config.put("instance_name", Json.str(config.get("pack_name")) + " "
					+ Json.str(pack.get("minecraft")) + " (Fabric)");
			config.put("repo_raw", base(id));
			config.put("pack_sources", new ArrayList<>(List.of(base(id))));
			if (Json.list(config.get("servers")).isEmpty()) config.put("servers", new ArrayList<>(List.of(m("name",
					Json.str(config.get("pack_name")), "address", gameAddress(s)))));
			Map<String, Object> control = config.get("server_control") instanceof Map<?, ?> ? Json.map(config.get("server_control")) : new LinkedHashMap<>();
			control.put("server", id);
			control.put("helper_url", (Net.reachable().isEmpty() ? "http" : Tls.scheme()) + "://" + (Net.reachable().isEmpty() ? "127.0.0.1" : Net.reachable()) + ":" + port());
			if (Net.internet()) control.put("join_key", Net.joinKey());   // (R79 stage 3: a launcher installing the pack can introduce itself)
			if (Tls.on() && !setting("bind", "").isEmpty()) control.put("helper_fp", Tls.fingerprint());   // (R79: launchers check its certificate by this)
			if (Json.str(control.get("minecraft")).isEmpty()) control.put("minecraft", gameAddress(s));
			config.put("server_control", control);
			if (!PackBuilder.dumps(config, false).equals(before)) write(d.resolve("config.json"), config);
			PackArt.fill(d, Json.str(config.get("pack_name")), Icons.png(id));
			String gatehouse = Json.str(notesInside("gatehouse").get("version"));
			Path fresh = h.resolve("building"), b = built(id), old = h.resolve("built-before");
			PackBuilder.wipe(fresh);
			List<String> said = PackBuilder.build(d, fresh, Files.readAllBytes(Peer.ownJar()), gatehouse);
			PackBuilder.wipe(old);
			IOException last = null;
			for (int i = 0; i < 10; i++) {   // (a launcher may be reading a file of the old one this very moment)
				try { if (Files.exists(b)) Files.move(b, old); Files.move(fresh, b); last = null; break; }
				catch (IOException e) { last = e; try { Thread.sleep(300); } catch (InterruptedException ie) { break; } }
			}
			if (last != null) throw last;
			try { PackBuilder.wipe(old); } catch (IOException ignored) { Quiet.seen(ignored); }
			String version = Json.str(pack.get("version")), was = Json.str(info(id).get("version"));
			Files.writeString(h.resolve("pack.json"), Json.write(m("version", version, "gatehouse", gatehouse, "built_at",
					LocalDateTime.now().format(TIME), "said", String.join("; ", said), "why", why)), StandardCharsets.UTF_8);
			if (!version.equals(was)) History.record("pack", s, version);
			log("packs: " + name + "'s pack " + version + " was built (" + why + "): " + String.join("; ", said));
			return new String[] {"ok", "Built " + Json.str(config.get("pack_name")) + " " + version + ": " + String.join("; ", said)
					+ ". Players get it the next time they press Play."};
		} catch (IOException | RuntimeException e) {
			log("packs: " + name + "'s pack wasn't built: " + e.getMessage());
			return new String[] {"failed", "The pack wasn't built: " + e.getMessage() + ". Players keep the one they have."};
		}
	}

	/** A new version: the notes players read, and the number after the one before (1.4.2 to 1.4.3). */
	static synchronized String[] release(Map<String, Object> s, String notesText) {
		String id = Json.str(s.get("id"));
		if (!defined(id)) return no(Json.str(s.get("name")) + " has no pack yet.");
		List<Object> notes = new ArrayList<>();
		for (String line : notesText.split("\\R")) { String t = line.replaceFirst("^\\s*[-*]\\s*",
				"").trim(); if (!t.isEmpty()) notes.add(t.length() > 400 ? t.substring(0, 400) : t); }
		if (notes.isEmpty()) return no("Write what changed, a line for each thing: players read it before they update.");
		try {
			Path mods = def(id).resolve("source/mods.json"), log = def(id).resolve("source/changelog.json");
			Map<String, Object> src = obj(mods), pack = Json.map(src.get("pack"));
			String v = Json.str(pack.get("version"));
			java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(v);
			String next = mt.matches() ? mt.group(1) + (Long.parseLong(mt.group(2)) + 1) : v.isEmpty() ? "1.0.0" : v + ".1";
			pack.put("version", next);
			List<Object> entries = arr(log);
			entries.add(0, m("version", next, "date", LocalDate.now().toString(), "notes", notes));
			write(mods, src); write(log, entries);
		} catch (IOException | RuntimeException e) { return new String[] {"failed", "The new version wasn't made: " + e.getMessage()}; }
		return build(s, "a new version");
	}

	/** At start: a pack that was built with an older program is built again, so the launcher it carries is this one. */
	static void refresh() {
		String gatehouse = Json.str(notesInside("gatehouse").get("version"));
		for (Object o : new ArrayList<>(servers())) {
			Map<String, Object> s = Json.map(o);
			String id = Json.str(s.get("id"));
			if (defined(id)) repairArt(s);
			if (defined(id) && (!has(id) || !gatehouse.equals(Json.str(info(id).get("gatehouse")))
					|| !Json.str(obj0(def(id).resolve("config.json")).get("repo_raw")).equals(base(id)))) build(s, "this Gatekeeper changed");
		}
	}
	/** R68: before 3.18.1, undoing a choice deleted the picture the pack came with. A pack whose other pictures are all
	 *  still the set this program carries (October Journey's) gets a lost one back from that set; none chosen is touched. */
	static void repairArt(Map<String, Object> s) {
		String id = Json.str(s.get("id"));
		Path art = def(id).resolve("art");
		try {
			List<String> missing = new ArrayList<>();
			int same = 0;
			for (String slot : PackArt.SLOTS) {
				Path own = art.resolve(slot + ".png");
				if (Files.isRegularFile(art.resolve(slot + ".chosen"))) continue;
				if (!Files.isRegularFile(own)) { missing.add(slot); continue; }
				if (!java.util.Arrays.equals(Files.readAllBytes(own), PackBuilder.extra("art/" + slot + ".png"))) return;   // (its own art: not ours to fill in)
				same++;
			}
			if (missing.isEmpty() || same == 0) return;
			for (String slot : missing) Files.write(art.resolve(slot + ".png"), PackBuilder.extra("art/" + slot + ".png"));
			log("packs: " + Json.str(s.get("name")) + "'s " + String.join(", ", missing) + " picture(s) for Steam, lost by an older Gatekeeper, were put back");
			// (refresh() builds next: a new program always rebuilds, so this goes out with this update)
		} catch (IOException | RuntimeException e) { Quiet.seen(e); }
	}
	private static Map<String, Object> obj0(Path f) { try { return obj(f); } catch (IOException e) { return new LinkedHashMap<>(); } }

	// ------------------------------------------------------------------ what launchers fetch

	private static void send(HttpExchange x, byte[] b, String type) throws IOException {
		x.getResponseHeaders().set("Content-Type", type);
		x.sendResponseHeaders(200, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}
	private static String type(String file) {
		String f = file.toLowerCase(java.util.Locale.ROOT);
		return f.endsWith(".json") ? "application/json" : f.endsWith(".toml") || f.endsWith(".txt") || f.endsWith(".ps1")
				|| f.endsWith(".sh") || f.endsWith(".vdf") ? "text/plain; charset=utf-8" : f.endsWith(".png") ? "image/png"
				: f.endsWith(".jar") ? "application/java-archive" : "application/octet-stream";
	}
	/** The profile Install reads: this Gatekeeper as the pack's repository and its one source. */
	static byte[] profile(String id) throws IOException {
		Map<String, Object> config = obj(def(id).resolve("config.json")), out = new LinkedHashMap<>();
		for (String k : new String[] {"pack_name", "instance_id", "instance_name", "repo_raw", "pack_sources"}) out.put(k, config.get(k));
		List<Object> servers = new ArrayList<>();
		for (Object o : Json.list(config.get("servers"))) servers.add(m("name", Json.map(o).get("name"), "address", Json.map(o).get("address")));
		out.put("servers", servers);
		Map<String, Object> control = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : Json.map(config.get("server_control")).entrySet()) if (!e.getKey().startsWith("_")) control.put(e.getKey(), e.getValue());
		out.put("server_control", control);
		out.put("bootstrap_sha256", sha256(PackBuilder.extra("clients/" + BOOTSTRAP)).toUpperCase(java.util.Locale.ROOT));
		return (PackBuilder.dumps(out, false) + "\n").getBytes(StandardCharsets.UTF_8);
	}
	/** What Prism Launcher is told the instance is made of: the one the definition came with, or the pack's versions. */
	static byte[] mmcPack(String id) throws IOException {
		Path own = def(id).resolve("mmc-pack.json");
		if (Files.isRegularFile(own)) return Files.readAllBytes(own);
		Map<String, Object> pack = Json.map(obj(def(id).resolve("source/mods.json")).get("pack"));
		String mc = Json.str(pack.get("minecraft")), loader = Json.str(pack.get("fabric"));
		return ("{\n    \"components\": [\n        { \"uid\": \"net.minecraft\", \"version\": \"" + mc
				+ "\", \"important\": true },\n        { \"uid\": \"net.fabricmc.intermediary\", \"version\": \"" + mc
				+ "\", \"dependencyOnly\": true },\n        { \"uid\": \"net.fabricmc.fabric-loader\", \"version\": \"" + loader
						+ "\" }\n    ],\n    \"formatVersion\": 1\n}\n").getBytes(StandardCharsets.UTF_8);
	}
	/** GET /v1/pack/<server id>/... */
	static void serve(HttpExchange x, String path) throws IOException {
		String rest = path.substring("/v1/pack/".length());
		int cut = rest.indexOf('/');
		String id = cut < 0 ? rest : rest.substring(0, cut), file = cut < 0 ? "" : rest.substring(cut + 1);
		if (!has(id) || file.isEmpty() || file.contains("..") || file.contains("\\") || file.contains(":")
				|| file.startsWith("/")) { reply(x, 404, m("error", "no such file")); return; }
		if (!Invites.packFor(x, id)) { reply(x, 403, m("error",
				"this pack isn't for this device: its owner hasn't let this launcher in, and it has used no invite")); return; }
		try {
			if (file.equals("profile.json")) { send(x, profile(id), "application/json"); return; }
			if (file.equals("clients/mmc-pack.json")) { send(x, mmcPack(id), "application/json"); return; }
			if (file.startsWith("clients/")) {
				String n = file.substring("clients/".length());
				if (!n.equals(BOOTSTRAP) && !List.of(PackBuilder.CLIENTS).contains(n)) { reply(x, 404, m("error", "no such file")); return; }
				send(x, PackBuilder.extra("clients/" + n), type(n));
				return;
			}
			if (file.startsWith("main/") || file.startsWith("deck/")) {
				Path root = built(id).toAbsolutePath().normalize(), p = root.resolve(file).normalize();
				if (p.startsWith(root) && Files.isRegularFile(p)) { send(x, Files.readAllBytes(p), type(file)); return; }
			}
		} catch (IOException e) { reply(x, 404, m("error", "no such file")); return; }
		reply(x, 404, m("error", "no such file"));
	}

	// ------------------------------------------------------------------ Gatekeeper's own window

	private static String q(HttpExchange x, String key) {
		String query = x.getRequestURI().getQuery();
		if (query != null) for (String part : query.split("&")) if (part.startsWith(key
				+ "=")) return java.net.URLDecoder.decode(part.substring(key.length() + 1), StandardCharsets.UTF_8);
		return "";
	}
	static Map<String, Object> state(Map<String, Object> s) {
		String id = Json.str(s.get("id"));
		Path sd = Path.of(Json.str(s.get("dir")));
		String loader = fabricLoader(sd);
		Map<String, Object> out = m("server", id, "name", Json.str(s.get("name")), "kind", loader.isEmpty() ? serverFacts(sd)[0]
				: "fabric", "minecraft", serverFacts(sd)[1], "loader", loader,
				"defined", defined(id), "built", has(id), "address", base(id), "slots", List.of(PackArt.SLOTS), "slot_says", List.of(PackArt.SAYS));
		out.putAll(Invites.state(s));
		if (!defined(id)) {
			out.put("message", loader.isEmpty()
					? "Not supported for this kind of server yet: Gatekeeper builds packs for Fabric servers. A plain server needs no pack. A pack made elsewhere can still be brought in from its folder."
					: "This server has no pack yet. Making one gives players an instance with its Minecraft and Fabric versions, set up and kept current from their launcher.");
			return out;
		}
		Map<String, Object> i = info(id);
		try {
			Map<String, Object> src = obj(def(id).resolve("source/mods.json")), pack = Json.map(src.get("pack"));
			int all = 0, deck = 0, own = 0, toggles = 0;
			for (Object o : Json.list(src.get("mods"))) { Map<String,
					Object> md = Json.map(o); all++; if (ServerTools.yes(md.get("deck"))) deck++; if (!md.containsKey("url")) own++; if (md.containsKey("toggle")) toggles++; }
			List<Object> log = arr(def(id).resolve("source/changelog.json"));
			out.putAll(m("pack_name", Json.str(pack.get("name")), "version", Json.str(pack.get("version")), "pack_minecraft",
					Json.str(pack.get("minecraft")), "pack_loader", Json.str(pack.get("fabric")),
					"mods", all, "deck_mods", deck, "own_mods", own, "switchable", toggles, "notes", log.isEmpty() ? new ArrayList<>()
							: Json.list(Json.map(log.get(0)).get("notes"))));
		} catch (IOException | RuntimeException e) { out.put("message", "The pack's definition can't be read: " + e.getMessage()); }
		Map<String, Object> art = new LinkedHashMap<>();
		// (chosen: one the owner picked here; pack: the picture the pack came with; made: one Gatekeeper makes)
		for (String slot : PackArt.SLOTS) art.put(slot, Files.isRegularFile(def(id).resolve("art").resolve(slot + ".chosen")) ? "chosen"
				: Files.isRegularFile(def(id).resolve("art").resolve(slot + ".png")) ? "pack" : "made");
		out.putAll(m("art", art, "built_version", Json.str(i.get("version")), "built_at", Json.str(i.get("built_at")), "built_with",
				Json.str(i.get("gatehouse")), "said", Json.str(i.get("said"))));
		return out;
	}
	private static void result(HttpExchange x, String[] r) throws IOException { reply(x, 200, m("result", r[0], "message", r[1])); }

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/pack") && !path.startsWith("/admin/pack/")) return false;
		Map<String, Object> s = server();
		boolean post = x.getRequestMethod().equals("POST");
		if (Json.str(s.get("id")).isEmpty()) { reply(x, 200, m("result", "refused", "message", "No such server.")); return true; }
		String id = Json.str(s.get("id"));
		if (ModList.admin(x, path, s)) return true;
		if (path.equals("/admin/pack") && !post) reply(x, 200, state(s));
		else if (path.equals("/admin/pack/visibility") && post) result(x, Invites.setMode(s, Json.str(body(x).get("mode"))));
		else if (path.equals("/admin/pack/invite") && post) reply(x, 200, Invites.make(s, "host", "the owner"));
		else if (path.equals("/admin/pack/invites/takeback") && post) result(x, Invites.takeBack(s));
		else if (path.equals("/admin/pack/start") && post) result(x, start(s));
		else if (path.equals("/admin/pack/build") && post) result(x, build(s, "asked for in Gatekeeper's window"));
		else if (path.equals("/admin/pack/release") && post) result(x, release(s, Json.str(body(x).get("notes"))));
		else if (path.equals("/admin/pack/import") && post) result(x, bringIn(s, Json.str(body(x).get("folder"))));
		else if (path.equals("/admin/pack/art")) {
			int slot = PackArt.slot(q(x, "slot"));
			if (slot < 0 || !defined(id)) { reply(x, 200, m("result", "refused", "message", !defined(id) ? "This server has no pack yet."
					: "No such picture.")); return true; }
			Path own = def(id).resolve("art").resolve(PackArt.SLOTS[slot] + ".png");
			if (!post) {
				if (!Files.isRegularFile(own)) PackArt.fill(def(id),
						Json.str(obj0(def(id).resolve("config.json")).get("pack_name")).isEmpty() ? Json.str(s.get("name"))
						: Json.str(obj0(def(id).resolve("config.json")).get("pack_name")), Icons.png(id));
				send(x, Files.readAllBytes(Files.isRegularFile(own) ? own : def(id).resolve("art-auto").resolve(PackArt.SLOTS[slot] + ".png")), "image/png");
				return true;
			}
			byte[] b;
			try (InputStream in = x.getRequestBody()) { b = in.readNBytes(MAX_ART + 1); }
			if (b.length > MAX_ART) { reply(x, 200, m("result", "refused", "message", "That file is too big for a picture.")); return true; }
			// A chosen picture is marked as one (<slot>.chosen), and the picture the pack came with is put aside the first
			// time it is replaced (art-first), so undoing a choice gives back what was there, not a made one.
			Path mark = own.resolveSibling(PackArt.SLOTS[slot] + ".chosen"), first = def(id).resolve("art-first").resolve(PackArt.SLOTS[slot] + ".png");
			if (b.length == 0) {
				if (!Files.isRegularFile(mark)) { reply(x, 200, m("result", "refused", "message",
						"This picture isn't one you chose, so there is no choice to undo.")); return true; }
				Files.delete(mark);
				boolean had = Files.isRegularFile(first);
				if (had) Files.copy(first, own, java.nio.file.StandardCopyOption.REPLACE_EXISTING); else Files.deleteIfExists(own);
				log("packs: " + Json.str(s.get("name")) + "'s " + PackArt.SLOTS[slot] + " picture for Steam went back to " + (had
						? "the one the pack came with" : "the made one"));
				reply(x, 200, m("result", "ok", "message", (had ? "Back to the picture the pack came with."
						: "Back to the one Gatekeeper makes.") + " It goes out with the next build."));
				return true;
			}
			try {
				byte[] png = PackArt.fit(b, slot);
				Files.createDirectories(own.getParent());
				if (Files.isRegularFile(own) && !Files.isRegularFile(mark)
						&& !Files.isRegularFile(first)) { Files.createDirectories(first.getParent()); Files.copy(own, first); }
				Files.write(own, png);
				Files.writeString(mark, "chosen here\n", StandardCharsets.UTF_8);
				log("packs: a " + PackArt.SLOTS[slot] + " picture for Steam was chosen for " + Json.str(s.get("name")));
				reply(x, 200, m("result", "ok", "message", "Saved, at " + PackArt.SIZE[slot][0] + " x " + PackArt.SIZE[slot][1] + ". It goes out with the next build."));
			} catch (IOException e) { reply(x, 200, m("result", "refused", "message", "That file isn't a picture this can read (PNG, JPEG, GIF and BMP are).")); }
		} else reply(x, 404, m("error", "no such call"));
		return true;
	}
}
