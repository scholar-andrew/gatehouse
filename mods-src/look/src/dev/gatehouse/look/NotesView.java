package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;

/**
 * A program's patch notes, newest on top, in both halves (Gatehouse's Updates and Gatekeeper's). The versions the
 * person hasn't seen yet sit in a blue box with a red dot beside the version number; "seen" is the newest version they
 * had seen when the list was drawn ("" = none yet, so nothing is marked: a first start isn't all new).
 *
 * <p>Where a version was last seen is kept in a small file per program (see {@link #seen} / {@link #markSeen}). The
 * windows draw the list with what was seen before, then mark it seen once the person has looked, so the boxes stay
 * while they read and are gone the next time they open Updates.
 */
public final class NotesView {
	private NotesView() {}

	/** True when version a is newer than b (numbers compared part by part; "3.20.10" is newer than "3.20.9"). */
	public static boolean newer(String a, String b) {
		String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}

	/** The version newest in these notes ("" when there are none). */
	public static String newest(Map<String, Object> notes) {
		Object v = notes.get("version");
		if (v != null && !String.valueOf(v).isEmpty()) return String.valueOf(v);
		if (notes.get("changes") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> c && c.get("version") != null) return String.valueOf(c.get("version"));
		return "";
	}

	/** Are there notes newer than what was seen? (Nothing seen yet counts as nothing new.) */
	public static boolean unseen(Map<String, Object> notes, String seen) {
		String v = newest(notes);
		return !seen.isEmpty() && !v.isEmpty() && newer(v, seen);
	}

	/** What was last seen, kept in this file ("" when there isn't one yet). */
	public static String seen(Path file) {
		try { return Files.readString(file, StandardCharsets.UTF_8).trim(); } catch (IOException | RuntimeException e) { return ""; }
	}
	/** Marks these notes seen (only ever forwards: an older copy's notes never mark a newer version unseen). */
	public static void markSeen(Path file, Map<String, Object> notes) {
		String v = newest(notes), was = seen(file);
		if (v.isEmpty() || (!was.isEmpty() && !newer(v, was))) return;
		try { Files.createDirectories(file.getParent()); Files.writeString(file, v + "\n", StandardCharsets.UTF_8); } catch (IOException | RuntimeException ignored) {}
	}
	/** The first time there is no file: everything up to now counts as seen, so nothing is marked new on a first start. */
	public static String seenOrNow(Path file, Map<String, Object> notes) {
		String s = seen(file);
		if (s.isEmpty()) { markSeen(file, notes); return ""; }
		return s;
	}

	/** A tab's title with a red dot after it when it has something new. */
	public static String tabTitle(String title, boolean dot) {
		return dot ? "<html>" + title + " <font color='#E5484D'>●</font></html>" : title;
	}

