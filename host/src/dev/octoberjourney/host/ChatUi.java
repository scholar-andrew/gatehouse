package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.m;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** The Chat page of Gatekeeper's window: the rooms (this Gatekeeper's, and one for each server), what was said in
 *  the one chosen, a line to write as "Host", and emptying a room. See Chat. */
final class ChatUi {
	private ChatUi() {}

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JComboBox<String> roomBox = new JComboBox<>();
		List<String> ids = new ArrayList<>();
		JTextArea log = new JTextArea();
		log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
		JTextField line = new JTextField();
		JButton send = new JButton("Send"), clear = new JButton("Empty this room…"), files = new JButton("Files\u2026");
		files.setToolTipText("The files on offer in this room: save a copy of one, or offer one");
		if (HostHelper.farUrl != null) { files.setEnabled(false); files.setToolTipText("Files are passed from that Gatekeeper's own window"); }
		files.addActionListener(e -> {
			int i = roomBox.getSelectedIndex();
			if (i < 0 || i >= ids.size()) return;
			String roomId = ids.get(i), base = "http://127.0.0.1:" + HostHelper.port() + "/admin/share";
			dev.gatehouse.look.FilesBox.show(p, String.valueOf(roomBox.getSelectedItem()), "",
				() -> { List<String[]> out = new ArrayList<>(); for (Object o : Json.list(ServerToolsUi.ask("GET", "/admin/share/list?room=" + java.net.URLEncoder.encode(roomId, java.nio.charset.StandardCharsets.UTF_8), null).get("files"))) { Map<String, Object> x = Json.map(o); long n = (long) Double.parseDouble(Json.str(x.get("size"))); out.add(new String[] {Json.str(x.get("id")), Json.str(x.get("name")), n < 1048576 ? (n / 1024 + 1) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0), Json.str(x.get("who")), Json.str(x.get("when"))}); } return out; },
				id -> { try { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "?id=" + id).openConnection(); c.setConnectTimeout(2000); c.setReadTimeout(60000); if (c.getResponseCode() != 200) return null; try (java.io.InputStream in = c.getInputStream()) { return in.readAllBytes(); } } catch (java.io.IOException ex) { return null; } },
				(name, bytes) -> { try { java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "?room=" + java.net.URLEncoder.encode(roomId, java.nio.charset.StandardCharsets.UTF_8) + "&name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)).openConnection(); c.setConnectTimeout(2000); c.setReadTimeout(120000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length); try (java.io.OutputStream o = c.getOutputStream()) { o.write(bytes); } try (java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return Json.str(Json.map(Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))).get("message")); } } catch (java.io.IOException ex) { return "Gatekeeper didn't answer."; } });
		});
		JLabel said = new JLabel(" ");
		said.setForeground(new Color(0x8F98A0));
		long[] shown = {0};
		String[] showing = {""};
		Runnable load = () -> ServerToolsUi.later(() -> {
			int i = roomBox.getSelectedIndex();
			String roomId = i >= 0 && i < ids.size() ? ids.get(i) : "";
			long after = roomId.equals(showing[0]) ? shown[0] : 0;
			Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/chat", m("room", roomId, "after", after));
			SwingUtilities.invokeLater(() -> {
				List<Object> rooms = Json.list(r.get("rooms"));
				if (rooms.size() != ids.size()) {
					String keep = roomId;
					ids.clear(); roomBox.removeAllItems();
					for (Object o : rooms) { ids.add(Json.str(Json.map(o).get("id"))); roomBox.addItem(Json.str(Json.map(o).get("name"))); }
					if (ids.contains(keep)) roomBox.setSelectedIndex(ids.indexOf(keep));
					return;
				}
				if (roomId.isEmpty()) return;
				if (!roomId.equals(showing[0])) { log.setText(""); showing[0] = roomId; }
				StringBuilder add = new StringBuilder();
				for (Object o : Json.list(r.get("messages"))) { Map<String, Object> e = Json.map(o); add.append(Json.str(e.get("when"))).append("  ").append(Json.str(e.get("who"))).append(": ").append(Json.str(e.get("text"))).append('\n'); }
				if (add.length() > 0) { log.append(add.toString()); log.setCaretPosition(log.getDocument().getLength()); }
				try { shown[0] = (long) Double.parseDouble(Json.str(r.get("next"))); } catch (RuntimeException e) { shown[0] = 0; }
			});
		});
		Runnable say = () -> {
			String t = line.getText().trim();
			int i = roomBox.getSelectedIndex();
			if (t.isEmpty() || i < 0 || i >= ids.size()) return;
			line.setText("");
			String roomId = ids.get(i);
			ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/chat", m("room", roomId, "after", shown[0], "text", t)); SwingUtilities.invokeLater(() -> said.setText(Json.str(r.get("message")).isEmpty() ? " " : Json.str(r.get("message")))); load.run(); });
		};
		send.addActionListener(e -> say.run());
		line.addActionListener(e -> say.run());
		roomBox.addActionListener(e -> { shown[0] = 0; showing[0] = ""; load.run(); });
		clear.addActionListener(e -> {
			int i = roomBox.getSelectedIndex();
			if (i < 0 || i >= ids.size()) return;
			if (JOptionPane.showConfirmDialog(f, "Empty this room? What was said in it is removed for everyone, and can't be brought back.", "Empty this room", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
			String roomId = ids.get(i);
			ServerToolsUi.later(() -> { ServerToolsUi.ask("POST", "/admin/chat/clear", m("room", roomId)); SwingUtilities.invokeLater(() -> { log.setText(""); shown[0] = 0; }); });
		});
		JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		top.add(new JLabel("Room:")); top.add(roomBox); top.add(files); top.add(clear);
		JPanel head = new JPanel(new BorderLayout(0, 4));
		head.add(ServerToolsUi.note(ServerToolsUi.wrap("One room for this Gatekeeper, and one for each server. A server's room is for the launchers you have let onto that server; this Gatekeeper's is for everyone in any of them. You write as Host. The newest 500 messages of each room are kept here.", 900)), BorderLayout.NORTH);
		head.add(top, BorderLayout.SOUTH);
		JPanel bottom = new JPanel(new BorderLayout(6, 2));
		bottom.add(line, BorderLayout.CENTER); bottom.add(send, BorderLayout.EAST); bottom.add(said, BorderLayout.SOUTH);
		p.add(head, BorderLayout.NORTH); p.add(new JScrollPane(log), BorderLayout.CENTER); p.add(bottom, BorderLayout.SOUTH);
		new Timer(3000, e -> { if (p.isShowing()) load.run(); }).start();
		load.run();
		return p;
	}
}
