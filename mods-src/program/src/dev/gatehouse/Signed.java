package dev.gatehouse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.gatehouse.look.Quiet;

/**
 * Release signatures (R24). Each published program file is signed on GitHub with the release key (it is kept in the
 * repository's secrets; tools/setup_signing_key.ps1 made it). The signature is the file's SHA-256 + ".sig" in the
 * repository's "signatures" release, and Gatekeepers hand it on beside the file (/v1/program/sig/<sha>), so one fetched
 * from a peer can be checked too. The public half is inside this program (release-key.pub.pem).
 *
 * <p>What a client does with an update it is about to take:
 * <ul>
 *   <li>signed, and the signature fits: taken, and said so ("signed");</li>
 *   <li>a signature is found and it does NOT fit: refused, whatever else ("bad"). That is a changed or forged file;</li>
 *   <li>no signature can be found ("unsigned"): taken as before, and noted. GitHub signs a minute or two after each
 *       publish, and the owner wants fast peer-to-peer updates, so a missing one doesn't stop anything yet. Gatekeeper's
 *       host.json "require_signed": "true" makes it refuse unsigned ones too.</li>
 * </ul>
 */
public final class Signed {
	private Signed() {}

	/** Where signatures are published, after the file's SHA-256. */
	public static final String RELEASES = "https://github.com/scholar-andrew/gatehouse/releases/download/signatures/";

	private static java.security.PublicKey key;
	/** The release key's public half, from inside this program (null when this build carries none). */
	static synchronized java.security.PublicKey key() {
		if (key != null) return key;
		try (InputStream in = Signed.class.getResourceAsStream("/release-key.pub.pem")) {
			if (in == null) return null;
			String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
			key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pem)));
			return key;
		} catch (Exception e) { return null; }
	}

	/** Does this signature fit these bytes, under the release key? */
	public static boolean fits(byte[] data, byte[] sig) {
		java.security.PublicKey k = key();
		if (k == null || sig == null || sig.length != 64) return false;
		try {
			java.security.Signature s = java.security.Signature.getInstance("Ed25519");
			s.initVerify(k); s.update(data);
			return s.verify(sig);
		} catch (Exception e) { return false; }
	}

	/** The signature for the file with this SHA-256, from the first of these places that has one (each is a base: the
	 *  sha and ".sig" follow it), then GitHub. Null when none has it (or none can be reached). */
	public static byte[] fetch(String sha, List<String> places) {
		List<String> all = new ArrayList<>(places == null ? List.of() : places);
		all.add(RELEASES);
		String name = sha.toLowerCase(java.util.Locale.ROOT) + ".sig";
		for (String base : all) try {
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(base + name).toURL().openConnection();
			c.setConnectTimeout(4000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
			if (c.getResponseCode() != 200) continue;
			try (InputStream in = c.getInputStream()) { byte[] b = in.readNBytes(65); if (b.length == 64) return b; }
		} catch (IOException | RuntimeException next) { /* the next place */ }
		return null;
	}

	/** "signed", "bad" or "unsigned" for these bytes (whose SHA-256 is sha); the signature found is kept in "keep" (a
	 *  folder, or null), so it can be handed on. */
	public static String check(byte[] data, String sha, List<String> places, Path keep) {
		if (key() == null) return "unsigned";
		byte[] sig = keep == null ? null : read(keep.resolve(sha.toLowerCase(java.util.Locale.ROOT) + ".sig"));
		if (sig == null) sig = fetch(sha, places);
		if (sig == null) return "unsigned";
		if (!fits(data, sig)) return "bad";
		if (keep != null) try { Files.createDirectories(keep); Files.write(keep.resolve(sha.toLowerCase(java.util.Locale.ROOT) + ".sig"),
				sig); } catch (IOException ignored) { Quiet.seen(ignored); }
		return "signed";
	}
	private static byte[] read(Path p) { try { return Files.isRegularFile(p) ? Files.readAllBytes(p) : null; } catch (IOException e) { return null; } }

	/** In words, for a log line or a message. */
	public static String words(String result) {
		return switch (result) {
			case "signed" -> "its release signature fits";
			case "bad" -> "its signature does NOT fit the release key (a changed or forged file)";
			default -> "it has no release signature yet";
		};
	}
}
