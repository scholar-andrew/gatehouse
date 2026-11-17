package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The frame round a window that is laid out like a game's menu: a bar across the top with the program's name and its
 * sections (flipped with the bumpers, or Q and E), the page itself in the middle on a dark gradient, and a strip
 * along the bottom with where the program stands on the left and what the buttons do on the right.
 *
 * It only draws and arranges. Which page a section shows is the window's own business: it is told when a section is
 * picked, and says back which one to light up.
 */
public final class GameShell extends JPanel {
	public static final Color BG0 = new Color(0x0D141D), BG1 = SteamLook.BACK, TEXT = new Color(0xE6EEF5), DIM = SteamLook.DIM, ACC = SteamLook.ACCENT, RED = new Color(0xE5484D);
	private static volatile boolean pad;

	/** Whether the prompts show a controller's buttons (true) or the keyboard's keys. It follows whichever was used last. */
	public static boolean pad() { return pad; }
	public static void pad(boolean on) {
		if (pad == on) return;
		pad = on;
		javax.swing.SwingUtilities.invokeLater(() -> { for (java.awt.Window w : java.awt.Window.getWindows()) if (w.isShowing()) { again(w); w.repaint(); } });
	}
	private static void again(java.awt.Container c) {
		for (java.awt.Component x : c.getComponents()) { if (x instanceof Glyph || x instanceof Hint) ((JComponent) x).revalidate(); if (x instanceof java.awt.Container k) again(k); }
	}

