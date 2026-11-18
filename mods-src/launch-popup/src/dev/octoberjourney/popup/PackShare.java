package dev.octoberjourney.popup;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.gatehouse.look.Quiet;

/**
 * R40, step 3: this machine handing out pack updates to other Gate Systems. Run by Gatekeeper (always, unless its owner
 * turned it off) and by Gatehouse while its window is open (unless turned off in Options; the Steam Deck only while
 * plugged in; not at all when this machine's own Gatekeeper is doing it).
 * <p>
 * What it hands out is only what the update step (PackRelay) checked and kept in an instance's gatehouse-cache: the
 * pack's pack.toml with its release signature, its file list, and every file in that list, each checked again before it
 * is sent. A pack is handed out only once all of it is here: what is missing is first copied from the installed game
 * (when its fingerprint fits) or fetched from the owner's own sources and checked. A pack that isn't signed (packs a
 * Gatekeeper builds) is never handed out.
 * <p>
 * Over Tailscale only: it listens on this machine's Tailscale address, and answers only the devices its Gatekeepers
 * know (the addresses they give), and this machine itself. Addresses: /pack/&lt;variant&gt;/&lt;file&gt; and
 * /pack/sigs/&lt;sha256&gt;.sig.
 */
public final class PackShare {
	private PackShare() {}

	/** A pack this machine can hand out: its checked copies (cache), its version and pack.toml's fingerprint, and the
	 *  list's fingerprints (file -> sha256). */
	record Held(Path cache, Path sigs, String version, String packSha, Map<String, String> files) {}

	private static HttpServer server;
	private static String boundTo = "";
	private static final Set<String> allowed = ConcurrentHashMap.newKeySet();
	private static volatile Map<String, Held> held = Map.of();
	private static long scannedAt = 0;
	private static final Set<String> filling = ConcurrentHashMap.newKeySet();   // (pack.toml fingerprints being completed)
	private static final Map<String, Integer> handed = new ConcurrentHashMap<>();   // (who was handed how many files, this run)

	/** Keeps the hand-out going on this address for these devices, with the packs in these Prism instance folders.
	 *  Cheap to call often. An empty address stops it. Returns how it stands: {port, packs: {variant: version}}. */
	public static synchronized Map<String, Object> keep(String bind, Collection<String> allow, List<Path> instanceFolders) {
		if (bind == null || bind.isBlank()) { stop(); return Map.of(); }
		allowed.retainAll(allow); allowed.addAll(allow);
		if (System.currentTimeMillis() - scannedAt > 60_000 || held.isEmpty() && System.currentTimeMillis() - scannedAt > 15_000) {
			scannedAt = System.currentTimeMillis();
			held = scan(instanceFolders);
		}
		if (server == null || !boundTo.equals(bind)) {
			stop();
			try {
				HttpServer s = HttpServer.create(new InetSocketAddress(bind, 0), 0);
				s.setExecutor(Executors.newFixedThreadPool(4));
				s.createContext("/pack/", PackShare::answer);
				s.start();
				server = s; boundTo = bind;
			} catch (IOException | RuntimeException e) { Quiet.seen(e); return Map.of(); }
		}
		return status();
	}

	public static synchronized void stop() {
		if (server != null) { server.stop(0); server = null; }
		boundTo = "";
	}

	/** {port, packs: {variant: version}} while handing out something; empty otherwise. */
	public static synchronized Map<String, Object> status() {
		if (server == null || held.isEmpty()) return Map.of();
		Map<String, Object> packs = new LinkedHashMap<>();
		for (Map.Entry<String, Held> e : held.entrySet()) packs.put(e.getKey(), e.getValue().version());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("port", server.getAddress().getPort());
		out.put("packs", packs);
		return out;
	}

	/** Who was handed files by this machine since it started, in words ("" for no one). */
	public static String handedOut() {
		List<String> s = new ArrayList<>();
		for (Map.Entry<String, Integer> e : handed.entrySet()) s.add(e.getKey() + " (" + e.getValue() + (e.getValue() == 1 ? " file)" : " files)"));
		return String.join(", ", s);
	}

