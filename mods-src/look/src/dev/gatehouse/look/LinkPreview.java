package dev.gatehouse.look;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R85: links in chat. A message's first web link gets Open, and Show preview: only when that is pressed does this
 * device fetch the page itself (no preview service in between, so the link goes only to its own site) and read its
 * title, description, site name and picture from the page's own tags (Open Graph, else its title). A link straight to
 * a video or sound file (.mp4, .mp3 ...) can be played in the chat's player instead. YouTube and Vimeo open in the
 * browser or their app.
 *
 * What it refuses, so a link can't be used to reach into the device's own networks: anything but http and https, and
 * any address that is this machine, the home network, Tailscale's or another private range (each redirect is checked
 * the same way). Pages are read up to 768 KB, pictures up to 2 MB, media up to 200 MB, each with a time limit.
 */
public final class LinkPreview {
	private LinkPreview() {}
	/** What a page says about itself; picture may be null. */
	public record Card(String url, String title, String description, String site, java.awt.image.BufferedImage picture) {}

	private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
	/** The first web link in a message's words, without trailing punctuation, or null. */
	public static String firstLink(String text) {
		if (text == null) return null;
		Matcher m = URL.matcher(text);
		if (!m.find()) return null;
		String u = m.group();
		while (!u.isEmpty() && ".,;:!?)]}'\"".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
		return u;
	}
	/** A link straight to a video or sound file the chat's player can play (by its name). */
	public static boolean media(String url) {
		String p = path(url);
		return p.matches(".*\\.(mp4|m4v|mov|mp3|m4a|wav|aif|aiff)$");
	}
	/** The file name a media link would be saved as. */
	public static String fileName(String url) {
		String p = path(url);
		String n = p.substring(p.lastIndexOf('/') + 1);
		return n.isEmpty() ? "linked-media" : n;
	}
	private static String path(String url) {
		try { String p = URI.create(url).getPath(); return p == null ? "" : p.toLowerCase(Locale.ROOT); } catch (RuntimeException e) { return ""; }
	}
	/** "YouTube" or "Vimeo" for their links (they open in the browser or app), else null. */
	public static String videoSite(String url) {
		String h = host(url);
		if (h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com")) return "YouTube";
		if (h.equals("vimeo.com") || h.endsWith(".vimeo.com")) return "Vimeo";
		return null;
	}
	static String host(String url) {
		try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (RuntimeException e) { return ""; }
	}

	/** Refuses addresses inside this device's own networks: loopback, private, link-local, Tailscale's (100.64/10),
	 *  unique-local IPv6 and the like. Public so the tests can try it. */
	public static boolean outside(InetAddress a) {
		if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
		byte[] b = a.getAddress();
		if (b.length == 4) {
			int x = b[0] & 0xFF, y = b[1] & 0xFF;
			if (x == 0 || x >= 224) return false;
			if (x == 100 && y >= 64 && y <= 127) return false;   // (carrier-grade NAT, which Tailscale uses)
			if (x == 192 && y == 0 && (b[2] & 0xFF) == 0) return false;
			if (x == 198 && (y == 18 || y == 19)) return false;
		} else if (b.length == 16) {
			if ((b[0] & 0xFE) == 0xFC) return false;   // (fc00::/7, unique local)
			boolean mapped = true; for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
			if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
				try { return outside(InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16))); } catch (IOException e) { return false; }
			}
		}
		return true;
	}
	private static void check(URI u) throws IOException {
		String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
		if (!s.equals("http") && !s.equals("https")) throw new IOException("only web links (http and https) are fetched");
		if (u.getHost() == null) throw new IOException("that link has no site in it");
		for (InetAddress a : InetAddress.getAllByName(u.getHost()))
			if (!outside(a)) throw new IOException("that link points inside a private network, so it isn't fetched");
	}

	/** Fetches up to max bytes from a web link (following up to four redirects, each checked); {bytes, content type}. */
	public static Object[] get(String url, int max, int seconds) throws IOException {
		URI u = URI.create(url);
		for (int hop = 0; hop < 5; hop++) {
			check(u);
			HttpURLConnection c = (HttpURLConnection) u.toURL().openConnection(java.net.Proxy.NO_PROXY);
			c.setInstanceFollowRedirects(false);
			c.setConnectTimeout(seconds * 1000); c.setReadTimeout(seconds * 1000);
			c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Gatehouse link preview)");
			c.setRequestProperty("Accept-Language", "en");
			int code = c.getResponseCode();
			if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { u = u.resolve(c.getHeaderField("Location")); c.disconnect(); continue; }
			if (code != 200) { c.disconnect(); throw new IOException("the site answered " + code); }
			long len = c.getContentLengthLong();
			// (a page is read only as far as max, since its own description is near its top; bigger media is refused)
			if (len > max && max > 1_000_000) { c.disconnect(); throw new IOException("it is " + ChatView.size(len) + ", more than " + ChatView.size(max)); }
			try (InputStream in = c.getInputStream()) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[16384]; int n; long t0 = System.currentTimeMillis();
				while ((n = in.read(buf)) > 0) {
					if (out.size() + n > max) { if (max > 1_000_000) throw new IOException("it is more than " + ChatView.size(max)); out.write(buf, 0, max - out.size()); break; }
					out.write(buf, 0, n);
					if (System.currentTimeMillis() - t0 > seconds * 4000L) throw new IOException("the site is too slow");
				}
				return new Object[] {out.toByteArray(), c.getContentType() == null ? "" : c.getContentType()};
			}
		}
		throw new IOException("the link redirects too many times");
	}

	/** The card a page's HTML gives (public for the tests); the picture is fetched separately (picture()). */
	public static Card parse(String url, String html) {
		String title = meta(html, "og:title"), desc = meta(html, "og:description"), site = meta(html, "og:site_name");
		if (title.isEmpty()) title = meta(html, "twitter:title");
		if (title.isEmpty()) { Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html); if (t.find()) title = text(t.group(1)); }
		if (desc.isEmpty()) desc = meta(html, "description");
		if (site.isEmpty()) site = host(url).replaceFirst("^www\\.", "");
		return new Card(url, cut(title, 140), cut(desc, 300), cut(site, 60), null);
	}
	/** The page's picture (og:image), scaled to at most 200 by 120, or null. */
	public static java.awt.image.BufferedImage picture(String url, String html) {
		String img = meta(html, "og:image");
		if (img.isEmpty()) img = meta(html, "twitter:image");
		if (img.isEmpty()) return null;
		try {
			Object[] got = get(URI.create(url).resolve(img).toString(), 2 * 1024 * 1024, 8);
			java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream((byte[]) got[0]));
			if (b == null) return null;
			double sc = Math.min(1.0, Math.min(200.0 / b.getWidth(), 120.0 / b.getHeight()));
			int w = Math.max(1, (int) (b.getWidth() * sc)), h = Math.max(1, (int) (b.getHeight() * sc));
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = out.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(b, 0, 0, w, h, null); g.dispose();
			return out;
		} catch (IOException | RuntimeException e) { return null; }
	}
	/** Fetches a page and its picture together: the whole card. */
	public static Card full(String url) throws IOException {
		String vs = videoSite(url);
		if (vs != null) return oembed(url, vs);
		Object[] got = get(url, 768 * 1024, 8);
		String type = ((String) got[1]).toLowerCase(Locale.ROOT);
		if (!type.isEmpty() && !type.contains("html")) throw new IOException("it isn't a web page (" + type.replaceAll(";.*", "") + ")");
		Charset cs = StandardCharsets.UTF_8;
		Matcher cm = Pattern.compile("charset=([\\w\\-]+)").matcher(type);
		if (cm.find()) try { cs = Charset.forName(cm.group(1)); } catch (RuntimeException ignored) { Quiet.seen(ignored); }
		String html = new String((byte[]) got[0], cs);
		Card c = parse(url, html);
		return new Card(c.url(), c.title(), c.description(), c.site(), picture(url, html));
	}

	/** YouTube and Vimeo give their pages to programs as a consent or sign-in page, so their own oEmbed answer is asked
	 *  instead (still their own site): the video's title, its channel, and its thumbnail. */
	private static Card oembed(String url, String site) throws IOException {
		String api = site.equals("YouTube") ? "https://www.youtube.com/oembed?format=json&url=" : "https://vimeo.com/api/oembed.json?url=";
		Object[] got = get(api + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8), 256 * 1024, 8);
		String j = new String((byte[]) got[0], StandardCharsets.UTF_8);
		String title = json(j, "title"), by = json(j, "author_name"), thumb = json(j, "thumbnail_url");
		java.awt.image.BufferedImage pic = thumb.isEmpty() ? null : picture(url, "<meta property=\"og:image\" content=\"" + thumb.replace("\"", "") + "\">");
		return new Card(url, cut(title, 140), by.isEmpty() ? "" : "by " + cut(by, 80), site, pic);
	}
	private static String json(String j, String key) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(j);
		if (!m.find()) return "";
		String s = m.group(1);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch != '\\' || i + 1 >= s.length()) { out.append(ch); continue; }
			char n = s.charAt(++i);
			if (n == 'u' && i + 4 < s.length()) { try { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } catch (NumberFormatException e) { out.append(n); } }
			else out.append(n == 'n' ? ' ' : n == 't' ? ' ' : n);
		}
		return out.toString().strip();
	}
	private static String meta(String html, String name) {
		Pattern p = Pattern.compile("(?is)<meta\\s[^>]*?(?:property|name)\\s*=\\s*[\"']" + Pattern.quote(name) + "[\"'][^>]*>");
		Matcher m = p.matcher(html);
		if (!m.find()) return "";
		Matcher c = Pattern.compile("(?is)content\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").matcher(m.group());
		return c.find() ? text(c.group(2) != null ? c.group(2) : c.group(3)) : "";
	}
	private static String text(String s) {
		return s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
	}
	private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1).strip() + "\u2026"; }

	/** Opens a link in this device's browser (or the site's own app, where the device sends it there). */
	public static void browse(String url) throws IOException {
		if (!url.matches("(?i)https?://.+")) throw new IOException("only web links (http and https) are opened");
		if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) java.awt.Desktop.getDesktop().browse(URI.create(url));
		else new ProcessBuilder("xdg-open", url).start();
	}
}
