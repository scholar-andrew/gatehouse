package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Small pictures of the kinds of machine a Gatekeeper runs on, drawn here (no picture files, nobody's logo): a
 * desktop, a laptop, the handhelds, the Macs, a small box, a server. A Gatekeeper says which it is on; the launcher
 * draws that on its card.
 *
 * The kinds, by id: desktop, laptop, steamdeck, rogally, handheld, minipc, maclaptop, macdesktop, macmini, server.
 */
public final class DeviceIcon {
	private DeviceIcon() {}
	public static final String[][] KINDS = {{"desktop", "Desktop PC"}, {"laptop", "Laptop"}, {"steamdeck", "Steam Deck"}, {"rogally", "ROG Ally"}, {"handheld", "Another handheld"},
			{"minipc", "Mini PC"}, {"maclaptop", "Mac laptop"}, {"macdesktop", "Mac desktop (all-in-one)"}, {"macmini", "Mac mini"}, {"server", "Server"}};

	public static boolean known(String kind) { for (String[] k : KINDS) if (k[0].equals(kind)) return true; return false; }
	/** What a kind is called ("" for one that isn't known). */
	public static String name(String kind) { for (String[] k : KINDS) if (k[0].equals(kind)) return k[1]; return ""; }

	private static RoundRectangle2D rr(double x, double y, double w, double h, double r) { return new RoundRectangle2D.Double(x, y, w, h, r, r); }
	private static Ellipse2D dot(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r); }

	/** Draws a kind inside the square at x, y of the given size. Light on whatever is behind it. */
	public static void paint(Graphics2D g0, String kind, double x, double y, double size) {
		Graphics2D g = (Graphics2D) g0.create();
		g.translate(x, y); g.scale(size / 100.0, size / 100.0);
		Color body = new Color(255, 255, 255, 236), glass = new Color(16, 26, 40, 215), mark = new Color(16, 26, 40, 200);
		g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (kind) {
			case "desktop" -> {   // a monitor on its stand, and a tower beside it
				g.setColor(body); g.fill(rr(4, 20, 62, 44, 7)); g.fill(rr(30, 64, 10, 12, 2)); g.fill(rr(18, 76, 34, 6, 3)); g.fill(rr(73, 16, 23, 66, 5));
				g.setColor(glass); g.fill(rr(9, 25, 52, 34, 3));
				g.setColor(mark); g.fill(dot(84.5, 26, 2.6)); g.fill(rr(79, 36, 11, 2.6, 1)); g.fill(rr(79, 42, 11, 2.6, 1));
			}
			case "laptop" -> {   // an open lid over a base that is wider than it
				g.setColor(body); g.fill(rr(18, 22, 64, 44, 6));
				Path2D base = new Path2D.Double(); base.moveTo(12, 68); base.lineTo(88, 68); base.lineTo(96, 78); base.quadTo(96, 82, 92, 82); base.lineTo(8, 82); base.quadTo(4, 82, 4, 78); base.closePath(); g.fill(base);
				g.setColor(glass); g.fill(rr(23, 27, 54, 34, 3));
				g.setColor(mark); g.fill(rr(41, 74, 18, 3, 1.5));
			}
			case "steamdeck" -> {   // a wide handheld with rounded grips: a stick and a square pad each side of the screen
				g.setColor(body); g.fill(rr(2, 28, 96, 44, 22));
				g.setColor(glass); g.fill(rr(27, 34, 46, 32, 3));
				g.setColor(mark); g.fill(dot(15, 41, 5)); g.fill(rr(9.5, 51, 11, 11, 2.5)); g.fill(dot(85, 41, 5)); g.fill(rr(79.5, 51, 11, 11, 2.5));
			}
			case "rogally" -> {   // a handheld with cut corners: a stick and a d-pad on the left, four buttons and a stick on the right
				Path2D p = new Path2D.Double(); p.moveTo(11, 28); p.lineTo(89, 28); p.lineTo(98, 39); p.lineTo(98, 60); p.lineTo(88, 72); p.lineTo(12, 72); p.lineTo(2, 60); p.lineTo(2, 39); p.closePath();
				g.setColor(body); g.fill(p);
				g.setColor(glass); g.fill(rr(26, 34, 48, 32, 2));
				g.setColor(mark); g.fill(dot(14, 42, 5)); g.fill(rr(11.5, 54, 5, 13, 1)); g.fill(rr(7.5, 58, 13, 5, 1));
				g.fill(dot(86, 59, 5)); for (double[] b : new double[][] {{86, 37}, {81.5, 42}, {90.5, 42}, {86, 47}}) g.fill(dot(b[0], b[1], 2.3));
			}
			case "handheld" -> {   // any other handheld: a rounded slab, a stick one side and buttons the other
				g.setColor(body); g.fill(rr(3, 30, 94, 40, 14));
				g.setColor(glass); g.fill(rr(25, 35, 50, 30, 3));
				g.setColor(mark); g.fill(dot(14, 50, 5.5)); for (double[] b : new double[][] {{86, 44}, {81, 50}, {91, 50}, {86, 56}}) g.fill(dot(b[0], b[1], 2.4));
			}
			case "minipc" -> {   // a small square box, seen from the front and a little above
				g.setColor(body); g.fill(rr(20, 34, 60, 36, 7));
				g.setColor(glass); g.fill(rr(20, 52, 60, 3, 1));
				g.setColor(mark); g.fill(dot(70, 62, 2.6)); g.fill(rr(28, 60, 12, 4, 1.5)); g.fill(rr(44, 60, 6, 4, 1.5));
			}
			case "maclaptop" -> {   // a thin lid with a notch at the top, on a base that is hardly thicker than a line
				g.setColor(body); g.fill(rr(17, 20, 66, 46, 7)); g.fill(rr(5, 69, 90, 6.5, 3.2));
				g.setColor(glass); g.fill(rr(21, 24, 58, 38, 4));
				g.setColor(body); g.fill(rr(44, 23, 12, 4.2, 2));
				g.setColor(mark); g.fill(rr(42, 69, 16, 2.4, 1.2));
			}
			case "macdesktop" -> {   // an all-in-one: a screen with a plain band under it, on a slim foot
				g.setColor(body); g.fill(rr(12, 12, 76, 56, 6)); g.fill(rr(43, 66, 14, 14, 1)); g.fill(rr(32, 79, 36, 5, 2.5));
				g.setColor(glass); g.fill(rr(16, 16, 68, 39, 3));
			}
			case "macmini" -> {   // a low, wide box with well-rounded corners and one small light
				g.setColor(body); g.fill(rr(14, 40, 72, 24, 9));
				g.setColor(new Color(16, 26, 40, 70)); g.fill(rr(14, 58, 72, 6, 5));
				g.setColor(mark); g.fill(dot(76, 52, 2.2));
			}
			case "server" -> {   // three units in a rack, each with its lights
				g.setColor(body); for (int i = 0; i < 3; i++) g.fill(rr(14, 20 + i * 21, 72, 17, 4));
				g.setColor(mark); for (int i = 0; i < 3; i++) { g.fill(dot(24, 28.5 + i * 21, 2.6)); g.fill(dot(32, 28.5 + i * 21, 2.6)); g.fill(rr(52, 27 + i * 21, 26, 3, 1.5)); }
			}
			default -> { }
		}
		g.dispose();
	}
}
