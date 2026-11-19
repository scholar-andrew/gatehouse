package dev.gatehouse;

import dev.gatehouse.look.LinkPreview;
import java.net.InetAddress;

/** R85: links in chat. Which link a message offers, what a page says about itself, and the addresses never fetched. */
public final class TestLinks {
	public static void main(String[] a) throws Exception {
		Tests t = new Tests("links in chat");
		t.check("the first link, without the full stop after it", "https://example.com/a?b=1".equals(LinkPreview.firstLink("look: https://example.com/a?b=1. and more")));
		t.check("no link, nothing offered", LinkPreview.firstLink("just words") == null);
		t.check("a video file link can be played", LinkPreview.media("https://cdn.example.com/clips/run.MP4?x=1"));
		t.check("a page isn't", !LinkPreview.media("https://example.com/watch"));
		t.check("YouTube's links are YouTube's", "YouTube".equals(LinkPreview.videoSite("https://youtu.be/abc")) && "YouTube".equals(LinkPreview.videoSite("https://www.youtube.com/watch?v=abc")));
		t.check("Vimeo's are Vimeo's", "Vimeo".equals(LinkPreview.videoSite("https://vimeo.com/123")));
		t.check("another site is neither", LinkPreview.videoSite("https://notyoutube.com.example.org/") == null);
		var c = LinkPreview.parse("https://www.example.org/x", "<html><head><title>Plain title</title><meta property=\"og:title\" content=\"The &amp; title\">"
				+ "<meta name=\"description\" content='What it is'></head></html>");
		t.check("a page's own title (Open Graph first)", "The & title".equals(c.title()));
		t.check("its description", "What it is".equals(c.description()));
		t.check("its site, from the address when it doesn't say", "example.org".equals(c.site()));
		for (String no : new String[] {"127.0.0.1", "10.1.2.3", "192.168.1.20", "172.16.5.4", "169.254.1.1", "100.100.1.1", "0.0.0.0", "::1", "fd00::1", "::ffff:192.168.1.1"})
			t.check("never fetched: " + no, !LinkPreview.outside(InetAddress.getByName(no)));
		t.check("a public address is fetched", LinkPreview.outside(InetAddress.getByName("93.184.215.14")));
		try { LinkPreview.get("http://127.0.0.1:1/", 1000, 2); t.check("a link to this machine is refused", false); }
		catch (java.io.IOException e) { t.check("a link to this machine is refused", e.getMessage().contains("private network")); }
		try { LinkPreview.get("file:///C:/Windows/win.ini", 1000, 2); t.check("a file link is refused", false); }
		catch (Exception e) { t.check("a file link is refused", true); }
		t.done();
	}
}
