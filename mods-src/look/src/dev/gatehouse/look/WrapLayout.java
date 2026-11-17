package dev.gatehouse.look;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.JComponent;

/**
 * A row of things that becomes several rows when the window is too narrow for one: whatever doesn't fit on a row
 * moves to the next, and the panel grows taller to hold them, so nothing is squeezed or cut off. (FlowLayout wraps
 * too, but never asks for the height its extra rows need, so they end up hidden.)
 *
 * Use on(panel) rather than setLayout: it also has the panel laid out again whenever its width changes.
 */
public final class WrapLayout extends FlowLayout {
	public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

	/** Gives a panel this layout (left-aligned), and keeps its rows right as the window is resized. */
	public static void on(JComponent panel, int hgap, int vgap) {
		panel.setLayout(new WrapLayout(FlowLayout.LEFT, hgap, vgap));
		java.awt.event.ComponentAdapter again = new java.awt.event.ComponentAdapter() {
			int was = -1;
			@Override public void componentResized(java.awt.event.ComponentEvent e) {
				if (panel.getWidth() == was) return;
				was = panel.getWidth();
				panel.revalidate();
				if (panel.getParent() != null) panel.getParent().revalidate();
			}
		};
		panel.addComponentListener(again);
	}

	@Override public Dimension preferredLayoutSize(Container target) { return size(target, true); }
	@Override public Dimension minimumLayoutSize(Container target) {
		Dimension d = size(target, false);
		d.width -= getHgap() + 1;
		return d;
	}

	private Dimension size(Container target, boolean preferred) {
		synchronized (target.getTreeLock()) {
			// The width there is to work with: the panel's own, or (before it has been laid out) the nearest ancestor's.
			int width = target.getSize().width;
			for (Container c = target.getParent(); width == 0 && c != null; c = c.getParent()) width = c.getSize().width;
			if (width == 0) width = Integer.MAX_VALUE;
			Insets in = target.getInsets();
			int room = width - in.left - in.right - getHgap() * 2, rowW = 0, rowH = 0, allW = 0, allH = 0;
			for (Component m : target.getComponents()) {
				if (!m.isVisible()) continue;
				Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
				if (rowW > 0 && rowW + getHgap() + d.width > room) {      // (doesn't fit: it starts the next row)
					allW = Math.max(allW, rowW); allH += rowH + getVgap();
					rowW = 0; rowH = 0;
				}
				rowW += (rowW > 0 ? getHgap() : 0) + d.width;
				rowH = Math.max(rowH, d.height);
			}
			allW = Math.max(allW, rowW); allH += rowH;
			return new Dimension(allW + in.left + in.right + getHgap() * 2, allH + in.top + in.bottom + getVgap() * 2);
		}
	}
}
