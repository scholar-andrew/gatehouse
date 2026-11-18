package dev.octoberjourney.popup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.gatehouse.look.Quiet;

/**
 * R40, step 2: the pack update fetched through Gatehouse, every file checked. The update script starts this on the device
 * (java -jar launch-popup.jar --pack-relay <variant> <instance folder> <source> ...) and points packwiz-installer at it
 * (http://127.0.0.1:<port>/pack.toml) instead of at a source. It then:
 * <ul>
 *   <li>asks every source for the pack's pack.toml at once (the sources from pack-sources.txt, which the owner's pack
 *       names, and the Gate Systems in pack-peers.txt, which hand out updates), and takes the newest version that
 *       answers well; between equals, the one that answered fastest;</li>
 *   <li>checks that pack.toml's release signature. From the owner's own sources (pack-sources.txt) an unsigned one is
 *       taken, as before (GitHub signs a minute or two after each publish); one whose signature doesn't fit never is. From
 *       another Gate System only a signed one is taken;</li>
 *   <li>fetches the list of files (index.toml) and each file from the sources in the order they answered, and checks
 *       each against the fingerprint the signed pack.toml (and through it the list) gives. One that doesn't match is
 *       thrown away and the next source asked;</li>
 *   <li>keeps each checked file in gatehouse-cache/ in the instance folder (so this device can hand it on: step 3), and
 *       a file already there with the right fingerprint isn't fetched again.</li>
 * </ul>
 * It writes its port to pack-relay-port.txt, what came from where to pack-relay.log, and where the pack came from to
 * pack-relay-used.txt; it ends by itself after five minutes with nothing asked of it.
 */
final class PackRelay {
	private PackRelay() {}

	/** A source: where the pack's files are (its folder for this variant), whether it is the owner's own (pack-sources.txt),
	 *  and where its signatures are, beside GitHub's (a Gate System hands those on too). */
	record Source(String base, boolean owners, String sigs) {}

