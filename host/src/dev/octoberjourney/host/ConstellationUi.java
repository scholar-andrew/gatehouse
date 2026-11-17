package dev.octoberjourney.host;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

/**
 * The window's Constellation tab: this Gatekeeper and every client connected to it, each with its kind, version,
 * update mode (normal, auto or constellation) and whether it is open now. It only shows; a client's mode is chosen on
 * that client, in its own Options.
 */
final class ConstellationUi {
	static String modeWord(String m) { return m.equals("auto") ? "Auto" : m.equals("constellation") ? "Constellation" : m.equals("normal") ? "Normal" : "Not said yet"; }

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JLabel head = ServerToolsUi.note(ServerToolsUi.wrap("This Gatekeeper's constellation: itself and every client connected to it. Normal: updates when Update is pressed there. Auto: updates itself as soon as it sees a new version. Constellation: waits for the group and updates with it. Each client's mode is chosen in its own Options.", 900));
		DefaultTableModel model = new DefaultTableModel(new Object[] {"Client", "Kind", "Player", "System", "Version", "Update mode", "Now", "Last seen"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(model);
		table.setRowHeight(24);
		JLabel said = new JLabel(" ");
		said.setForeground(new Color(0x8F98A0));
		Runnable load = () -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/constellation", null);
			SwingUtilities.invokeLater(() -> {
				Map<String, Object> g = Json.map(r.get("gatekeeper"));
				if (g.isEmpty()) { said.setText("Couldn't ask Gatekeeper: is it running?"); return; }
				model.setRowCount(0);
				model.addRow(new Object[] {Json.str(g.get("name")) + "  (this Gatekeeper)", "Gatekeeper", "", Json.str(g.get("system")), Json.str(g.get("version")), modeWord(Json.str(g.get("mode"))), "Running", ""});
				int open = 0, total = 0;
				int[] by = new int[4];   // normal, auto, constellation, not said
				by[Json.str(g.get("mode")).equals("auto") ? 1 : Json.str(g.get("mode")).equals("constellation") ? 2 : 0]++;
				for (Object o : Json.list(r.get("clients"))) {
					Map<String, Object> c = Json.map(o);
					boolean up = Boolean.TRUE.equals(c.get("open"));
					String m = Json.str(c.get("mode"));
					total++; if (up) open++;
					by[m.equals("normal") ? 0 : m.equals("auto") ? 1 : m.equals("constellation") ? 2 : 3]++;
					model.addRow(new Object[] {Json.str(c.get("name")) + (Boolean.TRUE.equals(c.get("here")) ? "  (this machine)" : ""), Json.str(c.get("type")), Json.str(c.get("player")), Json.str(c.get("system")),
							Json.str(c.get("version")).isEmpty() ? "Not said" : Json.str(c.get("version")), modeWord(m), up ? "Open" : "Closed", Json.str(c.get("last_seen"))});
				}
				said.setText("<html><div style='width:900px'>" + (total + 1) + " in this constellation (" + open + " launcher" + (open == 1 ? "" : "s") + " open now).   Normal: " + by[0] + "   ·   Auto: " + by[1] + "   ·   Constellation: " + by[2] + (by[3] > 0 ? "   ·   Not said: " + by[3] : "")
						+ "<br>This Gatekeeper: " + Json.str(g.get("status")).replace("<", "&lt;") + "</div></html>");
			});
		});
		p.add(head, BorderLayout.NORTH); p.add(new JScrollPane(table), BorderLayout.CENTER); p.add(said, BorderLayout.SOUTH);
		new Timer(5000, e -> { if (p.isShowing()) load.run(); }).start();
		load.run();
		return p;
	}
}
