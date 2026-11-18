package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import dev.gatehouse.look.Quiet;

/**
 * R29 (1): Gatekeeper notices when it has frozen, and restarts itself.
 *
 * <p>On 2026-11-17 two of Gatekeeper's parts each waited on the other forever: it kept running, answered nobody, and
 * stayed that way until someone noticed. This runs beside it, holds no lock of its own, and looks twice a minute:
 * <ul>
 *   <li>threads waiting on each other for good (Java can tell exactly; that is what happened then): two looks in a row
 *       that see it, and Gatekeeper restarts;</li>
 *   <li>anything else that leaves it not answering on this machine (/v1/alive, which needs nothing of Gatekeeper): ten
 *       minutes of it, and Gatekeeper restarts. That long, so a slow job (an update being fetched, a pack built) is
 *       never taken for a freeze.</li>
 * </ul>
 * The restart goes the way Gatekeeper is started at sign-in: its scheduled task on Windows (a small script beside it,
 * watchdog-restart.ps1, waits for this one to end, then starts it, directly if there is no task), its service on Linux
 * (which starts it again when it ends with an error). What it saw goes in host.log, written without any lock.
 * Off while a server Gatekeeper runs itself is on (its console would be lost), and under test (OJ_WATCHDOG=off).
 */
final class Watchdog {
	private Watchdog() {}

