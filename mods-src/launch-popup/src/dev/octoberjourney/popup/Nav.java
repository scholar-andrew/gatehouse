package dev.octoberjourney.popup;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import dev.gatehouse.look.Quiet;

/** Driving the launcher's windows with a controller (Pad reads it):
 *    left stick, d-pad   move the selection to the nearest button, tab, box or list in that direction
 *    A                   presses the selected button (ticks a box, opens a drop-down)
 *    B, Select           select the button that goes up a level (Gatehouse, Gatekeepers; Cancel in a question)
 *    Y                   selects Restart
 *    Start               selects Quit
 *    right trigger       a left mouse click where the pointer is (the right trackpad moves it, in the Steam layout)
 *    right stick         scrolls: the list or panel the selection is in or on, else the biggest one that scrolls;
 *                        up and down, and left and right where something is wider than it shows (R41)
 *  On tabs, left and right change the tab; in a list, up and down move the row. Whatever window of the launcher is in
 *  front is the one being driven, questions included. Buttons say what they are with tag(): "back", "restart", "quit".
 *  The arrow keys do the same moving when a button or a tab row is selected. */
public final class Nav {
	private Nav() {}

	private static boolean on;
	/** What the bumpers (and Q and E) do: the sections of the window in front, one back or one on. */
	static dev.gatehouse.look.GameShell shellOf(Window w) { return w instanceof javax.swing.JFrame jf
			&& jf.getContentPane() instanceof dev.gatehouse.look.GameShell gs ? gs : null; }
	private static void flip(Window w, int by) { if (w instanceof javax.swing.JFrame jf
			&& jf.getContentPane() instanceof dev.gatehouse.look.GameShell gs) gs.flip(by); }

	/** Marks a button as the one B / Select, Y or Start goes to. */
	public static void tag(JComponent c, String what) { c.putClientProperty("nav", what); }

