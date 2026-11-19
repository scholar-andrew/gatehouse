package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * R79 stage 2: HTTPS with this Gatekeeper's own certificate (host.json "https": "true"; off unless chosen).
 * The certificate is made here, once (an EC P-256 key, self-signed, for 20 years), and kept in tls.p12 in Gatekeeper's
 * folder with the password for it beside it (tls.key), readable by whoever can sign in to this machine, like the rest of
 * Gatekeeper's files. Nobody signs it, so a browser warns the first time; launchers and other Gatekeepers instead check
 * its fingerprint (Pins), which goes out in pairings, in packs and between Gatekeepers. Making a new one (Host setup,
 * New certificate) means pairing launchers again.
 */
final class Tls {
	private Tls() {}
	private static volatile SSLContext ctx;
	private static volatile String fp = "";

	static boolean on() { return setting("https", "false").equals("true") || "internet".equals(setting("network", "")); }
	/** "https" or "http", for the addresses Gatekeeper hands out. */
	static String scheme() { return on() ? "https" : "http"; }
	static String fingerprint() { try { load(); } catch (IOException e) { return ""; } return fp; }

	private static Path store() { return dir.resolve("tls.p12"); }
	private static Path pass() { return dir.resolve("tls.key"); }

	/** The SSL context for the HTTPS door, made (and the certificate with it) the first time. */
	static synchronized SSLContext context() throws IOException { load(); return ctx; }
	private static synchronized void load() throws IOException {
		if (ctx != null) return;
		try {
			if (!Files.isRegularFile(store()) || !Files.isRegularFile(pass())) make();
			char[] pw = Files.readString(pass(), StandardCharsets.UTF_8).trim().toCharArray();
			KeyStore ks = KeyStore.getInstance("PKCS12");
			try (var in = Files.newInputStream(store())) { ks.load(in, pw); }
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, pw);
			SSLContext c = SSLContext.getInstance("TLS");
			c.init(kmf.getKeyManagers(), null, null);
			fp = dev.octoberjourney.popup.Pins.fingerprint((X509Certificate) ks.getCertificate("gatekeeper"));
			ctx = c;
		} catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("the certificate couldn't be made or read: " + e, e); }
	}
	/** A new certificate (the old one is set aside as tls-before.p12). Launchers need the new pairing. */
	static synchronized void renew() throws IOException {
		if (Files.isRegularFile(store())) Files.move(store(), dir.resolve("tls-before.p12"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(pass());
		ctx = null; fp = "";
		load();
		log("https: a new certificate was made: " + fp);
	}

	// ---- making the certificate: X.509 v3 by hand (Java can read certificates but has no public way to make one)
	private static void make() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
		KeyPair kp = g.generateKeyPair();
		byte[] serial = new byte[16]; new SecureRandom().nextBytes(serial); serial[0] &= 0x7F;
		byte[] sigAlg = seq(oid(new int[] {1, 2, 840, 10045, 4, 3, 2}));   // ecdsa-with-SHA256
		String nm = setting("name", "").replaceAll("[^\\w .\\-]", "").trim();
		byte[] name = seq(set(seq(cat(oid(new int[] {2, 5, 4, 3}), utf8("Gatekeeper" + (nm.isEmpty() ? "" : " " + nm))))));
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1);
		byte[] validity = seq(cat(time(now), time(now.plusYears(20))));
		byte[] tbs = seq(cat(tag(0xA0, integer(BigInteger.TWO)), integer(new BigInteger(serial)), sigAlg, name, validity, name, kp.getPublic().getEncoded()));
		Signature s = Signature.getInstance("SHA256withECDSA");
		s.initSign(kp.getPrivate()); s.update(tbs);
		byte[] cert = seq(cat(tbs, sigAlg, tag(0x03, cat(new byte[] {0}, s.sign()))));
		X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(cert));
		x.verify(kp.getPublic());
		byte[] pwb = new byte[24]; new SecureRandom().nextBytes(pwb);
		char[] pw = java.util.HexFormat.of().formatHex(pwb).toCharArray();
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(null, null);
		ks.setKeyEntry("gatekeeper", kp.getPrivate(), pw, new java.security.cert.Certificate[] {x});
		Files.writeString(pass(), new String(pw) + "\n", StandardCharsets.UTF_8);
		try (var out = Files.newOutputStream(store())) { ks.store(out, pw); }
		log("https: this Gatekeeper's own certificate was made: " + dev.octoberjourney.popup.Pins.fingerprint(x));
	}
	private static byte[] cat(byte[]... parts) { ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] p : parts) o.writeBytes(p); return o.toByteArray(); }
	private static byte[] tag(int t, byte[] body) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.write(t);
		int n = body.length;
		if (n < 128) o.write(n);
		else if (n < 256) { o.write(0x81); o.write(n); }
		else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xFF); }
		else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xFF); o.write(n & 0xFF); }
		o.writeBytes(body);
		return o.toByteArray();
	}
	private static byte[] seq(byte[] body) { return tag(0x30, body); }
	private static byte[] set(byte[] body) { return tag(0x31, body); }
	private static byte[] integer(BigInteger v) { return tag(0x02, v.toByteArray()); }
	private static byte[] utf8(String s) { return tag(0x0C, s.getBytes(StandardCharsets.UTF_8)); }
	private static byte[] time(ZonedDateTime t) {
		return t.getYear() < 2050 ? tag(0x17, t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII))
				: tag(0x18, t.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
	}
	private static byte[] oid(int[] arcs) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		o.write(arcs[0] * 40 + arcs[1]);
		for (int i = 2; i < arcs.length; i++) {
			int v = arcs[i];
			byte[] b = new byte[5]; int n = 0;
			do { b[n++] = (byte) (v & 0x7F); v >>>= 7; } while (v != 0);
			for (int k = n - 1; k >= 0; k--) o.write(b[k] | (k > 0 ? 0x80 : 0));
		}
		return tag(0x06, o.toByteArray());
	}
}
