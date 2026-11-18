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
import dev.gatehouse.look.Quiet;

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
		volatile boolean ready, stopping, restart;
		final java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
		/** What it has printed (the newest 2000 lines), and how many lines it has printed in all. */
		final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
		long printed;
		final long started = System.currentTimeMillis();
		long cpuNanos = -1, cpuAt;
	}
	/** When servers that ended unasked were started again (crash handling), so a server that can't stay up isn't started for ever. */
	static final Map<String, List<Long>> revived = new ConcurrentHashMap<>();
	static final Map<String, Run> runs = new ConcurrentHashMap<>();

	private static String id() { return Json.str(server().get("id")); }
	/** Is a server on that this Gatekeeper started (and so would lose the console of, if it ended)? */
	static boolean anyTracked() {
		for (Run r : runs.values()) if (r.process.isAlive()) return true;
		return false;
	}

	// ------------------------------------------------------------------ what is in a server's folder (its game says: Game)

	/** Does that folder look like a server of a game Gatekeeper knows? */
	static boolean looksLikeServer(Path d) { return gameAt(d) != null; }
	/** Has the owner accepted what the current server's game needs accepted before it runs (Minecraft: its EULA)? */
	static boolean eulaAccepted(Path d) { Game g = Game.here(); return g != null && g.termsAccepted(d); }
	/** The command that starts one of host.json's servers, or null when its folder has nothing to start (or it has no game). */
	static List<String> launch(Map<String, Object> s) { Game g = Game.of(s); return g == null ? null : g.launch(s); }
	/** Why one of host.json's servers can't be started as it stands, or "" when it can. */
	static String blocker(Map<String, Object> s) {
		Game g = Game.of(s);
		if (g == null) return "it isn't set to a game Gatekeeper knows (its \"game\" in host.json)";
		return g.blocker(s);
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
		try { Files.deleteIfExists(pidFile(d)); } catch (IOException ignored) { Quiet.seen(ignored); }
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
		ServerWatch.asked(id);
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
				try { Files.writeString(pidFile(d), p.pid() + "|"
						+ p.toHandle().info().startInstant().map(i -> String.valueOf(i.toEpochMilli())).orElse(""),
						StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
				log("start " + name + ": " + String.join(" ", cmd) + "  (in " + d + ")");
				String awaitedAs = serverKey() + "|server";
				Game game = Game.here();
				Thread t = new Thread(() -> watch(id, name, d, r, awaitedAs, game), "server-" + id);
				t.setDaemon(true);
				t.start();
				return new String[] {"ok", "Start requested: the server takes a minute or so to come up"};
			} catch (IOException e) { return new String[] {"failed", "Couldn't start " + name + ": " + e.getMessage()}; }
		}
		if (have != null && have.process.isAlive()) {
			have.stopping = true;
			String[] r = stopCommand().isEmpty() ? askToEnd(have) : type(have, stopCommand());
			return r[0].equals("ok") ? new String[] {"ok", "Stop requested: the server saves the world and shuts down"} : r;
		}
		ProcessHandle h = adopted(d);
		if (h == null) return new String[] {"ok", "The server isn't running"};
		if (WIN) return new String[] {"refused", name
				+ " was started before Gatekeeper last restarted, so Gatekeeper can't reach its console to stop it safely. Stop it from inside the game (/stop, as an operator); after that it is Gatekeeper's to start and stop again."};
		// (the ordinary request to end, to the server and whatever started it: a Minecraft server saves the world and shuts down on it)
		h.descendants().forEach(ProcessHandle::destroy);
		h.destroy();
		return new String[] {"ok", "Stop requested: the server saves the world and shuts down"};
	}

	/** Reads what a server prints until it ends: when it has finished starting, and, at the end, how it went. */
	private static void watch(String id, String name, Path d, Run r, String awaitedAs, Game game) {
		// (a game whose own log is what it prints: kept in its folder, a new one each start, for who joined and left)
		java.io.Writer consoleKeep = null;
		if (game != null && game.keepsConsole()) try { consoleKeep = Files.newBufferedWriter(d.resolve(Game.CONSOLE_LOG), StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
		try (BufferedReader in = new BufferedReader(new InputStreamReader(r.process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				if (consoleKeep != null) try { consoleKeep.write(line); consoleKeep.write('\n'); consoleKeep.flush(); } catch (IOException ignored) { Quiet.seen(ignored); }
				if (game != null && game.readyLine(line)) r.ready = true;
				synchronized (r.last) { r.last.addLast(line); if (r.last.size() > 6) r.last.removeFirst(); }
				synchronized (r.lines) { r.lines.addLast(line); r.printed++; if (r.lines.size() > 2000) r.lines.removeFirst(); }
			}
		} catch (IOException ignored) { Quiet.seen(ignored); }
		if (consoleKeep != null) try { consoleKeep.close(); } catch (IOException ignored) { Quiet.seen(ignored); }
		int code = -1;
		try { code = r.process.waitFor(); } catch (InterruptedException ignored) { Quiet.seen(ignored); }
		runs.remove(id, r);
		ended.put(id, r);
		try { Files.deleteIfExists(pidFile(d)); } catch (IOException ignored) { Quiet.seen(ignored); }
		if (!r.stopping) { met(awaitedAs); phaseSeen.clear(); }   // (it is off, whatever was being waited for)
		String said;
		synchronized (r.last) { said = String.join(" / ", r.last); }
		if (!r.stopping && !r.restart) Notify.down(name, name + (r.ready ? " ended without being asked to"
				: " ended before it had finished starting") + " (exit code " + code + ")."
				+ (Json.str(server().get("keep_online")).equals("crash") ? " It's set to be kept on: Gatekeeper starts it again (up to three times in ten minutes)." : ""));
		log(name + (r.stopping ? " stopped" : r.ready ? " ended without being asked to" : " ended before it had finished starting") + " (exit code " + code + ")"
				+ (r.stopping ? "" : ". The last it said: " + (said.isEmpty() ? "nothing" : said)));
		// Started again: because a restart was asked for, or (crash handling) because it ended unasked and this server
		// is set to be kept on. Not more than three times in ten minutes: a server that can't stay up is left off.
		boolean again = r.restart;
		if (!again && !r.stopping) {
			String keep = "";
			for (Object o : servers()) if (id.equals(Json.str(Json.map(o).get("id")))) keep = Json.str(Json.map(o).get("keep_online"));
			if (keep.equals("crash")) {
				List<Long> when = revived.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
				when.removeIf(t -> t < System.currentTimeMillis() - 600_000);
				if (when.size() >= 3) log(name + " has ended three times in ten minutes: Gatekeeper leaves it off (Manage, Console shows what it said)");
				else { when.add(System.currentTimeMillis()); again = true; log(name + " ended unasked and is set to be kept on: starting it again"); }
			}
		}
		if (again) {
			try { Thread.sleep(2000); } catch (InterruptedException ignored) { Quiet.seen(ignored); }
			SERVER.set(id);
			String[] s = startServerFully(r.restart ? "a restart" : "crash handling");
			log("start " + name + " again: " + s[1]);
		}
	}
	/** The last run of each server that has ended (so what it said can still be read). */
	static final Map<String, Run> ended = new ConcurrentHashMap<>();
	/** The run whose console the current server has (alive), or null. */
	static Run run(String id) { Run r = runs.get(id); return r != null && r.process.isAlive() ? r : null; }

	/** Stop, then start again once it has stopped. */
	static String[] restart() {
		Run r = run(id());
		if (r == null) return adopted(serverDir()) != null ? new String[] {"refused", serverName()
				+ " was started before Gatekeeper last restarted, so Gatekeeper can't restart it: stop it from inside the game first"} : startServerFully("the owner");
		r.restart = true; r.stopping = true;
		String[] t = stopCommand().isEmpty() ? askToEnd(r) : type(r, stopCommand());
		return t[0].equals("ok") ? new String[] {"ok", "Restart requested: the server saves the world, shuts down and starts again"} : t;
	}
	/** Ends it at once, without saving: for a server that no longer answers. */
	static String[] kill() {
		Run r = run(id());
		ProcessHandle h = r != null ? r.process.toHandle() : adopted(serverDir());
		if (h == null) return new String[] {"ok", "The server isn't running"};
		if (r != null) { r.stopping = true; r.restart = false; }
		h.descendants().forEach(ProcessHandle::destroyForcibly);
		h.destroyForcibly();
		return new String[] {"ok", "Ended at once. Anything it hadn't saved yet is lost."};
	}

	/** What stops the current server, typed into its console. */
	static String stopCommand() { Game g = Game.here(); return g == null ? "stop" : g.stopCommand(); }

	/** A game with no stop command: asked to end as closing its window would (it saves on that), and ended outright if
	 *  it is still running a minute later. */
	static String[] askToEnd(Run r) {
		ProcessHandle h = r.process.toHandle();
		h.descendants().forEach(ProcessHandle::destroy);
		h.destroy();
		Thread later = new Thread(() -> {
			try { if (!r.process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { h.descendants().forEach(ProcessHandle::destroyForcibly); h.destroyForcibly(); } }
			catch (InterruptedException ignored) { Quiet.seen(ignored); }
		}, "end-server");
		later.setDaemon(true); later.start();
		return new String[] {"ok", "sent"};
	}

	static String[] type(Run r, String line) {
		try { r.console.write((line + "\n").getBytes(StandardCharsets.UTF_8)); r.console.flush(); return new String[] {"ok", "sent"}; }
		catch (IOException e) { return new String[] {"failed", "The server's console didn't take it: " + e.getMessage()}; }
	}

	@Override public String[] command(String command) {
		Run r = runs.get(id());
		if (r != null && r.process.isAlive()) return type(r, command.replaceAll("[\\r\\n]", " "));
		if (adopted(serverDir()) != null) return new String[] {"refused", serverName()
				+ " was started before Gatekeeper last restarted, so its console can't be reached until the server has been restarted"};
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
		out.put("console", r != null && r.process.isAlive() ? "Gatekeeper has it" : s.isEmpty() || adopted(serverDir()) == null
				? "the server is off" : "lost (the server outlived the Gatekeeper that started it)");
		return out;
	}
}
