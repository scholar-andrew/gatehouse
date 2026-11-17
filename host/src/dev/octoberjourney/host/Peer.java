package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * The program file passed from client to client, so a constellation needs it fetched from outside only once.
 *
 * A Gatekeeper keeps the newest program file it has heard of (program-held.jar, with program-held.json beside it:
 * version, sha256, size, who it came from), and gives it to whoever asks:
 *   GET /v1/program/gatekeeper.json, /v1/program/oj-host.jar           laid out like a pack server's gatekeeper folder
 *   GET /v1/program/main/update-sizes.json, .../extras/launch-popup.jar  laid out like a pack, for a launcher
 * (the same file both ways: it is one program). Until it holds something newer, it gives out the file it is running.
 * It is the published program, which anyone can fetch from GitHub, so nothing is asked of who is asking.
 *
 * How it comes to hold one:
 *   - it sees a newer version at one of its own sources and fetches it at once (prefetch), not only when it updates;
 *   - a client it trusts hands one over (POST /v1/program, or /admin/program from this machine): the launcher on
 *     this machine, a launcher with Gatekeeper control, or a Gatekeeper allowed into this constellation. What is
 *     handed over has to be a Gatekeeper that can run, newer than this one, and match the checksum given with it.
 * The first to see a version says so before it fetches (POST /v1/program/hand): the others are told someone has it
 * in hand, and wait for the copy instead of fetching one each. A Gatekeeper that joined another hands on what it
 * holds to that Gatekeeper the same way, and looks there for what that one holds.
 *
 * Nothing here checks who made the file: a copy handed over is trusted because the client handing it over is.
 */
final class Peer {
	private Peer() {}
	static final int MAX = 16 * 1024 * 1024;
	private static Map<String, Object> meta;
	private static String ownSha;

	private static Path file() { return dir.resolve("program-held.jar"); }
	static Path ownJar() throws IOException {
		try { return Path.of(HostHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()); } catch (java.net.URISyntaxException | RuntimeException e) { throw new IOException(e); }
	}

	/** What is held: {version, sha256, size, from}, or nothing. A file that is no newer than this Gatekeeper is thrown away. */
	static synchronized Map<String, Object> held() {
		if (meta == null) {
			meta = new LinkedHashMap<>();
			try { if (Files.isRegularFile(file())) meta = Json.map(Json.parse(read(dir.resolve("program-held.json")))); } catch (RuntimeException e) { meta = new LinkedHashMap<>(); }
		}
		if (!meta.isEmpty() && (cmpVersion(Json.str(meta.get("version")), ownVersion()) <= 0 || !Files.isRegularFile(file()))) {
			meta = new LinkedHashMap<>();
			try { Files.deleteIfExists(file()); Files.deleteIfExists(dir.resolve("program-held.json")); } catch (IOException ignored) {}
		}
		return meta;
	}
	static String heldVersion() { return Json.str(held().get("version")); }
	/** Where the held file came from, for the update log: the place it was fetched from, or who handed it over. */
	static String heldFrom() { String f = Json.str(held().get("from")); return f.startsWith("fetched from ") ? f.substring("fetched from ".length()) : f.isEmpty() ? "" : "a copy " + f; }

	/** The held file, if it is the one with this checksum (checked again as it is read). */
	static byte[] heldBytes(String sha) {
		try {
			if (sha.isEmpty() || !sha.equalsIgnoreCase(Json.str(held().get("sha256")))) return null;
			byte[] b = Files.readAllBytes(file());
			return sha256(b).equalsIgnoreCase(sha) ? b : null;
		} catch (IOException e) { return null; }
	}

	/** What a version held here is newer than everything the sources said: it is the newest this Gatekeeper knows. */
	static void prefer() {
		String v = heldVersion();
		if (v.isEmpty() || cmpVersion(v, ownVersion()) <= 0) return;
		if (!newestKeeper.isEmpty() && cmpVersion(v, newestKeeper) <= 0) return;
		newestKeeper = v; newestKeeperSha = Json.str(held().get("sha256")); newestKeeperAt = "a copy handed over by " + Json.str(held().get("from")); newestHouse = v; newestNeeds = Json.str(held().get("needs"));
	}

