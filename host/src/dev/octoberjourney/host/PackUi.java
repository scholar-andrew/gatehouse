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

	/** A server's pack in a window of its own: for any server, also one MC Server Soft runs (which has no Manage window). */
	static void pack(javax.swing.JFrame f, String id, String name) {
		javax.swing.JDialog d = new javax.swing.JDialog(f, name + ": pack", false);
		d.setContentPane(tab(d, "?server=" + id));
		d.pack(); d.setLocationRelativeTo(f); d.setVisible(true);
	}

	/** The Pack tab of one server. q: "?server=<id>". */
	static JPanel tab(java.awt.Component owner, String q) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		if (farUrl != null) { p.add(ServerToolsUi.note(ServerToolsUi.wrap("A server's pack is made and built at its own Gatekeeper's window, not from another Gatekeeper.", 560)), BorderLayout.NORTH); return p; }
		JLabel stands = new JLabel(" "), said = new JLabel(" "), artSaid = new JLabel(" "), preview = new JLabel();
		JButton make = new JButton("Make a pack for this server"), build = new JButton("Build again"), release = new JButton("New version…"), bring = new JButton("Bring in a pack from a folder…"), modsB = new JButton("Mods players get…");
		modsB.setToolTipText("<html>The server's own mods, each with a switch: which ones players are sent. Gatekeeper guesses; you decide.<br>A mod comes from its own page on the mod site when that exact file is published there, and from this Gatekeeper otherwise.</html>");
		String[] nextNotes = {""};
		String[][] modes = {{"private", "Private: only launchers you let in"}, {"shareable", "Shareable: those, and anyone they invite"}, {"public", "Public: every launcher that connects here"}};
		JComboBox<String> whoBox = new JComboBox<>(new String[] {modes[0][1], modes[1][1], modes[2][1]});
		whoBox.setToolTipText("<html>Who this server and its pack are for. Private unless you change it.<br>Private: this machine's launcher, launchers with control here or allowed to start this server, launchers playing as someone on its whitelist, and anyone you invite.<br>Shareable: those, and anyone one of them invites.<br>Public: every launcher that connects to this Gatekeeper.<br>An invite never puts anyone on the whitelist or gives any control.</html>");
		JButton inviteB = new JButton("Invite…"), takeBackB = new JButton("Take back invites");
		inviteB.setToolTipText("Makes an invite to send someone: in their Gatehouse they add this Gatekeeper with it, and this server is then listed for them with Install. Lasts seven days.");
		takeBackB.setToolTipText("Every invite to this server that hasn't run out stops working, and launchers that only came in by invite are no longer given it");
		JLabel invitedSaid = ServerToolsUi.note(" ");
		boolean[] filling = {false};
		make.setToolTipText("For a Fabric server: a pack with its Minecraft and Fabric versions and no mods yet. Launchers can then install it and keep it current.");
		build.setToolTipText("Builds the pack again as it is (after choosing a picture, say). Players get what changed the next time they press Play.");
		release.setToolTipText("Gives the pack its next version number with notes players read before updating, then builds it");
		bring.setToolTipText("<html>Takes a pack's definition from a folder on this machine: the folder October Journey is built from, or one laid out the same way.<br>What this server's pack was before is kept beside it until the next time.</html>");
		JComboBox<String> slotBox = new JComboBox<>(PackArt.SAYS);
		JButton choose = new JButton("Choose a picture…"), back = new JButton("Undo my choice");
		back.setToolTipText("Goes back to the picture this pack came with, or, where it came with none, to the one Gatekeeper makes");
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
			String how = Json.str(Json.map(now[0].get("art")).get(slot));
			artSaid.setText(how.equals("chosen") ? "A picture you chose. Undo my choice goes back to what was here before." : how.equals("pack") ? "The picture this pack came with." : "Made by Gatekeeper from the pack's name and the server's picture.");
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
				make.setVisible(!defined); build.setVisible(defined); release.setVisible(defined); modsB.setVisible(defined);
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
				filling[0] = true;
				for (int i = 0; i < modes.length; i++) if (modes[i][0].equals(Json.str(s.get("visibility")))) whoBox.setSelectedIndex(i);
				filling[0] = false;
				StringBuilder came = new StringBuilder();
				for (Object o : Json.list(s.get("invited"))) { Map<String, Object> c = Json.map(o); came.append(came.length() == 0 ? "" : "; ").append(Json.str(c.get("name"))).append(" (invited by ").append(Json.str(c.get("by"))).append(ServerTools.yes(c.get("counts")) ? ")" : ", not counting while this is private)"); }
				StringBuilder in = new StringBuilder();
				for (Object o : Json.list(s.get("let_in"))) { Map<String, Object> c = Json.map(o); in.append(in.length() == 0 ? "" : "; ").append(Json.str(c.get("name"))).append(" (").append(Json.str(c.get("why"))).append(")"); }
				String others = ServerToolsUi.num(s.get("others"));
				invitedSaid.setText(ServerToolsUi.wrap("Let in by you, so they have this server at every setting: " + (in.length() == 0 ? "nobody yet" : in.toString()) + ". "
						+ (others.equals("0") || others.isEmpty() ? "No other launcher is known here." : others + " other launcher(s) known here " + (Json.str(s.get("visibility")).equals("public") ? "have it because it is public." : "don't have it."))
						+ " " + (came.length() == 0 ? "Nobody has come in by invite." : "Came in by invite: " + came + ".") + " Invites that haven't run out: " + ServerToolsUi.num(s.get("invites_live")) + ".", 560));
				takeBackB.setEnabled(came.length() > 0 || !ServerToolsUi.num(s.get("invites_live")).equals("0"));
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
		whoBox.addActionListener(e -> { if (!filling[0]) post.accept("/admin/pack/visibility", m("mode", modes[Math.max(0, whoBox.getSelectedIndex())][0])); });
		takeBackB.addActionListener(e -> { if (javax.swing.JOptionPane.showConfirmDialog(p, ServerToolsUi.wrap("Take back every invite to this server? Launchers that only came in by invite are no longer given it or its pack. What they already installed stays on their device.", 420), "Take back invites", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) post.accept("/admin/pack/invites/takeback", m()); });
		inviteB.addActionListener(e -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/pack/invite" + q, m());
			SwingUtilities.invokeLater(() -> {
				if (!Json.str(r.get("result")).equals("ok")) { ServerToolsUi.say(said, r); return; }
				javax.swing.JTextField link = new javax.swing.JTextField(Json.str(r.get("link")), 44);
				link.setEditable(false);
				JButton copy = new JButton("Copy");
				copy.addActionListener(ev -> { try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(link.getText()), null); copy.setText("Copied"); } catch (RuntimeException ignored) {} });
				JPanel box = new JPanel(new BorderLayout(0, 8));
				box.add(new JLabel(ServerToolsUi.wrap("An invite to " + Json.str(r.get("server")) + ", good until " + Json.str(r.get("until")) + ". " + Json.str(r.get("message")), 480)), BorderLayout.NORTH);
				box.add(line(link, copy), BorderLayout.CENTER);
				javax.swing.JOptionPane.showMessageDialog(p, box, "Invite", javax.swing.JOptionPane.PLAIN_MESSAGE);
				load.run();
			});
		}));
		build.addActionListener(e -> post.accept("/admin/pack/build", m()));
		release.addActionListener(e -> {
			JTextArea notes = new JTextArea(nextNotes[0], 6, 46);
			notes.setLineWrap(true); notes.setWrapStyleWord(true);
			JPanel ask = new JPanel(new BorderLayout(0, 6));
			ask.add(new JLabel(ServerToolsUi.wrap("What changed, a line for each thing. Players read this before they update.", 420)), BorderLayout.NORTH);
			ask.add(new JScrollPane(notes), BorderLayout.CENTER);
			if (javax.swing.JOptionPane.showConfirmDialog(p, ask, "New version of the pack", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
			nextNotes[0] = "";
			post.accept("/admin/pack/release", m("notes", notes.getText()));
		});
		modsB.addActionListener(e -> mods(p, q, text -> { if (!text.isEmpty()) nextNotes[0] = nextNotes[0].isEmpty() ? text : nextNotes[0] + "\n" + text; load.run(); }));
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
		row(form, y++, "Who it is for", line(whoBox, inviteB, takeBackB));
		row(form, y++, "", invitedSaid);
		row(form, y++, null, stands);
		row(form, y++, null, line(make, modsB, release, build, bring));
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

	/** The list of the server's mods with a switch for each. saved: told what changed (a start for the next version's notes). */
	static void mods(java.awt.Component over, String q, java.util.function.Consumer<String> saved) {
		DefaultTableModel tm = new DefaultTableModel(new Object[] {"Send", "Mod", "Version", "MB", "Players get it from", "In the pack now", "Why"}, 0) {
			@Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
			@Override public boolean isCellEditable(int r, int c) { return c == 0 && !"hand".equals(getValueAt(r, 5) == null ? "" : rowHand.get(this).get(r)); }
		};
		java.util.List<String> keys = new java.util.ArrayList<>();
		java.util.List<String> hands = new java.util.ArrayList<>();
		rowHand.put(tm, hands);
		JTable table = new JTable(tm);
		table.setRowHeight(24);
		int[] widths = {50, 210, 90, 50, 150, 130, 420};
		for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		JLabel head = ServerToolsUi.note(" "), said = new JLabel(" ");
		JButton again = new JButton("Look again"), save = new JButton("Save the list");
		again.setToolTipText("Reads the server's mods folder again and asks the mod site afresh about every file");
		java.util.function.Consumer<Map<String, Object>> show = r -> {
			tm.setRowCount(0); keys.clear(); hands.clear();
			if (!Json.str(r.get("result")).equals("ok")) { head.setText(ServerToolsUi.wrap(Json.str(r.get("message")).isEmpty() ? "Couldn't ask Gatekeeper: is it running?" : Json.str(r.get("message")), 1000)); save.setEnabled(false); return; }
			save.setEnabled(true);
			for (Object o : Json.list(r.get("mods"))) {
				Map<String, Object> e = Json.map(o);
				String from = Json.str(e.get("from")), in = Json.str(e.get("in_pack"));
				keys.add(Json.str(e.get("key"))); hands.add(ServerTools.yes(e.get("hand")) ? "hand" : "");
				tm.addRow(new Object[] {ServerTools.yes(e.get("send")), Json.str(e.get("name")), Json.str(e.get("version")), Json.str(e.get("mb")),
						from.equals("site") ? "Its own page (" + Json.str(r.get("site")) + ")" : from.equals("gatekeeper") ? "This Gatekeeper" : from.equals("unknown") ? "Not known yet" : "",
						in.equals("yes") ? "Yes" : in.equals("older") ? "An older file of it" : in.equals("hand") ? "Yes, put there by hand" : "No", Json.str(e.get("why")) + (ServerTools.yes(e.get("chosen")) ? " (Your choice.)" : "")});
			}
			List<Object> gone = Json.list(r.get("gone"));
			String text = tm.getRowCount() == 0 ? "The server's mods folder has no mods in it." : "The server's own mods (" + tm.getRowCount() + "). Tick the ones players are sent; Gatekeeper's guess is ticked already. A mod comes from its own page when that exact file is published there, and from this Gatekeeper otherwise. Saving changes the pack's list; players get it with the pack's next version.";
			if (!ServerToolsUi.num(r.get("by_hand")).equals("0")) text += " The pack also has " + ServerToolsUi.num(r.get("by_hand")) + " mod(s) put there by hand, which this list leaves alone.";
			if (!gone.isEmpty()) { StringBuilder g = new StringBuilder(); for (Object o : gone) g.append(g.length() == 0 ? "" : ", ").append(Json.str(o)); text += " No longer on the server, and taken out of the pack when you save: " + g + "."; }
			if (!Json.str(r.get("trouble")).isEmpty()) text += " " + Json.str(r.get("trouble"));
			head.setText(ServerToolsUi.wrap(text, 1000));
		};
		java.util.function.Consumer<Boolean> load = fresh -> { head.setText("Reading the server's mods and asking the mod site…"); ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/pack/mods" + q + (fresh ? "&again=1" : ""), null); SwingUtilities.invokeLater(() -> show.accept(r)); }); };
		again.addActionListener(e -> load.accept(true));
		save.addActionListener(e -> {
			Map<String, Object> send = new java.util.LinkedHashMap<>();
			for (int i = 0; i < tm.getRowCount(); i++) if (!hands.get(i).equals("hand")) send.put(keys.get(i), Boolean.TRUE.equals(tm.getValueAt(i, 0)));
			said.setForeground(ServerToolsUi.AMBER); said.setText("Saving…");
			ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/pack/mods" + q, m("send", send)); SwingUtilities.invokeLater(() -> { ServerToolsUi.say(said, r); if (Json.str(r.get("result")).equals("ok")) { saved.accept(Json.str(r.get("notes"))); load.accept(false); } }); });
		});
		JScrollPane sp = new JScrollPane(table); sp.setPreferredSize(new java.awt.Dimension(1100, 400));
		JPanel lp = new JPanel(new BorderLayout(0, 6));
		lp.add(head, BorderLayout.NORTH); lp.add(sp, BorderLayout.CENTER);
		JPanel foot = new JPanel(new BorderLayout()); foot.add(line(save, again), BorderLayout.WEST); foot.add(said, BorderLayout.CENTER);
		lp.add(foot, BorderLayout.SOUTH);
		load.accept(false);
		if (shotTo != null) { show.accept(ServerToolsUi.ask("GET", "/admin/pack/mods" + q, null)); javax.swing.JFrame t = new javax.swing.JFrame(); t.setContentPane(lp); t.pack(); try { java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(lp.getWidth(), lp.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D g = img.createGraphics(); lp.paint(g); g.dispose(); javax.imageio.ImageIO.write(img, "png", new java.io.File(shotTo)); } catch (IOException ignored) {} t.dispose(); return; }
		javax.swing.JOptionPane.showMessageDialog(over, lp, "Mods players get", javax.swing.JOptionPane.PLAIN_MESSAGE);
	}
	private static final Map<DefaultTableModel, java.util.List<String>> rowHand = new java.util.WeakHashMap<>();
	static String shotTo;   // (testing: a picture of the list instead of the dialog)

	/** Systems: every machine this one knows of, how it stands with each, and Remember / Forget. */
	static void systems(java.awt.Component over) {
		DefaultTableModel tm = new DefaultTableModel(new Object[] {"System", "Machine", "What it has", "How it stands", "Its Gatekeeper's address", "Seen through", "Last heard from"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(tm);
		table.setRowHeight(24);
		table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		int[] wide = {170, 90, 170, 230, 160, 120, 120};
		for (int i = 0; i < wide.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(wide[i]);
		java.util.List<Map<String, Object>> shown = new java.util.ArrayList<>();
		JLabel said = new JLabel(" ");
		JButton keep = new JButton("Remember"), forget = new JButton("Forget");
		keep.setToolTipText("Keeps the chosen system in this list after the constellation is left. Only a neighbor can be remembered. It gives that system nothing.");
		forget.setToolTipText("Stops remembering the chosen system");
		Runnable pick = () -> { int i = table.getSelectedRow(); Map<String, Object> r = i >= 0 && i < shown.size() ? shown.get(i) : null; keep.setEnabled(r != null && ServerTools.yes(r.get("neighbor")) && !ServerTools.yes(r.get("remembered"))); forget.setEnabled(r != null && ServerTools.yes(r.get("remembered"))); };
		table.getSelectionModel().addListSelectionListener(e -> pick.run());
		java.util.function.Consumer<Map<String, Object>> show = r -> {
			int was = table.getSelectedRow();
			String wasId = was >= 0 && was < shown.size() ? Json.str(shown.get(was).get("id")) : "";
			tm.setRowCount(0); shown.clear();
			for (Object o : Json.list(r.get("systems"))) {
				Map<String, Object> s = Json.map(o);
				shown.add(s);
				boolean house = ServerTools.yes(s.get("gatehouse")), keeper = ServerTools.yes(s.get("gatekeeper"));
				tm.addRow(new Object[] {Json.str(s.get("name")), Json.str(s.get("device")), house && keeper ? "Gatehouse and Gatekeeper" : keeper ? "Gatekeeper" : "Gatehouse", Json.str(s.get("status")), Json.str(s.get("address")), Json.str(s.get("via")), ServerTools.yes(s.get("this")) ? "" : ServerTools.yes(s.get("open")) ? "Now" : Json.str(s.get("last_seen"))});
				if (!wasId.isEmpty() && wasId.equals(Json.str(s.get("id")))) table.setRowSelectionInterval(tm.getRowCount() - 1, tm.getRowCount() - 1);
			}
			pick.run();
		};
		Runnable load = () -> ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/systems", null); SwingUtilities.invokeLater(() -> show.accept(r)); });
		java.util.function.Consumer<Boolean> act = forgetIt -> { int i = table.getSelectedRow(); if (i < 0 || i >= shown.size()) return; String id = Json.str(shown.get(i).get("id")); ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/systems/remember", m("id", id, "forget", forgetIt)); SwingUtilities.invokeLater(() -> { ServerToolsUi.say(said, r); load.run(); }); }); };
		keep.addActionListener(e -> act.accept(false));
		forget.addActionListener(e -> act.accept(true));
		JScrollPane sp = new JScrollPane(table); sp.setPreferredSize(new java.awt.Dimension(980, 340));
		JPanel lp = new JPanel(new BorderLayout(0, 6));
		lp.add(ServerToolsUi.note(ServerToolsUi.wrap("A system is what one machine has of the program: its Gatehouse, its Gatekeeper, or both. A neighbor is in a constellation with this system now. A remembered one is kept by you, also after the constellation is left: choose a neighbor and press Remember. Each side decides for itself, and it gives the other nothing. Unknown: a launcher that introduced itself here and has been let in nowhere.", 940)), BorderLayout.NORTH);
		lp.add(sp, BorderLayout.CENTER);
		JPanel foot = new JPanel(new BorderLayout()); foot.add(line(keep, forget), BorderLayout.WEST); foot.add(said, BorderLayout.CENTER);
		lp.add(foot, BorderLayout.SOUTH);
		if (shotTo != null) { show.accept(ServerToolsUi.ask("GET", "/admin/systems", null)); javax.swing.JFrame t = new javax.swing.JFrame(); t.setContentPane(lp); t.pack(); try { java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(lp.getWidth(), lp.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB); java.awt.Graphics2D g = img.createGraphics(); lp.paint(g); g.dispose(); javax.imageio.ImageIO.write(img, "png", new java.io.File(shotTo)); } catch (IOException ignored) {} t.dispose(); return; }
		load.run();
		javax.swing.JOptionPane.showMessageDialog(over, lp, "Systems", javax.swing.JOptionPane.PLAIN_MESSAGE);
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
