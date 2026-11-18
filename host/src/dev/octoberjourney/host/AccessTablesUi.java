package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import dev.gatehouse.look.Quiet;

/**
 * The Access Manager's tables in Gatekeeper's window: a permission is white (not allowed), green (allowed) or red (denied), with a menu on each; shutting out a launcher (this device) or a player (every device), and letting them back; and the buttons under the tables.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class AccessTablesUi {
	private AccessTablesUi() {}

	static void wire(
			JFrame f,
			// the two tables: launchers (rows: ids) and players (rows: pnames)
			JTable table, JTable ptable, List<String> ids, List<String> pnames,
			// the buttons under them
			JPanel buttons, JPanel pbuttons, JButton[] pB, JButton serverB, JButton mcssB, JButton sleepB, JButton adminB,
			JButton revokeB, JButton refreshB, JButton forget,
			// MCSS's row
			JButton mcssRun, boolean[] mcssUp, String[] mcssClose, JLabel mcssState, JButton signB, JButton keyB,
			// what the page does: look again, which launcher or player is chosen, its permissions, and asking Gatekeeper
			Runnable refresh, java.util.function.Supplier<String> chosen, Map<String, boolean[]> perms,
			java.util.function.BiConsumer<String, String> act) {
		// ---- the Access Manager's tables: colours, a menu on each permission, and shutting someone out
		// A permission is white (not allowed), green (allowed) or red (denied); a click on it offers the three.
		javax.swing.table.DefaultTableCellRenderer paint = new javax.swing.table.DefaultTableCellRenderer() {
			@Override public java.awt.Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
				java.awt.Component c = super.getTableCellRendererComponent(tb, v, sel, focus, row, col);
				String s = String.valueOf(v);
				boolean perm = s.startsWith("Allowed") || s.startsWith("Denied") || s.startsWith("Not allowed");
				c.setBackground(s.startsWith("Allowed") ? new Color(0xC8EFD0) : s.startsWith("Denied") ? new Color(0xF6C4C4) : perm ? Color.WHITE
						: s.startsWith("Blacklisted") || s.startsWith("Suspended") ? new Color(0xF6C4C4) : sel ? tb.getSelectionBackground() : tb.getBackground());
				c.setForeground(perm || s.startsWith("Blacklisted") || s.startsWith("Suspended") ? Color.BLACK : sel ? tb.getSelectionForeground() : tb.getForeground());
				// (Where they are right now: green in a game, blue with the launcher open, grey otherwise.)
				if (s.equals("In game")) c.setForeground(new Color(0x8BD46E));
				else if (s.equals("In the launcher")) c.setForeground(new Color(0x66C0F4));
				else if (s.equals("Offline")) c.setForeground(new Color(0x8F98A0));
				return c;
			}
		};
		table.setDefaultRenderer(Object.class, paint);
		ptable.setDefaultRenderer(Object.class, paint);
		java.util.function.BiConsumer<String, String> post = (path, body) -> new Thread(() -> {
			String res = "ok", msg = "";
			try { Map<String, Object> rr = Json.map(Json.parse(call("POST", path,
					body))); res = Json.str(rr.get("result")); msg = Json.str(rr.get("message")); if (!Json.str(rr.get("error")).isEmpty()) { res = "failed"; msg = Json.str(rr.get("error")); } }
			catch (IOException ex) { res = "failed"; msg = "Gatekeeper isn't running."; }
			String m2 = msg; boolean tell = !msg.isEmpty() && (!res.equals("ok") || path.contains("ban") || path.contains("kick"));
			SwingUtilities.invokeLater(() -> { if (tell) JOptionPane.showMessageDialog(f, "<html><div style='width:440px'>" + m2
					+ "</div></html>", "Gatekeeper", JOptionPane.PLAIN_MESSAGE); refresh.run(); });
		}, "post").start();
		String[] devPerm = {"server", "mcss", "machine", "admin"};   // the launchers table's columns 6 to 9, the players table's 4 to 7
		java.awt.event.MouseAdapter menus = new java.awt.event.MouseAdapter() {
			@Override public void mouseClicked(java.awt.event.MouseEvent ev) {
				JTable tb = (JTable) ev.getSource();
				boolean players = tb == ptable;
				int row = tb.rowAtPoint(ev.getPoint()), col = tb.columnAtPoint(ev.getPoint()), first = players ? 4 : 6;
				if (row < 0 || col < first || col > first + 3) return;
				String who = players ? (row < pnames.size() ? pnames.get(row) : null) : (row < ids.size() ? ids.get(row) : null);
				if (who == null) return;
				String perm = devPerm[col - first];
				javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
				for (String[] st : new String[][] {{"allowed", "Allowed"}, {"none", "Not allowed"}, {"denied", "Denied (stays denied whatever the player is given)"}}) {
					javax.swing.JMenuItem it = new javax.swing.JMenuItem(st[1]);
					it.addActionListener(a2 -> post.accept(players ? "/admin/player/perm" : "/admin/perm", Json.write(players
							? m("player", who, "perm", perm, "state", st[0]) : m("id", who, "perm", perm, "state", st[0]))));
					menu.add(it);
				}
				menu.show(tb, ev.getX(), ev.getY());
			}
		};
		table.addMouseListener(menus);
		ptable.addMouseListener(menus);
		// Shutting out a launcher (this device only) or a player (every device), and letting them back.
		java.util.function.BiConsumer<Boolean, String> shutOut = (players, kind) -> {
			int row = (players ? ptable : table).getSelectedRow();
			if (row < 0 || row >= (players ? pnames.size() : ids.size())) { JOptionPane.showMessageDialog(f, players
					? "Pick a player in the list first." : "Pick a launcher in the list first."); return; }
			String who = players ? pnames.get(row) : ids.get(row);
			String reason = "";
			if (!kind.isEmpty()) {
				String what = kind.equals("blacklist") ? "Blacklist" : kind.equals("suspend") ? "Suspend" : "Kick";
				String explain = kind.equals("blacklist")
						? "Blacklisted: the launcher still updates the game, but is told nothing about the machine, MCSS or the server, and can only ask to be let back."
						: kind.equals("suspend") ? "Suspended: the launcher can't start the game or use any control until it's lifted, and can only ask for that."
						: "Kicks them from the server now; they can come straight back.";
				Object why = JOptionPane.showInputDialog(f, "<html><div style='width:400px'>" + what + (players
						? " this player (on every device)? " : " this launcher (this device only)? ") + explain
						+ "<br><br>Reason (optional; they see it):</div></html>", what, JOptionPane.PLAIN_MESSAGE, null, null, "");
				if (why == null) return;
				reason = why.toString();
			}
			if (kind.equals("kick")) post.accept("/admin/kick", Json.write(m("player", who, "reason", reason)));
			else post.accept("/admin/ban", Json.write(players ? m("player", who, "kind", kind, "reason", reason) : m("id", who, "kind", kind, "reason", reason)));
		};
		JButton dSuspend = new JButton("Suspend\u2026"), dBlack = new JButton("Blacklist\u2026"), dLift = new JButton("Lift");
		dSuspend.setToolTipText("Suspends the selected launcher: it still updates the game and sees the server's status, but can't start the game or use any control. It can ask to be let back.");
		dBlack.setToolTipText("Blacklists the selected launcher: as suspended, and it is shown nothing about the machine, MCSS or the server. It can ask to be let back.");
		dSuspend.addActionListener(e -> shutOut.accept(false, "suspend"));
		dBlack.addActionListener(e -> shutOut.accept(false, "blacklist"));
		dLift.addActionListener(e -> shutOut.accept(false, ""));
		dLift.setToolTipText("Lifts a suspension or blacklisting of the selected launcher");
		for (JButton b : new JButton[] {dSuspend, dBlack, dLift}) buttons.add(b);
		JButton pKick = new JButton("Kick\u2026"), pSuspend = new JButton("Suspend\u2026"), pBlack = new JButton("Blacklist\u2026"), pLift = new JButton("Lift");
		pKick.setToolTipText("Kicks the selected player off the Minecraft server now. They can join again.");
		pSuspend.setToolTipText("Suspends the selected player on every device: kicked, taken off the whitelist until lifted, and their launchers can't start the game or use any control.");
		pBlack.setToolTipText("Blacklists the selected player on every device: as suspended, and their launchers are shown nothing about the machine, MCSS or the server.");
		String[] pTips = {"Server control for this player: every confirmed device of theirs may start and stop this Minecraft server.",
				"MCSS control for this player: every confirmed device of theirs may start and close MCSS (it includes server control).",
				"Machine wake/sleep for this player: every confirmed device of theirs may wake this machine and put it to sleep.",
						"Gatekeeper control for this player: their devices may manage the others' access from the launcher's Remote Gatekeeper tab."};
		for (int i = 0; i < pB.length; i++) pB[i].setToolTipText(pTips[i]);
		if (builtin()) { pB[1].setEnabled(false); pB[1].setToolTipText("MCSS control: " + noMcssShort() + ". Use Server control instead."); }
		if (!sleepSupported()) { pB[2].setEnabled(false); pB[2].setToolTipText("Machine wake/sleep: " + notSupported().substring(0,
				1).toLowerCase(java.util.Locale.ROOT) + notSupported().substring(1) + "."); }
		pKick.addActionListener(e -> shutOut.accept(true, "kick"));
		pSuspend.addActionListener(e -> shutOut.accept(true, "suspend"));
		pBlack.addActionListener(e -> shutOut.accept(true, "blacklist"));
		pLift.addActionListener(e -> shutOut.accept(true, ""));
		pLift.setToolTipText("Lifts a suspension or blacklisting of the selected player");
		for (JButton b : new JButton[] {pKick, pSuspend, pBlack, pLift}) pbuttons.add(b);
		// The buttons, in three sets: what the launcher (or player) may do with the server, shutting someone out, and
		// the list itself.
		revokeB.setText("Remove server accesses");
		java.util.function.BiFunction<String, JButton[], JPanel> set = (title, bs) -> {
			JPanel g = new JPanel();
			dev.gatehouse.look.WrapLayout.on(g, 6, 2);   // (a set wider than the window puts its own buttons in rows too)
			javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), title);
			tb.setTitleColor(new Color(0x8F98A0));
			g.setBorder(tb);
			for (JButton b : bs) g.add(b);
			return g;
		};
		buttons.removeAll();
		dev.gatehouse.look.WrapLayout.on(buttons, 6, 1);
		for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {sleepB, mcssB, serverB, adminB, revokeB}),
				set.apply("Administrative actions", new JButton[] {dSuspend, dBlack, dLift}), set.apply("List", new JButton[] {refreshB, forget}))) {
			buttons.add(g);
		}
		pbuttons.removeAll();
		dev.gatehouse.look.WrapLayout.on(pbuttons, 6, 1);
		for (JPanel g : List.of(set.apply("Server accesses", new JButton[] {pB[2], pB[1], pB[0], pB[3]}),
				set.apply("Administrative actions", new JButton[] {pKick, pSuspend, pBlack, pLift}))) {
			pbuttons.add(g);
		}
		mcssRun.addActionListener(e -> {
			boolean up = mcssUp[0], force = mcssClose[0].equals("force");
			if (up && JOptionPane.showConfirmDialog(f, force ? "Force close MCSS? Its X button only hides it, so it is ended instead."
					: "Close MCSS?", "MCSS", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
			mcssRun.setEnabled(false);
			new Thread(() -> {
				String msg;
				try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/mcss/close" : "/admin/mcss/start", "{\"force\":"
						+ force + "}"))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
				String m2 = msg;
				SwingUtilities.invokeLater(() -> { mcssState.setText(m2); });
				try { Thread.sleep(1500); } catch (InterruptedException ignored) { Quiet.seen(ignored); }
				refresh.run();
			}, "mcss").start();
		});
		adminB.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/perm", "{\"id\":\"" + id
				+ "\",\"perm\":\"admin\",\"value\":" + !perms.getOrDefault(id, new boolean[4])[3] + "}"); });
		signB.addActionListener(e -> {
			javax.swing.JTextField user = new javax.swing.JTextField(24);
			javax.swing.JPasswordField pass = new javax.swing.JPasswordField(24);
			JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
			form.add(new JLabel("<html><div style='width:380px'>Sign Gatekeeper in to MCSS with a panel account that can view, start and stop the server. MCSS hands back a key, which is kept on this PC; the password isn't kept.</div></html>"));
			form.add(new JLabel("Username")); form.add(user);
			form.add(new JLabel("Password")); form.add(pass);
			if (JOptionPane.showConfirmDialog(f, form, "Sign in to MCSS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			String body = Json.write(m("username", user.getText().trim(), "password", new String(pass.getPassword())));
			new Thread(() -> {
				String msg;
				try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/signin",
						body))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
				String m2 = msg;
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, m2, "Sign in to MCSS", JOptionPane.PLAIN_MESSAGE));
				refresh.run();
			}, "signin").start();
		});
		keyB.addActionListener(e -> {
			javax.swing.JPasswordField key = new javax.swing.JPasswordField(36);
			JButton paste = new JButton("Paste");
			paste.addActionListener(ev -> {
				try { Object c = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); if (c != null) key.setText(c.toString().strip()); }
				catch (Exception ignored) { Quiet.seen(ignored); }
			});
			JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); row.add(key); row.add(paste);
			JPanel form = new JPanel(new BorderLayout(0, 6));
			form.add(new JLabel("<html><div style='width:420px'>An MCSS API key for Gatekeeper (make it in MCSS's web panel, under API Keys, with permission to view, start and stop the server). It's kept in host.json on this PC only. Leave empty to clear it.</div></html>"), BorderLayout.NORTH);
			form.add(row, BorderLayout.CENTER);
			if (JOptionPane.showConfirmDialog(f, form, "Set MCSS key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			act.accept("/admin/settings", Json.write(m("mcss_api_key", new String(key.getPassword()).trim())));
		});
		forget.addActionListener(e -> { String id = chosen.get(); if (id != null && JOptionPane.showConfirmDialog(f,
				"Forget this launcher? It can ask again later.", "Forget",
				JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act.accept("/admin/forget", "{\"id\":\"" + id + "\"}"); });
		refreshB.addActionListener(e -> refresh.run());
		new Timer(3000, e -> refresh.run()).start();
		refresh.run();
	}
}
