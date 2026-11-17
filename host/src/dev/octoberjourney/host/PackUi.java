package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * The window's side of Packs and History: a server's "Pack" tab in Manage (making, building and bringing in its pack,
 * and the pictures Steam shows for it), and the history of servers on the Constellation tab. It only asks the running
 * Gatekeeper; it changes nothing itself.
 */
final class PackUi {
	private PackUi() {}
	private static JPanel line(JComponent... parts) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); for (JComponent c : parts) p.add(c); return p; }
	private static void row(JPanel form, int y, String label, JComponent field) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = y; c.insets = new Insets(4, 4, 4, 8); c.anchor = GridBagConstraints.NORTHWEST;
		if (label != null) { c.gridx = 0; form.add(new JLabel(label), c); }
		c.gridx = label == null ? 0 : 1; c.gridwidth = label == null ? 2 : 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
		form.add(field, c);
	}
	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

	/** A picture's bytes to or from the Gatekeeper on this machine (the pictures are files, so not sent as text). */
	private static byte[] artCall(String q, String slot, byte[] send) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + "/admin/pack/art" + q + "&slot=" + slot).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(20000);
		if (send != null) { c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(send.length); try (OutputStream o = c.getOutputStream()) { o.write(send); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? new byte[0] : in.readAllBytes(); }
	}

	/** The Pack tab of one server. q: "?server=<id>". */
	static JPanel tab(java.awt.Component owner, String q) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		if (farUrl != null) { p.add(ServerToolsUi.note(ServerToolsUi.wrap("A server's pack is made and built at its own Gatekeeper's window, not from another Gatekeeper.", 560)), BorderLayout.NORTH); return p; }
		JLabel stands = new JLabel(" "), said = new JLabel(" "), artSaid = new JLabel(" "), preview = new JLabel();
		JButton make = new JButton("Make a pack for this server"), build = new JButton("Build again"), release = new JButton("New version…"), bring = new JButton("Bring in a pack from a folder…");
		make.setToolTipText("For a Fabric server: a pack with its Minecraft and Fabric versions and no mods yet. Launchers can then install it and keep it current.");
		build.setToolTipText("Builds the pack again as it is (after choosing a picture, say). Players get what changed the next time they press Play.");
		release.setToolTipText("Gives the pack its next version number with notes players read before updating, then builds it");
		bring.setToolTipText("<html>Takes a pack's definition from a folder on this machine: the folder October Journey is built from, or one laid out the same way.<br>What this server's pack was before is kept beside it until the next time.</html>");
		JComboBox<String> slotBox = new JComboBox<>(PackArt.SAYS);
		JButton choose = new JButton("Choose a picture…"), back = new JButton("Use the one Gatekeeper makes");
		preview.setBorder(BorderFactory.createLineBorder(ServerToolsUi.GREY));
		preview.setPreferredSize(new java.awt.Dimension(300, 170));
		preview.setHorizontalAlignment(JLabel.CENTER);
		Map<String, Object>[] now = new Map[] {m()};
		boolean[] opening = {true};
		Runnable showArt = () -> {
			int i = Math.max(0, slotBox.getSelectedIndex());
			String slot = PackArt.SLOTS[i];
			boolean defined = ServerTools.yes(now[0].get("defined"));
			choose.setEnabled(defined); back.setEnabled(defined && "chosen".equals(Json.str(Json.map(now[0].get("art")).get(slot))));
			if (!defined) { preview.setIcon(null); artSaid.setText(" "); return; }
			artSaid.setForeground(ServerToolsUi.GREY);
			artSaid.setText("chosen".equals(Json.str(Json.map(now[0].get("art")).get(slot))) ? "A picture you chose." : "Made by Gatekeeper from the pack's name and the server's picture.");
			Runnable fetch = () -> {
				try {
					java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(artCall(q, slot, null)));
					if (img == null) return;
					double k = Math.min(296.0 / img.getWidth(), 166.0 / img.getHeight());
					java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(Math.max(1, (int) (img.getWidth() * k)), Math.max(1, (int) (img.getHeight() * k)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
					java.awt.Graphics2D g = small.createGraphics();
					g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					g.drawImage(img, 0, 0, small.getWidth(), small.getHeight(), null); g.dispose();
					Runnable put = () -> { if (slotBox.getSelectedIndex() == i) preview.setIcon(new javax.swing.ImageIcon(small)); };
					if (SwingUtilities.isEventDispatchThread()) put.run(); else SwingUtilities.invokeLater(put);
				} catch (IOException | RuntimeException ignored) {}
			};
			if (opening[0]) fetch.run(); else ServerToolsUi.later(fetch);
		};
		java.util.function.Consumer<Map<String, Object>> show = s -> {
			{
				now[0] = s;
				boolean defined = ServerTools.yes(s.get("defined"));
				make.setVisible(!defined); build.setVisible(defined); release.setVisible(defined);
				make.setEnabled(!Json.str(s.get("loader")).isEmpty());
				String text;
				if (Json.str(s.get("server")).isEmpty()) text = Json.str(s.get("message")).isEmpty() ? "Couldn't ask Gatekeeper: is it running?" : esc(Json.str(s.get("message")));
				else if (!defined) text = esc(Json.str(s.get("message")));
				else {
					String n = ServerToolsUi.num(s.get("mods")), dk = ServerToolsUi.num(s.get("deck_mods")), own = ServerToolsUi.num(s.get("own_mods")), sw = ServerToolsUi.num(s.get("switchable"));
					text = "<b>" + esc(Json.str(s.get("pack_name"))) + " " + esc(Json.str(s.get("version"))) + "</b>: " + n + " mod(s)" + (n.equals("0") ? "" : " (" + dk + " only for the Steam Deck, " + own + " that are files of this pack's own, " + sw + " players can switch off)")
							+ ". Minecraft " + esc(Json.str(s.get("pack_minecraft"))) + ", Fabric loader " + esc(Json.str(s.get("pack_loader"))) + ".<br>"
							+ (ServerTools.yes(s.get("built")) ? "Built " + esc(Json.str(s.get("built_at"))) + " as " + esc(Json.str(s.get("built_version"))) + ", with Gatehouse " + esc(Json.str(s.get("built_with"))) + ". Launchers fetch it from this Gatekeeper: " + esc(Json.str(s.get("address"))) + "."
									: "Not built yet: launchers can't fetch it.")
							+ (Json.str(s.get("message")).isEmpty() ? "" : "<br>" + esc(Json.str(s.get("message"))));
					StringBuilder notes = new StringBuilder();
					for (Object o : Json.list(s.get("notes"))) notes.append("<br>&nbsp;&nbsp;- ").append(esc(Json.str(o)));
					if (notes.length() > 0) text += "<br>This version's notes:" + notes;
				}
				stands.setText("<html><div style='width:560px'>" + text + "</div></html>");
				p.revalidate();
				showArt.run();
			}
		};
		Runnable load = () -> ServerToolsUi.later(() -> { Map<String, Object> s = ServerToolsUi.ask("GET", "/admin/pack" + q, null); SwingUtilities.invokeLater(() -> show.accept(s)); });
		slotBox.addActionListener(e -> showArt.run());
		java.util.function.BiConsumer<String, Object> post = (path, body) -> ServerToolsUi.later(() -> {
			SwingUtilities.invokeLater(() -> { said.setForeground(ServerToolsUi.AMBER); said.setText("Working…"); });
			Map<String, Object> r = ServerToolsUi.ask("POST", path + q, body);
			SwingUtilities.invokeLater(() -> { ServerToolsUi.say(said, r); load.run(); });
		});
		make.addActionListener(e -> post.accept("/admin/pack/start", m()));
		build.addActionListener(e -> post.accept("/admin/pack/build", m()));
		release.addActionListener(e -> {
			JTextArea notes = new JTextArea(6, 46);
			notes.setLineWrap(true); notes.setWrapStyleWord(true);
			JPanel ask = new JPanel(new BorderLayout(0, 6));
			ask.add(new JLabel(ServerToolsUi.wrap("What changed, a line for each thing. Players read this before they update.", 420)), BorderLayout.NORTH);
			ask.add(new JScrollPane(notes), BorderLayout.CENTER);
			if (javax.swing.JOptionPane.showConfirmDialog(p, ask, "New version of the pack", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
			post.accept("/admin/pack/release", m("notes", notes.getText()));
		});
		bring.addActionListener(e -> {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setDialogTitle("The folder the pack is defined in");
			fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
			if (fc.showOpenDialog(p) != javax.swing.JFileChooser.APPROVE_OPTION) return;
			if (ServerTools.yes(now[0].get("defined")) && javax.swing.JOptionPane.showConfirmDialog(p, ServerToolsUi.wrap("This server already has a pack. Bringing one in replaces it (the one it has now is kept beside it until the next time), and players get the new one the next time they press Play. Go on?", 420),
					"Bring in a pack", javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) return;
			post.accept("/admin/pack/import", m("folder", fc.getSelectedFile().getAbsolutePath()));
		});
		java.util.function.Consumer<byte[]> sendArt = bytes -> ServerToolsUi.later(() -> {
			String slot = PackArt.SLOTS[Math.max(0, slotBox.getSelectedIndex())];
			Map<String, Object> r;
			try { r = Json.map(Json.parse(new String(artCall(q, slot, bytes), StandardCharsets.UTF_8))); } catch (IOException | RuntimeException ex) { r = m("result", "failed", "message", "Gatekeeper didn't answer."); }
			Map<String, Object> rr = r;
			SwingUtilities.invokeLater(() -> { ServerToolsUi.say(said, rr); load.run(); });
		});
		choose.addActionListener(e -> {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setDialogTitle("Choose a picture");
			fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Pictures (PNG, JPEG, GIF, BMP)", "png", "jpg", "jpeg", "gif", "bmp"));
			if (fc.showOpenDialog(p) != javax.swing.JFileChooser.APPROVE_OPTION) return;
			try {
				if (Files.size(fc.getSelectedFile().toPath()) > Packs.MAX_ART) { said.setForeground(ServerToolsUi.RED); said.setText("That file is too big for a picture."); return; }
				sendArt.accept(Files.readAllBytes(fc.getSelectedFile().toPath()));
			} catch (IOException ex) { said.setForeground(ServerToolsUi.RED); said.setText("That file couldn't be read."); }
		});
		back.addActionListener(e -> sendArt.accept(new byte[0]));
		JPanel form = new JPanel(new GridBagLayout());
		int y = 0;
		row(form, y++, null, ServerToolsUi.note(ServerToolsUi.wrap("A pack is what players' launchers install and keep current for this server: its mods, their settings, and the pictures Steam shows for its shortcut. Gatekeeper builds it and hands it to launchers itself.", 560)));
		row(form, y++, null, stands);
		row(form, y++, null, line(make, build, release, bring));
		row(form, y++, null, said);
		row(form, y++, null, ServerToolsUi.note(ServerToolsUi.wrap("Pictures for Steam. Each is one you choose (brought to the right size here) or one Gatekeeper makes. A change goes out with the next build.", 560)));
		row(form, y++, "Picture", line(slotBox, choose, back));
		row(form, y++, "", preview);
		row(form, y++, "", artSaid);
		p.add(form, BorderLayout.NORTH);
		show.accept(ServerToolsUi.ask("GET", "/admin/pack" + q, null));
		opening[0] = false;
		return p;
	}

	static String whatSays(String what) {
		return switch (what) { case "made" -> "Made"; case "found" -> "Looked after from here on"; case "pack" -> "Pack built"; case "removed" -> "No longer looked after"; default -> what; };
	}
	/** The history of servers, as a window over the Constellation tab. */
	static void history(java.awt.Component over) {
		ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/history", null);
			SwingUtilities.invokeLater(() -> {
				DefaultTableModel lm = new DefaultTableModel(new Object[] {"When", "What", "Server", "Kind", "Minecraft", "Pack", "Gatekeeper", "Its address", "System"}, 0) { @Override public boolean isCellEditable(int rr, int cc) { return false; } };
				for (Object o : Json.list(r.get("entries"))) {
					Map<String, Object> e = Json.map(o);
					lm.addRow(new Object[] {Json.str(e.get("when")), whatSays(Json.str(e.get("what"))), Json.str(e.get("name")), Json.str(e.get("kind")), Json.str(e.get("version")), Json.str(e.get("pack")), Json.str(e.get("gatekeeper")), Json.str(e.get("address")), Json.str(e.get("system"))});
				}
				List<Object> keepers = Json.list(r.get("gatekeepers"));
				StringBuilder who = new StringBuilder();
				for (Object o : keepers) { Map<String, Object> k = Json.map(o); who.append(who.length() == 0 ? "" : "; ").append(Json.str(k.get("name"))).append(ServerTools.yes(k.get("this")) ? " (this one)" : Json.str(k.get("address")).isEmpty() ? "" : " at " + Json.str(k.get("address"))).append(", ").append(ServerToolsUi.num(k.get("servers"))).append(" server(s), last ").append(Json.str(k.get("last"))); }
				JTable lt = new JTable(lm); lt.setRowHeight(24);
				JScrollPane ls = new JScrollPane(lt); ls.setPreferredSize(new java.awt.Dimension(1000, 380));
				JPanel lp = new JPanel(new BorderLayout(0, 6));
				String head = lm.getRowCount() == 0 ? "Nothing yet. A line is added when a Gatekeeper makes a server, starts or stops looking after one, or builds a pack's new version."
						: "Newest first. Every server a Gatekeeper of this constellation has made or looked after, kept even after the server is gone. Gatekeepers in it: " + who + ". "
								+ (ServerTools.yes(r.get("shared")) ? "Shared with the Gatekeeper this one joined, both ways." : "A Gatekeeper that joins this one is handed all of it, and adds its own.");
				lp.add(ServerToolsUi.note(ServerToolsUi.wrap(head, 960)), BorderLayout.NORTH);
				lp.add(ls, BorderLayout.CENTER);
				javax.swing.JOptionPane.showMessageDialog(over, lp, "History of servers", javax.swing.JOptionPane.PLAIN_MESSAGE);
			});
		});
	}
}