	/** The instances' checked packs; the newest of each variant. One that isn't whole yet is completed in the background
	 *  and counted at the next look. */
	static Map<String, Held> scan(List<Path> instanceFolders) {
		Map<String, Held> out = new LinkedHashMap<>();
		for (Path folder : instanceFolders) {
			List<Path> mcs = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(folder)) { s.map(i -> i.resolve("minecraft")).filter(m -> Files.isDirectory(m.resolve("gatehouse-cache"))).forEach(mcs::add); }
			catch (IOException | RuntimeException none) { continue; }
			for (Path mc : mcs) {
				List<Path> variants = new ArrayList<>();
				try (java.util.stream.Stream<Path> s = Files.list(mc.resolve("gatehouse-cache"))) { s.filter(v -> Files.isRegularFile(v.resolve("pack.toml"))).forEach(variants::add); }
				catch (IOException | RuntimeException none) { continue; }
				for (Path cache : variants) {
					Held h = whole(mc, cache);
					if (h == null) continue;
					String variant = cache.getFileName().toString();
					Held had = out.get(variant);
					if (had == null || PackRelay.cmp(h.version(), had.version()) > 0) out.put(variant, h);
				}
			}
		}
		return out;
	}

	private static final Pattern VERSION = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]*)\"");
	private static final Pattern INDEX_HASH = Pattern.compile("(?s)\\[index\\].*?\\bhash\\s*=\\s*\"([0-9a-fA-F]{64})\"");

	/** The pack in this cache when all of it is here and right (and signed); otherwise null, and its completion started. */
	static Held whole(Path mc, Path cache) {
		try {
			byte[] packToml = Files.readAllBytes(cache.resolve("pack.toml"));
			String packSha = PackRelay.sha(packToml), text = new String(packToml, StandardCharsets.UTF_8);
			Path sigs = mc.resolve("gatehouse-cache").resolve("sig");
			// (only a signed pack; its signature is kept beside, so it can be handed on with it)
			if (!Files.isRegularFile(sigs.resolve(packSha + ".sig"))
					|| !dev.gatehouse.Signed.check(packToml, packSha, List.of(), sigs).equals("signed")) return null;
			Matcher v = VERSION.matcher(text), ih = INDEX_HASH.matcher(text);
			if (!v.find() || !ih.find()) return null;
			Path idx = cache.resolve("index.toml");
			if (!Files.isRegularFile(idx) || !PackRelay.sha(Files.readAllBytes(idx)).equalsIgnoreCase(ih.group(1))) { complete(mc, cache, packSha); return null; }
			Map<String, String> files = PackRelay.fingerprints(new String(Files.readAllBytes(idx), StandardCharsets.UTF_8));
			for (Map.Entry<String, String> f : files.entrySet()) {
				Path p = cache.resolve(f.getKey());
				if (!Files.isRegularFile(p) || !PackRelay.sha(Files.readAllBytes(p)).equals(f.getValue())) { complete(mc, cache, packSha); return null; }
			}
			return new Held(cache, sigs, v.group(1), packSha, files);
		} catch (IOException | RuntimeException e) { Quiet.seen(e); return null; }
	}

	/** Completes a cache in the background: the list and each missing file, copied from the installed game when it fits,
	 *  otherwise fetched from the owner's own sources (pack-sources.txt) and checked. */
	static void complete(Path mc, Path cache, String packSha) {
		if (!filling.add(packSha + "|" + cache)) return;
		Thread t = new Thread(() -> {
			try {
				String variant = cache.getFileName().toString();
				List<String> sources = new ArrayList<>();
				for (String l : PackRelay.read(mc.resolve("pack-sources.txt")).split("\\R")) { String s = l.trim().replaceAll("/+$", ""); if (s.startsWith("http")) sources.add(s + "/" + variant); }
				String text = Files.readString(cache.resolve("pack.toml"));
				Matcher ih = INDEX_HASH.matcher(text);
				if (!ih.find()) return;
				Path idx = cache.resolve("index.toml");
				byte[] index = Files.isRegularFile(idx) ? Files.readAllBytes(idx) : null;
				if (index == null || !PackRelay.sha(index).equalsIgnoreCase(ih.group(1))) {
					index = fetch(sources, "index.toml", ih.group(1).toLowerCase(java.util.Locale.ROOT));
					if (index == null) return;
					Files.write(idx, index);
				}
				for (Map.Entry<String, String> f : PackRelay.fingerprints(new String(index, StandardCharsets.UTF_8)).entrySet()) {
					Path p = cache.resolve(f.getKey()).normalize();
					if (!p.startsWith(cache)) continue;
					if (Files.isRegularFile(p) && PackRelay.sha(Files.readAllBytes(p)).equals(f.getValue())) continue;
					byte[] b = null;
					Path installed = mc.resolve(f.getKey()).normalize();
					if (installed.startsWith(mc) && Files.isRegularFile(installed)) { byte[] i = Files.readAllBytes(installed); if (PackRelay.sha(i).equals(f.getValue())) b = i; }
					if (b == null) b = fetch(sources, f.getKey(), f.getValue());
					if (b == null) return;   // (tried again at a later look)
					Files.createDirectories(p.getParent());
					Files.write(p, b);
				}
				synchronized (PackShare.class) { scannedAt = 0; }   // (whole now: counted at the next call)
			} catch (IOException | RuntimeException e) { Quiet.seen(e); }
			finally { filling.remove(packSha + "|" + cache); }
		}, "pack-share-complete");
		t.setDaemon(true);
		t.start();
	}

	static byte[] fetch(List<String> sources, String path, String want) {
		for (String s : sources) {
			byte[] b = PackRelay.get(s + "/" + PackRelay.encode(path), 30000);
			if (b != null && PackRelay.sha(b).equals(want)) return b;
		}
		return null;
	}

	static void answer(HttpExchange x) throws IOException {
		try {
			String ip = x.getRemoteAddress().getAddress().getHostAddress();
			if (!x.getRemoteAddress().getAddress().isLoopbackAddress() && !allowed.contains(ip)) { PackRelay.send(x, 403, null); return; }
			if (!x.getRequestMethod().equals("GET")) { PackRelay.send(x, 405, null); return; }
			String path = x.getRequestURI().getPath().replaceFirst("^/pack/", "");
			if (path.contains("..") || path.contains("\\")) { PackRelay.send(x, 404, null); return; }
			int slash = path.indexOf('/');
			if (slash <= 0) { PackRelay.send(x, 404, null); return; }
			String first = path.substring(0, slash), rest = path.substring(slash + 1);
			Map<String, Held> now = held;
			if (first.equals("sigs")) {
				// (only the signature of a pack.toml this machine hands out)
				for (Held h : now.values()) if (rest.equals(h.packSha() + ".sig")) { PackRelay.send(x, 200, Files.readAllBytes(h.sigs().resolve(rest))); return; }
				PackRelay.send(x, 404, null); return;
			}
			Held h = now.get(first);
			if (h == null) { PackRelay.send(x, 404, null); return; }
			byte[] body = null;
			if (rest.equals("pack.toml")) { byte[] b = Files.readAllBytes(h.cache().resolve("pack.toml")); if (PackRelay.sha(b).equals(h.packSha())) body = b; }
			else if (rest.equals("index.toml")) body = Files.readAllBytes(h.cache().resolve("index.toml"));
			else {
				String want = h.files().get(rest);
				if (want != null) { byte[] b = Files.readAllBytes(h.cache().resolve(rest)); if (PackRelay.sha(b).equals(want)) body = b; }
			}
			if (body == null) { PackRelay.send(x, 404, null); synchronized (PackShare.class) { scannedAt = 0; } return; }
			if (!rest.equals("pack.toml") && !rest.equals("index.toml")) handed.merge(ip, 1, Integer::sum);
			PackRelay.send(x, 200, body);
		} catch (IOException | RuntimeException e) { Quiet.seen(e); try { PackRelay.send(x, 404, null); } catch (IOException ignored) { Quiet.seen(ignored); } }
	}

	// ------------------------------------------------------------------ Gatehouse's part (while its window is open)

	/** Whether this device hands out updates (gatehouse-options.json "hand_out"): on unless turned off in Options. */
	static boolean wanted(Path home) {
		return !"false".equals(String.valueOf(ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(home.resolve("gatehouse-options.json")))).get("hand_out")));
	}

	/** What this launcher tells its Gatekeepers it hands out, as JSON ({} for nothing). */
	static String report() { return SystemsBox.json(status()); }

	/** After a round of telling the Gatekeepers how this launcher stands: what they answered (one map each). Hands out
	 *  when this device's owner hasn't turned it off, a Gatekeeper gave this device's Tailscale address, this machine's
	 *  own Gatekeeper isn't the one doing it, and (a Steam Deck) it isn't on its battery. Writes, into each pack on this
	 *  device, which Gate Systems hand out right now (pack-peers.txt, for the update step). */
	static void heard(List<Map<String, Object>> answers, Path home, Path instances) {
		if (answers.isEmpty()) return;   // (no Gatekeeper answered: left as it was)
		String you = "";
		boolean local = false;
		java.util.Set<String> allow = new java.util.LinkedHashSet<>();
		List<String> lines = new ArrayList<>();
		for (Map<String, Object> a : answers) {
			if (Boolean.TRUE.equals(a.get("local")) || "true".equals(String.valueOf(a.get("local")))) local = true;
			String y = String.valueOf(a.getOrDefault("you", ""));
			if (you.isEmpty() && y.matches("[0-9a-fA-F.:]+") && !y.startsWith("127.") && !y.equals("::1")) you = y;
			if (a.get("allow") instanceof List<?> l) for (Object o : l) { String ip = String.valueOf(o); if (ip.matches("[0-9a-fA-F.:]+")) allow.add(ip); }
			if (a.get("peers") instanceof List<?> l) for (Object o : l) {
				String base = String.valueOf(ServerControl.asMap(o).get("base")).replaceAll("/+$", "");
				if (!base.matches("http://[0-9a-zA-Z.:\\[\\]\\-]+/pack")) continue;
				String line = base + "/{variant}|" + base + "/sigs/";
				if (!lines.contains(line)) lines.add(line);
			}
		}
		final String me = you;
		lines.removeIf(l -> !me.isEmpty() && l.startsWith("http://" + me + ":"));
		if (wanted(home) && !local && !you.isEmpty() && !onBattery()) keep(you, allow, List.of(instances));
		else stop();
		writePeers(instances, lines);
	}

	/** pack-peers.txt in each pack on this device that updates through the update step (has pack-sources.txt). */
	static void writePeers(Path instances, List<String> lines) {
		String text = "# Gate Systems handing out pack updates right now, as this device's Gatekeepers said. (Written by Gatehouse.)\n"
				+ String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path i : (Iterable<Path>) s::iterator) {
				Path mc = i.resolve("minecraft");
				// (packs a Gatekeeper builds aren't handed out between machines: theirs get no list)
				if (!Files.isRegularFile(mc.resolve("pack-sources.txt")) || PackRelay.read(mc.resolve("pack-sources.txt")).contains("/v1/pack/")) continue;
				Path f = mc.resolve("pack-peers.txt");
				if (!PackRelay.read(f).equals(text)) try { Files.writeString(f, text, StandardCharsets.UTF_8); } catch (IOException ignored) { Quiet.seen(ignored); }
			}
		} catch (IOException | RuntimeException none) { Quiet.seen(none); }
	}

	private static volatile java.util.function.Supplier<String> powerWin;
	/** R42: this machine's battery in words ("82%, charging", "45%"), or "" with none. */
	public static String battery() {
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			// (a laptop: Windows' own power status, through PowerWin, which needs Java 22; on an older Java nothing is said)
			if (powerWin == null) try {
				@SuppressWarnings("unchecked") java.util.function.Supplier<String> s = (java.util.function.Supplier<String>) Class.forName("dev.octoberjourney.popup.PowerWin").getDeclaredConstructor().newInstance();
				powerWin = s;
			} catch (Throwable t) { powerWin = () -> ""; }
			return powerWin.get();
		}
		try (java.util.stream.Stream<Path> s = Files.list(Path.of("/sys/class/power_supply"))) {
			for (Path p : (Iterable<Path>) s::iterator) {
				if (!PackRelay.read(p.resolve("type")).trim().equals("Battery")) continue;
				String pct = PackRelay.read(p.resolve("capacity")).trim(), st = PackRelay.read(p.resolve("status")).trim();
				if (!pct.matches("\\d{1,3}")) continue;
				return pct + "%" + (st.equals("Charging") ? ", charging" : st.equals("Full") ? ", full" : st.equals("Not charging") ? ", plugged in" : "");
			}
		} catch (IOException | RuntimeException none) { Quiet.seen(none); }
		return "";
	}

	/** True on a machine that runs on its battery right now (a Steam Deck unplugged): it doesn't hand out then. */
	public static boolean onBattery() {
		if (System.getProperty("os.name", "").startsWith("Windows")) return false;
		try (java.util.stream.Stream<Path> s = Files.list(Path.of("/sys/class/power_supply"))) {
			boolean battery = false, mains = false;
			for (Path p : (Iterable<Path>) s::iterator) {
				String type = PackRelay.read(p.resolve("type")).trim();
				if (type.equals("Battery")) battery = true;
				if ((type.equals("Mains") || type.equals("USB")) && PackRelay.read(p.resolve("online")).trim().equals("1")) mains = true;
			}
			return battery && !mains;
		} catch (IOException | RuntimeException none) { return false; }
	}
}
