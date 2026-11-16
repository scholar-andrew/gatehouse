package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gatekeeper running the Minecraft servers itself, with no host program in between (see ServerHost). It is what a
 * machine without MC Server Soft uses, which is every Linux and SteamOS machine (MCSS is Windows only); on Windows
 * host.json "host": "builtin" chooses it instead of MCSS.
 *
 * A server is a folder with its program in it. Gatekeeper starts that program as a process of its own, in that folder,
 * and keeps its console: what the server prints is read (to know when it has finished starting), and commands, "stop"
 * among them, are typed into it. It downloads nothing and accepts nothing on the owner's behalf: a server whose
 * eula.txt doesn't say eula=true isn't started, and Gatekeeper says where that file is.
 *
 * What starts a server, the first of these that applies:
 *   - the server's own "start" in host.json (a whole command line, run by the system's shell in the server's folder)
 *   - on Linux, run.sh in its folder (what Forge and NeoForge make), as: sh run.sh nogui
 *   - a server jar in its folder (fabric-server*.jar, quilt-server*.jar, server.jar, or the only jar there), as:
 *     <java> -Xmx<memory> -jar <that jar> nogui
 *     java: the server's "java" in host.json, else "server_java", else the Java Gatekeeper itself runs on.
 *     memory: the server's "memory", else "server_memory", else 4G.
 *
 * A server only has a console while the Gatekeeper that started it is still running. If Gatekeeper ends first (a
 * crash, a restart), the server carries on by itself; the next Gatekeeper recognises it by gatekeeper-server.pid in
 * its folder and shows it as on. It can't type into its console any more: on Linux it can still stop it properly
 * (the ordinary request to end, on which a Minecraft server saves and shuts down); on Windows it can't, and says so.
 * For that reason Gatekeeper doesn't update itself while a server it runs is on.
 */
final class DirectHost implements ServerHost {
	/** One server Gatekeeper started and still has the console of. */
	static final class Run {
		Process process;
		OutputStream console;
		volatile boolean ready, stopping;
		final java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
	}
	static final Map<String, Run> runs = new ConcurrentHashMap<>();

	private static String id() { return Json.str(server().get("id")); }
	/** Is a server on that this Gatekeeper started (and so would lose the console of, if it ended)? */
	static boolean anyTracked() {
		for (Run r : runs.values()) if (r.process.isAlive()) return true;
		return false;
	}

	// ------------------------------------------------------------------ what is in a server's folder

