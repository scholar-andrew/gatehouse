package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * One thing to pick, as a card: a square of art with its initials, its name, a line saying how it is (with a coloured
 * dot), and its buttons along the bottom. The card whose button is selected (or that the pointer is on) glows.
 *
 * The name and the line are ordinary labels (name, state), so whatever fills them in sets their text as it would on
 * any label. The art's colours come from the name, so a thing keeps its colours; art() sets them by hand.
 */
public final class GameCard extends JPanel {
	static final Color CARD = new Color(0x22344A), CARD_HI = new Color(0x2B4563);
	public static final int MIN_WIDTH = 410, HEIGHT = 178, GAP = 18;
	public final JLabel name = new JLabel(), state;
	private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
	private Color c1, c2;
	private boolean hot, over, dim;
	private final PropertyChangeListener focus = e -> { boolean h = e.getNewValue() instanceof Component c && SwingUtilities.isDescendingFrom(c, this); if (h != hot) { hot = h; repaint(); java.util.function.Consumer<GameCard> to = onHot; if (h && to != null) to.accept(this); } };

	/** Beside a grid of cards: more about the one in focus. Its name, then each heading with what goes under it. */
	public static final class Side extends JPanel {
		private final JPanel rows = new JPanel();
		private GameCard showing;
		public Side() {
			super(new java.awt.BorderLayout());
			setOpaque(false);
			setBorder(javax.swing.BorderFactory.createEmptyBorder(20, 22, 18, 22));
			rows.setOpaque(false);
			rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
			// (in a window too short for all of it, it scrolls: nothing is cut off at the bottom)
			JPanel holder = new JPanel(new java.awt.BorderLayout());
			holder.setOpaque(false);
			holder.add(rows, java.awt.BorderLayout.NORTH);
			javax.swing.JScrollPane sc = new javax.swing.JScrollPane(holder);
			sc.setBorder(null);
			sc.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			sc.getVerticalScrollBar().setUnitIncrement(16);
			GameShell.clear(sc);
			add(sc, java.awt.BorderLayout.CENTER);
			setPreferredSize(new Dimension(340, 200));
			clear();
		}
		private JLabel line(String text, float size, boolean bold, Color c, int above) {
			JLabel l = new JLabel("<html><div style='width:" + 262 + "px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
			l.setFont(GameShell.font(size, bold)); l.setForeground(c);
			l.setBorder(javax.swing.BorderFactory.createEmptyBorder(above, 0, 0, 0));
			l.setAlignmentX(Component.LEFT_ALIGNMENT);
			return l;
		}
		public void clear() { showing = null; rows.removeAll(); rows.add(line("Move to a server to see more about it here.", 13f, false, GameShell.DIM, 0)); rows.revalidate(); repaint(); }
		public void show(GameCard card) {
			showing = card;
			rows.removeAll();
			rows.add(line(card.name.getText() == null ? "" : card.name.getText(), 20f, true, GameShell.TEXT, 0));
			for (String[] f : card.facts()) {
				if (f.length < 2 || f[1] == null || f[1].isEmpty()) continue;
				rows.add(line(f[0].toUpperCase(java.util.Locale.ROOT), 11f, true, GameShell.ACC, 16));
				rows.add(line(f[1], 13.5f, false, GameShell.TEXT, 4));
			}
			rows.revalidate(); repaint();
		}
		public GameCard showing() { return showing; }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = GameShell.smooth(g0);
			g.setColor(new Color(0x1A, 0x26, 0x34, 235)); g.fill(new RoundRectangle2D.Double(0, 4, getWidth() - 1, getHeight() - 9, 16, 16));
			g.setColor(GameShell.a(GameShell.TEXT, 22)); g.draw(new RoundRectangle2D.Double(0.5, 4.5, getWidth() - 2, getHeight() - 10, 16, 16));
			g.dispose();
		}
	}

	/** A label whose words wrap inside whatever width the card gives it. */
	private static final class Wrapped extends JLabel {
		@Override public void setText(String t) { String s = t == null ? "" : t; super.setText(s.startsWith("<html>") ? s : "<html>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</html>"); }
	}

	public GameCard(String title, Component[] right) {
		super(null);
		setOpaque(false);
		state = new Wrapped();
		name.setFont(GameShell.font(19f, true)); name.setForeground(GameShell.TEXT);
		state.setFont(GameShell.font(13.5f, false)); state.setForeground(GameShell.TEXT);
		state.setVerticalAlignment(JLabel.TOP); state.setVerticalTextPosition(JLabel.TOP); state.setIconTextGap(8);
		name.addPropertyChangeListener("text", e -> repaint());   // (the art's initials and colours follow the name)
		name.setText(title);
		buttons.setOpaque(false);
		for (Component c : right) buttons.add(c);
		add(name); add(state); add(buttons);
		addMouseListener(new java.awt.event.MouseAdapter() {
			@Override public void mouseEntered(java.awt.event.MouseEvent e) { over = true; repaint(); java.util.function.Consumer<GameCard> to = onHot; if (to != null) to.accept(GameCard.this); }
			@Override public void mouseExited(java.awt.event.MouseEvent e) { over = getMousePosition(true) != null; repaint(); }
		});
	}

