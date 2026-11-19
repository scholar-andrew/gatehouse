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
 * The media engine for chat's videos and sounds (R62): OpenJFX (base, graphics, media, swing), Java's own media
 * player, fetched once per device from Maven Central the first time someone presses Play, and kept. Which OpenJFX
 * depends on the Java the program runs on: 23.0.2 on Java 21 or newer (it plays video with FFmpeg 7, which a handheld's
 * Flatpak runtime has), 21.0.6 on older Java (FFmpeg up to 6). Each file is
 * checked against the SHA-256 written here, inside the signed program, before it is kept and again before it is used,
 * so what runs is exactly what was checked when this version was made.
 *
 * Where it is kept: Windows %LOCALAPPDATA%\Gatehouse\media-engine\<version>; Linux and SteamOS
 * ~/.local/share/gatehouse/media-engine/<version> (XDG_DATA_HOME if set; inside a Flatpak, the Flatpak's own data
 * folder). About 7.5 MB on Windows, 6.9 MB on Linux.
 * What went wrong when something can't play is written to player.log in that folder (the chat shows it too).
 * Only Windows and Linux on x86-64 have one; elsewhere a video or sound opens with the device's own program, as before.
 *
 * The player (dev.gatehouse.media.Player, built against OpenJFX) is inside the program as media-player.jar and is
 * loaded with the engine in a class loader of their own, so nothing of OpenJFX is touched until Play is pressed. It is a
 * Swing component placed in the chat message itself (no window of its own).
 * For tests only: GATEHOUSE_MEDIA_DIR (where it is kept) and GATEHOUSE_MEDIA_SITE (where it is fetched from; the
 * checksums still decide what is kept).
 */
public final class MediaEngine {
	private MediaEngine() {}
	/** 23.0.2 needs Java 21; older Java keeps 21.0.6. */
	public static final String VERSION = Runtime.version().feature() >= 21 ? "23.0.2" : "21.0.6";
	private static final String SITE = "https://repo1.maven.org/maven2/org/openjfx";
	private record Part(String module, String file, long size, String sha256) {}
	private static final List<Part> WIN = List.of(
			new Part("javafx-base", "javafx-base-21.0.6-win.jar", 753980, "618a1149089d1459cc569cc10eac05c5589a532473d09f35c1e9ea2c5b6de50e"),
			new Part("javafx-graphics", "javafx-graphics-21.0.6-win.jar", 5942324, "4b0981384551f39a0ca9679672f2abbedd9d0966763205534fca230b1cf314a5"),
			new Part("javafx-media", "javafx-media-21.0.6-win.jar", 1123076, "59fbb279c303eb9a4598ca88228884bbc267f287769b6d22af8e65b923af25a6"),
			new Part("javafx-swing", "javafx-swing-21.0.6-win.jar", 89706, "51ca5c40ef4189f3aebdee2a6ebd3b9276bfa496cf2bbe2aeabffc6eb8ca8b1d"));
	private static final List<Part> LINUX = List.of(
			new Part("javafx-base", "javafx-base-21.0.6-linux.jar", 753988, "6477356238dd9aa77cf3dae4481269dab97582008d2ccabd90d88b5045e7c4c0"),
			new Part("javafx-graphics", "javafx-graphics-21.0.6-linux.jar", 4697444, "8961089f35014d104d20a246f534cf82cfb377d07630ac3cdb742102082215e3"),
			new Part("javafx-media", "javafx-media-21.0.6-linux.jar", 1511576, "2a65dfc998dc898a8ca15984d2861084fd03e093dc910778a10715109d0d8255"),
			new Part("javafx-swing", "javafx-swing-21.0.6-linux.jar", 89706, "bf56964326859dea8b9d302cf57e8a9f5f72741dea27f0887cbc029641f7e38c"));
	private static final List<Part> WIN23 = List.of(
			new Part("javafx-base", "javafx-base-23.0.2-win.jar", 756716, "72f8958b4a552fca14ab3628c672f399aa68bbe6b4cd45107fe174b4670c7cb6"),
			new Part("javafx-graphics", "javafx-graphics-23.0.2-win.jar", 6034746, "6b5b3041f2735508a8aad23c5795e745d5bf78a0a6aa920bae43ab41ec22d8c2"),
			new Part("javafx-media", "javafx-media-23.0.2-win.jar", 1137676, "eb2ae99397333094172ad73c7689b6a48ae59b2b7a0d80d571521214f4a770ec"),
			new Part("javafx-swing", "javafx-swing-23.0.2-win.jar", 91164, "875d14a5c2cf8ce04e5c48c39b5c9a3ff1d794820b8a473ec010af1c631afcb3"));
	private static final List<Part> LINUX23 = List.of(
			new Part("javafx-base", "javafx-base-23.0.2-linux.jar", 756716, "93cf67fe6f0e2c0b6f6b3134aa218b576b144cb5e114d210651b2594a9d15640"),
			new Part("javafx-graphics", "javafx-graphics-23.0.2-linux.jar", 4839668, "809376faff637c5bbbacbdff710e2e1f9d523c1e344020124a2735aca04daa28"),
			new Part("javafx-media", "javafx-media-23.0.2-linux.jar", 1555185, "b842a02e08221793615bac8bc3bfeb1be5e079474fbf6e815b45571fdf0c4a34"),
			new Part("javafx-swing", "javafx-swing-23.0.2-linux.jar", 91164, "c55286b39f996e53390920a9839293c74eb360650ed38bbe6ecc7fb74ea5160c"));
	// R85: the web view (OpenJFX's web and controls parts), for playing YouTube in the chat. Only with 23.0.2 (Java 21 or
	// newer), fetched once, when a YouTube video is first played here, asked first; the media player's parts come too.
	private static final List<Part> WEB_WIN = List.of(
			new Part("javafx-controls", "javafx-controls-23.0.2-win.jar", 2605863, "874daf48b0ec01e4eb0caf24cdb85b32d951999837f8e1c285f6d1e1e34870ba"),
			new Part("javafx-web", "javafx-web-23.0.2-win.jar", 32582307, "a18008ae0d3df1b8046a99d44f761dd01cce279ae1539a7e4685263aec2ea5fc"));
	private static final List<Part> WEB_LINUX = List.of(
			new Part("javafx-controls", "javafx-controls-23.0.2-linux.jar", 2605899, "2c10d1e7e9354e1cd573bde2e47a16c72cfb8e86604830ac29cd2b65523fd1ec"),
			new Part("javafx-web", "javafx-web-23.0.2-linux.jar", 42838508, "c06f091ee6413efe1deb707f0487c99a254661ded162dc13a96b3f36ef578605"));
	private static ClassLoader loaded;
	private static boolean loadedWithWeb;

