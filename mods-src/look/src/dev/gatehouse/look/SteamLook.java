package dev.gatehouse.look;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/** The look shared by the Gatehouse launcher and the Keeper's control panel: dark blue-grey panels, light text, a
 *  light-blue accent, flat buttons and tabs, and roomy controls that work on a Steam Deck's screen, in the manner of
 *  Steam's own windows. One call, {@link #apply()}, before any window is built. Plain Swing: Java's own Metal look
 *  with these colours, and flat drawing for buttons, tabs and scroll bars. */
public final class SteamLook {
	private SteamLook() {}

	/** The window; panels set into it (lists, text boxes); raised things (buttons, the selected row). */
	public static final Color BACK = new Color(0x1B2838), DEEP = new Color(0x141D29), RAISED = new Color(0x2A475E), HOVER = new Color(0x3D6C8E);
	/** Text; quieter text; the accent (focus, the selected tab); thin lines. */
	public static final Color TEXT = new Color(0xDCE6EF), DIM = new Color(0xA9B5C1), ACCENT = new Color(0x66C0F4), LINE = new Color(0x2F4860);
	/** Text that says something went well, needs attention, or went wrong, readable on the dark background. */
	public static final Color GOOD = new Color(0x8BD46E), WARN = new Color(0xE9B949), BAD = new Color(0xFF7B72);

	/** How much bigger than the old sizes all text is (R39: one size up, so the smallest text on a Steam Deck's
	 *  1280 x 800 screen is at least 13 points). Every font size in Gatehouse and Gatekeeper goes through pt(). */
	public static final float UP = 2f;
	public static float pt(float size) { return size + UP; }
	public static int pt(int size) { return size + (int) UP; }

	/** A permission cell in an Access Manager table (Allowed, Denied, Not allowed, Blacklisted, Suspended), in the dark
	 *  theme's colours: green for allowed, red for denied or shut out, quiet grey for not set (R39: these were pale
	 *  mint, pink and white, the brightest things on screen). Says whether the text was one of those. */
	public static boolean permCell(java.awt.Component c, String s, boolean selected) {
		boolean good = s.startsWith("Allowed"), bad = s.startsWith("Denied") || s.startsWith("Blacklisted") || s.startsWith("Suspended"),
				none = s.startsWith("Not allowed");
		if (!good && !bad && !none) return false;
		Color tint = good ? new Color(0x1F3B2C) : bad ? new Color(0x40242A) : DEEP;
		c.setBackground(selected ? tint.brighter() : tint);
		c.setForeground(good ? GOOD : bad ? BAD : DIM);
		return true;
	}

	/** R39: four kinds of button, the same everywhere. Secondary is what a plain button is; the others are marked:
	 *  main (filled blue: the one thing a card or page is for, one per place), quiet (text only, filled under the
	 *  pointer: small extras) and danger (red outline and text: removes or ends something, and always asks first). */
	public static final Color MAIN = new Color(0x2F7DE1), BUTTON = new Color(0x3A5A78), BUTTON_HOVER = new Color(0x4A7396);
	public static <T extends AbstractButton> T main(T b) { b.putClientProperty("level", "main"); b.repaint(); return b; }
	public static <T extends AbstractButton> T quiet(T b) { b.putClientProperty("level", "quiet"); b.repaint(); return b; }
	public static <T extends AbstractButton> T danger(T b) { b.putClientProperty("level", "danger"); b.repaint(); return b; }