	/** The list, scrolling: "head" is the grey line on top ("" for none), "none" what is said when there are no notes. */
	public static JScrollPane build(Map<String, Object> notes, String head, String seen, String none) {
		JPanel list = new Track();
		list.setLayout(new Column());
		list.setBorder(BorderFactory.createEmptyBorder(6, 12, 12, 12));
		if (!head.isEmpty()) { JLabel h = new JLabel(head); h.setForeground(GameShell.DIM); h.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0)); h.setAlignmentX(Component.LEFT_ALIGNMENT); list.add(h); }
		boolean any = false;
		if (notes.get("changes") instanceof List<?> l) for (Object o : l) {
			if (!(o instanceof Map<?, ?> c)) continue;
			any = true;
			String v = String.valueOf(c.get("version") == null ? "" : c.get("version")), date = c.get("date") == null ? "" : String.valueOf(c.get("date"));
			boolean fresh = !seen.isEmpty() && !v.isEmpty() && newer(v, seen);
			JPanel entry = fresh ? new Box() : new JPanel() { @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); } };
			entry.setOpaque(false);
			entry.setLayout(new Column());
			entry.setBorder(fresh ? BorderFactory.createEmptyBorder(8, 12, 10, 12) : BorderFactory.createEmptyBorder(4, 0, 4, 0));
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) {   // (only as tall as its line: no room left over is given to it)
				@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
			};
			top.setOpaque(false);
			JLabel h = new JLabel(v + (date.isEmpty() ? "" : " (" + date + ")"));
			h.setFont(h.getFont().deriveFont(java.awt.Font.BOLD, 14f)); h.setForeground(GameShell.TEXT);
			top.add(h);
			if (fresh) { top.add(javax.swing.Box.createHorizontalStrut(8)); top.add(new Dot()); top.add(javax.swing.Box.createHorizontalStrut(6)); JLabel nw = new JLabel("NEW"); nw.setFont(nw.getFont().deriveFont(java.awt.Font.BOLD, 11f)); nw.setForeground(new Color(0xFF8A8E)); top.add(nw); }
			top.setAlignmentX(Component.LEFT_ALIGNMENT);
			entry.add(top);
			if (c.get("notes") instanceof List<?> ns) for (Object n : ns) entry.add(line("• " + n));
			entry.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (fresh) list.add(javax.swing.Box.createVerticalStrut(6));
			list.add(entry);
		}
		if (!any) { JLabel e = new JLabel(none); e.setForeground(GameShell.DIM); e.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0)); e.setAlignmentX(Component.LEFT_ALIGNMENT); list.add(e); }
		JPanel holder = new Track(); holder.setLayout(new BorderLayout()); holder.setOpaque(false); holder.add(list, BorderLayout.NORTH);
		list.setOpaque(false);
		JScrollPane sp = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(null); sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.getViewport().setOpaque(false); sp.setOpaque(false);
		return sp;
	}

	/** A note's line: words wrap at whatever width the list has. */
	private static JComponent line(String text) {
		JTextArea a = new JTextArea() {
			@Override public Dimension getPreferredSize() {
				java.awt.Container p = getParent();
				int w = p == null ? 0 : p.getWidth() - p.getInsets().left - p.getInsets().right;
				if (w > 0 && getWidth() != w) setSize(w, Short.MAX_VALUE);
				return super.getPreferredSize();
			}
			@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		};
		// (its cursor stays put, and the words go in after: a cursor that followed them asked the list to scroll down to it)
		((javax.swing.text.DefaultCaret) a.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
		a.setText(text);
		a.setEditable(false); a.setFocusable(false); a.setOpaque(false); a.setLineWrap(true); a.setWrapStyleWord(true);
		a.setFont(GameShell.font(14f, false)); a.setForeground(GameShell.TEXT);
		a.setBorder(BorderFactory.createEmptyBorder(3, 4, 1, 0));
		a.setAlignmentX(Component.LEFT_ALIGNMENT);
		return a;
	}

	/** Its parts one under another, each as wide as it is and as tall as it asks at that width: measured afresh each
	 *  time (Swing's BoxLayout keeps what it measured before the width was known, which left room under the notes). */
	private static final class Column implements java.awt.LayoutManager {
		@Override public void addLayoutComponent(String n, Component c) {}
		@Override public void removeLayoutComponent(Component c) {}
		@Override public Dimension preferredLayoutSize(java.awt.Container p) {
			java.awt.Insets in = p.getInsets();
			int w = Math.max(0, p.getWidth() - in.left - in.right), h = 0, pw = 0;
			for (Component c : p.getComponents()) {
				if (!c.isVisible()) continue;
				if (w > 0 && c.getWidth() != w) c.setSize(w, Math.max(1, c.getHeight()));
				Dimension d = c.getPreferredSize(); h += d.height; pw = Math.max(pw, d.width);
			}
			return new Dimension((w > 0 ? w : pw) + in.left + in.right, h + in.top + in.bottom);
		}
		@Override public Dimension minimumLayoutSize(java.awt.Container p) { return preferredLayoutSize(p); }
		@Override public void layoutContainer(java.awt.Container p) {
			java.awt.Insets in = p.getInsets();
			int w = Math.max(0, p.getWidth() - in.left - in.right), y = in.top;
			for (Component c : p.getComponents()) {
				if (!c.isVisible()) continue;
				c.setSize(w, Math.max(1, c.getHeight()));
				int h = c.getPreferredSize().height;
				c.setBounds(in.left, y, w, h); y += h;
			}
		}
	}

	/** The blue box around a version not seen yet. */
	private static final class Box extends JPanel {
		@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			RoundRectangle2D r = new RoundRectangle2D.Double(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
			g.setColor(new Color(0x1C3550)); g.fill(r);
			g.setColor(GameShell.ACC); g.setStroke(new BasicStroke(1.5f)); g.draw(r);
			g.dispose();
		}
	}
	/** The red dot beside a new version's number. */
	private static final class Dot extends JComponent {
		Dot() { setPreferredSize(new Dimension(10, 10)); setMaximumSize(new Dimension(10, 10)); }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(GameShell.RED); g.fill(new Ellipse2D.Double(0, (getHeight() - 10) / 2.0, 10, 10));
			g.dispose();
		}
	}
	/** As wide as the scroll pane, never wider, so the notes wrap rather than run off the side. */
	private static final class Track extends JPanel implements Scrollable {
		private int laidAt = -1;
		// (the notes' lines are measured at the width they get: when that width changes, they are measured again, so
		// each box is as tall as its wrapped lines and no taller)
		@Override public void doLayout() {
			super.doLayout();
			if (getWidth() != laidAt) {
				laidAt = getWidth();
				javax.swing.SwingUtilities.invokeLater(() -> { remeasure(this); revalidate(); repaint(); });
			}
		}
		private static void remeasure(java.awt.Container c) { for (Component k : c.getComponents()) { k.invalidate(); if (k instanceof java.awt.Container cc) remeasure(cc); } }
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
		@Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
}
