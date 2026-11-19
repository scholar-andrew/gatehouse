package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.gatehouse.look.Quiet;

/**
 * Minecraft: Java Edition, as a Game (R34). Everything here was in DirectHost, HostHelper, ServerTools, ServerWatch and
 * LocalLink before games existed, and does exactly what it did there.
 */
final class Minecraft implements Game {
	@Override public String id() { return "minecraft"; }
	@Override public String name() { return "Minecraft"; }

	// ------------------------------------------------------------------ its server's folder and program

	/** R91: the Minecraft and Fabric loader versions in the name of Fabric's server launcher in a folder
	 *  (fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar), or {"", ""}: all a new, never-started server has. */
	static String[] fabricLauncher(Path d) {
		Pattern named = Pattern.compile("(?i)fabric-server-mc\\.([\\w.\\-]{1,32}?)-loader\\.([\\w.+\\-]{1,32}?)-launcher\\.[\\w.\\-]+\\.jar");
		try (java.util.stream.Stream<Path> s = Files.list(d)) {
			return s.map(p -> named.matcher(p.getFileName().toString())).filter(Matcher::matches).map(m -> new String[] {m.group(1), m.group(2)})
					.sorted((a, b) -> a[0].compareTo(b[0])).reduce((a, b) -> b).orElse(new String[] {"", ""});
		} catch (IOException | RuntimeException e) { return new String[] {"", ""}; }
	}

