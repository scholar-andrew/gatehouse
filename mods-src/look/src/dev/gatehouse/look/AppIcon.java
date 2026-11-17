package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The icon both programs show on their windows and on the taskbar: the gatehouse from the Steam artwork, drawn here
 * at whatever size is asked for (so it is sharp from 16 pixels up, and nothing has to be shipped as a picture).
 * Gatehouse has it with a blue gate, Gatekeeper with an amber one, so the two can be told apart side by side.
 *
 * It also carries what is waiting: a red circle with a number (messages: for Gatekeeper, launchers' requests; for
 * Gatehouse, the owner's answers not looked at yet) and a green circle (an update for the program is ready).
 */
public final class AppIcon {
	private AppIcon() {}
	static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
	private static final Map<Window, String> shown = new WeakHashMap<>();

	/** Puts the icon on a window (and on the taskbar entry it has), with what is waiting. Asked again with the same
	 *  things, it does nothing. */
	public static void apply(Window w, boolean keeper, int messages, boolean update) {
		if (w == null) return;
		String now = keeper + "|" + Math.max(0, messages) + "|" + update;
		synchronized (shown) { if (now.equals(shown.get(w))) return; shown.put(w, now); }
		List<Image> all = new ArrayList<>();
		for (int s : SIZES) all.add(draw(s, keeper, messages, update));
		w.setIconImages(all);
	}

	/** The icon at one size. */
	public static BufferedImage draw(int size, boolean keeper, int messages, boolean update) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		double k = size / 512.0;
		boolean small = size < 40;
		Color accent = keeper ? new Color(0x66C0F4) : GameShell.GATEHOUSE, stone = new Color(0xE3EAF2), stoneDark = new Color(0xC6D2DF), ink = new Color(0x15202D);

		// the tile
		RoundRectangle2D tile = new RoundRectangle2D.Double(0, 0, size, size, 112 * k, 112 * k);
		g.setPaint(new GradientPaint(0, 0, new Color(0x2B4A66), 0, size, new Color(0x121A26)));
		g.fill(tile);
		g.setClip(tile);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 34), 0, (float) (size * 0.5), new Color(255, 255, 255, 0)));
		g.fill(new Ellipse2D.Double(-size * 0.3, -size * 0.75, size * 1.6, size * 1.2));
		g.setClip(null);

		// the gatehouse: two towers, the wall between them, and the gate
		double m = small ? 30 : 20, top = small ? 112 : 116;       // (a merlon's width and where the battlements start)
		Area castle = new Area();
		for (double x0 : new double[] {76, 336}) {
			castle.add(new Area(new Rectangle2D.Double(x0, 156, 100, 280)));
			if (small) { castle.add(new Area(new Rectangle2D.Double(x0, top, m, 46))); castle.add(new Area(new Rectangle2D.Double(x0 + 100 - m, top, m, 46))); }
			else for (double x = x0; x <= x0 + 80; x += 40) castle.add(new Area(new Rectangle2D.Double(x, top, m, 42)));
		}
		Area wall = new Area(new Rectangle2D.Double(176, 216, 160, 220));
		if (small) wall.add(new Area(new Rectangle2D.Double(236, 184, 40, 34)));
		else for (double x = 186; x <= 306; x += 40) wall.add(new Area(new Rectangle2D.Double(x, 188, m, 30)));
		Path2D gate = new Path2D.Double();
		gate.moveTo(208, 436); gate.lineTo(208, 316); gate.curveTo(208, 252, 304, 252, 304, 316); gate.lineTo(304, 436); gate.closePath();
		Area gateArea = new Area(gate);
		java.awt.geom.AffineTransform scale = java.awt.geom.AffineTransform.getScaleInstance(k, k);
		Area all = new Area(castle); all.add(wall);
		// (a soft shadow under it)
		g.setColor(new Color(0, 0, 0, 70));
		g.fill(all.createTransformedArea(java.awt.geom.AffineTransform.getTranslateInstance(0, 10)).createTransformedArea(scale));
		g.setPaint(new GradientPaint(0, (float) (188 * k), stoneDark, 0, (float) (436 * k), new Color(0xAEBDCD)));
		g.fill(wall.createTransformedArea(scale));
		g.setPaint(new GradientPaint(0, (float) (116 * k), new Color(0xF4F7FB), 0, (float) (436 * k), stone));
		g.fill(castle.createTransformedArea(scale));
		// the stripe along the foot, and the gate with its portcullis
		g.setColor(accent);
		g.fill(new Rectangle2D.Double(76 * k, 414 * k, 360 * k, 22 * k));
		g.setColor(ink);
		g.fill(gateArea.createTransformedArea(scale));
		g.setClip(gateArea.createTransformedArea(scale));
		g.setColor(accent);
		double bar = small ? 14 : 8;
		for (double x : small ? new double[] {232, 266} : new double[] {222, 244, 266, 288}) g.fill(new Rectangle2D.Double(x * k, 260 * k, bar * k, (small ? 120 : 92) * k));
		g.fill(new Rectangle2D.Double(200 * k, (small ? 318 : 308) * k, 112 * k, (small ? 12 : 7) * k));
		g.setClip(null);
		if (!small) {
			g.setColor(ink);
			for (double x : new double[] {116, 376}) g.fill(new RoundRectangle2D.Double(x * k, 216 * k, 20 * k, 50 * k, 8 * k, 8 * k));
		}
		g.setColor(new Color(255, 255, 255, 40));
		g.setStroke(new BasicStroke((float) Math.max(1, 3 * k)));
		g.draw(new RoundRectangle2D.Double(1.5 * k, 1.5 * k, size - 3 * k, size - 3 * k, 110 * k, 110 * k));

		// what is waiting: messages (red, with how many) at the top right, an update (green) at the bottom right
		if (messages > 0) {
			double d = size * (small ? 0.62 : size >= 64 ? 0.40 : 0.46);
			badge(g, size - d, 0, d, new Color(0xE5484D));
			String n = messages > 9 ? "9+" : String.valueOf(messages);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.max(7, Math.round(d * (n.length() > 1 ? 0.56 : 0.72)))));
			FontMetrics fm = g.getFontMetrics();
			g.setColor(Color.WHITE);
			g.drawString(n, (float) (size - d / 2 - fm.stringWidth(n) / 2.0), (float) (d / 2 + (fm.getAscent() - fm.getDescent()) / 2.0));
		}
		if (update) {
			double d = size * (small ? 0.5 : size >= 64 ? 0.30 : 0.36);
			badge(g, size - d, size - d, d, new Color(0x3FB950));
		}
		g.dispose();
		return img;
	}
	private static void badge(Graphics2D g, double x, double y, double d, Color fill) {
		g.setColor(new Color(0x121A26));
		g.fill(new Ellipse2D.Double(x, y, d, d));
		double ring = Math.max(1, d * 0.09);
		g.setColor(fill);
		g.fill(new Ellipse2D.Double(x + ring, y + ring, d - 2 * ring, d - 2 * ring));
	}

	/** Testing: writes the icon to PNG files, <prefix>-<size>.png, for a look. Arguments: prefix, keeper (true/false), messages, update. */
	public static void main(String[] a) throws java.io.IOException {
		boolean keeper = a.length > 1 && a[1].equals("true"), update = a.length > 3 && a[3].equals("true");
		int messages = a.length > 2 ? Integer.parseInt(a[2]) : 0;
		for (int s : new int[] {16, 32, 48, 256}) javax.imageio.ImageIO.write(draw(s, keeper, messages, update), "png", new java.io.File(a[0] + "-" + s + ".png"));
	}
}
