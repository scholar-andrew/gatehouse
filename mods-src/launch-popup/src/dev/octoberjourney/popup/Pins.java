package dev.octoberjourney.popup;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import dev.gatehouse.look.Quiet;

/**
 * R79 stage 2: Gatekeepers that speak HTTPS with a certificate of their own (made by themselves, not signed by anyone
 * a browser knows). Such a certificate is accepted only where its fingerprint (SHA-256 of the certificate) is the one
 * this device was given for that address: in the pairing link or file, in a pack Gatekeeper built, or, between
 * Gatekeepers, the one it showed the first time they met. Every other site (GitHub, Modrinth, Mojang...) is checked
 * the usual way, against the certificates the system trusts. A pinned address showing a different certificate is
 * refused outright.
 *   install(file)        once at start: the pins kept in that file ({"host:port": "fingerprint"}), for every HTTPS
 *                        connection this program makes
 *   pin(hostPort, fp)    remembers one (and saves the file)
 *   seen(url)            the fingerprint a server shows, without trusting it (for the first meeting)
 */
public final class Pins {
	private Pins() {}
	private static final Map<String, String> pins = new ConcurrentHashMap<>();
	private static volatile Path file;
	private static volatile boolean installed;

	/** Gatehouse's pins, one file for the user (the launcher window and the game's window both read it). */
	public static void launcher() { install(Path.of(System.getProperty("user.home"), ".gatehouse", "pins.json")); }

	/** "ab12..." (64 hex digits) of a certificate. */
	public static String fingerprint(X509Certificate c) {
		try {
			StringBuilder b = new StringBuilder();
			for (byte x : MessageDigest.getInstance("SHA-256").digest(c.getEncoded())) b.append(String.format("%02x", x));
			return b.toString();
		} catch (Exception e) { return ""; }
	}
	static String clean(String fp) { String f = fp == null ? "" : fp.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(java.util.Locale.ROOT); return f.length() == 64 ? f : ""; }
	/** "host:port" of a URL or address. */
	public static String hostPort(String url) {
		String u = url == null ? "" : url.replaceFirst("(?i)^[a-z]+://", "").replaceFirst("/.*$", "");
		return u.contains(":") ? u.toLowerCase(java.util.Locale.ROOT) : u.toLowerCase(java.util.Locale.ROOT) + ":443";
	}
	public static String of(String hostPort) { return pins.getOrDefault(hostPort.toLowerCase(java.util.Locale.ROOT), ""); }
	/** R79 stage 3: a Gatekeeper facing the internet takes a new launcher only with its join key (from the pairing). */
	public static String joinKey(String hostPort) { return pins.getOrDefault("join@" + hostPort.toLowerCase(java.util.Locale.ROOT), ""); }
	public static synchronized void joinKey(String hostPort, String key) {
		String k = key == null ? "" : key.replaceAll("[^0-9A-Za-z]", "");
		if (k.length() < 6 || hostPort == null || hostPort.isBlank() || k.equals(pins.get("join@" + hostPort.toLowerCase(java.util.Locale.ROOT)))) return;
		pins.put("join@" + hostPort.toLowerCase(java.util.Locale.ROOT), k);
		save();
	}

