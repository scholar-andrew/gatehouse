package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
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

/**
 * The window's side of ServerTools: "New server" (making a Minecraft server from nothing) and "Manage" (one server's
 * console, settings, game settings, backups and scheduled tasks). Like the rest of the window it only asks the running
 * Gatekeeper (/admin/tools/...); it changes nothing itself.
 */
final class ServerToolsUi {
	static final Color GREY = new Color(0x8F98A0), GREEN = new Color(0x8BD46E), RED = new Color(0xFF7B72), AMBER = new Color(0xE9B949);
	static final String NO_FILE = "Not needed for this kind: Gatekeeper fetches it";
	static final String[][] WHEN = {{"daily", "Every day at a time"}, {"every", "Every so many minutes"}, {"manual", "Only when I press Run now"}};
	static final String[][] WHO = {{"any", "Whoever is on"}, {"empty", "Only if nobody is playing"}, {"some", "Only if someone is playing"}};
	static final String[][] JOB = {{"restart", "Restart the server"}, {"stop", "Stop the server"}, {"start", "Start the server"}, {"backup", "Make a backup"}, {"commands", "Type commands"}};

	static String word(String[][] table, String key) { for (String[] r : table) if (r[0].equals(key)) return r[1]; return table[0][1]; }
	static String key(String[][] table, Object word) { for (String[] r : table) if (r[1].equals(word)) return r[0]; return table[0][0]; }
	static String[] words(String[][] table) { String[] o = new String[table.length]; for (int i = 0; i < o.length; i++) o[i] = table[i][1]; return o; }

	static Map<String, Object> ask(String method, String path, Object body) {
		try { return Json.map(Json.parse(call(method, path, body == null ? null : Json.write(body)))); }
		catch (Exception e) { return m("result", "failed", "message", "Couldn't ask Gatekeeper: is it running?"); }
	}
	static String num(Object o) { return Json.str(o).replaceAll("\\.0$", ""); }
	static JLabel note(String text) { JLabel l = new JLabel(text); l.setForeground(GREY); return l; }
	static String wrap(String text, int px) { return "<html><div style='width:" + px + "px'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"; }
	/** Shows how a call went in a label: green when it was done, red when not. */
	static void say(JLabel l, Map<String, Object> r) { l.setForeground(Json.str(r.get("result")).equals("ok") ? GREEN : RED); l.setText(wrap(Json.str(r.get("message")), 520)); }
	static void later(Runnable work) { Thread t = new Thread(work, "server-tools"); t.setDaemon(true); t.start(); }