	/** The server jar in a folder, or null. */
	static Path serverJar(Path d) {
		List<Path> jars = new ArrayList<>();
		try (java.util.stream.Stream<Path> s = Files.list(d)) { s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")).sorted().forEach(jars::add); }
		catch (IOException | RuntimeException e) { return null; }
		for (String start : new String[] {"fabric-server", "quilt-server"}) for (Path p : jars) if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(start)) return p;
		for (Path p : jars) if (p.getFileName().toString().equalsIgnoreCase("server.jar")) return p;
		for (String part : new String[] {"paper", "purpur", "spigot", "forge", "server"}) for (Path p : jars) if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains(part)) return p;
		return jars.size() == 1 ? jars.get(0) : null;
	}
	/** Does that folder look like a Minecraft server's? */
	static boolean looksLikeServer(Path d) {
		return serverJar(d) != null || Files.isRegularFile(d.resolve("run.sh")) || Files.isRegularFile(d.resolve("server.properties"));
	}
	static boolean eulaAccepted(Path d) {
		for (String l : read(d.resolve("eula.txt")).split("\\R")) if (l.replace(" ", "").equalsIgnoreCase("eula=true")) return true;
		return false;
	}
	/** The command that starts one of host.json's servers, or null when its folder has nothing to start. */
	static List<String> launch(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		String custom = Json.str(s.get("start"));
		if (!custom.isEmpty()) return WIN ? List.of("cmd", "/c", custom) : List.of("sh", "-c", custom);
		if (!WIN && Files.isRegularFile(d.resolve("run.sh"))) return List.of("sh", "run.sh", "nogui");
		Path jar = serverJar(d);
		if (jar == null) return null;
		String java = Json.str(s.get("java")), memory = Json.str(s.get("memory"));
		if (java.isEmpty()) java = setting("server_java", ProcessHandle.current().info().command().orElse("java"));
		if (memory.isEmpty()) memory = setting("server_memory", "4G");
		return List.of(java, "-Xmx" + memory, "-jar", jar.getFileName().toString(), "nogui");
	}
	/** Why one of host.json's servers can't be started as it stands, or "" when it can. */
	static String blocker(Map<String, Object> s) {
		Path d = Path.of(Json.str(s.get("dir")));
		if (!Files.isDirectory(d)) return "its folder is gone (" + d + ")";
		if (launch(s) == null) return "there is no server program in " + d + " (put the server's jar there: server.jar from minecraft.net/download/server, or what Fabric's or Forge's installer makes)";
		if (!eulaAccepted(d)) return "Minecraft's EULA isn't accepted for it yet: read it (aka.ms/MinecraftEULA), then put the line eula=true in " + d.resolve("eula.txt");
		return "";
	}

	// ------------------------------------------------------------------ a server that outlived the Gatekeeper that started it

	private static Path pidFile(Path d) { return d.resolve("gatekeeper-server.pid"); }
	/** The running process a folder's gatekeeper-server.pid names (the same process, not a later one with its number), or null. */
	static ProcessHandle adopted(Path d) {
		String[] t = read(pidFile(d)).trim().split("\\|");
		if (t.length < 2 || !t[0].matches("\\d+")) return null;
		ProcessHandle h = ProcessHandle.of(Long.parseLong(t[0])).orElse(null);
		String began = h == null ? "" : h.info().startInstant().map(i -> String.valueOf(i.toEpochMilli())).orElse("");
		if (h != null && h.isAlive() && began.equals(t[1])) return h;
		try { Files.deleteIfExists(pidFile(d)); } catch (IOException ignored) {}
		return null;
	}

	// ------------------------------------------------------------------ the host program: there is none

	@Override public boolean running() { return true; }
	@Override public String[] start() { return new String[] {"refused", noMcss()}; }
	@Override public String closeMode() { return "close"; }
	@Override public String[] close(boolean force) { return new String[] {"refused", noMcss()}; }

	// ------------------------------------------------------------------ the Minecraft server

	@Override public int serverStatus() {
		Run r = runs.get(id());
		if (r != null && r.process.isAlive()) return r.stopping ? 4 : r.ready ? 1 : 3;
		return adopted(serverDir()) != null ? 1 : 0;
	}

	@Override public String[] serverAction(boolean start) {
		String id = id(), name = serverName();
		Path d = serverDir();
		Run have = runs.get(id);
		if (start) {
			if ((have != null && have.process.isAlive()) || adopted(d) != null) return new String[] {"ok", "The server is already on"};
			String why = blocker(server());
			if (!why.isEmpty()) return new String[] {"refused", name + " can't be started: " + why};
			List<String> cmd = launch(server());
			try {
				Process p = new ProcessBuilder(cmd).directory(d.toFile()).redirectErrorStream(true).start();
				Run r = new Run();
				r.process = p; r.console = p.getOutputStream();
				runs.put(id, r);
				try { Files.writeString(pidFile(d), p.pid() + "|" + p.toHandle().info().startInstant().map(i -> String.valueOf(i.toEpochMilli())).orElse(""), StandardCharsets.UTF_8); } catch (IOException ignored) {}
				log("start " + name + ": " + String.join(" ", cmd) + "  (in " + d + ")");
				String awaitedAs = serverKey() + "|server";
				Thread t = new Thread(() -> watch(id, name, d, r, awaitedAs), "server-" + id);
				t.setDaemon(true);
				t.start();
				return new String[] {"ok", "Start requested: the server takes a minute or so to come up"};
			} catch (IOException e) { return new String[] {"failed", "Couldn't start " + name + ": " + e.getMessage()}; }
		}
		if (have != null && have.process.isAlive()) {
			have.stopping = true;
			String[] r = type(have, "stop");
			return r[0].equals("ok") ? new String[] {"ok", "Stop requested: the server saves the world and shuts down"} : r;
		}
		ProcessHandle h = adopted(d);
		if (h == null) return new String[] {"ok", "The server isn't running"};
		if (WIN) return new String[] {"refused", name + " was started before Gatekeeper last restarted, so Gatekeeper can't reach its console to stop it safely. Stop it from inside the game (/stop, as an operator); after that it is Gatekeeper's to start and stop again."};
		// (the ordinary request to end, to the server and whatever started it: a Minecraft server saves the world and shuts down on it)
		h.descendants().forEach(ProcessHandle::destroy);
		h.destroy();
		return new String[] {"ok", "Stop requested: the server saves the world and shuts down"};
	}

	/** Reads what a server prints until it ends: when it has finished starting, and, at the end, how it went. */
	private static void watch(String id, String name, Path d, Run r, String awaitedAs) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				if (line.contains("Done (") && line.contains("For help")) r.ready = true;
				synchronized (r.last) { r.last.addLast(line); if (r.last.size() > 6) r.last.removeFirst(); }
			}
		} catch (IOException ignored) {}
		int code = -1;
		try { code = r.process.waitFor(); } catch (InterruptedException ignored) {}
		runs.remove(id, r);
		try { Files.deleteIfExists(pidFile(d)); } catch (IOException ignored) {}
		if (!r.stopping) { met(awaitedAs); phaseSeen.clear(); }   // (it is off, whatever was being waited for)
		String said;
		synchronized (r.last) { said = String.join(" / ", r.last); }
		log(name + (r.stopping ? " stopped" : r.ready ? " ended without being asked to" : " ended before it had finished starting") + " (exit code " + code + ")"
				+ (r.stopping ? "" : ". The last it said: " + (said.isEmpty() ? "nothing" : said)));
	}

	private static String[] type(Run r, String line) {
		try { r.console.write((line + "\n").getBytes(StandardCharsets.UTF_8)); r.console.flush(); return new String[] {"ok", "sent"}; }
		catch (IOException e) { return new String[] {"failed", "The server's console didn't take it: " + e.getMessage()}; }
	}

	@Override public String[] command(String command) {
		Run r = runs.get(id());
		if (r != null && r.process.isAlive()) return type(r, command.replaceAll("[\\r\\n]", " "));
		if (adopted(serverDir()) != null) return new String[] {"refused", serverName() + " was started before Gatekeeper last restarted, so its console can't be reached until the server has been restarted"};
		return new String[] {"refused", "The server isn't running"};
	}

	// ------------------------------------------------------------------ access: nothing to sign in to

	@Override public boolean hasAccess() { return true; }
	@Override public String accessState() { return "yes"; }
	@Override public String user() { return ""; }
	@Override public String[] signIn(String user, String password) { return new String[] {"refused", noMcss()}; }
	@Override public void setKey(String key) { }

	@Override public Map<String, Object> diagnose() {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> s = server();
		List<String> cmd = s.isEmpty() ? null : launch(s);
		Run r = runs.get(id());
		out.put("host", "Gatekeeper itself (no MC Server Soft)");
		out.put("servers_folder", serversDir());
		out.put("server_folder", Json.str(s.get("dir")));
		out.put("started_with", cmd == null ? "nothing to start in that folder" : String.join(" ", cmd));
		out.put("can_start", s.isEmpty() ? "no server chosen" : blocker(s).isEmpty() ? "yes" : blocker(s));
		out.put("console", r != null && r.process.isAlive() ? "Gatekeeper has it" : s.isEmpty() || adopted(serverDir()) == null ? "the server is off" : "lost (the server outlived the Gatekeeper that started it)");
		return out;
	}
}
