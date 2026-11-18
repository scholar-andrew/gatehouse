package dev.gatehouse.look;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;

/**
 * A row of small coloured circles, one for each client a constellation is taking along in an update, with a line
 * beside them saying how many of the others are still being waited for:
 *   green   ready
 *   amber   hasn't seen the update yet (or, for a Gatekeeper, one of its own launchers hasn't)
 *   red     a Gatekeeper that is running a server itself, which has to be off first
 * This client's own pip has a white ring. Pointing at the row lists who is who, where the Gatekeeper said.
 *
 * It is given what a Gatekeeper sends as "gather": {"pips": [{"s": ready|waiting|busy, "k": launcher|gatekeeper,
 * "n": name or "", "me": true|false}, ...]}; nothing (or no pips) hides it.
 */
public final class Pips extends JComponent {
	static final Color READY = new Color(0x3FB950), WAITING = new Color(0xE9B949), BUSY = new Color(0xE5484D), GREY = new Color(0x8F98A0),
			TEXT = new Color(0xC7D5E0);
	private final List<String[]> pips = new ArrayList<>();   // {state, kind, name, me}
	private String line = "";

	public Pips() { setVisible(false); setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13)); }

	/** Shows what a Gatekeeper said about the gathering (a Map as either program's own JSON reader gives it), or hides itself. */
	public void set(Object gather) {
		pips.clear();
		Object list = gather instanceof Map<?, ?> g ? g.get("pips") : null;
		if (list instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> p)
			pips.add(new String[] {str(p.get("s")), str(p.get("k")), str(p.get("n")), str(p.get("me"))});
		int others = 0, waiting = 0, busy = 0;
		StringBuilder tip = new StringBuilder("<html>");
		for (String[] p : pips) {
			boolean me = p[3].equals("true");
			if (!me) { others++; if (p[0].equals("waiting")) waiting++; if (p[0].equals("busy")) busy++; }
			tip.append(me ? "<b>This one</b>" : esc(p[2].isEmpty() ? (p[1].equals("gatekeeper") ? "A Gatekeeper" : "A launcher") : p[2]
					+ (p[1].equals("gatekeeper") ? " (Gatekeeper)" : "")))
					.append(": ").append(p[0].equals("ready") ? "ready" : p[0].equals("busy") ? "a server it runs is still on" : "hasn't seen the update yet").append("<br>");
		}
		int notReady = waiting + busy;
		line = pips.isEmpty() ? "" : others == 0 ? "Nobody else is open in this constellation"
				: notReady == 0 ? "All " + others + " other" + (others == 1 ? " is" : "s are") + " ready"
				: "Waiting for " + notReady + " of " + others + " other" + (others == 1 ? "" : "s") + (busy > 0 ? " (" + busy + " with a server still on)" : "");
		setToolTipText(pips.isEmpty() ? null : tip.append("</html>").toString());
		setVisible(!pips.isEmpty());
		revalidate(); repaint();
	}
	private static String str(Object o) { return o == null ? "" : o.toString(); }
	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

	@Override public Dimension getPreferredSize() {
		FontMetrics fm = getFontMetrics(getFont());
		return new Dimension(pips.size() * 18 + 10 + fm.stringWidth(line) + 4, 22);
	}
	@Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 22); }

	@Override protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int x = 2, y = (getHeight() - 12) / 2;
		for (String[] p : pips) {
			g.setColor(p[0].equals("ready") ? READY : p[0].equals("busy") ? BUSY : p[0].equals("waiting") ? WAITING : GREY);
			if (p[1].equals("gatekeeper")) g.fillRoundRect(x, y, 12, 12, 4, 4); else g.fillOval(x, y, 12, 12);   // (a Gatekeeper is a square, a launcher a circle)
			if (p[3].equals("true")) { g.setColor(Color.WHITE); g.setStroke(new java.awt.BasicStroke(1.6f)); if (p[1].equals("gatekeeper")) g.drawRoundRect(x - 2, y - 2, 16, 16, 6, 6); else g.drawOval(x - 2, y - 2, 16, 16); }
			x += 18;
		}
		g.setColor(TEXT);
		g.setFont(getFont());
		FontMetrics fm = g.getFontMetrics();
		g.drawString(line, x + 8, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
		g.dispose();
	}
}