	private static void row(JPanel form, int y, String label, JComponent field) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = y; c.insets = new Insets(4, 4, 4, 8); c.anchor = GridBagConstraints.WEST;
		if (label != null) { c.gridx = 0; form.add(new JLabel(label), c); }
		c.gridx = label == null ? 0 : 1; c.gridwidth = label == null ? 2 : 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
		form.add(field, c);
	}
	private static JPanel line(JComponent... parts) { JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); for (JComponent c : parts) p.add(c); return p; }
	private static JComboBox<String> javaBox(List<Object> javas, String chosen, String first) {
		JComboBox<String> box = new JComboBox<>();
		box.addItem(first);
		for (Object o : javas) { String path = Json.str(Json.map(o).get("path")); box.addItem("Java " + num(Json.map(o).get("version")) + "   " + path); if (path.equals(chosen)) box.setSelectedIndex(box.getItemCount() - 1); }
		if (!chosen.isEmpty() && box.getSelectedIndex() == 0) { box.addItem("Java ?   " + chosen); box.setSelectedIndex(box.getItemCount() - 1); }
		return box;
	}
	private static String javaOf(JComboBox<String> box) { return box.getSelectedIndex() <= 0 ? "" : Json.str(box.getSelectedItem()).replaceFirst("^Java \\S+\\s+", ""); }

	// ------------------------------------------------------------------ New server

	/** The form that makes a server. after: run when one has been made. */
	static JPanel newForm(java.awt.Component owner, Runnable after) {
		Map<String, Object> start = ask("GET", "/admin/tools/new", null);
		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		if (!Json.str(start.get("host")).equals("builtin")) { row(form, 0, null, new JLabel(wrap(Json.str(start.get("message")).isEmpty() ? "Couldn't ask Gatekeeper: is it running?" : Json.str(start.get("message")) + ". (Gatekeeper makes servers itself only where it runs them itself: on Linux and SteamOS, or on Windows without MCSS.)", 420))); return form; }
		List<Object> kinds = Json.list(start.get("kinds"));
		JTextField nameF = new JTextField(24), fileF = new JTextField(24), memF = new JTextField("4G", 6), portF = new JTextField(Json.str(start.get("port")), 6);
		JComboBox<String> kindF = new JComboBox<>(), verF = new JComboBox<>();
		for (Object k : kinds) kindF.addItem(Json.str(Json.map(k).get("name")));
		JComboBox<String> javaF = javaBox(Json.list(start.get("javas")), "", "Choose one that fits the version");
		JButton browse = new JButton("Browse…"), eulaB = new JButton("Open the EULA"), make = new JButton("Make the server");
		JCheckBox eula = new JCheckBox("I have read Minecraft's EULA and I agree to it");
		JLabel verNote = note(" "), said = new JLabel(" "), fileL = new JLabel("File");
		java.util.function.Supplier<String> kind = () -> Json.str(Json.map(kinds.get(Math.max(0, kindF.getSelectedIndex()))).get("id"));
		Runnable kindChanged = () -> {
			String k = kind.get();
			boolean own = k.equals("forge") || k.equals("file");
			verF.setEnabled(!own); fileF.setEnabled(own); browse.setEnabled(own);
			// (a box that isn't used for this kind says so, and why)
			if (!own) fileF.setText(NO_FILE); else if (fileF.getText().equals(NO_FILE)) fileF.setText("");
			fileL.setText(k.equals("forge") ? "Installer" : "File");
			verF.removeAllItems();
			if (own) verF.addItem(k.equals("forge") ? "Not needed: the installer decides it" : "Not needed: it is whatever that jar is");
			verNote.setText(own ? (k.equals("forge") ? "The installer decides the version. Get it from files.minecraftforge.net or neoforged.net." : "The version is whatever that jar is.") : "Fetching the list of versions…");
			if (!own) later(() -> {
				List<Object> got; String trouble = "";
				try { got = ServerTools.versions(k); } catch (Exception e) { got = List.of(); trouble = "The list couldn't be fetched (" + e.getMessage() + "). Is this machine online?"; }
				List<Object> g2 = got; String t2 = trouble;
				SwingUtilities.invokeLater(() -> { if (!kind.get().equals(k)) return; verF.removeAllItems(); for (Object v : g2) verF.addItem(Json.str(v)); verNote.setText(t2.isEmpty() ? "Newest first. Players need the same version." : t2); });
			});
		};
		kindF.addActionListener(e -> kindChanged.run());
		browse.addActionListener(e -> { JFileChooser fc = new JFileChooser(fileF.getText().isBlank() || fileF.getText().equals(NO_FILE) ? System.getProperty("user.home", "") : fileF.getText()); fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Jar files", "jar")); if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) fileF.setText(fc.getSelectedFile().getPath()); });
		eulaB.addActionListener(e -> dev.gatehouse.look.Links.openOrShow(owner, "https://aka.ms/MinecraftEULA"));
		Timer[] poll = {null};
		make.addActionListener(e -> {
			Map<String, Object> r = ask("POST", "/admin/tools/new", m("name", nameF.getText(), "kind", kind.get(), "version", verF.isEnabled() ? Json.str(verF.getSelectedItem()) : "", "file", fileF.isEnabled() ? fileF.getText() : "", "memory", memF.getText(), "port", portF.getText(), "java", javaOf(javaF), "eula", eula.isSelected()));
			if (!Json.str(r.get("result")).equals("ok")) { say(said, r); return; }
			make.setEnabled(false);
			said.setForeground(AMBER); said.setText("Starting…");
			poll[0] = new Timer(700, ev -> later(() -> {
				Map<String, Object> mk = Json.map(ask("GET", "/admin/tools/new", null).get("making"));
				SwingUtilities.invokeLater(() -> {
					String st = Json.str(mk.get("state"));
					if (st.equals("working")) { said.setForeground(AMBER); said.setText(wrap(Json.str(mk.get("step")), 520)); return; }
					if (poll[0] != null) poll[0].stop();
					make.setEnabled(true);
					say(said, m("result", st.equals("done") ? "ok" : "failed", "message", Json.str(mk.get("message"))));
					if (st.equals("done") && after != null) after.run();
				});
			}));
			poll[0].start();
		});
		int y = 0;
		row(form, y++, null, new JLabel(wrap("Makes a Minecraft server in a folder of its own in " + Json.str(start.get("folder")) + ". Gatekeeper fetches the server's program from where its makers publish it, checks it, and sets it up. Nothing is fetched until you press Make the server.", 520)));
		row(form, y++, "Name", nameF);
		row(form, y++, "Kind", kindF);
		row(form, y++, "Minecraft version", verF);
		row(form, y++, null, verNote);
		GridBagConstraints c = new GridBagConstraints(); c.gridy = y; c.gridx = 0; c.insets = new Insets(4, 4, 4, 8); c.anchor = GridBagConstraints.WEST; form.add(fileL, c);
		JPanel fileRow = new JPanel(new BorderLayout(6, 0)); fileRow.add(fileF, BorderLayout.CENTER); fileRow.add(browse, BorderLayout.EAST);
		c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; form.add(fileRow, c); y++;
		row(form, y++, "Memory", line(memF, note("the most it may use, like 4G or 6G")));
		row(form, y++, "Port", line(portF, note("free on this machine; Minecraft's usual one is 25565")));
		row(form, y++, "Java", javaF);
		row(form, y++, null, line(eula, eulaB));
		row(form, y++, null, note(wrap("A Minecraft server can't be run without agreeing to Mojang's EULA. That is yours to do: Gatekeeper records it in the server's eula.txt only when this box is ticked.", 520)));
		row(form, y++, null, line(make));
		row(form, y++, null, said);
		kindChanged.run();
		return form;
	}
	static void newServer(JFrame f, Runnable after) {
		JDialog d = new JDialog(f, "New server", false);
		d.setContentPane(newForm(d, after));
		d.pack(); d.setLocationRelativeTo(f); d.setVisible(true);
	}

	// ------------------------------------------------------------------ Manage one server

	/** One server's console, settings, game settings, backups and tasks, as tabs. */
	static JTabbedPane manageTabs(java.awt.Component owner, String id, boolean live) {
		String q = "?server=" + id;
		JTabbedPane tabs = new JTabbedPane();
		Map<String, Object> first = ask("GET", "/admin/tools/server" + q, null);
		if (Json.str(first.get("id")).isEmpty()) { JPanel p = new JPanel(new BorderLayout()); p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); p.add(new JLabel(wrap(Json.str(first.get("message")).isEmpty() ? "Couldn't ask Gatekeeper: is it running?" : Json.str(first.get("message")), 460)), BorderLayout.NORTH); tabs.addTab("Manage", p); return tabs; }

		// ---- Console
		JTextArea out = new JTextArea(22, 110);
		out.setEditable(false); out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JTextField typed = new JTextField();
		JButton send = new JButton("Send"), runB = new JButton("Start"), restartB = new JButton("Restart"), killB = new JButton("End it now…");
		killB.setToolTipText("For a server that no longer answers: it is ended at once, without saving");
		JLabel stats = new JLabel(" "), conSaid = new JLabel(" ");
		long[] next = {0};
		boolean[] up = {false};
		java.util.function.Consumer<Map<String, Object>> showStats = s -> {
			String ph = Json.str(s.get("phase"));
			up[0] = Boolean.TRUE.equals(s.get("running"));
			long sec = 0; try { sec = (long) Double.parseDouble(Json.str(s.get("uptime"))); } catch (NumberFormatException ignored) {}
			String state = ph.equals("online") ? "On" : ph.equals("starting") ? "Starting…" : ph.equals("stopping") ? "Shutting down…" : up[0] ? "On" : "Off";
			stats.setForeground(ph.equals("online") ? GREEN : up[0] ? AMBER : GREY);
			stats.setText(state + (up[0] ? "   ·   up " + (sec >= 3600 ? sec / 3600 + " h " : "") + sec % 3600 / 60 + " min   ·   memory " + num(s.get("memory_used")) + " MB of " + Json.str(s.get("memory")) + (s.containsKey("cpu") ? "   ·   processor " + num(s.get("cpu")) + "%" : "") + "   ·   " + num(s.get("players")) + " playing" : "") + "   ·   port " + Json.str(s.get("port"))
					+ (up[0] && !Boolean.TRUE.equals(s.get("console")) ? "   ·   console lost (started before Gatekeeper last restarted)" : ""));
			runB.setText(up[0] ? "Stop" : "Start");
			restartB.setEnabled(up[0]); killB.setEnabled(up[0]); send.setEnabled(Boolean.TRUE.equals(s.get("console"))); typed.setEnabled(Boolean.TRUE.equals(s.get("console")));
			if (!up[0] && !Json.str(s.get("blocker")).isEmpty()) { conSaid.setForeground(AMBER); conSaid.setText(wrap("It can't be started yet: " + Json.str(s.get("blocker")), 700)); }
		};
		Runnable readConsole = () -> {
			Map<String, Object> r = ask("POST", "/admin/tools/console" + q, m("after", next[0]));
			Map<String, Object> s = ask("GET", "/admin/tools/server" + q, null);
			SwingUtilities.invokeLater(() -> {
				long n = 0; try { n = (long) Double.parseDouble(Json.str(r.get("next"))); } catch (NumberFormatException ignored) {}
				if (n < next[0]) out.append("\n---- started again ----\n");
				StringBuilder sb = new StringBuilder();
				for (Object l : Json.list(r.get("lines"))) sb.append(Json.str(l)).append('\n');
				if (n != next[0] && sb.length() > 0) { out.append(sb.toString()); out.setCaretPosition(out.getDocument().getLength()); }
				next[0] = n;
				if (!Json.str(s.get("id")).isEmpty()) showStats.accept(s);
			});
		};
		Runnable sendIt = () -> { String line = typed.getText().trim(); if (line.isEmpty()) return; typed.setText(""); later(() -> { Map<String, Object> r = ask("POST", "/admin/tools/command" + q, m("command", line)); SwingUtilities.invokeLater(() -> { if (Json.str(r.get("result")).equals("ok")) { conSaid.setForeground(GREY); conSaid.setText("Typed: " + line); } else say(conSaid, r); }); readConsole.run(); }); };
		send.addActionListener(e -> sendIt.run());
		typed.addActionListener(e -> sendIt.run());
		java.util.function.BiConsumer<String, Object> act = (path, body) -> later(() -> { Map<String, Object> r = ask("POST", path + q, body); SwingUtilities.invokeLater(() -> say(conSaid, r)); readConsole.run(); });
		runB.addActionListener(e -> { if (up[0] && JOptionPane.showConfirmDialog(owner, "Stop the Minecraft server? Anyone playing is disconnected (the world is saved first).", "Minecraft server", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return; act.accept(up[0] ? "/admin/server/stop" : "/admin/server/start", m()); });
		restartB.addActionListener(e -> { if (JOptionPane.showConfirmDialog(owner, "Restart the Minecraft server? Anyone playing is disconnected until it is back.", "Minecraft server", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act.accept("/admin/tools/action", m("action", "restart")); });
		killB.addActionListener(e -> { if (JOptionPane.showConfirmDialog(owner, "End the server at once, without saving? Whatever it hadn't saved yet is lost. Use Stop unless it no longer answers.", "End it now", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) act.accept("/admin/tools/action", m("action", "kill")); });
		JPanel console = new JPanel(new BorderLayout(0, 6));
		console.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JPanel top = new JPanel(new BorderLayout()); top.add(stats, BorderLayout.CENTER); top.add(line(runB, restartB, killB), BorderLayout.EAST);
		JPanel bottom = new JPanel(new BorderLayout(6, 4)); bottom.add(typed, BorderLayout.CENTER); bottom.add(send, BorderLayout.EAST); bottom.add(conSaid, BorderLayout.SOUTH);
		console.add(top, BorderLayout.NORTH); console.add(new JScrollPane(out), BorderLayout.CENTER); console.add(bottom, BorderLayout.SOUTH);
		tabs.addTab("Console", console);

		// ---- Settings
		JPanel set = new JPanel(new GridBagLayout());
		set.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		JTextField nameF = new JTextField(Json.str(first.get("name")), 24), memF = new JTextField(Json.str(first.get("memory")), 6), portF = new JTextField(Json.str(first.get("port")), 6), startF = new JTextField(Json.str(first.get("start")), 30);
		JComboBox<String> javaF = javaBox(Json.list(first.get("javas")), Json.str(first.get("java")), "The Java Gatekeeper runs on");
		JCheckBox autoF = new JCheckBox("Start it when Gatekeeper starts", Boolean.TRUE.equals(first.get("autostart"))), keepF = new JCheckBox("Start it again if it ends without being asked to (a crash)", Json.str(first.get("keep_online")).equals("crash"));
		JCheckBox eulaF = new JCheckBox("I have read Minecraft's EULA (aka.ms/MinecraftEULA) and I agree to it", Boolean.TRUE.equals(first.get("eula")));
		eulaF.setEnabled(!Boolean.TRUE.equals(first.get("eula")));
		JLabel setSaid = new JLabel(" "), withL = note(wrap("Started with: " + (Json.str(first.get("started_with")).isEmpty() ? "nothing yet (its folder has no server program)" : Json.str(first.get("started_with"))), 560));
		JButton saveSet = new JButton("Save");
		saveSet.addActionListener(e -> later(() -> {
			Map<String, Object> r = ask("POST", "/admin/tools/server" + q, m("name", nameF.getText(), "memory", memF.getText(), "port", portF.getText(), "java", javaOf(javaF), "start", startF.getText(), "autostart", autoF.isSelected(), "keep_online", keepF.isSelected() ? "crash" : "none", "eula", eulaF.isSelected()));
			Map<String, Object> s = ask("GET", "/admin/tools/server" + q, null);
			SwingUtilities.invokeLater(() -> { say(setSaid, r); if (!Json.str(s.get("id")).isEmpty()) { withL.setText(wrap("Started with: " + Json.str(s.get("started_with")), 560)); if (Boolean.TRUE.equals(s.get("eula"))) eulaF.setEnabled(false); } });
		}));
		int y = 0;
		row(set, y++, "Name", nameF);
		row(set, y++, "Memory", line(memF, note("the most it may use, like 4G or 6G")));
		row(set, y++, "Port", line(portF, note("changed with the server off")));
		row(set, y++, "Java", javaF);
		row(set, y++, "Own start command", startF);
		row(set, y++, null, note("Leave empty unless you know you need one: it replaces Java, memory and the server's file above."));
		row(set, y++, null, autoF);
		row(set, y++, null, keepF);
		row(set, y++, null, note("Given up after three times in ten minutes, so a server that can't stay up isn't started for ever."));
		row(set, y++, null, eulaF);
		row(set, y++, null, withL);
		row(set, y++, null, note("Folder: " + Json.str(first.get("dir"))));
		row(set, y++, null, line(saveSet));
		row(set, y++, null, setSaid);
		// Deleting the server: out of Gatekeeper only, or its files too (which needs its name typed).
		JButton delB = new JButton("Delete this server\u2026");
		delB.setToolTipText("Takes this server out of Gatekeeper, and, if you choose, deletes its folder for good");
		delB.addActionListener(e -> {
			String nm = Json.str(first.get("name"));
			javax.swing.JRadioButton only = new javax.swing.JRadioButton("Take it out of Gatekeeper, and leave its files where they are", true), all = new javax.swing.JRadioButton("Delete it for good: out of Gatekeeper, and its whole folder deleted (the world too)");
			javax.swing.ButtonGroup g = new javax.swing.ButtonGroup(); g.add(only); g.add(all);
			JTextField typedName = new JTextField(20); typedName.setEnabled(false);
			all.addActionListener(ev -> typedName.setEnabled(true)); only.addActionListener(ev -> typedName.setEnabled(false));
			JPanel form = new JPanel(); form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
			for (JComponent c : new JComponent[] {new JLabel(wrap("Delete " + nm + "? The server has to be off. Launchers that had it stop listing it.", 460)), only, all, new JLabel(wrap("Folder: " + Json.str(first.get("dir")) + ". Deleting it can't be undone; backups Gatekeeper made are kept. To delete for good, type the server's name:", 460)), typedName}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); form.add(javax.swing.Box.createVerticalStrut(6)); }
			if (JOptionPane.showConfirmDialog(owner, form, "Delete " + nm, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
			later(() -> {
				Map<String, Object> r = ask("POST", "/admin/tools/delete" + q, m("files", all.isSelected(), "name", typedName.getText()));
				SwingUtilities.invokeLater(() -> {
					if (!Json.str(r.get("result")).equals("ok")) { say(setSaid, r); return; }
					JOptionPane.showMessageDialog(owner, wrap(Json.str(r.get("message")), 420), "Delete " + nm, JOptionPane.PLAIN_MESSAGE);
					java.awt.Window w = SwingUtilities.getWindowAncestor(tabs);
					if (w instanceof JDialog) w.dispose();
				});
			});
		});
		row(set, y++, null, line(delB));
		JPanel setTop = new JPanel(new BorderLayout()); setTop.add(set, BorderLayout.NORTH);
		tabs.addTab("Settings", setTop);

		// ---- Game settings (server.properties)
		DefaultTableModel pm = new DefaultTableModel(new Object[] {"Setting", "Value"}, 0) { @Override public boolean isCellEditable(int r, int c) { return c == 1 && !"server-port".equals(getValueAt(r, 0)); } };
		Map<String, String> was = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : Json.map(first.get("properties")).entrySet()) { pm.addRow(new Object[] {e.getKey(), Json.str(e.getValue())}); was.put(e.getKey(), Json.str(e.getValue())); }
		JTable pt = new JTable(pm);
		pt.putClientProperty("terminateEditOnFocusLost", true);
		JLabel propSaid = new JLabel(" ");
		JButton saveProps = new JButton("Save");
		saveProps.addActionListener(e -> {
			if (pt.isEditing()) pt.getCellEditor().stopCellEditing();
			Map<String, Object> changed = new LinkedHashMap<>();
			for (int i = 0; i < pm.getRowCount(); i++) { String k = Json.str(pm.getValueAt(i, 0)), v = Json.str(pm.getValueAt(i, 1)); if (!v.equals(was.get(k))) changed.put(k, v); }
			if (changed.isEmpty()) { propSaid.setForeground(GREY); propSaid.setText("Nothing was changed."); return; }
			later(() -> { Map<String, Object> r = ask("POST", "/admin/tools/properties" + q, m("values", changed)); SwingUtilities.invokeLater(() -> { say(propSaid, r); if (Json.str(r.get("result")).equals("ok")) for (Map.Entry<String, Object> c : changed.entrySet()) was.put(c.getKey(), Json.str(c.getValue())); }); });
		});
		JPanel props = new JPanel(new BorderLayout(0, 6));
		props.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		props.add(note(pm.getRowCount() == 0 ? "The server writes its settings file (server.properties) the first time it starts: start it once, then look here again." : "The server's own settings file (server.properties): difficulty, game mode, how many may play, and so on. Click a value to change it. It reads them when it starts."), BorderLayout.NORTH);
		props.add(new JScrollPane(pt), BorderLayout.CENTER);
		JPanel propBottom = new JPanel(new BorderLayout()); propBottom.add(line(saveProps), BorderLayout.NORTH); propBottom.add(propSaid, BorderLayout.SOUTH);
		props.add(propBottom, BorderLayout.SOUTH);
		tabs.addTab("Game settings", props);

		// ---- Backups
		JComboBox<String> whatF = new JComboBox<>(new String[] {"The world", "Everything in the server's folder"});
		whatF.setSelectedIndex(Json.str(first.get("backup_what")).equals("all") ? 1 : 0);
		JTextField keepN = new JTextField(Json.str(first.get("backup_keep")), 4), dirF = new JTextField(Json.str(first.get("backup_dir")), 34);
		JButton backNow = new JButton("Back up now"), saveBack = new JButton("Save");
		JLabel backSaid = new JLabel(" ");
		javax.swing.DefaultListModel<String> made = new javax.swing.DefaultListModel<>();
		java.util.function.Consumer<Map<String, Object>> showBackups = s -> {
			made.clear();
			for (Object o : Json.list(s.get("backups"))) made.addElement(Json.str(Json.map(o).get("file")) + "     " + num(Json.map(o).get("mb")) + " MB");
			String st = Json.str(s.get("backup_state"));
			if (!st.isEmpty()) { backSaid.setForeground(st.startsWith("Backing up") ? AMBER : st.startsWith("Backed up") ? GREEN : RED); backSaid.setText(wrap(st, 560)); }
		};
		showBackups.accept(first);
		Runnable readBackups = () -> later(() -> { Map<String, Object> s = ask("GET", "/admin/tools/server" + q, null); SwingUtilities.invokeLater(() -> { if (!Json.str(s.get("id")).isEmpty()) showBackups.accept(s); }); });
		saveBack.addActionListener(e -> later(() -> { Map<String, Object> r = ask("POST", "/admin/tools/server" + q, m("backup_what", whatF.getSelectedIndex() == 1 ? "all" : "world", "backup_keep", keepN.getText(), "backup_dir", dirF.getText())); SwingUtilities.invokeLater(() -> say(backSaid, r)); }));
		backNow.addActionListener(e -> later(() -> { Map<String, Object> r = ask("POST", "/admin/tools/backup" + q, m()); SwingUtilities.invokeLater(() -> say(backSaid, r)); }));
		JPanel backForm = new JPanel(new GridBagLayout());
		y = 0;
		row(backForm, y++, null, note(wrap("A backup is a zip of the world (or of the whole server folder, without its logs). With the server on, it is told to save and to hold its saving still while the copy is made. To put one back: stop the server and unzip it over the server's folder.", 560)));
		row(backForm, y++, "Back up", whatF);
		row(backForm, y++, "Keep the newest", line(keepN, note("older ones made by Gatekeeper are removed")));
		row(backForm, y++, "Into", dirF);
		row(backForm, y++, null, line(saveBack, backNow));
		row(backForm, y++, null, backSaid);
		JPanel back = new JPanel(new BorderLayout(0, 6));
		back.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		back.add(backForm, BorderLayout.NORTH); back.add(new JScrollPane(new javax.swing.JList<>(made)), BorderLayout.CENTER);
		tabs.addTab("Backups", back);

		// ---- Tasks
		DefaultTableModel tm = new DefaultTableModel(new Object[] {"On", "Name", "When", "Time (24h)", "Minutes", "Players", "Does", "Commands (one after another, split by ;)"}, 0) { @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; } };
		for (Object o : Json.list(first.get("tasks"))) {
			Map<String, Object> t = Json.map(o);
			List<String> cmds = new ArrayList<>(); for (Object c2 : Json.list(t.get("commands"))) cmds.add(Json.str(c2));
			tm.addRow(new Object[] {Boolean.TRUE.equals(t.get("enabled")), Json.str(t.get("name")), word(WHEN, Json.str(t.get("when"))), Json.str(t.get("at")), num(t.get("minutes")), word(WHO, Json.str(t.get("players"))), word(JOB, Json.str(t.get("job"))), String.join(" ; ", cmds)});
		}
		JTable tt = new JTable(tm);
		tt.putClientProperty("terminateEditOnFocusLost", true);
		tt.setRowHeight(24);
		tt.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(words(WHEN))));
		tt.getColumnModel().getColumn(5).setCellEditor(new DefaultCellEditor(new JComboBox<>(words(WHO))));
		tt.getColumnModel().getColumn(6).setCellEditor(new DefaultCellEditor(new JComboBox<>(words(JOB))));
		tt.getColumnModel().getColumn(0).setMaxWidth(40); tt.getColumnModel().getColumn(7).setPreferredWidth(260);
		int[] wide = {40, 130, 190, 80, 60, 190, 150};
		for (int i = 1; i < wide.length; i++) tt.getColumnModel().getColumn(i).setPreferredWidth(wide[i]);
		JButton addT = new JButton("Add a task"), delT = new JButton("Remove"), saveT = new JButton("Save"), runT = new JButton("Run now");
		JLabel taskSaid = new JLabel(" ");
		addT.addActionListener(e -> tm.addRow(new Object[] {true, "Nightly restart", WHEN[0][1], "04:00", "", WHO[1][1], JOB[0][1], ""}));
		delT.addActionListener(e -> { if (tt.isEditing()) tt.getCellEditor().stopCellEditing(); if (tt.getSelectedRow() >= 0) tm.removeRow(tt.getSelectedRow()); });
		java.util.function.Supplier<Map<String, Object>> saveTasks = () -> {
			List<Object> list = new ArrayList<>();
			for (int i = 0; i < tm.getRowCount(); i++) {
				List<Object> cmds = new ArrayList<>(); for (String c2 : Json.str(tm.getValueAt(i, 7)).split(";")) if (!c2.isBlank()) cmds.add(c2.trim());
				list.add(m("enabled", Boolean.TRUE.equals(tm.getValueAt(i, 0)), "name", Json.str(tm.getValueAt(i, 1)), "when", key(WHEN, tm.getValueAt(i, 2)), "at", Json.str(tm.getValueAt(i, 3)), "minutes", Json.str(tm.getValueAt(i, 4)), "players", key(WHO, tm.getValueAt(i, 5)), "job", key(JOB, tm.getValueAt(i, 6)), "commands", cmds));
			}
			return ask("POST", "/admin/tools/server" + q, m("tasks", list));
		};
		saveT.addActionListener(e -> { if (tt.isEditing()) tt.getCellEditor().stopCellEditing(); later(() -> { Map<String, Object> r = saveTasks.get(); SwingUtilities.invokeLater(() -> say(taskSaid, r)); }); });
		runT.addActionListener(e -> { if (tt.isEditing()) tt.getCellEditor().stopCellEditing(); int i = tt.getSelectedRow(); if (i < 0) { taskSaid.setForeground(GREY); taskSaid.setText("Click a task first."); return; } later(() -> { Map<String, Object> s = saveTasks.get(); Map<String, Object> r = Json.str(s.get("result")).equals("ok") ? ask("POST", "/admin/tools/task" + q, m("index", i)) : s; SwingUtilities.invokeLater(() -> say(taskSaid, r)); }); });
		JPanel tasks = new JPanel(new BorderLayout(0, 6));
		tasks.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		tasks.add(note(wrap("Things Gatekeeper does for this server by the clock, while Gatekeeper is running: a nightly restart, a backup every few hours, a message to the players. Click a cell to change it.", 700)), BorderLayout.NORTH);
		tasks.add(new JScrollPane(tt), BorderLayout.CENTER);
		JPanel taskBottom = new JPanel(new BorderLayout()); taskBottom.add(line(addT, delT, runT, saveT), BorderLayout.NORTH); taskBottom.add(taskSaid, BorderLayout.SOUTH);
		tasks.add(taskBottom, BorderLayout.SOUTH);
		tabs.addTab("Tasks", tasks);
		tabs.addTab("Pack", PackUi.tab(owner, q));

		showStats.accept(first);
		if (live) {
			Timer tick = new Timer(1500, e -> { if (!tabs.isDisplayable()) { ((Timer) e.getSource()).stop(); return; } later(readConsole); if (tabs.getSelectedIndex() == 3) readBackups.run(); });
			tick.start();
		}
		later(readConsole);
		return tabs;
	}
	static void manage(JFrame f, String id, String name) {
		JDialog d = new JDialog(f, "Manage " + name, false);
		d.setContentPane(manageTabs(d, id, true));
		d.pack(); d.setLocationRelativeTo(f); d.setVisible(true);
	}

	/** Testing: pictures of the forms (<prefix>-newserver.png, -manage-<tab>.png) instead of the dialogs. */
	static void shots(String prefix, String id) {
		try {
			JFrame t = new JFrame(); t.setContentPane(newForm(t, null)); t.pack(); Thread.sleep(2500); t.pack(); paint(t, prefix + "-newserver.png"); t.dispose();
			if (id.isEmpty()) return;
			JFrame t2 = new JFrame(); JTabbedPane tabs = manageTabs(t2, id, false); t2.setContentPane(tabs); t2.pack(); Thread.sleep(1500);
			String[] names = {"console", "settings", "game", "backups", "tasks", "pack"};
			for (int i = 0; i < tabs.getTabCount() && i < names.length; i++) { tabs.setSelectedIndex(i); t2.validate(); paint(t2, prefix + "-manage-" + names[i] + ".png"); }
			PackUi.shotTo = prefix + "-manage-mods.png"; PackUi.mods(t2, "?server=" + id, s -> {}); PackUi.shotTo = prefix + "-systems.png"; PackUi.systems(t2); PackUi.shotTo = null;
			t2.dispose();
		} catch (Exception e) { e.printStackTrace(); }
	}
	private static void paint(JFrame t, String file) throws java.io.IOException {
		java.awt.Container c = t.getContentPane();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = img.createGraphics(); c.paint(g); g.dispose();
		javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
	}
}
