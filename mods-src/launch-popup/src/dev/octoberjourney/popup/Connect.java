package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import dev.gatehouse.look.Quiet;

/** "Connect to this Gatekeeper": the steps between having a pairing and the Gatekeeper answering, each with how it
 *  stands right now and what to do about it. It looks again every couple of seconds, so a step ticks by itself once
 *  it's done somewhere else (Tailscale's own window, the browser).
 *    1. Tailscale is running on this device and connected (this device has a Tailscale address).
 *    2. The owner's invite is accepted (their link comes with the pairing; nothing here can see whether it was, so
 *       this ticks when step 3 does).
 *    3. This device can reach the Gatekeeper.
 *    4. The Gatekeeper knows this device.
 *  Nothing is installed, signed in to or accepted from here: the buttons open Tailscale's own download page,
 *  Tailscale itself, and the owner's invite in the browser.
 *  Testing: OJ_CONNECT_FAKE=&lt;tailscale&gt;&lt;reach&gt;&lt;pair&gt; (each 0 or 1) stands in for the three checks. */
final class Connect {
	private Connect() {}

	static final String DOWNLOAD = "https://tailscale.com/download";
	private static final Color GREY = new Color(0xA9B5C1);

	/** An invite is only ever opened if it is one of Tailscale's own links. */
	static String invite(String link) {
		String l = link == null ? "" : link.trim();
		return l.matches("https://login\\.tailscale\\.com/[A-Za-z0-9/_?=&.%-]+") ? l : "";
	}