	static void start() {
		if ("off".equals(System.getenv("OJ_WATCHDOG"))) return;
		long every = 30_000;
		try { every = Math.max(1000, Long.parseLong(System.getenv().getOrDefault("OJ_WATCHDOG_MS",
				"30000"))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
		long ms = every;
		// (testing only: a stand-in Gatekeeper made to freeze the way it did on 2026-11-17, once)
		if (System.getenv("OJ_HOST_DIR") != null && System.getenv("OJ_WATCHDOG_TEST_FREEZE") != null && !Files.exists(dir.resolve("watchdog-test-froze"))) {
			try { Files.writeString(dir.resolve("watchdog-test-froze"), "1"); } catch (IOException ignored) { Quiet.seen(ignored); }
			Object a = new Object(), b = new Object();
			Thread one = new Thread(() -> { synchronized (a) { pause(); synchronized (b) { } } }, "test-one"),
					two = new Thread(() -> { synchronized (b) { pause(); synchronized (a) { } } }, "test-two");
			one.setDaemon(true); two.setDaemon(true); one.start(); two.start();
		}
		Thread t = new Thread(() -> {
			try { Thread.sleep(Math.min(90_000, ms * 3)); } catch (InterruptedException e) { return; }   // (settling in after a start)
			int locked = 0; long silentSince = 0;
			while (true) {
				try {
					long[] stuck = java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads();
					locked = stuck == null ? 0 : locked + 1;
					boolean answers = alive();
					long now = System.currentTimeMillis();
					silentSince = answers ? 0 : silentSince == 0 ? now : silentSince;
					String why = locked >= 2 ? "two of its parts are waiting on each other (" + names(stuck) + ")"
							: silentSince > 0 && now - silentSince > Math.max(ms * 20, 600_000L)
									? "it hasn't answered on this machine for " + (now - silentSince) / 60_000 + " minutes" : null;
					if (why != null) { restart(why); return; }
				} catch (RuntimeException | Error ignored) { /* the next look */ }
				try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
			}
		}, "watchdog");
		t.setDaemon(true);
		t.start();
	}

	private static void pause() { try { Thread.sleep(300); } catch (InterruptedException ignored) { Quiet.seen(ignored); } }

	/** Does this Gatekeeper answer on this machine? (/v1/alive is answered before anything that takes a lock.) */
	static boolean alive() {
		try {
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + port() + "/v1/alive").toURL().openConnection();
			c.setConnectTimeout(5000); c.setReadTimeout(10_000);
			return c.getResponseCode() == 200;
		} catch (IOException | RuntimeException e) { return false; }
	}

	private static String names(long[] ids) {
		if (ids == null) return "";
		StringBuilder s = new StringBuilder();
		for (java.lang.management.ThreadInfo i
				: java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(ids)) if (i != null) s.append(s.length() == 0 ? ""
				: ", ").append(i.getThreadName());
		return s.toString();
	}

	/** A line in host.log without going through log() (whose lock could be among those stuck). */
	static void note(String s) {
		try { Files.writeString(dir.resolve("host.log"),
				LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "  " + s + "\n",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
		catch (IOException ignored) { Quiet.seen(ignored); }
	}

	private static final String SCRIPT = String.join("\r\n",
			"# Made by Gatekeeper's watchdog. When Gatekeeper has frozen, it starts this and ends itself; this waits for it to",
			"# be gone and starts it again: through its scheduled task when there is one, else directly. Values come in GH_WD_*.",
			"$old = [int]$env:GH_WD_PID",
			"try { Wait-Process -Id $old -Timeout 20 -ErrorAction Stop } catch { Stop-Process -Id $old -Force -ErrorAction SilentlyContinue; Start-Sleep 2 }",
			"$task = $env:GH_WD_TASK",
			"$started = $false",
			"if ($task) { $t = Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue; if ($t) { Start-ScheduledTask -TaskName $task; $started = $true } }",
			"if ($started) { Start-Sleep 10; try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', [int]$env:GH_WD_PORT); $c.Close() } catch { $started = $false } }",
			"if (-not $started) { Start-Process -FilePath $env:GH_WD_JAVA -ArgumentList '-jar', ('\"' + $env:GH_WD_JAR + '\"'), '--serve' -WorkingDirectory $env:GH_WD_DIR -WindowStyle Hidden }",
			"");

	static void restart(String why) {
		if (DirectHostRunning()) { note("watchdog: Gatekeeper seems frozen (" + why
				+ "), but a server it runs itself is on, so it isn't restarted: restart Gatekeeper yourself when that server can stop"); return; }
		note("watchdog: Gatekeeper froze (" + why + "): restarting it");
		try {
			Path jar = Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			String java = ProcessHandle.current().info().command().orElse(WIN ? "javaw" : "java");
			if (WIN) {
				Path ps = dir.resolve("watchdog-restart.ps1");
				Files.writeString(ps, SCRIPT, StandardCharsets.UTF_8);
				ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", ps.toString());
				java.util.Map<String, String> env = pb.environment();
				env.put("GH_WD_PID", String.valueOf(ProcessHandle.current().pid())); env.put("GH_WD_JAVA", java); env.put("GH_WD_JAR", jar.toString());
				env.put("GH_WD_DIR", dir.toString()); env.put("GH_WD_PORT", String.valueOf(port()));
				env.put("GH_WD_TASK", System.getenv("OJ_HOST_DIR") == null ? "Gatehouse Keeper" : "");   // (a stand-in under test is never started through the real task)
				pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			} else if (System.getenv("INVOCATION_ID") == null) {
				// (not under its service, which would start it again by itself: a small waiter does it)
				String cmd = "while kill -0 " + ProcessHandle.current().pid() + " 2>/dev/null; do sleep 1; done; cd '"
						+ dir.toString().replace("'", "'\\''") + "' && exec '" + java.replace("'", "'\\''") + "' -jar '" + jar.toString().replace("'", "'\\''") + "' --serve";
				new ProcessBuilder("setsid", "bash", "-c", cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			}
		} catch (Exception e) { note("watchdog: couldn't arrange the restart (" + e + "); ending anyway so whatever started Gatekeeper can start it again"); }
		try { Files.writeString(dir.resolve("watchdog-restarted.txt"), why,
				StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }   // (the next Gatekeeper tells the owner)
		Runtime.getRuntime().halt(3);   // (halt, not exit: nothing may wait on a lock on the way out)
	}

	/** Is a server Gatekeeper runs itself on? Asked on a thread of its own, as it could be among what is stuck. */
	private static boolean DirectHostRunning() {
		java.util.concurrent.CompletableFuture<Boolean> f = java.util.concurrent.CompletableFuture.supplyAsync(() -> { try { return DirectHost.anyTracked(); } catch (RuntimeException e) { return false; } });
		try { return f.get(3, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { return false; }
	}
}
