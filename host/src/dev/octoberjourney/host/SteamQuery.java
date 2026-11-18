package dev.octoberjourney.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Steam's server query (A2S, R34 stage 2): the question most dedicated game servers answer on their query port,
 * whatever the game. A2S_INFO says the server is up, its name, and how many are on; A2S_PLAYER names them. Over UDP,
 * on this machine only (Gatekeeper asks its own servers). A server may first answer with a challenge number, which is
 * then sent back with the question, as Valve's protocol has it.
 */
final class SteamQuery {
	private SteamQuery() {}
	static final int TIMEOUT = 1500;

	/** {name, map, game, players, max} for the server at host:port, or null when it doesn't answer. */
	static Map<String, Object> info(String host, int port) {
		ByteArrayOutputStream q = new ByteArrayOutputStream();
		q.writeBytes(new byte[] {-1, -1, -1, -1, 0x54});
		q.writeBytes("Source Engine Query\0".getBytes(StandardCharsets.US_ASCII));
		byte[] a = ask(host, port, q.toByteArray(), true);
		if (a == null || a.length < 6 || a[4] != 0x49) return null;
		ByteBuffer b = ByteBuffer.wrap(a, 6, a.length - 6).order(ByteOrder.LITTLE_ENDIAN);   // (after the header and 'I' and the protocol byte)
		try {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("name", str(b)); out.put("map", str(b)); str(b); out.put("game", str(b));
			b.getShort();
			out.put("players", b.get() & 0xFF); out.put("max", b.get() & 0xFF);
			return out;
		} catch (RuntimeException e) { return null; }
	}
	/** The names of who is on, or null when it doesn't answer. */
	static List<String> players(String host, int port) {
		byte[] a = ask(host, port, new byte[] {-1, -1, -1, -1, 0x55, -1, -1, -1, -1}, false);
		if (a == null || a.length < 6 || a[4] != 0x44) return null;
		ByteBuffer b = ByteBuffer.wrap(a, 5, a.length - 5).order(ByteOrder.LITTLE_ENDIAN);
		List<String> out = new ArrayList<>();
		try {
			int n = b.get() & 0xFF;
			for (int i = 0; i < n; i++) { b.get(); String name = str(b); b.getInt(); b.getFloat(); if (!name.isBlank()) out.add(name); }
		} catch (RuntimeException e) { /* (a short answer: the names read so far) */ }
		return out;
	}
	/** Sends a question; answers a challenge ('A' and four bytes) once. append: the challenge goes after the question
	 *  (A2S_INFO), else it replaces its last four bytes (A2S_PLAYER). */
	private static byte[] ask(String host, int port, byte[] q, boolean append) {
		try (DatagramSocket s = new DatagramSocket()) {
			s.setSoTimeout(TIMEOUT);
			InetAddress to = InetAddress.getByName(host);
			byte[] a = send(s, to, port, q);
			if (a != null && a.length >= 9 && a[4] == 0x41) {
				byte[] again;
				if (append) { again = new byte[q.length + 4]; System.arraycopy(q, 0, again, 0, q.length); System.arraycopy(a, 5, again, q.length, 4); }
				else { again = q.clone(); System.arraycopy(a, 5, again, q.length - 4, 4); }
				a = send(s, to, port, again);
			}
			return a;
		} catch (IOException | RuntimeException e) { return null; }
	}
	private static byte[] send(DatagramSocket s, InetAddress to, int port, byte[] q) throws IOException {
		s.send(new DatagramPacket(q, q.length, to, port));
		byte[] buf = new byte[1400];
		DatagramPacket p = new DatagramPacket(buf, buf.length);
		try { s.receive(p); } catch (java.net.SocketTimeoutException e) { return null; }
		byte[] out = new byte[p.getLength()];
		System.arraycopy(buf, 0, out, 0, out.length);
		return out;
	}
	private static String str(ByteBuffer b) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		for (byte c; (c = b.get()) != 0; ) o.write(c);
		return o.toString(StandardCharsets.UTF_8);
	}
}
