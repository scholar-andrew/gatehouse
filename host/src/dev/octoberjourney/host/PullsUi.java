package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
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
 * A server's Updates Pulled page in Gatekeeper's window: each update a launcher fetched from this machine (who, when, what, how big), with totals in tiles along the top. It asks Gatekeeper every five seconds while it is showing.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class PullsUi {
	private PullsUi() {}

	static JPanel tab() {
		// Updates Pulled: each update a launcher fetched from this machine: who, when, what, and how big.
		DefaultTableModel pullModel = new DefaultTableModel(new String[] {"When", "Launcher", "Player", "System", "Update", "Files",
				"Mods", "Size", "From this machine"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable pullTable = new JTable(pullModel);
		roomy(pullTable);
		pullTable.setRowHeight(30);
		pullTable.getColumnModel().getColumn(0).setPreferredWidth(150); pullTable.getColumnModel().getColumn(4).setPreferredWidth(220);
		javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
		right.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
		for (int ci = 5; ci <= 8; ci++) pullTable.getColumnModel().getColumn(ci).setCellRenderer(right);
		JLabel[] pullTiles = {new JLabel("0"), new JLabel("0 MB"), new JLabel("0 MB"), new JLabel("\u2013")};
		String[] pullTitles = {"Downloads recorded", "Total size", "Last 7 days", "Latest"};
		JPanel tiles = new JPanel(new java.awt.GridLayout(1, 4, 10, 0));
		for (int ti = 0; ti < 4; ti++) {
			JPanel tile = new JPanel(new BorderLayout(0, 2));
			tile.setBackground(new Color(0x141D29));
			tile.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
			JLabel cap = new JLabel(pullTitles[ti]); cap.setForeground(new Color(0xA9B5C1));
			pullTiles[ti].setFont(pullTiles[ti].getFont().deriveFont(Font.BOLD, dev.gatehouse.look.SteamLook.pt(ti == 3 ? 15f : 22f)));
			tile.add(cap, BorderLayout.NORTH); tile.add(pullTiles[ti], BorderLayout.CENTER);
			tiles.add(tile);
		}
		JLabel pullNote = new JLabel("<html><div style='width:900px'>Each time a launcher fetches an update from this machine (not from GitHub), it says so here. Size is the whole download for that update; the part that came from this machine is in the last column (mods the pack only lists come from Modrinth).</div></html>");
		pullNote.setForeground(new Color(0xA9B5C1));
		JPanel pullsTab = new JPanel(new BorderLayout(0, 10));
		pullsTab.setBorder(BorderFactory.createEmptyBorder(10, 6, 6, 6));
		pullsTab.add(tiles, BorderLayout.NORTH); pullsTab.add(new JScrollPane(pullTable), BorderLayout.CENTER); pullsTab.add(pullNote, BorderLayout.SOUTH);
		java.util.function.Function<Long, String> mb = bytes -> bytes < 1_000_000 ? Math.max(bytes == 0 ? 0 : 1,
				Math.round(bytes / 1000.0)) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6);
		java.util.function.Consumer<List<Object>> fillPulls = all -> {
					pullModel.setRowCount(0);
					long total = 0, week = 0;
					String cutoff = LocalDateTime.now().minusDays(7).format(TIME);
					for (Object o : all) {
						Map<String, Object> r = Json.map(o);
						long bytes = 0, local = 0;
						try { bytes = (long) Double.parseDouble(Json.str(r.get("bytes"))); local = (long) Double.parseDouble(Json.str(r.get("local_bytes"))); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
						total += bytes; if (Json.str(r.get("time")).compareTo(cutoff) >= 0) week += bytes;
						String upd = (Json.str(r.get("pack")).isEmpty() ? "" : Json.str(r.get("pack")) + " ")
								+ (Json.str(r.get("from")).isEmpty() ? "first install, " : Json.str(r.get("from")) + " \u2192 ") + Json.str(r.get("to"));
						pullModel.addRow(new Object[] {r.get("time"), r.get("name"), r.get("player"), r.get("os"), upd,
								Json.str(r.get("files")).replaceAll("\\.0$", ""), Json.str(r.get("mods")).replaceAll("\\.0$", ""), mb.apply(bytes), mb.apply(local)});
					}
					pullTiles[0].setText(String.valueOf(all.size())); pullTiles[1].setText(mb.apply(total)); pullTiles[2].setText(mb.apply(week));
					pullTiles[3].setText(all.isEmpty() ? "\u2013" : Json.str(Json.map(all.get(0)).get("name")) + ", " + Json.str(Json.map(all.get(0)).get("time")));
		};
		Runnable loadPulls = () -> new Thread(() -> {
			try {
				List<Object> all = Json.list(Json.map(Json.parse(call("GET", "/admin/pulls", null))).get("pulls"));
				SwingUtilities.invokeLater(() -> fillPulls.accept(all));
			} catch (Exception ignored) { Quiet.seen(ignored); }
		}, "pulls").start();
		new Timer(5000, e -> { if (pullsTab.isShowing()) loadPulls.run(); }).start();
		pullsTab.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
				&& pullsTab.isShowing()) loadPulls.run(); });
		pullsTab.putClientProperty("fill", fillPulls);
		return pullsTab;
	}

	/** Fills the page with these pulls at once (the window's test pictures use it). */
	@SuppressWarnings("unchecked")
	static void fill(JPanel pullsTab, List<Object> all) {
		if (pullsTab.getClientProperty("fill") instanceof java.util.function.Consumer<?> c) ((java.util.function.Consumer<List<Object>>) c).accept(all);
	}
}
