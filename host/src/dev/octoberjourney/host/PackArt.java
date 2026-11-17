package dev.octoberjourney.host;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The five pictures Steam shows for a pack's shortcut, which every pack carries (steam/art) and the sync scripts put
 * on a device's Steam shortcut:
 *   cover  600 x 900    the library's upright picture
 *   hero   1920 x 620   the wide picture behind the shortcut's own page
 *   logo   636 x 360    the name over the hero (see-through around it)
 *   icon   512 x 512    the small square one
 *   wide   920 x 430    the library's wide picture
 * Each is either one the owner chose (any PNG, JPEG, GIF or BMP, brought to the slot's size here: cover, hero and wide
 * are filled and cropped from the middle; logo and icon are fitted inside, see-through around them) or one made here
 * from the server's name and its picture (Icons), so a pack never goes out with another pack's art. The made ones are
 * the same every time for the same name and picture, so a build that changes nothing changes no file.
 */
final class PackArt {
	private PackArt() {}
	static final String[] SLOTS = {"cover", "hero", "logo", "icon", "wide"};
	static final int[][] SIZE = {{600, 900}, {1920, 620}, {636, 360}, {512, 512}, {920, 430}};
	static final String[] SAYS = {"Cover (upright, 600 x 900)", "Hero (behind the page, 1920 x 620)", "Logo (the name over the hero, 636 x 360)", "Icon (square, 512 x 512)", "Wide (920 x 430)"};