	/** The art's two colours, instead of the ones the name gives. */
	public void art(Color from, Color to) { c1 = from; c2 = to; repaint(); }
	private java.awt.Image picture;
	private java.util.List<String[]> facts = java.util.List.of();
	/** Told each card that becomes the one in focus (or under the pointer): for a panel that shows more about it. */
	public static volatile java.util.function.Consumer<GameCard> onHot;
	/** A picture to show in the art, over anything else (null for none). */
	public void picture(java.awt.Image img) { if (picture != img) { picture = img; repaint(); } }
	/** More about the thing this card is for, as headings and what goes under them. */
	public void facts(java.util.List<String[]> rows) { facts = rows == null ? java.util.List.of() : rows; java.util.function.Consumer<GameCard> h = onHot; if (h != null && (hot || over)) h.accept(this); }
	public java.util.List<String[]> facts() { return facts; }
	private String device = "";
	/** A kind of machine (see DeviceIcon) to draw in the art in place of the initials; "" or an unknown kind: the initials. */
	public void device(String kind) { String k = kind == null ? "" : kind; if (!k.equals(device)) { device = k; repaint(); } }
	/** Lit as if its button were selected (for a picture of the window). */
	public void glow(boolean on) { hot = on; repaint(); }
	/** Greyed: something that is listed but can't be used here. */
	public void dim(boolean on) { dim = on; name.setForeground(on ? GameShell.DIM : GameShell.TEXT); repaint(); }

