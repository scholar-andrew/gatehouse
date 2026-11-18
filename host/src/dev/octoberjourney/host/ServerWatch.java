package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import dev.gatehouse.look.Quiet;

/**
 * Once a minute, for each server (R34):
 * <ul>
 *   <li>crash watch: a server that was on and is now off, without Gatekeeper having been asked to stop it, went down.
 *       The owner is told (a phone notification, when those are on, and the activity log). A server set to be kept on
 *       (Manage, "Start it again if it crashes") is started again, as one Gatekeeper runs itself already was: not more
 *       than three times in ten minutes. For an MCSS server that needs Gatekeeper's MCSS key; a stop made in MCSS itself
 *       looks the same from here, so the message says "stopped outside Gatekeeper, or crashed";</li>
 *   <li>friends joining: a name that wasn't on a server a minute ago and is now (from the server's own log);</li>
 *   <li>performance: memory and processor use of the server's program (found by the port it listens on, or the one
 *       Gatekeeper started), how many were playing, and the server's own "Can't keep up!" warnings (Minecraft's sign
 *       that it is behind: its ticks are taking too long). The last hour is kept, for the server's card.</li>
 * </ul>
 */
final class ServerWatch {
	private ServerWatch() {}

	/** One look: when, processor % (-1 unknown), memory MB (-1 unknown), players, lag warnings since the last look. */
	record Sample(long at, int cpu, long mem, int players, int lag) {}

	static final Map<String, ArrayDeque<Sample>> hour = new ConcurrentHashMap<>();
	private static final Map<String, String> phaseWas = new ConcurrentHashMap<>();
	private static final Map<String, Set<String>> playersWas = new ConcurrentHashMap<>();
	private static final Map<String, Long> askedAt = new ConcurrentHashMap<>();
	private static final Map<String, long[]> cpuWas = new ConcurrentHashMap<>();   // {cpu nanos, at nanos, pid}
	private static final Map<String, Long> logRead = new ConcurrentHashMap<>();

	/** Gatekeeper was asked to start or stop this server (so going off now isn't a crash). */
	static void asked(String id) { askedAt.put(id, System.currentTimeMillis()); }

	static void start() {
		Thread t = new Thread(() -> {
			try { Thread.sleep(20_000); } catch (InterruptedException e) { return; }
			while (true) {
				try { look(); } catch (RuntimeException | Error ignored) { Quiet.seen(ignored); }
				try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
			}
		}, "server-watch");
		t.setDaemon(true);
		t.start();
	}

	static void look() {
		eachServer((id, s) -> {
				String name = Json.str(s.get("name"));
				String phase = serverPhase(), was = phaseWas.put(id, phase);
				boolean on = phase.equals("online");
				// crash watch (a server Gatekeeper runs itself is watched where it ends: DirectHost)
				if ("online".equals(was) && (phase.equals("off") || phase.equals("nomcss")) && !isNative()
						&& System.currentTimeMillis() - askedAt.getOrDefault(id, 0L) > 180_000) wentDown(id, name, s);
				// friends joining
				Set<String> now = on ? new java.util.TreeSet<>(onlineBy.getOrDefault(serverKey(), Map.of()).keySet()) : Set.of();
				Set<String> before = playersWas.put(id, now);
				if (before != null) for (String p : now) if (!before.contains(p)) Notify.joined(p, name);
				// performance
				sample(id, s, on, now.size());
		});
	}

