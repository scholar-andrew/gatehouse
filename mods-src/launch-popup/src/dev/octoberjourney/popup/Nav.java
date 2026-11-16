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

/** Driving the launcher's windows with a controller (Pad reads it):
 *    left stick, d-pad   move the selection to the nearest button, tab, box or list in that direction
 *    A                   presses the selected button (ticks a box, opens a drop-down)
 *    B, Select           select the button that goes up a level (Gatehouse, Gatekeepers; Cancel in a question)
 *    Y                   selects Restart
 *    Start               selects Quit
 *    right trigger       a left mouse click where the pointer is (the right trackpad moves it, in the Steam layout)
 *  On tabs, left and right change the tab; in a list, up and down move the row. Whatever window of the launcher is in
 *  front is the one being driven, questions included. Buttons say what they are with tag(): "back", "restart", "quit".
 *  The arrow keys do the same moving when a button or a tab row is selected. */
final class Nav {
	private Nav() {}

	private static boolean on;

	/** Marks a button as the one B / Select, Y or Start goes to. */
	static void tag(JComponent c, String what) { c.putClientProperty("nav", what); }

	/** Turns it on (once, for every window of the launcher). */
	static synchronized void start() {
		if (on) return;
		on = true;
		Pad.start(word -> SwingUtilities.invokeLater(() -> act(word)));
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
			if (e.getID() != java.awt.event.KeyEvent.KEY_PRESSED || e.getModifiersEx() != 0) return false;
			Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
			int k = e.getKeyCode();
			String dir = k == java.awt.event.KeyEvent.VK_UP ? "up" : k == java.awt.event.KeyEvent.VK_DOWN ? "down" : k == java.awt.event.KeyEvent.VK_LEFT ? "left" : k == java.awt.event.KeyEvent.VK_RIGHT ? "right" : null;
			if (dir == null || !(fo instanceof AbstractButton || (fo instanceof JTabbedPane && (dir.equals("up") || dir.equals("down"))))) return false;
			if (fo instanceof javax.swing.JMenuItem) return false;
			act(dir);
			return true;
		});
	}

	private static java.awt.Robot robot;

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
			case "b", "back" -> { if (!select(w, "back")) selectByText(w, "Cancel", "No", "Close"); }
			case "y" -> select(w, "restart");
			case "start" -> select(w, "quit");
			case "rt-down", "rt-up" -> {
				try {
					if (robot == null) robot = new java.awt.Robot();
					if (word.equals("rt-down")) robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); else robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
				} catch (java.awt.AWTException | RuntimeException ignored) {}
			}
			default -> { }
		}
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
			boolean stop = (x instanceof AbstractButton || x instanceof JTabbedPane || x instanceof JTextComponent || x instanceof JTable || x instanceof JComboBox) && x.isEnabled() && x.isFocusable() && x.isShowing()
					&& !(x instanceof JTextComponent t && !t.isEditable());
			// (the arrows of a scroll bar or a drop-down aren't stops)
			if (stop && !(x.getParent() instanceof javax.swing.JScrollBar) && !(x.getParent() instanceof JComboBox)) out.add(x);
			if (x instanceof Container k && !(x instanceof JComboBox) && !(x instanceof JTable)) collect(k, out);
		}
	}

	/** Where a stop is on the screen: for a row of tabs, the tab that's open. */
	private static Rectangle where(Component c) {
		Point p = c.getLocationOnScreen();
		if (c instanceof JTabbedPane t && t.getSelectedIndex() >= 0) { Rectangle r = t.getBoundsAt(t.getSelectedIndex()); if (r != null) return new Rectangle(p.x + r.x, p.y + r.y, r.width, r.height); }
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
	static void home(Window w) {
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
	private static void selectByText(Window w, String... texts) {
		for (String t : texts) for (Component c : stops(w)) if (c instanceof AbstractButton b && t.equals(b.getText())) { land(c); return; }
	}
}
