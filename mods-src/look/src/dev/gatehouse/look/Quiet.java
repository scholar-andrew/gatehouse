package dev.gatehouse.look;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Errors that are expected and passed over on purpose (a device that doesn't answer, a file that isn't there yet, a
 * window that closed mid-way): the program carries on, as it should. Each such place calls {@link #seen} rather than
 * dropping the error without a trace (R36), so when something odd happens it can be looked into.
 *
 * <p>Nothing is written unless debugging is on: a file named debug-on.txt in the program's folder (Gatekeeper's folder,
 * or Gatehouse's home folder), or OJ_DEBUG set. Then each one is a line in debug.log beside it: when, the thread, the
 * place in the code, and the error. The log is started afresh past 1 MB.
 */
public final class Quiet {
	private Quiet() {}

	private static volatile Path log;
	private static volatile boolean on;

	/** Where this program's debug log goes (its folder); debugging is on if debug-on.txt is there, or OJ_DEBUG is set. */
	public static void to(Path folder) {
		if (folder == null) return;
		log = folder.resolve("debug.log");
		on = System.getenv("OJ_DEBUG") != null || Files.exists(folder.resolve("debug-on.txt"));
	}

	/** An error passed over on purpose: noted in debug.log when debugging is on, else nothing. */
	public static void seen(Throwable t) {
		if (!on || log == null || t == null) return;
		try {
			StackTraceElement[] st = new Throwable().getStackTrace();
			String where = st.length > 1 ? st[1].getClassName().replaceAll("^.*\\.", "") + "." + st[1].getMethodName() + ":" + st[1].getLineNumber() : "?";
			String line = Times.logStamp() + "  [" + Thread.currentThread().getName() + "] " + where + "  " + t + "\n";
			synchronized (Quiet.class) {
				if (Files.isRegularFile(log) && Files.size(log) > 1_000_000) Files.delete(log);
				Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		} catch (IOException | RuntimeException noLog) { /* debugging can't stop the program */ }
	}
}
