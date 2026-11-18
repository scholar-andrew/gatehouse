package dev.gatehouse.look;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The media engine for chat's videos and sounds (R62): OpenJFX 21.0.6 (base, graphics, media), Java's own media
 * player, fetched once per device from Maven Central the first time someone presses Play, and kept. Each file is
 * checked against the SHA-256 written here, inside the signed program, before it is kept and again before it is used,
 * so what runs is exactly what was checked when this version was made.
 *
 * Where it is kept: Windows %LOCALAPPDATA%\Gatehouse\media-engine\21.0.6; Linux and SteamOS
 * ~/.local/share/gatehouse/media-engine/21.0.6 (XDG_DATA_HOME if set). About 7.8 MB on Windows, 6.9 MB on Linux.
 * Only Windows and Linux on x86-64 have one; elsewhere a video or sound opens with the device's own program, as before.
 *
 * The player (dev.gatehouse.media.Player, built against OpenJFX) is inside the program as media-player.jar and is
 * loaded with the engine in a class loader of their own, so nothing of OpenJFX is touched until Play is pressed.
 * For tests only: GATEHOUSE_MEDIA_DIR (where it is kept) and GATEHOUSE_MEDIA_SITE (where it is fetched from; the
 * checksums still decide what is kept).
 */
public final class MediaEngine {
	private MediaEngine() {}
	public static final String VERSION = "21.0.6";
	private static final String SITE = "https://repo1.maven.org/maven2/org/openjfx";
	private record Part(String module, String file, long size, String sha256) {}
	private static final List<Part> WIN = List.of(
			new Part("javafx-base", "javafx-base-21.0.6-win.jar", 753980, "618a1149089d1459cc569cc10eac05c5589a532473d09f35c1e9ea2c5b6de50e"),
			new Part("javafx-graphics", "javafx-graphics-21.0.6-win.jar", 5942324, "4b0981384551f39a0ca9679672f2abbedd9d0966763205534fca230b1cf314a5"),
			new Part("javafx-media", "javafx-media-21.0.6-win.jar", 1123076, "59fbb279c303eb9a4598ca88228884bbc267f287769b6d22af8e65b923af25a6"));
	private static final List<Part> LINUX = List.of(
			new Part("javafx-base", "javafx-base-21.0.6-linux.jar", 753988, "6477356238dd9aa77cf3dae4481269dab97582008d2ccabd90d88b5045e7c4c0"),
			new Part("javafx-graphics", "javafx-graphics-21.0.6-linux.jar", 4697444, "8961089f35014d104d20a246f534cf82cfb377d07630ac3cdb742102082215e3"),
			new Part("javafx-media", "javafx-media-21.0.6-linux.jar", 1511576, "2a65dfc998dc898a8ca15984d2861084fd03e093dc910778a10715109d0d8255"));
	private static ClassLoader loaded;