	public static synchronized void pin(String hostPort, String fp) {
		String f = clean(fp);
		if (f.isEmpty() || hostPort == null || hostPort.isBlank()) return;
		if (f.equals(pins.get(hostPort.toLowerCase(java.util.Locale.ROOT)))) return;
		pins.put(hostPort.toLowerCase(java.util.Locale.ROOT), f);
		save();
	}
	private static void save() {
		Path f = file;
		if (f == null) return;
		StringBuilder b = new StringBuilder("{");
		for (Map.Entry<String, String> e : new java.util.TreeMap<>(pins).entrySet()) b.append(b.length() > 1 ? ",\n " : "\n ").append('"').append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
		b.append("\n}\n");
		try { Files.createDirectories(f.getParent()); Files.writeString(f, b.toString(), StandardCharsets.UTF_8); } catch (IOException e) { Quiet.seen(e); }
	}
	private static void load(Path f) {
		try {
			if (!Files.isRegularFile(f)) return;
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([0-9A-Za-z]{6,64})\"").matcher(Files.readString(f, StandardCharsets.UTF_8));
			while (m.find()) pins.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
		} catch (IOException e) { Quiet.seen(e); }
	}

	/** Makes every HTTPS connection of this program check pinned addresses by their fingerprint (see above). */
	public static synchronized void install(Path pinFile) {
		file = pinFile;
		load(pinFile);
		if (installed) return;
		try {
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init((java.security.KeyStore) null);
			X509TrustManager usual = null;
			for (TrustManager t : tmf.getTrustManagers()) if (t instanceof X509TrustManager x) usual = x;
			X509TrustManager normal = usual;
			X509ExtendedTrustManager pinned = new X509ExtendedTrustManager() {
				private void check(X509Certificate[] chain, String authType, String peer) throws CertificateException {
					String want = peer == null ? "" : pins.getOrDefault(peer, "");
					if (chain != null && chain.length > 0 && !want.isEmpty()) {
						if (want.equals(fingerprint(chain[0]))) return;
						throw new CertificateException("This Gatekeeper's certificate isn't the one this device was given for " + peer + ": refused");
					}
					normal.checkServerTrusted(chain, authType);
				}
				private String peerOf(SSLSession s) { return s == null || s.getPeerHost() == null ? null : (s.getPeerHost() + ":" + s.getPeerPort()).toLowerCase(java.util.Locale.ROOT); }
				@Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { check(c, a, s instanceof SSLSocket ss ? peerOf(ss.getHandshakeSession()) : null); }
				@Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { check(c, a, e == null ? null : peerOf(e.getHandshakeSession())); }
				@Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { check(c, a, null); }
				@Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { normal.checkClientTrusted(c, a); }
				@Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { normal.checkClientTrusted(c, a); }
				@Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { normal.checkClientTrusted(c, a); }
				@Override public X509Certificate[] getAcceptedIssuers() { return normal.getAcceptedIssuers(); }
			};
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(null, new TrustManager[] {pinned}, null);
			SSLContext.setDefault(ctx);
			HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
			HostnameVerifier usualNames = HttpsURLConnection.getDefaultHostnameVerifier();
			// (a pinned certificate is checked by its fingerprint; it names no host, so the name check is for the others)
			HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> {
				try {
					String want = pins.getOrDefault((host + ":" + session.getPeerPort()).toLowerCase(java.util.Locale.ROOT), "");
					if (!want.isEmpty() && session.getPeerCertificates().length > 0 && session.getPeerCertificates()[0] instanceof X509Certificate x
							&& want.equals(fingerprint(x))) return true;
				} catch (Exception e) { Quiet.seen(e); }
				return usualNames.verify(host, session);
			});
			installed = true;
		} catch (Exception e) { Quiet.seen(e); }
	}

	/** The fingerprint an HTTPS server shows, without trusting it or sending it anything ("" when it can't be read). */
	public static String seen(String url) {
		try {
			SSLContext any = SSLContext.getInstance("TLS");
			X509Certificate[][] got = {null};
			any.init(null, new TrustManager[] {new X509TrustManager() {
				@Override public void checkClientTrusted(X509Certificate[] c, String a) {}
				@Override public void checkServerTrusted(X509Certificate[] c, String a) { got[0] = c; }
				@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
			}}, null);
			String hp = hostPort(url);
			int colon = hp.lastIndexOf(':');
			try (SSLSocket s = (SSLSocket) any.getSocketFactory().createSocket()) {
				s.connect(new java.net.InetSocketAddress(hp.substring(0, colon), Integer.parseInt(hp.substring(colon + 1))), 4000);
				s.setSoTimeout(4000);
				s.startHandshake();
			}
			return got[0] == null || got[0].length == 0 ? "" : fingerprint(got[0][0]);
		} catch (Exception e) { return ""; }
	}
}