	private static final Pattern VERSION = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]*)\"");
	private static final Pattern NAME = Pattern.compile("(?m)^name\\s*=\\s*\"([^\"]*)\"");
	private static final Pattern INDEX_HASH = Pattern.compile("(?s)\\[index\\].*?\\bhash\\s*=\\s*\"([0-9a-fA-F]{64})\"");

	static void run(String[] args) throws Exception {
		String variant = args[1];
		Path here = Path.of(args[2]);
		List<Source> sources = new ArrayList<>();
		for (int i = 3; i < args.length; i++) { String s = args[i].trim().replaceAll("/+$", ""); if (!s.isEmpty()) sources.add(new Source(s + "/" + variant, true, "")); }
		// (Gate Systems that hand out updates: "<their pack folder for this variant>|<their signatures folder>", one a line)
		for (String l : read(here.resolve("pack-peers.txt")).split("\\R")) {
			// (step 3: "{variant}" in a line stands for this pack's variant; written by Gatehouse from what its Gatekeepers say)
			String[] p = l.trim().replace("{variant}", variant).split("\\|"); if (p.length >= 1 && p[0].startsWith("http")) sources.add(new Source(p[0].replaceAll("/+$", ""), false,
					p.length > 1 ? p[1].replaceAll("/+$", "") + "/" : ""));
		}
		new PackRelay.Run(here, variant, sources).start();
	}

	static final class Run {
		final Path here, cache;
		final String variant;
		final List<Source> sources;
		final Path log;
		volatile long lastAsked = System.currentTimeMillis();
		// what was decided on the first ask for pack.toml, kept for the rest of the run
		byte[] packToml;
		List<Source> order = List.of();
		String indexHash;
		final Map<String, String> fingerprints = new ConcurrentHashMap<>();   // file -> sha256 (from the checked list)
		Run(Path here, String variant, List<Source> sources) {
			this.here = here; this.variant = variant; this.sources = sources;
			this.cache = here.resolve("gatehouse-cache").resolve(variant);
			this.log = here.resolve("pack-relay.log");
		}

		void start() throws Exception {
			HttpServer server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newFixedThreadPool(6));
			server.createContext("/", this::answer);
			server.start();
			int port = server.getAddress().getPort();
			Files.writeString(here.resolve("pack-relay-port.txt"), String.valueOf(port), StandardCharsets.US_ASCII);
			say("started on 127.0.0.1:" + port + " with " + sources.size() + " source(s)");
			System.out.println(port);
			while (System.currentTimeMillis() - lastAsked < 300_000) Thread.sleep(2000);
			say("nothing asked for five minutes: ended");
			server.stop(0);
			System.exit(0);
		}

		void answer(HttpExchange x) throws IOException {
			lastAsked = System.currentTimeMillis();
			try {
				String path = x.getRequestURI().getPath().replaceFirst("^/+", "");
				if (path.contains("..") || path.isEmpty()) { send(x, 404, null); return; }
				byte[] body;
				synchronized (this) { if (packToml == null && !path.equals("pack.toml")) choosePack(); }
				if (path.equals("pack.toml")) { synchronized (this) { if (packToml == null) choosePack(); } body = packToml; }
				else if (path.equals("index.toml")) body = index();
				else body = file(path);
				send(x, body == null ? 404 : 200, body);
			} catch (Exception e) {
				say("failed: " + x.getRequestURI() + ": " + e.getMessage());
				send(x, 502, null);
			} finally { lastAsked = System.currentTimeMillis(); }
		}

		/** Every source asked for pack.toml at once; the newest version that answers well wins, then the fastest. */
		void choosePack() throws Exception {
			ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, sources.size()));
			ExecutorCompletionService<Object[]> done = new ExecutorCompletionService<>(pool);
			long t0 = System.nanoTime();
			List<Future<Object[]>> asked = new ArrayList<>();
			for (Source s : sources) asked.add(done.submit(() -> { byte[] b = get(s.base() + "/pack.toml", 6000); return new Object[] {s, b, System.nanoTime() - t0}; }));
			List<Object[]> answers = new ArrayList<>();   // {source, bytes, nanos, version}
			long firstAt = 0;
			// (the version this device last took: another Gate System's older pack is never taken, so a machine that is
			// behind can't turn this one back)
			String kept = read(cache.resolve("pack.toml"));
			Matcher had = VERSION.matcher(kept), hadName = NAME.matcher(kept);
			String floor = had.find() ? had.group(1) : "", name = hadName.find() ? hadName.group(1) : "";
			for (int i = 0; i < asked.size(); i++) {
				// (once one has answered, the others are waited for a little longer, so a newer pack beats a faster old one)
				long wait = firstAt == 0 ? 8000 : Math.max(0, 1500 - (System.nanoTime() - firstAt) / 1_000_000);
				Future<Object[]> f = done.poll(wait, TimeUnit.MILLISECONDS);
				if (f == null) break;
				Object[] a;
				try { a = f.get(); } catch (Exception failed) { continue; }
				Source s = (Source) a[0]; byte[] b = (byte[]) a[1];
				if (b == null || b.length == 0) continue;
				String sha = sha(b);
				String signed = dev.gatehouse.Signed.check(b, sha, s.sigs().isEmpty() ? List.of() : List.of(s.sigs()), here.resolve("gatehouse-cache").resolve("sig"));
				if (signed.equals("bad") || (!s.owners() && !signed.equals("signed"))) { say("pack.toml from " + s.base() + ": " + signed + ", not taken"); continue; }
				Matcher v = VERSION.matcher(new String(b, StandardCharsets.UTF_8));
				if (!s.owners() && !floor.isEmpty() && cmp(v.find() ? v.group(1) : "", floor) < 0) { say("pack.toml from " + s.base() + ": older than this device's, not taken"); continue; }
				v.reset();
				// (and never another pack: from another Gate System, only one with the name of the pack this device has)
				Matcher n = NAME.matcher(new String(b, StandardCharsets.UTF_8));
				if (!s.owners() && !name.isEmpty() && !(n.find() && n.group(1).equals(name))) { say("pack.toml from " + s.base() + ": a different pack, not taken"); continue; }
				answers.add(new Object[] {s, b, a[2], v.find() ? v.group(1) : "", sha, signed});
				if (firstAt == 0) firstAt = System.nanoTime();
			}
			pool.shutdownNow();
			if (answers.isEmpty()) throw new IOException("no source answered with a pack this device can take");
			Object[] best = answers.get(0);
			for (Object[] a : answers) if (cmp((String) a[3], (String) best[3]) > 0) best = a;
			packToml = (byte[]) best[1];
			Matcher ih = INDEX_HASH.matcher(new String(packToml, StandardCharsets.UTF_8));
			indexHash = ih.find() ? ih.group(1).toLowerCase(java.util.Locale.ROOT) : null;
			// (the sources with this same pack, in the order they answered; then the owner's others, as a last resort)
			List<Source> o = new ArrayList<>();
			for (Object[] a : answers) if (a[4].equals(best[4])) o.add((Source) a[0]);
			for (Source s : sources) if (s.owners() && !o.contains(s)) o.add(s);
			order = o;
			Source from = (Source) best[0];
			say("pack " + best[3] + " (" + best[5] + ") from " + from.base() + " in " + ((long) best[2] / 1_000_000) + " ms; files from: " + order.stream().map(Source::base).toList());
			// (kept with the checked files, so this device can hand the pack on: see PackShare)
			try { Files.createDirectories(cache); Files.write(cache.resolve("pack.toml"), packToml); } catch (IOException ignored) { Quiet.seen(ignored); }
			Files.writeString(here.resolve("pack-relay-used.txt"), from.base() + "\n" + (from.owners() ? "owner" : "peer") + "\n" + best[3] + "\n", StandardCharsets.UTF_8);
		}

		/** The list of files, checked against the fingerprint in the signed pack.toml. */
		byte[] index() throws IOException {
			byte[] b = fetchChecked("index.toml", indexHash, true);
			if (b != null && fingerprints.isEmpty()) {
				fingerprints.putAll(fingerprints(new String(b, StandardCharsets.UTF_8)));
				try { Files.createDirectories(cache); Files.write(cache.resolve("index.toml"), b); } catch (IOException ignored) { Quiet.seen(ignored); }
			}
			return b;
		}

		/** A file of the pack: from the cache when it is already there and right, otherwise fetched and checked. Files
		 *  outside the list (update-sizes.json) come only from the owner's own sources. */
		byte[] file(String path) throws IOException {
			if (fingerprints.isEmpty()) index();
			String want = fingerprints.get(path);
			if (want != null) {
				Path kept = cache.resolve(path);
				if (Files.isRegularFile(kept)) { byte[] b = Files.readAllBytes(kept); if (sha(b).equals(want)) { say(path + " from this device's checked copies"); return b; } }
			}
			return fetchChecked(path, want, want != null);
		}

		/** From the sources in order: the first copy whose fingerprint is right (or, with no fingerprint, the first answer
		 *  from one of the owner's own sources). Kept in the cache when checked. */
		byte[] fetchChecked(String path, String want, boolean keep) throws IOException {
			String enc = encode(path);
			for (Source s : order) {
				if (want == null && !s.owners()) continue;
				byte[] b = get(s.base() + "/" + enc, 30000);
				if (b == null) continue;
				if (want != null && !sha(b).equals(want)) { say(path + " from " + s.base() + " doesn't match the signed list: thrown away"); continue; }
				say(path + " from " + s.base());
				if (keep && want != null) try { Path kept = cache.resolve(path); Files.createDirectories(kept.getParent()); Files.write(kept, b); } catch (IOException ignored) { Quiet.seen(ignored); }
				return b;
			}
			throw new IOException(path + ": no source had a right copy");
		}

		void say(String line) {
			try { Files.writeString(log, java.time.LocalDateTime.now().withNano(0) + "  " + line + "\n", StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (IOException ignored) { Quiet.seen(ignored); }
		}
	}

	/** The list's files and their sha256 fingerprints (files given another kind of fingerprint are left out). */
	static Map<String, String> fingerprints(String text) {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		Matcher def = Pattern.compile("(?m)^hash-format\\s*=\\s*\"([^\"]+)\"").matcher(text);
		String defFormat = def.find() ? def.group(1) : "sha256";
		for (String block : text.split("\\[\\[files\\]\\]")) {
			Matcher f = Pattern.compile("(?m)^file\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block), h = Pattern.compile("(?m)^hash\\s*=\\s*\"([0-9a-fA-F]+)\"").matcher(block),
					hf = Pattern.compile("(?m)^hash-format\\s*=\\s*\"([^\"]+)\"").matcher(block);
			if (!f.find() || !h.find()) continue;
			String format = hf.find() ? hf.group(1) : defFormat;
			if (format.equals("sha256")) out.put(f.group(1).replace("\\\"", "\"").replace("\\\\", "\\"), h.group(1).toLowerCase(java.util.Locale.ROOT));
		}
		return out;
	}

	static byte[] get(String url, int readMs) {
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setConnectTimeout(5000); c.setReadTimeout(readMs); c.setInstanceFollowRedirects(true);
			if (c.getResponseCode() != 200) return null;
			try (InputStream in = c.getInputStream()) { return in.readNBytes(512 << 20); }
		} catch (IOException | RuntimeException e) { return null; }
	}
	static void send(HttpExchange x, int code, byte[] body) throws IOException {
		if (body == null) { x.sendResponseHeaders(code, -1); x.close(); return; }
		x.sendResponseHeaders(code, body.length);
		try (OutputStream o = x.getResponseBody()) { o.write(body); }
	}
	static String sha(byte[] b) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
	}
	static String encode(String path) {
		StringBuilder sb = new StringBuilder();
		for (String part : path.split("/")) { if (sb.length() > 0) sb.append('/'); sb.append(java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")); }
		return sb.toString();
	}
	static String read(Path p) { try { return Files.readString(p); } catch (IOException e) { return ""; } }
	static int cmp(String a, String b) {
		String[] x = a.split("\\."), y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = 0, q = 0;
			try { if (i < x.length) p = Integer.parseInt(x[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { Quiet.seen(e); }
			try { if (i < y.length) q = Integer.parseInt(y[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { Quiet.seen(e); }
			if (p != q) return Integer.compare(p, q);
		}
		return 0;
	}
}