	@Override public void addNotify() { super.addNotify(); KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focus); }
	@Override public void removeNotify() { KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focus); super.removeNotify(); }

	@Override public Dimension getPreferredSize() { return new Dimension(MIN_WIDTH, HEIGHT); }
	@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, HEIGHT); }
	@Override public void doLayout() {
		int w = getWidth(), h = getHeight(), art = 92, x = 14 + art + 16;
		name.setBounds(x, 14, w - x - 14, 26);
		state.setBounds(x, 44, w - x - 14, h - 44 - 14 - 44);
		buttons.setBounds(10, h - 14 - 38, w - 20, 38);
	}

	private static String initials(String s) {
		StringBuilder b = new StringBuilder();
		for (String word : s.replaceAll("\\(.*?\\)", " ").trim().split("[^\\p{L}\\p{N}]+")) if (!word.isEmpty() && b.length() < 2) b.append(Character.toUpperCase(word.charAt(0)));
		return b.length() == 0 ? "?" : b.toString();
	}

	@Override protected void paintComponent(Graphics g0) {
		Graphics2D g = GameShell.smooth(g0);
		int w = getWidth(), h = getHeight();
		boolean lit = hot || over;
		if (hot) for (int k = 4; k >= 1; k--) { g.setColor(GameShell.a(GameShell.ACC, 16 + (4 - k) * 12)); g.setStroke(new BasicStroke(k * 2.4f)); g.draw(new RoundRectangle2D.Double(4, 4, w - 9, h - 9, 16, 16)); }
		g.setColor(lit ? CARD_HI : CARD); g.fill(new RoundRectangle2D.Double(4, 4, w - 9, h - 9, 16, 16));
		g.setColor(hot ? GameShell.ACC : GameShell.a(GameShell.TEXT, over ? 70 : 24)); g.setStroke(new BasicStroke(hot ? 2.2f : 1f)); g.draw(new RoundRectangle2D.Double(4, 4, w - 9, h - 9, 16, 16));
		// the art
		String n = name.getText() == null ? "" : name.getText();
		Color a = c1, b = c2;
		if (a == null || b == null) { float hue = (n.hashCode() & 0xFFFF) / 65535f; a = Color.getHSBColor(hue, 0.62f, 0.74f); b = Color.getHSBColor(hue + 0.11f, 0.58f, 0.36f); }
		if (dim) { a = new Color(0x5A5F68); b = new Color(0x33373D); }
		double ax = 14, ay = 14, as = 92;
		Shape old = g.getClip();
		g.clip(new RoundRectangle2D.Double(ax, ay, as, as, 12, 12));
		g.setPaint(new GradientPaint((float) ax, (float) ay, a, (float) (ax + as), (float) (ay + as), b)); g.fill(new Rectangle2D.Double(ax, ay, as, as));
		java.util.Random r = new java.util.Random(n.hashCode()); double cell = as / 5;   // (a blocky pattern, like terrain seen from above)
		for (int cy = 0; cy < 5; cy++) for (int cx = 0; cx < 5; cx++) { int v = r.nextInt(100); if (v < 38) { g.setColor(v < 19 ? new Color(255, 255, 255, 10 + v) : new Color(0, 0, 0, v)); g.fill(new Rectangle2D.Double(ax + cx * cell, ay + cy * cell, cell, cell)); } }
		g.setPaint(new GradientPaint(0, (float) (ay + as * 0.45), new Color(0, 0, 0, 0), 0, (float) (ay + as), new Color(0, 0, 0, 110))); g.fill(new Rectangle2D.Double(ax, ay, as, as));
		g.setClip(old);
		if (picture != null) {
			Shape before = g.getClip();
			g.clip(new RoundRectangle2D.Double(ax, ay, as, as, 12, 12));
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			java.awt.Composite was = g.getComposite();
			if (dim) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
			g.drawImage(picture, (int) ax, (int) ay, (int) as, (int) as, null);
			g.setComposite(was); g.setClip(before); g.dispose();
			return;
		}
		if (DeviceIcon.known(device)) { java.awt.Composite was = g.getComposite(); if (dim) g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.6f)); DeviceIcon.paint(g, device, ax + 9, ay + 9, as - 18); g.setComposite(was); g.dispose(); return; }
		String in = initials(n);
		Font f = GameShell.font(32f, true); g.setFont(f); g.setColor(new Color(255, 255, 255, dim ? 150 : 228));
		g.drawString(in, (float) (ax + as / 2 - g.getFontMetrics().stringWidth(in) / 2.0), (float) (ay + as / 2 + 11));
		g.dispose();
	}

	/** A line in a list drawn as a slim card: rounded, and glowing while one of its buttons is selected. It holds
	 *  whatever it is given, laid out however it is told. */
	public static final class Strip extends JPanel {
		private boolean hot;
		private final PropertyChangeListener focus = e -> { boolean h = e.getNewValue() instanceof Component c && SwingUtilities.isDescendingFrom(c, this); if (h != hot) { hot = h; repaint(); } };
		public Strip(LayoutManager layout) { super(layout); setOpaque(false); }
		@Override public void addNotify() { super.addNotify(); KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner", focus); }
		@Override public void removeNotify() { KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("focusOwner", focus); super.removeNotify(); }
		@Override protected void paintComponent(Graphics g0) {
			Graphics2D g = GameShell.smooth(g0);
			int w = getWidth(), h = getHeight();
			if (hot) for (int k = 3; k >= 1; k--) { g.setColor(GameShell.a(GameShell.ACC, 18 + (3 - k) * 14)); g.setStroke(new BasicStroke(k * 2.2f)); g.draw(new RoundRectangle2D.Double(3, 3, w - 7, h - 7, 14, 14)); }
			g.setColor(hot ? CARD_HI : CARD); g.fill(new RoundRectangle2D.Double(3, 3, w - 7, h - 7, 14, 14));
			g.setColor(hot ? GameShell.ACC : GameShell.a(GameShell.TEXT, 26)); g.setStroke(new BasicStroke(hot ? 2f : 1f)); g.draw(new RoundRectangle2D.Double(3, 3, w - 7, h - 7, 14, 14));
			g.dispose();
		}
	}

	/** Lays cards out in as many equal columns as fit, and as many rows as that takes. */
	private static final class Grid implements LayoutManager {
		@Override public void addLayoutComponent(String n, Component c) {}
		@Override public void removeLayoutComponent(Component c) {}
		private static int cols(int w) { return Math.max(1, (w + GAP) / (MIN_WIDTH + GAP)); }
		private static int shown(Container p) { int n = 0; for (Component c : p.getComponents()) if (c.isVisible()) n++; return n; }
		@Override public Dimension preferredLayoutSize(Container p) {
			int w = p.getWidth() > 0 ? p.getWidth() : p.getParent() != null && p.getParent().getWidth() > 0 ? p.getParent().getWidth() : 2 * MIN_WIDTH + GAP;
			int n = shown(p), rows = (n + cols(w) - 1) / cols(w);
			return new Dimension(MIN_WIDTH, rows == 0 ? 0 : rows * HEIGHT + (rows - 1) * GAP);
		}
		@Override public Dimension minimumLayoutSize(Container p) { return preferredLayoutSize(p); }
		@Override public void layoutContainer(Container p) {
			int w = p.getWidth(), cols = cols(w), cw = (w - (cols - 1) * GAP) / cols, i = 0;
			for (Component c : p.getComponents()) { if (!c.isVisible()) continue; c.setBounds((i % cols) * (cw + GAP), (i / cols) * (HEIGHT + GAP), cw, HEIGHT); i++; }
		}
	}

	/** Gives a panel the grid of cards, and keeps its rows right as the window is resized. */
	public static void grid(JComponent panel) {
		panel.setLayout(new Grid());
		panel.setOpaque(false);
		panel.addComponentListener(new java.awt.event.ComponentAdapter() {
			int was = -1;
			@Override public void componentResized(java.awt.event.ComponentEvent e) { if (panel.getWidth() == was) return; was = panel.getWidth(); panel.revalidate(); if (panel.getParent() != null) panel.getParent().revalidate(); }
		});
	}
}
