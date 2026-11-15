package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The window the pack sync shows before Minecraft starts (Prism waits for the sync, so the game starts once this is
 * closed). Run as: java -jar launch-popup.jar <game folder> <ok|offline|failed>
 *
 * It reads, all in the game folder:
 *   extras/changelog.txt     the pack's patch notes, newest first ("== 1.6.0 | 2026-11-14" then "- note" lines)
 *   extras/mod-names.json    each mod's file in the pack and its name, to name added, removed and updated mods
 *   pack-version-seen.txt    the version this device last showed (written here afterwards)
 *   pack-sync-status.txt     what the sync checked this launch and how it went, one per line:
 *                              check|<ok, updated, off, warn or failed>|<what>|<detail>
 *                              switch|<on or off>|<mod name>      a mod switched on or off this launch
 *                              note|<text>                        anything else worth saying
 *   packwiz-before.json      packwiz-installer's record of the installed files before this launch's update (copied by
 *   packwiz.json             the sync), and after it: the difference is the mods added, removed and updated
 *   pack-sync-news.txt       from syncs before pack-sync-status.txt existed: notes, one per line
 *   pack-options.json        "popup_updates" / "popup_no_updates": false turns that kind of pop-up off (default on)
 *   extras/server-state.json what the server runs (tools/server_snapshot.py): versions, mods, settings
 *   extras/server-changelog.txt  notes about server changes, newest first (same format as changelog.txt)
 *   server-state-seen.json, server-notes-seen.txt  the server state and note this device last showed (written here)
 *
 * The Server tab shows what the server runs and, since this device last looked, its mods added, updated and removed,
 * settings changed and new notes. When there's something new there, the button on the first tab reads "Next: server
 * changes" and the window moves to the Server tab (on a click, Enter, or when the first tab's countdown ends).
 *
 * After an update, or when anything changed or failed, it shows each check with a coloured dot, the mods added,
 * removed and updated, mods switched on or off, notes, and the patch notes for every version since the last one seen;
 * it waits (closing itself after 90 seconds, so a launch can't get stuck). With nothing new it shows the checks and
 * closes after 5 seconds.
 */
public final class LaunchPopup {
	private static final int UPDATE_SECONDS = 90, QUIET_SECONDS = 5;
	private static final Color GREEN = new Color(0x2E9D48), BLUE = new Color(0x2F6FD6), GRAY = new Color(0x8A8F98),
			AMBER = new Color(0xD99A16), RED = new Color(0xD23C3C);

	record Version(String version, String date, List<String> notes) {}
	record Check(String state, String label, String detail) {}

	public static void main(String[] args) throws Exception {
		// Testing and troubleshooting: java -jar launch-popup.jar --server <game folder> status|wake|start
		if (args.length >= 3 && args[0].equals("--server")) {
			ServerControl c = new ServerControl(Path.of(args[1]));
			switch (args[2]) {
				case "wake" -> System.out.println("wake signal sent to " + c.wake());
				case "start" -> { c.start(); System.out.println("start requested"); }
				case "clipboard-set" -> {   // testing: put args[3] on the clipboard and keep it there for 20 seconds
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(args.length > 3 ? args[3] : "test"), null);
					Thread.sleep(20_000);
				}
				case "clipboard" -> { String t = clipboard(); System.out.println(t == null ? "clipboard: empty" : "clipboard: " + t.strip().length() + " characters"); }
				default -> System.out.println(String.join(": ", c.status()));
			}
			return;
		}
		// Plain (GDI / X11) drawing, not Direct3D or OpenGL: the Steam overlay hooks those in programs Steam started, and
		// drew its notifications over the Play button (leaving a smear when they went).
		System.setProperty("sun.java2d.d3d", "false");
		System.setProperty("sun.java2d.opengl", "false");
		System.setProperty("sun.java2d.noddraw", "true");
		Path dir = Path.of(args.length > 0 ? args[0] : ".");
		String status = args.length > 1 ? args[1] : "ok";
		List<Version> log = changelog(dir.resolve("extras/changelog.txt"));
		String current = log.isEmpty() ? null : log.get(0).version();
		String seen = read(dir.resolve("pack-version-seen.txt")).trim();
		String options = read(dir.resolve("pack-options.json"));

		// What the sync reported.
		List<Check> checks = new ArrayList<>();
		List<String> switchedOn = new ArrayList<>(), switchedOff = new ArrayList<>(), notes = new ArrayList<>();
		for (String l : read(dir.resolve("pack-sync-status.txt")).split("\\R")) {
			String[] f = l.split("\\|", 4);
			if (f[0].equals("check") && f.length >= 3) checks.add(new Check(f[1], f[2], f.length > 3 ? f[3] : ""));
			else if (f[0].equals("switch") && f.length >= 3) (f[1].equals("on") ? switchedOn : switchedOff).add(clean(f[2]));
			else if (f[0].equals("note") && f.length >= 2 && !f[1].isBlank()) notes.add(f[1].trim());
		}
		for (String l : read(dir.resolve("pack-sync-news.txt")).split("\\R")) if (!l.isBlank()) notes.add(l.trim());

		// Mods added, removed and updated: packwiz-installer's file records before and after this launch's update.
		Map<String, Object> names = asMap(parse(read(dir.resolve("extras/mod-names.json"))));
		// No "before" record (a sync from before this existed): nothing to compare, so no list.
		boolean haveBefore = Files.exists(dir.resolve("packwiz-before.json"));
		Map<String, Object> before = asMap(asMap(parse(read(dir.resolve("packwiz-before.json")))).get("cachedFiles"));
		Map<String, Object> after = haveBefore ? asMap(asMap(parse(read(dir.resolve("packwiz.json")))).get("cachedFiles")) : new LinkedHashMap<>();
		boolean firstInstall = haveBefore && before.isEmpty() && !after.isEmpty();
		TreeSet<String> added = new TreeSet<>(String.CASE_INSENSITIVE_ORDER), removed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER),
				changed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		int settingsFiles = 0, modsInstalled = 0;
		for (Map.Entry<String, Object> e : after.entrySet()) {
			if (!e.getKey().startsWith("mods/")) { if (e.getKey().startsWith("config/") && !before.isEmpty() && !same(before.get(e.getKey()), e.getValue())) settingsFiles++; continue; }
			modsInstalled++;
			if (before.isEmpty()) continue;
			Object old = before.get(e.getKey());
			if (old == null) added.add(modName(names, e.getKey(), e.getValue()));
			else if (!same(old, e.getValue())) changed.add(modName(names, e.getKey(), e.getValue()) + versions(old, e.getValue()));
		}
		for (Map.Entry<String, Object> e : before.entrySet())
			if (e.getKey().startsWith("mods/") && !after.containsKey(e.getKey())) removed.add(modName(names, e.getKey(), e.getValue()));

		// The pack update's own line: the sync says whether it ran; what it changed comes from the records above.
		for (int i = 0; i < checks.size(); i++) {
			Check c = checks.get(i);
			if (!c.label().equals("Mods and settings") || !c.state().equals("ok")) continue;
			String d;
			if (firstInstall) d = "Installed " + modsInstalled + " mods";
			else if (added.isEmpty() && removed.isEmpty() && changed.isEmpty() && settingsFiles == 0) d = "Up to date";
			else d = join(added.isEmpty() ? null : added.size() + " added", removed.isEmpty() ? null : removed.size() + " removed",
					changed.isEmpty() ? null : changed.size() + " updated", settingsFiles == 0 ? null : settingsFiles + " settings file" + (settingsFiles > 1 ? "s" : "") + " updated");
			checks.set(i, new Check(d.equals("Up to date") ? "ok" : "updated", c.label(), d));
		}

		boolean updated = current != null && !current.equals(seen);
		// The versions to tell about: everything newer than the one last seen (only the newest on a first run).
		List<Version> fresh = new ArrayList<>();
		if (updated) for (Version v : log) { if (v.version().equals(seen)) break; fresh.add(v); if (seen.isEmpty()) break; }
		boolean somethingHappened = !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty() || !switchedOn.isEmpty()
				|| !switchedOff.isEmpty() || !notes.isEmpty() || checks.stream().anyMatch(c -> c.state().equals("updated") || c.state().equals("failed"));
		// The server: what it runs now, against what this device last showed.
		Map<String, Object> server = asMap(parse(read(dir.resolve("extras/server-state.json"))));
		Map<String, Object> serverSeen = asMap(parse(read(dir.resolve("server-state-seen.json"))));
		List<Version> serverLog = changelog(dir.resolve("extras/server-changelog.txt"));
		String notesSeen = read(dir.resolve("server-notes-seen.txt")).trim();
		List<Version> serverFresh = new ArrayList<>();
		for (Version v : serverLog) { if (v.version().equals(notesSeen)) break; serverFresh.add(v); if (notesSeen.isEmpty()) break; }
		List<String[]> serverChanges = serverSeen.isEmpty() || server.isEmpty() ? new ArrayList<>() : serverDiff(serverSeen, server);
		boolean serverNews = !server.isEmpty() && (!serverFresh.isEmpty() || !serverChanges.isEmpty());
		if (!server.isEmpty()) {
			Files.writeString(dir.resolve("server-state-seen.json"), read(dir.resolve("extras/server-state.json")), StandardCharsets.UTF_8);
			if (!serverLog.isEmpty()) Files.writeString(dir.resolve("server-notes-seen.txt"), serverLog.get(0).version() + "\n", StandardCharsets.UTF_8);
		}

		boolean big = updated || somethingHappened || serverNews;
		boolean wanted = big ? option(options, "popup_updates") : option(options, "popup_no_updates");
		if (current != null) Files.writeString(dir.resolve("pack-version-seen.txt"), current + "\n", StandardCharsets.UTF_8);
		if (!wanted || GraphicsEnvironment.isHeadless()) return;

		String playing = current == null ? "" : " " + current;
		String headline;
		// Nothing seen yet (a new install, or the first launch with the pop-up): no "updated from" to speak of.
		if (updated && seen.isEmpty()) headline = "October Journey " + current;
		else if (updated) headline = "October Journey updated to " + current;
		else if (status.equals("offline")) headline = "Couldn't check for updates";
		else if (status.equals("failed")) headline = "The update didn't finish";
		else if (!somethingHappened && serverNews) headline = "October Journey" + playing + ": server changes";
		else headline = "October Journey" + playing + (somethingHappened ? "" : ": no updates");
		String sub;
		if (status.equals("offline")) sub = "The pack couldn't be reached (Tailscale or internet). Playing" + playing + " as installed.";
		else if (status.equals("failed")) sub = "Starting with the mods already installed. Details are in pack-sync.log in the game folder.";
		else if (updated || somethingHappened) sub = "Here's what this launch checked and changed.";
		else if (serverNews) sub = "Nothing new for this device; the Server tab shows what changed on the server.";
		else sub = "You're up to date.";

		// The window's content, top to bottom.
		List<Component> rows = new ArrayList<>();
		if (!checks.isEmpty()) {
			rows.add(heading("Checked"));
			for (Check c : checks) rows.add(row(colour(c.state()), c.label(), c.detail()));
		}
		if (!added.isEmpty() || !removed.isEmpty() || !changed.isEmpty()) {
			rows.add(heading("Mods"));
			for (String m : added) rows.add(row(GREEN, "Added: " + m, null));
			for (String m : changed) rows.add(row(BLUE, "Updated: " + m, null));
			for (String m : removed) rows.add(row(GRAY, "Removed: " + m, null));
		}
		if (!switchedOn.isEmpty() || !switchedOff.isEmpty()) {
			rows.add(heading("Mod switches (Mods > October Journey Settings)"));
			for (String m : switchedOn) rows.add(row(GREEN, "Switched on: " + m, null));
			for (String m : switchedOff) rows.add(row(GRAY, "Switched off: " + m, null));
		}
		if (!notes.isEmpty()) {
			rows.add(heading("Also on this device"));
			for (String n : notes) rows.add(text("• " + n));
		}
		for (Version v : fresh) {
			rows.add(heading(fresh.size() > 1 || !seen.isEmpty() ? "What's new in " + v.version() + (v.date().isEmpty() ? "" : " (" + v.date() + ")") : "What's new"));
			for (String n : v.notes()) rows.add(text("• " + n));
		}
		show(dir, headline, sub, rows, serverRows(server, serverChanges, serverFresh, serverSeen.isEmpty()), serverNews,
				updated || somethingHappened ? UPDATE_SECONDS : QUIET_SECONDS, updated || somethingHappened);
	}

	// ------------------------------------------------------------------ the server

	private static String s(Object o) { return o == null ? "" : o.toString(); }

	/** Changes from the state last shown to the current one: {colour key, text}. */
	private static List<String[]> serverDiff(Map<String, Object> old, Map<String, Object> now) {
		List<String[]> out = new ArrayList<>();
		for (String[] k : new String[][] {{"minecraft", "Minecraft"}, {"fabric_loader", "Fabric Loader"}, {"java", "Java"}, {"memory", "Memory"}})
			if (!s(old.get(k[0])).equals(s(now.get(k[0])))) out.add(new String[] {"updated", k[1] + ": " + s(old.get(k[0])) + " \u2192 " + s(now.get(k[0]))});
		Map<String, Object> om = asMap(old.get("mods")), nm = asMap(now.get("mods"));
		for (Map.Entry<String, Object> e : nm.entrySet()) {
			Map<String, Object> m = asMap(e.getValue()), o = asMap(om.get(e.getKey()));
			if (o.isEmpty()) out.add(new String[] {"ok", "Added: " + clean(s(m.get("name"))) + " " + s(m.get("version"))});
			else if (!s(o.get("sha256")).equals(s(m.get("sha256")))) out.add(new String[] {"updated", "Updated: " + clean(s(m.get("name"))) + " " + s(o.get("version")) + " \u2192 " + s(m.get("version"))});
		}
		for (Map.Entry<String, Object> e : om.entrySet())
			if (!nm.containsKey(e.getKey())) out.add(new String[] {"off", "Removed: " + clean(s(asMap(e.getValue()).get("name")))});
		Map<String, Object> os = asMap(old.get("settings")), ns = asMap(now.get("settings"));
		for (Map.Entry<String, Object> e : ns.entrySet())
			if (!s(os.get(e.getKey())).equals(s(e.getValue()))) out.add(new String[] {"updated", e.getKey() + ": " + (os.containsKey(e.getKey()) ? s(os.get(e.getKey())) : "not set") + " \u2192 " + s(e.getValue())});
		if (!s(old.get("datapacks")).equals(s(now.get("datapacks")))) out.add(new String[] {"updated", "Data packs changed"});
		return out;
	}

	private static List<Component> serverRows(Map<String, Object> st, List<String[]> changes, List<Version> notes, boolean firstLook) {
		List<Component> rows = new ArrayList<>();
		if (st.isEmpty()) { rows.add(text("No server information in this version of the pack yet.")); return rows; }
		boolean any = !changes.isEmpty();
		java.util.function.Predicate<String> touched = w -> changes.stream().anyMatch(c -> c[1].startsWith(w));
		Map<String, Object> mods = asMap(st.get("mods")), set = asMap(st.get("settings"));
		Map<String, Object> jarvis = asMap(mods.get("jarvis"));
		// (On a device's first look there's nothing to compare with: just the notes and what it runs.)
		if (!firstLook) rows.add(heading(any ? "Since you last played" : "No server changes since you last played"));
		for (String[] c : changes) rows.add(row(colour(c[0]), c[1], null));
		for (Version v : notes) {
			rows.add(heading("Server notes" + (v.date().isEmpty() ? "" : " (" + v.date() + ")")));
			for (String n : v.notes()) rows.add(text("\u2022 " + n));
		}
		rows.add(heading("The server now"));
		rows.add(row(touched.test("Minecraft") || touched.test("Fabric") ? BLUE : GREEN, "Game", "Minecraft " + s(st.get("minecraft")) + ", Fabric Loader " + s(st.get("fabric_loader"))));
		if (!jarvis.isEmpty()) rows.add(row(touched.test("Updated: Jarvis") || touched.test("Added: Jarvis") ? BLUE : GREEN, "Jarvis", s(jarvis.get("version"))));
		long changedMods = changes.stream().filter(c -> c[1].startsWith("Added: ") || c[1].startsWith("Updated: ") || c[1].startsWith("Removed: ")).count();
		rows.add(row(changedMods > 0 ? BLUE : GREEN, "Mods", mods.size() + " on the server" + (changedMods > 0 ? ", " + changedMods + " changed" : "")));
		List<String> key = new ArrayList<>();
		for (String k : new String[] {"Difficulty", "Game mode", "PvP", "Whitelist", "Hardcore"}) if (set.containsKey(k)) key.add(k + " " + s(set.get(k)));
		boolean settingsChanged = changes.stream().anyMatch(c -> set.keySet().stream().anyMatch(k -> c[1].startsWith(k + ":")));
		rows.add(row(settingsChanged ? BLUE : GREEN, "Settings", String.join(", ", key)));
		rows.add(heading("Mods on the server"));
		List<String> names = new ArrayList<>();
		for (Object m : mods.values()) names.add(clean(s(asMap(m).get("name"))) + "  " + s(asMap(m).get("version")));
		names.sort(String.CASE_INSENSITIVE_ORDER);
		for (String n : names) rows.add(row(GREEN, n, null));
		return rows;
	}

	// ------------------------------------------------------------------ server controls

	/** The top of the Server tab: whether the server answers (checked every few seconds) and the buttons. */
	private static JComponent controls(ServerControl c, JFrame f, Runnable hold, boolean live) {
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBorder(BorderFactory.createEmptyBorder(8, 6, 0, 6));
		JLabel status = new JLabel("Checking the server\u2026", dot(GRAY), JLabel.LEFT);
		status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
		status.setIconTextGap(8);
		JLabel note = new JLabel(" ");
		note.setFont(note.getFont().deriveFont(Font.PLAIN, 12f));
		note.setForeground(new Color(0x6B7280));
		JButton wake = new JButton("Wake server device"), start = new JButton("Start server"), panel = new JButton("Open server panel"), access = new JButton("Server access\u2026");
		wake.setEnabled(c.canWake());
		start.setEnabled(c.hasKey());
		panel.setEnabled(!c.panelUrl.isEmpty());
		long[] wokeAt = {0};
		Runnable refresh = () -> new Thread(() -> {
			String[] s = c.status();
			boolean waking = !s[0].equals("ok") && wokeAt[0] > 0 && System.currentTimeMillis() - wokeAt[0] < 180_000;
			SwingUtilities.invokeLater(() -> {
				status.setIcon(dot(waking && !s[1].contains("awake") ? AMBER : colour(s[0])));
				status.setText("<html><div style='width:430px'>" + esc(waking && !s[1].contains("awake") ? "Waking the server PC\u2026 (it can take a minute)" : s[1]) + "</div></html>");
				start.setEnabled(c.hasKey());
			});
		}, "server-status").start();
		wake.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				String msg;
				try { msg = "Wake signal sent (" + c.wake().size() + " address" + "es" + ")" + (c.wakeAddress.isEmpty() ? ": works from the home network" : ", including your internet wake address"); wokeAt[0] = System.currentTimeMillis(); }
				catch (IOException | RuntimeException ex) { msg = "Couldn't send the wake signal: " + ex.getMessage(); }
				String m = msg;
				SwingUtilities.invokeLater(() -> note.setText(m));
				refresh.run();
			}, "wake").start();
		});
		start.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				String msg;
				try { c.start(); msg = "Start requested: the server takes a minute or so to come up"; }
				catch (ServerControl.HttpError ex) { msg = ex.code == 401 || ex.code == 403 ? "Your API key isn't allowed to start the server (ask the owner)" : "The panel said no (" + ex.getMessage() + ")"; }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the panel: is the server PC awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> note.setText(m));
				refresh.run();
			}, "start").start();
		});
		panel.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				boolean ok = c.openPanel();
				SwingUtilities.invokeLater(() -> note.setText(ok ? "Opened the server panel in your browser (sign in there)" : "Open " + c.panelUrl + " in a browser"));
			}, "panel").start();
		});
		access.addActionListener(e -> { hold.run(); accessDialog(c, f, note); refresh.run(); });
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
		for (JButton b : new JButton[] {wake, start, panel, access}) { b.setFont(b.getFont().deriveFont(13f)); buttons.add(b); }
		for (JComponent x : new JComponent[] {status, buttons, note}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); box.add(x); }
		if (live) {
			refresh.run();
			// Every few seconds while the window is open.
			Timer poll = new Timer(4000, e -> { if (f.isDisplayable()) refresh.run(); });
			poll.start();
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { poll.stop(); } });
		}
		return box;
	}

	/** Text on the clipboard: the system clipboard, else (on Linux) the selection, the text last highlighted. */
	static String clipboard() {
		java.awt.Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
		for (java.awt.datatransfer.Clipboard cb : new java.awt.datatransfer.Clipboard[] {tk.getSystemClipboard(), tk.getSystemSelection()}) {
			if (cb == null) continue;
			try {
				Object t = cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
				if (t != null && !t.toString().isBlank()) return t.toString();
			} catch (Exception ignored) {}
		}
		return null;
	}

	/** This device's own server settings: the API key from the panel, and an internet wake address. */
	private static void accessDialog(ServerControl c, JFrame f, JLabel note) {
		javax.swing.JPasswordField key = new javax.swing.JPasswordField(c.apiKey, 28);
		javax.swing.JTextField wake = new javax.swing.JTextField(c.wakeAddress, 28);
		char hidden = key.getEchoChar();
		JLabel pasteNote = new JLabel(" ");
		pasteNote.setForeground(new Color(0x6B7280));
		// Paste buttons: an on-screen keyboard (Steam Deck) has no Ctrl+V, so pasting a key copied in the browser
		// needs a button.
		java.util.function.Function<javax.swing.text.JTextComponent, JButton> paste = field -> {
			JButton b = new JButton("Paste");
			b.addActionListener(e -> {
				String t = clipboard();
				if (t == null || t.isBlank()) pasteNote.setText("The clipboard is empty: copy the text first, then press Paste.");
				else { field.setText(t.strip()); pasteNote.setText("Pasted."); }
			});
			return b;
		};
		javax.swing.JCheckBox show = new javax.swing.JCheckBox("Show");
		show.addActionListener(e -> key.setEchoChar(show.isSelected() ? (char) 0 : hidden));
		JPanel keyRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		keyRow.add(key); keyRow.add(paste.apply(key)); keyRow.add(show);
		JPanel wakeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		wakeRow.add(wake); wakeRow.add(paste.apply(wake));
		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		for (JComponent x : new JComponent[] {
				text("Saved on this device only (server-access.json in the game folder)."),
				new JLabel("API key (made for you in the server panel, under API Keys):"), keyRow,
				new JLabel("Internet wake address, host:port (optional; only if the owner gave you one):"), wakeRow, pasteNote}) {
			x.setAlignmentX(Component.LEFT_ALIGNMENT); form.add(x); form.add(Box.createVerticalStrut(4));
		}
		int r = javax.swing.JOptionPane.showConfirmDialog(f, form, "Server access", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
		if (r != javax.swing.JOptionPane.OK_OPTION) return;
		try { c.save(new String(key.getPassword()), wake.getText()); note.setText("Saved"); }
		catch (IOException e) { note.setText("Couldn't save: " + e.getMessage()); }
	}

	// ------------------------------------------------------------------ what's shown

	private static Color colour(String state) {
		switch (state) {
			case "ok": return GREEN;
			case "updated": return BLUE;
			case "warn": return AMBER;
			case "failed": return RED;
			default: return GRAY;
		}
	}

	/** A coloured dot. */
	private static Icon dot(Color c) {
		return new Icon() {
			public int getIconWidth() { return 12; }
			public int getIconHeight() { return 12; }
			public void paintIcon(Component comp, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(c); g2.fillOval(x + 1, y + 1, 10, 10);
				g2.dispose();
			}
		};
	}

	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

	private static JComponent heading(String s) {
		JLabel l = new JLabel(s);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
		l.setBorder(BorderFactory.createEmptyBorder(10, 0, 3, 0));
		return l;
	}

	private static JComponent row(Color c, String label, String detail) {
		if (detail != null && !detail.isEmpty()) detail = Character.toUpperCase(detail.charAt(0)) + detail.substring(1);
		JLabel l = new JLabel("<html>" + esc(label) + (detail == null || detail.isEmpty() ? "" : "<font color='#6b7280'>&nbsp;&nbsp;" + esc(detail) + "</font>") + "</html>", dot(c), JLabel.LEFT);
		l.setIconTextGap(8);
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 14f));
		l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
		return l;
	}

	private static JComponent text(String s) {
		// (Swing's HTML widths come out about a third wider than given on a normal display.)
		JLabel l = new JLabel("<html><div style='width:410px'>" + esc(s) + "</div></html>");
		l.setFont(l.getFont().deriveFont(Font.PLAIN, 14f));
		l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
		return l;
	}

	private static JScrollPane scroller(List<Component> rows, boolean big) {
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		for (Component c : rows) { ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT); list.add(c); }
		list.add(Box.createVerticalGlue());
		JScrollPane sp = new JScrollPane(list);
		sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createLineBorder(new Color(0xD5D8DD)));
		Dimension want = list.getPreferredSize();
		sp.setPreferredSize(new Dimension(Math.max(560, Math.min(640, want.width + 30)), Math.min(big ? 420 : 240, want.height + 6)));
		return sp;
	}

	private static String plain(List<Component> rows) {
		StringBuilder sb = new StringBuilder();
		for (Component c : rows) if (c instanceof JLabel jl) sb.append(jl.getText().replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")).append('\n');
		return sb.toString();
	}

	private static void show(Path dir, String headline, String sub, List<Component> rows, List<Component> serverRows, boolean serverNews, int seconds, boolean big) throws Exception {
		Object lock = new Object();
		boolean[] done = {false};
		SwingUtilities.invokeAndWait(() -> {
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
			}
			JFrame f = new JFrame("October Journey");
			f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 10));
			p.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
			JPanel top = new JPanel(new BorderLayout(0, 4));
			JLabel h = new JLabel(headline);
			h.setFont(h.getFont().deriveFont(Font.BOLD, 18f));
			JLabel s = new JLabel(sub);
			s.setFont(s.getFont().deriveFont(14f));
			top.add(h, BorderLayout.NORTH);
			top.add(s, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			// Two tabs: this device (the pack's update) and the server.
			javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
			tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13f));
			List<Component> deviceRows = rows.isEmpty() ? List.of(text("Nothing to report.")) : rows;
			tabs.addTab("This device", scroller(deviceRows, big || serverNews));
			ServerControl control = new ServerControl(dir);
			Runnable[] hold = {() -> {}};   // set below: stops the countdown once the player uses a server control
			JPanel serverTab = new JPanel(new BorderLayout(0, 8));
			if (control.configured()) serverTab.add(controls(control, f, () -> hold[0].run(), System.getenv("OJ_POPUP_PNG") == null), BorderLayout.NORTH);
			serverTab.add(scroller(serverRows, big || serverNews), BorderLayout.CENTER);
			tabs.addTab(serverNews ? "Server (new)" : "Server", serverTab);
			p.add(tabs, BorderLayout.CENTER);
			JButton play = new JButton();
			play.setFont(play.getFont().deriveFont(Font.BOLD, 15f));
			JPanel bottom = new JPanel(new BorderLayout());
			// What the colours mean.
			JPanel legend = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			for (Object[] k : new Object[][] {{GREEN, "OK / added"}, {BLUE, "updated"}, {GRAY, "off / removed"}, {AMBER, "not checked"}, {RED, "failed"}}) {
				JLabel l = new JLabel((String) k[1], dot((Color) k[0]), JLabel.LEFT);
				l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
				l.setForeground(new Color(0x6B7280));
				legend.add(l);
			}
			bottom.add(legend, BorderLayout.WEST);
			bottom.add(play, BorderLayout.EAST);
			p.add(bottom, BorderLayout.SOUTH);
			// The button: "Next: server changes" while there's something new on the Server tab that hasn't been shown;
			// "Play" after. Each tab gets its own countdown: the first tab's (5 or 90 seconds), then 90 on the server's.
			int[] left = {seconds};
			boolean[] serverShown = {!serverNews};
			Runnable label = () -> play.setText((serverShown[0] ? "Play" : "Next: server changes") + "  (" + left[0] + ")");
			Runnable next = () -> {
				if (!serverShown[0]) { serverShown[0] = true; tabs.setSelectedIndex(1); left[0] = UPDATE_SECONDS; label.run(); }
				else f.dispose();
			};
			tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == 1 && !serverShown[0]) { serverShown[0] = true; left[0] = Math.max(left[0], UPDATE_SECONDS); label.run(); } });
			label.run();
			// Each second: the countdown, and a full repaint, so anything drawn over the window (an overlay, a notification)
			// never leaves a smear behind.
			Timer timer = new Timer(1000, e -> { if (--left[0] <= 0) next.run(); else { label.run(); f.repaint(); } });
			hold[0] = () -> { if (timer.isRunning()) { timer.stop(); serverShown[0] = true; play.setText("Play"); } };
			play.addActionListener(e -> next.run());
			// Enter or Space press the button; Escape starts the game straight away.
			for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE}) p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), "next");
			p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "play");
			p.getActionMap().put("next", new javax.swing.AbstractAction() {
				@Override public void actionPerformed(java.awt.event.ActionEvent e) { next.run(); }
			});
			p.getActionMap().put("play", new javax.swing.AbstractAction() {
				@Override public void actionPerformed(java.awt.event.ActionEvent e) { f.dispose(); }
			});
			f.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override public void windowClosed(java.awt.event.WindowEvent e) {
					timer.stop();
					synchronized (lock) { done[0] = true; lock.notifyAll(); }
				}
			});
			f.setContentPane(p);
			f.pack();
			// Testing: OJ_POPUP_PNG=<file> saves a picture of each tab (<file>, and <file> with "-server") and their text
			// in <file>.txt, instead of showing the window.
			String png = System.getenv("OJ_POPUP_PNG");
			if (png != null && !png.isEmpty()) {
				try {
					for (int i = 0; i < 2; i++) {
						tabs.setSelectedIndex(i);
						if (i == 1) { serverShown[0] = true; label.run(); }
						p.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics();
						p.paint(g);
						g.dispose();
						javax.imageio.ImageIO.write(img, "png", new java.io.File(i == 0 ? png : png.replaceAll("\\.png$", "") + "-server.png"));
						if (i == 0) Files.writeString(Path.of(png + ".txt"), f.getTitle() + "\n" + headline + "\n" + sub + "\n" + plain(deviceRows) + play.getText() + "\n", StandardCharsets.UTF_8);
						else Files.writeString(Path.of(png + ".txt"), "---- Server tab: " + tabs.getTitleAt(1) + "\n" + plain(serverRows) + play.getText() + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
					}
				} catch (IOException e) { e.printStackTrace(); }
				f.dispose();
				return;
			}
			f.setLocationRelativeTo(null);
			f.setAlwaysOnTop(true);
			f.setVisible(true);
			f.toFront();
			play.requestFocusInWindow();
			timer.start();
		});
		synchronized (lock) { while (!done[0]) lock.wait(); }
		System.exit(0);
	}

	// ------------------------------------------------------------------ mods, from packwiz-installer's records

	/** Same installed file: the same hash in packwiz-installer's record (the pack's file, and the jar it points to). */
	private static boolean same(Object a, Object b) {
		Map<String, Object> x = asMap(a), y = asMap(b);
		return String.valueOf(x.get("hash")).equals(String.valueOf(y.get("hash"))) && String.valueOf(x.get("linkedFileHash")).equals(String.valueOf(y.get("linkedFileHash")));
	}

	private static String jar(Object entry) {
		Object loc = asMap(entry).get("cachedLocation");
		if (loc == null) return null;
		String s = loc.toString();
		return s.substring(s.lastIndexOf('/') + 1).replaceAll("\\.jar$", "");
	}

	private static String modName(Map<String, Object> names, String key, Object entry) {
		Object n = names.get(key);
		if (n != null) return clean(n.toString());
		String j = jar(entry);
		return j != null ? j : key.substring(key.lastIndexOf('/') + 1).replaceAll("\\.(pw\\.toml|jar)$", "");
	}

	/** " (old-file → new-file)" when the jar's name changed, as it usually does with the version. */
	private static String versions(Object before, Object after) {
		String a = jar(before), b = jar(after);
		return a != null && b != null && !a.equals(b) ? " (" + a + " → " + b + ")" : "";
	}

	/** A mod's name without the emoji some titles carry (Jade's), which not every system's font can draw. */
	private static String clean(String name) {
		return name.replaceAll("[\\x{10000}-\\x{10FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]", "").trim();
	}

	private static String join(String... parts) {
		StringBuilder b = new StringBuilder();
		for (String p : parts) if (p != null) b.append(b.length() == 0 ? "" : ", ").append(p);
		return b.toString();
	}

	// ------------------------------------------------------------------ files

	private static List<Version> changelog(Path p) {
		List<Version> out = new ArrayList<>();
		Version cur = null;
		for (String l : read(p).split("\\R")) {
			if (l.startsWith("== ")) {
				String[] parts = l.substring(3).split("\\|", 2);
				cur = new Version(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "", new ArrayList<>());
				out.add(cur);
			} else if (cur != null && l.startsWith("- ")) {
				cur.notes().add(l.substring(2).trim());
			}
		}
		return out;
	}

	/** A top-level true/false setting in pack-options.json; on unless it says false. */
	private static boolean option(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
		return !m.find() || m.group(1).equals("true");
	}

	static Object parseJson(String s) { return parse(s); }
	static String readFile(Path p) { return read(p); }

	private static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("﻿", ""); } catch (IOException | RuntimeException e) { return ""; }
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }

	/** A small JSON reader (Java has none built in): objects, arrays, strings; other values come back as text. */
	private static Object parse(String s) {
		try { return s.isBlank() ? null : new Json(s).value(); } catch (RuntimeException e) { return null; }
	}

	private static final class Json {
		private final String s;
		private int i;
		Json(String s) { this.s = s; }
		Object value() {
			ws();
			char c = s.charAt(i);
			if (c == '{') {
				i++;
				Map<String, Object> m = new LinkedHashMap<>();
				ws();
				if (s.charAt(i) == '}') { i++; return m; }
				while (true) {
					ws(); String k = str(); ws(); i++; // ':'
					m.put(k, value()); ws();
					if (s.charAt(i++) == '}') return m;
				}
			}
			if (c == '[') {
				i++;
				List<Object> l = new ArrayList<>();
				ws();
				if (s.charAt(i) == ']') { i++; return l; }
				while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; }
			}
			if (c == '"') return str();
			int st = i;
			while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
			return s.substring(st, i);
		}
		String str() {
			i++;
			StringBuilder b = new StringBuilder();
			while (true) {
				char c = s.charAt(i++);
				if (c == '"') return b.toString();
				if (c != '\\') { b.append(c); continue; }
				char e = s.charAt(i++);
				switch (e) {
					case 'n': b.append('\n'); break;
					case 't': b.append('\t'); break;
					case 'r': b.append('\r'); break;
					case 'b': b.append('\b'); break;
					case 'f': b.append('\f'); break;
					case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
					default: b.append(e);
				}
			}
		}
		void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
	}
}
