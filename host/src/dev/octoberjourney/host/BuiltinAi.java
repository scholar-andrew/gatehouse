package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

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
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The built-in AI (R66): Gatekeeper's own AI for machines without Ollama. Opt-in and opt-out: nothing is fetched until
 * the owner presses Get the built-in AI (and says yes to the sizes), and Remove the built-in AI deletes it again.
 *
 *   engine  llama-4.2.0.jar (llama.cpp for Java, de.kherud:llama, 12.2 MB) from Maven Central
 *   model   qwen2.5-1.5b-instruct-q4_k_m.gguf (Qwen2.5 1.5B Instruct, Apache 2.0, 1.12 GB) from Qwen's Hugging Face page
 * Both are checked against the SHA-256 written here, inside the signed program, before they are kept and once more
 * before first use, and kept in Gatekeeper's folder under ai/. While answering it uses about 1.5 GB of memory and the
 * processor; the model is unloaded after 10 minutes without a question.
 *
 * Handheld PCs (Steam Deck, ROG Ally, Legion Go, MSI Claw, GPD, AYANEO, OneXPlayer and similar) don't get it: the
 * download and the work are too heavy for a battery, so the controls stay listed and greyed with the reason. Other
 * machines running on battery are warned before the download. Tests: OJ_DEVICE_KIND=handheld|battery|desktop
 * overrides the check, OJ_AI_SITE serves both files from a stand-in (the checksums still decide).
 */