	private static void wentDown(String id, String name, Map<String, Object> s) {
		boolean keepOn = Json.str(s.get("keep_online")).equals("crash");
		String again = "";
		if (keepOn) {
			List<Long> when = DirectHost.revived.computeIfAbsent(id, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
			when.removeIf(t -> t < System.currentTimeMillis() - 600_000);
			if (when.size() >= 3) again = " It has gone down three times in ten minutes, so Gatekeeper leaves it off.";
			else if (!sh().hasAccess()) again = " It's set to be kept on, but Gatekeeper has no MCSS key to start it.";
			else { when.add(System.currentTimeMillis()); asked(id); String[] r = sh().serverAction(true); again = r[0].equals("ok")
					? " Gatekeeper is starting it again." : " Gatekeeper couldn't start it again: " + r[1]; }
		}
		log(name + " went down without Gatekeeper being asked (stopped outside Gatekeeper, or crashed)." + again);
		Notify.down(name, name + " stopped without Gatekeeper being asked: it was stopped outside Gatekeeper, or it crashed." + again);
	}

	private static void sample(String id, Map<String, Object> s, boolean on, int players) {
		int cpu = -1; long mem = -1;
		if (on) {
			ProcessHandle h = process(id, s);
			if (h != null) {
				mem = ServerTools.memoryMb(h);
				long c = h.info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L), t = System.nanoTime();
				long[] w = cpuWas.get(id);
				if (c >= 0 && w != null && w[2] == h.pid() && t > w[1]) cpu = (int) Math.max(0, Math.min(100,
						Math.round(100.0 * (c - w[0]) / (t - w[1]) / Runtime.getRuntime().availableProcessors())));
				if (c >= 0) cpuWas.put(id, new long[] {c, t, h.pid()});
			}
		}
		int lag = on ? lagSince(id, s) : 0;
		ArrayDeque<Sample> q = hour.computeIfAbsent(id, k -> new ArrayDeque<>());
		synchronized (q) {
			q.addLast(new Sample(System.currentTimeMillis(), cpu, mem, players, lag));
			while (!q.isEmpty() && q.peekFirst().at() < System.currentTimeMillis() - 3_600_000) q.pollFirst();
		}
	}

	/** The server's program: the one Gatekeeper started, else whatever listens on its port. */
	static ProcessHandle process(String id, Map<String, Object> s) {
		DirectHost.Run r = DirectHost.run(id);
		if (r != null) return r.process.toHandle();
		String port = Json.str(s.get("minecraft")).replaceAll(".*:", "");
		if (!port.matches("\\d+")) return null;
		long pid = -1;
		if (WIN) {
			for (String l : commandSays("netstat", "-ano", "-p", "TCP").split("\\R")) {
				String[] f = l.trim().split("\\s+");
				if (f.length >= 5 && f[3].equals("LISTENING") && f[1].endsWith(":"
						+ port)) { try { pid = Long.parseLong(f[4]); } catch (NumberFormatException ignored) { Quiet.seen(ignored); } break; }
			}
		} else {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile(":" + port + "\\s.*pid=(\\d+)").matcher(commandSays("ss", "-ltnpH"));
			if (m.find()) pid = Long.parseLong(m.group(1));
		}
		return pid > 0 ? ProcessHandle.of(pid).orElse(null) : null;
	}

	/** "Can't keep up!" lines the server has written to its log since the last look. */
	private static int lagSince(String id, Map<String, Object> s) {
		Path lg = Path.of(Json.str(s.get("dir"))).resolve("logs").resolve("latest.log");
		try {
			long size = Files.size(lg), from = logRead.getOrDefault(id, -1L);
			logRead.put(id, size);
			if (from < 0 || from > size) return 0;   // (first look, or a new log: counted from now on)
			if (size == from) return 0;
			try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(lg.toFile(), "r")) {
				f.seek(from);
				byte[] b = new byte[(int) Math.min(size - from, 8_000_000)];
				f.readFully(b);
				String t = new String(b, java.nio.charset.StandardCharsets.UTF_8);
				int n = 0, i = 0;
				while ((i = t.indexOf("Can't keep up!", i)) >= 0) { n++; i++; }
				return n;
			}
		} catch (Exception e) { return 0; }
	}

	/** For the server's card: now, and the last hour. */
	static Map<String, Object> summary(String id) {
		ArrayDeque<Sample> q = hour.get(id);
		Map<String, Object> out = new LinkedHashMap<>();
		if (q == null) return out;
		synchronized (q) {
			if (q.isEmpty()) return out;
			Sample last = q.peekLast();
			int cpuPeak = -1, playersPeak = 0, lag = 0; long memPeak = -1;
			for (Sample x : q) { cpuPeak = Math.max(cpuPeak, x.cpu()); memPeak = Math.max(memPeak,
					x.mem()); playersPeak = Math.max(playersPeak, x.players()); lag += x.lag(); }
			out.put("cpu", last.cpu()); out.put("mem", last.mem()); out.put("cpu_peak", cpuPeak); out.put("mem_peak", memPeak);
			out.put("players_peak", playersPeak); out.put("lag", lag); out.put("minutes", q.size());
			List<Object> mems = new java.util.ArrayList<>(); for (Sample x : q) mems.add(x.mem());
			out.put("mem_series", mems);
		}
		return out;
	}
}