	static Color a(Color c, int alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha); }
	static Graphics2D smooth(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g2;
	}
	static Font font(float size, boolean bold) { return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 12).deriveFont(size); }
	static Font spaced(float size, float tracking) { return font(size, true).deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, tracking)); }

	// ---- a button's picture: a round controller button, or a keyboard key
	static int glyphWidth(Graphics2D g, String padName, String key) {
		if (pad && padName.length() == 1) return 24;
		return Math.max(26, g.getFontMetrics(font(11.5f, true)).stringWidth(pad ? padName : key) + 14);
	}
	static int glyph(Graphics2D g, String padName, String key, int x, int cy) {
		if (pad && padName.length() == 1) {
			g.setColor(TEXT); g.fill(new Ellipse2D.Double(x, cy - 12, 24, 24));
			Font f = font(13f, true); g.setFont(f); g.setColor(BG0);
			g.drawString(padName, x + 12 - g.getFontMetrics().stringWidth(padName) / 2f, cy + 5);
			return 24;
		}
		String s = pad ? padName : key; Font f = font(11.5f, true); g.setFont(f);
		int w = glyphWidth(g, padName, key);
		g.setColor(a(TEXT, 28)); g.fill(new RoundRectangle2D.Double(x, cy - 11, w, 22, 7, 7));
		g.setColor(a(TEXT, 150)); g.setStroke(new BasicStroke(1.2f)); g.draw(new RoundRectangle2D.Double(x, cy - 11, w, 22, 7, 7));
		g.setColor(TEXT); g.drawString(s, x + w / 2f - g.getFontMetrics().stringWidth(s) / 2f, cy + 4.5f);
		return w;
	}

	/** A button's picture by itself (beside a real button, to say which press reaches it). */
	public static final class Glyph extends JComponent {
		private final String padName, key;
		private java.awt.Component of;
		public Glyph(String padName, String key) { this.padName = padName; this.key = key; }
		/** Beside this button: shown only while the button is. */
		public Glyph of(java.awt.Component button) {
			of = button;
			button.addComponentListener(new java.awt.event.ComponentAdapter() {
				@Override public void componentShown(java.awt.event.ComponentEvent e) { revalidate(); repaint(); }
				@Override public void componentHidden(java.awt.event.ComponentEvent e) { revalidate(); repaint(); }
			});
			return this;
		}
		private boolean nothing() { return (!pad && key.isEmpty()) || (of != null && !of.isVisible()); }
		@Override public Dimension getPreferredSize() { if (nothing()) return new Dimension(0, 26); return new Dimension(Math.max(26, getFontMetrics(font(11.5f, true)).stringWidth(pad ? padName : key) + 16), 26); }
		@Override protected void paintComponent(Graphics g0) { if (nothing()) return; Graphics2D g = smooth(g0); int w = glyphWidth(g, padName, key); glyph(g, padName, key, (getWidth() - w) / 2, getHeight() / 2); g.dispose(); }
	}

	/** A button's picture with what it does here, in words ("A Select"): something to read, not to press. */
	public static final class Hint extends JComponent {
		private final String padName, key; private String label;
		public Hint(String padName, String key, String label) { this.padName = padName; this.key = key; this.label = label; }
		public void label(String l) { label = l; revalidate(); repaint(); }
		@Override public Dimension getPreferredSize() { return new Dimension(Math.max(26, getFontMetrics(font(11.5f, true)).stringWidth(padName.length() > key.length() ? padName : key) + 14) + 8 + getFontMetrics(font(13.5f, false)).stringWidth(label) + 4, 30); }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = smooth(g0);
			int x = glyph(g, padName, key, 0, getHeight() / 2) + 8;
			g.setFont(font(13.5f, false)); g.setColor(TEXT); g.drawString(label, x, getHeight() / 2 + 5);
			g.dispose();
		}
	}

	// ---- a section's name in the top bar
	private final class Tab extends JButton {
		final String name, label; int badge;
		Tab(String name, String label) {
			super(label); this.name = name; this.label = label.toUpperCase(java.util.Locale.ROOT);
			setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false); setRolloverEnabled(true);
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			addActionListener(e -> pick.accept(name));
		}
		@Override public Dimension getPreferredSize() { return new Dimension(getFontMetrics(spaced(14.5f, 0.08f)).stringWidth(label) + 40, 64); }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = smooth(g0);
			boolean sel = name.equals(lit), over = getModel().isRollover();
			Font f = spaced(14.5f, 0.08f); g.setFont(f);
			int tw = g.getFontMetrics().stringWidth(label), x = (getWidth() - tw) / 2 - (badge > 0 ? 6 : 0);
			if (isFocusOwner()) { g.setColor(a(ACC, 190)); g.setStroke(new BasicStroke(1.6f)); g.draw(new RoundRectangle2D.Double(4, 12, getWidth() - 8, 38, 12, 12)); }
			g.setColor(sel ? TEXT : over ? a(TEXT, 210) : DIM); g.drawString(label, x, 38);
			if (badge < 0) { g.setColor(RED); g.fill(new Ellipse2D.Double(x + tw + 4, 19, 9, 9)); }   // (something new there)
			if (sel) { g.setColor(a(ACC, 60)); g.fill(new RoundRectangle2D.Double(x - 6, 55, tw + 12, 9, 8, 8)); g.setColor(ACC); g.fill(new RoundRectangle2D.Double(x - 2, 58, tw + 4, 3.5, 3, 3)); }
			if (badge > 0) {
				String n = badge > 99 ? "99" : String.valueOf(badge);
				g.setColor(RED); g.fill(new Ellipse2D.Double(x + tw + 3, 15, 18, 18));
				g.setFont(font(11.5f, true)); g.setColor(Color.WHITE); g.drawString(n, x + tw + 12 - g.getFontMetrics().stringWidth(n) / 2f, 28.5f);
			}
			g.dispose();
		}
	}

	private final Map<String, Tab> tabs = new LinkedHashMap<>();
	private final Consumer<String> pick;
	private String lit = "";
	private final JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0)), right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
	private final JPanel middle = new JPanel(new BorderLayout());
	private final JLabel corner = new JLabel(" ");
	private final JPanel bar = new JPanel(new Row());

	/** The sections in one row, in the middle. When the window is too narrow for them as they are, the bumpers'
	 *  pictures go first, then the room round each name is squeezed: no section is ever pushed out of sight. */
	private static final class Row implements java.awt.LayoutManager {
		@Override public void addLayoutComponent(String n, java.awt.Component c) {}
		@Override public void removeLayoutComponent(java.awt.Component c) {}
		@Override public Dimension preferredLayoutSize(java.awt.Container p) { int w = 0; for (java.awt.Component c : p.getComponents()) w += c.getPreferredSize().width; return new Dimension(w, 64); }
		@Override public Dimension minimumLayoutSize(java.awt.Container p) { return new Dimension(40, 64); }
		@Override public void layoutContainer(java.awt.Container p) {
			java.awt.Component[] cs = p.getComponents();
			int[] w = new int[cs.length]; int total = 0, tabsN = 0;
			for (int i = 0; i < cs.length; i++) { w[i] = cs[i].getPreferredSize().width; total += w[i]; if (cs[i] instanceof Tab) tabsN++; }
			int room = p.getWidth();
			if (total > room) for (int i = 0; i < cs.length; i++) if (!(cs[i] instanceof Tab)) { total -= w[i]; w[i] = 0; }
			if (total > room && tabsN > 0) { int cutEach = Math.min(34, (total - room + tabsN - 1) / tabsN); for (int i = 0; i < cs.length; i++) if (cs[i] instanceof Tab) { w[i] -= cutEach; total -= cutEach; } }
			int x = Math.max(0, (room - total) / 2);
			for (int i = 0; i < cs.length; i++) { cs[i].setBounds(x, 0, w[i], p.getHeight()); x += w[i]; }
		}
	}

	/** The sections in the top bar, replaced: their names (what pick is told), and what each is shown as. */
	public void sections(String[] names, java.util.function.UnaryOperator<String> shown) {
		bar.removeAll(); tabs.clear();
		if (names.length > 0) {
			JPanel lb = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 19)); lb.setOpaque(false); lb.add(new Glyph("LB", "Q")); bar.add(lb);
			for (String s : names) { Tab t = new Tab(s, shown.apply(s)); tabs.put(s, t); bar.add(t); }
			JPanel rb = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 19)); rb.setOpaque(false); rb.add(new Glyph("RB", "E")); bar.add(rb);
		}
		bar.revalidate(); bar.repaint();
	}

	/** A tabbed pane with no row of tabs of its own: the top bar shows them instead. */
	private static final class NoStrip extends javax.swing.plaf.basic.BasicTabbedPaneUI {
		@Override protected int calculateTabAreaHeight(int placement, int runs, int maxTabHeight) { return 0; }
		@Override protected int calculateTabHeight(int placement, int index, int fontHeight) { return 0; }
		@Override protected void paintTabArea(Graphics g, int placement, int selected) {}
		@Override protected void paintContentBorder(Graphics g, int placement, int selected) {}
		@Override protected java.awt.Insets getContentBorderInsets(int placement) { return new java.awt.Insets(0, 0, 0, 0); }
		@Override protected java.awt.Insets getTabAreaInsets(int placement) { return new java.awt.Insets(0, 0, 0, 0); }
	}
	/** Takes a tabbed pane's own row of tabs away (something else chooses its tab: the top bar, or buttons). */
	public static void hideTabs(javax.swing.JTabbedPane pane) { pane.setUI(new NoStrip()); pane.setFocusable(false); }
	private static String key(String title) { return title.endsWith(" (new)") ? title.substring(0, title.length() - 6) : title; }

	/** A shell whose sections are a tabbed pane's tabs, kept in step with it as tabs come, go, are renamed or chosen
	 *  (a title ending " (new)" gets a red dot instead). The pane's own row of tabs is taken away. shown: what a tab's
	 *  title is called in the bar. */
	public static GameShell mirror(String title, javax.swing.JTabbedPane pane, java.util.function.UnaryOperator<String> shown) {
		GameShell[] s = {null};
		s[0] = new GameShell(title, new String[0], name -> { for (int i = 0; i < pane.getTabCount(); i++) if (key(pane.getTitleAt(i)).equals(name)) { pane.setSelectedIndex(i); return; } });
		pane.setUI(new NoStrip());
		pane.setFocusable(false);
		String[] last = {null};
		Runnable sync = () -> {
			List<String> keys = new ArrayList<>(); StringBuilder sig = new StringBuilder();
			for (int i = 0; i < pane.getTabCount(); i++) { keys.add(key(pane.getTitleAt(i))); sig.append(pane.getTitleAt(i)).append('|'); }
			if (!sig.toString().equals(last[0])) {
				last[0] = sig.toString();
				s[0].sections(keys.toArray(new String[0]), shown);
				for (int i = 0; i < pane.getTabCount(); i++) if (pane.getTitleAt(i).endsWith(" (new)")) s[0].badge(keys.get(i), -1);
			}
			int sel = pane.getSelectedIndex();
			s[0].light(sel < 0 ? "" : key(pane.getTitleAt(sel)));
		};
		pane.addChangeListener(e -> sync.run());
		boolean[] was = {false};
		javax.swing.Timer tick = new javax.swing.Timer(600, null);
		tick.addActionListener(e -> { if (s[0].isDisplayable()) was[0] = true; else if (was[0]) { tick.stop(); return; } sync.run(); });
		tick.start();
		sync.run();
		return s[0];
	}

	/** A page with room round it, on a sheet: for a window that is one plain page. */
	public static JComponent padded(JComponent page) {
		JPanel m = new JPanel(new BorderLayout());
		m.setOpaque(false);
		m.setBorder(BorderFactory.createEmptyBorder(14, 24, 12, 24));
		m.add(sheet(page), BorderLayout.CENTER);
		return m;
	}

	/** title: the program's name, shown in capitals beside its mark. pick: told a section's name when it is chosen. */
	public GameShell(String title, String[] sections, Consumer<String> pick) {
		super(new BorderLayout());
		this.pick = pick;
		setOpaque(true);
		String shown = title.toUpperCase(java.util.Locale.ROOT);
		JPanel top = new JPanel(new BorderLayout()) {
			@Override protected void paintComponent(Graphics g0) {
				Graphics2D g = smooth(g0);
				g.setColor(a(BG0, 150)); g.fillRect(0, 0, getWidth(), 64); g.setColor(a(TEXT, 20)); g.fillRect(0, 64, getWidth(), 1);
				Path2D gate = new Path2D.Double();   // (a small gatehouse: two towers and an arch)
				gate.moveTo(24, 48); gate.lineTo(24, 18); gate.lineTo(29, 18); gate.lineTo(29, 22); gate.lineTo(33, 22); gate.lineTo(33, 18); gate.lineTo(38, 18); gate.lineTo(38, 27);
				gate.lineTo(46, 27); gate.lineTo(46, 18); gate.lineTo(51, 18); gate.lineTo(51, 22); gate.lineTo(55, 22); gate.lineTo(55, 18); gate.lineTo(60, 18); gate.lineTo(60, 48);
				gate.lineTo(48, 48); gate.lineTo(48, 40); gate.curveTo(48, 32, 36, 32, 36, 40); gate.lineTo(36, 48); gate.closePath();
				g.setColor(ACC); g.fill(gate);
				g.setFont(spaced(17f, 0.12f)); g.setColor(TEXT); g.drawString(shown, 72, 40);
				g.dispose();
			}
		};
		top.setOpaque(false);
		top.setPreferredSize(new Dimension(100, 65));
		bar.setOpaque(false);
		sections(sections, s -> s);
		// (the same room on both sides, so the sections sit in the middle of the window)
		JPanel west = new JPanel() { @Override public Dimension getPreferredSize() { return new Dimension(side(), 64); } }; west.setOpaque(false);
		JPanel east = new JPanel(new BorderLayout()) { @Override public Dimension getPreferredSize() { return new Dimension(Math.max(24, Math.min(230, GameShell.this.getWidth() - side() - bar.getPreferredSize().width)), 64); } }; east.setOpaque(false);
		corner.setForeground(DIM); corner.setFont(font(12.5f, false)); corner.setHorizontalAlignment(JLabel.RIGHT); corner.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 24));
		east.add(corner, BorderLayout.CENTER);
		top.add(west, BorderLayout.WEST); top.add(bar, BorderLayout.CENTER); top.add(east, BorderLayout.EAST);
		JPanel bottom = new JPanel(new BorderLayout()) {
			@Override protected void paintComponent(Graphics g0) { Graphics2D g = smooth(g0); g.setColor(a(BG0, 215)); g.fillRect(0, 0, getWidth(), getHeight()); g.setColor(a(TEXT, 20)); g.fillRect(0, 0, getWidth(), 1); g.dispose(); }
		};
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
		left.setOpaque(false); right.setOpaque(false);
		bottom.add(left, BorderLayout.WEST); bottom.add(right, BorderLayout.EAST);
		middle.setOpaque(false);
		add(top, BorderLayout.NORTH); add(middle, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH);
	}

	@Override protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		int w = getWidth(), h = getHeight();
		g.setPaint(new GradientPaint(0, 0, BG1, 0, h, BG0)); g.fillRect(0, 0, w, h);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(w * 0.25, 0), Math.max(1f, w * 0.6f), new float[] {0f, 1f}, new Color[] {a(ACC, 34), a(ACC, 0)})); g.fillRect(0, 0, w, h);
		g.dispose();
	}

	/** How wide the left of the top bar is: as wide as the right when there is room (so the sections sit in the
	 *  middle), never narrower than the program's mark and name. */
	private int side() { return Math.max(205, Math.min(230, (getWidth() - bar.getPreferredSize().width) / 2)); }

	/** The page: whatever the window shows between the two bars. */
	public void body(JComponent page) { middle.removeAll(); middle.add(page, BorderLayout.CENTER); middle.revalidate(); }
	/** Which section is lit in the top bar ("" for none: a page that belongs to no section). */
	public void light(String section) { lit = section == null ? "" : section; for (Tab t : tabs.values()) t.repaint(); }
	public String lit() { return lit; }
	/** The next section (by 1) or the one before (by -1), as the bumpers do it. */
	public void flip(int by) {
		List<String> names = new ArrayList<>(tabs.keySet());
		if (names.isEmpty()) return;
		int i = names.indexOf(lit);
		pick.accept(names.get(i < 0 ? 0 : Math.floorMod(i + by, names.size())));
	}
	/** A red count on a section's name (0 for none). */
	public void badge(String section, int count) { Tab t = tabs.get(section); if (t != null && t.badge != count) { t.badge = count; t.revalidate(); t.repaint(); } }
	/** The few words at the top right. */
	public void corner(String text) { corner.setText(text); }
	/** The bottom strip's two ends: where the program stands (left), and what the buttons do (right). */
	public JPanel left() { return left; }
	public JPanel right() { return right; }

	/** A rounded sheet to put an ordinary page of boxes and buttons on, so it sits on the background like a card. */
	public static JComponent sheet(JComponent content) {
		JPanel s = new JPanel(new BorderLayout()) {
			@Override protected void paintComponent(Graphics g0) {
				Graphics2D g = smooth(g0);
				g.setColor(SteamLook.BACK); g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, 18, 18));
				g.setColor(a(TEXT, 26)); g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 2, getHeight() - 2, 18, 18));
				g.dispose();
			}
		};
		s.setOpaque(false);
		s.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		s.add(content, BorderLayout.CENTER);
		return s;
	}

	/** Makes a scroll pane and what it holds see-through, so the background shows behind a grid of cards. */
	public static void clear(javax.swing.JScrollPane sc) {
		sc.setOpaque(false); sc.getViewport().setOpaque(false); sc.setBorder(null);
		if (sc.getViewport().getView() instanceof JComponent v) { v.setOpaque(false); for (java.awt.Component c : v.getComponents()) if (c instanceof JComponent j) j.setOpaque(false); }
	}
}
