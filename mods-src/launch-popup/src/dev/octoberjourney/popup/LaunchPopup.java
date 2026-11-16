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
	/** The name this launcher shows: the server profile's (extras/profile.json, shipped with the pack; see
	 *  docs/profiles.md). Until that file has arrived (the first launch after an update that brings it), the name of the
	 *  pack's first server in extras/servers.json, which older packs have too. Nothing about a server is fixed here. */
	/** This tool's own name (the launcher, the installers and Gatehouse Keeper are Gatehouse; the pack and its server
	 *  have the profile's name). */
	static final String TOOL = "Gatehouse";
	static String NAME = "Minecraft";
	static void loadProfile(Path dir) {
		String n = s(asMap(parse(read(dir.resolve("extras/profile.json")))).get("name"));
		if (n.isEmpty() && parse(read(dir.resolve("extras/servers.json"))) instanceof List<?> l && !l.isEmpty()) n = s(asMap(l.get(0)).get("name"));
		if (!n.isEmpty()) NAME = n;
	}

	/** Brings the window to the front when it opens (it's started by Prism Launcher, behind whose window it could
	 *  otherwise open), without keeping it above other windows afterwards: it is on top for a moment only, so anything
	 *  the player switches to covers it like any other window. */
	static void bringForwardOnce(JFrame f) {
		f.setAlwaysOnTop(true);
		f.toFront();
		f.requestFocus();
		Timer t = new Timer(600, e -> f.setAlwaysOnTop(false));
		t.setRepeats(false);
		t.start();
	}

	static volatile JFrame reuse;
	/** The question before a download (how big the update is; Download or Skip this time): being asked now, and the
	 *  size that was skipped, if it was. */
	static volatile boolean asking;
	static volatile String skipped;
	static JPanel askPanel;
	static JLabel askText;
	/** The checklist's Play button: greyed out while the update runs; lit, and reading "Download", while the question
	 *  before a download is up (pressing it accepts). */
	static JButton progressPlay;
	/** What the player chose: 0 play, 10 quit (cancel the launch), 11 run the update again. The sync scripts act on it. */
	static volatile int exitCode = 0;
	static final int QUIT = 10, RESTART = 11;
	/** The launcher fetched a newer copy of itself (launch-popup.jar.new beside it): the sync swaps it in and opens it. */
	static final int RELAUNCH = 12;
	/** The update's steps, in the order the sync scripts do them (their "check" lines use these names). */
	static final String[] STEPS = {"Connection", "Mods and settings", "Mod switches", "Steam art and controller layouts", "Server list", "Update script"};

	public static void main(String[] args) throws Exception {
		// The look, before anything is built (rows are made before their window is).
		if (!GraphicsEnvironment.isHeadless() && !(args.length >= 1 && args[0].equals("--server"))) dev.gatehouse.look.SteamLook.apply();
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
				case "pc-sleep" -> System.out.println("pc sleep: " + c.sleepPc());
				case "steam-update" -> { System.out.println("Steam running: " + SteamUpdate.steamRunning()); for (String l : SteamUpdate.run(Path.of(args[1]))) System.out.println(l); }
				case "pair" -> System.out.println("pair: " + String.join(": ", c.pair(args.length > 3 ? args[3] : "")) + "  [keeper " + c.helperLabel() + ", id " + c.deviceId.substring(0, 8) + "]");
				case "request" -> System.out.println("request: " + c.request(args.length > 3 ? args[3] : "server", args.length > 4 ? args[4] : ""));
				case "connection" -> System.out.print(Connection.text(Connection.run(Path.of(args[1]), c)));
				case "admin-devices" -> System.out.println("admin devices: " + c.helper("GET", "/v1/admin/devices", null));
				case "admin-perm" -> System.out.println("admin perm: " + c.helper("POST", "/v1/admin/perm", "{\"id\":\"" + args[3] + "\",\"perm\":\"" + args[4] + "\",\"state\":\"" + args[5] + "\"}"));
				case "admin-forget" -> System.out.println("admin forget: " + c.helper("POST", "/v1/admin/forget", "{\"id\":\"" + args[3] + "\"}"));
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
		if (args.length >= 3 && args[0].equals("--size")) {   // testing: what an update from this pack address would download
			long[] n = pending(Path.of(args[1]), fetch(args[2] + "/update-sizes.json"));
			System.out.println("this launcher: " + sha256(Files.readAllBytes(Path.of(LaunchPopup.class.getProtectionDomain().getCodeSource().getLocation().toURI()))).substring(0, 12));
			System.out.println(n == null ? "unknown" : sizeText(n[0]) + " (" + n[0] + " bytes), " + n[1] + " files, " + n[2] + " mods" + (n[3] == 1 ? ", first install" : ""));
			return;
		}
		if (args.length >= 2 && args[0].equals("--progress")) { progress(Path.of(args[1])); System.exit(exitCode); }
		if (args.length >= 2 && args[0].equals("--home")) { home(args); System.exit(0); }
		summary(Path.of(args.length > 0 ? args[0] : "."), args.length > 1 ? args[1] : "ok");
		System.exit(exitCode);
	}

	// ------------------------------------------------------------------ progress

	/** The checklist while the sync runs: each step waiting, running (with packwiz-installer's latest line for the
	 *  download), or done with a mark. Quit cancels the launch. Then the summary takes over the same window. */
	static void progress(Path dir) throws Exception {
		loadProfile(dir);
		String options = read(dir.resolve("pack-options.json"));
		boolean ui = !GraphicsEnvironment.isHeadless() && (option(options, "popup_updates") || option(options, "popup_no_updates"));
		JLabel[] rows = new JLabel[STEPS.length];
		if (ui) SwingUtilities.invokeAndWait(() -> {
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = new JFrame(TOOL + " \u2013 " + NAME);
			f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
			JLabel h = new JLabel("Getting " + NAME + " ready");
			h.setFont(h.getFont().deriveFont(Font.BOLD, 18f));
			JLabel s = new JLabel("Checking for updates before the game starts.");
			s.setFont(s.getFont().deriveFont(14f));
			JPanel top = new JPanel(new BorderLayout(0, 4));
			top.add(h, BorderLayout.NORTH); top.add(s, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			list.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
			for (int i = 0; i < STEPS.length; i++) {
				rows[i] = new JLabel(STEPS[i], mark("pending"), JLabel.LEFT);
				rows[i].setFont(rows[i].getFont().deriveFont(Font.PLAIN, 14f));
				rows[i].setIconTextGap(10);
				rows[i].setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
				rows[i].setPreferredSize(new Dimension(560, 26));
				list.add(rows[i]);
			}
			p.add(list, BorderLayout.CENTER);
			JButton quit = new PlayButton("Quit", QUIT_RED, Color.WHITE, 14f);
			quit.addActionListener(e -> { exitCode = QUIT; f.dispose(); });
			JPanel bottom = new JPanel(new BorderLayout());
			JLabel hint = new JLabel("Quit cancels this launch.");
			hint.setForeground(new Color(0x8F98A0));
			bottom.add(hint, BorderLayout.WEST);
			progressPlay = new PlayButton("Play");
			progressPlay.setEnabled(false);
			JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			right.add(quit); right.add(progressPlay);
			bottom.add(right, BorderLayout.EAST);
			// The size question, shown above the bottom row when the sync asks before downloading: the Play button
			// becomes "Download" and accepts it; Quit cancels the launch.
			askText = new JLabel(" ");
			askText.setFont(askText.getFont().deriveFont(Font.PLAIN, 14f));
			askPanel = new JPanel(new BorderLayout(0, 2));
			askPanel.add(askText, BorderLayout.NORTH);
			askPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
			askPanel.setVisible(false);
			JPanel south = new JPanel(new BorderLayout());
			south.add(askPanel, BorderLayout.NORTH);
			south.add(bottom, BorderLayout.SOUTH);
			p.add(south, BorderLayout.SOUTH);
			f.addWindowListener(new java.awt.event.WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent e) { exitCode = QUIT; f.dispose(); }
			});
			f.setContentPane(p);
			f.pack();
			fit(f, false);
			f.setLocationRelativeTo(null);
			if (System.getenv("OJ_POPUP_PNG") == null) { f.setVisible(true); bringForwardOnce(f); }   // (testing: pictures only)
			reuse = f;
		});
		String png = System.getenv("OJ_POPUP_PNG");
		boolean[] pictured = {false};
		long started = System.currentTimeMillis();
		String done = null;
		int tick = 0;
		boolean askedOnce = false;
		while (done == null) {
			if (reuse != null && !reuse.isDisplayable()) return;   // quit
			Map<String, String[]> got = new LinkedHashMap<>();
			for (String l : read(dir.resolve("pack-sync-status.txt")).split("\\R")) {
				String[] f = l.split("\\|", 4);
				if (f[0].equals("check") && f.length >= 3) got.put(f[2], new String[] {f[1], f.length > 3 ? f[3] : ""});
				else if (f[0].equals("done")) done = f.length > 1 ? f[1] : "ok";
				else if (f[0].equals("ask") && f.length >= 2 && !askedOnce) {
					// The sync is about to download: say how much, and ask (the answer goes to pack-sync-answer.txt).
					askedOnce = true;
					String base = f[1].strip();
					boolean self = f.length > 2 && f[2].strip().equals("self-update");
					new Thread(() -> ask(dir, base, ui, self), "ask").start();
				}
			}
			if (askedOnce) got.putIfAbsent("Connection", new String[] {"ok", ""});   // (the pack answered, or there'd be no question)
			if (asking) started = System.currentTimeMillis();   // (waiting for the player isn't the sync hanging)
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
							String detail = i == 0 ? "Reaching the pack (Tailscale, then GitHub)" : i == 1 ? (asking ? "Waiting for your answer below" : last.isEmpty() ? "Checking the pack's files" : last) : "";
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

	/** Before the download: works out what the update would fetch and, when there's something, shows the size in the
	 *  checklist window, where the Play button turns into Download (Quit cancels the launch), then answers the sync in
	 *  pack-sync-answer.txt: "wait" while asking, "go" after. Nothing to download, the size unknown,
	 *  or no window: "go" straight away. Quit (or closing the window) leaves no answer; the sync sees the exit code. */
	private static void ask(Path dir, String base, boolean ui, boolean selfUpdate) {
		Path answer = dir.resolve("pack-sync-answer.txt");
		String reply = "go";
		try {
			String sizes = fetch(base + "/update-sizes.json");
			// The launcher itself first: when the pack has a different launch-popup.jar than the one running, fetch it,
			// check it against the pack's hash, leave it as extras/launch-popup.jar.new and close with RELAUNCH.
			// The sync (which said "self-update", so it knows to) swaps it in and opens it; this very question is then
			// asked by the new launcher. So a launcher fix shows on the launch that brings it, before anything else.
			if (selfUpdate) try {
				String want = s(asMap(asMap(asMap(parse(sizes)).get("files")).get("extras/launch-popup.jar")).get("hash"));
				Path me = Path.of(LaunchPopup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				if (!want.isEmpty() && Files.isRegularFile(me) && !want.equals(sha256(Files.readAllBytes(me)))) {
					byte[] jar = fetchBytes(base + "/extras/launch-popup.jar");
					if (want.equals(sha256(jar))) {
						Files.write(dir.resolve("extras").resolve("launch-popup.jar.new"), jar);
						System.exit(RELAUNCH);
					}
				}
			} catch (Exception ignored) {}   // (couldn't: carry on with this launcher; the update replaces it as before)
			long[] n = pending(dir, sizes);
			JFrame f = reuse;
			if (n != null && n[0] > 0 && ui && f != null) {
				asking = true;
				Files.writeString(answer, "wait\n", StandardCharsets.UTF_8);
				String size = sizeText(n[0]);
				boolean first = n[3] == 1;
				long other = n[1] - n[2];
				String what = join(n[2] == 0 ? null : n[2] + " mod" + (n[2] == 1 ? "" : "s"), other == 0 ? null : other + (n[2] == 0 ? "" : " other") + " file" + (other == 1 ? "" : "s"));
				// Where it comes from: the pack's own files from the source the sync reached (the owner's PC over Tailscale,
				// or GitHub when that isn't reachable); mods the pack only lists, from Modrinth.
				String source = base.contains("githubusercontent") ? "GitHub" : "the owner's PC, over Tailscale";
				String from = n[4] == 0 ? "Downloads from " + source + "."
						: n[4] == n[1] ? "Downloads from Modrinth."
						: "Downloads from " + source + (n[4] == n[2] ? "; the mods from Modrinth." : "; " + n[4] + " of the mods from Modrinth.");
				String[] choice = {null};
				String png = System.getenv("OJ_POPUP_PNG");
				SwingUtilities.invokeLater(() -> {
					askText.setText(wrap((first ? NAME + " needs about " + size + " of downloads to install" : "An update is ready: about " + size + " to download")
							+ " (" + what + "). " + from + " Press Download to continue, or Quit to cancel this launch."));
					progressPlay.setText("Download");
					progressPlay.setEnabled(true);
					progressPlay.addActionListener(e -> { synchronized (choice) { choice[0] = "go"; choice.notifyAll(); } });
					askPanel.setVisible(true);
					f.pack();
					fit(f, false);
					if (png != null) try {   // testing: a picture of the question
						JComponent cp = (JComponent) f.getContentPane();
						cp.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(cp.getWidth(), cp.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics(); cp.paint(g); g.dispose();
						javax.imageio.ImageIO.write(img, "png", new java.io.File(png.replaceAll("\\.png$", "") + "-ask.png"));
					} catch (IOException ignored) {}
				});
				synchronized (choice) {
					while (choice[0] == null && f.isDisplayable()) {
						choice.wait(png == null ? 300 : 1500);
						// Testing (pictures only, nobody to click): OJ_ASK_ANSWER answers, "go" if it isn't set.
						if (png != null && choice[0] == null) choice[0] = System.getenv("OJ_ASK_ANSWER") == null ? "go" : System.getenv("OJ_ASK_ANSWER");
					}
				}
				if (choice[0] == null) { asking = false; return; }   // quit
				if (choice[0].equals("skip")) { skipped = size; reply = "skip|" + size; }
				SwingUtilities.invokeLater(() -> { askPanel.setVisible(false); progressPlay.setText("Play"); progressPlay.setEnabled(false); f.pack(); });
			}
		} catch (Exception ignored) {}   // (size unknown: the update just runs, as it always did)
		asking = false;
		try { Files.writeString(answer, reply + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}

	/** What an update would download: {bytes, files, of them mods, 1 on a first install, mods that come from Modrinth}, from the pack's
	 *  update-sizes.json (each file's hash in the pack index and its size) against packwiz-installer's record of what
	 *  is installed here. A file counts when it isn't here yet or its hash differs; one the pack only supplies when
	 *  missing ("preserve") counts only when it isn't here. Null when the sizes can't be read. */
	static long[] pending(Path dir, String sizesJson) {
		Map<String, Object> files = asMap(asMap(parse(sizesJson)).get("files"));
		if (files.isEmpty()) return null;
		String record = read(dir.resolve("packwiz.json"));
		Map<String, Object> have = record.isBlank() ? new LinkedHashMap<>() : asMap(asMap(parse(record)).get("cachedFiles"));
		long bytes = 0, n = 0, mods = 0, modrinth = 0;
		for (Map.Entry<String, Object> e : files.entrySet()) {
			Map<String, Object> f = asMap(e.getValue()), h = asMap(have.get(e.getKey()));
			if (!h.isEmpty() && ("true".equals(s(f.get("preserve"))) || s(asMap(h.get("hash")).get("value")).equals(s(f.get("hash"))))) continue;
			try { bytes += (long) Double.parseDouble(s(f.get("size"))); } catch (NumberFormatException ignored) {}
			n++;
			if (e.getKey().startsWith("mods/")) mods++;
			if (e.getKey().endsWith(".pw.toml")) modrinth++;   // (a mod listed by the pack and fetched from Modrinth)
		}
		return new long[] {bytes, n, mods, have.isEmpty() ? 1 : 0, modrinth};
	}

	/** Was this launch asked for from the Gatehouse launcher (it leaves a "direct-launch" note in the game folder, good
	 *  for ten minutes), and can the game start without this window: nothing new to show (clean), the server online,
	 *  the player on its whitelist, and this launcher not suspended or blacklisted. The note is used up either way. */
	static boolean directLaunch(Path dir, boolean clean) {
		Path mark = dir.resolve("direct-launch");
		try {
			if (!Files.exists(mark)) return false;
			boolean fresh = System.currentTimeMillis() - Files.getLastModifiedTime(mark).toMillis() < 10 * 60_000;
			Files.deleteIfExists(mark);
			if (!fresh || !clean) return false;
			ServerControl c = new ServerControl(dir);
			if (!c.configured()) return true;
			if (c.hasHelper()) try { c.hello(); c.mcssState(); } catch (IOException | RuntimeException ignored) {}
			if (!c.ban.isEmpty() || c.whitelisted.equals("no")) return false;
			String[] s = c.status();
			return s.length > 2 && s[2].equals("online");
		} catch (IOException | RuntimeException e) { return false; }
	}

	/** The Gatehouse launcher as a window of its own (what the "Gatehouse Minecraft Launcher" Steam shortcut opens):
	 *    --home <Prism's instances folder> --prism <how to start Prism Launcher> [--play <instance id>]
	 *  It lists the Gatehouse packs installed in Prism (instances whose game folder has extras/profile.json), each with
	 *  whether its server is online right now and a Play button. Play starts that instance through Prism (--launch,
	 *  and --server to join); the pack's update runs first as always, and its window is skipped when there's nothing
	 *  to show and the server is online (directLaunch). With --play, there's no window here at all: that's a
	 *  server's own Steam shortcut. This program stays until Prism closes, so Steam sees the game as running. */
	static void home(String[] args) throws Exception {
		Path instances = Path.of(args[1]);
		String prism = "prismlauncher", play = null;
		for (int i = 2; i + 1 < args.length; i += 2) { if (args[i].equals("--prism")) prism = args[i + 1]; else if (args[i].equals("--play")) play = args[i + 1]; }
		List<String[]> servers = new ArrayList<>();   // {instance id, name, address to join, game folder}
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Path mc = Files.isDirectory(d.resolve("minecraft")) ? d.resolve("minecraft") : d.resolve(".minecraft");
				String profile = read(mc.resolve("extras/profile.json"));
				if (profile.isBlank()) continue;
				String name = s(asMap(parse(profile)).get("name")), address = "";
				try { if (parse(read(mc.resolve("extras/servers.json"))) instanceof List<?> l && !l.isEmpty()) address = s(asMap(l.get(0)).get("address")); } catch (RuntimeException ignored) {}
				servers.add(new String[] {d.getFileName().toString(), name.isEmpty() ? d.getFileName().toString() : name, address, mc.toString()});
			}
		} catch (IOException ignored) {}
		String prismCmd = prism;
		if (play != null) for (String[] e : servers) if (e[0].equals(play)) { startGame(prismCmd, e); return; }
		String[][] chosen = {null};
		boolean[] openPrism = {false};
		java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
		String shot = System.getenv("OJ_POPUP_PNG");
		SwingUtilities.invokeAndWait(() -> {
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = new JFrame(TOOL);
			f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
			JLabel head = new JLabel(TOOL + " Minecraft Launcher");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 20f));
			JLabel sub = new JLabel(servers.isEmpty() ? "No Gatehouse packs are installed in Prism Launcher yet." : "Pick a server to play. The pack updates first, as always.");
			sub.setFont(sub.getFont().deriveFont(14f));
			JPanel top = new JPanel(new BorderLayout(0, 4));
			top.add(head, BorderLayout.NORTH); top.add(sub, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			JPanel list = new JPanel();
			list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
			List<Runnable> polls = new ArrayList<>();
			for (String[] e : servers) {
				JPanel row = new JPanel(new BorderLayout(14, 0));
				row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
				JLabel name = new JLabel(e[1]);
				name.setFont(name.getFont().deriveFont(Font.BOLD, 17f));
				JLabel state = new JLabel("Checking the server\u2026", dot(GRAY), JLabel.LEFT);
				state.setIconTextGap(8);
				state.setFont(state.getFont().deriveFont(13f));
				JPanel text = new JPanel(new BorderLayout(0, 4));
				text.setOpaque(false);
				text.add(name, BorderLayout.NORTH); text.add(state, BorderLayout.SOUTH);
				PlayButton go = new PlayButton("Play");
				go.addActionListener(ev -> { chosen[0] = e; f.dispose(); });
				row.add(text, BorderLayout.CENTER); row.add(go, BorderLayout.EAST);
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
				list.add(row); list.add(Box.createVerticalStrut(8));
				ServerControl c = new ServerControl(Path.of(e[3]));
				Runnable poll = () -> {
					String[] st = c.ban.equals("blacklist") ? new String[] {"off", "Not shown: this launcher is blacklisted"} : !c.configured() ? new String[] {"off", "No server set up for this pack"} : c.status();
					Runnable ui = () -> { state.setIcon(dot(colour(st[0]))); state.setText(st[1]); };
					if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
				};
				polls.add(poll);
				if (shot != null) poll.run();
			}
			JPanel inner = new JPanel(new BorderLayout());
			inner.add(list, BorderLayout.NORTH);
			JScrollPane sc = new JScrollPane(inner);
			sc.setBorder(null);
			sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			sc.getVerticalScrollBar().setUnitIncrement(16);
			sc.setPreferredSize(new Dimension(640, Math.max(140, Math.min(430, servers.size() * 98 + 10))));
			p.add(sc, BorderLayout.CENTER);
			JButton prismB = new PlayButton("Open Prism Launcher", SLATE, Color.WHITE, 14f), quit = new PlayButton("Quit", QUIT_RED, Color.WHITE, 14f);
			prismB.setToolTipText("Prism Launcher itself: accounts, other instances, settings");
			prismB.addActionListener(ev -> { openPrism[0] = true; f.dispose(); });
			quit.addActionListener(ev -> f.dispose());
			JPanel bottom = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			bottom.add(prismB); bottom.add(quit);
			p.add(bottom, BorderLayout.SOUTH);
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent ev) { closed.countDown(); } });
			f.setContentPane(p);
			f.pack();
			fit(f, false);
			if (shot != null && !shot.isEmpty()) {   // (testing: a picture instead of the window)
				f.validate();
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
				try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot)); } catch (IOException ex) { ex.printStackTrace(); }
				f.dispose();
				return;
			}
			Timer timer = new Timer(5000, ev -> { if (f.isDisplayable()) for (Runnable r : polls) new Thread(r, "server-status").start(); else ((Timer) ev.getSource()).stop(); });
			timer.setInitialDelay(0);
			timer.start();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
			bringForwardOnce(f);
		});
		closed.await();
		if (chosen[0] != null) startGame(prismCmd, chosen[0]);
		else if (openPrism[0]) run(List.of(prismCmd));
	}

	/** Starts an instance through Prism Launcher, joining its server, and waits for Prism to close. */
	private static void startGame(String prism, String[] e) {
		try { Files.writeString(Path.of(e[3]).resolve("direct-launch"), String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8); } catch (IOException ignored) {}
		List<String> cmd = new ArrayList<>(List.of(prism, "--launch", e[0]));
		if (!e[2].isEmpty()) { cmd.add("--server"); cmd.add(e[2]); }
		run(cmd);
	}
	private static void run(List<String> cmd) {
		if (System.getenv("OJ_HOME_DRY") != null) { System.out.println("would run: " + String.join(" ", cmd)); return; }   // (testing)
		try { new ProcessBuilder(cmd).inheritIO().start().waitFor(); }
		catch (IOException | InterruptedException ex) { System.err.println("couldn't start Prism Launcher (" + cmd.get(0) + "): " + ex.getMessage()); }
	}

	/** How big the build installed here is: the pack's files as they are on this device (packwiz-installer's record
	 *  of what it put where), counted and added up, with the mods' share. Null when there's no record yet. */
	static String buildSize(Path dir) {
		try {
			String record = read(dir.resolve("packwiz.json"));
			if (record.isBlank()) return null;
			long bytes = 0, files = 0, mods = 0, modBytes = 0;
			for (Map.Entry<String, Object> e : asMap(asMap(parse(record)).get("cachedFiles")).entrySet()) {
				String loc = s(asMap(e.getValue()).get("cachedLocation"));
				Path p = dir.resolve(loc.isEmpty() ? e.getKey() : loc);
				if (!Files.isRegularFile(p)) p = p.resolveSibling(p.getFileName() + ".disabled");   // (a mod switched off)
				if (!Files.isRegularFile(p)) continue;
				long size = Files.size(p);
				bytes += size; files++;
				if (e.getKey().startsWith("mods/")) { mods++; modBytes += size; }
			}
			if (files == 0) return null;
			return sizeText(bytes) + " in " + files + " files (" + mods + " mods, " + sizeText(modBytes) + ")";
		} catch (IOException | RuntimeException e) { return null; }
	}

	/** Keeps a window on the screen it opens on. The launcher is laid out for a Steam Deck's display (1280 x 800): the
	 *  main window opens no taller than fits there, wide enough for its tabs, and its tabs scroll; it can still be
	 *  resized. (Testing: OJ_POPUP_SCREEN=<width>x<height> stands in for the screen.) */
	static void fit(java.awt.Window f, boolean main) {
		int maxW, maxH;
		String fake = System.getenv("OJ_POPUP_SCREEN");
		if (fake != null && fake.matches("\\d+x\\d+")) { maxW = Integer.parseInt(fake.split("x")[0]); maxH = Integer.parseInt(fake.split("x")[1]); }
		else {
			java.awt.GraphicsConfiguration gc = f.getGraphicsConfiguration();
			java.awt.Insets in = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
			maxW = gc.getBounds().width - in.left - in.right; maxH = gc.getBounds().height - in.top - in.bottom;
		}
		Dimension d = f.getSize();
		int w = Math.min(maxW, main ? Math.max(d.width, 900) : d.width), h = Math.min(maxH, main ? Math.min(d.height, 720) : d.height);
		if (w != d.width || h != d.height) f.setSize(w, h);
	}

	static String sizeText(long bytes) {
		return bytes < 1_000_000 ? Math.max(1, Math.round(bytes / 1000.0)) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6);
	}

	private static String fetch(String url) throws IOException { return new String(fetchBytes(url), StandardCharsets.UTF_8); }

	private static byte[] fetchBytes(String url) throws IOException {
		java.net.URLConnection c = java.net.URI.create(url).toURL().openConnection();
		c.setConnectTimeout(5000);
		c.setReadTimeout(10000);
		try (java.io.InputStream in = c.getInputStream()) { return in.readAllBytes(); }
	}

	private static String sha256(byte[] b) throws java.security.NoSuchAlgorithmException {
		StringBuilder sb = new StringBuilder();
		for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	/** The Play button: blue when it can be pressed, grey when it can't (drawn here, since the system's own buttons
	 *  don't take a colour). In the checklist it's also the button that accepts a download. */
	static final class PlayButton extends JButton {
		private final Color base, ink;
		private boolean flashOn;
		private Timer flashing;
		PlayButton(String text) { this(text, BLUE, Color.WHITE, 15f); }
		/** Flashes red, half a second on and half off, for five seconds: a request was denied. */
		void flash() {
			if (flashing != null) flashing.stop();
			int[] n = {0};
			flashing = new Timer(500, e -> { flashOn = n[0] % 2 == 0; n[0]++; if (n[0] > 10) { flashOn = false; ((Timer) e.getSource()).stop(); } repaint(); });
			flashing.setInitialDelay(0);
			flashing.start();
		}
		/** The same button in another colour (Restart is yellow, Quit red), with its text colour and size. */
		PlayButton(String text, Color base, Color ink, float size) {
			super(text);
			this.base = base; this.ink = ink;
			setFont(getFont().deriveFont(Font.BOLD, size));
			setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(7, 22, 7, 22));
		}
		@Override protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			boolean on = isEnabled();
			Color fill = flashOn ? QUIT_RED : !on ? new Color(0x2B3A4B) : getModel().isPressed() ? base.darker() : getModel().isRollover() ? shade(base, 1.12f) : base;
			g2.setColor(fill);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			if (on && isFocusOwner()) { g2.setColor(new Color(255, 255, 255, 170)); g2.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 6, 6); }
			g2.setFont(getFont());
			java.awt.FontMetrics fm = g2.getFontMetrics();
			g2.setColor(flashOn ? Color.WHITE : on ? ink : new Color(0x71808F));
			g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
			g2.dispose();
		}
	}

	static final Color YELLOW = new Color(0xF2C230), QUIT_RED = new Color(0xD23C3C), INK = new Color(0x3A2F00), SLATE = new Color(0x5B6B82);

	/** A short, soft three-note chime (made here, no sound file): the owner granted something this device asked for. */
	static void chime() {
		Thread t = new Thread(() -> {
			try {
				float rate = 44100f;
				double[] notes = {659.25, 830.61, 987.77};
				int step = (int) (rate * 0.16), tail = (int) (rate * 0.9);
				double[] mix = new double[step * (notes.length - 1) + tail];
				for (int n = 0; n < notes.length; n++)
					for (int i = 0; i < tail; i++) {
						double time = i / rate, env = Math.min(1, time / 0.008) * Math.exp(-time * 4.2);
						mix[n * step + i] += env * (Math.sin(2 * Math.PI * notes[n] * time) + 0.25 * Math.sin(4 * Math.PI * notes[n] * time));
					}
				byte[] pcm = new byte[mix.length * 2];
				for (int i = 0; i < mix.length; i++) { int v = (int) (mix[i] * 0.22 * 32767); pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8); }
				javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(rate, 16, 1, true, false);
				try (javax.sound.sampled.SourceDataLine line = javax.sound.sampled.AudioSystem.getSourceDataLine(fmt)) {
					line.open(fmt); line.start(); line.write(pcm, 0, pcm.length); line.drain();
				}
			} catch (Exception | LinkageError ignored) {}
		}, "chime");
		t.setDaemon(true);
		t.start();
	}
	private static Color shade(Color c, float f) {
		return new Color(Math.min(255, Math.round(c.getRed() * f)), Math.min(255, Math.round(c.getGreen() * f)), Math.min(255, Math.round(c.getBlue() * f)));
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
				g2.setColor(i < lit ? Color.getHSBColor(i / 9f * 0.33f, 0.78f, 0.86f) : new Color(0x2F4860));
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
		return "<html>" + esc(label) + "<font color='#8f98a0'>&nbsp;&nbsp;" + esc(Character.toUpperCase(d.charAt(0)) + d.substring(1)) + "</font></html>";
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
					g2.setColor(new Color(0x3D4F63)); g2.drawOval(x + 2, y + 2, 14, 14);
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
		loadProfile(dir);
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

		boolean big = updated || somethingHappened || serverNews || skipped != null;
		boolean wanted = big ? option(options, "popup_updates") : option(options, "popup_no_updates");
		if (current != null) Files.writeString(dir.resolve("pack-version-seen.txt"), current + "\n", StandardCharsets.UTF_8);
		if (!wanted || GraphicsEnvironment.isHeadless()) { JFrame r = reuse; if (r != null) SwingUtilities.invokeAndWait(r::dispose); return; }

		String playing = current == null ? "" : " " + current;
		String headline;
		// Nothing seen yet (a new install, or the first launch with the pop-up): no "updated from" to speak of.
		if (updated && seen.isEmpty()) headline = NAME + " " + current;
		else if (updated) headline = NAME + " updated to " + current;
		else if (status.equals("offline")) headline = "Couldn't check for updates";
		else if (status.equals("failed")) headline = "The update didn't finish";
		else if (!somethingHappened && serverNews) headline = NAME + playing + ": server changes";
		else headline = NAME + playing + (somethingHappened ? "" : ": no updates");
		String sub;
		if (status.equals("offline")) sub = "The pack couldn't be reached (Tailscale or internet). Playing" + playing + " as installed. The Connection tab says what's wrong.";
		else if (status.equals("failed")) sub = "Starting with the mods already installed. Details are in pack-sync.log in the game folder.";
		else if (updated || somethingHappened) sub = "Here's what this launch checked and changed.";
		else if (serverNews) sub = "Nothing new for this device; the Server updates tab shows what changed on the server.";
		else sub = "You're up to date.";
		// The player chose Skip this time at the size question: say so, rather than "no updates".
		if (skipped != null) {
			headline = NAME + playing + ": update skipped";
			sub = "An update of about " + skipped + " is waiting. Restart offers it again; Play starts with what's installed.";
		}

		// The window's content, top to bottom.
		List<Component> rows = new ArrayList<>();
		String build = buildSize(dir);
		if (build != null) { rows.add(heading("This build")); rows.add(row(GREEN, current == null ? "Installed on this device" : "Version " + current, build)); }
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
			rows.add(heading("Mod switches (Mods > " + TOOL + " Settings)"));
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
		// Started from the Gatehouse launcher or a server's own Steam shortcut (the player already said Play there): with
		// nothing new to show and the server online, the game starts straight away.
		if (directLaunch(dir, status.equals("ok") && !updated && !somethingHappened && !serverNews && skipped == null)) {
			SwingUtilities.invokeAndWait(() -> { if (reuse != null) reuse.dispose(); });
			exitCode = 0;
			return;
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
	private static JComponent[] controls(ServerControl c, JFrame f, Runnable hold, boolean live, java.util.function.Consumer<String[]> also, Runnable onPerms) {
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		// Three groups, in the order they depend on each other: the server machine (the machine), MCSS (the server software),
		// and the Minecraft server itself. Each has its own status line, buttons and messages.
		JLabel pcStatus = new JLabel("Checking\u2026", dot(GRAY), JLabel.LEFT);
		JLabel status = new JLabel("Checking\u2026", dot(GRAY), JLabel.LEFT);
		for (JLabel l : new JLabel[] {pcStatus, status}) { l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f)); l.setIconTextGap(8); }
		JLabel pcNote = new JLabel(" ");
		pcNote.setFont(pcNote.getFont().deriveFont(Font.PLAIN, 12f));
		pcNote.setForeground(new Color(0x8F98A0));
		JLabel note = new JLabel(" ");
		note.setFont(note.getFont().deriveFont(Font.PLAIN, 12f));
		note.setForeground(new Color(0x8F98A0));
		// How the last stop went: watched after Stop until the server is off, then the log is read for the save.
		JLabel stopped = new JLabel(" ");
		stopped.setFont(stopped.getFont().deriveFont(Font.PLAIN, 13f));
		stopped.setIconTextGap(8);
		JButton wake = new JButton("Wake server machine"), start = new JButton("Start server"), stop = new JButton("Stop server");
		Runnable[] permsChanged = {() -> {}};   // (set further down: the Request permission buttons follow what the Keeper says)
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
				String phase = s.length > 2 ? s[2] : "";
				// The PC: awake whenever anything on it answers (game server, panel or Gatehouse Keeper).
				boolean asleep = phase.equals("asleep"), blocked = phase.equals("blocked");   // (blocked: blacklisted, nothing is shown)
				pcStatus.setIcon(dot(blocked ? GRAY : asleep ? (waking ? AMBER : GRAY) : GREEN));
				pcStatus.setText(wrap(blocked ? s[1] : asleep ? (waking ? "Waking up\u2026 (it can take a minute)" : "Asleep or off") : "Awake"));
				// The Minecraft server.
				String game;
				switch (phase) {
					case "online" -> game = "Online";
					case "starting", "stopping" -> game = s[1];
					case "off" -> game = "Off" + (c.canControlServer() ? ": Start server starts it" : "");
					case "nomcss" -> game = "Off: MCSS isn't running (start it above)";
					case "asleep" -> game = "Off: the server machine is asleep or off";
					default -> game = s[1];
				}
				status.setIcon(dot(phase.equals("nomcss") || asleep ? GRAY : colour(s[0])));
				status.setText(wrap(game));
				// Start only when the server is off (and MCSS running); Stop only while it runs; neither mid-change.
				start.setEnabled(c.canControlServer() && phase.equals("off"));
				stop.setEnabled(c.canControlServer() && phase.equals("online"));
				// Wake only while the PC isn't answering at all (neither the game server nor the panel): asleep or off.
				wake.setEnabled(c.canWake() && phase.equals("asleep") && (c.permSleep || !c.hasHelper()) && c.ban.isEmpty());
				wake.setToolTipText(phase.equals("asleep") ? "Send the wake signal (works from the home network)" : "The server machine is awake");
				String ping = s.length > 3 ? s[3] : "";
				int ms = -1;
				try { ms = Integer.parseInt(ping); } catch (NumberFormatException ignored) {}
				meter.set(ms);
				pingText.setText(ms < 0 ? "\u2014" : ms + " ms");
				players.setText("Players: " + (s.length > 4 && !s[4].isEmpty() ? s[4] + " online" : "\u2014"));
			}
		};
		// A blacklisted launcher isn't shown how the machine, MCSS or the server are, and doesn't go looking either.
		java.util.function.Supplier<String[]> reading = () -> c.ban.equals("blacklist") ? new String[] {"off", "Not shown: this launcher is blacklisted", "blocked", "", ""} : c.status();
		Runnable refresh = () -> new Thread(() -> { String[] s = reading.get(); SwingUtilities.invokeLater(() -> apply.accept(s)); }, "server-status").start();
		if (!live && c.hasHelper()) try { c.hello(); c.mcssState(); } catch (IOException | RuntimeException ignored) {}
		if (!live) apply.accept(reading.get());   // (test pictures: one reading, so they show real values)
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
				SwingUtilities.invokeLater(() -> pcNote.setText(wrap(m)));
				refresh.run();
			}, "wake").start();
		});
		start.addActionListener(e -> {
			hold.run();
			new Thread(() -> {
				String msg;
				try { c.start(); msg = "Start requested: the server takes a minute or so to come up"; }
				catch (ServerControl.Refused ex) { msg = ex.getMessage(); }
				catch (ServerControl.HttpError ex) { msg = ex.code == 401 || ex.code == 403 ? "Your access isn't allowed to start the server (ask the owner)" : "The panel said no (" + ex.getMessage() + ")"; }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the panel: is the server machine awake?"; }
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
				catch (ServerControl.Refused ex) { stopResult.accept("failed", ex.getMessage()); return; }
				catch (ServerControl.HttpError ex) { stopResult.accept("failed", ex.code == 401 || ex.code == 403 ? "Your access isn't allowed to stop the server (ask the owner)" : "The panel said no (" + ex.getMessage() + ")"); return; }
				catch (IOException | RuntimeException ex) { stopResult.accept("failed", "Couldn't reach the panel: is the server machine awake?"); return; }
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

		// MCSS itself (the server software), through Gatehouse Keeper on the server machine: allowed per launcher by the owner.
		JLabel mcss = new JLabel("Checking\u2026", dot(GRAY), JLabel.LEFT);
		mcss.setFont(mcss.getFont().deriveFont(Font.PLAIN, 13f));
		mcss.setIconTextGap(8);
		JButton mStart = new JButton("Start MCSS"), mClose = new JButton("Close MCSS"), pcSleep = new JButton("Sleep server machine");
		boolean[] forceMode = {false};
		mStart.setEnabled(false); mClose.setEnabled(false); pcSleep.setEnabled(false);
		JLabel mNote = new JLabel(" ");
		mNote.setFont(mNote.getFont().deriveFont(Font.PLAIN, 12f));
		mNote.setForeground(new Color(0x8F98A0));
		Runnable mcssRefresh = () -> new Thread(() -> {
			String state, text, mode = "close"; boolean granted = false, running = false, sleepOk = false, game = false;
			try {
				Map<String, Object> r = c.mcssState();
				state = s(r.get("state"));
				running = "true".equals(s(r.get("mcss")));   // (this reader gives true/false back as text)
				granted = state.equals("granted");
				if (s(r.get("close")).equals("force")) mode = "force";
				sleepOk = "true".equals(s(r.get("sleep")));
				game = "true".equals(s(r.get("minecraft")));
				// How MCSS is, whatever this device may do about it (only a blacklisted launcher isn't told).
				if (c.ban.equals("blacklist")) { text = "Not shown: this launcher is blacklisted"; state = "off"; }
				else { text = running ? "MCSS is running" : "MCSS isn't running"; state = running ? "ok" : "off"; }
			} catch (IOException | RuntimeException e) { state = "off"; text = "Gatehouse Keeper isn't answering (the server machine may be asleep or signed out)"; }
			String st = state, tx = text, md = mode; boolean g = granted, run = running, sl = sleepOk, gm = game;
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
				onPerms.run();   // (what the owner allows may have changed: the tabs and the access line follow)
				permsChanged[0].run();
				// Sleep: only with the owner's PC sleep permission, and only while the Minecraft server is stopped.
				pcSleep.setEnabled(sl && !gm);
				pcSleep.setToolTipText(!sl ? "This device isn't allowed to sleep the PC (the owner allows it in the control panel)"
						: gm ? "Stop the Minecraft server first" : "Put the server machine to sleep (Wake server machine wakes it from the home network)");
			});
		}, "mcss-status").start();
		java.util.function.Function<Boolean, Runnable> mcssAct = startIt -> () -> {
			hold.run();
			boolean force = forceMode[0];
			if (!startIt && javax.swing.JOptionPane.showConfirmDialog(f, force
					? "Force close MCSS on the server machine? Its X button is set to hide it, so it will be ended rather than closed normally. (Only once the Minecraft server is stopped.)"
					: "Close MCSS on the server machine? It closes normally, like its X button. (Only once the Minecraft server is stopped.)",
					force ? "Force close MCSS" : "Close MCSS", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
			mNote.setText(startIt ? "Starting MCSS\u2026" : "Closing MCSS\u2026");
			new Thread(() -> {
				String msg;
				try { Map<String, Object> r = startIt ? c.mcssStart() : c.mcssClose(force); msg = s(r.get("message")).isEmpty() ? s(r.get("error")) : s(r.get("message")); }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach Gatehouse Keeper: is the server machine awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> mNote.setText(wrap(m)));
				mcssRefresh.run();
				refresh.run();
			}, "mcss-act").start();
		};
		mStart.addActionListener(e -> mcssAct.apply(true).run());
		pcSleep.addActionListener(e -> {
			hold.run();
			if (javax.swing.JOptionPane.showConfirmDialog(f, "Put the server machine to sleep? MCSS stays open and carries on when it wakes. Wake server machine wakes it again from the home network.",
					"Sleep server machine", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) return;
			pcNote.setText("Asking the server machine to sleep\u2026");
			new Thread(() -> {
				String msg;
				try { Map<String, Object> r = c.sleepPc(); msg = s(r.get("message")).isEmpty() ? s(r.get("error")) : s(r.get("message")); }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach Gatehouse Keeper: is the server machine awake?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> pcNote.setText(wrap(m)));
			}, "pc-sleep").start();
		});
		mClose.addActionListener(e -> mcssAct.apply(false).run());
		// The layout: each group a heading, its status line, its buttons and its messages.
		java.util.function.BiConsumer<String, JComponent[]> group = (title, parts) -> {
			JLabel h = new JLabel(title);
			h.setFont(h.getFont().deriveFont(Font.BOLD, 14f));
			h.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createEmptyBorder(box.getComponentCount() == 0 ? 0 : 10, 0, 0, 0),
					BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(box.getComponentCount() == 0 ? 0 : 1, 0, 0, 0, new Color(0x2F4860)), BorderFactory.createEmptyBorder(6, 0, 2, 0))));
			h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));   // (so its dividing line spans the tab)
			for (JComponent x : java.util.stream.Stream.concat(java.util.stream.Stream.of((JComponent) h), java.util.Arrays.stream(parts)).toList()) {
				x.setAlignmentX(Component.LEFT_ALIGNMENT); box.add(x); box.add(Box.createVerticalStrut(2));
			}
		};
		java.util.function.Function<JButton[], JPanel> row = bs -> {
			JPanel r = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
			for (JButton b : bs) { b.setFont(b.getFont().deriveFont(13f)); r.add(b); }
			return r;
		};
		// "Request permission", one for each thing this device may not do: it asks the owner (a chime and a line in the
		// control panel there). Granted: a chime here and a note. Denied: the button flashes red, with the owner's reason.
		PlayButton[] reqB = {new PlayButton("Request permission", SLATE, Color.WHITE, 13f), new PlayButton("Request permission", SLATE, Color.WHITE, 13f), new PlayButton("Request permission", SLATE, Color.WHITE, 13f)};
		String[] reqType = {"perm:machine", "perm:mcss", "perm:server"};
		JLabel[] reqNote = {pcNote, mNote, note};
		boolean[] reqPending = new boolean[3];
		long[] reqWait = new long[3];
		for (int i = 0; i < 3; i++) {
			int k = i;
			reqB[i].setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
			reqB[i].setVisible(false);
			reqB[i].addActionListener(e -> {
				hold.run();
				reqWait[k] = System.currentTimeMillis() + 60_000; reqPending[k] = true;
				reqB[k].setEnabled(false);
				reqNote[k].setText("Asking the owner\u2026");
				c.reqStates.put(reqType[k], "asked");
				new Thread(() -> {
					String m;
					try {
						Map<String, Object> r = c.request(reqType[k], "");
						m = s(r.get("message")).isEmpty() ? (s(r.get("error")).isEmpty() ? "Gatehouse Keeper didn't take the request" : s(r.get("error"))) : s(r.get("message"));
						if (!"ok".equals(s(r.get("result")))) { reqPending[k] = false; reqWait[k] = 0; }
					} catch (IOException | RuntimeException ex) { m = "Couldn't reach Gatehouse Keeper (the server machine may be asleep)"; reqPending[k] = false; reqWait[k] = 0; }
					String msg = m;
					SwingUtilities.invokeLater(() -> { reqNote[k].setText(wrap(msg)); permsChanged[0].run(); });
				}, "request-permission").start();
			});
		}
		permsChanged[0] = () -> {
			boolean[] has = {c.permSleep, c.permMcss, c.canControlServer()};
			String[] st = {c.permState("machine"), c.permState("mcss"), c.permState("server")};
			for (int i = 0; i < 3; i++) {
				reqB[i].setVisible(c.hasHelper() && !has[i] && c.ban.isEmpty());
				reqB[i].setText(st[i].equals("denied") ? "Request permission (denied)" : "Request permission");
				reqB[i].setEnabled(System.currentTimeMillis() >= reqWait[i]);
				String answer = c.reqStates.getOrDefault(reqType[i], "");
				if (reqPending[i] && answer.equals("granted")) { reqPending[i] = false; reqWait[i] = 0; chime(); reqNote[i].setText(wrap("Permission granted by the owner.")); }
				else if (reqPending[i] && answer.equals("denied")) {
					reqPending[i] = false; reqWait[i] = 0;
					reqB[i].setEnabled(true); reqB[i].flash();
					String why = c.reqReasons.getOrDefault(reqType[i], "");
					reqNote[i].setText(wrap("Permission denied by the owner" + (why.isEmpty() ? "." : ": " + why)));
				}
			}
		};
		permsChanged[0].run();
		group.accept("Server Machine", c.hasHelper() ? new JComponent[] {pcStatus, row.apply(new JButton[] {wake, pcSleep, reqB[0]}), pcNote} : new JComponent[] {pcStatus, row.apply(new JButton[] {wake}), pcNote});
		if (c.hasHelper()) group.accept("MCSS (the server software)", new JComponent[] {mcss, row.apply(new JButton[] {mStart, mClose, reqB[1]}), mNote});
		JLabel via = new JLabel(wrap("Control: " + c.accessText()));
		via.setFont(via.getFont().deriveFont(Font.PLAIN, 12f));
		via.setForeground(new Color(0x8F98A0));
		group.accept("Minecraft server", new JComponent[] {status, pingRow, playersRow, row.apply(new JButton[] {start, stop, reqB[2]}), via, note, stopped});

		// Server config: how this device controls the server, and signing in to the server panel with a panel account
		// (the launcher keeps the key the panel hands back, never the password).
		JPanel cfg = new JPanel();
		cfg.setLayout(new BoxLayout(cfg, BoxLayout.Y_AXIS));
		cfg.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JLabel cfgNote = new JLabel(" ");
		cfgNote.setForeground(new Color(0x8F98A0));
		JLabel who = new JLabel();
		who.setFont(who.getFont().deriveFont(Font.PLAIN, 13f));
		who.setIconTextGap(8);
		JButton signOut = new JButton("Sign out");
		Runnable showWho = () -> {
			who.setIcon(dot(c.canControlServer() ? GREEN : GRAY));
			who.setText(wrap("Server control: " + c.accessText()));
			via.setText(wrap("Control: " + c.accessText()));
			signOut.setEnabled(c.hasKey());
		};
		JLabel signHead = new JLabel("Server panel sign-in");
		signHead.setFont(signHead.getFont().deriveFont(Font.BOLD, 14f));
		signHead.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
		javax.swing.JTextField user = new javax.swing.JTextField(c.username, 18);
		javax.swing.JPasswordField pass = new javax.swing.JPasswordField(18);
		JButton signIn = new JButton("Sign in");
		Runnable doSignIn = () -> {
			hold.run();
			signIn.setEnabled(false);
			cfgNote.setText("Signing in\u2026");
			String u = user.getText(), pw = new String(pass.getPassword());
			new Thread(() -> {
				String msg;
				try { c.signIn(u, pw, c.wakeAddress); msg = "Signed in as " + u.trim() + "."; }
				catch (ServerControl.HttpError ex) { msg = ex.code == 401 || ex.code == 403 ? "The panel didn't accept that username and password." : "The panel said no (" + ex.getMessage() + ")."; }
				catch (IOException | RuntimeException ex) { msg = "Couldn't reach the panel: is the server machine awake, and MCSS running?"; }
				String m = msg;
				SwingUtilities.invokeLater(() -> { cfgNote.setText(wrap(m)); signIn.setEnabled(true); pass.setText(""); showWho.run(); });
				refresh.run();
			}, "sign-in").start();
		};
		signIn.addActionListener(e -> doSignIn.run());
		pass.addActionListener(e -> doSignIn.run());
		signOut.setToolTipText("Forget the sign-in or key saved on this device");
		signOut.addActionListener(e -> {
			hold.run();
			try { c.username = ""; c.save("", c.wakeAddress); cfgNote.setText("Signed out: nothing is saved on this device now."); }
			catch (IOException ex) { cfgNote.setText("Couldn't save: " + ex.getMessage()); }
			showWho.run(); refresh.run();
		});
		JPanel fields = new JPanel(new java.awt.GridBagLayout());
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.insets = new java.awt.Insets(2, 0, 2, 8); gc.anchor = java.awt.GridBagConstraints.WEST;
		gc.gridx = 0; gc.gridy = 0; fields.add(new JLabel("Username"), gc);
		gc.gridx = 1; fields.add(user, gc);
		gc.gridx = 0; gc.gridy = 1; fields.add(new JLabel("Password"), gc);
		gc.gridx = 1; fields.add(pass, gc);
		JPanel signButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		for (JButton bt : new JButton[] {signIn, signOut}) { bt.setFont(bt.getFont().deriveFont(13f)); signButtons.add(bt); }
		gc.gridx = 1; gc.gridy = 2; gc.insets = new java.awt.Insets(2, -6, 2, 0); fields.add(signButtons, gc);
		JPanel fieldsWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		fieldsWrap.add(fields);
		JLabel signHelp = new JLabel(wrap("Sign in with your own account on the server panel. Only the key the panel hands back is saved, on this device; your password isn't. If the owner allowed this device through Gatehouse Keeper, you don't need to sign in."));
		signHelp.setForeground(new Color(0x8F98A0));
		showWho.run();
		// Which Gatehouse Keeper this launcher talks to: the pack's, or one this device was paired with by address.
		JLabel keeperHead = new JLabel("Gatehouse Keeper");
		keeperHead.setFont(keeperHead.getFont().deriveFont(Font.BOLD, 14f));
		keeperHead.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
		JLabel keeperNow = new JLabel(), keeperNote = new JLabel(" ");
		Runnable showKeeper = () -> keeperNow.setText(wrap(!c.hasHelper() ? "This launcher isn't paired with a Keeper: it can show whether the server is online, and nothing more."
				: "This launcher talks to the Keeper at " + c.helperLabel() + (c.keeperUrl.isEmpty() ? " (the one this pack names)." : " (paired on this device).")));
		showKeeper.run();
		javax.swing.JTextField keeperField = new javax.swing.JTextField(c.keeperUrl.replaceFirst("(?i)^https?://", ""), 20);
		JButton pairB = new JButton("Pair"), unpairB = new JButton("Use the pack's Keeper");
		unpairB.setVisible(!c.packHelperUrl.isEmpty());
		java.util.function.Consumer<String> pairWith = address -> {
			hold.run();
			pairB.setEnabled(false); unpairB.setEnabled(false);
			keeperNote.setText("Asking\u2026");
			new Thread(() -> {
				String[] r = c.pair(address);
				SwingUtilities.invokeLater(() -> {
					pairB.setEnabled(true); unpairB.setEnabled(true);
					keeperNote.setForeground(r[0].equals("ok") ? new Color(0x8BD46E) : new Color(0xFF7B72));
					keeperNote.setText(wrap(r[1]));
					if (r[0].equals("ok")) { keeperField.setText(c.keeperUrl.replaceFirst("(?i)^https?://", "")); showKeeper.run(); showWho.run(); onPerms.run(); }
				});
			}, "pair").start();
		};
		pairB.addActionListener(e -> { if (keeperField.getText().isBlank()) { keeperNote.setForeground(RED); keeperNote.setText("Type the Keeper's address first (the owner copies it from the control panel)."); } else pairWith.accept(keeperField.getText()); });
		unpairB.addActionListener(e -> pairWith.accept(""));
		JPanel keeperRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		keeperRow.add(new JLabel("Keeper address")); keeperRow.add(keeperField);
		for (JButton bt : new JButton[] {pairB, unpairB}) { bt.setFont(bt.getFont().deriveFont(13f)); keeperRow.add(bt); }
		JLabel keeperHelp = new JLabel(wrap("To pair with another Keeper, ask its owner for the pairing address (control panel on the server machine: Status, Copy pairing address) and type it here. This device has to be in that owner's Tailscale network."));
		keeperHelp.setForeground(new Color(0x8F98A0));
		for (JComponent x : new JComponent[] {who, signHead, signHelp, fieldsWrap, cfgNote, keeperHead, keeperNow, keeperHelp, keeperRow, keeperNote}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); cfg.add(x); cfg.add(Box.createVerticalStrut(4)); }

		if (live) {
			refresh.run();
			if (c.hasHelper()) new Thread(() -> { try { c.hello(); } catch (IOException | RuntimeException ignored) {} mcssRefresh.run(); }, "hello").start();
			// Every few seconds while the window is open.
			Timer poll = new Timer(4000, e -> { if (f.isDisplayable()) { refresh.run(); if (c.hasHelper()) mcssRefresh.run(); showWho.run(); } });
			poll.start();
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { poll.stop(); } });
		}
		JPanel[] manage = managePanel(c, f);
		return new JComponent[] {box, cfg, manage[0]};
	}

	/** After a stop: was the world saved? The end of the server's log (through the panel's console) should show
	 *  "Stopping server", "Saving worlds" and a chunk save for each dimension, with no errors after the stop began. */
	/** For a device the owner allowed Keeper control, what the control panel on the server machine does, as two tabs:
	 *  Manage access (who may do what, one launcher at a time or a player with all their devices; Remove server access;
	 *  Forget) and Keeper activity (MCSS and the Minecraft server right now, and everything asked of the Keeper). Helper
	 *  control itself and the Keeper's MCSS sign-in stay with the panel on the server machine. Returns the one panel
	 *  that holds both (the launcher's Remote control panel tab). */
	private static JPanel[] managePanel(ServerControl c, JFrame f) {
		Color grey = new Color(0x8F98A0);
		// ---- Access Manager: each permission is Allowed (green), Not allowed (white) or Denied (red); click one to change
		// it. A player's setting goes to all their devices, except a device that is itself Denied. Whitelist, kick,
		// suspend and blacklist are here too.
		JPanel access = new JPanel(new BorderLayout(0, 6));
		access.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		javax.swing.JRadioButton byDevice = new javax.swing.JRadioButton("By launcher (one device)", true), byPlayer = new javax.swing.JRadioButton("By player (all their devices)");
		javax.swing.ButtonGroup how = new javax.swing.ButtonGroup(); how.add(byDevice); how.add(byPlayer);
		JPanel pick = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
		pick.add(byDevice); pick.add(byPlayer);
		access.add(pick, BorderLayout.NORTH);
		java.util.function.Function<String[], javax.swing.table.DefaultTableModel> newModel = cols -> new javax.swing.table.DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int col) { return false; } };
		javax.swing.table.DefaultTableModel model = newModel.apply(new String[] {"Launcher", "Player", "Last seen", "Status", "Server Machine", "MCSS", "Minecraft server", "Keeper control", "Controls the server"});
		javax.swing.table.DefaultTableModel pmodel = newModel.apply(new String[] {"Player", "Launchers", "Last joined", "Whitelist", "Status", "Server Machine", "MCSS", "Minecraft server"});
		javax.swing.JTable table = new javax.swing.JTable(model), ptable = new javax.swing.JTable(pmodel);
		javax.swing.table.DefaultTableCellRenderer paint = new javax.swing.table.DefaultTableCellRenderer() {
			@Override public Component getTableCellRendererComponent(javax.swing.JTable tb, Object v, boolean sel, boolean focus, int r, int col) {
				Component x = super.getTableCellRendererComponent(tb, v, sel, focus, r, col);
				String tx = s(v);
				Color bg = tx.startsWith("Allowed") ? new Color(0xCDEFD3) : tx.startsWith("Denied") || tx.startsWith("Blacklisted") || tx.startsWith("Suspended") ? new Color(0xF6CACA) : tx.startsWith("Not allowed") ? Color.WHITE : null;
				x.setBackground(bg != null ? bg : sel ? tb.getSelectionBackground() : tb.getBackground());
				x.setForeground(bg != null ? Color.BLACK : sel ? tb.getSelectionForeground() : tb.getForeground());
				return x;
			}
		};
		for (javax.swing.JTable tb : new javax.swing.JTable[] {table, ptable}) { tb.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION); tb.setRowHeight(24); tb.setDefaultRenderer(Object.class, paint); }
		List<String> ids = new ArrayList<>(), pnames = new ArrayList<>();
		Map<String, String> dban = new LinkedHashMap<>(), pban = new LinkedHashMap<>();   // (who is suspended or blacklisted: a device itself, or a player)
		Map<String, Boolean> pwl = new LinkedHashMap<>();
		JScrollPane ts = new JScrollPane(table), pts = new JScrollPane(ptable);
		ts.setPreferredSize(new Dimension(600, 200)); pts.setPreferredSize(new Dimension(600, 200));
		java.awt.CardLayout cards = new java.awt.CardLayout();
		JPanel deck = new JPanel(cards);
		deck.add(ts, "device"); deck.add(pts, "player");
		access.add(deck, BorderLayout.CENTER);
		JButton wlB = new JButton("Add to whitelist"), kickB = new JButton("Kick\u2026"), suspendB = new JButton("Suspend\u2026"), blackB = new JButton("Blacklist\u2026"), liftB = new JButton("Lift"),
				forget = new JButton("Forget"), revoke = new JButton("Remove server access");
		JPanel buttons = new JPanel(new java.awt.GridLayout(0, 4, 6, 4));
		for (JButton b : new JButton[] {suspendB, blackB, liftB, wlB, kickB, revoke, forget}) buttons.add(b);
		String hint = "Click a permission to change it: Allowed, Not allowed or Denied.";
		JLabel note = new JLabel(wrap(hint));
		note.setForeground(grey);
		JPanel under = new JPanel(new BorderLayout(0, 2));
		under.add(buttons, BorderLayout.NORTH); under.add(note, BorderLayout.SOUTH);
		access.add(under, BorderLayout.SOUTH);
		// ---- Keeper activity
		JPanel activity = new JPanel(new BorderLayout(0, 8));
		activity.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JLabel mcssLine = new JLabel("Asking Gatehouse Keeper\u2026", dot(GRAY), JLabel.LEFT), serverLine = new JLabel(" ", dot(GRAY), JLabel.LEFT), keyLine = new JLabel(" ");
		keyLine.setForeground(grey);
		JPanel lines = new JPanel();
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		for (JLabel l : new JLabel[] {mcssLine, serverLine, keyLine}) { l.setIconTextGap(8); l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); l.setAlignmentX(Component.LEFT_ALIGNMENT); lines.add(l); }
		activity.add(lines, BorderLayout.NORTH);
		javax.swing.JTextArea log = new javax.swing.JTextArea(12, 40);
		log.setEditable(false);
		log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		JPanel logBox = new JPanel(new BorderLayout(0, 4));
		JLabel logHead = new JLabel("Activity on the server machine");
		logHead.setFont(logHead.getFont().deriveFont(Font.BOLD));
		logBox.add(logHead, BorderLayout.NORTH); logBox.add(new JScrollPane(log), BorderLayout.CENTER);
		activity.add(logBox, BorderLayout.CENTER);

		boolean[] players = {false};
		java.util.function.Function<String, String> word = st -> st.equals("allowed") ? "Allowed" : st.equals("denied") ? "Denied" : "Not allowed";
		Runnable labels = () -> {
			String ban = "";
			if (players[0]) {
				int r = ptable.getSelectedRow();
				if (r >= 0 && r < pnames.size()) { ban = pban.getOrDefault(pnames.get(r), ""); wlB.setText(pwl.getOrDefault(pnames.get(r), false) ? "Remove from whitelist" : "Add to whitelist"); }
			} else { int r = table.getSelectedRow(); if (r >= 0 && r < ids.size()) ban = dban.getOrDefault(ids.get(r), ""); }
			liftB.setEnabled(!ban.isEmpty());
			wlB.setVisible(players[0]); kickB.setVisible(players[0]); revoke.setVisible(!players[0]); forget.setVisible(!players[0]);
		};
		byDevice.addActionListener(e -> { players[0] = false; cards.show(deck, "device"); note.setText(wrap(hint + " Suspend and Blacklist here are for this one device.")); labels.run(); });
		byPlayer.addActionListener(e -> { players[0] = true; cards.show(deck, "player"); note.setText(wrap(hint + " A player's setting goes to all their confirmed launchers (and ones they join from later), except a launcher that is itself Denied.")); labels.run(); });
		java.util.function.Consumer<Runnable> ui = r -> { if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r); };
		Runnable fetch = () -> {
			try {
				Map<String, Object> r = c.helper("GET", "/v1/admin/devices", null), pl = c.helper("GET", "/v1/admin/players", null), lg = c.helper("GET", "/v1/admin/log", null);
				ui.accept(() -> {
					if (!"200".equals(s(r.get("_code")))) { mcssLine.setIcon(dot(RED)); mcssLine.setText(s(r.get("error")).isEmpty() ? "Gatehouse Keeper said no (" + s(r.get("_code")) + ")" : s(r.get("error"))); note.setText(wrap(mcssLine.getText())); return; }
					int sel = table.getSelectedRow();
					String keep = sel >= 0 && sel < ids.size() ? ids.get(sel) : null, you = s(r.get("you"));
					model.setRowCount(0); ids.clear(); dban.clear();
					for (Map.Entry<String, Object> e : asMap(r.get("devices")).entrySet()) {
						Map<String, Object> v = asMap(e.getValue());
						ids.add(e.getKey());
						if ("machine".equals(s(v.get("ban_scope")))) dban.put(e.getKey(), s(v.get("ban_kind")));
						String who = s(v.get("player")).isEmpty() ? "" : s(v.get("player")) + ("true".equals(s(v.get("player_confirmed"))) ? "" : " (not confirmed)");
						String sm = s(v.get("state_mcss")), ss = s(v.get("state_server"));
						model.addRow(new Object[] {s(v.get("name")) + (e.getKey().equals(you) ? " (this device)" : ""), who, s(v.get("last_seen")), s(v.get("ban_text")),
								word.apply(s(v.get("state_machine"))), word.apply(sm), ss.equals("none") && sm.equals("allowed") ? "Allowed (with MCSS)" : word.apply(ss),
								"true".equals(s(v.get("admin"))) ? "Allowed" : "Not allowed", s(v.get("access_text"))});
						if (e.getKey().equals(keep)) table.setRowSelectionInterval(ids.size() - 1, ids.size() - 1);
					}
					int psel = ptable.getSelectedRow();
					String pkeep = psel >= 0 && psel < pnames.size() ? pnames.get(psel) : null;
					pmodel.setRowCount(0); pnames.clear(); pban.clear(); pwl.clear();
					for (Map.Entry<String, Object> e : asMap(pl.get("players")).entrySet()) {
						Map<String, Object> q = asMap(e.getValue());
						StringBuilder ds = new StringBuilder();
						if (q.get("devices") instanceof List<?> l) for (Object o : l) ds.append(ds.length() == 0 ? "" : ", ").append(o);
						String ban = s(q.get("ban")), why = s(q.get("ban_reason"));
						boolean wl = "true".equals(s(q.get("whitelisted")));
						String sm = s(q.get("state_mcss")), ss = s(q.get("state_server"));
						pnames.add(e.getKey()); pwl.put(e.getKey(), wl);
						if (!ban.isEmpty()) pban.put(e.getKey(), ban);
						pmodel.addRow(new Object[] {e.getKey(), ds.length() == 0 ? "(none yet)" : ds.toString(), s(q.get("last_seen")), wl ? "Yes" : "No",
								ban.isEmpty() ? "" : (ban.equals("blacklist") ? "Blacklisted" : "Suspended") + (why.isEmpty() ? "" : ": " + why),
								word.apply(s(q.get("state_machine"))), word.apply(sm), ss.equals("none") && sm.equals("allowed") ? "Allowed (with MCSS)" : word.apply(ss)});
						if (e.getKey().equals(pkeep)) ptable.setRowSelectionInterval(pnames.size() - 1, pnames.size() - 1);
					}
					labels.run();
					boolean mc = "true".equals(s(r.get("mcss"))), sv = "true".equals(s(r.get("minecraft")));
					mcssLine.setIcon(dot(mc ? GREEN : GRAY)); mcssLine.setText(mc ? "MCSS is running" : "MCSS isn't running");
					serverLine.setIcon(dot(sv ? GREEN : GRAY)); serverLine.setText(sv ? "The Minecraft server is running" : "The Minecraft server is off");
					keyLine.setText("true".equals(s(r.get("has_key"))) ? " " : "The Keeper isn't signed in to MCSS yet (server control needs it; done on the server machine).");
					StringBuilder sb = new StringBuilder();
					if (lg.get("log") instanceof List<?> l) for (Object x : l) sb.append(x).append('\n');
					if (!log.getText().equals(sb.toString())) { log.setText(sb.toString()); log.setCaretPosition(log.getDocument().getLength()); }
				});
			} catch (IOException | RuntimeException ex) {
				ui.accept(() -> { mcssLine.setIcon(dot(RED)); mcssLine.setText("Couldn't reach Gatehouse Keeper (is the server machine awake?)"); serverLine.setText(" "); note.setText(wrap(mcssLine.getText())); });
			}
		};
		Runnable load = () -> new Thread(fetch, "manage").start();
		java.util.function.BiConsumer<String, String> act = (path, body) -> new Thread(() -> {
			String m;
			try { Map<String, Object> r = c.helper("POST", path, body); m = "200".equals(s(r.get("_code"))) ? (s(r.get("message")).isEmpty() ? " " : s(r.get("message"))) : s(r.get("error")).isEmpty() ? "Gatehouse Keeper said no (" + s(r.get("_code")) + ")" : s(r.get("error")); }
			catch (IOException | RuntimeException ex) { m = "Couldn't reach Gatehouse Keeper"; }
			String msg = m;
			SwingUtilities.invokeLater(() -> note.setText(wrap(msg)));
			load.run();
		}, "manage-act").start();
		String[] permNames = {"machine", "mcss", "server"};
		// The launcher or player picked in the list, as the start of what's sent to the Keeper (or nothing picked).
		java.util.function.Supplier<String> target = () -> {
			if (players[0]) {
				int r = ptable.getSelectedRow();
				if (r < 0 || r >= pnames.size()) { note.setText("Pick a player in the list first."); return null; }
				return "\"player\":\"" + ServerControl.jsonEsc(pnames.get(r)) + "\"";
			}
			int r = table.getSelectedRow();
			if (r < 0 || r >= ids.size()) { note.setText("Pick a launcher in the list first."); return null; }
			return "\"id\":\"" + ServerControl.jsonEsc(ids.get(r)) + "\"";
		};
		// Click a permission: a small menu with the three states. Click a player's Whitelist: on or off the whitelist.
		java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter() {
			@Override public void mouseClicked(java.awt.event.MouseEvent e) {
				javax.swing.JTable tb = (javax.swing.JTable) e.getSource();
				boolean pl = tb == ptable;
				int r = tb.rowAtPoint(e.getPoint()), col = tb.columnAtPoint(e.getPoint()), first = pl ? 5 : 4;
				if (r < 0 || r >= (pl ? pnames.size() : ids.size())) return;
				tb.setRowSelectionInterval(r, r);
				String who = pl ? "\"player\":\"" + ServerControl.jsonEsc(pnames.get(r)) + "\"" : "\"id\":\"" + ServerControl.jsonEsc(ids.get(r)) + "\"";
				javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
				if (col >= first && col < first + 3) {
					for (String[] o : new String[][] {{"Allowed", "allowed"}, {"Not allowed", "none"}, {"Denied", "denied"}}) {
						javax.swing.JMenuItem it = new javax.swing.JMenuItem(o[0]);
						it.addActionListener(a -> act.accept(pl ? "/v1/admin/player/perm" : "/v1/admin/perm", "{" + who + ",\"perm\":\"" + permNames[col - first] + "\",\"state\":\"" + o[1] + "\"}"));
						menu.add(it);
					}
				} else if (pl && col == 3) {
					boolean on = pwl.getOrDefault(pnames.get(r), false);
					javax.swing.JMenuItem it = new javax.swing.JMenuItem(on ? "Remove from the whitelist" : "Add to the whitelist");
					it.addActionListener(a -> act.accept("/v1/admin/whitelist", "{" + who + ",\"value\":" + !on + "}"));
					menu.add(it);
				} else return;
				menu.show(tb, e.getX(), e.getY());
			}
		};
		table.addMouseListener(click); ptable.addMouseListener(click);
		// Kick, suspend, blacklist (each with an optional reason the player is shown) and lift.
		java.util.function.BiConsumer<String, String> banAct = (kind, title) -> {
			String who = target.get();
			if (who == null) return;
			String reason = "";
			if (!kind.isEmpty()) {
				String typed = javax.swing.JOptionPane.showInputDialog(f, "A reason the player is shown (optional):", title, javax.swing.JOptionPane.PLAIN_MESSAGE);
				if (typed == null) return;
				reason = typed.trim();
			}
			act.accept(kind.equals("kick") ? "/v1/admin/kick" : "/v1/admin/ban", "{" + who + ",\"kind\":\"" + kind + "\",\"reason\":\"" + ServerControl.jsonEsc(reason) + "\"}");
		};
		kickB.addActionListener(e -> banAct.accept("kick", "Kick from the server"));
		suspendB.addActionListener(e -> banAct.accept("suspend", "Suspend (can't start the game or use controls)"));
		blackB.addActionListener(e -> banAct.accept("blacklist", "Blacklist (also sees nothing about the server)"));
		liftB.addActionListener(e -> banAct.accept("", ""));
		wlB.addActionListener(e -> {
			String who = target.get();
			if (who != null) act.accept("/v1/admin/whitelist", "{" + who + ",\"value\":" + !pwl.getOrDefault(pnames.get(ptable.getSelectedRow()), false) + "}");
		});
		forget.addActionListener(e -> {
			int r = table.getSelectedRow();
			if (r < 0 || r >= ids.size()) { note.setText("Pick a launcher in the list first."); return; }
			if (javax.swing.JOptionPane.showConfirmDialog(f, "Forget this launcher? It can ask again later.", "Forget", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) act.accept("/v1/admin/forget", "{\"id\":\"" + ids.get(r) + "\"}");
		});
		revoke.addActionListener(e -> {
			int r = table.getSelectedRow();
			if (r < 0 || r >= ids.size()) { note.setText("Pick a launcher in the list first."); return; }
			if (javax.swing.JOptionPane.showConfirmDialog(f, wrap("Remove this launcher's server access? Its server and MCSS control are switched off, and it's told to forget any sign-in or key it has saved."),
					"Remove server access", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) act.accept("/v1/admin/revoke", "{\"id\":\"" + ids.get(r) + "\"}");
		});
		table.getSelectionModel().addListSelectionListener(e -> labels.run());
		ptable.getSelectionModel().addListSelectionListener(e -> labels.run());
		// Asked for while either tab is on screen: every few seconds, and at once when one is opened.
		Timer t = new Timer(4000, e -> { if (!f.isDisplayable()) ((Timer) e.getSource()).stop(); else if (access.isShowing() || activity.isShowing()) load.run(); });
		t.start();
		for (JPanel q : new JPanel[] {access, activity})
			q.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && q.isShowing()) load.run(); });
		// Both live under one tab of the launcher, Remote control panel, as two tabs of their own.
		javax.swing.JTabbedPane inner = new javax.swing.JTabbedPane();
		inner.addTab("Access Manager", access);
		inner.addTab("Keeper activity", activity);
		JPanel remote = new JPanel(new BorderLayout());
		remote.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
		remote.add(inner, BorderLayout.CENTER);
		labels.run();
		if (System.getenv("OJ_POPUP_PNG") != null && c.permAdmin) fetch.run();   // (test pictures: filled in once)
		return new JPanel[] {remote};
	}

	/** The Client mods tab: the pack's optional mods (extras/mod-toggles.json: only ones the game still joins the server
	 *  without), each on or off for this device. The choices go under "mods" in pack-options.json, everything else in
	 *  that file kept as it is; the update applies them, so a change takes effect after Restart. Null when the pack
	 *  has no optional mods. */
	private static JComponent modsPanel(Path dir) {
		List<?> list;
		try { list = parse(read(dir.resolve("extras/mod-toggles.json"))) instanceof List<?> l ? l : List.of(); } catch (RuntimeException e) { return null; }
		if (list.isEmpty()) return null;
		Path options = dir.resolve("pack-options.json");
		Map<String, Object> saved;
		try { saved = asMap(asMap(parse(read(options))).get("mods")); } catch (RuntimeException e) { saved = new LinkedHashMap<>(); }
		Map<String, Boolean> choice = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : saved.entrySet()) choice.put(e.getKey(), !"false".equals(s(e.getValue())));
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		JLabel top = new JLabel(wrap("Optional mods on this device. Switching one off never stops you joining the server. A change is applied when the update runs again: press Restart."));
		top.setForeground(new Color(0x8F98A0));
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.add(top); box.add(Box.createVerticalStrut(8));
		JLabel note = new JLabel(" ");
		note.setFont(note.getFont().deriveFont(Font.BOLD));
		Map<String, javax.swing.JCheckBox> boxes = new LinkedHashMap<>();
		Map<String, List<?>> needs = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		// A mod that needs another one is off (and can't be ticked) while that one is off.
		Runnable follow = () -> {
			for (int pass = 0; pass < boxes.size(); pass++)
				for (Map.Entry<String, javax.swing.JCheckBox> e : boxes.entrySet()) {
					String missing = "";
					for (Object n : needs.get(e.getKey())) { javax.swing.JCheckBox nb = boxes.get(s(n)); if (nb != null && !(nb.isSelected() && nb.isEnabled())) missing = names.get(s(n)); }
					e.getValue().setEnabled(missing.isEmpty());
					e.getValue().setToolTipText(missing.isEmpty() ? null : "Needs " + missing + ", which is off");
				}
		};
		for (Object o : list) {
			Map<String, Object> m = asMap(o);
			String key = s(m.get("key"));
			if (key.isEmpty()) continue;
			boolean on = choice.containsKey(key) ? choice.get(key) : !"false".equals(s(m.get("default")));
			javax.swing.JCheckBox cb = new javax.swing.JCheckBox(s(m.get("name")), on);
			cb.setFont(cb.getFont().deriveFont(Font.BOLD, 13f));
			cb.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel d = new JLabel(wrap(s(m.get("description")) + ("false".equals(s(m.get("default"))) ? " (Off unless you switch it on.)" : "")));
			d.setForeground(new Color(0x8F98A0));
			d.setBorder(BorderFactory.createEmptyBorder(0, 24, 6, 0));
			d.setAlignmentX(Component.LEFT_ALIGNMENT);
			boxes.put(key, cb); names.put(key, s(m.get("name")));
			needs.put(key, m.get("needs") instanceof List<?> l ? l : List.of());
			cb.addActionListener(e -> {
				choice.put(key, cb.isSelected());
				follow.run();
				StringBuilder sb = new StringBuilder("{");
				for (Map.Entry<String, Boolean> c : choice.entrySet()) sb.append(sb.length() > 1 ? ", " : "").append('"').append(ServerControl.jsonEsc(c.getKey())).append("\": ").append(c.getValue());
				String now = read(options), next = withMember(now, "mods", sb.append("}").toString());
				try {
					if (next == null) throw new IOException("pack-options.json isn't in a form this can change");
					Files.writeString(options, next, StandardCharsets.UTF_8);
					note.setForeground(new Color(0xE9B949));
					note.setText("Saved. Press Restart to apply it before you play.");
				} catch (IOException ex) { note.setForeground(RED); note.setText("Couldn't save: " + ex.getMessage()); }
			});
			box.add(cb); box.add(d);
		}
		follow.run();
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.add(Box.createVerticalStrut(4)); box.add(note);
		JPanel inner = new JPanel(new BorderLayout());
		inner.add(box, BorderLayout.NORTH);
		JScrollPane sc = new JScrollPane(inner);
		sc.setBorder(null);
		sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sc.getVerticalScrollBar().setUnitIncrement(16);
		JPanel tab = new JPanel(new BorderLayout());
		tab.setBorder(BorderFactory.createLineBorder(new Color(0x2F4860)));
		tab.add(sc, BorderLayout.CENTER);
		return tab;
	}

	/** JSON text with one top-level member set to the given JSON value, everything else left exactly as written (true
	 *  and false stay true and false: the sync scripts need that). Empty text gives a new object; null when the text
	 *  isn't an object. */
	static String withMember(String json, String key, String value) {
		String member = "\"" + key + "\": " + value;
		if (json.isBlank()) return "{\n  " + member + "\n}\n";
		String tr = json.trim();
		if (!tr.startsWith("{") || !tr.endsWith("}")) return null;
		int depth = 0;
		for (int i = 0; i < json.length(); i++) {
			char ch = json.charAt(i);
			if (ch == '"') {
				int end = stringEnd(json, i);
				if (end < 0) return null;
				int after = end + 1;
				while (after < json.length() && Character.isWhitespace(json.charAt(after))) after++;
				if (depth == 1 && after < json.length() && json.charAt(after) == ':' && json.substring(i + 1, end).equals(key)) {
					int vs = after + 1;
					while (vs < json.length() && Character.isWhitespace(json.charAt(vs))) vs++;
					int ve = vs, d = 0;
					for (; ve < json.length(); ve++) {
						char x = json.charAt(ve);
						if (x == '"') { ve = stringEnd(json, ve); if (ve < 0) return null; }
						else if (x == '{' || x == '[') d++;
						else if (x == '}' || x == ']') { if (d == 0) break; d--; }
						else if (x == ',' && d == 0) break;
					}
					return json.substring(0, vs) + value + json.substring(ve).replaceFirst("^\\s*(?=[,}])", "");
				}
				i = end;
			} else if (ch == '{' || ch == '[') depth++;
			else if (ch == '}' || ch == ']') depth--;
		}
		String head = json.substring(0, json.lastIndexOf('}')).stripTrailing();
		return head + (head.endsWith("{") ? "" : ",") + "\n  " + member + "\n}\n";
	}
	/** Where the string that opens at this quote closes, or -1. */
	private static int stringEnd(String json, int open) {
		for (int i = open + 1; i < json.length(); i++) {
			if (json.charAt(i) == '\\') i++;
			else if (json.charAt(i) == '"') return i;
		}
		return -1;
	}

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
		JLabel l = new JLabel("<html>" + esc(label) + (detail == null || detail.isEmpty() ? "" : "<font color='#8f98a0'>&nbsp;&nbsp;" + esc(detail) + "</font>") + "</html>", dot(c), JLabel.LEFT);
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
		sp.setBorder(BorderFactory.createLineBorder(new Color(0x2F4860)));
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
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = reuse != null ? reuse : new JFrame(TOOL + " \u2013 " + NAME);
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
			// Under the list, where setup added Steam shortcuts: a button that brings them up to date with the pack (they
			// start through the Gatehouse launcher, the launcher's own name and art, the controller layout), without
			// running setup again. Steam has to be closed: it writes its shortcut list itself when it exits.
			JPanel clientTab = new JPanel(new BorderLayout(0, 6));
			clientTab.add(scroller(deviceRows, big || serverNews), BorderLayout.CENTER);
			if (SteamUpdate.available(dir)) {
				JButton steamB = new JButton("Update Steam shortcuts");
				steamB.setToolTipText("Brings this device's Steam shortcuts, art and controller layout up to date with the pack");
				String about = "Brings this device's Steam shortcuts up to date with the pack: its art and controller layout, and how they start. Steam has to be closed.";
				JLabel steamNote = new JLabel("<html><div style='width:520px'>" + esc(about) + "</div></html>");
				steamNote.setForeground(new Color(0x8F98A0));
				java.util.function.BiConsumer<Color, String> say = (col, msg) -> { steamNote.setForeground(col); steamNote.setText("<html><div style='width:520px'>" + esc(msg).replace("\n", "<br>") + "</div></html>"); };
				steamB.addActionListener(e -> {
					steamB.setEnabled(false);
					new Thread(() -> {
						String running = SteamUpdate.steamRunning();
						if (running.equals("unknown")) {
							// (Inside Prism's Flatpak the launcher can't see whether Steam is running: the player says.)
							int[] answer = {javax.swing.JOptionPane.CANCEL_OPTION};
							try {
								SwingUtilities.invokeAndWait(() -> answer[0] = javax.swing.JOptionPane.showConfirmDialog(f, "<html><div style='width:400px'>Steam has to be closed for this, or it undoes the change when it exits.<br><br>"
										+ "On a Steam Deck: switch to Desktop Mode, quit Steam there (Steam menu, Exit), start this pack from Prism Launcher, and press this button.<br><br>Is Steam closed now?</div></html>",
										"Update Steam shortcuts", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE));
							} catch (Exception ignored) {}
							running = answer[0] == javax.swing.JOptionPane.YES_OPTION ? "no" : "asked";
						}
						Color col; String msg;
						if (running.equals("yes")) { col = new Color(0xE9B949); msg = "Steam is running. Quit Steam (Steam menu, Exit), then press this again: Steam would undo the change when it closes. This window can stay open."; }
						else if (running.equals("asked")) { col = new Color(0x8F98A0); msg = about; }
						else try { col = new Color(0x8BD46E); msg = String.join("\n", SteamUpdate.run(dir)) + "\nStart Steam to see it."; }
						catch (IOException | RuntimeException ex) { col = new Color(0xFF7B72); msg = "Not updated: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()); }
						Color c2 = col; String m2 = msg;
						SwingUtilities.invokeLater(() -> { say.accept(c2, m2); steamB.setEnabled(true); });
					}, "steam-update").start();
				});
				JPanel steamRow = new JPanel(new BorderLayout(10, 0));
				JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)); left.add(steamB);
				steamRow.add(left, BorderLayout.WEST); steamRow.add(steamNote, BorderLayout.CENTER);
				clientTab.add(steamRow, BorderLayout.SOUTH);
			}
			tabs.addTab("Client updates", clientTab);
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
			tabs.addTab(serverNews ? "Server updates (new)" : "Server updates", serverTab);
			// Connection: one tab holding Server controls (how the server machine, MCSS and the server are, always shown;
			// what this device may do about them, and asking for more) and Network diagnosis (whether this device can
			// reach the server machine, and what to do when it can't).
			javax.swing.JTabbedPane connTabs = new javax.swing.JTabbedPane();
			JPanel diagnosis = new JPanel(new BorderLayout());
			diagnosis.setBorder(BorderFactory.createLineBorder(new Color(0x2F4860)));
			diagnosis.add(Connection.panel(dir, control, f, System.getenv("OJ_POPUP_PNG") == null), BorderLayout.CENTER);
			JPanel connectionTab = new JPanel(new BorderLayout());
			connectionTab.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
			connectionTab.add(connTabs, BorderLayout.CENTER);
			tabs.addTab("Connection", connectionTab);
			// Play only works while the server is online (checked every few seconds; with no server set up for the pack
			// there's nothing to wait for).
			boolean[] serverLive = {!control.configured()};
			Runnable[] playState = {() -> {}};
			java.util.function.Consumer<String[]> live = r -> {
				String phase = r.length > 2 ? r[2] : "", ping = r.length > 3 ? r[3] : "", pl = r.length > 4 ? r[4] : "";
				serverLive[0] = phase.equals("online");
				playState[0].run();
				String text;
				switch (phase) {
					case "online" -> text = "Right now: online" + (ping.isEmpty() ? "" : ", " + ping + " ms") + (pl.isEmpty() ? "" : ", " + pl + " players");
					case "starting" -> text = "Right now: starting";
					case "stopping" -> text = "Right now: stopping";
					case "off" -> text = "Right now: the server machine is awake, but the Minecraft server is off (Connection, Server controls)";
					case "nomcss" -> text = "Right now: the server machine is awake, but MCSS isn't running, so the server is off (Connection, Server controls, Start MCSS)";
					case "asleep" -> text = "Right now: the server machine isn't answering, so it may be asleep or off (Connection, Server controls, Wake server machine). Below is the server as last recorded.";
					default -> text = "Right now: " + r[1];
				}
				liveLine.setIcon(dot(colour(r[0])));
				liveLine.setText("<html><div style='width:470px'>" + esc(text) + "</div></html>");
			};
			// Server controls, a tab of their own: status, wake, start, stop, the panel, and this device's access.
			if (control.configured()) {
				Runnable[] syncTabs = {() -> {}};
				JComponent[] parts = controls(control, f, () -> hold[0].run(), System.getenv("OJ_POPUP_PNG") == null, live, () -> { syncTabs[0].run(); playState[0].run(); });
				JPanel[] serverTabs = new JPanel[3];
				for (int i = 0; i < 3; i++) {
					JPanel tab = new JPanel(new BorderLayout());
					tab.setBorder(BorderFactory.createLineBorder(new Color(0x2F4860)));
					JPanel inner = new JPanel(new BorderLayout());
					inner.add(parts[i], BorderLayout.NORTH);
					JScrollPane sc = new JScrollPane(inner);
					sc.setBorder(null);
					sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
					sc.getVerticalScrollBar().setUnitIncrement(16);
					tab.add(sc, BorderLayout.CENTER);
					serverTabs[i] = tab;
				}
				connTabs.addTab("Server controls", serverTabs[0]);
				connTabs.addTab("Network diagnosis", diagnosis);
				tabs.addTab("Server config", serverTabs[1]);
				JComponent modsTab = modsPanel(dir);
				if (modsTab != null) tabs.addTab("Client mods", modsTab);
				// Remote control panel: only for a device the owner allowed Keeper control (in the control panel on the
				// server machine); it comes and goes as that's allowed or removed.
				syncTabs[0] = () -> {
					boolean wantM = control.permAdmin && control.ban.isEmpty(), haveM = tabs.indexOfComponent(serverTabs[2]) >= 0;
					if (wantM && !haveM) tabs.addTab("Remote control panel", serverTabs[2]);
					else if (!wantM && haveM) { if (tabs.getSelectedComponent() == serverTabs[2]) tabs.setSelectedIndex(0); tabs.remove(serverTabs[2]); }
				};
				syncTabs[0].run();
			} else {
				connTabs.addTab("Network diagnosis", diagnosis);
				JComponent modsTab = modsPanel(dir);
				if (modsTab != null) tabs.addTab("Client mods", modsTab);
			}
			p.add(tabs, BorderLayout.CENTER);
			PlayButton play = new PlayButton("Play");
			JLabel playWhy = new JLabel(" ");
			playWhy.setFont(playWhy.getFont().deriveFont(Font.PLAIN, 12f));
			playWhy.setForeground(new Color(0x8F98A0));
			JPanel bottom = new JPanel(new BorderLayout());
			// What the colours mean.
			JPanel legend = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			for (Object[] k : new Object[][] {{GREEN, "OK / added"}, {BLUE, "updated"}, {GRAY, "off / removed"}, {AMBER, "not checked"}, {RED, "failed"}}) {
				JLabel l = new JLabel((String) k[1], dot((Color) k[0]), JLabel.LEFT);
				l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
				l.setForeground(new Color(0x8F98A0));
				legend.add(l);
			}
			bottom.add(legend, BorderLayout.WEST);
			// Beside Play: run the update again, or don't start the game.
			JButton again = new PlayButton("Restart", YELLOW, INK, 14f), quit = new PlayButton("Quit", QUIT_RED, Color.WHITE, 14f);
			again.setToolTipText("Run the update again");
			quit.setToolTipText("Don't start the game");
			again.addActionListener(e -> { exitCode = RESTART; f.dispose(); });
			quit.addActionListener(e -> { exitCode = QUIT; f.dispose(); });
			JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			for (JButton b : new JButton[] {again, quit}) right.add(b);
			right.add(play);
			// The line about Play (why it's grey, or what a request did) sits above the buttons, wrapped to their width.
			JPanel rightCol = new JPanel(new BorderLayout(0, 3));
			playWhy.setHorizontalAlignment(JLabel.RIGHT);
			rightCol.add(playWhy, BorderLayout.NORTH);
			rightCol.add(right, BorderLayout.SOUTH);
			bottom.add(rightCol, BorderLayout.EAST);
			p.add(bottom, BorderLayout.SOUTH);
			// The button: "Next: server changes" while there's something new on the Server tab that hasn't been shown;
			// "Play" after. Nothing happens on its own: no countdown, the game starts only when the player presses Play.
			boolean[] serverShown = {!serverNews};
			// While the server isn't online, the same button is "Request server": it asks Gatehouse Keeper on the server
			// machine to let the owner know (a chime and the control panel there). It goes back to Play by itself when the
			// server comes up. One request a minute.
			// The same goes for a player who isn't on the whitelist ("Request whitelist"), and for a launcher the owner
			// suspended or blacklisted ("Request unsuspend", "Request unblacklist", with an optional message): the game
			// doesn't start until the owner says so. Granted: a chime. Denied: the button flashes red, with the reason.
			long[] requestWait = {0};
			String[] requestNote = {""}, noteFor = {""}, asked = {""};
			java.util.function.Supplier<String> mode = () -> !serverShown[0] ? "next" : control.ban.equals("blacklist") ? "unblacklist" : control.ban.equals("suspend") ? "unsuspend"
					: control.hasHelper() && control.whitelisted.equals("no") ? "whitelist" : !serverLive[0] && control.hasHelper() ? "server" : "play";
			Runnable label = () -> {
				if (!asked[0].isEmpty()) {
					String answer = control.reqStates.getOrDefault(asked[0], "");
					if (answer.equals("granted")) { chime(); requestNote[0] = asked[0].equals("server") ? "The owner is turning the server on." : "Granted by the owner."; asked[0] = ""; requestWait[0] = 0; }
					else if (answer.equals("denied")) {
						String why = control.reqReasons.getOrDefault(asked[0], "");
						requestNote[0] = "Request denied by the owner" + (why.isEmpty() ? ". You can ask again." : ": " + why);
						asked[0] = ""; requestWait[0] = 0;
						play.flash();
					}
				}
				String m = mode.get();
				play.setText(switch (m) { case "next" -> "Next: server updates"; case "unblacklist" -> "Request unblacklist"; case "unsuspend" -> "Request unsuspend";
					case "whitelist" -> "Request whitelist"; case "server" -> "Request server"; default -> "Play"; });
				// "Next" only turns the page, so it always works; Play waits for the server; a request, one a minute.
				play.setEnabled(m.equals("next") || (m.equals("play") ? serverLive[0] : System.currentTimeMillis() >= requestWait[0]));
				String who = control.banScope.equals("user") ? "Your account" + (control.player().isEmpty() ? "" : " (" + control.player() + ")") : "This device";
				String reason = control.banReason.isEmpty() ? "." : ": " + control.banReason + (control.banReason.matches(".*[.!?]$") ? "" : ".");
				String why = switch (m) {
					case "next" -> "";
					case "play" -> serverLive[0] ? "" : "Play lights up when the server is online";
					case "unblacklist" -> who + " is blacklisted on this server" + reason + " Request unblacklist asks the owner to let you back.";
					case "unsuspend" -> who + " is suspended on this server" + reason + " Request unsuspend asks the owner to let you back.";
					case "whitelist" -> "You aren't on the server's whitelist yet. Request whitelist asks the owner to add you.";
					default -> "The server is off. Request server asks the owner to turn it on.";
				};
				if (!requestNote[0].isEmpty() && noteFor[0].equals(m)) why = requestNote[0];
				playWhy.setText(why.isEmpty() ? " " : "<html><div style='width:300px;text-align:right'>" + esc(why) + "</div></html>");
			};
			playState[0] = label;
			Runnable next = () -> {
				if (!play.isEnabled()) return;
				String m = mode.get();
				if (m.equals("next")) { serverShown[0] = true; tabs.setSelectedIndex(1); label.run(); }
				else if (m.equals("play")) { exitCode = 0; f.dispose(); }
				else {
					String message = "";
					if (m.equals("unblacklist") || m.equals("unsuspend")) {
						String typed = javax.swing.JOptionPane.showInputDialog(f, "A message for the owner (optional):", play.getText(), javax.swing.JOptionPane.PLAIN_MESSAGE);
						if (typed == null) return;
						message = typed.trim();
					}
					String text = message;
					requestWait[0] = System.currentTimeMillis() + 60_000;
					requestNote[0] = "Asking the owner\u2026"; noteFor[0] = m;
					label.run();
					new Thread(() -> {
						String msg;
						boolean ok = false;
						try {
							Map<String, Object> r = control.request(m, text);
							msg = s(r.get("message")).isEmpty() ? (s(r.get("error")).isEmpty() ? "The server machine didn't take the request" : s(r.get("error"))) : s(r.get("message"));
							ok = "ok".equals(s(r.get("result")));
						} catch (IOException | RuntimeException ex) { msg = "Couldn't reach the server machine (it may be asleep: Connection, Server controls can wake it, if you're allowed)"; }
						String shown = msg;
						boolean took = ok;
						SwingUtilities.invokeLater(() -> {
							if (took) { control.reqStates.put(m, "asked"); asked[0] = m; } else requestWait[0] = 0;
							requestNote[0] = shown; noteFor[0] = m; label.run();
						});
					}, "request").start();
				}
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
			fit(f, true);
			f.validate();
			// Testing: OJ_POPUP_PNG=<file> saves a picture of each tab (<file>, and <file> with "-server") and their text
			// in <file>.txt, instead of showing the window.
			String png = System.getenv("OJ_POPUP_PNG");
			if (png != null && !png.isEmpty()) {
				Path choice = dir.resolve("test-popup-choice.txt");
				String ch = read(choice).trim();
				if (!ch.isEmpty()) { try { Files.delete(choice); } catch (IOException ignored) {} exitCode = ch.equals("quit") ? QUIT : ch.equals("restart") ? RESTART : 0; }
				try {
					// Every other tab too: <file>-controls.png and -connection.png (inside Connection), -config.png,
					// -mods.png, -manage.png.
					String stem = png.replaceAll("\\.png$", "");
					for (int i = 2; i < tabs.getTabCount(); i++) {
						tabs.setSelectedIndex(i);
						String title = tabs.getTitleAt(i);
						List<String> shots = new ArrayList<>();
						if (title.equals("Connection")) for (int j = 0; j < connTabs.getTabCount(); j++) shots.add(connTabs.getTitleAt(j).startsWith("Server") ? "-controls.png" : "-connection.png");
						else shots.add(title.startsWith("Server config") ? "-config.png" : title.startsWith("Client mods") ? "-mods.png" : "-manage.png");
						for (int j = 0; j < shots.size(); j++) {
							if (title.equals("Connection")) connTabs.setSelectedIndex(j);
							p.validate();
							java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
							java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
							javax.imageio.ImageIO.write(img, "png", new java.io.File(stem + shots.get(j)));
						}
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
			f.setVisible(true);
			bringForwardOnce(f);
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