	/** Turns it on (once, for every window of the launcher). */
	public static synchronized void start() {
		rightClickIsLeftClick();
		if (on) return;
		on = true;
		Pad.start(word -> { dev.gatehouse.look.GameShell.pad(true); SwingUtilities.invokeLater(() -> act(word)); });
		try { if (java.nio.file.Files.exists(java.nio.file.Path.of("/dev/input/js0"))) dev.gatehouse.look.GameShell.pad(true); } catch (RuntimeException ignored) { Quiet.seen(ignored); }   // (a Steam Deck: the controller's buttons from the start)
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			if (e.getID() != java.awt.event.KeyEvent.KEY_PRESSED || e.getModifiersEx() != 0) return false;
			dev.gatehouse.look.GameShell.pad(false);
			{   // (Q and E flip the sections, Esc goes back: unless something is being typed, or a question is open)
				Component typing = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
				Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
				int key = e.getKeyCode();
				if (active instanceof javax.swing.JFrame && !(typing instanceof JTextComponent || typing instanceof JTable || typing instanceof JComboBox)) {
					if (key == java.awt.event.KeyEvent.VK_Q) { act("lb"); return true; }
					if (key == java.awt.event.KeyEvent.VK_E) { act("rb"); return true; }
					if (key == java.awt.event.KeyEvent.VK_ESCAPE) { act("b"); return true; }
					if (key == java.awt.event.KeyEvent.VK_M && shellOf(active) != null && shellOf(active).hasMenu()) { act("back"); return true; }
				}
			}
			Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
			int k = e.getKeyCode();
			String dir = k == java.awt.event.KeyEvent.VK_UP ? "up" : k == java.awt.event.KeyEvent.VK_DOWN ? "down"
					: k == java.awt.event.KeyEvent.VK_LEFT ? "left" : k == java.awt.event.KeyEvent.VK_RIGHT ? "right" : null;
			if (dir == null || !(fo instanceof AbstractButton || (fo instanceof JTabbedPane && (dir.equals("up") || dir.equals("down"))))) return false;
			if (fo instanceof javax.swing.JMenuItem) return false;
			act(dir);
			return true;
		});
	}

	private static java.awt.Robot robot;

	private static boolean rightIsLeft;
	/** On a Steam Deck the pack's controller layout makes a press of the right trackpad the RIGHT mouse button, which
	 *  is what the game wants (use, place). Gatehouse's own windows have no use for a right click, so there every
	 *  right click is taken as a left click: pressing the trackpad presses what the pointer is on. Done where the
	 *  window's events are handed out, so nothing about the layout or Steam changes.
	 *  (OJ_RIGHT_AS_LEFT=1: on any device, for testing.) */
	static synchronized void rightClickIsLeftClick() {
		if (rightIsLeft) return;
		boolean deck = false;
		try { String p = java.nio.file.Files.readString(java.nio.file.Path.of("/sys/devices/virtual/dmi/id/product_name")).trim(); deck = p.equals("Jupiter") || p.equals("Galileo"); } catch (java.io.IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		if (!deck && System.getenv("OJ_RIGHT_AS_LEFT") == null) return;
		rightIsLeft = true;
		final int R = java.awt.event.InputEvent.BUTTON3_DOWN_MASK, L = java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
		java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().push(new java.awt.EventQueue() {
			@Override protected void dispatchEvent(java.awt.AWTEvent ev) {
				if (ev instanceof java.awt.event.MouseEvent m && !(m instanceof java.awt.event.MouseWheelEvent)
						&& (m.getButton() == java.awt.event.MouseEvent.BUTTON3 || (m.getModifiersEx() & R) != 0)) {
					int mods = m.getModifiersEx() & ~R;
					if ((m.getModifiersEx() & R) != 0) mods |= L;
					ev = new java.awt.event.MouseEvent(m.getComponent(), m.getID(), m.getWhen(), mods, m.getX(), m.getY(), m.getXOnScreen(), m.getYOnScreen(),
							m.getClickCount(), false, m.getButton() == java.awt.event.MouseEvent.BUTTON3 ? java.awt.event.MouseEvent.BUTTON1 : m.getButton());
				}
				super.dispatchEvent(ev);
			}
		});
	}

	static void act(String word) {
		Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (w == null || !w.isShowing()) return;
		Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		switch (word) {
			case "up", "down", "left", "right" -> move(w, fo, word);
			case "a" -> {
				if (fo instanceof AbstractButton b && b.isEnabled()) b.doClick();
				else if (fo instanceof JComboBox<?> cb) cb.setPopupVisible(!cb.isPopupVisible());
			}
			case "back" -> {   // (the View button: the window's menu, where it has one; otherwise as B)
				dev.gatehouse.look.GameShell gs = shellOf(w);
				if (gs != null && gs.hasMenu()) { gs.toggleMenu(); return; }
				if (!select(w, "back")) selectByText(w, "Cancel", "No", "Close");
			}
			case "b" -> {
				// (B with the menu open closes it first)
				dev.gatehouse.look.GameShell gm = shellOf(w);
				if (gm != null && gm.menuOpen() && fo != null && SwingUtilities.isDescendingFrom(fo, gm) && fo.getParent() != null
						&& "menu".equals(String.valueOf(((JComponent) fo.getParent()).getClientProperty("navMenu")))) { gm.toggleMenu(); return; }
				// (a back button marked "navNow" is pressed at once; any other is only selected, for A to confirm)
				for (Component c : stops(w)) if (c instanceof AbstractButton ab && "back".equals(ab.getClientProperty("nav"))
						&& Boolean.TRUE.equals(ab.getClientProperty("navNow"))) { ab.doClick(); return; }
				if (!select(w, "back")) selectByText(w, "Cancel", "No", "Close");
			}
			case "lb" -> flip(w, -1);
			case "rb" -> flip(w, 1);
			case "x" -> select(w, "extra");
			case "y" -> selectOrMenu(w, "restart");
			case "start" -> selectOrMenu(w, "quit");
			case "rt-down", "rt-up" -> {
				try {
					if (robot == null) robot = new java.awt.Robot();
					if (word.equals("rt-down")) robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
					else robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
				} catch (java.awt.AWTException | RuntimeException ignored) { Quiet.seen(ignored); }
			}
			default -> { if (word.startsWith("scroll:")) try { scroll(w, fo,
					Integer.parseInt(word.substring(7)), false); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
				else if (word.startsWith("hscroll:")) try { scroll(w, fo,
					Integer.parseInt(word.substring(8)), true); } catch (NumberFormatException ignored) { Quiet.seen(ignored); } }
		}
	}

	/** R23: the right stick scrolls one thing: the scrolling list or panel the selection is in (or is), else the
	 *  biggest one showing that has more than fits. Up and down, or (R41, sideways) left and right: then the one that
	 *  is wider than it shows. */
	private static void scroll(Window w, Component fo, int px, boolean sideways) {
		javax.swing.JScrollPane sp = null;
		for (Component c = fo; c != null && c != w; c = c.getParent()) if (c instanceof javax.swing.JScrollPane s && scrolls(s, sideways)) { sp = s; break; }
		if (sp == null) {
			long biggest = 0;
			for (javax.swing.JScrollPane s : panes(w)) { long a = (long) s.getWidth() * s.getHeight(); if (scrolls(s, sideways) && a > biggest) { biggest = a; sp = s; } }
		}
		if (sp == null) return;
		javax.swing.JScrollBar bar = sideways ? sp.getHorizontalScrollBar() : sp.getVerticalScrollBar();
		bar.setValue(Math.max(bar.getMinimum(), Math.min(bar.getMaximum() - bar.getVisibleAmount(), bar.getValue() + px)));
	}
	private static boolean scrolls(javax.swing.JScrollPane s) { return scrolls(s, false); }
	private static boolean scrolls(javax.swing.JScrollPane s, boolean sideways) {
		Component v = s.getViewport().getView();
		java.awt.Dimension shown = s.getViewport().getExtentSize();
		return s.isShowing() && v != null && (sideways ? v.getWidth() > shown.width + 2 : v.getHeight() > shown.height + 2);
	}
	private static List<javax.swing.JScrollPane> panes(Container c) {
		List<javax.swing.JScrollPane> out = new ArrayList<>();
		for (Component x : c.getComponents()) {
			if (!x.isVisible()) continue;
			if (x instanceof javax.swing.JScrollPane s) out.add(s);
			if (x instanceof Container k) out.addAll(panes(k));
		}
		return out;
	}
	/** A scrolling list or panel with nothing to press in it (notes, the panel beside the servers, what was said in
	 *  chat) can be landed on, so the right stick can be pointed at it: it shows a blue outline while it is chosen. */
	private static boolean readable(javax.swing.JScrollPane s) {
		if (!scrolls(s) || s.getParent() instanceof JComboBox || s.getViewport().getView() instanceof JTable
				|| (s.getViewport().getView() instanceof JTextComponent t && t.isEditable())) return false;
		List<Component> inside = new ArrayList<>();
		collect(s, inside);
		return inside.isEmpty();
	}
	private static void makeStop(javax.swing.JScrollPane s) {
		if (Boolean.TRUE.equals(s.getClientProperty("navRead"))) return;
		s.putClientProperty("navRead", Boolean.TRUE);
		s.setFocusable(true);
		javax.swing.border.Border was = s.getBorder();
		s.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override public void focusGained(java.awt.event.FocusEvent e) { s.setBorder(javax.swing.BorderFactory.createLineBorder(dev.gatehouse.look.GameShell.ACC, 2)); }
			@Override public void focusLost(java.awt.event.FocusEvent e) { s.setBorder(was); }
		});
	}

	/** Everything in the window the selection can land on. */
	static List<Component> stops(Container root) {
		List<Component> out = new ArrayList<>();
		collect(root, out);
		return out;
	}
	private static void collect(Container c, List<Component> out) {
		for (Component x : c.getComponents()) {
			if (!x.isVisible()) continue;
			boolean stop = (x instanceof AbstractButton || x instanceof JTabbedPane || x instanceof JTextComponent || x instanceof JTable
					|| x instanceof JComboBox) && x.isEnabled() && x.isFocusable() && x.isShowing()
					&& !(x instanceof JTextComponent t && !t.isEditable());
			// (the arrows of a scroll bar or a drop-down aren't stops)
			if (stop && !(x.getParent() instanceof javax.swing.JScrollBar) && !(x.getParent() instanceof JComboBox)) out.add(x);
			// (R23: a list or panel to read, with nothing to press inside it, is a stop too)
			if (x instanceof javax.swing.JScrollPane s && x.isShowing() && readable(s)) { makeStop(s); out.add(s); continue; }
			if (x instanceof Container k && !(x instanceof JComboBox) && !(x instanceof JTable)) collect(k, out);
		}
	}

	/** Where a stop is on the screen: for a row of tabs, the tab that's open. */
	private static Rectangle where(Component c) {
		Point p = c.getLocationOnScreen();
		if (c instanceof JTabbedPane t
				&& t.getSelectedIndex() >= 0) { Rectangle r = t.getBoundsAt(t.getSelectedIndex()); if (r != null) return new Rectangle(p.x
				+ r.x, p.y + r.y, r.width, r.height); }
		return new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
	}

	/** Which of these rectangles is the next one from "from" in a direction: the nearest whose middle lies that way,
	 *  counting sideways drift three times as far. -1 when there's none. */
	static int pick(List<Rectangle> all, Rectangle from, String dir) {
		int best = -1; double bestScore = Double.MAX_VALUE;
		double fx = from.getCenterX(), fy = from.getCenterY();
		for (int i = 0; i < all.size(); i++) {
			Rectangle r = all.get(i);
			double dx = r.getCenterX() - fx, dy = r.getCenterY() - fy, along, across;
			switch (dir) {
				case "right" -> { along = dx; across = Math.abs(dy); }
				case "left" -> { along = -dx; across = Math.abs(dy); }
				case "down" -> { along = dy; across = Math.abs(dx); }
				default -> { along = -dy; across = Math.abs(dx); }
			}
			if (along < 6) continue;
			double drift = across;   // (how far off to the side it really is: settles a tie between two that are both straight ahead)
			// (something in the same row or column counts as straight ahead)
			boolean vertical = dir.equals("up") || dir.equals("down");
			if (vertical ? r.x < from.x + from.width && from.x < r.x + r.width : r.y < from.y + from.height && from.y < r.y + r.height) across = Math.min(across, 4);
			double score = along + 3 * across + 0.02 * drift;
			if (score < bestScore) { bestScore = score; best = i; }
		}
		return best;
	}

	private static void move(Window w, Component fo, String dir) {
		if (fo instanceof JTabbedPane t && (dir.equals("left") || dir.equals("right"))) {
			int i = t.getSelectedIndex() + (dir.equals("right") ? 1 : -1);
			while (i >= 0 && i < t.getTabCount() && !t.isEnabledAt(i)) i += dir.equals("right") ? 1 : -1;
			if (i >= 0 && i < t.getTabCount()) { t.setSelectedIndex(i); return; }
		}
		if (fo instanceof JTable tb && (dir.equals("up") || dir.equals("down")) && tb.getRowCount() > 0) {
			int r = tb.getSelectedRow() + (dir.equals("down") ? 1 : -1);
			if (tb.getSelectedRow() < 0) r = 0;
			if (r >= 0 && r < tb.getRowCount()) { tb.setRowSelectionInterval(r, r); tb.scrollRectToVisible(tb.getCellRect(r, 0, true)); return; }
		}
		List<Component> stops = stops(w);
		if (stops.isEmpty()) return;
		if (fo == null || !stops.contains(fo)) { land(stops.get(0)); return; }
		List<Rectangle> rects = new ArrayList<>();
		for (Component c : stops) rects.add(c == fo ? new Rectangle(-99999, -99999, 0, 0) : where(c));
		int i = pick(rects, where(fo), dir);
		if (i >= 0) land(stops.get(i));
	}

	static void land(Component c) {
		c.requestFocusInWindow();
		if (c instanceof JComponent j) j.scrollRectToVisible(new Rectangle(0, 0, j.getWidth(), j.getHeight()));
		c.repaint();
	}

	/** Puts the selection on the window's main button (the first Play-style button) when nothing is selected, so the
	 *  first press of A or a direction has somewhere to start from. */
	public static void home(Window w) {
		if (w == null || !w.isShowing()) return;
		Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		List<Component> stops = stops(w);
		if (fo != null && stops.contains(fo)) return;
		for (Component c : stops) if (c instanceof LaunchPopup.PlayButton && !"quit".equals(((JComponent) c).getClientProperty("nav"))) { land(c); return; }
		if (!stops.isEmpty()) land(stops.get(0));
	}

	private static boolean select(Window w, String tag) {
		for (Component c : stops(w)) if (c instanceof JComponent j && tag.equals(j.getClientProperty("nav"))) { land(c); return true; }
		return false;
	}
	/** Selects the button with that tag; if none is showing and the window has a menu (where Restart and Quit live now,
	 *  R38), opens the menu and selects it there. */
	private static void selectOrMenu(Window w, String tag) {
		if (select(w, tag)) return;
		dev.gatehouse.look.GameShell gm = shellOf(w);
		if (gm == null || !gm.hasMenu()) return;
		if (!gm.menuOpen()) gm.toggleMenu();
		SwingUtilities.invokeLater(() -> select(w, tag));
	}
	private static void selectByText(Window w, String... texts) {
		for (String t : texts) for (Component c : stops(w)) if (c instanceof AbstractButton b && t.equals(b.getText())) { land(c); return; }
	}
}
