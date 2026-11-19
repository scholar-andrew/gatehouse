package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.m;

import java.awt.BorderLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import dev.gatehouse.look.ChatView;
import dev.gatehouse.look.Quiet;

/** The Chat page of Gatekeeper's window: its rooms (this Gatekeeper's, and one for each server) as cards, what was
 *  said in the one chosen, writing as "Host", files offered in a room, and emptying a room. See Chat and ChatView. */
final class ChatUi {
	private ChatUi() {}
	/** Told how many rooms have messages not seen here (the window puts it on its Chat section). */
	static volatile IntConsumer unread = n -> {};

	private static Path seenFile() { return HostHelper.dir.resolve("chat-seen.txt"); }
	private static synchronized java.util.Properties seen() {
		java.util.Properties p = new java.util.Properties();
		try (InputStream in = Files.newInputStream(seenFile())) { p.load(in); } catch (IOException ignored) { Quiet.seen(ignored); }
		return p;
	}
	private static synchronized void seen(String k, String v) {
		java.util.Properties p = seen();
		p.setProperty(k, v);
		try (OutputStream o = Files.newOutputStream(seenFile())) { p.store(o,
				"where each chatroom has been read up to here"); } catch (IOException ignored) { Quiet.seen(ignored); }
	}
	private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		boolean far = HostHelper.farUrl != null;
		ChatView.Backend backend = new ChatView.Backend() {
			@Override public List<ChatView.Room> rooms() {
				Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/chat", m());
				if (!r.containsKey("rooms")) throw new IllegalStateException("Gatekeeper isn't answering");
				List<ChatView.Room> out = new ArrayList<>();
				for (Object o : Json.list(r.get("rooms"))) {
					Map<String, Object> e = Json.map(o);
					String id = Json.str(e.get("id"));
					long last; try { last = (long) Double.parseDouble(Json.str(e.get("last"))); } catch (RuntimeException x) { last = 0; }
					out.add(new ChatView.Room(id, Json.str(e.get("name")).replace(" (everyone here)", ""), "", last, far ? null : () -> {
						byte[] b = Icons.png(id);
						try { return b == null ? null : javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b)); } catch (IOException x) { return null; }
					}));
				}
				return out;
			}
			@Override public ChatView.Batch talk(ChatView.Room room, long after, String text) {
				Map<String, Object> body = m("room", room.key(), "after", after);
				if (text != null) body.put("text", text);
				Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/chat", body);
				List<ChatView.Msg> ms = new ArrayList<>();
				for (Object o : Json.list(r.get("messages"))) {
					Map<String, Object> e = Json.map(o), fl = Json.map(e.get("file"));
					long n, size; try { n = (long) Double.parseDouble(Json.str(e.get("n"))); } catch (RuntimeException x) { n = 0; }
					try { size = (long) Double.parseDouble(Json.str(fl.get("size"))); } catch (RuntimeException x) { size = 0; }
					ms.add(new ChatView.Msg(n, Json.str(e.get("when")), Json.str(e.get("who")), Json.str(e.get("text")),
							Json.str(fl.get("id")), Json.str(fl.get("name")), size));
				}
				long next; try { next = (long) Double.parseDouble(Json.str(r.get("next"))); } catch (RuntimeException x) { next = after; }
				return new ChatView.Batch(ms, next, Json.str(r.get("message")));
			}
			@Override public byte[] file(ChatView.Room room, String id) throws IOException {
				if (far) throw new IOException("files are passed from that Gatekeeper's own window");
				HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + HostHelper.port() + "/admin/share?id=" + enc(id)).openConnection();
				c.setConnectTimeout(2000); c.setReadTimeout(120000);
				if (c.getResponseCode() != 200) return null;
				try (InputStream in = c.getInputStream()) { return in.readAllBytes(); }
			}
			@Override public String offer(ChatView.Room room, String name, byte[] data) throws IOException {
				if (far) throw new IOException("files are passed from that Gatekeeper's own window");
				HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + HostHelper.port() + "/admin/share?room="
						+ enc(room.key()) + "&name=" + enc(name)).openConnection();
				c.setConnectTimeout(2000); c.setReadTimeout(120000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(data.length);
				try (OutputStream o = c.getOutputStream()) { o.write(data); }
				try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream()
						: c.getErrorStream()) { return Json.str(Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("message")); }
			}
		};
		ChatView.Store store = new ChatView.Store() {
			@Override public String get(String key) { return seen().getProperty(key); }
			@Override public void put(String key, String value) { seen(key, value); }
		};
		JButton clear = new JButton("Empty this room…");
		clear.setToolTipText("Removes what was said in the chosen room, for everyone");
		ChatView[] view = {null};
		boolean pictures = System.getenv("OJ_PANEL_PNG") != null;   // (testing: pictures of the window, which is never on screen)
		view[0] = new ChatView(backend, store, () -> p.isShowing() || pictures, n -> unread.accept(n), clear);
		clear.addActionListener(e -> {
			String room = view[0].chosen();
			if (room.isEmpty()) return;
			if (dev.gatehouse.look.InWindow.showConfirmDialog(f,
					"Empty this room? What was said in it is removed for everyone, and can't be brought back.", "Empty this room",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
			ServerToolsUi.later(() -> { ServerToolsUi.ask("POST", "/admin/chat/clear", m("room", room)); view[0].reset(); });
		});
		if (far) clear.setEnabled(false);
		p.add(ServerToolsUi.note(ServerToolsUi.wrap("One room for this Gatekeeper, and one for each server. A server's room is for the launchers you have let onto that server; this Gatekeeper's is for everyone in any of them. You write as Host. A file offered in a room is kept here for a day.", 900)), BorderLayout.NORTH);
		p.add(view[0], BorderLayout.CENTER);
		p.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && p.isShowing()) view[0].poke(); });
		return p;
	}
}
