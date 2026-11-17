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
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/**
 * The window's Constellation tab: this Gatekeeper and every client connected to it (its launchers, the Gatekeepers
 * that have joined it, and their launchers), each with its kind, version, update mode (normal, auto or constellation)
 * and whether it is open now. From here a Gatekeeper that asked to join is allowed in or removed, and this Gatekeeper
 * joins or leaves another one's constellation. A client's update mode is chosen on that client, in its own Options.
 */
final class ConstellationUi {
	static String modeWord(String m) { return m.equals("auto") ? "Auto" : m.equals("constellation") ? "Constellation" : m.equals("normal") ? "Normal" : "Not said yet"; }

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JLabel head = ServerToolsUi.note(ServerToolsUi.wrap("A constellation is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that have joined it, and their launchers. Normal: updates when Update is pressed there. Auto: updates itself as soon as it sees a new version. Constellation: waits for the group and updates with it. Each client's mode is chosen in its own Options.", 900));
		DefaultTableModel model = new DefaultTableModel(new Object[] {"Client", "Kind", "Player", "System", "Version", "Update mode", "Now", "In this constellation"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(model);
		table.setRowHeight(24);
		List<String[]> rowIds = new ArrayList<>();   // {its id here (a Gatekeeper that joined or asked), how it stands} per row
		JLabel said = new JLabel(" "), did = new JLabel(" "), leadSaid = new JLabel(" ");
		said.setForeground(new Color(0x8F98A0)); leadSaid.setForeground(new Color(0x8F98A0));
		JButton allow = new JButton("Allow into this constellation"), remove = new JButton("Remove from this constellation"), join = new JButton("Join"), leave = new JButton("Leave");
		allow.setToolTipText("For a Gatekeeper that has asked to join: in constellation mode it then updates on this Gatekeeper's go, and its launchers are listed here. It gets no control of anything.");
		JTextField address = new JTextField(22);
		address.setToolTipText("The other Gatekeeper's address (100.x.y.z:25570), or its pairing link");
		Runnable[] loadRef = {null};
		Runnable load = () -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/constellation", null);
			SwingUtilities.invokeLater(() -> {
				Map<String, Object> g = Json.map(r.get("gatekeeper")), lead = Json.map(r.get("lead"));
				if (g.isEmpty()) { said.setText("Couldn't ask Gatekeeper: is it running?"); return; }
				int keep = table.getSelectedRow();
				model.setRowCount(0); rowIds.clear();
				model.addRow(new Object[] {Json.str(g.get("name")) + "  (this Gatekeeper)", "Gatekeeper", "", Json.str(g.get("system")), Json.str(g.get("version")), modeWord(Json.str(g.get("mode"))), "Running", "Leads it"});
				rowIds.add(new String[] {"", ""});
				int open = 0, total = 0, asked = 0, dupes = 0;
				try { dupes = (int) Double.parseDouble(Json.str(r.get("duplicates"))); } catch (NumberFormatException ignored) {}
				int dupesNow = dupes;
				int[] by = new int[4];   // normal, auto, constellation, not said
				by[Json.str(g.get("mode")).equals("auto") ? 1 : Json.str(g.get("mode")).equals("constellation") ? 2 : 0]++;
				for (Object o : Json.list(r.get("clients"))) {
					Map<String, Object> c = Json.map(o);
					boolean up = Boolean.TRUE.equals(c.get("open"));
					String md = Json.str(c.get("mode")), ms = Json.str(c.get("membership")), via = Json.str(c.get("via"));
					if (ms.equals("asked")) asked++; else { total++; if (up) open++; by[md.equals("normal") ? 0 : md.equals("auto") ? 1 : md.equals("constellation") ? 2 : 3]++; }
					model.addRow(new Object[] {(via.isEmpty() ? "" : "    ") + Json.str(c.get("name")) + (Boolean.TRUE.equals(c.get("here")) ? "  (this machine)" : ""), Json.str(c.get("type")), Json.str(c.get("player")), Json.str(c.get("system")),
							Json.str(c.get("version")).isEmpty() ? "Not said" : Json.str(c.get("version")), modeWord(md), up ? "Open" : "Closed",
							ms.equals("asked") ? "Asked to join: not allowed in yet" : ms.equals("member") ? "Member (a Gatekeeper that joined)"
									: (via.isEmpty() ? "Yes" : "Yes, through " + via) + (Json.list(c.get("also")).isEmpty() ? "" : "; also " + Json.list(c.get("also")).toString().replaceAll("[\\[\\]]", "") + (Json.str(c.get("same_by")).equals("name") ? " (same name, player and system)" : ""))});
					rowIds.add(new String[] {ms.isEmpty() ? "" : Json.str(c.get("id")), ms});
				}
				if (keep >= 0 && keep < model.getRowCount()) table.setRowSelectionInterval(keep, keep);
				said.setText("<html><div style='width:900px'>" + (total + 1) + " in this constellation (" + open + " open now" + (asked > 0 ? "; " + asked + " asking to join" : "") + ").   Normal: " + by[0] + "   ·   Auto: " + by[1] + "   ·   Constellation: " + by[2] + (by[3] > 0 ? "   ·   Not said: " + by[3] : "")
						+ (dupesNow > 0 ? "<br>" + dupesNow + (dupesNow == 1 ? " client is" : " clients are") + " connected more than one way (to this Gatekeeper and through one that joined): each is listed and counted once, with the other way beside it." : "")
						+ "<br>This Gatekeeper: " + Json.str(g.get("status")).replace("<", "&lt;") + "</div></html>");
				boolean joined = !Json.str(lead.get("address")).isEmpty();
				leadSaid.setText("<html><div style='width:900px'>" + (joined ? Json.str(lead.get("said")).replace("<", "&lt;") : "This Gatekeeper leads its own constellation. To make it part of another Gatekeeper's instead, give that Gatekeeper's address and press Join; its owner then allows it in.") + "</div></html>");
				if (!address.isFocusOwner()) address.setText(Json.str(lead.get("address")));
				address.setEditable(!joined); join.setEnabled(!joined); leave.setEnabled(joined);
			});
		});
		loadRef[0] = load;
		java.util.function.BiConsumer<Object, String> post = (body, none) -> ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/constellation", body); SwingUtilities.invokeLater(() -> ServerToolsUi.say(did, r)); loadRef[0].run(); });
		java.util.function.Consumer<Boolean> pick = in -> {
			int i = table.getSelectedRow();
			if (i < 0 || i >= rowIds.size() || rowIds.get(i)[0].isEmpty()) { did.setForeground(new Color(0x8F98A0)); did.setText("Click a Gatekeeper that joined or asked to join first."); return; }
			post.accept(m("allow", rowIds.get(i)[0], "value", in), "");
		};
		allow.addActionListener(e -> pick.accept(true));
		remove.addActionListener(e -> pick.accept(false));
		join.addActionListener(e -> post.accept(m("join", address.getText().trim()), ""));
		leave.addActionListener(e -> post.accept(m("leave", true), ""));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); buttons.add(allow); buttons.add(remove);
		JPanel joinRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); joinRow.add(new JLabel("Join another Gatekeeper's constellation:")); joinRow.add(address); joinRow.add(join); joinRow.add(leave);
		JPanel bottom = new JPanel();
		bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
		for (javax.swing.JComponent c : new javax.swing.JComponent[] {buttons, said, joinRow, leadSaid, did}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); bottom.add(c); }
		p.add(head, BorderLayout.NORTH); p.add(new JScrollPane(table), BorderLayout.CENTER); p.add(bottom, BorderLayout.SOUTH);
		new Timer(5000, e -> { if (p.isShowing()) load.run(); }).start();
		load.run();
		return p;
	}
}