final class BuiltinAi {
	private BuiltinAi() {}
	private record Part(String name, String url, long size, String sha256) {}
	private static final Part ENGINE = new Part("llama-4.2.0.jar", "https://repo1.maven.org/maven2/de/kherud/llama/4.2.0/llama-4.2.0.jar",
			12207002L, "f8ed8a8420f0648dac695514d03bf627e8c5c8e31edcbbfd0ecee31a71dec0a9");
	private static final Part MODEL = new Part("qwen2.5-1.5b-instruct-q4_k_m.gguf",
			"https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
			1117320736L, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e");
	static final String MODEL_NAME = "Qwen2.5 1.5B (built in)";
	private static volatile String fetching = "";   // (what the download is doing now, "" when nothing)
	private static ClassLoader loader;
	private static boolean checked;
	private static volatile long lastUse;
	private static String[] device;   // {kind: handheld|battery|desktop, what it is}

	static Path folder() { return dir.resolve("ai"); }
	private static Path at(Part p) { return folder().resolve(p.name()); }
	static String sizes() { return "12.2 MB for the engine and 1.12 GB for the model (about 1.15 GB in all)"; }

	// ---- what kind of machine this is

	/** {kind, what}: "handheld" for handheld gaming PCs, "battery" for other machines with a battery, else "desktop". */
	static synchronized String[] device() {
		if (device != null) return device;
		String forced = System.getenv("OJ_DEVICE_KIND");
		if (forced != null && !forced.isEmpty()) return device = new String[] {forced, "set for a test"};
		String what = "", battery = "0";
		try {
			if (WIN) {
				Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
						"$c=Get-CimInstance Win32_ComputerSystem; $c.Manufacturer; $c.Model; $c.SystemFamily; (Get-CimInstance Win32_Battery | Measure-Object).Count")
						.redirectErrorStream(true).start();
				String[] lines = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip().split("\\R");
				p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
				what = String.join(" ", java.util.Arrays.copyOf(lines, Math.max(0, lines.length - 1))).strip();
				battery = lines.length == 0 ? "0" : lines[lines.length - 1].strip();
			} else {
				StringBuilder b = new StringBuilder();
				for (String f : new String[] {"sys_vendor", "product_name", "product_family", "board_name"}) {
					Path p = Path.of("/sys/class/dmi/id", f);
					if (Files.isReadable(p)) b.append(Files.readString(p).strip()).append(' ');
				}
				what = b.toString().strip();
				try (var s = Files.list(Path.of("/sys/class/power_supply"))) { battery = s.anyMatch(x -> x.getFileName().toString().startsWith("BAT")) ? "1" : "0"; }
			}
		} catch (IOException | RuntimeException | InterruptedException e) { dev.gatehouse.look.Quiet.seen(e); }
		String w = what.toLowerCase(Locale.ROOT);
		boolean handheld = SYSTEM.equals("steamos")
				|| w.matches(".*\\b(jupiter|galileo)\\b.*") && w.contains("valve")
				|| w.contains("rog ally") || w.matches(".*\\brc7[12]l.*")
				|| w.contains("legion go") || w.contains("claw") && w.contains("micro-star") || w.contains("msi claw")
				|| w.startsWith("gpd") || w.contains(" gpd ") || w.contains("ayaneo") || w.contains("ayadevice")
				|| w.contains("one-netbook") || w.contains("onexplayer") || w.contains("aokzoe") || w.contains("zotac gaming zone");
		String kind = handheld ? "handheld" : "0".equals(battery) || battery.isEmpty() ? "desktop" : "battery";
		return device = new String[] {kind, what.isEmpty() ? "this machine" : what};
	}
	/** Why the built-in AI can't be on this machine, or "". */
	static String notHere() {
		if (device()[0].equals("handheld"))
			return "Not supported on handheld PCs (Steam Deck, ROG Ally, Legion Go and similar): the 1.15 GB download and the work the AI does are too heavy for their battery and memory.";
		String arch = System.getProperty("os.arch", "");
		if (!(arch.equals("amd64") || arch.equals("x86_64"))) return "Not supported on this kind of processor (" + arch + ").";
		if (!(WIN || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux"))) return "Not supported on this system.";
		return "";
	}
	static boolean onBattery() { return device()[0].equals("battery"); }

	// ---- getting it and removing it

	static boolean installed() {
		if (Files.exists(folder().resolve(PENDING))) return false;
		try { return Files.size(at(ENGINE)) == ENGINE.size() && Files.size(at(MODEL)) == MODEL.size(); }
		catch (IOException e) { return false; }
	}
	static String fetching() { return fetching; }

	private static String sha256(Path f) throws IOException {
		try (InputStream in = Files.newInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] b = new byte[1 << 20];
			for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
			StringBuilder s = new StringBuilder();
			for (byte x : md.digest()) s.append(String.format("%02x", x));
			return s.toString();
		} catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
	}
	/** Fetches what is missing (engine, then model), each checked before it is kept. Blocking. */
	static synchronized void fetch() throws IOException {
		String why = notHere();
		if (!why.isEmpty()) throw new IOException(why);
		Files.createDirectories(folder());
		if (Files.deleteIfExists(folder().resolve(PENDING))) checked = false;
		String site = System.getenv("OJ_AI_SITE");
		try {
			for (Part p : new Part[] {ENGINE, MODEL}) {
				Path to = at(p);
				if (Files.isRegularFile(to) && Files.size(to) == p.size()) continue;
				String url = site == null || site.isEmpty() ? p.url() : site.replaceAll("/+$", "") + "/" + p.name();
				Path part = folder().resolve(p.name() + ".part");
				try {
					HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
					c.setConnectTimeout(15_000); c.setReadTimeout(120_000); c.setInstanceFollowRedirects(true);
					c.setRequestProperty("User-Agent", "Gatehouse-Gatekeeper/" + ownVersion() + " (built-in AI)");
					if (c.getResponseCode() != 200) throw new IOException(p.name() + ": the site answered " + c.getResponseCode());
					long n = 0, nextNote = 0;
					try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
						byte[] b = new byte[1 << 16];
						for (int r; (r = in.read(b)) > 0; ) {
							n += r;
							if (n > p.size()) throw new IOException(p.name() + " is bigger than it should be");
							out.write(b, 0, r);
							if (n >= nextNote) { fetching = "Fetching " + (p == ENGINE ? "the engine" : "the model") + ": " + (n * 100 / p.size()) + "% of " + mb(p.size()); nextNote = n + p.size() / 50; }
						}
					}
					fetching = "Checking " + p.name() + "…";
					if (!sha256(part).equals(p.sha256())) throw new IOException(p.name() + " didn't match its checksum, so it wasn't kept");
					Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
				} finally { Files.deleteIfExists(part); }
			}
			checked = true;
			log("AI helper: the built-in AI was fetched and checked (" + MODEL_NAME + ")");
		} finally { fetching = ""; }
	}
	private static String mb(long n) { return n >= 1 << 30 ? String.format(Locale.ROOT, "%.2f GB", n / (double) (1 << 30)) : String.format(Locale.ROOT, "%.1f MB", n / 1048576.0); }

