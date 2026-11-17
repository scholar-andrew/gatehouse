package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The launcher's Connection tab: works out, step by step, whether this device can reach the server machine, and says
 * what to do about the first thing that's wrong. Everything goes over the owner's Tailscale network, so most problems
 * are one of: no internet, Tailscale not installed, not signed in or switched off, signed in to an account the server
 * machine isn't shared with, or the server machine asleep.
 *
 * It only looks and explains: it reads this device's network addresses, asks Tailscale's own command for its status
 * when that command can be run (not from inside Prism's Flatpak sandbox on Linux and the Steam Deck), and tries to
 * connect to the server machine's ports. It installs nothing and changes no settings; the buttons open Tailscale or
 * its download page for the player to do that.
 *
 *   java -jar launch-popup.jar --connection <game folder>     prints the same checks (testing)
 */
final class Connection {
	/** One line of the checklist. state: ok, warn, failed or off. fix: what to do, or empty. */
	record Check(String state, String label, String detail, String fix) {}

	private Connection() {}

	// ------------------------------------------------------------------ the checks

	static List<Check> run(Path dir, ServerControl c) {
		List<Check> out = new ArrayList<>();
		// (What Gatekeeper says about this launcher right now, so one blacklisted a moment ago is treated as such.)
		if (c.hasHelper() && !c.ban.equals("blacklist")) try { c.hello(); c.mcssState(); } catch (IOException | RuntimeException ignored) {}
		if (c.ban.equals("blacklist")) {
			// Blacklisted: nothing about the server machine, its addresses or the pack's source is checked or shown.
			boolean net = ServerControl.answers("github.com:443", 4000);
			out.add(net ? new Check("ok", "Internet", "Working", "") : new Check("failed", "Internet", "This device isn't reaching the internet", "Connect this device to the internet (Wi-Fi or cable), then press Check again."));
			out.add(new Check("warn", "Server", "Not shown: this launcher is blacklisted", "The Play button can ask the owner to let you back."));
			return out;
		}
		List<String> sources = new ArrayList<>();
		try { for (String l : Files.readAllLines(dir.resolve("pack-sources.txt"), StandardCharsets.UTF_8)) { l = l.strip(); if (!l.isEmpty() && !l.startsWith("#")) sources.add(l.replaceAll("/+$", "")); } }
		catch (IOException ignored) {}
		String owner = null, github = null;
		for (String s : sources) { if (s.contains("githubusercontent")) { if (github == null) github = s; } else if (owner == null) owner = s; }
		String variant = Files.isDirectory(dir.resolve("extras")) && System.getProperty("os.name", "").startsWith("Windows") ? "main" : "main";
		String machine = host(c.helperUrl);
		if (machine == null) machine = host(owner);
		if (machine == null && c.minecraft.contains(":")) machine = c.minecraft.substring(0, c.minecraft.lastIndexOf(':'));

		// 1. The internet at all (GitHub, which the pack falls back to).
		boolean internet = github != null ? httpOk(github + "/" + variant + "/pack.toml") : ServerControl.answers("github.com:443", 4000);
		out.add(internet ? new Check("ok", "Internet", "Working", "")
				: new Check("failed", "Internet", "This device isn't reaching the internet", "Connect this device to the internet (Wi-Fi or cable), then press Check again."));

		// 2. Tailscale on this device.
		String mine = tailscaleAddress();
		boolean sandbox = System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info"));
		String exe = sandbox ? null : tailscaleCommand();
		Map<String, Object> status = exe == null ? null : tailscaleStatus(exe);
		String backend = status == null ? "" : str(status.get("BackendState"));
		if (mine != null) out.add(new Check("ok", "Tailscale on this device", "Connected: this device is " + mine, ""));
		else if (backend.equals("NeedsLogin") || backend.equals("NoState")) out.add(new Check("failed", "Tailscale on this device", "Installed, but not signed in",
				"Open Tailscale and sign in with the account the owner invited (or shared the server machine with)."));
		else if (exe != null) out.add(new Check("failed", "Tailscale on this device", "Installed, but switched off" + (backend.isEmpty() ? "" : " (" + backend + ")"),
				"Open Tailscale and connect (switch it on)."));
		else if (sandbox) out.add(new Check("failed", "Tailscale on this device", "Not connected",
				"Tailscale isn't running on this device, or isn't installed. On a Steam Deck: switch to Desktop Mode, make sure Tailscale is installed and signed in with the account the owner invited, then come back."));
		else out.add(new Check("failed", "Tailscale on this device", "Not installed",
				"Install Tailscale (Get Tailscale below), then sign in with the account the owner invited (or shared the server machine with)."));

		// 3. Is the server machine in this device's Tailscale network (told by Tailscale itself, when it can be asked)?
		String peerState = "";   // "online", "offline", "missing", or "" when Tailscale couldn't be asked
		if (status != null && machine != null && mine != null) {
			peerState = "missing";
			String name = "";
			if (status.get("Peer") instanceof Map<?, ?> peers)
				for (Object p : peers.values())
					if (p instanceof Map<?, ?> pm && pm.get("TailscaleIPs") instanceof List<?> ips && ips.contains(machine)) {
						peerState = "true".equals(str(pm.get("Online"))) ? "online" : "offline";
						name = str(pm.get("HostName"));
					}
			if (status.get("Self") instanceof Map<?, ?> self && self.get("TailscaleIPs") instanceof List<?> ips && ips.contains(machine)) { peerState = "online"; name = "this device"; }
			String what = name.isEmpty() ? "The server machine" : "The server machine (" + name + ")";
			out.add(switch (peerState) {
				case "online" -> new Check("ok", "Server machine in your Tailscale network", what + " is there and online", "");
				case "offline" -> new Check("warn", "Server machine in your Tailscale network", what + " is there, but offline: it's asleep or switched off",
						"Wake it from Server controls (that works from the owner's home network), or ask the owner to turn it on.");
				default -> new Check("failed", "Server machine in your Tailscale network", "It isn't in the network this device is signed in to",
						"This device may be signed in to a different Tailscale account, or the owner hasn't shared the server machine with yours yet: ask the owner.");
			});
		}

		// 4. Does the server machine answer on anything?
		List<String> answering = new ArrayList<>();
		boolean pack = owner != null && httpOk(owner + "/" + variant + "/pack.toml");
		if (pack) answering.add("the pack");
		boolean keeper = c.hasHelper() && ServerControl.answers(c.helperHostPort(), 2500);
		if (keeper) answering.add("Gatekeeper");
		String[] ping = c.minecraft.isEmpty() ? null : ServerControl.serverListPing(c.minecraft, 2500);
		if (ping != null) answering.add("the Minecraft server");
		if (!answering.isEmpty()) out.add(new Check("ok", "Server machine answers", "Yes: " + String.join(", ", answering), ""));
		else if (mine == null) out.add(new Check("off", "Server machine answers", "Can't be reached until Tailscale is connected on this device", ""));
		else if (peerState.equals("offline") || peerState.equals("missing")) out.add(new Check("off", "Server machine answers", "No (see the line above)", ""));
		else out.add(new Check("failed", "Server machine answers", "No: nothing on it answered",
				peerState.equals("online") ? "Tailscale says it's online, yet nothing answers: ask the owner to check the pack server and Gatekeeper on it."
						: "Either it's asleep or switched off (Server controls can wake it from the owner's home network), or this device hasn't been given access to the owner's Tailscale network: ask the owner."));

		// 5-7. What runs on it.
		if (owner != null) out.add(pack ? new Check("ok", "Updates", "From the owner's PC", "")
				: internet ? new Check("warn", "Updates", "The owner's PC isn't answering, so updates come from GitHub", "")
				: new Check("failed", "Updates", "No source for updates can be reached", ""));
		if (c.hasHelper()) out.add(keeper ? new Check("ok", "Gatekeeper", "Answering", "")
				: new Check(answering.isEmpty() ? "off" : "warn", "Gatekeeper", "Not answering", answering.isEmpty() ? "" : "It runs while the owner is signed in to the server machine: ask the owner."));
		if (!c.minecraft.isEmpty()) out.add(ping != null ? new Check("ok", "Minecraft server", "Online, " + ping[0] + " ms", "")
				: new Check("off", "Minecraft server", "Not running" + (answering.isEmpty() ? " (or not reachable)" : ""), answering.isEmpty() ? "" : "Request server on the Play button asks the owner to turn it on."));
		return out;
	}

	/** This device's address on Tailscale (100.64.0.0/10), or null: there is one exactly when Tailscale is connected. */
	static String tailscaleAddress() {
		try {
			for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp()) continue;
				for (InetAddress a : Collections.list(n.getInetAddresses())) {
					byte[] b = a.getAddress();
					if (a instanceof Inet4Address && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a.getHostAddress();
				}
			}
		} catch (IOException | RuntimeException ignored) {}
		return null;
	}

	/** Tailscale's own command on this device, or null when it isn't installed (or can't be seen from here). */
	static String tailscaleCommand() {
		List<String> tries = new ArrayList<>();
		if (System.getProperty("os.name", "").startsWith("Windows")) {
			for (String base : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) if (base != null) tries.add(base + "\\Tailscale\\tailscale.exe");
		} else {
			tries.addAll(List.of("/usr/bin/tailscale", "/usr/sbin/tailscale", "/usr/local/bin/tailscale", "/opt/tailscale/tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale"));
			String path = System.getenv("PATH");
			if (path != null) for (String d : path.split(":")) if (!d.isEmpty()) tries.add(d + "/tailscale");
		}
		for (String t : tries) if (Files.isRegularFile(Path.of(t))) return t;
		return null;
	}

	private static Map<String, Object> tailscaleStatus(String exe) {
		try {
			Process p = new ProcessBuilder(exe, "status", "--json").redirectErrorStream(false).start();
			byte[] outBytes;
			try (InputStream in = p.getInputStream()) { outBytes = in.readAllBytes(); }
			if (!p.waitFor(6, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
			return LaunchPopup.parseJson(new String(outBytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> m ? cast(m) : null;
		} catch (IOException | RuntimeException | InterruptedException e) { return null; }
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
	private static String str(Object o) { return o == null ? "" : o.toString(); }
	private static String host(String url) {
		try { return url == null || url.isEmpty() ? null : URI.create(url).getHost(); } catch (RuntimeException e) { return null; }
	}
	private static boolean httpOk(String url) {
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setConnectTimeout(4000); c.setReadTimeout(4000); c.setRequestMethod("HEAD");
			return c.getResponseCode() == 200;
		} catch (IOException | RuntimeException e) { return false; }
	}

	static String text(List<Check> checks) {
		StringBuilder sb = new StringBuilder();
		for (Check k : checks) sb.append(k.state()).append(": ").append(k.label()).append(": ").append(k.detail()).append(k.fix().isEmpty() ? "" : "\n    -> " + k.fix()).append('\n');
		return sb.toString();
	}

	// ------------------------------------------------------------------ the tab

	static JPanel panel(Path dir, ServerControl c, JFrame f, boolean live) {
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
		JLabel head = new JLabel("Checking the connection…");
		head.setFont(head.getFont().deriveFont(Font.BOLD, 14f));
		p.add(head, BorderLayout.NORTH);
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JPanel listTop = new JPanel(new BorderLayout());
		listTop.add(list, BorderLayout.NORTH);
		p.add(listTop, BorderLayout.CENTER);
		JButton again = new JButton("Check again"), openTs = new JButton("Open Tailscale"), getTs = new JButton("Get Tailscale"), copy = new JButton("Copy details");
		again.setToolTipText("Runs the checks again now");
		openTs.setToolTipText("Opens the Tailscale app on this device, to sign in or switch it on");
		getTs.setToolTipText("Opens Tailscale's download page in your browser");
		copy.setToolTipText("Copies the result of these checks, to send to the owner");
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
		for (JButton b : new JButton[] {again, openTs, getTs, copy}) { b.setFont(b.getFont().deriveFont(13f)); buttons.add(b); }
		JLabel note = new JLabel(" ");
		note.setForeground(new Color(0x8F98A0));
		JPanel south = new JPanel(new BorderLayout(0, 2));
		south.add(buttons, BorderLayout.NORTH); south.add(note, BorderLayout.SOUTH);
		p.add(south, BorderLayout.SOUTH);
		String ipn = windowsApp();
		openTs.setVisible(ipn != null);
		String[] last = {""};
		java.util.function.Consumer<List<Check>> show = checks -> {
			list.removeAll();
			Check first = null;
			for (Check k : checks) {
				if (first == null && !k.fix().isEmpty() && (k.state().equals("failed") || k.state().equals("warn"))) first = k;
				JLabel row = new JLabel("<html><b>" + esc(k.label()) + "</b>&nbsp;&nbsp;<font color='#8f98a0'>" + esc(k.detail()) + "</font></html>", LaunchPopup.mark(k.state()), JLabel.LEFT);
				row.setFont(row.getFont().deriveFont(Font.PLAIN, 13f));
				row.setIconTextGap(10);
				row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
				row.setAlignmentX(Component.LEFT_ALIGNMENT);
				list.add(row);
				if (!k.fix().isEmpty()) {
					JLabel fix = new JLabel("<html><div style='width:470px'>" + esc(k.fix()) + "</div></html>");
					fix.setForeground(new Color(0x8F98A0));
					fix.setBorder(BorderFactory.createEmptyBorder(0, 28, 4, 0));
					fix.setAlignmentX(Component.LEFT_ALIGNMENT);
					list.add(fix);
				}
			}
			list.add(Box.createVerticalStrut(4));
			head.setText(first == null ? "Everything this device needs is working" : "To fix: " + first.label());
			head.setIcon(LaunchPopup.mark(first == null ? "ok" : first.state()));
			head.setIconTextGap(8);
			getTs.setVisible(checks.stream().anyMatch(k -> k.label().startsWith("Tailscale on") && !k.state().equals("ok")));
			last[0] = text(checks);
			list.revalidate(); list.repaint();
		};
		Runnable check = () -> {
			again.setEnabled(false);
			head.setIcon(null);
			head.setText("Checking the connection…");
			new Thread(() -> {
				List<Check> r = run(dir, c);
				SwingUtilities.invokeLater(() -> { show.accept(r); again.setEnabled(true); });
			}, "connection").start();
		};
		again.addActionListener(e -> check.run());
		openTs.addActionListener(e -> {
			try { new ProcessBuilder(ipn).start(); note.setText("Tailscale opened: look for its icon by the clock, sign in or connect there, then press Check again."); }
			catch (IOException ex) { note.setText("Couldn't open Tailscale: open it from the Start menu."); }
		});
		getTs.addActionListener(e -> note.setText(open("https://tailscale.com/download") ? "Opened Tailscale's download page in your browser." : "Go to tailscale.com/download in a browser."));
		copy.addActionListener(e -> {
			try {
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection("Gatehouse connection check\n" + last[0]), null);
				note.setText("Copied: paste it in a message to the owner.");
			} catch (RuntimeException ex) { note.setText("Couldn't copy."); }
		});
		// Checked when the tab is first opened (and for a picture of the window, straight away).
		boolean[] done = {false};
		if (!live) show.accept(run(dir, c));
		else p.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && p.isShowing() && !done[0]) { done[0] = true; check.run(); } });
		return p;
	}

	private static String windowsApp() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
		for (String base : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")})
			if (base != null && Files.isRegularFile(Path.of(base, "Tailscale", "tailscale-ipn.exe"))) return Path.of(base, "Tailscale", "tailscale-ipn.exe").toString();
		return null;
	}

	/** Opens a web address in the person's browser (see Links: every system's own way is tried). */
	static boolean open(String url) { return dev.gatehouse.look.Links.open(url); }

	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
