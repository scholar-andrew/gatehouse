package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;

/**
 * What a server program's own manager does for its servers, for the servers Gatekeeper runs itself (DirectHost):
 * making a server from nothing, its settings, its console, its server.properties, backups and scheduled tasks.
 * MC Server Soft does all of this for its own servers, so where Gatekeeper works through MCSS every call here is
 * refused with where to do it instead.
 *
 * Making a server fetches its program from where its makers publish it, and only when the owner asks for it:
 *   vanilla  Mojang's version list (piston-meta.mojang.com), checked against the SHA-1 it gives
 *   paper    PaperMC's list (fill.papermc.io), checked against the SHA-256 it gives
 *   fabric   Fabric's own server launcher (meta.fabricmc.net); Fabric publishes no checksum for it
 *   forge    an installer the owner downloaded (Forge or NeoForge), run with --installServer in the new folder
 *   file     a server jar the owner already has, copied in
 * Minecraft's EULA is the owner's to agree to: eula.txt is written only when they ticked the box that says so.
 *
 * Everything about a server is kept in its entry in host.json: memory, java, autostart, keep_online ("crash": start
 * it again when it ends unasked), backup_what / backup_keep / backup_dir, and tasks.
 */
final class ServerTools {
	static final String HERE_MCSS = "MC Server Soft does this for its servers here: use MCSS's own window";
	static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	static synchronized void saveCfg() throws IOException { Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8); }
	static boolean yes(Object o) { return Boolean.TRUE.equals(o) || "true".equals(Json.str(o)); }
	private static String id() { return Json.str(server().get("id")); }

	// ------------------------------------------------------------------ fetching

	static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(8000); c.setReadTimeout(30000);
		c.setRequestProperty("User-Agent", "Gatekeeper/" + ownVersion() + " (Minecraft server setup)");
		if (c.getResponseCode() != 200) throw new IOException(new URL(url).getHost() + " answered " + c.getResponseCode());
		return c;
	}
	static Object json(String url) throws IOException {
		try (InputStream in = open(url).getInputStream()) { return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
	}
	/** Fetches a file to disk, saying how far it is, and returns its checksum by that algorithm (SHA-1 or SHA-256). */
	static String fetchTo(String url, Path to, String algorithm) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			HttpURLConnection c = open(url);
			long all = c.getContentLengthLong(), got = 0;
			try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(to)) {
				byte[] buf = new byte[65536];
				for (int n; (n = in.read(buf)) > 0; ) {
					out.write(buf, 0, n); md.update(buf, 0, n); got += n;
					step("Fetching the server's program: " + got / 1048576 + (all > 0 ? " of " + all / 1048576 : "") + " MB");
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
	}

	static String mojang() { return setting("source_mojang", "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"); }
	static String fabric() { return setting("source_fabric", "https://meta.fabricmc.net/v2/versions"); }
	static String paper() { return setting("source_paper", "https://fill.papermc.io/v3/projects/paper"); }

	/** The Minecraft versions a kind of server can be made for, newest first. */
	static List<Object> versions(String kind) throws IOException {
		List<Object> out = new ArrayList<>();
		if (kind.equals("vanilla")) {
			for (Object o : Json.list(Json.map(json(mojang())).get("versions"))) if (Json.str(Json.map(o).get("type")).equals("release")) out.add(Json.str(Json.map(o).get("id")));
		} else if (kind.equals("fabric")) {
			for (Object o : Json.list(json(fabric() + "/game"))) if (yes(Json.map(o).get("stable"))) out.add(Json.str(Json.map(o).get("version")));
		} else if (kind.equals("paper")) {
			for (Object group : Json.map(Json.map(json(paper())).get("versions")).values()) for (Object v : Json.list(group)) if (!Json.str(v).contains("-")) out.add(Json.str(v));
		}
		return out;
	}
	/** The Java a Minecraft version needs (its major number, from Mojang's list), or 0 when that can't be told. */
	static int javaNeeded(String version) {
		try {
			for (Object o : Json.list(Json.map(json(mojang())).get("versions"))) if (Json.str(Json.map(o).get("id")).equals(version))
				return (int) Double.parseDouble(Json.str(Json.map(Json.map(json(Json.str(Json.map(o).get("url")))).get("javaVersion")).get("majorVersion")));
		} catch (IOException | RuntimeException ignored) {}
		return 0;
	}

	// ------------------------------------------------------------------ the Javas on this machine

	/** A Java's major version, from the "release" file in its folder (nothing is run), or 0. */
	static int javaMajor(Path exe) {
		try {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?").matcher(read(exe.toAbsolutePath().getParent().getParent().resolve("release")));
			if (m.find()) return m.group(1).equals("1") && m.group(2) != null ? Integer.parseInt(m.group(2)) : Integer.parseInt(m.group(1));
		} catch (RuntimeException ignored) {}
		return 0;
	}
	/** The Javas found in the usual places: each {path, version}. Gatekeeper's own is first. */
	static List<Object> javas() {
		String name = WIN ? "java.exe" : "java", home = System.getProperty("user.home", "");
		java.util.LinkedHashSet<Path> found = new java.util.LinkedHashSet<>();
		ProcessHandle.current().info().command().ifPresent(c -> found.add(Path.of(c.replaceAll("(?i)javaw(\\.exe)?$", name))));
		String jh = System.getenv("JAVA_HOME");
		if (jh != null && !jh.isEmpty()) found.add(Path.of(jh, "bin", name));
		List<String> roots = new ArrayList<>();
		if (WIN) for (String pf : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) { if (pf != null) for (String v : new String[] {"Eclipse Adoptium", "Java", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto"}) roots.add(pf + "\\" + v); }
		else { roots.add("/usr/lib/jvm"); roots.add("/usr/lib64/jvm"); roots.add(home + "/.local/share/PrismLauncher/java"); roots.add(home + "/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java"); roots.add(home + "/.sdkman/candidates/java"); }
		for (String r : roots) if (Files.isDirectory(Path.of(r))) try (java.util.stream.Stream<Path> s = Files.walk(Path.of(r), 4)) {
			s.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(name) && p.getParent().getFileName().toString().equals("bin") && Files.isRegularFile(p)).sorted().forEach(found::add);
		} catch (IOException | RuntimeException ignored) {}
		List<Object> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (Path p : found) {
			String real;
			try { real = p.toRealPath().toString(); } catch (IOException e) { continue; }
			if (seen.add(real.toLowerCase(Locale.ROOT)) && javaMajor(Path.of(real)) > 0) out.add(m("path", real, "version", javaMajor(Path.of(real))));
		}
		return out;
	}

	// ------------------------------------------------------------------ making a server

	/** The one server being made, or the last one: {state: idle | working | done | failed, step, message, id}. */
	static final Map<String, Object> making = new ConcurrentHashMap<>(Map.of("state", "idle", "step", "", "message", "", "id", ""));
	static void step(String s) { making.put("step", s); }

	static String memoryOk(String memory) { return memory.matches("(?i)\\d{1,6}[MG]") ? memory.toUpperCase(Locale.ROOT) : ""; }

	/** Starts making a server (it carries on in the background; "making" says how it goes). */
	static synchronized String[] create(Map<String, Object> b) {
		if (!builtin()) return new String[] {"refused", HERE_MCSS};
		if (making.get("state").equals("working")) return new String[] {"refused", "Gatekeeper is still making the last server"};
		String name = clean(Json.str(b.get("name")), 40), kind = Json.str(b.get("kind")), version = Json.str(b.get("version")).trim(), file = Json.str(b.get("file")).trim();
		String memory = memoryOk(Json.str(b.get("memory")).trim().isEmpty() ? "4G" : Json.str(b.get("memory")).trim()), port = Json.str(b.get("port")).trim().replaceAll("\\.0$", ""), java = Json.str(b.get("java")).trim();
		if (name.isEmpty()) return new String[] {"refused", "Give the server a name."};
		if (!List.of("vanilla", "fabric", "paper", "forge", "file").contains(kind)) return new String[] {"refused", "Choose what kind of server to make."};
		if (!yes(b.get("eula"))) return new String[] {"refused", "A Minecraft server can't be run without agreeing to Minecraft's EULA (aka.ms/MinecraftEULA). Read it and tick the box; Gatekeeper doesn't agree to it for you."};
		boolean fetched = !kind.equals("forge") && !kind.equals("file");
		if (fetched && version.isEmpty()) return new String[] {"refused", "Choose a Minecraft version."};
		if (!fetched && !Files.isRegularFile(Path.of(file))) return new String[] {"refused", kind.equals("forge") ? "Choose the installer you downloaded from Forge or NeoForge (a .jar file)." : "Choose the server's jar file."};
		if (memory.isEmpty()) return new String[] {"refused", "Memory should look like 4G or 4096M."};
		if (port.isEmpty()) { List<String> free = freePorts(1); port = free.isEmpty() ? "25565" : free.get(0); }
		if (!port.matches("\\d{4,5}") || Integer.parseInt(port) > 65535 || Integer.parseInt(port) < 1024) return new String[] {"refused", "The port should be a number from 1024 to 65535 (Minecraft's usual one is 25565)."};
		for (Object o : servers()) if (port.equals(Json.str(Json.map(o).get("minecraft")).replaceAll(".*:", ""))) return new String[] {"refused", "Port " + port + " is " + Json.str(Json.map(o).get("name")) + "'s already: two servers can't share one."};
		if (!java.isEmpty() && !Files.isRegularFile(Path.of(java))) return new String[] {"refused", "There's no Java at " + java};
		String folder = name.replaceAll("[^A-Za-z0-9 ._-]", "").trim().replaceAll("\\.+$", "");
		if (folder.isEmpty()) folder = "server";
		Path d = Path.of(serversDir(), folder);
		if (Files.exists(d)) return new String[] {"refused", "There's already a folder called " + folder + " in " + serversDir() + ": choose another name (or add that one in Server setup, if it is a server)."};
		String p2 = port, v2 = version;
		making.putAll(Map.of("state", "working", "step", "Starting", "message", "", "id", ""));
		Thread t = new Thread(() -> {
			try {
				String id = make(name, kind, v2, Path.of(file.isEmpty() ? "." : file), memory, p2, java, d);
				making.putAll(Map.of("state", "done", "step", "", "id", id, "message", name + " is made, in " + d + ". It's off: start it from its Status tab, or Open it from the first screen. The first start makes the world, which takes a minute or two."));
				log("new server " + name + " (" + kind + (v2.isEmpty() ? "" : " " + v2) + ", port " + p2 + ") made in " + d);
			} catch (Exception e) {
				String why = e.getMessage() == null ? e.toString() : e.getMessage();
				try { wipe(d); } catch (IOException ignored) {}
				making.putAll(Map.of("state", "failed", "step", "", "id", "", "message", "The server wasn't made: " + why + ". Nothing was left behind."));
				log("new server " + name + " (" + kind + ") not made: " + why);
			}
		}, "make-server");
		t.setDaemon(true);
		t.start();
		return new String[] {"ok", "Making " + name};
	}

	private static String make(String name, String kind, String version, Path file, String memory, String port, String java, Path d) throws Exception {
		// Java first: the one chosen, else the lowest one found that the version can run on.
		int need = kind.equals("forge") || kind.equals("file") ? 0 : javaNeeded(version);
		step("Checking Java");
		if (java.isEmpty()) {
			int best = Integer.MAX_VALUE;
			for (Object o : javas()) { int v = (int) Double.parseDouble(Json.str(Json.map(o).get("version"))); if (v >= need && v < best) { best = v; java = Json.str(Json.map(o).get("path")); } }
			if (java.isEmpty()) throw new IOException("Minecraft " + version + " needs Java " + need + " or newer, and none was found on this machine (found: " + found() + "). Install it (adoptium.net has it), then try again");
		} else if (need > 0 && javaMajor(Path.of(java)) > 0 && javaMajor(Path.of(java)) < need) throw new IOException("Minecraft " + version + " needs Java " + need + " or newer, and the Java chosen is " + javaMajor(Path.of(java)));
		Files.createDirectories(d);
		if (kind.equals("vanilla")) {
			step("Asking Mojang for Minecraft " + version);
			String url = "";
			for (Object o : Json.list(Json.map(json(mojang())).get("versions"))) if (Json.str(Json.map(o).get("id")).equals(version)) url = Json.str(Json.map(o).get("url"));
			if (url.isEmpty()) throw new IOException("Mojang's list has no version " + version);
			Map<String, Object> dl = Json.map(Json.map(Json.map(json(url)).get("downloads")).get("server"));
			if (Json.str(dl.get("url")).isEmpty()) throw new IOException("Mojang publishes no server for " + version);
			String sum = fetchTo(Json.str(dl.get("url")), d.resolve("server.jar"), "SHA-1");
			if (!sum.equalsIgnoreCase(Json.str(dl.get("sha1")))) throw new IOException("what arrived doesn't match Mojang's checksum, so it wasn't kept");
		} else if (kind.equals("paper")) {
			step("Asking PaperMC for Paper " + version);
			Map<String, Object> build = Json.map(json(paper() + "/versions/" + version + "/builds/latest"));
			Map<String, Object> dl = Json.map(Json.map(build.get("downloads")).get("server:default"));
			if (Json.str(dl.get("url")).isEmpty()) throw new IOException("PaperMC has no build for " + version);
			String jar = Json.str(dl.get("name")).replaceAll("[^A-Za-z0-9._-]", "");
			String sum = fetchTo(Json.str(dl.get("url")), d.resolve(jar.endsWith(".jar") ? jar : "paper-" + version + ".jar"), "SHA-256");
			if (!sum.equalsIgnoreCase(Json.str(Json.map(dl.get("checksums")).get("sha256")))) throw new IOException("what arrived doesn't match PaperMC's checksum, so it wasn't kept");
		} else if (kind.equals("fabric")) {
			step("Asking Fabric for its newest loader");
			String loader = "", installer = "";
			for (Object o : Json.list(json(fabric() + "/loader"))) if (loader.isEmpty() && yes(Json.map(o).get("stable"))) loader = Json.str(Json.map(o).get("version"));
			for (Object o : Json.list(json(fabric() + "/installer"))) if (installer.isEmpty() && yes(Json.map(o).get("stable"))) installer = Json.str(Json.map(o).get("version"));
			if (loader.isEmpty() || installer.isEmpty()) throw new IOException("Fabric's list has no stable loader or installer");
			fetchTo(fabric() + "/loader/" + version + "/" + loader + "/" + installer + "/server/jar", d.resolve("fabric-server-mc." + version + "-loader." + loader + "-launcher." + installer + ".jar"), "SHA-256");
			Files.createDirectories(d.resolve("mods"));
		} else if (kind.equals("file")) {
			step("Copying the server's jar");
			Files.copy(file, d.resolve(file.getFileName().toString()));
			if (DirectHost.serverJar(d) == null) throw new IOException("that file isn't a .jar");
		} else {
			// Forge / NeoForge: their installer does the work (it fetches Minecraft and the loader's libraries itself).
			step("Running the installer (this takes a few minutes)");
			Path inst = d.resolve("installer.jar");
			Files.copy(file, inst);
			Process p = new ProcessBuilder(java, "-jar", "installer.jar", "--installServer").directory(d.toFile()).redirectErrorStream(true).start();
			java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
			try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				for (String l; (l = in.readLine()) != null; ) { tail.addLast(l); if (tail.size() > 4) tail.removeFirst(); if (!l.isBlank()) step("Installer: " + (l.length() > 90 ? l.substring(0, 90) : l)); }
			}
			if (!p.waitFor(20, TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("the installer didn't finish in 20 minutes"); }
			if (p.exitValue() != 0) throw new IOException("the installer ended with an error: " + String.join(" / ", tail));
			Files.deleteIfExists(inst);
			Files.deleteIfExists(d.resolve("installer.jar.log"));
			Map<String, Object> probe = m("dir", d.toString());
			if (DirectHost.launch(probe) == null) throw new IOException("the installer finished but left nothing Gatekeeper can start (is it a server installer?)");
			Files.createDirectories(d.resolve("mods"));
		}
		step("Writing its settings");
		Files.writeString(d.resolve("eula.txt"), "# Minecraft's EULA (https://aka.ms/MinecraftEULA), agreed to by this machine's owner in Gatekeeper on " + LocalDateTime.now().format(TIME) + "\neula=true\n", StandardCharsets.UTF_8);
		Files.writeString(d.resolve("server.properties"), "server-port=" + port + "\n", StandardCharsets.UTF_8);
		synchronized (HostHelper.class) {
			java.util.Set<String> used = new java.util.HashSet<>();
			for (Object o : servers()) used.add(Json.str(Json.map(o).get("id")));
			String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""), id = base.isEmpty() ? "server" : base;
			for (int n = 2; used.contains(id); n++) id = (base.isEmpty() ? "server" : base) + "-" + n;
			List<Object> all = new ArrayList<>(servers());
			all.add(m("id", id, "name", name, "dir", d.toString(), "minecraft", "127.0.0.1:" + port, "mcss_server", "", "memory", memory, "java", java, "kind", kind, "version", version));
			cfg.put("servers", all);
			if (setting("servers_dir", "").isEmpty()) cfg.put("servers_dir", serversDir());
			saveCfg();
			return id;
		}
	}
	private static String found() {
		List<String> l = new ArrayList<>();
		for (Object o : javas()) l.add("Java " + Json.str(Json.map(o).get("version")).replaceAll("\\.0$", ""));
		return l.isEmpty() ? "none" : String.join(", ", l);
	}
	/** Removes a folder Gatekeeper has just made itself. */
	static void wipe(Path d) throws IOException {
		if (!Files.isDirectory(d)) return;
		try (java.util.stream.Stream<Path> s = Files.walk(d)) { for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(p); }
	}

	// ------------------------------------------------------------------ one server: what it is set to, and how it is doing

	static Map<String, String> properties(Path d) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String l : read(d.resolve("server.properties")).split("\\R")) { int eq = l.indexOf('='); if (eq > 0 && !l.startsWith("#")) out.put(l.substring(0, eq).trim(), l.substring(eq + 1)); }
		return out;
	}
	/** Sets lines of server.properties (ones it doesn't have yet are added); everything else in it is left as it is. */
	static void setProperties(Path d, Map<String, String> set) throws IOException {
		Path f = d.resolve("server.properties");
		String t = read(f), nl = t.contains("\r\n") ? "\r\n" : "\n";
		List<String> lines = new ArrayList<>(t.isEmpty() ? List.of() : List.of(t.split("\\R")));
		for (Map.Entry<String, String> e : set.entrySet()) {
			boolean had = false;
			for (int i = 0; i < lines.size(); i++) if (lines.get(i).replaceAll("\\s*=.*", "").trim().equals(e.getKey()) && !lines.get(i).startsWith("#")) { lines.set(i, e.getKey() + "=" + e.getValue()); had = true; }
			if (!had) lines.add(e.getKey() + "=" + e.getValue());
		}
		Files.writeString(f, String.join(nl, lines) + nl, StandardCharsets.UTF_8);
	}

	/** Memory a process and what it started use, in MB (0 when it can't be told). */
	static long memoryMb(ProcessHandle h) {
		long kb = 0;
		List<ProcessHandle> all = new ArrayList<>(List.of(h));
		h.descendants().forEach(all::add);
		for (ProcessHandle p : all) {
			if (!WIN) { for (String l : read(Path.of("/proc/" + p.pid() + "/status")).split("\\R")) if (l.startsWith("VmRSS:")) try { kb += Long.parseLong(l.replaceAll("\\D", "")); } catch (NumberFormatException ignored) {} }
			else { String s = commandSays("tasklist", "/FI", "PID eq " + p.pid(), "/FO", "CSV", "/NH"); java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([\\d.,\\s ]+) K\"").matcher(s); if (m.find()) try { kb += Long.parseLong(m.group(1).replaceAll("\\D", "")); } catch (NumberFormatException ignored) {} }
		}
		return kb / 1024;
	}

	static Map<String, Object> info() {
		Map<String, Object> s = server();
		Path d = serverDir();
		String id = id();
		DirectHost.Run r = DirectHost.run(id);
		ProcessHandle h = r != null ? r.process.toHandle() : DirectHost.adopted(d);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", id); out.put("name", Json.str(s.get("name"))); out.put("dir", d.toString());
		out.put("memory", Json.str(s.get("memory")).isEmpty() ? setting("server_memory", "4G") : Json.str(s.get("memory")));
		out.put("java", Json.str(s.get("java"))); out.put("start", Json.str(s.get("start")));
		out.put("autostart", yes(s.get("autostart"))); out.put("keep_online", Json.str(s.get("keep_online")).equals("crash") ? "crash" : "none");
		out.put("port", portNow(d.toString()).isEmpty() ? "25565" : portNow(d.toString()));
		out.put("eula", DirectHost.eulaAccepted(d));
		List<String> cmd = DirectHost.launch(s);
		out.put("started_with", cmd == null ? "" : String.join(" ", cmd));
		out.put("blocker", DirectHost.blocker(s));
		out.put("phase", serverPhase());
		out.put("console", r != null);
		out.put("running", h != null);
		if (h != null) {
			out.put("uptime", (System.currentTimeMillis() - h.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(System.currentTimeMillis())) / 1000);
			out.put("memory_used", memoryMb(h));
			long cpu = h.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L), now = System.nanoTime();
			if (r != null && cpu >= 0) {
				if (r.cpuNanos >= 0 && now > r.cpuAt) out.put("cpu", Math.round(100.0 * (cpu - r.cpuNanos) / (now - r.cpuAt) / Runtime.getRuntime().availableProcessors()));
				r.cpuNanos = cpu; r.cpuAt = now;
			}
		}
		out.put("players", serverPhase().equals("online") ? onlineBy.getOrDefault(serverKey(), Map.of()).size() : 0);
		out.put("properties", properties(d));
		out.put("javas", javas());
		out.put("backup_what", Json.str(s.get("backup_what")).equals("all") ? "all" : "world");
		out.put("backup_keep", Json.str(s.get("backup_keep")).isEmpty() ? "10" : Json.str(s.get("backup_keep")).replaceAll("\\.0$", ""));
		out.put("backup_dir", backupDir().toString());
		out.put("backups", backups());
		out.put("backup_state", backing.getOrDefault(id, ""));
		out.put("tasks", Json.list(s.get("tasks")));
		return out;
	}

	/** Saves a server's own settings (only the ones given). The port goes in its server.properties too. */
	static synchronized String[] saveSettings(Map<String, Object> b) throws IOException {
		Map<String, Object> s = server();
		if (s.isEmpty()) return new String[] {"refused", "No such server"};
		Path d = serverDir();
		boolean on = minecraftRunning();
		List<String> notes = new ArrayList<>();
		if (b.containsKey("name")) { String n = clean(Json.str(b.get("name")), 40); if (n.isEmpty()) return new String[] {"refused", "Give the server a name."}; s.put("name", n); }
		if (b.containsKey("memory")) { String mem = memoryOk(Json.str(b.get("memory")).trim()); if (mem.isEmpty()) return new String[] {"refused", "Memory should look like 4G or 4096M."}; s.put("memory", mem); }
		if (b.containsKey("java")) { String j = Json.str(b.get("java")).trim(); if (!j.isEmpty() && !Files.isRegularFile(Path.of(j))) return new String[] {"refused", "There's no Java at " + j}; s.put("java", j); }
		if (b.containsKey("start")) s.put("start", Json.str(b.get("start")).replaceAll("[\\r\\n]", " ").trim());
		if (b.containsKey("autostart")) s.put("autostart", yes(b.get("autostart")));
		if (b.containsKey("keep_online")) s.put("keep_online", Json.str(b.get("keep_online")).equals("crash") ? "crash" : "none");
		if (b.containsKey("backup_what")) s.put("backup_what", Json.str(b.get("backup_what")).equals("all") ? "all" : "world");
		if (b.containsKey("backup_keep")) { String k = Json.str(b.get("backup_keep")).replaceAll("\\.0$", "").trim(); if (!k.matches("\\d{1,3}") || k.equals("0")) return new String[] {"refused", "Keep at least 1 backup (a number up to 999)."}; s.put("backup_keep", k); }
		if (b.containsKey("backup_dir")) { String bd = Json.str(b.get("backup_dir")).trim(); if (bd.isEmpty()) s.remove("backup_dir"); else s.put("backup_dir", bd); }
		if (b.containsKey("port")) {
			String port = Json.str(b.get("port")).replaceAll("\\.0$", "").trim();
			if (!port.matches("\\d{4,5}") || Integer.parseInt(port) > 65535 || Integer.parseInt(port) < 1024) return new String[] {"refused", "The port should be a number from 1024 to 65535."};
			if (!port.equals(portNow(d.toString()))) {
				for (Object o : servers()) if (Json.map(o) != s && port.equals(Json.str(Json.map(o).get("minecraft")).replaceAll(".*:", ""))) return new String[] {"refused", "Port " + port + " is " + Json.str(Json.map(o).get("name")) + "'s already."};
				if (on) return new String[] {"refused", "Stop the server before changing its port."};
				setProperties(d, Map.of("server-port", port));
				s.put("minecraft", "127.0.0.1:" + port);
				notes.add("players' launchers pick the new port up by themselves");
			}
		}
		if (b.containsKey("eula") && yes(b.get("eula")) && !DirectHost.eulaAccepted(d)) {
			Files.writeString(d.resolve("eula.txt"), "# Minecraft's EULA (https://aka.ms/MinecraftEULA), agreed to by this machine's owner in Gatekeeper on " + LocalDateTime.now().format(TIME) + "\neula=true\n", StandardCharsets.UTF_8);
			log(Json.str(s.get("name")) + ": the owner agreed to Minecraft's EULA in Gatekeeper");
		}
		if (b.containsKey("tasks")) {
			List<Object> tasks = new ArrayList<>();
			for (Object o : Json.list(b.get("tasks"))) {
				Map<String, Object> t = Json.map(o);
				String when = Json.str(t.get("when")), job = Json.str(t.get("job")), at = Json.str(t.get("at")).trim(), mins = Json.str(t.get("minutes")).replaceAll("\\.0$", "").trim(), who = Json.str(t.get("players"));
				String tn = clean(Json.str(t.get("name")), 40);
				if (!List.of("daily", "every", "manual").contains(when) || !List.of("start", "stop", "restart", "commands", "backup").contains(job)) return new String[] {"refused", "A task needs a time and something to do."};
				if (when.equals("daily") && !at.matches("([01]?\\d|2[0-3]):[0-5]\\d")) return new String[] {"refused", "\"" + tn + "\": the time of day should look like 04:30 (24-hour clock)."};
				if (when.equals("every") && (!mins.matches("\\d{1,5}") || Integer.parseInt(mins) < 1)) return new String[] {"refused", "\"" + tn + "\": say every how many minutes (1 or more)."};
				List<Object> cmds = new ArrayList<>();
				for (Object c : Json.list(t.get("commands"))) { String line = Json.str(c).replaceAll("[\\r\\n]", " ").trim().replaceAll("^/", ""); if (!line.isEmpty()) cmds.add(line); }
				if (job.equals("commands") && cmds.isEmpty()) return new String[] {"refused", "\"" + tn + "\": give it at least one command to type."};
				tasks.add(m("name", tn.isEmpty() ? job : tn, "enabled", !t.containsKey("enabled") || yes(t.get("enabled")), "when", when, "at", at.length() == 4 ? "0" + at : at, "minutes", mins, "players", List.of("empty", "some").contains(who) ? who : "any", "job", job, "commands", cmds));
			}
			s.put("tasks", tasks);
			lastRan.keySet().removeIf(k -> k.startsWith(Json.str(s.get("id")) + "|"));
			for (int i = 0; i < tasks.size(); i++) if (Json.str(Json.map(tasks.get(i)).get("when")).equals("every")) lastRan.put(Json.str(s.get("id")) + "|" + i, System.currentTimeMillis());   // (counted from now)
		}
		saveCfg();
		return new String[] {"ok", "Saved" + (on && (b.containsKey("memory") || b.containsKey("java") || b.containsKey("start")) ? ": it takes effect when the server next starts" : "") + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")") + "."};
	}

	// ------------------------------------------------------------------ backups

	static final Map<String, String> backing = new ConcurrentHashMap<>();   // (server id -> how its backup is going, or went)

	static Path backupDir() {
		String set = Json.str(server().get("backup_dir"));
		if (!set.isEmpty()) return Path.of(set);
		Path d = serverDir();
		return d.toAbsolutePath().getParent().resolveSibling("minecraft-server-backups").resolve(d.getFileName().toString());
	}
	static List<Object> backups() {
		List<Object> out = new ArrayList<>();
		Path bd = backupDir();
		if (Files.isDirectory(bd)) try (java.util.stream.Stream<Path> s = Files.list(bd)) {
			for (Path p : (Iterable<Path>) s.filter(q -> q.getFileName().toString().matches("backup_.*\\.zip")).sorted(java.util.Comparator.reverseOrder())::iterator)
				out.add(m("file", p.getFileName().toString(), "mb", Math.max(1, Files.size(p) / 1048576)));
		} catch (IOException | RuntimeException ignored) {}
		return out;
	}
	/** Starts a backup of the current server (in the background). With the server on, it is told to save and to hold
	 *  its saving still while its files are copied; a server whose console Gatekeeper lost has to be stopped first. */
	static String[] backup(String by) {
		if (!builtin()) return new String[] {"refused", HERE_MCSS};
		Map<String, Object> s = server();
		if (s.isEmpty()) return new String[] {"refused", "No such server"};
		String id = id(), name = serverName();
		Path d = serverDir(), bd = backupDir();
		if (backing.getOrDefault(id, "").startsWith("Backing up")) return new String[] {"refused", "A backup of " + name + " is already being made"};
		DirectHost.Run r = DirectHost.run(id);
		if (r == null && DirectHost.adopted(d) != null) return new String[] {"refused", name + " was started before Gatekeeper last restarted, so it can't be told to hold its saving still: stop it first, then back it up"};
		boolean all = Json.str(s.get("backup_what")).equals("all");
		int keep = 10; try { keep = Integer.parseInt(Json.str(s.get("backup_keep")).replaceAll("\\.0$", "")); } catch (NumberFormatException ignored) {}
		int keepN = Math.max(1, keep);
		String level = properties(d).getOrDefault("level-name", "world").trim();
		backing.put(id, "Backing up…");
		Thread t = new Thread(() -> {
			Path zip = bd.resolve("backup_" + LocalDateTime.now().format(STAMP) + (all ? "_all" : "_world") + ".zip"), part = bd.resolve(zip.getFileName() + ".part");
			try {
				Files.createDirectories(bd);
				if (r != null && r.ready) {
					long before = r.printed;
					DirectHost.type(r, "save-off"); DirectHost.type(r, "save-all flush");
					for (int i = 0; i < 120 && r.process.isAlive(); i++) {   // (up to a minute for "Saved the game")
						boolean saved = false;
						synchronized (r.lines) { long n = r.printed; for (java.util.Iterator<String> it = r.lines.descendingIterator(); it.hasNext() && n > before; n--) if (it.next().contains("Saved the game")) saved = true; }
						if (saved) break;
						Thread.sleep(500);
					}
				}
				long files = 0;
				try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(part)); java.util.stream.Stream<Path> walk = Files.walk(d)) {
					for (Path p : (Iterable<Path>) walk::iterator) {
						if (!Files.isRegularFile(p) || p.startsWith(bd)) continue;
						String rel = d.relativize(p).toString().replace('\\', '/'), top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
						if (rel.endsWith("session.lock") || rel.equals("gatekeeper-server.pid")) continue;
						if (all ? (top.equals("logs") || top.equals("crash-reports")) : !top.startsWith(level)) continue;
						z.putNextEntry(new java.util.zip.ZipEntry(rel));
						try { Files.copy(p, z); files++; } catch (IOException busy) { /* a file the server has open just now: left out */ }
						z.closeEntry();
					}
				}
				if (files == 0) { Files.deleteIfExists(part); throw new IOException(all ? "the server's folder is empty" : "there is no world yet (the first start makes it)"); }
				Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING);
				// (the oldest of Gatekeeper's own backups there go, beyond the number to keep)
				List<Path> mine = new ArrayList<>();
				try (java.util.stream.Stream<Path> l = Files.list(bd)) { l.filter(q -> q.getFileName().toString().matches("backup_.*\\.zip")).sorted(java.util.Comparator.reverseOrder()).forEach(mine::add); }
				for (int i = keepN; i < mine.size(); i++) Files.deleteIfExists(mine.get(i));
				String done = "Backed up " + LocalDateTime.now().format(TIME) + ": " + zip.getFileName() + " (" + Math.max(1, Files.size(zip) / 1048576) + " MB, " + files + " files)";
				backing.put(id, done);
				log("backup of " + name + " (asked by " + by + "): " + zip + (mine.size() > keepN ? "; " + (mine.size() - keepN) + " older removed" : ""));
			} catch (Exception e) {
				try { Files.deleteIfExists(part); } catch (IOException ignored) {}
				backing.put(id, "The last backup failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
				log("backup of " + name + " (asked by " + by + ") failed: " + e);
			} finally {
				if (r != null && r.process.isAlive()) DirectHost.type(r, "save-on");
			}
		}, "backup-" + id);
		t.setDaemon(true);
		t.start();
		return new String[] {"ok", "Backing up " + name + " to " + bd};
	}

	// ------------------------------------------------------------------ scheduled tasks

	static final Map<String, Long> lastRan = new ConcurrentHashMap<>();   // (server id | task number -> when)
	static final long began = System.currentTimeMillis();

	/** Does a task's job for the current server; says what happened. */
	static String[] runTask(Map<String, Object> t, String by) {
		String job = Json.str(t.get("job"));
		if (job.equals("start")) return startServerFully(by);
		if (job.equals("stop")) { String[] r = host.serverAction(false); if (r[0].equals("ok")) expect(serverKey() + "|server", "stopping", 200_000); return r; }
		if (job.equals("restart")) return DirectHost.restart();
		if (job.equals("backup")) return backup(by);
		String[] last = {"refused", "It has no commands"};
		for (Object c : Json.list(t.get("commands"))) { last = host.command(Json.str(c)); if (!last[0].equals("ok")) return last; }
		return last[0].equals("ok") ? new String[] {"ok", "Typed into the console"} : last;
	}
	/** Every 20 seconds: each server's enabled tasks whose time has come. */
	static void schedule() {
		Thread t = new Thread(() -> {
			while (true) {
				try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
				if (!builtin()) continue;
				try {
					String hhmm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
					for (Object o : new ArrayList<>(servers())) {
						Map<String, Object> s = Json.map(o);
						String id = Json.str(s.get("id"));
						SERVER.set(id);
						List<Object> tasks = Json.list(s.get("tasks"));
						for (int i = 0; i < tasks.size(); i++) {
							Map<String, Object> task = Json.map(tasks.get(i));
							if (!yes(task.get("enabled"))) continue;
							String when = Json.str(task.get("when")), key = id + "|" + i;
							long now = System.currentTimeMillis(), last = lastRan.getOrDefault(key, 0L);
							boolean due = when.equals("daily") ? hhmm.equals(Json.str(task.get("at"))) && now - last > 90_000
									: when.equals("every") && now - Math.max(last, began) >= Long.parseLong(Json.str(task.get("minutes")).replaceAll("\\.0$", "")) * 60_000L;
							if (!due) continue;
							lastRan.put(key, now);
							int playing = serverPhase().equals("online") ? onlineBy.getOrDefault(serverKey(), Map.of()).size() : 0;
							String who = Json.str(task.get("players"));
							if ((who.equals("empty") && playing > 0) || (who.equals("some") && playing == 0)) continue;   // (not for who is on just now: it waits for its next time)
							String[] r = runTask(task, "the task \"" + Json.str(task.get("name")) + "\"");
							log("task \"" + Json.str(task.get("name")) + "\" for " + serverName() + ": " + r[1]);
						}
					}
				} catch (RuntimeException e) { log("scheduled tasks: " + e); }
				finally { SERVER.set(""); }
			}
		}, "tasks");
		t.setDaemon(true);
		t.start();
	}
	/** When Gatekeeper starts: the servers set to start with it. */
	static void autostart() {
		if (!builtin()) return;
		Thread t = new Thread(() -> {
			try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
			for (Object o : new ArrayList<>(servers())) {
				Map<String, Object> s = Json.map(o);
				if (!yes(s.get("autostart"))) continue;
				SERVER.set(Json.str(s.get("id")));
				String[] r = startServerFully("autostart");
				log("autostart " + serverName() + ": " + r[1]);
			}
			SERVER.set("");
		}, "autostart");
		t.setDaemon(true);
		t.start();
	}

	// ------------------------------------------------------------------ the window's calls: /admin/tools/...

	/** Answers one of the window's calls; false when the path isn't one of these. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.startsWith("/admin/tools/")) return false;
		String what = path.substring(13);
		boolean post = x.getRequestMethod().equals("POST");
		Map<String, Object> b = post ? body(x) : new LinkedHashMap<>();
		if (!builtin()) { reply(x, 200, m("result", "refused", "message", HERE_MCSS, "host", "mcss")); return true; }
		String[] r = null;
		if (what.equals("new")) {
			if (post) r = create(b);
			else {
				List<String> free = freePorts(1);
				reply(x, 200, m("host", "builtin", "folder", serversDir(), "port", free.isEmpty() ? "25565" : free.get(0), "javas", javas(), "making", new LinkedHashMap<>(making),
						"kinds", List.of(m("id", "vanilla", "name", "Vanilla (the plain game, from Mojang)"), m("id", "paper", "name", "Paper (plugins, from PaperMC)"), m("id", "fabric", "name", "Fabric (mods, from Fabric)"),
								m("id", "forge", "name", "Forge or NeoForge (mods): an installer I downloaded"), m("id", "file", "name", "A server jar I already have"))));
				return true;
			}
		} else if (what.equals("versions")) {
			try { reply(x, 200, m("result", "ok", "versions", versions(Json.str(b.get("kind"))))); }
			catch (IOException e) { reply(x, 200, m("result", "failed", "message", "The list of versions couldn't be fetched (" + e.getMessage() + "). Is this machine online?", "versions", List.of())); }
			return true;
		} else if (server().isEmpty()) r = new String[] {"refused", "No such server"};
		else if (what.equals("server")) {
			if (post) r = saveSettings(b);
			else { reply(x, 200, info()); return true; }
		} else if (what.equals("properties")) {
			Map<String, String> set = new LinkedHashMap<>();
			for (Map.Entry<String, Object> e : Json.map(b.get("values")).entrySet()) if (e.getKey().matches("[A-Za-z0-9._-]+")) set.put(e.getKey(), Json.str(e.getValue()).replaceAll("[\\r\\n]", " "));
			if (set.containsKey("server-port")) r = new String[] {"refused", "Change the port on the Settings tab (Gatekeeper and the launchers need to know it too)."};
			else { setProperties(serverDir(), set); log("server.properties of " + serverName() + " changed from Gatekeeper: " + String.join(", ", set.keySet())); r = new String[] {"ok", minecraftRunning() ? "Saved. The server reads its settings when it starts: restart it for them to take effect." : "Saved."}; }
		} else if (what.equals("console")) {
			// What it has printed since line number "after" (0: the newest 300).
			long after = 0; try { after = (long) Double.parseDouble(Json.str(b.get("after"))); } catch (NumberFormatException ignored) {}
			DirectHost.Run run = DirectHost.runs.get(id());
			if (run == null) run = DirectHost.ended.get(id());
			List<Object> lines = new ArrayList<>();
			long next = 0;
			if (run != null) synchronized (run.lines) {
				next = run.printed;
				long first = run.printed - run.lines.size(), from = after <= 0 || after > run.printed ? Math.max(first, run.printed - 300) : Math.max(first, after);
				long n = first;
				for (String l : run.lines) { if (n >= from) lines.add(l); n++; }
			}
			reply(x, 200, m("lines", lines, "next", next, "live", run != null && run.process.isAlive(), "adopted", run == null && DirectHost.adopted(serverDir()) != null));
			return true;
		} else if (what.equals("command")) {
			String line = Json.str(b.get("command")).trim().replaceAll("^/", "");
			r = line.isEmpty() ? new String[] {"refused", "Nothing to type"} : host.command(line);
			if (r[0].equals("ok")) log("console of " + serverName() + ", typed in Gatekeeper: " + clean(line, 120));
		} else if (what.equals("action")) {
			String a = Json.str(b.get("action"));
			r = a.equals("restart") ? DirectHost.restart() : a.equals("kill") ? DirectHost.kill() : new String[] {"refused", "Unknown action"};
			if (a.equals("restart") && r[0].equals("ok")) expect(serverKey() + "|server", "stopping", 200_000);
			log(a + " " + serverName() + " from Gatekeeper: " + r[1]);
		} else if (what.equals("backup")) r = backup("the owner");
		else if (what.equals("task")) {
			List<Object> tasks = Json.list(server().get("tasks"));
			int i = -1; try { i = (int) Double.parseDouble(Json.str(b.get("index"))); } catch (NumberFormatException ignored) {}
			if (i < 0 || i >= tasks.size()) r = new String[] {"refused", "No such task (save the list first)"};
			else { r = runTask(Json.map(tasks.get(i)), "the owner"); log("task \"" + Json.str(Json.map(tasks.get(i)).get("name")) + "\" for " + serverName() + ", run now: " + r[1]); }
		} else return false;
		reply(x, 200, m("result", r[0], "message", r[1]));
		return true;
	}
}
