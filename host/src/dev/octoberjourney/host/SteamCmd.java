package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SteamCMD, Valve's own tool for installing and updating game servers by their Steam app id (R34 stage 2). Gatekeeper
 * fetches it from Valve the first time a server needs it, and only when the owner has pressed Make the server (or
 * Update) for a game that is installed this way: never by itself. It lives in Gatekeeper's folder (steamcmd/) and
 * keeps itself up to date, as Valve has it. It signs in anonymously, which is how dedicated servers are given out.
 * host.json "steamcmd" (or OJ_STEAMCMD, for testing) names another copy to use instead.
 */
final class SteamCmd {
	private SteamCmd() {}
	static final String WIN_URL = "https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip",
			LINUX_URL = "https://steamcdn-a.akamaihd.net/client/installer/steamcmd_linux.tar.gz";

	/** Where its program is (whether or not it is there yet). */
	static Path program() {
		String set = System.getenv("OJ_STEAMCMD");
		if (set == null || set.isEmpty()) set = setting("steamcmd", "");
		if (!set.isEmpty()) return Path.of(set);
		return dir.resolve("steamcmd").resolve(WIN ? "steamcmd.exe" : "steamcmd.sh");
	}
	static boolean here() { return Files.isRegularFile(program()); }

	/** Fetches SteamCMD from Valve when it isn't here yet. */
	static void ensure(Consumer<String> step) throws IOException {
		if (here()) return;
		Path home = dir.resolve("steamcmd");
		Files.createDirectories(home);
		step.accept("Fetching SteamCMD from Valve");
		Path archive = home.resolve(WIN ? "steamcmd.zip" : "steamcmd_linux.tar.gz");
		ServerTools.fetchTo(WIN ? WIN_URL : LINUX_URL, archive, "SHA-256");
		if (WIN) {
			try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
				for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
					Path to = home.resolve(e.getName()).normalize();
					if (!to.startsWith(home) || e.isDirectory()) continue;
					Files.createDirectories(to.getParent());
					Files.copy(z, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} else {
			Process p = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", home.toString()).redirectErrorStream(true).start();
			try { if (p.waitFor() != 0) throw new IOException("its archive couldn't be unpacked"); } catch (InterruptedException e) { throw new IOException("interrupted"); }
		}
		Files.deleteIfExists(archive);
		if (!here()) throw new IOException("SteamCMD wasn't in what Valve sent");
	}

	/** Installs or updates app into folder d (anonymously). step hears how it goes; the last lines are in the exception. */
	static void install(int app, Path d, Consumer<String> step) throws IOException {
		ensure(step);
		Files.createDirectories(d);
		List<String> cmd = new ArrayList<>(List.of(program().toString(), "+force_install_dir", d.toAbsolutePath().toString(), "+login", "anonymous",
				"+app_update", String.valueOf(app), "validate", "+quit"));
		if (!WIN && program().toString().endsWith(".sh")) cmd.add(0, "bash");
		step.accept("SteamCMD is installing the server (app " + app + "); a first install can take some minutes");
		Process p = new ProcessBuilder(cmd).directory(program().getParent().toFile()).redirectErrorStream(true).start();
		java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
		boolean ok = false;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = in.readLine()) != null; ) {
				String l = line.trim();
				if (l.isEmpty()) continue;
				last.addLast(l); if (last.size() > 4) last.removeFirst();
				if (l.startsWith("Success! App")) ok = true;
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("progress: ([\\d.]+)").matcher(l);
				if (m.find()) step.accept("SteamCMD is installing the server: " + m.group(1).replaceAll("\\.\\d+$", "") + "%");
			}
		}
		int code;
		try { code = p.waitFor(); } catch (InterruptedException e) { throw new IOException("interrupted"); }
		if (!ok || code != 0) throw new IOException("SteamCMD didn't finish (" + String.join(" / ", last) + ")");
	}
	@SuppressWarnings("unused") private static String text(InputStream in) throws IOException { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
}
