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
		// R85: which YouTube video a link is, for Play here
		t.check("a watch link's video", "dQw4w9WgXcQ".equals(LinkPreview.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s")));
		t.check("a short link's video", "dQw4w9WgXcQ".equals(LinkPreview.youtubeId("https://youtu.be/dQw4w9WgXcQ?si=abc")));
		t.check("a Shorts link's video", "dQw4w9WgXcQ".equals(LinkPreview.youtubeId("https://youtube.com/shorts/dQw4w9WgXcQ")));
		t.check("not a video: a channel", LinkPreview.youtubeId("https://www.youtube.com/@someone") == null);
		t.check("not YouTube at all", LinkPreview.youtubeId("https://example.com/watch?v=dQw4w9WgXcQ") == null);
		t.check("an odd id is refused", LinkPreview.youtubeId("https://youtu.be/abc%22onload") == null);
		// R121: an X post that quotes another: the quoted one's author, words and video come along
		var xq = LinkPreview.xParse("{\"id_str\":\"111\",\"text\":\"Look at this https://t.co/abc\",\"user\":{\"name\":\"Outer\",\"screen_name\":\"outer\"},"
				+ "\"quoted_tweet\":{\"id_str\":\"222\",\"text\":\"The quoted words\",\"user\":{\"name\":\"Inner\",\"screen_name\":\"inner\"},"
				+ "\"mediaDetails\":[{\"type\":\"video\",\"media_url_https\":\"https://pbs.twimg.com/t.jpg\",\"video_info\":{\"variants\":[{\"content_type\":\"video/mp4\",\"bitrate\":832000,\"url\":\"https://video.twimg.com/v.mp4\"}]}}]}}", "111");
		t.check("an X post's words, without its own media link", "Look at this".equals(xq.text()) && "outer".equals(xq.handle()));
		t.check("the post it quotes comes along", xq.quoted() != null && "inner".equals(xq.quoted().handle()) && "222".equals(xq.quoted().id()) && "The quoted words".equals(xq.quoted().text()));
		t.check("with the quoted post's video", xq.quoted() != null && "https://video.twimg.com/v.mp4".equals(xq.quoted().video()) && xq.video() == null);
		var xg = LinkPreview.xParse("{\"id_str\":\"333\",\"text\":\"x\",\"user\":{},\"quoted_tweet\":{\"__typename\":\"TweetTombstone\"},\"quoted_tweet_id_str\":\"444\"}", "333");
		t.check("a quoted post X won't give: known as missing, with its number", xg.quoted() != null && !xg.quoted().known() && "444".equals(xg.quoted().id()));
		// R85: X posts
		t.check("an X post's number", "1585341984679469056".equals(LinkPreview.xPostId("https://x.com/someone/status/1585341984679469056?s=20")));
		t.check("a twitter.com post's number", "20".equals(LinkPreview.xPostId("https://twitter.com/jack/status/20")));
		t.check("X's links are X's", "X".equals(LinkPreview.videoSite("https://x.com/someone/status/123")));
		t.check("an X profile isn't a post", LinkPreview.xPostId("https://x.com/someone") == null);
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
