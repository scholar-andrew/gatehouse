package dev.gatehouse.look;

/**
 * The sound a constellation makes when it is ready and about to update together: four soft notes rising (C, E, G and
 * the C above), the last one ringing over a quiet chord. It is made here, as a wave of numbers, so nothing has to be
 * shipped as a sound file; a machine with no sound simply stays silent.
 */
public final class Chime {
	private Chime() {}
	private static volatile long last = 0;

	/** How long it sounds, in milliseconds (for a program that is about to close: wait this long first). */
	public static final int MILLIS = 2300;

	/** The samples: 44.1 kHz, 16 bit, one channel, little-endian. */
	public static byte[] wave() {
		float rate = 44100f;
		double[] rise = {523.25, 659.25, 783.99, 1046.50};
		int step = (int) (rate * 0.15), tail = (int) (rate * 1.7);
		double[] mix = new double[step * rise.length + tail];
		for (int n = 0; n < rise.length; n++) {
			boolean lastNote = n == rise.length - 1;
			for (int i = 0; i < tail && n * step + i < mix.length; i++) {
				double t = i / rate, env = Math.min(1, t / 0.012) * Math.exp(-t * (lastNote ? 2.0 : 4.0));
				// (a bell-like tone: the note, a little of its octave, and a touch of a higher partial that dies quickly)
				mix[n * step + i] += env * (Math.sin(2 * Math.PI * rise[n] * t) + 0.22 * Math.sin(4 * Math.PI * rise[n] * t) + 0.10 * Math.exp(-t * 9) * Math.sin(6 * Math.PI * rise[n] * t));
			}
		}
		// (under the last note: the chord it belongs to, an octave down and quiet)
		int at = step * (rise.length - 1);
		for (double f : new double[] {261.63, 329.63, 392.00})
			for (int i = 0; at + i < mix.length; i++) { double t = i / rate; mix[at + i] += 0.28 * Math.min(1, t / 0.06) * Math.exp(-t * 2.2) * Math.sin(2 * Math.PI * f * t); }
		byte[] pcm = new byte[mix.length * 2];
		for (int i = 0; i < mix.length; i++) {
			double fade = Math.min(1, (mix.length - i) / (rate * 0.25));   // (no click at the end)
			int v = (int) Math.max(-32767, Math.min(32767, mix[i] * fade * 0.17 * 32767));
			pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8);
		}
		return pcm;
	}

	/** Plays it, in the background; not twice within ten seconds (a client may be told "go" by more than one Gatekeeper). */
	public static void constellation() {
		if (System.getenv("OJ_HOST_DIR") != null || System.getenv("OJ_HOME_DRY") != null) return;   // (a stand-in under test stays silent)
		long now = System.currentTimeMillis();
		if (now - last < 10_000) return;
		last = now;
		Thread t = new Thread(() -> {
			try {
				byte[] pcm = wave();
				javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100f, 16, 1, true, false);
				try (javax.sound.sampled.SourceDataLine line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt)) {
					line.open(fmt); line.start(); line.write(pcm, 0, pcm.length); line.drain();
				}
			} catch (Exception | LinkageError silent) { /* no sound on this machine */ }
		}, "constellation-chime");
		t.setDaemon(true);
		t.start();
	}

	private static volatile long lastMessage = 0;
	/** A new chat message: two soft notes, falling (not more than once in five seconds). */
	public static void message() {
		if (System.getenv("OJ_HOST_DIR") != null || System.getenv("OJ_HOME_DRY") != null) return;
		long now = System.currentTimeMillis();
		if (now - lastMessage < 5000) return;
		lastMessage = now;
		Thread t = new Thread(() -> {
			try {
				float rate = 44100f;
				double[] notes = {987.77, 783.99};
				int step = (int) (rate * 0.11), tail = (int) (rate * 0.5);
				double[] mix = new double[step + tail];
				for (int n = 0; n < notes.length; n++)
					for (int i = 0; i < tail && n * step + i < mix.length; i++) { double tt = i / rate; mix[n * step + i] += Math.min(1, tt / 0.008) * Math.exp(-tt * 7) * (Math.sin(2 * Math.PI * notes[n] * tt) + 0.2 * Math.sin(4 * Math.PI * notes[n] * tt)); }
				byte[] pcm = new byte[mix.length * 2];
				for (int i = 0; i < mix.length; i++) { int v = (int) Math.max(-32767, Math.min(32767, mix[i] * 0.16 * 32767)); pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8); }
				javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(rate, 16, 1, true, false);
				try (javax.sound.sampled.SourceDataLine line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt)) { line.open(fmt); line.start(); line.write(pcm, 0, pcm.length); line.drain(); }
			} catch (Exception | LinkageError silent) { /* no sound on this machine */ }
		}, "message-chime");
		t.setDaemon(true);
		t.start();
	}

	/** Testing: writes it as a sound file to listen to. Argument: the .wav file to write. */
	public static void main(String[] a) throws Exception {
		byte[] pcm = wave();
		javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(44100f, 16, 1, true, false);
		javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm), fmt, pcm.length / 2), javax.sound.sampled.AudioFileFormat.Type.WAVE, new java.io.File(a[0]));
		System.out.println(pcm.length / 2 / 44100.0 + " seconds");
	}
}
