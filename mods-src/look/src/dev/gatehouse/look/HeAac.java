package dev.gatehouse.look;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * R80: HE-AAC sound in an MP4 played an octave low, with chunks of picture and sound skipped, on Linux (the Steam
 * Deck). OpenJFX's Linux player reads the sound's rate from the file's sample description unless the codec header is
 * the basic two-byte kind (qtdemux.c); HE-AAC's header is longer, and its sample description gives the core rate (24
 * kHz), while the decoder (the system's FFmpeg) puts out the full rate (48 kHz). OpenJFX's decoder sets its output up
 * once from the former and never looks again (audiodecoder.c). So, before playing, the sample description's rate in
 * the temporary copy is set to the HE-AAC header's output rate. Four bytes; nothing else in the file changes, and the
 * codec header the decoder needs is left as it is. Only the explicit kind (object type 5 or 29 in the header) is
 * recognised; anything else is left alone.
 */
public final class HeAac {
	private HeAac() {}
	private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

	/** Sets the rate, when it needs it. Returns what it did, for the log ("" when nothing). */
	public static String fix(Path file) {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			long size = ch.size();
			long[] moov = box(ch, 0, size, "moov");
			if (moov == null) return "";
			for (long at = moov[0]; at + 8 <= moov[1]; ) {
				long[] trak = next(ch, at, moov[1]);
				if (trak == null) break;
				at = trak[2];
				if (!type(ch, trak[3]).equals("trak")) continue;
				long[] mdia = box(ch, trak[0], trak[1], "mdia");
				if (mdia == null) continue;
				long[] hdlr = box(ch, mdia[0], mdia[1], "hdlr");
				if (hdlr == null || !text(ch, hdlr[0] + 8, 4).equals("soun")) continue;
				long[] minf = box(ch, mdia[0], mdia[1], "minf"), stbl = minf == null ? null : box(ch, minf[0], minf[1], "stbl"),
						stsd = stbl == null ? null : box(ch, stbl[0], stbl[1], "stsd");
				if (stsd == null) continue;
				long entry = stsd[0] + 8;   // (version and flags, then the number of entries)
				if (!text(ch, entry + 4, 4).equals("mp4a")) continue;
				long entryEnd = entry + (u32(ch, entry) & 0xFFFFFFFFL);
				long rateAt = entry + 32;
				int now = (int) ((u32(ch, rateAt) & 0xFFFFFFFFL) >>> 16);
				byte[] asc = asc(ch, entry + 36, Math.min(entryEnd, size));
				if (asc == null) continue;
				int out = outputRate(asc);
				if (out <= 0 || out == now || out > 65535) continue;
				ByteBuffer b = ByteBuffer.allocate(4);
				b.putInt(out << 16).flip();
				ch.write(b, rateAt);
				return "HE-AAC sound: rate in the file's description set from " + now + " to " + out + " Hz for this device's player";
			}
		} catch (IOException | RuntimeException e) { Quiet.seen(e); }
		return "";
	}

	/** The output rate an explicit HE-AAC header (object type 5 or 29) names, or 0. */
	static int outputRate(byte[] asc) {
		int[] pos = {0};
		int aot = bits(asc, pos, 5);
		if (aot == 31) aot = 32 + bits(asc, pos, 6);
		int fi = bits(asc, pos, 4);
		if (fi == 15) bits(asc, pos, 24);
		bits(asc, pos, 4);   // (channel configuration)
		if (aot != 5 && aot != 29) return 0;
		int ei = bits(asc, pos, 4);
		if (ei == 15) return bits(asc, pos, 24);
		return ei < RATES.length ? RATES[ei] : 0;
	}
	private static int bits(byte[] b, int[] pos, int n) {
		int v = 0;
		for (int i = 0; i < n; i++, pos[0]++) {
			int byteAt = pos[0] >> 3;
			if (byteAt >= b.length) throw new IllegalArgumentException("short header");
			v = (v << 1) | ((b[byteAt] >> (7 - (pos[0] & 7))) & 1);
		}
		return v;
	}

	/** The AudioSpecificConfig in the esds box between from and to, or null. */
	private static byte[] asc(FileChannel ch, long from, long to) throws IOException {
		int n = (int) Math.min(512, to - from);
		if (n <= 0) return null;
		ByteBuffer b = ByteBuffer.allocate(n);
		ch.read(b, from);
		byte[] a = b.array();
		for (int i = 0; i + 4 < n; i++) {
			if (a[i] != 'e' || a[i + 1] != 's' || a[i + 2] != 'd' || a[i + 3] != 's') continue;
			for (int k = i + 8; k < Math.min(n, i + 80); k++) {
				if (a[k] != 0x05) continue;
				int len = 0, p = k + 1;
				for (int q = 0; q < 4 && p < n; q++) { int c = a[p++] & 0xFF; len = (len << 7) | (c & 0x7F); if ((c & 0x80) == 0) break; }
				if (len >= 2 && len <= 16 && p + len <= n) return java.util.Arrays.copyOfRange(a, p, p + len);
			}
		}
		return null;
	}

	// ---- boxes: {start of its contents, its end, the next box's start, its own start}
	private static long[] next(FileChannel ch, long at, long end) throws IOException {
		if (at + 8 > end) return null;
		long size = u32(ch, at) & 0xFFFFFFFFL, head = 8;
		if (size == 1) { ByteBuffer b = ByteBuffer.allocate(8); ch.read(b, at + 8); size = b.flip().getLong(); head = 16; }
		else if (size == 0) size = end - at;
		if (size < head || at + size > end) return null;
		return new long[] {at + head, at + size, at + size, at};
	}
	private static long[] box(FileChannel ch, long from, long end, String want) throws IOException {
		for (long at = from; at + 8 <= end; ) {
			long[] b = next(ch, at, end);
			if (b == null) return null;
			if (type(ch, b[3]).equals(want)) return b;
			at = b[2];
		}
		return null;
	}
	private static String type(FileChannel ch, long boxStart) throws IOException { return text(ch, boxStart + 4, 4); }
	private static String text(FileChannel ch, long at, int n) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(n);
		ch.read(b, at);
		return new String(b.array(), java.nio.charset.StandardCharsets.ISO_8859_1);
	}
	private static int u32(FileChannel ch, long at) throws IOException { ByteBuffer b = ByteBuffer.allocate(4); ch.read(b, at); return b.flip().getInt(); }
}
