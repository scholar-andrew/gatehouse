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
import dev.gatehouse.look.Quiet;

/**
 * The window's Constellation tab: this Gatekeeper and every client connected to it (its launchers, the Gatekeepers
 * that have joined it, and their launchers), each with its kind, version, update mode (normal, auto or constellation)
 * and whether it is open now. From here a Gatekeeper that asked to join is allowed in or removed, and this Gatekeeper
 * joins or leaves another one's constellation. A client's update mode is chosen on that client, in its own Options.
 */
final class ConstellationUi {
	static String modeWord(String m) { return m.equals("auto") ? "Auto" : m.equals("constellation") ? "Constellation" : m.equals("normal")
			? "Normal" : "Not said yet"; }

	/** The update log: every update of every client in this constellation, newest first, in a window over p. */
	static void updateLog(java.awt.Component p) {
		ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/updates", null);
			SwingUtilities.invokeLater(() -> {
				DefaultTableModel lm = new DefaultTableModel(new Object[] {"When", "Client", "Kind", "Player", "System", "From", "To",
						"How", "Fetched from", "Through"}, 0) { @Override public boolean isCellEditable(int rr, int cc) { return false; } };
				for (Object o : Json.list(r.get("entries"))) { Map<String,
						Object> en = Json.map(o); lm.addRow(new Object[] {Json.str(en.get("when")), Json.str(en.get("client")),
						Json.str(en.get("kind")), Json.str(en.get("player")), Json.str(en.get("system"))
						+ (Json.str(en.get("info")).isEmpty() ? "" : "  (" + Json.str(en.get("info")) + ")"), Json.str(en.get("from")),
						Json.str(en.get("to")), Json.str(en.get("how")), Json.str(en.get("source")).isEmpty() ? "not said"
						: Json.str(en.get("source")), (Json.str(en.get("via")).isEmpty() && !Json.str(en.get("also")).isEmpty()
						? "(also heard through " + Json.str(en.get("also")) + ")" : Json.str(en.get("via")))}); }
				JTable lt = new JTable(lm); lt.setRowHeight(28);
				JScrollPane ls = new JScrollPane(lt); ls.setPreferredSize(new java.awt.Dimension(1100, 420));
				JPanel lp = new JPanel(new BorderLayout(0, 6));
				lp.add(ServerToolsUi.note(ServerToolsUi.wrap(lm.getRowCount() == 0
						? "No updates recorded yet. A line is added each time a client of this constellation, or this Gatekeeper, goes from one version to another." : "Newest first: " + lm.getRowCount() + " of " + ServerToolsUi.num(r.get("total")) + " recorded. Kept in updates.json in Gatekeeper's folder; through the web panel only an account given \"See the constellation and its update log\" can read it.", 1000)), BorderLayout.NORTH);
				lp.add(ls, BorderLayout.CENTER);
				javax.swing.JOptionPane.showMessageDialog(p, lp, "Update log", javax.swing.JOptionPane.PLAIN_MESSAGE);
			});
		});
	}

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JLabel head = ServerToolsUi.note(ServerToolsUi.wrap("A constellation is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that have joined it, and their launchers. Normal: updates when Update is pressed there. Auto: updates itself as soon as it sees a new version. Constellation: waits for the group and updates with it. Each client's mode is chosen in its own Options.", 820));
		DefaultTableModel model = new DefaultTableModel(new Object[] {"Client", "Kind", "Player", "System", "Version", "Update mode",
				"Now", "In this constellation"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(model);
		HostHelper.roomy(table);
		table.setRowHeight(28);
		dev.gatehouse.look.SteamLook.fitColumns(table);
		List<String[]> rowIds = new ArrayList<>();   // {its id here (a Gatekeeper that joined or asked), how it stands} per row
		JLabel said = new JLabel(" "), did = new JLabel(" "), leadSaid = new JLabel(" ");
		said.setForeground(new Color(0xA9B5C1)); leadSaid.setForeground(new Color(0xA9B5C1));
		javax.swing.JCheckBox leadUpdates = new javax.swing.JCheckBox("Share program updates with that Gatekeeper");
		leadUpdates.setToolTipText("<html>On unless you turn it off. This Gatekeeper takes a newer version from the Gatekeeper it joined as soon as that one has it,<br>and hands on one it finds first, so the constellation fetches it from outside only once.<br>That trusts the other Gatekeeper's machine to supply this Gatekeeper's program: turn it off for a machine that isn't yours.</html>");
		boolean[] leadFilling = {false};
		JButton manageLet = new JButton("Let it manage"), manageStop = new JButton("Stop managing"),
				manageOpen = new JButton("Manage it\u2026"), leadOpen = new JButton("Manage that Gatekeeper\u2026");
		manageLet.setToolTipText("<html>For a Gatekeeper that joined and was allowed in: from its own window it can then see this Gatekeeper's servers, start and stop them,<br>use a console, and answer launchers and players. Not this Gatekeeper's settings, updates, web accounts or the machine. Off unless you choose it.</html>");
		manageOpen.setToolTipText("Opens a window on the Gatekeeper you clicked, if its owner has let this one manage it");
		javax.swing.JCheckBox leadManage = new javax.swing.JCheckBox("Let that Gatekeeper manage this one");
		leadManage.setToolTipText("<html>Off unless you choose it. The Gatekeeper this one joined can then, from its own window, see this Gatekeeper's servers, start and stop them,<br>use a console, and answer launchers and players. Not this Gatekeeper's settings, updates, web accounts or the machine.</html>");
		JButton allow = new JButton("Allow in"), remove = dev.gatehouse.look.SteamLook.danger(new JButton("Remove")),
				join = new JButton("Join"), leave = dev.gatehouse.look.SteamLook.danger(new JButton("Leave"));
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
				model.addRow(new Object[] {Json.str(g.get("name")) + "  (this Gatekeeper)", "Gatekeeper", "", Json.str(g.get("system")),
						Json.str(g.get("version")), modeWord(Json.str(g.get("mode"))), "Running", "Leads it"});
				rowIds.add(new String[] {"", ""});
				int open = 0, total = 0, asked = 0, dupes = 0;
				try { dupes = (int) Double.parseDouble(Json.str(r.get("duplicates"))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
				int dupesNow = dupes;
				int[] by = new int[4];   // normal, auto, constellation, not said
				by[Json.str(g.get("mode")).equals("auto") ? 1 : Json.str(g.get("mode")).equals("constellation") ? 2 : 0]++;
				for (Object o : Json.list(r.get("clients"))) {
					Map<String, Object> c = Json.map(o);
					boolean up = Boolean.TRUE.equals(c.get("open"));
					String md = Json.str(c.get("mode")), ms = Json.str(c.get("membership")), via = Json.str(c.get("via"));
					if (ms.equals("asked")) asked++;
					else { total++; if (up) open++; by[md.equals("normal") ? 0 : md.equals("auto") ? 1 : md.equals("constellation") ? 2 : 3]++; }
					model.addRow(new Object[] {(via.isEmpty() ? "" : "    ") + Json.str(c.get("name"))
							+ (Boolean.TRUE.equals(c.get("here")) ? "  (this machine)" : ""), Json.str(c.get("type")), Json.str(c.get("player")), Json.str(c.get("system")),
							Json.str(c.get("version")).isEmpty() ? "Not said" : Json.str(c.get("version")), modeWord(md), up ? "Open" : "Closed",
							ms.equals("asked") ? "Asked to join: not allowed in yet" : ms.equals("member")
									? "Member (a Gatekeeper that joined)" + (Boolean.TRUE.equals(c.get("manage"))
									? "; may manage this Gatekeeper" : "") + (Boolean.TRUE.equals(c.get("manageable")) ? "; lets this one manage it" : "")
									: (via.isEmpty() ? "Yes" : "Yes, through " + via) + (Json.list(c.get("also")).isEmpty() ? ""
											: "; also " + Json.list(c.get("also")).toString().replaceAll("[\\[\\]]", "")
											+ (Json.str(c.get("same_by")).equals("name") ? " (same name, player and system)" : ""))});
					rowIds.add(new String[] {ms.isEmpty() ? "" : Json.str(c.get("id")), ms});
				}
				if (keep >= 0 && keep < model.getRowCount()) table.setRowSelectionInterval(keep, keep);
				said.setText("<html><div style='width:900px'>" + (total + 1) + " in this constellation (" + open + " open now" + (asked > 0
						? "; " + asked + " asking to join" : "") + ").   Normal: " + by[0] + "   ·   Auto: " + by[1]
						+ "   ·   Constellation: " + by[2] + (by[3] > 0 ? "   ·   Not said: " + by[3] : "")
						+ (dupesNow > 0 ? "<br>" + dupesNow + (dupesNow == 1 ? " client is" : " clients are")
								+ " connected more than one way (to this Gatekeeper and through one that joined): each is listed and counted once, with the other way beside it." : "")
						+ "<br>This Gatekeeper: " + Json.str(g.get("status")).replace("<", "&lt;") + "</div></html>");
				boolean joined = !Json.str(lead.get("address")).isEmpty();
				leadSaid.setText("<html><div style='width:900px'>" + (joined ? Json.str(lead.get("said")).replace("<", "&lt;")
						: "This Gatekeeper leads its own constellation. To make it part of another Gatekeeper's instead, give that Gatekeeper's address and press Join; its owner then allows it in.") + "</div></html>");
				if (!address.isFocusOwner()) address.setText(Json.str(lead.get("address")));
				address.setEditable(!joined); join.setEnabled(!joined); leave.setEnabled(joined);
				leadFilling[0] = true;
				leadManage.setEnabled(joined); leadManage.setSelected(joined && Boolean.TRUE.equals(lead.get("manage")));
				leadOpen.setEnabled(joined && Boolean.TRUE.equals(lead.get("manageable")));
				leadUpdates.setEnabled(joined); leadUpdates.setSelected(joined && Boolean.TRUE.equals(lead.get("updates")));
				String pk = Json.str(lead.get("pack"));
				leadUpdates.setText("Share program updates with that Gatekeeper" + (""));
				leadFilling[0] = false;
			});
		});
		loadRef[0] = load;
		java.util.function.BiConsumer<Object, String> post = (body, none) -> ServerToolsUi.later(() -> { Map<String,
				Object> r = ServerToolsUi.ask("POST", "/admin/constellation", body); SwingUtilities.invokeLater(() -> ServerToolsUi.say(did, r)); loadRef[0].run(); });
		java.util.function.Consumer<Boolean> pick = in -> {
			int i = table.getSelectedRow();
			if (i < 0 || i >= rowIds.size()
					|| rowIds.get(i)[0].isEmpty()) { did.setForeground(new Color(0xA9B5C1)); did.setText("Click a Gatekeeper that joined or asked to join first."); return; }
			post.accept(m("allow", rowIds.get(i)[0], "value", in), "");
		};
		allow.addActionListener(e -> pick.accept(true));
		remove.addActionListener(e -> pick.accept(false));
		join.addActionListener(e -> post.accept(m("join", address.getText().trim()), ""));
		leadManage.addActionListener(e -> { if (!leadFilling[0]) post.accept(m("lead_manage", leadManage.isSelected()), ""); });
		java.util.function.Consumer<Object> open = body -> ServerToolsUi.later(() -> { Map<String, Object> r = ServerToolsUi.ask("POST",
				"/admin/manage/open", body); SwingUtilities.invokeLater(() -> ServerToolsUi.say(did, r)); });
		leadOpen.addActionListener(e -> open.accept(m("lead", true)));
		java.util.function.BiConsumer<String, Boolean> onRow = (what, value) -> {
			int i = table.getSelectedRow();
			if (i < 0 || i >= rowIds.size()
					|| rowIds.get(i)[0].isEmpty()) { did.setForeground(new Color(0xA9B5C1)); did.setText("Click a Gatekeeper that joined first."); return; }
			if (what.equals("open")) open.accept(m("id", rowIds.get(i)[0])); else post.accept(m("manage", rowIds.get(i)[0], "value", value), "");
		};
		manageLet.addActionListener(e -> onRow.accept("manage", true));
		manageStop.addActionListener(e -> onRow.accept("manage", false));
		manageOpen.addActionListener(e -> onRow.accept("open", true));
		leadUpdates.addActionListener(e -> { if (!leadFilling[0]) post.accept(m("lead_updates", leadUpdates.isSelected()), ""); });
		leave.addActionListener(e -> post.accept(m("leave", true), ""));
		// The update log: who went from which version to which, when and how, for the whole constellation.
		JButton logB = new JButton("Update log\u2026");
		logB.setToolTipText("Every update of every client in this constellation: when, from which version to which, how, and its system");
		logB.addActionListener(e -> updateLog(p));
		JPanel buttons = new JPanel(new dev.gatehouse.look.WrapLayout(FlowLayout.LEFT, 6, 2));
		buttons.add(allow);
		buttons.add(remove);
		buttons.add(manageLet);
		buttons.add(manageStop);
		buttons.add(manageOpen);
		buttons.add(logB);
		// (History of servers and Systems are in the menu beside Constellation, so they are no longer buttons here too:
		// R38. That also keeps this row on one line at the Steam Deck's size.)
		JPanel joinRow = new JPanel(new dev.gatehouse.look.WrapLayout(FlowLayout.LEFT, 6, 2));
		joinRow.add(new JLabel("Join another Gatekeeper's constellation:"));
		joinRow.add(address);
		joinRow.add(join);
		joinRow.add(leave);
		joinRow.add(leadUpdates);
		joinRow.add(leadManage);
		joinRow.add(leadOpen);
		JPanel bottom = new JPanel();
		bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
		dev.gatehouse.look.Pips pips = new dev.gatehouse.look.Pips();
		new Timer(4000, e -> { if (p.isShowing()) ServerToolsUi.later(() -> { Object gg = ServerToolsUi.ask("GET", "/admin/auto",
				null).get("gather"); SwingUtilities.invokeLater(() -> pips.set(gg)); }); }).start();
		for (javax.swing.JComponent c : new javax.swing.JComponent[] {buttons, said, pips, joinRow, leadSaid,
				did}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); bottom.add(c); }
		p.add(head, BorderLayout.NORTH); JScrollPane tableScroll = new JScrollPane(table); tableScroll.setPreferredSize(new java.awt.Dimension(400, 230));   // (R39: so the buttons under it fit on a Steam Deck)
		p.add(tableScroll, BorderLayout.CENTER); p.add(bottom, BorderLayout.SOUTH);
		new Timer(5000, e -> { if (p.isShowing()) load.run(); }).start();
		load.run();
		return p;
	}
}
