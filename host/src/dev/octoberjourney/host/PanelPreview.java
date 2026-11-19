package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.gatehouse.look.LinkPreview;

/**
 * R120: a link's preview for the web panel's chat. A phone's browser can't read another site's page itself, so the
 * panel asks its Gatekeeper, which fetches it the way Gatehouse's chat does (LinkPreview: never this machine, the home
 * network or Tailscale addresses; a few seconds and a size limit) and answers with its title, site, description and a
 * small picture (put in the answer itself, so the phone fetches nothing from that site for it). For an X post: who
 * wrote it and what, its photos and video's addresses (the phone fetches those from X when Show media is pressed),
 * and the post it quotes (R121). Only when a panel user presses Show preview; at most 30 a minute.
 */
final class PanelPreview {
	private PanelPreview() {}
	private static final List<Long> recent = new ArrayList<>();

	static Map<String, Object> answer(String url) {
		synchronized (recent) {
			long now = System.currentTimeMillis();
			recent.removeIf(t -> now - t > 60_000);
			if (recent.size() >= 30) return m("result", "refused", "message", "Too many previews just now: try again in a minute.");
			recent.add(now);
		}
		if (url == null || url.length() > 2000 || !url.matches("(?i)https?://[^\\s<>\"']+")) return m("result", "refused", "message", "That isn't a web link.");
		try {
			String xid = LinkPreview.xPostId(url);
			if (xid != null) {
				LinkPreview.XMedia x = LinkPreview.xMedia(xid);
				return m("result", "ok", "kind", "x", "post", post(x), "quoted", x.quoted() == null ? null : post(x.quoted()));
			}
			LinkPreview.Card c = LinkPreview.full(url);
			return m("result", "ok", "kind", "page", "title", c.title(), "site", c.site(), "description", c.description(),
					"picture", c.picture() == null ? "" : dataUri(c.picture()));
		} catch (Exception e) {
			return m("result", "failed", "message", "No preview for that link: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
		}
	}
	private static Map<String, Object> post(LinkPreview.XMedia x) {
		return m("name", x.name(), "handle", x.handle(), "text", x.text(), "id", x.id(), "known", x.known(), "photos", new ArrayList<Object>(x.photos()),
				"video", x.video() == null ? "" : x.video(), "thumb", x.videoThumb() == null ? "" : x.videoThumb());
	}
	/** A picture as a small JPEG in a data: address (at most 320 wide). */
	private static String dataUri(BufferedImage src) throws java.io.IOException {
		int w = src.getWidth(), h = src.getHeight();
		double k = Math.min(1, 320.0 / Math.max(1, w));
		int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
		BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, nw, nh);
		g.drawImage(src, 0, 0, nw, nh, null); g.dispose();
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "jpg", o);
		return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(o.toByteArray());
	}
}