	/** Does this device have a Tailscale address (one in 100.64.0.0/10) on an interface that is up? */
	static boolean tailscaleUp() {
		String fake = System.getenv("OJ_CONNECT_FAKE");
		if (fake != null && fake.length() > 0) return fake.charAt(0) == '1';
		try {
			for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
				if (!ni.isUp()) continue;
				for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
					byte[] b = a.getAddress();
					if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40) return true;
				}
			}
		} catch (IOException | RuntimeException ignored) { Quiet.seen(ignored); }
		return false;
	}

	/** Tailscale's own window on Windows, when it's installed in the usual place (null otherwise). */
	private static Path tailscaleApp() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
		String pf = System.getenv("ProgramFiles");
		Path p = Path.of(pf == null ? "C:\\Program Files" : pf, "Tailscale", "tailscale-ipn.exe");
		return Files.isRegularFile(p) ? p : null;
	}

	static boolean reaches(String url) {
		String fake = System.getenv("OJ_CONNECT_FAKE");
		if (fake != null && fake.length() > 1) return fake.charAt(1) == '1';
		try (java.net.Socket s = new java.net.Socket()) {
			java.net.URI u = java.net.URI.create(url);
			s.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 25570), 2500);
			return true;
		} catch (IOException | RuntimeException e) { return false; }
	}

	/** One look at everything: {tailscale, reach, paired}. "hello" introduces this device to the Gatekeeper and says
	 *  how it answered (empty or an error: it didn't). */
	static boolean[] look(String url, Callable<String> hello) {
		boolean ts = !onTailscale(url) || tailscaleUp(), reach = reaches(url), paired = false;   // (R79: off Tailscale, its step doesn't apply)
		String fake = System.getenv("OJ_CONNECT_FAKE");
		if (fake != null && fake.length() > 2) paired = fake.charAt(2) == '1';
		else if (reach) try { String st = hello.call(); paired = st != null && !st.isEmpty(); } catch (Exception ignored) { Quiet.seen(ignored); }
		return new boolean[] {ts || reach, reach, paired};   // (reaching it over Tailscale proves Tailscale, however it's installed)
	}

	/** R79: is the Gatekeeper at a Tailscale address (100.64.0.0/10)? Otherwise it is on a home or another private
	 *  network, and Tailscale's steps don't apply. */
	static boolean onTailscale(String url) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:[a-z]+://)?100\\.(\\d{1,3})\\.").matcher(url.toLowerCase(java.util.Locale.ROOT));
		if (!m.find()) return false;
		int y = Integer.parseInt(m.group(1));
		return y >= 64 && y <= 127;
	}
	static boolean homeAddress(String url) { return url.replaceFirst("(?i)^[a-z]+://", "").matches("(10|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\..*"); }

	private static JLabel row(String html) {
		JLabel l = new JLabel(html);
		l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }
	private static String line(boolean done, boolean waiting, String title, String detail) {
		String colour = done ? "#3FB950" : waiting ? "#A9B5C1" : "#E3B341", word = done ? "Done" : waiting ? "Waiting for the step above" : "To do";
		return "<html><div style='width:520px'><span style='color:" + colour + "'>\u25CF</span>&nbsp; <b>" + esc(title)
				+ "</b> &nbsp;<span style='color:" + colour + "'>" + word + "</span>"
				+ (detail.isEmpty() ? "" : "<br><span style='color:#A9B5C1'>" + esc(detail) + "</span>") + "</div></html>";
	}

	/** The steps as a panel; "fill" (returned in fillOut[0]) shows a look's result in it. */
	static JPanel panel(String url, String name, String share, Runnable[] fillOut, boolean[][] state, Component owner) {
		String invite = invite(share), shown = url.replaceFirst("(?i)^https?://", ""), who = name.isEmpty() ? "the Gatekeeper at " + shown : name;
		boolean windows = System.getProperty("os.name", "").startsWith("Windows");
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		boolean ts0 = onTailscale(url);
		JLabel intro = new JLabel("<html><div style='width:520px'>" + esc(who)
				+ (ts0 ? " is reached over Tailscale, a private network between its owner's machine and the devices they let in. "
				: homeAddress(url) ? " is on its owner's home network: this device has to be on the same one (the same Wi-Fi or cable). "
				: " is reached over the internet, or over its owner's own private network (then this device has to be on it too). ")
				+ "These are the steps; each one ticks by itself when it's done.</div></html>");
		intro.setAlignmentX(Component.LEFT_ALIGNMENT);
		list.add(intro);
		JLabel s1 = row(""), s2 = row(""), s3 = row(""), s4 = row("");
		// 1. Tailscale
		JButton get = new JButton("Get Tailscale"), openTs = new JButton("Open Tailscale");
		get.setToolTipText("Opens Tailscale's own download page (" + DOWNLOAD + ") in your browser");
		get.addActionListener(e -> dev.gatehouse.look.Links.openChecked(get, DOWNLOAD));
		openTs.setToolTipText("Opens Tailscale on this device, to sign in or connect");
		openTs.addActionListener(e -> {
			Path app = tailscaleApp();
			try { if (app == null) throw new IOException("Tailscale's program wasn't found"); new ProcessBuilder(app.toString()).start(); }
			catch (IOException ex) { dev.gatehouse.look.InWindow.showMessageDialog(openTs,
					"<html><div style='width:380px'>Tailscale couldn't be opened from here (" + ex.getMessage()
					+ "). Open it yourself: it is in the Start menu, and by the clock when it is running.</div></html>", "Open Tailscale",
					javax.swing.JOptionPane.PLAIN_MESSAGE); }
		});
		JPanel b1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		b1.add(get); b1.add(openTs);
		b1.setAlignmentX(Component.LEFT_ALIGNMENT);
		// 2. the invite
		JTextField inviteF = new JTextField(invite, 30);
		inviteF.setEditable(false);
		JButton openInvite = new JButton("Open invite"), copyInvite = new JButton("Copy");
		openInvite.setToolTipText("Opens the owner's Tailscale invite in your browser: sign in with your own Tailscale account and accept");
		openInvite.addActionListener(e -> dev.gatehouse.look.Links.openChecked(openInvite, invite));
		copyInvite.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(invite), null));
		JPanel b2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		b2.add(openInvite); b2.add(inviteF); b2.add(copyInvite);
		b2.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Component c : new Component[] {s1, b1, s2, b2, s3, s4}) list.add(c);
		fillOut[0] = () -> {
			boolean ts = state[0][0], reach = state[0][1], paired = state[0][2];
			s1.setText(line(ts, false, "Tailscale is running on this device and connected",
					ts ? "" : windows ? (tailscaleApp() != null
							? "Tailscale is installed. Open it and sign in with your own Tailscale account (making one is free), then switch it on."
							: "Install Tailscale from its own site, then sign in with your own Tailscale account (making one is free).")
							: "Install Tailscale and sign in with your own account. On a Steam Deck that's done in Desktop Mode, following Tailscale's own guide for the Steam Deck (it needs the system password)."));
			get.setVisible(!ts && tailscaleApp() == null); openTs.setVisible(!ts && tailscaleApp() != null);
			b1.setVisible(get.isVisible() || openTs.isVisible());
			s2.setText(line(reach, !ts, "You've accepted the owner's invite",
					reach ? "" : invite.isEmpty()
							? "This pairing didn't come with an invite. Ask the owner for their Tailscale invite link (they keep it in Gatekeeper, under Host setup), or to share their machine with your Tailscale account."
							: "Open the owner's invite, sign in with the same Tailscale account as on this device, and accept. It gives this device access to that one machine only."));
			b2.setVisible(!reach && !invite.isEmpty());
			s3.setText(line(reach, !ts, "This device can reach " + who,
					reach ? shown : ts ? "Not yet. If the invite is accepted, its machine may be asleep or off: ask the owner." : ""));
			s4.setText(line(paired, !reach, who + " knows this device",
					paired ? "It's on the owner's list now. What this device may do there is up to them (Access Manager in Gatekeeper)."
							: reach ? "It answers, but didn't take this device's introduction. Try again in a moment." : ""));
			if (!ts0) { s1.setVisible(false); b1.setVisible(false); s2.setVisible(false); b2.setVisible(false);   // (R79: no Tailscale steps)
				s3.setText(line(reach, false, "This device can reach " + who, reach ? shown : homeAddress(url)
						? "Not yet. Is this device on the same network as the owner's machine, and is that machine on?"
						: "Not yet. Is the owner's machine on, and their router letting it through (or this device on their private network)?")); }
			list.revalidate(); list.repaint();
		};
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		p.add(list, BorderLayout.NORTH);
		return p;
	}

	/** Shows the steps until the player closes them. True when the Gatekeeper knows this device by then. */
	static boolean show(JFrame owner, String url, String name, String share, Callable<String> hello) {
		Runnable[] fill = {null};
		boolean[][] state = {{false, false, false}};
		JPanel steps = panel(url, name, share, fill, state, owner);
		fill[0].run();
		dev.gatehouse.look.InWindow.Sheet d = new dev.gatehouse.look.InWindow.Sheet(owner, "Connect to " + (name.isEmpty() ? "this Gatekeeper" : name), true);   // (R97)
		JButton close = new JButton("Close");
		close.addActionListener(e -> d.dispose());
		JLabel note = new JLabel("Looking\u2026");
		note.setForeground(GREY);
		JPanel south = new JPanel(new BorderLayout());
		south.setBorder(BorderFactory.createEmptyBorder(10, 4, 0, 4));
		south.add(note, BorderLayout.WEST); south.add(close, BorderLayout.EAST);
		JPanel all = new JPanel(new BorderLayout());
		all.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		all.add(steps, BorderLayout.CENTER); all.add(south, BorderLayout.SOUTH);
		d.setContentPane(all);
		boolean[] busy = {false};
		Runnable again = () -> {
			if (busy[0]) return;
			busy[0] = true;
			new Thread(() -> {
				boolean[] got = look(url, hello);
				SwingUtilities.invokeLater(() -> {
					busy[0] = false;
					if (!d.isDisplayable()) return;
					boolean was = state[0][2];
					state[0] = got;
					fill[0].run();
					note.setText(got[2] ? "Connected." : "Looking again every few seconds.");
					if (got[2]) { close.setText("Show its servers"); if (!was) LaunchPopup.chime(); }
					d.pack();
				});
			}, "connect").start();
		};
		Timer t = new Timer(2500, e -> { if (d.isDisplayable() && !state[0][2]) again.run(); });
		t.start();
		again.run();
		d.pack();
		d.setLocationRelativeTo(owner);
		d.setVisible(true);   // (until closed)
		t.stop();
		return state[0][2];
	}

	/** Testing: a picture of the steps as a look found them. */
	static void picture(String file, String url, String name, String share) throws IOException {
		Runnable[] fill = {null};
		boolean[][] state = {look(url, () -> "")};
		JPanel steps = panel(url, name, share, fill, state, null);
		fill[0].run();
		steps.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		JFrame tf = new JFrame();
		tf.setContentPane(steps);
		tf.pack();
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(steps.getWidth(), steps.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = img.createGraphics(); steps.paint(g); g.dispose();
		javax.imageio.ImageIO.write(img, "png", new java.io.File(file));
		tf.dispose();
	}
}