	/** Opt-out: unloads the model and deletes the engine and the model. */
	static synchronized void remove() throws IOException {
		unload();
		if (loader instanceof URLClassLoader u) { try { u.close(); } catch (IOException e) { dev.gatehouse.look.Quiet.seen(e); } }
		loader = null; checked = false;
		if (!Files.isDirectory(folder())) return;
		// Once the engine's native part has been loaded, Windows keeps it locked until Gatekeeper stops: what can't go now
		// goes the next time Gatekeeper starts (sweep()), and until then the built-in AI counts as removed.
		if (sweep()) log("AI helper: the built-in AI was removed");
		else {
			Files.writeString(folder().resolve(PENDING), "removed " + java.time.LocalDateTime.now() + "\n");
			log("AI helper: the built-in AI was removed (its last files go when Gatekeeper next starts)");
		}
	}
	private static final String PENDING = "removed.txt";
	/** Deletes the built-in AI's folder; true when all of it went. */
	private static boolean sweep() {
		boolean all = true;
		try (var s = Files.list(folder())) {
			for (Path f : (Iterable<Path>) s::iterator) {
				if (f.getFileName().toString().equals(PENDING)) continue;
				try { Files.deleteIfExists(f); } catch (IOException e) { all = false; }
			}
		} catch (IOException e) { return false; }
		if (all) { try { Files.deleteIfExists(folder().resolve(PENDING)); Files.deleteIfExists(folder()); } catch (IOException e) { dev.gatehouse.look.Quiet.seen(e); } }
		return all;
	}
	/** At start: finishes a removal that had to wait for Gatekeeper to stop. */
	static void finishRemoval() {
		if (Files.exists(folder().resolve(PENDING)) && sweep()) log("AI helper: the built-in AI's last files were removed");
	}

	// ---- answering

	private static synchronized ClassLoader load() throws Exception {
		if (loader != null) return loader;
		if (!checked) {
			for (Part p : new Part[] {ENGINE, MODEL})
				if (!sha256(at(p)).equals(p.sha256())) { Files.deleteIfExists(at(p)); throw new IOException(p.name() + " changed since it was fetched, so it was removed: get the built-in AI again"); }
			checked = true;
		}
		byte[] glue;
		try (InputStream in = BuiltinAi.class.getResourceAsStream("ai-engine.jar")) {
			if (in == null) throw new IOException("the built-in AI isn't in this copy of the program");
			glue = in.readAllBytes();
		}
		Path g = folder().resolve("ai-engine-" + Integer.toHexString(java.util.Arrays.hashCode(glue)) + ".jar");
		if (!Files.isRegularFile(g)) Files.write(g, glue);
		System.setProperty("de.kherud.llama.tmpdir", folder().toString());
		loader = new URLClassLoader(new URL[] {at(ENGINE).toUri().toURL(), g.toUri().toURL()}, BuiltinAi.class.getClassLoader());
		Thread idle = new Thread(() -> {
			while (true) {
				try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
				if (lastUse > 0 && System.currentTimeMillis() - lastUse > 10 * 60_000) { unload(); lastUse = 0; }
			}
		}, "builtin-ai-idle");
		idle.setDaemon(true);
		idle.start();
		return loader;
	}
	/** One answer, or "" when it couldn't give one (the reason goes to the log). Blocking. */
	static String answer(String system, String user, int words) {
		try {
			lastUse = System.currentTimeMillis();
			Class<?> c = Class.forName("dev.gatehouse.ai.Local", true, load());
			int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
			// A model this small tends to repeat the question when the facts and the question come as one message, so the
			// facts go with the instructions and the question (the last line) is the person's turn on its own.
			int k = user.stripTrailing().lastIndexOf('\n');
			if (k > 0) { system = system + "\n\n" + user.substring(0, k).strip(); user = user.substring(k + 1).strip(); }
			Object r = c.getMethod("answer", String.class, int.class, String.class, String.class, int.class)
					.invoke(null, at(MODEL).toString(), threads, system, user, words);
			lastUse = System.currentTimeMillis();
			return String.valueOf(r);
		} catch (Throwable e) {
			Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
			log("AI helper: the built-in AI didn't answer: " + t);
			return "";
		}
	}
	private static void unload() {
		if (loader == null) return;
		try { Class.forName("dev.gatehouse.ai.Local", true, loader).getMethod("unload").invoke(null); }
		catch (Throwable e) { dev.gatehouse.look.Quiet.seen(e instanceof Exception x ? x : new Exception(e)); }
	}

	/** For the AI helper's status: what the built-in AI is and can do here. */
	static Map<String, Object> state() {
		return m("installed", installed(), "not_here", notHere(), "device", device()[0], "what", device()[1], "battery", onBattery(),
				"fetching", fetching, "sizes", sizes(), "model", MODEL_NAME);
	}
}
