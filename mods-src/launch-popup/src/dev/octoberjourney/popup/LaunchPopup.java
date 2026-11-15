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
 * with nothing new it shows the checks. Either way it waits for the player: only Play starts the game (closing the
 * window is Quit).
 */
public final class LaunchPopup {
	private static final int UPDATE_SECONDS = 90, QUIET_SECONDS = 5;
	private static final Color GREEN = new Color(0x2E9D48), BLUE = new Color(0x2F6FD6), GRAY = new Color(0x8A8F98),
			AMBER = new Color(0xD99A16), RED = new Color(0xD23C3C);

	record Version(String version, String date, List<String> notes) {}
	record Check(String state, String label, String detail) {}

	/** The progress window, reused for the summary so there's one window from start to finish. */
	static volatile JFrame reuse;
	/** What the player chose: 0 play, 10 quit (cancel the launch), 11 run the update again. The sync scripts act on it. */
	static volatile int exitCode = 0;
	static final int QUIT = 10, RESTART = 11;
	/** The update's steps, in the order the sync scripts do them (their "check" lines use these names). */
	static final String[] STEPS = {"Connection", "Mods and settings", "Mod switches", "Steam art and controller layouts", "Server list", "Update script"};

	public static void main(String[] args) throws Exception {
		// Testing and troubleshooting: java -jar launch-popup.jar --server <game folder> status|wake|start
		if (args.length >= 3 && args[0].equals("--server")) {
			ServerControl c = new ServerControl(Path.of(args[1]));
			switch (args[2]) {
				case "wake" -> System.out.println("wake signal sent to " + c.wake());
				case "start" -> { c.start(); System.out.println("start requested"); }
				case "stop" -> { c.stop(); System.out.println("stop requested"); }
				case "hello" -> System.out.println("hello: " + c.hello());
				case "mcss" -> System.out.println("mcss: " + c.mcssState());
				case "mcss-start" -> System.out.println("mcss start: " + c.mcssStart());
				case "mcss-close" -> System.out.println("mcss close: " + c.mcssClose(false));
				case "mcss-force-close" -> System.out.println("mcss force close: " + c.mcssClose(true));
				case "stop-watch" -> {   // stop, wait until it's off, and say whether the shutdown was smooth
					long t0 = System.currentTimeMillis(); c.stop(); int st = -1;
					while (System.currentTimeMillis() - t0 < 120_000) { Thread.sleep(2000); st = c.statusCode(); if (st == 0) break; }
					System.out.println(st != 0 ? "failed: didn't stop" : String.join(": ", shutdownVerdict(c, (System.currentTimeMillis() - t0) / 1000)));
				}
				case "signin" -> { c.signIn(args[3], args[4], c.wakeAddress); System.out.println("signed in as " + c.username + "; key saved"); }
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
		// Started by the sync at the beginning: the progress checklist, then (when the sync writes "done|<status>" to
		// pack-sync-status.txt) the summary in the same window.
		if (args.length >= 2 && args[0].equals("--progress")) { progress(Path.of(args[1])); System.exit(exitCode); }
		summary(Path.of(args.length > 0 ? args[0] : "."), args.length > 1 ? args[1] : "ok");
		System.exit(exitCode);
	}

	// ------------------------------------------------------------------ progress

	/** The checklist while the sync runs: each step waiting, running (with packwiz-installer's latest line for the
	 *  download), or done with a mark. Quit cancels the launch. Then the summary takes over the same window. */
	static void progress(Path dir) throws Exception {
		String options = read(dir.resolve("pack-options.json"));
		boolean ui = !GraphicsEnvironment.isHeadless() && (option(options, "popup_updates") || option(options, "popup_no_updates"));
		JLabel[] rows = new JLabel[STEPS.length];
		if (ui) SwingUtilities.invokeAndWait(() -> {
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
			}
			JFrame f = new JFrame("October Journey");
			f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
			JLabel h = new JLabel("Getting October Journey ready");
			h.setFont(h.getFont().deriveFont(Font.BOLD, 18f));
			JLabel s = new JLabel("Checking for updates before the game starts.");
			s.setFont(s.getFont().deriveFont(14f));
			JPanel top = new JPanel(new BorderLayout(0, 4));
			top.add(h, BorderLayout.NORTH); top.add(s, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			list.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xD5D8DD)), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
			for (int i = 0; i < STEPS.length; i++) {
				rows[i] = new JLabel(STEPS[i], mark("pending"), JLabel.LEFT);
				rows[i].setFont(rows[i].getFont().deriveFont(Font.PLAIN, 14f));
				rows[i].setIconTextGap(10);
				rows[i].setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
				rows[i].setPreferredSize(new Dimension(560, 26));
				list.add(rows[i]);
			}
			p.add(list, BorderLayout.CENTER);
			JButton quit = new JButton("Quit");
			quit.setFont(quit.getFont().deriveFont(14f));
			quit.addActionListener(e -> { exitCode = QUIT; f.dispose(); });
			JPanel bottom = new JPanel(new BorderLayout());
			JLabel hint = new JLabel("Quit cancels this launch.");
			hint.setForeground(new Color(0x6B7280));
			bottom.add(hint, BorderLayout.WEST);
			bottom.add(quit, BorderLayout.EAST);
			p.add(bottom, BorderLayout.SOUTH);
			f.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent e) { exitCode = QUIT; f.dispose(); }
			});
			f.setContentPane(p);
			f.pack();
			f.setLocationRelativeTo(null);
			f.setAlwaysOnTop(true);
			if (System.getenv("OJ_POPUP_PNG") == null) f.setVisible(true);   // (testing: pictures only)
			reuse = f;
		});
		String png = System.getenv("OJ_POPUP_PNG");
		boolean[] pictured = {false};
		long started = System.currentTimeMillis();
		String done = null;
		int tick = 0;
		while (done == null) {
			if (reuse != null && !reuse.isDisplayable()) return;   // quit
			Map<String, String[]> got = new LinkedHashMap<>();
			for (String l : read(dir.resolve("pack-sync-status.txt")).split("\\R")) {
				String[] f = l.split("\\|", 4);
				if (f[0].equals("check") && f.length >= 3) got.put(f[2], new String[] {f[1], f.length > 3 ? f[3] : ""});
				else if (f[0].equals("done")) done = f.length > 1 ? f[1] : "ok";
			}
			if (System.currentTimeMillis() - started > 25 * 60_000) done = "failed";   // the sync never said it finished
			if (ui) {
				String last = lastLine(dir.resolve("pack-sync-java.log"));
				int running = -1;
				for (int i = 0; i < STEPS.length; i++) if (!got.containsKey(STEPS[i])) { running = i; break; }
				int r = running, k = tick++;
				SwingUtilities.invokeLater(() -> {
					for (int i = 0; i < STEPS.length; i++) {
						String[] g = got.get(STEPS[i]);
						if (g != null) { rows[i].setIcon(mark(g[0])); rows[i].setText(html(STEPS[i], g[1])); }
						else if (i == r) {
							String detail = i == 0 ? "Reaching the pack (Tailscale, then GitHub)" : i == 1 ? (last.isEmpty() ? "Checking the pack's files" : last) : "";
							rows[i].setIcon(mark("running" + (k % 4))); rows[i].setText(html(STEPS[i], detail));
						} else { rows[i].setIcon(mark("pending")); rows[i].setText(STEPS[i]); }
					}
					// Testing: a picture of the checklist while the mods step runs, beside the summary's pictures.
					if (png != null && !pictured[0] && r >= 1) {
						pictured[0] = true;
						try {
							JComponent cp = (JComponent) reuse.getContentPane();
							cp.validate();
							java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(cp.getWidth(), cp.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
							java.awt.Graphics2D g = img.createGraphics(); cp.paint(g); g.dispose();
							javax.imageio.ImageIO.write(img, "png", new java.io.File(png.replaceAll("\\.png$", "") + "-progress.png"));
						} catch (IOException ignored) {}
					}
				});
			}
			if (done == null) Thread.sleep(300);
		}
		summary(dir, done);
	}

	/** Ten pips for the connection: how many are lit follows the ping (more is better), coloured from red (first) to
	 *  green (last); unlit pips are grey. -1: no reading. */
	static final class PipMeter extends JComponent {
		private int lit = 0;
		PipMeter() { setPreferredSize(new Dimension(10 * 11 + 2, 16)); setToolTipText("Connection to the game server: the more pips, the better"); }
		/** Pips for a ping in ms: 25 or less lights all ten, each band after that one fewer, down to one. */
		void set(int ms) {
			int[] bands = {25, 40, 60, 80, 100, 130, 170, 230, 320, 500};
			int n = 0;
			if (ms >= 0) { n = 1; for (int i = 0; i < bands.length; i++) if (ms <= bands[i]) { n = 10 - i; break; } }
			lit = n;
			repaint();
		}
		@Override protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			for (int i = 0; i < 10; i++) {
				g2.setColor(i < lit ? Color.getHSBColor(i / 9f * 0.33f, 0.78f, 0.86f) : new Color(0xD5D8DD));
				g2.fillRoundRect(1 + i * 11, 2, 8, 12, 3, 3);
			}
			g2.dispose();
		}
	}

	/** Text for a message line, wrapped to the window's width. */
	static String wrap(String s) { return "<html><div style='width:430px'>" + esc(s) + "</div></html>"; }

	private static String html(String label, String detail) {
		if (detail == null || detail.isEmpty()) return label;
		String d = detail.length() > 70 ? detail.substring(0, 67) + "..." : detail;
		return "<html>" + esc(label) + "<font color='#6b7280'>&nbsp;&nbsp;" + esc(Character.toUpperCase(d.charAt(0)) + d.substring(1)) + "</font></html>";
	}

	/** The last line packwiz-installer wrote (its progress, such as "(12/28) Downloaded ..."). */
	private static String lastLine(Path p) {
		String[] ls = read(p).split("\\R");
		for (int i = ls.length - 1; i >= 0; i--) if (!ls[i].isBlank()) return ls[i].strip();
		return "";
	}

	/** A step's mark: waiting (hollow), running (a turning dot), done (tick), updated (blue tick), off (dash),
	 *  not checked (!), failed (cross). */
	static Icon mark(String state) {
		return new Icon() {
			public int getIconWidth() { return 18; }
			public int getIconHeight() { return 18; }
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
				if (state.equals("pending")) { g2.setColor(GRAY); g2.drawOval(x + 2, y + 2, 14, 14); g2.dispose(); return; }
				if (state.startsWith("running")) {
					int k = state.length() > 7 ? state.charAt(7) - '0' : 0;
					g2.setColor(new Color(0xE5E7EB)); g2.drawOval(x + 2, y + 2, 14, 14);
					g2.setColor(AMBER); g2.drawArc(x + 2, y + 2, 14, 14, 90 - k * 90, -120);
					g2.dispose(); return;
				}
				g2.setColor(colour(state)); g2.fillOval(x + 1, y + 1, 16, 16);
				g2.setColor(Color.WHITE);
				switch (state) {
					case "ok", "updated" -> { g2.drawLine(x + 5, y + 9, x + 8, y + 12); g2.drawLine(x + 8, y + 12, x + 13, y + 6); }
					case "failed" -> { g2.drawLine(x + 6, y + 6, x + 12, y + 12); g2.drawLine(x + 12, y + 6, x + 6, y + 12); }
					case "warn" -> { g2.drawLine(x + 9, y + 5, x + 9, y + 10); g2.drawLine(x + 9, y + 13, x + 9, y + 13); }
					default -> g2.drawLine(x + 6, y + 9, x + 12, y + 9);
				}
				g2.dispose();
			}
		};
	}

	// ------------------------------------------------------------------ the summary

	static void summary(Path dir, String status) throws Exception {
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
		if (!wanted || GraphicsEnvironment.isHeadless()) { JFrame r = reuse; if (r != null) SwingUtilities.invokeAndWait(r::dispose); return; }

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
		rows.add(heading("What the server runs (as last recorded)"));
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

	/** The Server controls tab (whether the server answers, checked every few seconds; wake, start, stop; how the last
	 *  stop went) and the Server config tab (the panel in a browser, this device's access). Returns both. */
	private static JComponent[] controls(ServerControl c, JFrame f, Runnable hold, boolean live, java.util.function.Consumer<String[]> also) {
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JLabel status = new JLabel("Checking the server\u2026", dot(GRAY), JLabel.LEFT);
		status.setFont(status.getFont().deriveFont(Font.BOLD, 14f));
		status.setIconTextGap(8);
		JLabel note = new JLabel(" ");
		note.setFont(note.getFont().deriveFont(Font.PLAIN, 12f));
		note.setForeground(new Color(0x6B7280));
		// How the last stop went: watched after Stop until the server is off, then the log is read for the save.
		JLabel stopped = new JLabel(" ");
		stopped.setFont(stopped.getFont().deriveFont(Font.PLAIN, 13f));
		stopped.setIconTextGap(8);
		JButton wake = new JButton("Wake server device"), start = new JButton("Start server"), stop = new JButton("Stop server");
		wake.setEnabled(false);   // (until the first reading: only while the PC isn't answering)
		start.setEnabled(false);
		stop.setEnabled(false);
		// Ping (from Minecraft's own server ping) as ten pips from red to green, then the players connected.
		PipMeter meter = new PipMeter();
		JLabel pingText = new JLabel("\u2014"), players = new JLabel("Players: \u2014");
		for (JLabel l : new JLabel[] {pingText, players}) l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
		JLabel pingLabel = new JLabel("Ping");
		pingLabel.setFont(pingLabel.getFont().deriveFont(Font.PLAIN, 13f));
		JPanel pingRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 2));
		pingRow.add(pingLabel); pingRow.add(meter); pingRow.add(pingText);
		JPanel playersRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
		playersRow.add(players);
		long[] wokeAt = {0};
		java.util.function.Consumer<String[]> apply = s -> {
			boolean waking = !s[0].equals("ok") && wokeAt[0] > 0 && System.currentTimeMillis() - wokeAt[0] < 180_000;
			also.accept(s);   // (the Server tab's live line, from the same reading)
			{
				status.setIcon(dot(waking && !s[1].contains("awake") ? AMBER : colour(s[0])));
				status.setText("<html><div style='width:430px'>" + esc(waking && !s[1].contains("awake") ? "Waking the server PC\u2026 (it can take a minute)" : s[1]) + "</div></html>");
				// Start only when the server is off (and the PC awake); Stop only while it runs; neither mid-change.
				String phase = s.length > 2 ? s[2] : "";
				start.setEnabled(c.hasKey() && phase.equals("off"));
				stop.setEnabled(c.hasKey() && phase.equals("online"));
				// Wake only while the PC isn't answering at all (neither the game server nor the panel): asleep or off.
				wake.setEnabled(c.canWake() && phase.equals("asleep"));
				wake.setToolTipText(phase.equals("asleep") ? "Send the wake signal (works from the home network)" : "The server PC is awake");
				String ping = s.length > 3 ? s[3] : "";
				int ms = -1;
				try { ms = Integer.parseInt(ping); } catch (NumberFormatException ignored) {}
				meter.set(ms);
				pingText.setText(ms < 0 ? "\u2014" : ms + " ms");
				players.setText("Players: " + (s.length > 4 && !s[4].isEmpty() ? s[4] + " online" : "\u2014"));
			}
		};
		Runnable refresh = () -> new Thread(() -> { String[] s = c.status(); SwingUtilities.invokeLater(() -> apply.accept(s)); }, "server-status").start();
		if (!live) apply.accept(c.status());   // (test pictures: one reading, so they show real values)
		java.util.function.BiConsumer<String, String> stopResult = (state, text) -> SwingUtilities.invokeLater(() -> {
			stopped.setIcon(state.equals("running") ? mark("running0") : mark(state));
			stopped.setText("<html><div style='width:430px'>" + esc(text) + "</div></html>");
		});
		wake.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				String msg;
				try { msg = "Wake signal sent (" + c.wake().size() + " addresses)" + (c.wakeAddress.isEmpty() ? ": works from the home network" : ", including your internet wake address"); wokeAt[0] = System.currentTimeMillis(); }
				catch (IOException | RuntimeException ex) { msg = "Couldn't send the wake signal: " + ex.getMessage(); }
				String m = msg;
				SwingUtilities.invokeLater(() -> note.setText(wrap(m)));
				refresh.run();
			}, "wake").start();
		});
		start.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				String msg;
				try { c.start(); msg = "Start requested: the server takes a minute or so to come up"; }
				catch (ServerControl.HttpError ex) { msg = ex.code == 401 || ex.code == 403 ? "Your access isn't allowed to start the server (ask the owner)" : "The panel said no (" + ex.getMessage() + ")"; }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the panel: is the server PC awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> note.setText(wrap(m)));
				refresh.run();
			}, "start").start();
		});
		stop.addActionListener(e -> {
			hold.run();
			// Stopping disconnects everyone playing: asked first.
			int ok = javax.swing.JOptionPane.showConfirmDialog(f, "Stop the server? Anyone playing is disconnected (the world is saved first).",
					"Stop server", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (ok != javax.swing.JOptionPane.OK_OPTION) return;
			new Thread(() -> {
				long t0 = System.currentTimeMillis();
				try { c.stop(); }
				catch (ServerControl.HttpError ex) { stopResult.accept("failed", ex.code == 401 || ex.code == 403 ? "Your access isn't allowed to stop the server (ask the owner)" : "The panel said no (" + ex.getMessage() + ")"); return; }
				catch (IOException | RuntimeException ex) { stopResult.accept("failed", "Couldn't reach the panel: is the server PC awake?"); return; }
				stopResult.accept("running", "Stopping: saving the world and shutting down\u2026");
				refresh.run();
				// Watch until the server is off (2 minutes at most), then read the end of its log for the save.
				int st = -1;
				while (System.currentTimeMillis() - t0 < 120_000) {
					try { Thread.sleep(2000); } catch (InterruptedException ex) { return; }
					st = c.statusCode();
					if (st == 0) break;
				}
				refresh.run();
				long secs = (System.currentTimeMillis() - t0) / 1000;
				if (st != 0) { stopResult.accept("failed", "The server didn't stop within 2 minutes: check it in the server panel"); return; }
				String[] verdict = shutdownVerdict(c, secs);
				stopResult.accept(verdict[0], verdict[1]);
			}, "stop").start();
		});
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
		for (JButton b : new JButton[] {wake, start, stop}) { b.setFont(b.getFont().deriveFont(13f)); buttons.add(b); }
		for (JComponent x : new JComponent[] {status, pingRow, playersRow, buttons, note, stopped}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); box.add(x); box.add(Box.createVerticalStrut(4)); }

		// MCSS itself (the server software), through the host helper on the server PC: allowed per launcher by the owner.
		JLabel mcssHead = new JLabel("MCSS (the server software)");
		mcssHead.setFont(mcssHead.getFont().deriveFont(Font.BOLD, 13f));
		mcssHead.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
		JLabel mcss = new JLabel("Checking\u2026", dot(GRAY), JLabel.LEFT);
		mcss.setFont(mcss.getFont().deriveFont(Font.PLAIN, 13f));
		mcss.setIconTextGap(8);
		JButton mStart = new JButton("Start MCSS"), mClose = new JButton("Close MCSS");
		boolean[] forceMode = {false};
		mStart.setEnabled(false); mClose.setEnabled(false);
		JLabel mNote = new JLabel(" ");
		mNote.setFont(mNote.getFont().deriveFont(Font.PLAIN, 12f));
		mNote.setForeground(new Color(0x6B7280));
		Runnable mcssRefresh = () -> new Thread(() -> {
			String state, text, mode = "close"; boolean granted = false, running = false;
			try {
				Map<String, Object> r = c.mcssState();
				state = s(r.get("state"));
				running = "true".equals(s(r.get("mcss")));   // (this reader gives true/false back as text)
				granted = state.equals("granted");
				if (s(r.get("close")).equals("force")) mode = "force";
				if (granted) text = running ? "MCSS is running" : "MCSS isn't running";
				else text = "Waiting for the owner to allow this device (in the host panel on the server PC)";
				state = granted ? (running ? "ok" : "off") : "warn";
			} catch (IOException | RuntimeException e) { state = "off"; text = "The host helper isn't answering (the server PC may be asleep or signed out)"; }
			String st = state, tx = text, md = mode; boolean g = granted, run = running;
			SwingUtilities.invokeLater(() -> {
				// The button says what it will do: MCSS's X button either exits it (Close) or only hides it (Force close).
				forceMode[0] = md.equals("force");
				mClose.setText(forceMode[0] ? "Force close MCSS" : "Close MCSS");
				mClose.setToolTipText(forceMode[0] ? "MCSS's X button is set to hide it, so closing it means ending it (the Minecraft server must be stopped first)"
						: "Closes MCSS properly, like its X button (the Minecraft server must be stopped first)");
				mcss.setIcon(dot(colour(st)));
				mcss.setText("<html><div style='width:430px'>" + esc(tx) + "</div></html>");
				mStart.setEnabled(g && !run);
				mClose.setEnabled(g && run);
			});
		}, "mcss-status").start();
		java.util.function.Function<Boolean, Runnable> mcssAct = startIt -> () -> {
			hold.run();
			boolean force = forceMode[0];
			if (!startIt && javax.swing.JOptionPane.showConfirmDialog(f, force
					? "Force close MCSS on the server PC? Its X button is set to hide it, so it will be ended rather than closed normally. (Only once the Minecraft server is stopped.)"
					: "Close MCSS on the server PC? It closes normally, like its X button. (Only once the Minecraft server is stopped.)",
					force ? "Force close MCSS" : "Close MCSS", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
			mNote.setText(startIt ? "Starting MCSS\u2026" : "Closing MCSS\u2026");
			new Thread(() -> {
				String msg;
				try { Map<String, Object> r = startIt ? c.mcssStart() : c.mcssClose(force); msg = s(r.get("message")).isEmpty() ? s(r.get("error")) : s(r.get("message")); }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the host helper: is the server PC awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> mNote.setText(wrap(m)));
				mcssRefresh.run();
				refresh.run();
			}, "mcss-act").start();
		};
		mStart.addActionListener(e -> mcssAct.apply(true).run());
		mClose.addActionListener(e -> mcssAct.apply(false).run());
		if (c.hasHelper()) {
			JPanel mButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
			for (JButton b : new JButton[] {mStart, mClose}) { b.setFont(b.getFont().deriveFont(13f)); mButtons.add(b); }
			for (JComponent x : new JComponent[] {mcssHead, mcss, mButtons, mNote}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); box.add(x); box.add(Box.createVerticalStrut(2)); }
		}

		// Server config: the panel in a browser, and this device's access.
		JPanel cfg = new JPanel();
		cfg.setLayout(new BoxLayout(cfg, BoxLayout.Y_AXIS));
		cfg.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JLabel cfgNote = new JLabel(" ");
		cfgNote.setForeground(new Color(0x6B7280));
		JLabel who = new JLabel();
		who.setFont(who.getFont().deriveFont(Font.PLAIN, 13f));
		who.setIconTextGap(8);
		Runnable showWho = () -> {
			who.setIcon(dot(c.hasKey() ? GREEN : GRAY));
			who.setText(c.hasKey() ? (c.username.isEmpty() ? "Panel access: an API key is saved on this device" : "Panel access: signed in as " + c.username)
					: "Panel access: not set up (needed for Start and Stop)");
		};
		showWho.run();
		JButton panel = new JButton("Open server panel"), access = new JButton("Server access\u2026");
		panel.setEnabled(!c.panelUrl.isEmpty());
		panel.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				boolean opened = c.openPanel();
				SwingUtilities.invokeLater(() -> cfgNote.setText(wrap(opened ? "Opened the server panel in your browser (sign in there)" : "Open " + c.panelUrl + " in a browser")));
			}, "panel").start();
		});
		access.addActionListener(e -> { hold.run(); accessDialog(c, f, cfgNote); showWho.run(); refresh.run(); });
		JPanel cfgButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
		for (JButton b : new JButton[] {panel, access}) { b.setFont(b.getFont().deriveFont(13f)); cfgButtons.add(b); }
		JLabel where = new JLabel("Server panel: " + (c.panelUrl.isEmpty() ? "not set" : c.panelUrl));
		where.setForeground(new Color(0x6B7280));
		for (JComponent x : new JComponent[] {who, where, cfgButtons, cfgNote}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); cfg.add(x); cfg.add(Box.createVerticalStrut(4)); }

		if (live) {
			refresh.run();
			if (c.hasHelper()) new Thread(() -> { try { c.hello(); } catch (IOException | RuntimeException ignored) {} mcssRefresh.run(); }, "hello").start();
			// Every few seconds while the window is open.
			Timer poll = new Timer(4000, e -> { if (f.isDisplayable()) { refresh.run(); if (c.hasHelper()) mcssRefresh.run(); } });
			poll.start();
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { poll.stop(); } });
		}
		return new JComponent[] {box, cfg};
	}

	/** After a stop: was the world saved? The end of the server's log (through the panel's console) should show
	 *  "Stopping server", "Saving worlds" and a chunk save for each dimension, with no errors after the stop began. */
	static String[] shutdownVerdict(ServerControl c, long secs) {
		List<String> log;
		try { log = c.console(80); }
		catch (IOException e) { return new String[] {"warn", "The server stopped (after " + secs + " s), but its log couldn't be read to confirm the world was saved"}; }
		int from = -1;
		for (int i = log.size() - 1; i >= 0; i--) if (log.get(i).contains("Stopping server")) { from = i; break; }
		if (from < 0) return new String[] {"warn", "The server stopped (after " + secs + " s), but its log doesn't show a normal shutdown"};
		boolean worlds = false; int dims = 0, errors = 0;
		for (int i = from; i < log.size(); i++) {
			String l = log.get(i);
			if (l.contains("Saving worlds")) worlds = true;
			if (l.contains("Saving chunks for level")) dims++;
			if (l.contains("/ERROR]") || l.contains("Exception")) errors++;
		}
		if (!worlds || dims == 0) return new String[] {"warn", "The server stopped (after " + secs + " s), but its log doesn't show the world being saved"};
		if (errors > 0) return new String[] {"warn", "Shut down and saved the world (" + dims + " dimensions, " + secs + " s), with " + errors + " error line" + (errors > 1 ? "s" : "") + " in the log (see the panel's console)"};
		return new String[] {"ok", "Shut down smoothly: the world was saved (" + dims + " dimensions) and the server stopped after " + secs + " s"};
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
		// Easiest: sign in with your panel account; the pop-up keeps the key the panel hands back (never the password).
		javax.swing.JTextField user = new javax.swing.JTextField(c.username, 18);
		javax.swing.JPasswordField pass = new javax.swing.JPasswordField(18);
		JButton signIn = new JButton("Sign in");
		signIn.addActionListener(e -> {
			signIn.setEnabled(false);
			pasteNote.setText("Signing in...");
			String u = user.getText(), pw = new String(pass.getPassword()), w = wake.getText();
			new Thread(() -> {
				String msg;
				try { c.signIn(u, pw, w); msg = "Signed in as " + u.trim() + ". You can close this window."; }
				catch (ServerControl.HttpError ex) { msg = ex.code == 401 || ex.code == 403 ? "The panel didn't accept that username and password." : "The panel said no (" + ex.getMessage() + ")."; }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the panel: is the server PC awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> { pasteNote.setText(m); key.setText(c.apiKey); signIn.setEnabled(true); pass.setText(""); });
			}, "sign-in").start();
		});
		JPanel signRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
		signRow.add(new JLabel("Username")); signRow.add(user); signRow.add(new JLabel("Password")); signRow.add(pass); signRow.add(signIn);
		JLabel signedIn = new JLabel(c.username.isEmpty() ? " " : "Signed in as " + c.username + " (sign in again if the panel stops accepting it).");
		signedIn.setForeground(new Color(0x6B7280));
		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		JLabel h1 = new JLabel("Sign in with your server panel account");
		h1.setFont(h1.getFont().deriveFont(Font.BOLD));
		JLabel h2 = new JLabel("Or use an API key made for you in the panel (under API Keys)");
		h2.setFont(h2.getFont().deriveFont(Font.BOLD));
		for (JComponent x : new JComponent[] {
				text("Saved on this device only (server-access.json in the game folder). Your password isn't saved."),
				h1, signRow, signedIn, h2, keyRow,
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
			JFrame f = reuse != null ? reuse : new JFrame("October Journey");
			for (java.awt.event.WindowListener l : f.getWindowListeners()) f.removeWindowListener(l);
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
			// The Server tab: a live line on top (whether the server answers right now; the rest of the tab is the last
			// published record of what it runs, so it reads the same with the server down), then that record.
			JLabel liveLine = new JLabel("Checking the server…", dot(GRAY), JLabel.LEFT);
			liveLine.setFont(liveLine.getFont().deriveFont(Font.BOLD, 13f));
			liveLine.setIconTextGap(8);
			liveLine.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
			JPanel serverTab = new JPanel(new BorderLayout());
			if (control.configured()) serverTab.add(liveLine, BorderLayout.NORTH);
			serverTab.add(scroller(serverRows, big || serverNews), BorderLayout.CENTER);
			tabs.addTab(serverNews ? "Server (new)" : "Server", serverTab);
			java.util.function.Consumer<String[]> live = r -> {
				String phase = r.length > 2 ? r[2] : "", ping = r.length > 3 ? r[3] : "", pl = r.length > 4 ? r[4] : "";
				String text;
				switch (phase) {
					case "online" -> text = "Right now: online" + (ping.isEmpty() ? "" : ", " + ping + " ms") + (pl.isEmpty() ? "" : ", " + pl + " players");
					case "starting" -> text = "Right now: starting";
					case "stopping" -> text = "Right now: stopping";
					case "off" -> text = "Right now: the server PC is awake, but the Minecraft server is off (Server controls)";
					case "asleep" -> text = "Right now: the server PC isn't answering, so it may be asleep or off (Server controls, Wake server device). Below is the server as last recorded.";
					default -> text = "Right now: " + r[1];
				}
				liveLine.setIcon(dot(colour(r[0])));
				liveLine.setText("<html><div style='width:470px'>" + esc(text) + "</div></html>");
			};
			// Server controls, a tab of their own: status, wake, start, stop, the panel, and this device's access.
			if (control.configured()) {
				JComponent[] parts = controls(control, f, () -> hold[0].run(), System.getenv("OJ_POPUP_PNG") == null, live);
				for (int i = 0; i < 2; i++) {
					JPanel tab = new JPanel(new BorderLayout());
					tab.setBorder(BorderFactory.createLineBorder(new Color(0xD5D8DD)));
					JPanel inner = new JPanel(new BorderLayout());
					inner.add(parts[i], BorderLayout.NORTH);
					JScrollPane sc = new JScrollPane(inner);
					sc.setBorder(null);
					sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
					sc.getVerticalScrollBar().setUnitIncrement(16);
					tab.add(sc, BorderLayout.CENTER);
					tabs.addTab(i == 0 ? "Server controls" : "Server config", tab);
				}
			}
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
			// Beside Play: run the update again, or don't start the game.
			JButton again = new JButton("Restart"), quit = new JButton("Quit");
			again.setToolTipText("Run the update again");
			quit.setToolTipText("Don't start the game");
			again.addActionListener(e -> { exitCode = RESTART; f.dispose(); });
			quit.addActionListener(e -> { exitCode = QUIT; f.dispose(); });
			JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			for (JButton b : new JButton[] {again, quit}) { b.setFont(b.getFont().deriveFont(14f)); right.add(b); }
			right.add(play);
			bottom.add(right, BorderLayout.EAST);
			p.add(bottom, BorderLayout.SOUTH);
			// The button: "Next: server changes" while there's something new on the Server tab that hasn't been shown;
			// "Play" after. Nothing happens on its own: no countdown, the game starts only when the player presses Play.
			boolean[] serverShown = {!serverNews};
			Runnable label = () -> play.setText(serverShown[0] ? "Play" : "Next: server changes");
			Runnable next = () -> {
				if (!serverShown[0]) { serverShown[0] = true; tabs.setSelectedIndex(1); label.run(); }
				else { exitCode = 0; f.dispose(); }
			};
			tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == 1 && !serverShown[0]) { serverShown[0] = true; label.run(); } });
			label.run();
			// A full repaint every second, so anything drawn over the window (an overlay, a notification) never leaves a
			// smear behind.
			Timer timer = new Timer(1000, e -> f.repaint());
			hold[0] = () -> { };
			play.addActionListener(e -> next.run());
			// Enter or Space press the button. Closing the window is Quit: only Play starts the game.
			for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE}) p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), "next");
			p.getActionMap().put("next", new javax.swing.AbstractAction() {
				@Override public void actionPerformed(java.awt.event.ActionEvent e) { next.run(); }
			});
			f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			f.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent e) { exitCode = QUIT; f.dispose(); }
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
				Path choice = dir.resolve("test-popup-choice.txt");
				String ch = read(choice).trim();
				if (!ch.isEmpty()) { try { Files.delete(choice); } catch (IOException ignored) {} exitCode = ch.equals("quit") ? QUIT : ch.equals("restart") ? RESTART : 0; }
				try {
					for (int i = 2; i < tabs.getTabCount(); i++) {   // Server controls and Server config too, as <file>-controls.png, -config.png
						tabs.setSelectedIndex(i);
						p.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
						javax.imageio.ImageIO.write(img, "png", new java.io.File(png.replaceAll("\\.png$", "") + (i == 2 ? "-controls.png" : "-config.png")));
					}
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