	/** A set of buttons under a small heading (R39: in place of the old etched boxes with a title in the line). */
	public static javax.swing.JPanel group(String title, javax.swing.JButton... bs) {
		javax.swing.JPanel g = new javax.swing.JPanel(new java.awt.BorderLayout(0, 4));
		javax.swing.JLabel h = new javax.swing.JLabel(title.toUpperCase(java.util.Locale.ROOT));
		h.setFont(h.getFont().deriveFont(java.awt.Font.BOLD, pt(11f))); h.setForeground(DIM);
		h.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
		javax.swing.JPanel row = new javax.swing.JPanel();
		WrapLayout.on(row, 6, 0);
		for (javax.swing.JButton b : bs) row.add(b);
		g.add(h, java.awt.BorderLayout.NORTH); g.add(row, java.awt.BorderLayout.CENTER);
		g.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 18));
		return g;
	}

	/** Sizes a table's columns to what is in them (and their headings), again whenever the rows change, so short
	 *  columns stay short and names and dates aren't cut off while there is room (R39). */
	public static void fitColumns(javax.swing.JTable t) {
		Runnable fit = () -> {
			javax.swing.table.TableColumnModel cm = t.getColumnModel();
			for (int c = 0; c < cm.getColumnCount(); c++) {
				javax.swing.table.TableColumn col = cm.getColumn(c);
				javax.swing.table.TableCellRenderer hr = col.getHeaderRenderer() != null ? col.getHeaderRenderer()
						: t.getTableHeader() == null ? null : t.getTableHeader().getDefaultRenderer();
				int w = hr == null ? 40 : hr.getTableCellRendererComponent(t, col.getHeaderValue(), false, false, -1, c).getPreferredSize().width;
				for (int r = 0; r < Math.min(t.getRowCount(), 200); r++) w = Math.max(w, t.prepareRenderer(t.getCellRenderer(r, c), r, c).getPreferredSize().width);
				col.setPreferredWidth(w + 14);
			}
		};
		t.getModel().addTableModelListener(e -> javax.swing.SwingUtilities.invokeLater(fit));
		fit.run();
	}

	private static boolean applied;

	public static synchronized void apply() {
		if (applied) return;
		applied = true;
		try {
			MetalLookAndFeel.setCurrentTheme(new Theme());
			UIManager.setLookAndFeel(new MetalLookAndFeel());
		} catch (Exception e) { return; }   // (the system's own look stays)
		UIManager.put("swing.boldMetal", Boolean.FALSE);
		Object[] kv = {
			"ButtonUI", FlatButton.class.getName(), "TabbedPaneUI", FlatTabs.class.getName(), "ScrollBarUI", FlatScroll.class.getName(),
			"Panel.background", BACK, "OptionPane.background", BACK, "OptionPane.messageForeground", TEXT, "Viewport.background", BACK,
			"Label.foreground", TEXT, "Label.disabledForeground", DIM,
			"Button.background", RAISED, "Button.foreground", TEXT, "Button.margin", new Insets(6, 14, 6, 14),
			"CheckBox.icon", toggle(), "RadioButton.icon", ring(), "CheckBox.background", BACK, "CheckBox.foreground", TEXT,
					"RadioButton.background", BACK, "RadioButton.foreground", TEXT,
			"TextField.background", DEEP, "TextField.foreground", TEXT, "TextField.caretForeground", TEXT, "TextField.selectionBackground",
					HOVER, "TextField.selectionForeground", Color.WHITE,
			"PasswordField.background", DEEP, "PasswordField.foreground", TEXT, "PasswordField.caretForeground", TEXT,
			"TextArea.background", DEEP, "TextArea.foreground", TEXT, "TextArea.selectionBackground", HOVER, "TextArea.selectionForeground", Color.WHITE,
			"TextField.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(4, 6, 4, 6)),
			"PasswordField.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(4, 6, 4, 6)),
			"ComboBox.background", RAISED, "ComboBox.foreground", TEXT, "ComboBox.selectionBackground", HOVER, "ComboBox.selectionForeground", Color.WHITE,
			"List.background", DEEP, "List.foreground", TEXT, "List.selectionBackground", HOVER, "List.selectionForeground", Color.WHITE,
			"Table.background", DEEP, "Table.foreground", TEXT, "Table.gridColor", LINE, "Table.selectionBackground", HOVER, "Table.selectionForeground", Color.WHITE,
			"TableHeader.background", RAISED, "TableHeader.foreground", TEXT, "TableHeader.cellBorder",
					BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, DEEP), BorderFactory.createEmptyBorder(5, 6, 5, 6)),
			"ScrollPane.background", BACK, "ScrollPane.border", BorderFactory.createLineBorder(LINE), "ScrollBar.width", 14,
			"TabbedPane.background", BACK, "TabbedPane.foreground", DIM, "TabbedPane.selected", BACK, "TabbedPane.contentBorderInsets", new Insets(1, 0, 0, 0),
			"TabbedPane.tabInsets", new Insets(8, 16, 8, 16), "TabbedPane.selectedTabPadInsets", new Insets(0, 0, 0, 0),
					"TabbedPane.tabAreaInsets", new Insets(0, 0, 0, 0), "TabbedPane.tabsOverlapBorder", Boolean.FALSE,
			"PopupMenu.background", RAISED, "PopupMenu.border", BorderFactory.createLineBorder(HOVER), "MenuItem.background", RAISED, "MenuItem.foreground", TEXT,
			"MenuItem.selectionBackground", HOVER, "MenuItem.selectionForeground", Color.WHITE, "MenuItem.border", BorderFactory.createEmptyBorder(6, 10, 6, 10),
			"ToolTip.background", DEEP, "ToolTip.foreground", TEXT, "ToolTip.border",
					BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(HOVER), BorderFactory.createEmptyBorder(4, 6, 4, 6)),
			"FileChooser.listViewBackground", DEEP, "Separator.foreground", LINE, "TitledBorder.titleColor", TEXT,
		};
		for (int i = 0; i < kv.length; i += 2) UIManager.put(kv[i], kv[i + 1]);
	}

	/** Metal's colours, darkened: what it calls white is where text is typed, and black is the text. */
	private static final class Theme extends DefaultMetalTheme {
		private final FontUIResource plain, small;
		Theme() {
			String face = System.getProperty("os.name", "").startsWith("Windows") ? "Segoe UI" : Font.SANS_SERIF;
			plain = new FontUIResource(new Font(face, Font.PLAIN, dev.gatehouse.look.SteamLook.pt(14)));
			small = new FontUIResource(new Font(face, Font.PLAIN, dev.gatehouse.look.SteamLook.pt(12)));
		}
		@Override public String getName() { return "Gatehouse"; }
		@Override protected ColorUIResource getPrimary1() { return new ColorUIResource(ACCENT); }
		@Override protected ColorUIResource getPrimary2() { return new ColorUIResource(HOVER); }
		@Override protected ColorUIResource getPrimary3() { return new ColorUIResource(RAISED); }
		@Override protected ColorUIResource getSecondary1() { return new ColorUIResource(DEEP); }
		@Override protected ColorUIResource getSecondary2() { return new ColorUIResource(LINE); }
		@Override protected ColorUIResource getSecondary3() { return new ColorUIResource(BACK); }
		@Override protected ColorUIResource getWhite() { return new ColorUIResource(DEEP); }
		@Override protected ColorUIResource getBlack() { return new ColorUIResource(TEXT); }
		@Override public ColorUIResource getInactiveControlTextColor() { return new ColorUIResource(0x6B7785); }
		@Override public ColorUIResource getMenuDisabledForeground() { return new ColorUIResource(0x6B7785); }
		@Override public FontUIResource getControlTextFont() { return plain; }
		@Override public FontUIResource getSystemTextFont() { return plain; }
		@Override public FontUIResource getUserTextFont() { return plain; }
		@Override public FontUIResource getMenuTextFont() { return plain; }
		@Override public FontUIResource getWindowTitleFont() { return plain; }
		@Override public FontUIResource getSubTextFont() { return small; }
	}

	/** A flat button with rounded corners: lighter under the pointer, darker when pressed, dull when it can't be used.
	 *  (Buttons that paint themselves, like the launcher's Play, aren't touched.) */
	public static final class FlatButton extends BasicButtonUI {
		public static ComponentUI createUI(JComponent c) { return new FlatButton(); }
		@Override public void installUI(JComponent c) {
			super.installUI(c);
			AbstractButton b = (AbstractButton) c;
			b.setOpaque(false); b.setRolloverEnabled(true); b.setFocusPainted(false);
			b.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
		}
		@Override public void paint(Graphics g, JComponent c) {
			AbstractButton b = (AbstractButton) c;
			ButtonModel m = b.getModel();
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			String level = String.valueOf(c.getClientProperty("level"));
			boolean own = b.getBackground() != null && !(b.getBackground() instanceof javax.swing.plaf.UIResource) && !b.getBackground().equals(RAISED);
			Color base = own ? b.getBackground() : level.equals("main") ? MAIN : BUTTON;
			Color over = own ? HOVER : level.equals("main") ? MAIN.brighter() : BUTTON_HOVER;
			boolean down = m.isArmed() && m.isPressed();
			if (level.equals("quiet") || level.equals("danger")) {
				// (text-only kinds: a fill only under the pointer; danger has its red outline always)
				if (b.isEnabled() && (m.isRollover() || down)) {
					g2.setColor(level.equals("danger") ? new Color(0x40242A) : BUTTON); g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 8, 8); }
				if (level.equals("danger")) { g2.setColor(b.isEnabled() ? BAD : new Color(0x6B7785));
					g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8); }
			} else {
				g2.setColor(!b.isEnabled() ? new Color(0x222F3F) : down ? base.darker() : m.isRollover() ? over : base);
				if (b.isContentAreaFilled()) g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 8, 8);
			}
			if (b.isFocusOwner() && b.isEnabled()) { g2.setColor(ACCENT); g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8); }
			g2.dispose();
			super.paint(g, c);
		}
		@Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			FontMetrics fm = g2.getFontMetrics(b.getFont());
			g2.setFont(b.getFont());
			String level = String.valueOf(b.getClientProperty("level"));
			Color ink = level.equals("main") ? Color.WHITE : level.equals("quiet") ? ACCENT : level.equals("danger") ? BAD
					: b.getForeground() instanceof javax.swing.plaf.UIResource ? TEXT : b.getForeground();
			g2.setColor(b.isEnabled() ? ink : new Color(0x6B7785));
			g2.drawString(text, r.x, r.y + fm.getAscent());
			g2.dispose();
		}
	}

	/** Flat tabs: names in a row, the open one in bright text with an accent line under it, and one thin line across
	 *  under the row. */
	public static final class FlatTabs extends BasicTabbedPaneUI {
		public static ComponentUI createUI(JComponent c) { return new FlatTabs(); }
		@Override protected void installDefaults() { super.installDefaults(); tabAreaInsets = new Insets(0, 0, 0,
				0); contentBorderInsets = new Insets(1, 0, 0, 0); selectedTabPadInsets = new Insets(0, 0, 0, 0); }
		@Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
			g.setColor(selected ? DEEP : index == getRolloverTab() ? new Color(0x22344A) : BACK);
			g.fillRect(x, y, w, h);
			if (selected) { g.setColor(ACCENT); g.fillRect(x, y + h - 3, w, 3); }
		}
		@Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {}
		@Override protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects, int index, Rectangle icon, Rectangle text, boolean selected) {
			// (the row of tabs is what a controller or the keyboard has selected: an outline round the open tab)
			if (!selected || !tabPane.isFocusOwner() || index < 0 || index >= rects.length) return;
			g.setColor(ACCENT);
			g.drawRect(rects[index].x + 1, rects[index].y + 1, rects[index].width - 3, rects[index].height - 3);
		}
		@Override protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
			Insets in = tabPane.getInsets();
			int y = in.top + calculateTabAreaHeight(placement, runCount, maxTabHeight);
			g.setColor(LINE);
			g.fillRect(in.left, y, tabPane.getWidth() - in.left - in.right, 1);
		}
		@Override protected void paintText(Graphics g, int placement, Font font, FontMetrics metrics, int index, String title, Rectangle textRect, boolean selected) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setFont(font);
			g2.setColor(!tabPane.isEnabledAt(index) ? new Color(0x6B7785) : selected ? Color.WHITE : index == getRolloverTab() ? TEXT : DIM);
			g2.drawString(title, textRect.x, textRect.y + metrics.getAscent());
			g2.dispose();
		}
		@Override protected int getTabLabelShiftX(int placement, int index, boolean selected) { return 0; }
		@Override protected int getTabLabelShiftY(int placement, int index, boolean selected) { return 0; }
	}

	/** A thin scroll bar with a rounded thumb and no arrow buttons. */
	public static final class FlatScroll extends BasicScrollBarUI {
		public static ComponentUI createUI(JComponent c) { return new FlatScroll(); }
		private static JButton none() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,
				0)); b.setMinimumSize(new Dimension(0, 0)); b.setMaximumSize(new Dimension(0, 0)); return b; }
		@Override protected JButton createDecreaseButton(int orientation) { return none(); }
		@Override protected JButton createIncreaseButton(int orientation) { return none(); }
		@Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { g.setColor(DEEP); g.fillRect(r.x, r.y, r.width, r.height); }
		@Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
			if (r.isEmpty() || !scrollbar.isEnabled()) return;
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(isDragging || isThumbRollover() ? HOVER : RAISED);
			g2.fillRoundRect(r.x + 3, r.y + 3, r.width - 6, r.height - 6, 8, 8);
			g2.dispose();
		}
	}

	/** For a component that should sit in the window as a card: the deep background and a thin line round it. */
	/** A settings wheel, drawn (the fonts at hand don't all have one): eight teeth around a ring. */
	/** A tick box drawn as a switch: green with its knob to the right when on, grey with it to the left when off. */
	public static javax.swing.Icon toggle() {
		return new javax.swing.Icon() {
			@Override public int getIconWidth() { return 38; }
			@Override public int getIconHeight() { return 20; }
			@Override public void paintIcon(Component c, java.awt.Graphics g0, int x, int y) {
				java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				boolean on = c instanceof javax.swing.AbstractButton b && b.isSelected(), can = c.isEnabled();
				g.setColor(!can ? new Color(0x2C3948) : on ? new Color(0x3FB950) : new Color(0x445262));
				g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, 38, 20, 20, 20));
				g.setColor(can ? Color.WHITE : new Color(0x6B7785));
				g.fill(new java.awt.geom.Ellipse2D.Double(on ? x + 20.5 : x + 2.5, y + 2.5, 15, 15));
				if (c.isFocusOwner()) { g.setColor(ACCENT); g.setStroke(new java.awt.BasicStroke(1.6f)); g.draw(new java.awt.geom.RoundRectangle2D.Double(x - 2, y - 2, 42, 24, 24, 24)); }
				g.dispose();
			}
		};
	}

	/** A radio button drawn so the chosen one can't be missed: a bright ring with a filled middle; the others a dull ring. */
	public static javax.swing.Icon ring() {
		return new javax.swing.Icon() {
			@Override public int getIconWidth() { return 20; }
			@Override public int getIconHeight() { return 20; }
			@Override public void paintIcon(Component c, java.awt.Graphics g0, int x, int y) {
				java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
				boolean on = c instanceof javax.swing.AbstractButton b && b.isSelected(), can = c.isEnabled();
				g.setColor(!can ? new Color(0x445262) : on ? ACCENT : new Color(0x7A8794));
				g.setStroke(new java.awt.BasicStroke(on ? 2.2f : 1.6f));
				g.draw(new java.awt.geom.Ellipse2D.Double(x + 2, y + 2, 16, 16));
				if (on) g.fill(new java.awt.geom.Ellipse2D.Double(x + 6, y + 6, 8, 8));
				if (c.isFocusOwner()) { g.setColor(GameShell.a(ACCENT,
						120)); g.setStroke(new java.awt.BasicStroke(1.4f)); g.draw(new java.awt.geom.Ellipse2D.Double(x - 1, y - 1, 22, 22)); }
				g.dispose();
			}
		};
	}

	public static javax.swing.Icon gear(int size) {
		return new javax.swing.Icon() {
			@Override public int getIconWidth() { return size; }
			@Override public int getIconHeight() { return size; }
			@Override public void paintIcon(Component c, java.awt.Graphics g0, int x, int y) {
				java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g.translate(x + size / 2.0, y + size / 2.0);
				g.setColor(c != null && c.isEnabled() ? TEXT : DIM);
				double outer = size / 2.0, body = outer * 0.72, hole = outer * 0.32, tooth = outer * 0.34;
				java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(-body, -body, body * 2, body * 2));
				for (int i = 0; i < 8; i++) {
					java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform.getRotateInstance(Math.PI / 4 * i);
					a.add(new java.awt.geom.Area(t.createTransformedShape(new java.awt.geom.Rectangle2D.Double(-tooth / 2, -outer, tooth, outer))));
				}
				a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(-hole, -hole, hole * 2, hole * 2)));
				g.fill(a);
				g.dispose();
			}
		};
	}

	public static void card(JComponent c) {
		c.setBackground(DEEP);
		c.setOpaque(true);
		c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), c.getBorder()));
	}

	/** True once the look is on (so a component can pick colours for it). */
	public static boolean on() { return applied && UIManager.getLookAndFeel() instanceof MetalLookAndFeel; }

	static Component unused;   // (keeps the import list honest for tools that check it)
}