	/** Takes a program file in. Returns {result, message}: ok, have (nothing newer in it) or refused. */
	static synchronized String[] store(byte[] b, String saysSha, String from) {
		if (b.length == 0 || b.length > MAX) return new String[] {"refused", "That isn't a program file."};
		String sha = sha256(b);
		if (!saysSha.isEmpty() && !saysSha.equalsIgnoreCase(sha)) return new String[] {"refused", "What arrived doesn't match its checksum."};
		Path part = dir.resolve("program-held.jar.part");
		try {
			Files.write(part, b);
			String v = "", needs = "";
			try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(part.toFile())) {
				java.util.zip.ZipEntry notes = z.getEntry("gatekeeper-notes.json");
				if (z.getEntry("dev/octoberjourney/host/HostHelper.class") == null || z.getEntry("dev/gatehouse/Main.class") == null || z.getEntry("META-INF/MANIFEST.MF") == null || notes == null) throw new IOException("not the program");
				try (InputStream in = z.getInputStream(notes)) { Map<String, Object> n = Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); v = Json.str(n.get("version")); needs = Json.str(n.get("needs")); }
			} catch (IOException | RuntimeException notIt) { Files.deleteIfExists(part); return new String[] {"refused", "That isn't a Gatekeeper that can run."}; }
			if (v.isEmpty() || cmpVersion(v, ownVersion()) <= 0 || (!heldVersion().isEmpty() && cmpVersion(v, heldVersion()) <= 0)) { Files.deleteIfExists(part); return new String[] {"have", "This Gatekeeper already has that version or a newer one."}; }
			Files.move(part, file(), StandardCopyOption.REPLACE_EXISTING);
			meta = new LinkedHashMap<>(m("version", v, "sha256", sha, "size", b.length, "from", from, "needs", needs));
			Files.writeString(dir.resolve("program-held.json"), Json.write(meta), StandardCharsets.UTF_8);
			log("program: Gatekeeper " + v + " is held here now (" + from + "), for this Gatekeeper and whoever asks it");
			prefer();
			passOn();
			return new String[] {"ok", "Taken: Gatekeeper " + v + "."};
		} catch (IOException e) { try { Files.deleteIfExists(part); } catch (IOException ignored) {} return new String[] {"refused", "Couldn't keep it: " + e.getMessage()}; }
	}

	// ---- seeing a newer version at a source: it is fetched at once, so it can be given out
	private static volatile boolean fetching;
	static void prefetch() {
		if (!AutoUpdate.newer() || fetching || !newestKeeperAt.startsWith("http") || newestKeeperSha.equalsIgnoreCase(Json.str(held().get("sha256")))) return;
		String at = newestKeeperAt, sha = newestKeeperSha, v = newestKeeper;
		fetching = true;
		Thread t = new Thread(() -> {
			try {
				// (seen at the Gatekeeper this one joined: that is the constellation's copy, taken as it is. Seen outside:
				// this one raises its hand first, and if that Gatekeeper has it or has it in hand, looks there again shortly)
				boolean theirs = !lead().isEmpty() && at.startsWith(lead().replaceAll("/+$", ""));
				if (!theirs && !raise(v)) { try { Thread.sleep(12_000); } catch (InterruptedException e) { return; } fetching = false; lookForNewer(); return; }
				if (!theirs) hand("this Gatekeeper", v);   // (and whoever asks here meanwhile is told it is in hand)
				byte[] b = fetchProgram(at + "/oj-host.jar", v);
				if (sha256(b).equalsIgnoreCase(sha)) store(b, sha, "fetched from " + UpdateLog.where(at));
			} catch (IOException | RuntimeException notNow) { /* at its next look */ }
			finally { fetching = false; }
		}, "program-fetch");
		t.setDaemon(true);
		t.start();
	}

	// ---- raising a hand: the first to see a version says so, and the others wait for its copy
	private static String handVersion = "", handWho = "";
	private static long handUntil;
	static synchronized Map<String, Object> hand(String who, String version) {
		String have = heldVersion().isEmpty() ? ownVersion() : heldVersion();
		if (version.isEmpty() || cmpVersion(version, have) <= 0) return m("go", false, "have", true);
		long now = System.currentTimeMillis();
		if (version.equals(handVersion) && now < handUntil && !who.equals(handWho)) return m("go", false, "by", handWho);
		if (!version.equals(handVersion) || !who.equals(handWho)) log("program: " + who + " saw Gatekeeper " + version + " first and is fetching it for this constellation");
		handVersion = version; handWho = who; handUntil = now + 90_000;
		return m("go", true);
	}
	/** Is someone else already fetching this version for the constellation? (Then this Gatekeeper waits for the copy.) */
	static synchronized boolean inHand(String version) { return version.equals(handVersion) && System.currentTimeMillis() < handUntil; }

	// ---- the Gatekeeper this one joined: asked before fetching, and handed what is held here
	private static String lead() { return setting("lead_updates", "true").equals("true") ? setting("constellation_lead", "") : ""; }
	private static String leadPost(String path, byte[] body, String type, String sha) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(lead().replaceAll("/+$", "") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(30000); c.setRequestMethod("POST"); c.setDoOutput(true);
		c.setRequestProperty("Content-Type", type);
		c.setRequestProperty("X-Device", setting("constellation_id", "")); c.setRequestProperty("X-Token", setting("constellation_token", ""));
		if (!sha.isEmpty()) c.setRequestProperty("X-Sha256", sha);
		c.setFixedLengthStreamingMode(body.length);
		try (OutputStream o = c.getOutputStream()) { o.write(body); }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}
	/** True when this Gatekeeper should fetch the version itself: it joined nobody, or was told to go ahead. */
	private static boolean raise(String version) {
		if (lead().isEmpty()) return !inHand(version);
		try { Map<String, Object> r = Json.map(Json.parse(leadPost("/v1/program/hand", Json.write(m("version", version)).getBytes(StandardCharsets.UTF_8), "application/json", ""))); return !Boolean.FALSE.equals(r.get("go")); }
		catch (IOException | RuntimeException alone) { return true; }
	}
	private static void passOn() {
		if (lead().isEmpty()) return;
		Map<String, Object> h = new LinkedHashMap<>(held());
		Thread t = new Thread(() -> {
			try {
				String sha = Json.str(h.get("sha256"));
				byte[] b = heldBytes(sha);
				if (b == null) return;
				Map<String, Object> ask = Json.map(Json.parse(leadPost("/v1/program/hand", Json.write(m("version", Json.str(h.get("version")))).getBytes(StandardCharsets.UTF_8), "application/json", "")));
				if (Boolean.TRUE.equals(ask.get("have"))) return;
				String said = leadPost("/v1/program", b, "application/java-archive", sha);
				if (said.contains("\"ok\"")) log("program: Gatekeeper " + Json.str(h.get("version")) + " was handed on to the Gatekeeper this one joined");
			} catch (IOException | RuntimeException notNow) { /* it finds it itself */ }
		}, "program-on");
		t.setDaemon(true);
		t.start();
	}

	// ---- giving it out
	private static void send(HttpExchange x, byte[] b, String type) throws IOException {
		x.getResponseHeaders().set("Content-Type", type);
		x.sendResponseHeaders(200, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}
	/** GET /v1/program/...: what is held, or else the file this Gatekeeper is running. */
	static void serve(HttpExchange x, String path) throws IOException {
		String v = heldVersion(), sha, needs;
		Path f;
		if (!v.isEmpty()) { f = file(); sha = Json.str(held().get("sha256")); needs = Json.str(held().get("needs")); }
		else { f = ownJar(); v = ownVersion(); needs = Json.str(notesInside("gatekeeper").get("needs")); synchronized (Peer.class) { if (ownSha == null) ownSha = sha256(Files.readAllBytes(f)); sha = ownSha; } }
		long size = Files.size(f);
		String rest = path.substring("/v1/program/".length());
		if (rest.equals("gatekeeper.json")) send(x, Json.write(m("version", v, "sha256", sha, "size", size, "gatehouse", v, "needs", needs)).getBytes(StandardCharsets.UTF_8), "application/json");
		else if (rest.equals("main/update-sizes.json") || rest.equals("deck/update-sizes.json")) send(x, Json.write(m("gatehouse", v, "files", m("extras/launch-popup.jar", m("hash", sha, "size", size)))).getBytes(StandardCharsets.UTF_8), "application/json");
		else if (rest.equals("oj-host.jar") || rest.equals("main/extras/launch-popup.jar") || rest.equals("deck/extras/launch-popup.jar")) send(x, Files.readAllBytes(f), "application/java-archive");
		else reply(x, 404, m("error", "no such file"));
	}

	private static byte[] bodyBytes(HttpExchange x) throws IOException { try (InputStream in = x.getRequestBody()) { return in.readNBytes(MAX + 1); } }
	/** POST /v1/program and /v1/program/hand, from a client that has said hello (d: what is kept about it). */
	static void take(HttpExchange x, String path, String who, Map<String, Object> d) throws IOException {
		boolean trusted = ServerTools.yes(d.get("local")) || ServerTools.yes(d.get("admin")) || ServerTools.yes(d.get("constellation"));
		if (!trusted) { reply(x, 200, m("result", "refused", "go", false, "message", "Only this machine's own launcher, a launcher with Gatekeeper control, or a Gatekeeper allowed into this constellation hands program files over.")); return; }
		if (path.equals("/v1/program/hand")) { reply(x, 200, hand(who, Json.str(body(x).get("version")))); return; }
		String sha = x.getRequestHeaders().getFirst("X-Sha256");
		String[] r = store(bodyBytes(x), sha == null ? "" : sha.trim(), "handed over by " + who);
		reply(x, 200, m("result", r[0], "message", r[1]));
	}
	/** POST /admin/program (and /admin/program/hand): the launcher on this same machine. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/program") && !path.equals("/admin/program/hand")) return false;
		if (!x.getRequestMethod().equals("POST")) { reply(x, 200, m("held", held(), "own", ownVersion())); return true; }
		if (path.endsWith("/hand")) { reply(x, 200, hand("the launcher on this machine", Json.str(body(x).get("version")))); return true; }
		String sha = x.getRequestHeaders().getFirst("X-Sha256");
		String[] r = store(bodyBytes(x), sha == null ? "" : sha.trim(), "handed over by the launcher on this machine");
		reply(x, 200, m("result", r[0], "message", r[1]));
		return true;
	}
}