	/** This device's parts, or null where there is no engine (another system, or not x86-64). */
	private static List<Part> parts() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!(arch.equals("amd64") || arch.equals("x86_64"))) return null;
		return os.startsWith("windows") ? WIN : os.startsWith("linux") ? LINUX : null;
	}
	/** Can this device have the engine at all? */
	public static boolean available() { return parts() != null && Player.class.getResource("media-player.jar") != null; }
	/** How big the fetch is, in MB, for asking first. */
	public static String size() {
		long n = 0; for (Part p : parts()) n += p.size();
		return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
	}
	/** Files this player can play; others open with the device's own program. */
	public static boolean plays(String name) {
		return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|m4a|mp3|wav|aiff?)$");
	}
	public static boolean isVideo(String name) { return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v)$"); }

	private static Path home() {
		String set = System.getenv("GATEHOUSE_MEDIA_DIR");
		if (set != null && !set.isEmpty()) return Path.of(set, VERSION);
		String h = System.getProperty("user.home", "");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			String la = System.getenv("LOCALAPPDATA");
			return Path.of(la == null || la.isEmpty() ? h : la, "Gatehouse", "media-engine", VERSION);
		}
		String xd = System.getenv("XDG_DATA_HOME");
		return Path.of(xd == null || xd.isEmpty() ? Path.of(h, ".local", "share").toString() : xd, "gatehouse", "media-engine", VERSION);
	}
	private static String sha256(Path f) throws IOException {
		try (InputStream in = Files.newInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] b = new byte[65536];
			for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
			StringBuilder s = new StringBuilder();
			for (byte x : md.digest()) s.append(String.format("%02x", x));
			return s.toString();
		} catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
	}
	/** Is it here, whole? (sizes only; the checksums are checked when it is loaded) */
	public static boolean ready() {
		List<Part> ps = parts();
		if (ps == null) return false;
		try { for (Part p : ps) if (Files.size(home().resolve(p.file())) != p.size()) return false; return true; }
		catch (IOException e) { return false; }
	}

	/** Fetches what is missing; progress gets short sentences. Blocking: call it off the window's thread. */
	public static void fetch(Consumer<String> progress) throws IOException {
		List<Part> ps = parts();
		if (ps == null) throw new IOException("there is no media player for this system");
		Path d = home();
		Files.createDirectories(d);
		String site = System.getenv().getOrDefault("GATEHOUSE_MEDIA_SITE", SITE).replaceAll("/+$", "");
		int i = 0;
		for (Part p : ps) {
			i++;
			Path to = d.resolve(p.file());
			if (Files.isRegularFile(to) && Files.size(to) == p.size() && sha256(to).equals(p.sha256())) continue;
			progress.accept("Fetching the media player (" + i + " of " + ps.size() + ", " + (p.size() / 1048576 + 1) + " MB)…");
			Path part = d.resolve(p.file() + ".part");
			try {
				HttpURLConnection c = (HttpURLConnection) URI.create(site + "/" + p.module() + "/" + VERSION + "/" + p.file()).toURL().openConnection();
				c.setConnectTimeout(10_000); c.setReadTimeout(60_000);
				c.setRequestProperty("User-Agent", "Gatehouse (media player)");
				if (c.getResponseCode() != 200) throw new IOException("Maven Central answered " + c.getResponseCode());
				try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
					byte[] b = new byte[65536]; long n = 0;
					for (int r; (r = in.read(b)) > 0; ) { n += r; if (n > p.size()) throw new IOException(p.file() + " is bigger than it should be"); out.write(b, 0, r); }
				}
				if (!sha256(part).equals(p.sha256())) throw new IOException(p.file() + " didn't match its checksum, so it wasn't kept");
				Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
			} finally { Files.deleteIfExists(part); }
		}
	}

	/** Loads the engine and the player once (checksums checked), and opens a player window for a file. */
	public static synchronized void play(Path file, String title, Runnable failed) throws Exception {
		if (loaded == null) {
			List<URL> urls = new ArrayList<>();
			Path d = home();
			for (Part p : parts()) {
				Path f = d.resolve(p.file());
				if (!sha256(f).equals(p.sha256())) { Files.deleteIfExists(f); throw new IOException(p.file() + " changed since it was fetched, so it was removed: press Play again to fetch it afresh"); }
				urls.add(f.toUri().toURL());
			}
			Path pj = d.resolve("media-player-" + Integer.toHexString(java.util.Arrays.hashCode(own())) + ".jar");
			if (!Files.isRegularFile(pj)) Files.write(pj, own());
			urls.add(pj.toUri().toURL());
			System.setProperty("javafx.cachedir", d.resolve("cache").toString());
			loaded = new URLClassLoader(urls.toArray(new URL[0]), MediaEngine.class.getClassLoader());
		}
		Class<?> c = Class.forName("dev.gatehouse.media.Player", true, loaded);
		c.getMethod("open", Path.class, String.class, boolean.class, Runnable.class).invoke(null, file, title, isVideo(title), failed);
	}
	private static byte[] own() throws IOException {
		try (InputStream in = Player.class.getResourceAsStream("media-player.jar")) {
			if (in == null) throw new IOException("the player isn't in this copy of the program");
			return in.readAllBytes();
		}
	}
	/** (a class beside media-player.jar, to find it by) */
	private static final class Player {}
}
