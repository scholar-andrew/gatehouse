package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import dev.gatehouse.look.Quiet;

/**
 * Part of Gatekeeper's Options page: the pictures launchers show, one for this Gatekeeper and one for each server, chosen, shown and removed here.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class PicturesUi {
	private PicturesUi() {}

	static void addTo(JPanel options, JFrame f, java.util.function.BiConsumer<String, Boolean> optLine) {
		// The pictures launchers show: one for this Gatekeeper, one for each server.
		optLine.accept("Pictures", true);
		javax.swing.JComboBox<String> picFor = new javax.swing.JComboBox<>();
		List<String> picIds = new ArrayList<>();
		JButton picChoose = new JButton("Choose a picture\u2026"), picRemove = new JButton("Remove it");
		JLabel picSaid = new JLabel(" ");
		picSaid.setForeground(new Color(0x8F98A0));
		JPanel picRow = new JPanel(new dev.gatehouse.look.WrapLayout(java.awt.FlowLayout.LEFT, 6, 4));
		picRow.add(picFor); picRow.add(picChoose); picRow.add(picRemove); picRow.add(picSaid);
		picRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(picRow);
		if (viewOnly) { picChoose.setEnabled(false); picRemove.setEnabled(false); picSaid.setText("Pictures are chosen in Gatekeeper's window on its own machine, opened from its own entry in the menu."); }
		optLine.accept("Launchers show these on this Gatekeeper's card and on each server's. Any picture file will do; it is made square and kept at 256 by 256. A server with none chosen shows the server-icon.png in its own folder if it has one, and this Gatekeeper with none shows the kind of machine it is on (below).", false);
		Runnable picLoad = () -> new Thread(() -> {
			try {
				Map<String, Object> o = Json.map(Json.parse(call("GET", "/admin/overview", null)));
				SwingUtilities.invokeLater(() -> {
					Object was = picFor.getSelectedItem();
					picFor.removeAllItems(); picIds.clear();
					picFor.addItem("This Gatekeeper"); picIds.add("gatekeeper");
					for (Object s : Json.list(o.get("servers"))) { picFor.addItem("Server: "
							+ Json.str(Json.map(s).get("name"))); picIds.add(Json.str(Json.map(s).get("id"))); }
					if (was != null) picFor.setSelectedItem(was);
				});
			} catch (Exception ignored) { Quiet.seen(ignored); }
		}, "pictures").start();
		picLoad.run();
		picFor.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { if (picIds.size() <= 1) picLoad.run(); }
			@Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
			@Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
		});
		java.util.function.Consumer<byte[]> picSend = bytes -> new Thread(() -> {
			int i = picFor.getSelectedIndex();
			String what = i >= 0 && i < picIds.size() ? picIds.get(i) : "gatekeeper", said;
			try {
				HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + "/admin/icon?for="
						+ java.net.URLEncoder.encode(what, StandardCharsets.UTF_8)).openConnection();
				c.setConnectTimeout(2000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
				c.setFixedLengthStreamingMode(bytes.length);
				try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
				try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream()
						: c.getErrorStream()) { said = Json.str(Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("message")); }
			} catch (IOException | RuntimeException e) { said = "Gatekeeper didn't answer."; }
			String show = said;
			SwingUtilities.invokeLater(() -> picSaid.setText(show));
		}, "picture").start();
		picChoose.addActionListener(ev -> {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setDialogTitle("Choose a picture");
			fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pictures (PNG, JPEG, GIF, BMP)", "png", "jpg", "jpeg", "gif", "bmp"));
			if (fc.showOpenDialog(f) != javax.swing.JFileChooser.APPROVE_OPTION) return;
			try {
				if (Files.size(fc.getSelectedFile().toPath()) > Icons.MAX) { picSaid.setText("That file is too big for a picture."); return; }
				picSend.accept(Files.readAllBytes(fc.getSelectedFile().toPath()));
			} catch (IOException e) { picSaid.setText("That file couldn't be read."); }
		});
		picRemove.addActionListener(ev -> picSend.accept(new byte[0]));
	}
}
