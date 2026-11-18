package dev.gatehouse.look;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Downloads that say how far along they are (R14), and the bar that shows it, in both halves: Gatehouse fetching a
 * newer Gatehouse, Gatekeeper fetching a newer Gatekeeper, and a server's pack updating before Play.
 */
public final class Download {
	private Download() {}

	/** How far a download has come: bytes so far, bytes in all (0 when the place didn't say), and when it started. */
	public static final class State {
		public volatile long done, total, started = System.currentTimeMillis();
		public volatile String what = "";
		public volatile boolean active;
		/** "0.6 of 1.3 MB, about 4 s left" (or what can be said without a total). */
		public String words() {
			long d = done, t = total;
			String s = size(d) + (t > 0 ? " of " + size(t) : "");
			long ms = System.currentTimeMillis() - started;
			if (t > 0 && d > 0 && ms > 600) {
				long left = (long) ((t - d) / (d / (double) ms) / 1000);
				if (left >= 1) s += ", about " + (left < 60 ? left + " s" : (left / 60) + " min") + " left";
			}
			return s;
		}
	}

	public static String size(long b) { return b < 1_000_000 ? Math.max(1, Math.round(b / 1000.0)) + " KB" : String.format(Locale.ROOT, "%.1f MB", b / 1e6); }

	/** Reads it all, noting progress in "st" as it goes ("total" is what the place said the size is, or -1). */
	public static byte[] read(InputStream in, long total, State st) throws IOException {
		st.done = 0; st.total = Math.max(0, total); st.started = System.currentTimeMillis(); st.active = true;
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 && total < Integer.MAX_VALUE ? (int) total : 1 << 16);
			byte[] buf = new byte[32 * 1024];
			for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); st.done += n; }
			return out.toByteArray();
		} finally { st.active = false; }
	}

	/** The bar: a line of words above a slim bar in the program's blue. Fed with {@link #show}. */
	public static final class Bar extends JPanel {
		private final JLabel words = new JLabel(" ");
		private double part = -1;
		private final JComponent track = new JComponent() {
			@Override protected void paintComponent(Graphics g0) {
				Graphics2D g = (Graphics2D) g0.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth(), h = getHeight();
				g.setColor(new Color(0x23384D)); g.fill(new RoundRectangle2D.Double(0, 0, w, h, h, h));
				g.setColor(GameShell.ACC);
				if (part >= 0) g.fill(new RoundRectangle2D.Double(0, 0, Math.max(h, w * Math.min(1, part)), h, h, h));
				else { long t = System.currentTimeMillis() % 1400; double x = (w
						+ w / 3.0) * t / 1400.0 - w / 3.0; g.fill(new RoundRectangle2D.Double(x, 0, w / 3.0, h, h, h)); }   // (no size known: a moving piece)
				g.dispose();
			}
		};
		private final javax.swing.Timer spin = new javax.swing.Timer(40, e -> track.repaint());
		public Bar() {
			super(new BorderLayout(0, 4));
			setOpaque(false);
			words.setForeground(GameShell.DIM); words.setFont(GameShell.font(13f, false));
			track.setPreferredSize(new Dimension(220, 8));
			add(words, BorderLayout.NORTH); add(track, BorderLayout.CENTER);
			setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
			setVisible(false);
		}
		/** Shows how far it has come ("lead" goes before the numbers, as "Fetching Gatehouse 3.21.0:"). */
		public void show(String lead, State st) {
			part = st.total > 0 ? st.done / (double) st.total : -1;
			words.setText((lead.isEmpty() ? "" : lead + " ") + st.words());
			if (part < 0) { if (!spin.isRunning()) spin.start(); } else spin.stop();
			setVisible(true); track.repaint();
		}
		/** Shows a count: "lead" then "n of m" (files, say). */
		public void count(String lead, long n, long m) {
			part = m > 0 ? n / (double) m : -1;
			words.setText(lead);
			if (part < 0) { if (!spin.isRunning()) spin.start(); } else spin.stop();
			setVisible(true); track.repaint();
		}
		public void done() { spin.stop(); setVisible(false); }
	}
}
