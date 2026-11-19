package dev.gatehouse.web;

import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javax.swing.SwingUtilities;

/**
 * R85: a YouTube video played inside the chat message, in OpenJFX's web view. YouTube won't play its player in a page
 * that has no address of its own (Error 153), so this serves one tiny page on this machine only (127.0.0.1, a port
 * the system picks, nothing else on it): YouTube's privacy-enhanced player (youtube-nocookie.com) for the one video.
 * Built against OpenJFX's base, graphics, controls, web and swing parts, shipped inside the program as web-player.jar,
 * and loaded by dev.gatehouse.look.MediaEngine together with the media player, which calls youtube() by name.
 */
public final class WebPlayer {
	private WebPlayer() {}
	private static final int W = 560, H = 315;
	private static HttpServer server;

	/** The page for one video, on this machine only. */
	private static synchronized int port() throws IOException {
		if (server != null) return server.getAddress().getPort();
		HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		s.createContext("/yt/", x -> {
			String id = x.getRequestURI().getPath().substring(4);
			String host = String.valueOf(x.getRequestHeaders().getFirst("Host"));
			byte[] b;
			int code = 200;
			if (!id.matches("[A-Za-z0-9_-]{11}") || !host.startsWith("127.0.0.1:")) { code = 404; b = "no".getBytes(StandardCharsets.UTF_8); }
			else b = ("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
					+ "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head><body>"
					+ "<iframe src=\"https://www.youtube-nocookie.com/embed/" + id + "?autoplay=1&playsinline=1&rel=0&origin=http://127.0.0.1:" + s.getAddress().getPort()
					+ "\" allow=\"autoplay; encrypted-media; picture-in-picture\" referrerpolicy=\"strict-origin-when-cross-origin\" allowfullscreen></iframe></body></html>").getBytes(StandardCharsets.UTF_8);
			x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
			x.getResponseHeaders().set("Cache-Control", "no-store");
			x.sendResponseHeaders(code, b.length);
			try (OutputStream o = x.getResponseBody()) { o.write(b); }
		});
		s.start();
		server = s;
		return s.getAddress().getPort();
	}

	/** A web view playing the video, for the chat message (call on Swing's thread). */
	public static Component youtube(String id, Consumer<String> problem) throws IOException {
		if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IOException("that isn't a YouTube video's address");
		String url = "http://127.0.0.1:" + port() + "/yt/" + id;
		JFXPanel panel = new JFXPanel();
		Platform.setImplicitExit(false);
		Dimension d = new Dimension(W, H);
		panel.setPreferredSize(d); panel.setMaximumSize(d);
		WebView[] view = {null};
		Platform.runLater(() -> {
			try {
				WebView v = new WebView();
				v.setContextMenuEnabled(false);
				v.getEngine().getLoadWorker().exceptionProperty().addListener((o, was, now) -> {
					if (now != null) SwingUtilities.invokeLater(() -> problem.accept("the video's page didn't load (" + now.getMessage() + ")"));
				});
				view[0] = v;
				panel.setScene(new Scene(v, Color.BLACK));
				v.getEngine().load(url);
			} catch (RuntimeException e) { SwingUtilities.invokeLater(() -> problem.accept(String.valueOf(e.getMessage()))); }
		});
		// (stops it when the message leaves the screen for good: another room, the window closed, Close)
		panel.addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !panel.isDisplayable())
				Platform.runLater(() -> { if (view[0] != null) view[0].getEngine().load("about:blank"); });
		});
		return panel;
	}
}
