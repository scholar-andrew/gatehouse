package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * The pictures launchers show for this Gatekeeper and for each of its servers.
 *
 * The owner chooses them in Gatekeeper's window (Options, Pictures): any picture file, kept here as a 256 by 256
 * PNG in icons/<what>.png, where <what> is "gatekeeper" or a server's id. A server with none chosen uses the
 * server-icon.png in its own folder, if it has one (the picture Minecraft itself shows in its server list).
 * Launchers are told a short tag for each in the catalog ("icon"), and fetch the picture when the tag is new to them:
 *   GET /v1/icon?for=<what>            a launcher that has said hello
 *   ...&moving=1                       R75: the animated GIF it was chosen from, when it was one (404 otherwise);
 *                                      launchers play it while the card is lit, and show the still one otherwise
 *   GET, POST /admin/icon?for=<what>   Gatekeeper's own window: the picture; a new one (the file as the body), or
 *                                      none (an empty body)
 */
final class Icons {
	private Icons() {}
	static final int SIDE = 256, MAX = 12 * 1024 * 1024;

	private static String safe(String what) { return what.replaceAll("[^A-Za-z0-9_\\-]", "_"); }
	private static Path file(String what) { return dir.resolve("icons").resolve(safe(what) + ".png"); }
	private static Path gifFile(String what) { return dir.resolve("icons").resolve(safe(what) + ".gif"); }
	static final int MAX_GIF = 8 * 1024 * 1024;
	/** R75: the animated GIF a picture was chosen from, or null. */
	static byte[] gif(String what) {
		try { Path g = gifFile(what); return what != null && !what.isEmpty() && Files.isRegularFile(g) && Files.isRegularFile(file(what)) ? Files.readAllBytes(g) : null; }
		catch (IOException | RuntimeException none) { return null; }
	}
	/** Is it a GIF of more than one frame? */
	static boolean animated(byte[] b) {
		if (b.length < 6 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') return false;
		try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
			java.util.Iterator<javax.imageio.ImageReader> rs = javax.imageio.ImageIO.getImageReaders(in);
			if (!rs.hasNext()) return false;
			javax.imageio.ImageReader r = rs.next();
			try { r.setInput(in); return r.getNumImages(true) > 1; } finally { r.dispose(); }
		} catch (IOException | RuntimeException e) { return false; }
	}

	/** The picture for "gatekeeper" or a server's id, or null for none. */
	static byte[] png(String what) {
		try {
			if (what == null || what.isEmpty()) return null;
			Path f = file(what);
			if (Files.isRegularFile(f)) return Files.readAllBytes(f);
			if (!what.equals("gatekeeper")) for (Object o : servers()) {
				Map<String, Object> s = Json.map(o);
				if (!what.equals(Json.str(s.get("id"))) || Json.str(s.get("dir")).isEmpty()) continue;
				Path own = Path.of(Json.str(s.get("dir"))).resolve("server-icon.png");
				if (Files.isRegularFile(own) && Files.size(own) < 2_000_000) return Files.readAllBytes(own);
			}
		} catch (IOException | RuntimeException none) { /* no picture */ }
		return null;
	}
	/** A few letters that change when the picture does ("" for none). */
	static String tag(String what) { byte[] b = png(what), g = b == null ? null : gif(what); return b == null ? "" : sha256(b).substring(0, g == null ? 12 : 10) + (g == null ? "" : sha256(g).substring(0, 2)); }

	private static String asked(HttpExchange x) {
		String q = x.getRequestURI().getRawQuery();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)for=([^&]*)").matcher(q == null ? "" : q);
		return m.find() ? java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : "";
	}
	private static void send(HttpExchange x, String what) throws IOException {
		String q = x.getRequestURI().getRawQuery();
		boolean mv = q != null && q.matches("(?:.*&)?moving=1(?:&.*)?");
		byte[] b = mv ? gif(what) : png(what);
		if (b == null) { reply(x, 404, m("error", "no picture")); return; }
		x.getResponseHeaders().set("Content-Type", mv ? "image/gif" : "image/png");
		x.sendResponseHeaders(200, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}
	/** GET /v1/icon?for=... */
	static void serve(HttpExchange x) throws IOException { send(x, asked(x)); }

	/** Any picture, made square (the middle of it) and 256 on a side. */
	static byte[] square(byte[] any) throws IOException {
		BufferedImage in = javax.imageio.ImageIO.read(new ByteArrayInputStream(any));
		if (in == null) throw new IOException("not a picture");
		int cut = Math.min(in.getWidth(), in.getHeight()), sx = (in.getWidth() - cut) / 2, sy = (in.getHeight() - cut) / 2;
		BufferedImage out = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, cut < SIDE ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
				: RenderingHints.VALUE_INTERPOLATION_BICUBIC);   // (a small pixel-art icon stays sharp)
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(in, 0, 0, SIDE, SIDE, sx, sy, sx + cut, sy + cut, null);
		g.dispose();
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "png", o);
		return o.toByteArray();
	}

	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/icon")) return false;
		String what = asked(x);
		if (!x.getRequestMethod().equals("POST")) { send(x, what); return true; }
		boolean known = what.equals("gatekeeper");
		for (Object o : servers()) if (what.equals(Json.str(Json.map(o).get("id")))) known = true;
		if (!known) { reply(x, 200, m("result", "refused", "message", "No such server.")); return true; }
		byte[] b;
		try (InputStream in = x.getRequestBody()) { b = in.readNBytes(MAX + 1); }
		if (b.length > MAX) { reply(x, 200, m("result", "refused", "message", "That file is too big for a picture.")); return true; }
		try {
			if (b.length == 0) { Files.deleteIfExists(file(what)); Files.deleteIfExists(gifFile(what)); log("pictures: the chosen picture for " + what
					+ " was removed"); reply(x, 200, m("result", "ok", "message", "Removed.", "icon", tag(what))); return true; }
			byte[] png = square(b);
			Files.createDirectories(file(what).getParent());
			Files.write(file(what), png);
			boolean anim = animated(b) && b.length <= MAX_GIF;
			if (anim) Files.write(gifFile(what), b); else Files.deleteIfExists(gifFile(what));
			log("pictures: a" + (anim ? "n animated" : "") + " picture was chosen for " + what);
			reply(x, 200, m("result", "ok", "message", (anim ? "Saved. It plays while its card is under the pointer or chosen with a controller. "
					: animated(b) ? "Saved, as a still picture: an animated one can be 8 MB at most. " : "Saved. ")
					+ "Launchers show it the next time they ask.", "icon", tag(what)));
		} catch (IOException e) { reply(x, 200, m("result", "refused", "message", "That file isn't a picture this can read (PNG, JPEG, GIF and BMP are).")); }
		return true;
	}
}