	/** This device's parts, or null where there is no engine (another system, or not x86-64). */
	private static List<Part> parts() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		if (!(arch.equals("amd64") || arch.equals("x86_64"))) return null;
		boolean newer = VERSION.equals("23.0.2");
		return os.startsWith("windows") ? (newer ? WIN23 : WIN) : os.startsWith("linux") ? (newer ? LINUX23 : LINUX) : null;
	}
	/** R85: the web view's parts here, or null where there is none (older Java, another system). */
	private static List<Part> webParts() {
		if (!VERSION.equals("23.0.2") || parts() == null) return null;
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? WEB_WIN : WEB_LINUX;
	}
	/** Can YouTube be played in the chat on this device (once the web view is fetched)? */
	public static boolean webAvailable() { return webParts() != null && Player.class.getResource("web-player.jar") != null; }
	/** Is the web view here, whole, with the media player's parts? */
	public static boolean webReady() {
		List<Part> ps = webParts();
		if (ps == null || !ready()) return false;
		try { for (Part p : ps) if (Files.size(home().resolve(p.file())) != p.size()) return false; return true; }
		catch (IOException e) { return false; }
	}
	/** How much fetching the web view (and the media player, if it isn't here yet) is, for asking first. */
	public static String webSize() {
		long n = 0;
		for (Part p : webParts()) if (!Files.isRegularFile(home().resolve(p.file()))) n += p.size();
		if (!ready()) for (Part p : parts()) if (!Files.isRegularFile(home().resolve(p.file()))) n += p.size();
		return String.format(Locale.ROOT, "%.0f MB", n / 1048576.0);
	}
	/** Fetches the media player's parts and the web view's (what is missing). Blocking: off the window's thread. */
	public static void fetchWeb(Consumer<String> progress) throws IOException {
		fetch(progress);
		List<Part> ps = webParts();
		if (ps == null) throw new IOException("YouTube can't be played in the chat on this device (it needs Java 21 or newer)");
		fetchParts(ps, "the web view", progress);
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

	private static Path home() { return home(VERSION); }
	private static Path home(String version) {
		String set = System.getenv("GATEHOUSE_MEDIA_DIR");
		if (set != null && !set.isEmpty()) return Path.of(set, version);
		String h = System.getProperty("user.home", "");
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
			String la = System.getenv("LOCALAPPDATA");
			return Path.of(la == null || la.isEmpty() ? h : la, "Gatehouse", "media-engine", version);
		}
		String xd = System.getenv("XDG_DATA_HOME");
		return Path.of(xd == null || xd.isEmpty() ? Path.of(h, ".local", "share").toString() : xd, "gatehouse", "media-engine", version);
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

	/** Has this device fetched the player before (some of its parts are here)? Then a missing new part is fetched without asking again. */
	public static boolean fetchedBefore() {
		List<Part> ps = parts();
		if (ps == null) return false;
		for (Part p : ps) if (Files.isRegularFile(home().resolve(p.file()))) return true;
		// (an earlier engine fetched with the owner's yes counts too: the newer one replaces it without asking again)
		try (var old = Files.list(home("21.0.6"))) { if (!VERSION.equals("21.0.6") && old.anyMatch(f -> f.getFileName().toString().startsWith("javafx-"))) return true; }
		catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		return false;
	}

	/** Fetches what is missing; progress gets short sentences. Blocking: call it off the window's thread. */
	public static void fetch(Consumer<String> progress) throws IOException {
		List<Part> ps = parts();
		if (ps == null) throw new IOException("there is no media player for this system");
		fetchParts(ps, "the media player", progress);
	}
	private static void fetchParts(List<Part> ps, String what, Consumer<String> progress) throws IOException {
		Path d = home();
		Files.createDirectories(d);
		String site = System.getenv().getOrDefault("GATEHOUSE_MEDIA_SITE", SITE).replaceAll("/+$", "");
		int i = 0;
		for (Part p : ps) {
			i++;
			Path to = d.resolve(p.file());
			if (Files.isRegularFile(to) && Files.size(to) == p.size() && sha256(to).equals(p.sha256())) continue;
			progress.accept("Fetching " + what + " (" + i + " of " + ps.size() + ", " + (p.size() / 1048576 + 1) + " MB)…");
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

	/** Loads the engine and the player once (checksums checked). */
	private static synchronized ClassLoader load() throws Exception {
		if (loaded != null) return loaded;
		List<URL> urls = new ArrayList<>();
		Path d = home();
		for (Part p : parts()) {
			Path f = d.resolve(p.file());
			if (!Files.isRegularFile(f) || !sha256(f).equals(p.sha256())) {
				Files.deleteIfExists(f);
				throw new IOException(p.file() + " is missing or changed since it was fetched: press Play again to fetch it afresh");
			}
			urls.add(f.toUri().toURL());
		}
		Path pj = d.resolve("media-player-" + Integer.toHexString(java.util.Arrays.hashCode(own())) + ".jar");
		if (!Files.isRegularFile(pj)) Files.write(pj, own());
		urls.add(pj.toUri().toURL());
		// (R85: the web view joins when it has been fetched: JavaFX can be loaded only once in a run, so both go together)
		if (webAvailable() && webReady()) {
			boolean whole = true;
			List<URL> web = new ArrayList<>();
			for (Part p : webParts()) { Path f = d.resolve(p.file()); if (!sha256(f).equals(p.sha256())) { Files.deleteIfExists(f); whole = false; } else web.add(f.toUri().toURL()); }
			if (whole) {
				byte[] wj = ownWeb();
				Path wp = d.resolve("web-player-" + Integer.toHexString(java.util.Arrays.hashCode(wj)) + ".jar");
				if (!Files.isRegularFile(wp)) Files.write(wp, wj);
				web.add(wp.toUri().toURL());
				urls.addAll(web);
				loadedWithWeb = true;
			}
		}
		System.setProperty("javafx.cachedir", d.resolve("cache").toString());
		// R80: on Linux (the Steam Deck) the player's picture is drawn off-screen and copied into the window every frame,
		// often by the processor alone; video then slowed and its sound broke up. Fewer copies (30 a second, which a
		// chat video doesn't need more than), the graphics chip first when it can, and a log line saying which it used.
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
			if (System.getProperty("javafx.animation.pulse") == null) System.setProperty("javafx.animation.pulse", "30");
			if (System.getProperty("prism.order") == null) System.setProperty("prism.order", "es2,sw");
			if (System.getProperty("prism.vsync") == null) System.setProperty("prism.vsync", "false");
			System.setProperty("prism.verbose", "true");   // (says which pipeline it chose, on the program's own output)
			// (R80, second try: the player's sound goes through ALSA to PipeWire, whose small default buffer crackled and
			// dragged the video. A start script's setting doesn't reach Gatehouse inside Prism's Flatpak, so Gatehouse sets
			// it in its own process, before the sound library starts. Whatever the device already set wins.)
			boolean pw = NativeEnv.setIfUnset("PIPEWIRE_LATENCY", "2048/48000"), pa = NativeEnv.setIfUnset("PULSE_LATENCY_MSEC", "60");
			log("sound buffer settings: PIPEWIRE_LATENCY " + NativeEnv.get("PIPEWIRE_LATENCY") + (pw ? "" : " (couldn't be set)") + ", PULSE_LATENCY_MSEC "
					+ NativeEnv.get("PULSE_LATENCY_MSEC") + (pa ? "" : " (couldn't be set)"));
		}
		log("loading the player: Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version")
				+ " " + System.getProperty("os.arch") + ", display " + System.getenv("DISPLAY") + ", wayland " + System.getenv("WAYLAND_DISPLAY"));
		loaded = new URLClassLoader(urls.toArray(new URL[0]), MediaEngine.class.getClassLoader());
		return loaded;
	}

	/**
	 * A player for a file, to put in the chat message (call on Swing's thread). problem gets a sentence when it can't
	 * play there; the reason is also written to player.log beside the engine.
	 */
	public static java.awt.Component embed(Path file, String name, Consumer<String> problem) throws Exception {
		try {
			// (R80: on Linux, HE-AAC sound otherwise plays an octave low and skips; see HeAac. file is the chat's own copy)
			if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")
					&& name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|m4a|m4v|mov)$")) { String did = HeAac.fix(file); if (!did.isEmpty()) log(name + ": " + did); }
			Class<?> c = Class.forName("dev.gatehouse.media.Player", true, load());
			Consumer<String> told = why -> { log("can't play " + name + ": " + why); problem.accept(why); };
			return (java.awt.Component) c.getMethod("embed", Path.class, String.class, boolean.class, Consumer.class).invoke(null, file, name, isVideo(name), told);
		} catch (Throwable e) {
			Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
			java.io.StringWriter sw = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(sw));
			log("the player didn't start for " + name + ":\n" + sw);
			throw t instanceof Exception x ? x : new Exception(t.toString(), t);
		}
	}

	/**
	 * R85: a YouTube video playing in the chat message, in the web view (call on Swing's thread). id: its 11-letter id.
	 * problem gets a sentence when it can't. If the media player was started earlier in this run without the web view
	 * (it was fetched afterwards), the web view can't join until Gatehouse starts again: that is said.
	 */
	public static java.awt.Component embedYouTube(String id, Consumer<String> problem) throws Exception {
		if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IOException("that isn't a YouTube video's address");
		ClassLoader l = load();
		if (!loadedWithWeb) throw new IOException("the web view was fetched after the player started in this run: close this program and open it again, then press Play here");
		try {
			Class<?> c = Class.forName("dev.gatehouse.web.WebPlayer", true, l);
			Consumer<String> told = why -> { log("can't play YouTube " + id + ": " + why); problem.accept(why); };
			return (java.awt.Component) c.getMethod("youtube", String.class, Consumer.class).invoke(null, id, told);
		} catch (java.lang.reflect.InvocationTargetException e) { Throwable t = e.getCause(); log("the web view didn't start: " + t); throw t instanceof Exception x ? x : new Exception(String.valueOf(t), t); }
	}

	/** R80: told each measurement the player makes (Gatehouse sends it to its Gatekeepers, for the owner's log). */
	public static volatile Consumer<String> onReport;
	/** Called by the player (by name, from its own class loader): one line about how a video played. */
	public static void report(String line) {
		log(line);
		Consumer<String> r = onReport;
		if (r != null) try { r.accept(line); } catch (RuntimeException e) { Quiet.seen(e); }
	}

	/** One line (or a stack trace) in player.log beside the engine; the log is kept under 256 KB. */
	public static void log(String line) {
		try {
			Path d = home(), f = d.resolve("player.log");
			Files.createDirectories(d);
			if (Files.isRegularFile(f) && Files.size(f) > 256 * 1024) Files.move(f, d.resolve("player.old.log"), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(f, java.time.OffsetDateTime.now().withNano(0) + " " + line + System.lineSeparator(), java.nio.charset.StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
	}
	private static byte[] own() throws IOException {
		try (InputStream in = Player.class.getResourceAsStream("media-player.jar")) {
			if (in == null) throw new IOException("the player isn't in this copy of the program");
			return in.readAllBytes();
		}
	}
	private static byte[] ownWeb() throws IOException {
		try (InputStream in = Player.class.getResourceAsStream("web-player.jar")) {
			if (in == null) throw new IOException("the web view's part isn't in this copy of the program");
			return in.readAllBytes();
		}
	}
	/** (a class beside media-player.jar, to find it by) */
	private static final class Player {}
}
