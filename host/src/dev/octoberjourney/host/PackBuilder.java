package dev.octoberjourney.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a server's client pack, as launchers and the sync scripts expect it: packwiz format, in two variants
 * (main: every device; deck: the same plus the mods marked for the Steam Deck). It writes what tools/build_pack.py
 * writes, file for file, so a pack built here and one built there are the same pack.
 *
 * A pack's definition is a folder:
 *   source/mods.json            {"pack": {name, author, version, minecraft, fabric}, "mods": [...]}. A mod with "url"
 *                               is fetched by players from where it is published (url, sha512, project, version,
 *                               size); one without is a file of ours, in source/files/mods. "deck": true: Steam
 *                               Deck only. "toggle": players may switch it off.
 *   source/changelog.json       the pack's notes, newest first
 *   source/files/config/        copied into the pack as they are
 *   source/files/config-defaults/   copied where a player has no such file yet
 *   source/files/mods/          the mods that are files of ours
 *   config.json                 pack_name, instance_id, pack_sources, servers, server_control
 *   art/                        cover, hero, logo, icon, wide .png for Steam (a missing one: the one in art-auto,
 *                               which Gatekeeper makes for the pack (PackArt); with neither, the program's own)
 *   server/state.json, changelog.json   what the server runs and its notes (optional)
 * What every pack carries of the program itself (the sync scripts, the launch wrappers, the launcher's scripts and
 * art, Prism Launcher's art, the Steam Deck controller layout) comes from inside this program (/pack-extras), and
 * the launcher it carries is this program's own file.
 */
public final class PackBuilder {
	private PackBuilder() {}
	static final String[] ARTS = {"cover.png", "hero.png", "logo.png", "icon.png", "wide.png"};
	static final String[] CLIENTS = {"pack-sync.ps1", "pack-sync.sh", "launch-wrapper.sh", "launch-wrapper.exe", "gatehouse-launcher.ps1", "gatehouse-launcher.sh", "plain-launch.ps1", "plain-launch.sh"};

	private static String hex(String algorithm, byte[] b) {
		try { StringBuilder sb = new StringBuilder(); for (byte x : MessageDigest.getInstance(algorithm).digest(b)) sb.append(String.format("%02x", x)); return sb.toString(); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	private static String toml(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
	static byte[] extra(String name) throws IOException {
		try (InputStream in = PackBuilder.class.getResourceAsStream("/pack-extras/" + name)) { if (in == null) throw new IOException("this program doesn't carry " + name); return in.readAllBytes(); }
	}

	// ---- JSON written the way Python's json.dumps(x, indent=1) writes it, so the files come out the same
	static String dumps(Object o, boolean sortKeys) { StringBuilder sb = new StringBuilder(); dump(sb, o, 0, sortKeys); return sb.toString(); }
	private static void dump(StringBuilder sb, Object o, int level, boolean sort) {
		if (o instanceof Map<?, ?> m) {
			if (m.isEmpty()) { sb.append("{}"); return; }
			Map<String, Object> ordered = sort ? new TreeMap<>() : new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) ordered.put(String.valueOf(e.getKey()), e.getValue());
			sb.append("{\n");
			int i = 0;
			for (Map.Entry<String, Object> e : ordered.entrySet()) { sb.append(" ".repeat(level + 1)); quote(sb, e.getKey()); sb.append(": "); dump(sb, e.getValue(), level + 1, sort); sb.append(++i < ordered.size() ? ",\n" : "\n"); }
			sb.append(" ".repeat(level)).append("}");
		} else if (o instanceof List<?> l) {
			if (l.isEmpty()) { sb.append("[]"); return; }
			sb.append("[\n");
			for (int i = 0; i < l.size(); i++) { sb.append(" ".repeat(level + 1)); dump(sb, l.get(i), level + 1, sort); sb.append(i + 1 < l.size() ? ",\n" : "\n"); }
			sb.append(" ".repeat(level)).append("]");
		} else if (o instanceof String s) quote(sb, s);
		else if (o instanceof Number n) { double d = n.doubleValue(); if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(d); }
		else if (o instanceof Boolean b) sb.append(b ? "true" : "false");
		else if (o == null) sb.append("null");
		else quote(sb, o.toString());
	}
	private static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> { if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
			}
		}
		sb.append('"');
	}

	private record Entry(String rel, String sha256, String kind) {}   // kind: "" a file, "meta" a pointer to a mod, "preserve"

	// ---- JSON read with its numbers kept as numbers and its keys in their order (this program's own reader hands
	// numbers back as text, which is right for its settings and wrong for a file that is written out again)
	private static final class Reader {
		final String s; int i;
		Reader(String s) { this.s = s; }
		void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
		Object value() {
			ws();
			if (i >= s.length()) throw new IllegalArgumentException("it ends too soon");
			char c = s.charAt(i);
			if (c == '{') {
				Map<String, Object> m = new LinkedHashMap<>();
				i++; ws();
				if (s.charAt(i) == '}') { i++; return m; }
				while (true) { ws(); String k = string(); ws(); if (s.charAt(i++) != ':') throw new IllegalArgumentException("a colon is missing near " + i); m.put(k, value()); ws(); char d = s.charAt(i++); if (d == '}') return m; if (d != ',') throw new IllegalArgumentException("a comma is missing near " + i); }
			}
			if (c == '[') {
				List<Object> l = new ArrayList<>();
				i++; ws();
				if (s.charAt(i) == ']') { i++; return l; }
				while (true) { l.add(value()); ws(); char d = s.charAt(i++); if (d == ']') return l; if (d != ',') throw new IllegalArgumentException("a comma is missing near " + i); }
			}
			if (c == '"') return string();
			if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
			if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
			if (s.startsWith("null", i)) { i += 4; return null; }
			int from = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
			String n = s.substring(from, i);
			if (n.isEmpty()) throw new IllegalArgumentException("something unexpected near " + i);
			return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
		}
		String string() {
			if (s.charAt(i++) != '"') throw new IllegalArgumentException("a quote is missing near " + i);
			StringBuilder sb = new StringBuilder();
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') return sb.toString();
				if (c != '\\') { sb.append(c); continue; }
				char e = s.charAt(i++);
				switch (e) {
					case 'n' -> sb.append('\n'); case 'r' -> sb.append('\r'); case 't' -> sb.append('\t'); case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
					case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
					default -> sb.append(e);
				}
			}
		}
	}
	static Object parse(Path f) throws IOException {
		try { return new Reader(Files.readString(f, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "")).value(); }
		catch (RuntimeException e) { throw new IOException(f.getFileName() + " isn't readable: " + e.getMessage()); }
	}
	@SuppressWarnings("unchecked") private static Map<String, Object> json(Path f) throws IOException { Object o = parse(f); if (o instanceof Map<?, ?> m) return (Map<String, Object>) m; throw new IOException(f.getFileName() + " isn't what was expected"); }
	@SuppressWarnings("unchecked") private static List<Object> jsonList(Path f) throws IOException { Object o = parse(f); if (o instanceof List<?> l) return (List<Object>) l; throw new IOException(f.getFileName() + " isn't what was expected"); }

	/** Builds both variants of the pack defined in def into out/main and out/deck. program: the launcher file the pack
	 *  carries; gatehouse: its version. Returns a line for each variant saying what is in it. */
	static List<String> build(Path def, Path out, byte[] program, String gatehouse) throws IOException {
		Map<String, Object> src = json(def.resolve("source/mods.json"));
		List<String> said = new ArrayList<>();
		for (String variant : new String[] {"main", "deck"}) said.add(one(def, out.resolve(variant), variant, Json.map(src.get("pack")), Json.list(src.get("mods")), program, gatehouse));
		return said;
	}

	static void wipe(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (java.util.stream.Stream<Path> s = Files.walk(dir)) { for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
	}
	private static byte[] put(Path out, String rel, byte[] data) throws IOException { Path p = out.resolve(rel); Files.createDirectories(p.getParent()); Files.write(p, data); return data; }
	private static byte[] put(Path out, String rel, String text) throws IOException { return put(out, rel, text.getBytes(StandardCharsets.UTF_8)); }
	private static void copyTree(Path from, Path out, String under, String kind, List<Entry> entries) throws IOException {
		if (!Files.isDirectory(from)) return;
		List<Path> files;
		try (java.util.stream.Stream<Path> s = Files.walk(from)) { files = s.filter(Files::isRegularFile).toList(); }
		for (Path p : files) { String rel = under + "/" + from.relativize(p).toString().replace('\\', '/'); entries.add(new Entry(rel, hex("SHA-256", put(out, rel, Files.readAllBytes(p))), kind)); }
	}

	private static String one(Path def, Path out, String variant, Map<String, Object> meta, List<Object> mods, byte[] program, String gatehouse) throws IOException {
		wipe(out);
		List<Entry> entries = new ArrayList<>();
		List<Map<String, Object>> mine = new ArrayList<>();
		for (Object o : mods) { Map<String, Object> m = Json.map(o); if (!ServerTools.yes(m.get("deck")) || variant.equals("deck")) mine.add(m); }
		Map<String, Object> names = new LinkedHashMap<>(), sizes = new LinkedHashMap<>();
		List<Object> toggles = new ArrayList<>();
		for (Map<String, Object> m : mine) {
			String rel;
			if (m.containsKey("url")) {
				rel = "mods/" + Json.str(m.get("slug")) + ".pw.toml";
				String text = "name = " + toml(Json.str(m.get("title"))) + "\nfilename = " + toml(Json.str(m.get("filename"))) + "\nside = \"client\"\n\n[download]\nurl = " + toml(Json.str(m.get("url")))
						+ "\nhash-format = \"sha512\"\nhash = " + toml(Json.str(m.get("sha512"))) + "\n\n[update.modrinth]\nmod-id = " + toml(Json.str(m.get("project"))) + "\nversion = " + toml(Json.str(m.get("version"))) + "\n";
				entries.add(new Entry(rel, hex("SHA-256", put(out, rel, text)), "meta"));
				if (Json.str(m.get("size")).isEmpty()) throw new IOException(rel + ": no download size is recorded for it");
				sizes.put(rel, m.get("size"));
			} else {
				Path jar = def.resolve("source/files/mods").resolve(Json.str(m.get("filename")));
				if (!Files.isRegularFile(jar)) throw new IOException(Json.str(m.get("filename")) + " isn't in the pack's own mods");
				byte[] data = Files.readAllBytes(jar);
				if (!hex("SHA-512", data).equals(Json.str(m.get("sha512")))) throw new IOException(Json.str(m.get("filename")) + " does not match its recorded SHA-512");
				rel = "mods/" + Json.str(m.get("filename"));
				entries.add(new Entry(rel, hex("SHA-256", put(out, rel, data)), ""));
			}
			names.put(rel, Json.str(m.get("title")));
			if (m.containsKey("toggle")) {
				Map<String, Object> tg = Json.map(m.get("toggle"));
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("key", Json.str(m.get("slug")).isEmpty() ? Json.str(tg.get("modid")) : Json.str(m.get("slug")));
				t.put("file", rel); t.put("modid", Json.str(tg.get("modid")));
				t.put("name", tg.containsKey("name") ? Json.str(tg.get("name")) : Json.str(m.get("title")));
				t.put("description", Json.str(tg.get("description")));
				t.put("default", !tg.containsKey("default") || ServerTools.yes(tg.get("default")));
				t.put("needs", tg.containsKey("needs") ? Json.list(tg.get("needs")) : new ArrayList<>());
				toggles.add(t);
			}
		}
		copyTree(def.resolve("source/files/config"), out, "config", "", entries);
		copyTree(def.resolve("source/files/config-defaults"), out, "config", "preserve", entries);
		// Steam: the pack's own art (or the program's, where the pack has none), Prism Launcher's, the launcher's, and the Deck's controller layout
		for (String n : ARTS) { Path own = def.resolve("art").resolve(n), auto = def.resolve("art-auto").resolve(n); String rel = "steam/art/" + n; entries.add(new Entry(rel, hex("SHA-256", put(out, rel, Files.isRegularFile(own) ? Files.readAllBytes(own) : Files.isRegularFile(auto) ? Files.readAllBytes(auto) : extra("art/" + n))), "")); }
		for (String set : new String[] {"art-prism", "art-launcher"}) for (String n : ARTS) { String rel = "steam/" + set + "/" + n; entries.add(new Entry(rel, hex("SHA-256", put(out, rel, extra(set + "/" + n))), "")); }
		entries.add(new Entry("steam/controller_neptune.vdf", hex("SHA-256", put(out, "steam/controller_neptune.vdf", extra("steam/controller_neptune.vdf"))), ""));
		Map<String, Object> config = json(def.resolve("config.json"));
		List<Object> servers = new ArrayList<>();
		for (Object o : Json.list(config.get("servers"))) { Map<String, Object> s = new LinkedHashMap<>(); s.put("name", Json.map(o).get("name")); s.put("address", Json.map(o).get("address")); servers.add(s); }
		entries.add(new Entry("extras/servers.json", hex("SHA-256", put(out, "extras/servers.json", dumps(servers, false) + "\n")), ""));
		entries.add(new Entry("extras/mod-names.json", hex("SHA-256", put(out, "extras/mod-names.json", dumps(names, false) + "\n")), ""));
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("name", config.get("pack_name")); profile.put("instance_id", config.get("instance_id")); profile.put("pack_sources", config.containsKey("pack_sources") ? config.get("pack_sources") : new ArrayList<>());
		entries.add(new Entry("extras/profile.json", hex("SHA-256", put(out, "extras/profile.json", dumps(profile, false) + "\n")), ""));
		Map<String, Object> control = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : Json.map(config.get("server_control")).entrySet()) if (!e.getKey().startsWith("_")) control.put(e.getKey(), e.getValue());
		entries.add(new Entry("extras/server-control.json", hex("SHA-256", put(out, "extras/server-control.json", dumps(control, false) + "\n")), ""));
		entries.add(new Entry("extras/mod-toggles.json", hex("SHA-256", put(out, "extras/mod-toggles.json", dumps(toggles, false) + "\n")), ""));
		// what the server runs, and its notes
		Map<String, Object> state = Files.isRegularFile(def.resolve("server/state.json")) ? json(def.resolve("server/state.json")) : new LinkedHashMap<>();
		List<Object> serverLog = Files.isRegularFile(def.resolve("server/state.json")) && Files.isRegularFile(def.resolve("server/changelog.json")) ? jsonList(def.resolve("server/changelog.json")) : new ArrayList<>();
		entries.add(new Entry("extras/server-state.json", hex("SHA-256", put(out, "extras/server-state.json", dumps(state, true) + "\n")), ""));
		StringBuilder sl = new StringBuilder();
		for (Object o : serverLog) { Map<String, Object> e = Json.map(o); sl.append("== ").append(Json.str(e.get("id"))).append(" | ").append(Json.str(e.get("date"))).append("\n"); for (Object n : Json.list(e.get("notes"))) sl.append("- ").append(Json.str(n)).append("\n"); }
		entries.add(new Entry("extras/server-changelog.txt", hex("SHA-256", put(out, "extras/server-changelog.txt", sl.toString())), ""));
		// the pack's own notes: its first entry has to be this version, so no version goes out without notes
		List<Object> log = jsonList(def.resolve("source/changelog.json"));
		if (log.isEmpty() || !Json.str(Json.map(log.get(0)).get("version")).equals(Json.str(meta.get("version")))) throw new IOException("the pack's notes need an entry for " + Json.str(meta.get("version")) + " at the top");
		List<String> lines = new ArrayList<>();
		for (Object o : log) { Map<String, Object> v = Json.map(o); lines.add("== " + Json.str(v.get("version")) + " | " + Json.str(v.get("date"))); for (Object n : Json.list(v.get("notes"))) lines.add("- " + Json.str(n)); }
		entries.add(new Entry("extras/changelog.txt", hex("SHA-256", put(out, "extras/changelog.txt", String.join("\n", lines) + "\n")), ""));
		entries.add(new Entry("extras/launch-popup.jar", hex("SHA-256", put(out, "extras/launch-popup.jar", program)), ""));
		for (String n : CLIENTS) entries.add(new Entry("extras/" + n, hex("SHA-256", put(out, "extras/" + n, extra("clients/" + n))), ""));
		entries.sort(java.util.Comparator.comparing(Entry::rel));
		StringBuilder index = new StringBuilder("hash-format = \"sha256\"\n");
		Map<String, Object> files = new LinkedHashMap<>();
		for (Entry e : entries) {
			index.append("\n[[files]]\nfile = ").append(toml(e.rel())).append("\nhash = ").append(toml(e.sha256())).append("\n").append(e.kind().equals("preserve") ? "preserve = true\n" : e.kind().equals("meta") ? "metafile = true\n" : "");
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("hash", e.sha256()); f.put("size", e.kind().equals("meta") ? sizes.get(e.rel()) : (Object) Files.size(out.resolve(e.rel())));
			if (e.kind().equals("preserve")) f.put("preserve", true);
			files.put(e.rel(), f);
		}
		byte[] indexData = put(out, "index.toml", index.toString());
		Map<String, Object> us = new LinkedHashMap<>();
		us.put("version", meta.get("version")); us.put("gatehouse", gatehouse); us.put("files", files);
		put(out, "update-sizes.json", dumps(us, false) + "\n");
		String name = Json.str(meta.get("name")) + (variant.equals("deck") ? " (Steam Deck)" : "");
		put(out, "pack.toml", "name = " + toml(name) + "\nauthor = " + toml(Json.str(meta.get("author"))) + "\nversion = " + toml(Json.str(meta.get("version"))) + "\npack-format = \"packwiz:1.1.0\"\n\n[index]\nfile = \"index.toml\"\nhash-format = \"sha256\"\nhash = "
				+ toml(hex("SHA-256", indexData)) + "\n\n[versions]\nminecraft = " + toml(Json.str(meta.get("minecraft"))) + "\nfabric = " + toml(Json.str(meta.get("fabric"))) + "\n");
		long modsN = entries.stream().filter(e -> e.rel().startsWith("mods/")).count(), configN = entries.stream().filter(e -> e.rel().startsWith("config/")).count(), steamN = entries.stream().filter(e -> e.rel().startsWith("steam/")).count();
		return variant + ": " + modsN + " mods, " + configN + " config file(s), " + steamN + " Steam file(s)";
	}

	/** Testing and the build tools: --build-pack <definition folder> <where to write it> [<the launcher file to carry>]. */
	public static void main(String[] a) throws Exception {
		Path program = a.length > 3 ? Path.of(a[3]) : Path.of(PackBuilder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		for (String line : build(Path.of(a[1]), Path.of(a[2]), Files.readAllBytes(program), Json.str(HostHelper.notesInside("gatehouse").get("version")))) System.out.println(line);
	}
}