	static int slot(String name) { for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(name)) return i; return -1; }

	private static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "png", o);
		return o.toByteArray();
	}
	private static Graphics2D pen(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		return g;
	}

	/** A picture the owner chose, brought to a slot's size. */
	static byte[] fit(byte[] any, int slot) throws IOException {
		BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(any));
		if (src == null || src.getWidth() < 1 || src.getHeight() < 1) throw new IOException("not a picture");
		int w = SIZE[slot][0], h = SIZE[slot][1];
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = pen(out);
		boolean inside = SLOTS[slot].equals("logo") || SLOTS[slot].equals("icon");
		double k = inside ? Math.min(w / (double) src.getWidth(), h / (double) src.getHeight()) : Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
		int dw = Math.max(1, (int) Math.round(src.getWidth() * k)), dh = Math.max(1, (int) Math.round(src.getHeight() * k));
		g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
		g.dispose();
		return png(out);
	}

	/** The pack's own two colours, from its name: the same name always gives the same ones. */
	private static Color[] colours(String name) {
		int n = 0;
		for (char c : name.toCharArray()) n = n * 31 + c;
		float hue = ((n & 0x7fffffff) % 360) / 360f;
		return new Color[] {Color.getHSBColor(hue, 0.55f, 0.42f), Color.getHSBColor((hue + 0.08f) % 1f, 0.70f, 0.14f)};
	}
	/** The name, as big as fits in the box, on up to three lines, centred. Nothing is drawn where this machine has no
	 *  fonts to draw with (a server machine without a desktop): the picture then goes out without the name. */
	private static void words(Graphics2D g, String name, int x, int y, int w, int h, Color colour) {
		try {
			String[] parts = name.trim().split("\\s+");
			for (int size = Math.min(h, 200); size >= 12; size -= 4) {
				Font f = new Font(Font.SANS_SERIF, Font.BOLD, size);
				FontMetrics fm = g.getFontMetrics(f);
				java.util.List<String> lines = new java.util.ArrayList<>();
				String line = "";
				for (String p : parts) { String t = line.isEmpty() ? p : line + " " + p; if (fm.stringWidth(t) <= w || line.isEmpty()) line = t; else { lines.add(line); line = p; } }
				if (!line.isEmpty()) lines.add(line);
				boolean fits = lines.size() <= 3 && lines.size() * fm.getHeight() <= h;
				for (String l : lines) if (fm.stringWidth(l) > w) fits = false;
				if (!fits && size > 12) continue;
				g.setFont(f);
				int top = y + (h - lines.size() * fm.getHeight()) / 2 + fm.getAscent();
				for (int i = 0; i < lines.size(); i++) {
					int lx = x + (w - fm.stringWidth(lines.get(i))) / 2, ly = top + i * fm.getHeight();
					g.setColor(new Color(0, 0, 0, 150)); g.drawString(lines.get(i), lx + Math.max(1, size / 24), ly + Math.max(1, size / 24));
					g.setColor(colour); g.drawString(lines.get(i), lx, ly);
				}
				return;
			}
		} catch (RuntimeException | Error noFonts) { /* (drawn without the name) */ }
	}
	private static String initials(String name) {
		StringBuilder sb = new StringBuilder();
		for (String p : name.trim().split("\\s+")) if (!p.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(p.charAt(0)));
		return sb.length() == 0 ? "?" : sb.toString();
	}

	/** A slot's picture made from the name and (when there is one) the server's own picture. */
	static byte[] made(int slot, String name, byte[] picture) throws IOException {
		int w = SIZE[slot][0], h = SIZE[slot][1];
		String which = SLOTS[slot];
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), pic = null;
		if (picture != null) try { pic = javax.imageio.ImageIO.read(new ByteArrayInputStream(picture)); } catch (IOException ignored) {}
		Graphics2D g = pen(out);
		Color[] c = colours(name);
		if (which.equals("logo")) { words(g, name, 8, 8, w - 16, h - 16, Color.WHITE); g.dispose(); return png(out); }
		g.setPaint(new GradientPaint(0, 0, c[0], w, h, c[1]));
		if (which.equals("icon")) g.fillRoundRect(0, 0, w, h, w / 5, w / 5); else g.fillRect(0, 0, w, h);
		// (a soft band of light across it, so it isn't a flat slab)
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 46), 0, h * 0.6f, new Color(255, 255, 255, 0)));
		if (which.equals("icon")) g.fillRoundRect(0, 0, w, h, w / 5, w / 5); else g.fillRect(0, 0, w, h);
		switch (which) {
			case "icon" -> { if (pic != null) { int s = (int) (w * 0.72); g.drawImage(pic, (w - s) / 2, (h - s) / 2, s, s, null); } else words(g, initials(name), w / 8, h / 8, w * 3 / 4, h * 3 / 4, Color.WHITE); }
			case "cover" -> {
				if (pic != null) { int s = (int) (w * 0.56); g.drawImage(pic, (w - s) / 2, h / 6, s, s, null); words(g, name, 40, h / 6 + s + 30, w - 80, h - (h / 6 + s + 30) - 60, Color.WHITE); }
				else words(g, name, 40, 60, w - 80, h - 120, Color.WHITE);
			}
			case "wide" -> {
				if (pic != null) { int s = (int) (h * 0.62); g.drawImage(pic, 50, (h - s) / 2, s, s, null); words(g, name, 50 + s + 36, 40, w - (50 + s + 36) - 40, h - 80, Color.WHITE); }
				else words(g, name, 50, 40, w - 100, h - 80, Color.WHITE);
			}
			default -> { if (pic != null) { int s = (int) (h * 0.5); g.drawImage(pic, w - s - 140, (h - s) / 2, s, s, null); } }   // (hero: Steam puts the logo over it, so no name here)
		}
		g.dispose();
		return png(out);
	}

	/** Fills art-auto in a pack's definition with a made picture for every slot the owner hasn't chosen one for, and
	 *  clears the ones no longer needed. Only written where it differs, so nothing changes when nothing changed. */
	static void fill(Path def, String name, byte[] picture) throws IOException {
		Path auto = def.resolve("art-auto");
		Files.createDirectories(auto);
		for (int i = 0; i < SLOTS.length; i++) {
			Path own = def.resolve("art").resolve(SLOTS[i] + ".png"), made = auto.resolve(SLOTS[i] + ".png");
			if (Files.isRegularFile(own)) { Files.deleteIfExists(made); continue; }
			byte[] b = made(i, name, picture);
			if (!Files.isRegularFile(made) || !java.util.Arrays.equals(Files.readAllBytes(made), b)) Files.write(made, b);
		}
	}
}
