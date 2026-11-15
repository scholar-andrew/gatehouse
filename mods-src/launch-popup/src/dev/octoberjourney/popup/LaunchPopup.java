package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
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
 *   pack-version-seen.txt    the version this device last showed (written here afterwards)
 *   pack-sync-news.txt       what the sync changed on this device this launch (mods switched, Steam art, server list)
 *   pack-options.json        "popup_updates" / "popup_no_updates": false turns that kind of pop-up off (default on)
 *
 * After an update it shows the notes for every version since the last one seen, and waits (closing itself after 90
 * seconds, so a launch can't get stuck). With nothing new it says so and closes after 5 seconds.
 */
public final class LaunchPopup {
	private static final int UPDATE_SECONDS = 90, QUIET_SECONDS = 5;

	record Version(String version, String date, List<String> notes) {}

	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args.length > 0 ? args[0] : ".");
		String status = args.length > 1 ? args[1] : "ok";
		List<Version> log = changelog(dir.resolve("extras/changelog.txt"));
		String current = log.isEmpty() ? null : log.get(0).version();
		String seen = read(dir.resolve("pack-version-seen.txt")).trim();
		List<String> news = new ArrayList<>();
		for (String l : read(dir.resolve("pack-sync-news.txt")).split("\\R")) if (!l.isBlank()) news.add(l.trim());
		String options = read(dir.resolve("pack-options.json"));

		boolean updated = current != null && !current.equals(seen);
		// The versions to tell about: everything newer than the one last seen (only the newest on a first run).
		List<Version> fresh = new ArrayList<>();
		if (updated) for (Version v : log) { if (v.version().equals(seen)) break; fresh.add(v); if (seen.isEmpty()) break; }

		boolean big = updated || !news.isEmpty();
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
		else headline = "October Journey" + playing + (news.isEmpty() ? ": no updates" : "");
		String sub;
		if (status.equals("offline")) sub = "The pack couldn't be reached (Tailscale or internet). Playing" + playing + " as installed.";
		else if (status.equals("failed")) sub = "Starting with the mods already installed. Details are in pack-sync.log in the game folder.";
		else if (updated) sub = fresh.size() > 1 ? "What's new since " + seen + ":" : "What's new:";
		else if (!news.isEmpty()) sub = "No pack updates, but some of your settings were applied:";
		else sub = "You're up to date.";

		StringBuilder body = new StringBuilder();
		for (Version v : fresh) {
			if (body.length() > 0) body.append('\n');
			body.append(v.version()).append(v.date().isEmpty() ? "" : "  (" + v.date() + ")").append('\n');
			for (String n : v.notes()) body.append("  • ").append(n).append('\n');
		}
		if (!news.isEmpty()) {
			if (body.length() > 0) body.append('\n');
			body.append("On this device this launch:\n");
			for (String n : news) body.append("  • ").append(n).append('\n');
		}
		show(headline, sub, body.toString().stripTrailing(), big ? UPDATE_SECONDS : QUIET_SECONDS);
	}

	private static void show(String headline, String sub, String body, int seconds) throws Exception {
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
			if (!body.isEmpty()) {
				JTextArea t = new JTextArea(body);
				t.setEditable(false);
				t.setFocusable(false);
				t.setLineWrap(true);
				t.setWrapStyleWord(true);
				t.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
				t.setBackground(new Color(0xF4F4F4));
				t.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
				JScrollPane sp = new JScrollPane(t);
				sp.setPreferredSize(new Dimension(560, 300));
				p.add(sp, BorderLayout.CENTER);
			}
			JButton play = new JButton();
			play.setFont(play.getFont().deriveFont(Font.BOLD, 15f));
			JPanel bottom = new JPanel(new BorderLayout());
			bottom.add(play, BorderLayout.EAST);
			p.add(bottom, BorderLayout.SOUTH);
			int[] left = {seconds};
			Runnable label = () -> play.setText("Play  (" + left[0] + ")");
			label.run();
			Timer timer = new Timer(1000, e -> { if (--left[0] <= 0) f.dispose(); else label.run(); });
			play.addActionListener(e -> f.dispose());
			// Enter, Space or Escape (keyboard, or a controller mapped to keys) also continue.
			for (int key : new int[] {KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE}) {
				p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), "play");
			}
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
			if (body.isEmpty()) { f.setSize(Math.max(f.getWidth(), 460), f.getHeight()); f.validate(); }
			// Testing: OJ_POPUP_PNG=<file> saves a picture of the window there instead of showing it.
			String png = System.getenv("OJ_POPUP_PNG");
			if (png != null && !png.isEmpty()) {
				try {
					java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D g = img.createGraphics();
					p.paint(g);
					g.dispose();
					javax.imageio.ImageIO.write(img, "png", new java.io.File(png));
					Files.writeString(Path.of(png + ".txt"), f.getTitle() + "\n" + headline + "\n" + sub + "\n" + body + "\n" + play.getText() + "\n", StandardCharsets.UTF_8);
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

	private static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("﻿", ""); } catch (IOException | RuntimeException e) { return ""; }
	}
}