	/** The server jar in a folder, or null. */
	static Path serverJar(Path d) {
		List<Path> jars = new ArrayList<>();
		try (java.util.stream.Stream<Path> s = Files.list(d)) { s.filter(p -> Files.isRegularFile(p)
				&& p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).sorted().forEach(jars::add); }
		catch (IOException | RuntimeException e) { return null; }
		for (String start : new String[] {"fabric-server", "quilt-server"}) for (Path p
				: jars) if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(start)) return p;
		for (Path p : jars) if (p.getFileName().toString().equalsIgnoreCase("server.jar")) return p;
		for (String part : new String[] {"paper", "purpur", "spigot", "forge", "server"}) for (Path p
				: jars) if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(part)) return p;
		return jars.size() == 1 ? jars.get(0) : null;
	}
	@Override public boolean looksLikeServer(Path d) {
		return serverJar(d) != null || Files.isRegularFile(d.resolve("run.sh")) || Files.isRegularFile(d.resolve("server.properties"));
	}
	@Override public boolean termsAccepted(Path d) {
		for (String l : read(d.resolve("eula.txt")).split("\\R")) if (l.replace(" ", "").equalsIgnoreCase("eula=true")) return true;
		return false;
	}
	/** Forge's or NeoForge's argument file for this system (libraries/.../win_args.txt or unix_args.txt), or null. */
	static Path modArgs(Path d) {
		Path lib = d.resolve("libraries");
		if (!Files.isDirectory(lib)) return null;
		String want = WIN ? "win_args.txt" : "unix_args.txt";
		try (java.util.stream.Stream<Path> s = Files.walk(lib,
				6)) { return s.filter(p -> p.getFileName().toString().equals(want)).sorted(java.util.Comparator.reverseOrder()).findFirst().orElse(null); }
		catch (IOException | RuntimeException e) { return null; }
	}
	@Override public List<String> launch(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		String custom = Json.str(s.get("start"));
		if (!custom.isEmpty()) return WIN ? List.of("cmd", "/c", custom) : List.of("sh", "-c", custom);
		String java = Json.str(s.get("java")), memory = Json.str(s.get("memory"));
		if (java.isEmpty()) java = setting("server_java", ProcessHandle.current().info().command().orElse("java"));
		if (memory.isEmpty()) memory = setting("server_memory", "4G");
		// (what Forge's and NeoForge's installers make: a list of arguments for Java, which run.sh / run.bat hand it)
		Path args = modArgs(d);
		if (args != null) {
			List<String> cmd = new ArrayList<>(List.of(java, "-Xmx" + memory));
			if (Files.isRegularFile(d.resolve("user_jvm_args.txt"))) cmd.add("@user_jvm_args.txt");
			cmd.add("@" + d.relativize(args).toString().replace('\\', '/'));
			cmd.add("nogui");
			return cmd;
		}
		if (!WIN && Files.isRegularFile(d.resolve("run.sh"))) return List.of("sh", "run.sh", "nogui");
		Path jar = serverJar(d);
		if (jar == null) return null;
		return List.of(java, "-Xmx" + memory, "-jar", jar.getFileName().toString(), "nogui");
	}
	@Override public String blocker(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		if (!Files.isDirectory(d)) return "its folder is gone (" + d + ")";
		if (launch(s) == null) return "there is no server program in " + d + " (New server in Gatekeeper makes one; or put the server's jar there yourself)";
		if (!termsAccepted(d)) return "Minecraft's EULA isn't accepted for it yet: read it (aka.ms/MinecraftEULA), then put the line eula=true in " + d.resolve("eula.txt") + " (or tick the box in Manage, Settings)";
		return "";
	}

	// ------------------------------------------------------------------ its console and its log

	@Override public boolean readyLine(String line) { return line.contains("Done (") && line.contains("For help"); }
	@Override public String stopCommand() { return "stop"; }
	@Override public Path log(Path d) { return d.resolve("logs").resolve("latest.log"); }
	private static final Pattern JOINED = Pattern.compile("^(?:\\[([0-9:]+)\\])?.*?: (\\w{1,16})\\[/([0-9a-fA-F.:]+):\\d+\\] logged in"),
			LEFT = Pattern.compile(": (\\w{1,16}) (left the game|lost connection)");
	@Override public String[] joined(String line) {
		Matcher m = JOINED.matcher(line);
		return m.find() ? new String[] {m.group(2), m.group(3), m.group(1) == null ? "" : m.group(1)} : null;
	}
	@Override public String left(String line) { Matcher m = LEFT.matcher(line); return m.find() ? m.group(1) : null; }
	@Override public boolean stoppingLine(String line) { return line.contains("Stopping server") || line.contains("Stopping the server"); }
	@Override public int lagIn(String t) {
		int n = 0, i = 0;
		while ((i = t.indexOf("Can't keep up!", i)) >= 0) { n++; i++; }
		return n;
	}
	/** Modded: it has mods (a mods folder with jars in it) or a mod loader's files (Fabric, Forge, NeoForge, Quilt). The
	 *  version is the one the server last said it started as (its own log), else the one its versions folder is named for. */
	@Override public String[] facts(Path dir) {
		boolean modded = false;
		String version = "";
		try {
			Path mods = dir.resolve("mods");
			if (Files.isDirectory(mods)) try (java.util.stream.Stream<Path> s = Files.list(mods)) { modded = s.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")); }
			if (!modded && Files.isDirectory(dir)) try (java.util.stream.Stream<Path> s = Files.list(dir)) { modded = s.anyMatch(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches("(fabric|forge|neoforge|quilt).*\\.(jar|properties)")); }
			if (!modded) modded = Files.isDirectory(dir.resolve("libraries/net/fabricmc"))
					|| Files.isDirectory(dir.resolve("libraries/net/minecraftforge")) || Files.isDirectory(dir.resolve("libraries/net/neoforged"));
			Matcher m = Pattern.compile("Starting minecraft server version ([\\w.\\-]+)").matcher(read(log(dir)));
			while (m.find()) version = m.group(1);
			if (version.isEmpty()
					&& Files.isDirectory(dir.resolve("versions"))) try (java.util.stream.Stream<Path> s = Files.list(dir.resolve("versions"))) { version = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().reduce((a, b) -> b).orElse(""); }
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		if (version.isEmpty()) version = fabricLauncher(dir)[0];   // (R91: a Fabric server that hasn't started yet)
		return new String[] {modded ? "modded" : "vanilla", version};
	}
	/** A running server holds a lock on session.lock in its world folder. */
	@Override public boolean worldInUse(Path d) {
		Path lock = d.resolve(worldFolder(d)).resolve("session.lock");
		if (!Files.isRegularFile(lock)) return false;
		try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock,
				java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock fl = ch.tryLock()) { return fl == null; }
		catch (IOException | java.nio.channels.OverlappingFileLockException ex) { return true; }
	}

	// ------------------------------------------------------------------ its settings: server.properties

	@Override public Map<String, String> settings(Path d) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String l : read(d.resolve("server.properties")).split("\\R")) { int eq = l.indexOf('='); if (eq > 0
				&& !l.startsWith("#")) out.put(l.substring(0, eq).trim(), l.substring(eq + 1)); }
		return out;
	}
	/** Lines it doesn't have yet are added; everything else in it is left as it is. */
	@Override public void setSettings(Path d, Map<String, String> set) throws IOException {
		Path f = d.resolve("server.properties");
		String t = read(f), nl = t.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>(t.isEmpty() ? List.of() : List.of(t.split("\\R")));
		for (Map.Entry<String, String> e : set.entrySet()) {
			boolean had = false;
			for (int i = 0; i < lines.size(); i++) if (lines.get(i).replaceAll("\\s*=.*", "").trim().equals(e.getKey())
					&& !lines.get(i).startsWith("#")) { lines.set(i, e.getKey() + "=" + e.getValue()); had = true; }
			if (!had) lines.add(e.getKey() + "=" + e.getValue());
		}
		Files.writeString(f, String.join(nl, lines) + nl, StandardCharsets.UTF_8);
	}
	@Override public String port(Path d) {
		String port = "";
		try { for (String l : read(d.resolve("server.properties")).split("\\R")) if (l.startsWith("server-port=")
				&& l.substring(12).trim().matches("\\d+")) port = l.substring(12).trim(); }
		catch (RuntimeException ignored) { Quiet.seen(ignored); }
		return port;
	}

	// ------------------------------------------------------------------ who may join: the whitelist

	@Override public boolean validPlayer(String name) { return name != null && name.matches("\\w{1,16}"); }
	/** whitelist.json in its folder: uuid and name each. */
	@Override public List<Object> allowList(Path d) { return Json.list(Json.parse(read(d.resolve("whitelist.json")))); }
	@Override public Boolean allowListInForce(Path d) {
		String props = read(d.resolve("server.properties"));
		return props.isEmpty() ? null : props.replace(" ", "").contains("white-list=true");
	}
	private boolean listed(String name) {
		for (Object o : allowList(serverDir())) if (name.equalsIgnoreCase(Json.str(Json.map(o).get("name")))) return true;
		return false;
	}
	/** While the server runs, the server does it itself (the console commands "whitelist add" / "whitelist remove": it
	 *  finds the account and saves its file). While it's off, the file is changed here; adding then needs the player's
	 *  id, which the server's own record of accounts it has seen (usercache.json) has for anyone who has joined or tried to. */
	@Override public String[] setAllowed(String name, boolean on) {
		if (!validPlayer(name)) return new String[] {"refused", "That isn't a Minecraft name"};
		if (listed(name) == on) return new String[] {"ok", name + (on ? " is already on the whitelist" : " isn't on the whitelist")};
		try {
			if (minecraftRunning()) {
				String[] r = sh().command("whitelist " + (on ? "add " : "remove ") + name);
				if (!r[0].equals("ok")) return r;
				for (int i = 0; i < 12 && listed(name) != on; i++) Thread.sleep(300);
				return listed(name) == on ? new String[] {"ok", (on ? "Added " + name + " to" : "Removed " + name + " from") + " the whitelist of " + serverName()}
						: new String[] {"failed", "The server was asked, but its whitelist hasn't changed" + (on ? " (it may not know an account called " + name + ")" : "")};
			}
			List<Object> wl = allowList(serverDir());
			if (on) {
				String uuid = "", proper = name;
				for (Object o : Json.list(Json.parse(read(serverDir().resolve("usercache.json"))))) {
					if (name.equalsIgnoreCase(Json.str(Json.map(o).get("name")))) { uuid = Json.str(Json.map(o).get("uuid")); proper = Json.str(Json.map(o).get("name")); }
				}
				if (uuid.isEmpty()) return new String[] {"refused", "The server is off, and it hasn't seen " + name
						+ " before, so their account id isn't known here: start the server and tick them again"};
				wl.add(m("uuid", uuid, "name", proper));
			} else wl.removeIf(o -> name.equalsIgnoreCase(Json.str(Json.map(o).get("name"))));
			Files.writeString(serverDir().resolve("whitelist.json"), Json.write(wl), StandardCharsets.UTF_8);
			return new String[] {"ok", (on ? "Added " + name + " to" : "Removed " + name + " from") + " the whitelist of " + serverName()
					+ " (the server is off: it reads it when it starts)"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't change the whitelist: " + e.getMessage()}; }
	}
	/** With the server off and the player never seen there, the account id the launcher gave (from Prism's own account
	 *  list) is what the whitelist file needs. */
	@Override public String[] allowWithId(String player, String uuid) {
		String[] r = setAllowed(player, true);
		if (r[0].equals("ok") || minecraftRunning() || !uuid.matches("[0-9a-f]{32}")) return r;
		try {
			List<Object> wl = new ArrayList<>(allowList(serverDir()));
			wl.add(m("uuid", uuid.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"), "name", player));
			Files.writeString(serverDir().resolve("whitelist.json"), Json.write(wl), StandardCharsets.UTF_8);
			return new String[] {"ok", "added"};
		} catch (IOException | RuntimeException e) { return new String[] {"failed", "its whitelist file couldn't be written: " + e.getMessage()}; }
	}
	/** A server that's running is told in its console ("whitelist on": it takes effect at once and the server saves the
	 *  setting itself); one that's off has white-list=true written into its server.properties. */
	@Override public String[] allowListOn() {
		Path props = serverDir().resolve("server.properties");
		String t = read(props), name = serverName();
		if (t.isEmpty() || t.replace(" ", "").contains("white-list=true")) return null;
		if (minecraftRunning()) {
			String[] r = sh().command("whitelist on");
			if (r[0].equals("ok")) {
				for (int i = 0; i < 12 && !read(props).replace(" ",
						"").contains("white-list=true"); i++) try { Thread.sleep(300); } catch (InterruptedException ignored) { Quiet.seen(ignored); }
				return new String[] {"done", name + " (told in its console; in effect now)"};
			}
			return new String[] {"not", name + " is running and couldn't be told: " + r[1]};
		}
		String nl = t.contains("\r\n") ? "\r\n" : "\n";
		String out = t.matches("(?s).*(^|\\R)\\s*white-list\\s*=.*") ? t.replaceAll("(?m)^\\s*white-list\\s*=.*$",
				"white-list=true") : t + (t.endsWith("\n") ? "" : nl) + "white-list=true" + nl;
		try { Files.writeString(props, out, StandardCharsets.UTF_8); return new String[] {"done", name + " (in its settings file; in effect when it next starts)"}; }
		catch (IOException e) { return new String[] {"not", name + ": couldn't write its server.properties (" + e.getMessage() + ")"}; }
	}

	// ------------------------------------------------------------------ backups

	@Override public String worldFolder(Path d) {
		String level = settings(d).getOrDefault("level-name", "world").trim();
		return level.isEmpty() ? "world" : level;
	}
	@Override public List<String> beforeBackup() { return List.of("save-off", "save-all flush"); }
	@Override public boolean savedLine(String line) { return line.contains("Saved the game"); }
	@Override public List<String> afterBackup() { return List.of("save-on"); }
}
