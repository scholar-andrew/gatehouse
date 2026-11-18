package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.m;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * The window's "Web panel" tab: turning the web panel on, its accounts (made, changed and removed here and nowhere
 * else; the owner types each password themselves), and the devices it can wake.
 */
final class PanelUi {
	private static JPanel line(JComponent... parts) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2)); for (JComponent c
			: parts) p.add(c); p.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); return p; }
	private static JLabel head(String text) { JLabel l = new JLabel(text); l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD,
			14f)); l.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0)); l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); return l; }
	private static JLabel note(String text) { JLabel l = ServerToolsUi.note(ServerToolsUi.wrap(text,
			760)); l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); return l; }

	static JPanel tab(JFrame f) {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JCheckBox onF = new JCheckBox("The web panel is on");
		JTextField addrF = new JTextField(34); addrF.setEditable(false);
		JButton copy = new JButton("Copy");
		JLabel said = new JLabel(" "); said.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		DefaultTableModel um = new DefaultTableModel(new Object[] {"Account", "Admin", "On", "May", "Last signed in", "Signed in now"},
				0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable ut = new JTable(um);
		HostHelper.roomy(ut);
		DefaultTableModel wm = new DefaultTableModel(new Object[] {"Device", "MAC address"},
				0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable wt = new JTable(wm);
		List<Map<String, Object>> users = new ArrayList<>();
		List<Object[]> perms = new ArrayList<>();   // {id, what it is called}
		boolean[] filling = {false};
		Runnable load = () -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/panel", null);
			SwingUtilities.invokeLater(() -> {
				if (!r.containsKey("users")) { said.setForeground(ServerToolsUi.RED); said.setText("Couldn't ask Gatekeeper: is it running?"); return; }
				filling[0] = true; onF.setSelected(Boolean.TRUE.equals(r.get("on"))); filling[0] = false;
				addrF.setText(Json.str(r.get("address")).isEmpty() ? "(needs this machine's Tailscale address: Server setup)" : Json.str(r.get("address")));
				perms.clear(); for (Object o : Json.list(r.get("perms"))) perms.add(new Object[] {Json.str(Json.map(o).get("id")), Json.str(Json.map(o).get("name"))});
				users.clear(); um.setRowCount(0);
				for (Object o : Json.list(r.get("users"))) {
					Map<String, Object> u = Json.map(o); users.add(u);
					List<String> may = new ArrayList<>(); for (Object q : Json.list(u.get("perms"))) may.add(Json.str(q));
					um.addRow(new Object[] {Json.str(u.get("name")), Boolean.TRUE.equals(u.get("admin")) ? "Yes" : "",
							Boolean.TRUE.equals(u.get("enabled")) ? "Yes" : "No", Boolean.TRUE.equals(u.get("admin")) ? "everything"
							: String.join(", ", may), Json.str(u.get("last_in")), ServerToolsUi.num(u.get("signed_in")).equals("0") ? "" : ServerToolsUi.num(u.get("signed_in"))});
				}
				wm.setRowCount(0);
				for (Object o : Json.list(r.get("wake_devices"))) wm.addRow(new Object[] {Json.str(Json.map(o).get("name")), Json.str(Json.map(o).get("mac"))});
			});
		});
		java.util.function.BiConsumer<String, Object> post = (path, body) -> ServerToolsUi.later(() -> { Map<String,
				Object> r = ServerToolsUi.ask("POST", path, body); SwingUtilities.invokeLater(() -> ServerToolsUi.say(said, r)); load.run(); });
		onF.addActionListener(e -> { if (!filling[0]) post.accept("/admin/panel", m("on", onF.isSelected())); });
		copy.addActionListener(e -> { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(addrF.getText()), null); said.setForeground(ServerToolsUi.GREEN); said.setText("Address copied."); });

		// An account: its name, a password typed twice (only for a new one, or to change it), and what it may do.
		java.util.function.Consumer<Map<String, Object>> edit = have -> {
			boolean fresh = have == null;
			JTextField nameF = new JTextField(fresh ? "" : Json.str(have.get("name")), 20); nameF.setEditable(fresh);
			JPasswordField p1 = new JPasswordField(20), p2 = new JPasswordField(20);
			JCheckBox adminF = new JCheckBox("Admin: everything below, and updating Gatekeeper and making servers", !fresh
					&& Boolean.TRUE.equals(have.get("admin"))), enF = new JCheckBox("The account can sign in", fresh || Boolean.TRUE.equals(have.get("enabled")));
			JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
			form.add(line(new JLabel("Account name"), nameF));
			form.add(line(new JLabel(fresh ? "Password" : "New password (empty: keep it)"), p1));
			form.add(line(new JLabel("The same again"), p2));
			form.add(note("At least 10 characters. Gatekeeper keeps only a scrambled form of it that can't be turned back, so nobody can read it later, you included."));
			form.add(line(enF)); form.add(line(adminF));
			List<JCheckBox> boxes = new ArrayList<>();
			for (Object[] pm : perms) { JCheckBox b = new JCheckBox(Json.str(pm[1]), !fresh
					&& Json.list(have.get("perms")).contains(pm[0])); boxes.add(b); form.add(line(b)); }
			if (JOptionPane.showConfirmDialog(f, form, fresh ? "New account" : "Change " + Json.str(have.get("name")),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			String a = new String(p1.getPassword()), b2 = new String(p2.getPassword());
			if (!a.equals(b2)) { said.setForeground(ServerToolsUi.RED); said.setText("The two passwords weren't the same: nothing was saved."); return; }
			List<Object> may = new ArrayList<>();
			for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) may.add(perms.get(i)[0]);
			post.accept("/admin/panel/user", m("name", nameF.getText().trim(), "password", a, "admin", adminF.isSelected(), "enabled", enF.isSelected(), "perms", may));
		};
		JButton addU = new JButton("New account…"), changeU = new JButton("Change…"), delU = dev.gatehouse.look.SteamLook.danger(new JButton("Remove"));
		addU.addActionListener(e -> edit.accept(null));
		changeU.addActionListener(e -> { int i = ut.getSelectedRow(); if (i >= 0
				&& i < users.size()) edit.accept(users.get(i)); else { said.setForeground(ServerToolsUi.GREY); said.setText("Click an account first."); } });
		delU.addActionListener(e -> { int i = ut.getSelectedRow(); if (i < 0
				|| i >= users.size()) { said.setForeground(ServerToolsUi.GREY); said.setText("Click an account first."); return; } String n = Json.str(users.get(i).get("name")); if (JOptionPane.showConfirmDialog(f, "Remove the account " + n + "? It is signed out everywhere.", "Remove account", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) post.accept("/admin/panel/user", m("name", n, "remove", true)); });

		JButton addW = new JButton("Add a device…"), delW = dev.gatehouse.look.SteamLook.danger(new JButton("Remove")), wakeW = new JButton("Wake it now");
		addW.addActionListener(e -> {
			JTextField nameF = new JTextField(18), macF = new JTextField(18);
			JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
			form.add(line(new JLabel("Name"), nameF)); form.add(line(new JLabel("MAC address"), macF));
			form.add(note("The MAC address of that device's network adapter, like 1C:2D:3E:4F:5A:6B. On Windows: Settings, Network & internet, the adapter's properties, Physical address (MAC). The device has to be on the same home network as this machine and set to wake on a network signal (its adapter's Power Management in Device Manager, and often a setting in its BIOS)."));
			if (JOptionPane.showConfirmDialog(f, form, "A device to wake", JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) post.accept("/admin/wake", m("add", m("name", nameF.getText().trim(), "mac", macF.getText().trim())));
		});
		delW.addActionListener(e -> { int i = wt.getSelectedRow(); if (i >= 0) post.accept("/admin/wake", m("remove", Json.str(wm.getValueAt(i, 0)))); });
		wakeW.addActionListener(e -> { int i = wt.getSelectedRow(); if (i >= 0) post.accept("/admin/wake", m("send",
				Json.str(wm.getValueAt(i, 0)))); else { said.setForeground(ServerToolsUi.GREY); said.setText("Click a device first."); } });

		JScrollPane us = new JScrollPane(ut), ws = new JScrollPane(wt);
		us.setPreferredSize(new java.awt.Dimension(780, 150)); ws.setPreferredSize(new java.awt.Dimension(780, 110));
		us.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); ws.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		p.add(note("A page for controlling this Gatekeeper from a browser, after signing in: servers, players, launchers' access, the log, and waking and sleeping machines. It is served only on this machine's Tailscale address, so only devices on your Tailscale network can open it; it is not on the internet or your home network."));
		p.add(line(onF, addrF, copy));
		p.add(head("Accounts"));
		p.add(note("Accounts are made here and nowhere else: the web panel can't make, change or remove them. Things that could run a program on this machine (an own start command, a Java path, an installer), Server setup and Gatekeeper's access to MCSS can't be done from the web at all."));
		p.add(us);
		p.add(line(addU, changeU, delU));
		p.add(head("Devices the panel can wake"));
		p.add(note("Other computers on this machine's home network. This machine sends them a wake signal (Wake-on-LAN) when someone allowed to presses Wake in the panel. The panel can't wake this machine itself: it runs on it."));
		p.add(ws);
		p.add(line(addW, delW, wakeW));
		p.add(said);
		JPanel top = new JPanel(new BorderLayout()); top.add(p, BorderLayout.NORTH);
		if (System.getenv("OJ_PANEL_PNG") == null || !System.getenv("OJ_PANEL_PNG").isEmpty()) load.run();
		return top;
	}
}
