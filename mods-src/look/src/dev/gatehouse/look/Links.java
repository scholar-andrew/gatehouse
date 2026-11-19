package dev.gatehouse.look;

import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Opening a web page or a folder from a button, on every system the programs run on. Java's own way (Desktop) does
 * nothing on many Linux desktops, SteamOS among them, so there the system's opener is asked first (xdg-open; from
 * inside a Flatpak, through the host). If nothing takes it, the address is shown to copy rather than the button
 * silently doing nothing.
 */
public final class Links {
	private Links() {}
	static final boolean WIN = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	/** The program's home on GitHub, and the user guide there (R43, R44): both halves link to them from their menus. */
	public static final String REPO = "https://github.com/scholar-andrew/gatehouse";
	public static final String GUIDE = REPO + "/blob/main/docs/user-guide.md";

	/** Opens a web address in the person's browser. False when nothing on this system took it. */
	public static boolean open(String url) {
		if (WIN) {
			if (desktop(url, false)) return true;
			return run(List.of("rundll32", "url.dll,FileProtocolHandler", url));
		}
		List<List<String>> ways = new ArrayList<>();
		if (Files.exists(Path.of("/.flatpak-info"))) ways.add(List.of("flatpak-spawn", "--host", "xdg-open", url));
		ways.add(List.of("xdg-open", url));
		ways.add(List.of("gio", "open", url));
		ways.add(List.of("kde-open5", url)); ways.add(List.of("kde-open", url));
		ways.add(List.of("flatpak-spawn", "--host", "xdg-open", url));
		for (List<String> w : ways) if (run(w)) return true;
		return desktop(url, false);
	}
	/** Opens a folder in the system's file manager. */
	public static boolean openFolder(Path dir) {
		if (WIN) return desktop(dir.toString(), true) || run(List.of("explorer.exe", dir.toString()));
		for (List<String> w : List.of(List.of("xdg-open", dir.toString()), List.of("gio", "open", dir.toString()), List.of("flatpak-spawn",
				"--host", "xdg-open", dir.toString()))) if (run(w)) return true;
		return desktop(dir.toString(), true);
	}
	/** As open(); when nothing took it, shows the address with a Copy button instead of doing nothing. */
	public static void openOrShow(Component parent, String url) {
		if (open(url)) return;
		JTextField field = new JTextField(url, Math.min(46, Math.max(24, url.length())));
		field.setEditable(false);
		JButton copy = new JButton("Copy");
		copy.addActionListener(e -> { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(url), null); copy.setText("Copied"); });
		JPanel p = new JPanel(new java.awt.BorderLayout(6, 8));
		p.add(new javax.swing.JLabel("<html><div style='width:420px'>This system didn't open a browser for it. Here is the address, to open on this or another device:</div></html>"), java.awt.BorderLayout.NORTH);
		p.add(field, java.awt.BorderLayout.CENTER); p.add(copy, java.awt.BorderLayout.EAST);
		dev.gatehouse.look.InWindow.showMessageDialog(parent, p, "Open this page", JOptionPane.PLAIN_MESSAGE);
	}

	// ------------------------------------------------------------------ R54: a link that doesn't work says why

	/** Opens a link a person pressed, once it has been tried: a web address that answers opens as before; one that
	 *  doesn't shows what is wrong (no internet, the site's name not found, the site not answering, the page gone or the
	 *  site failing; for a machine on Tailscale, Tailscale or the machine itself), with Try again, Open anyway and Copy.
	 *  Links that aren't web addresses (steam://, ntfy://...) open straight away. The trying is off the window's thread. */
	public static void openChecked(Component parent, String url) {
		if (!url.matches("(?i)https?://.+")) { openOrShow(parent, url); return; }
		Thread t = new Thread(() -> {
			String why = diagnose(url);
			javax.swing.SwingUtilities.invokeLater(() -> { if (why.isEmpty()) openOrShow(parent, url); else broken(parent, url, why); });
		}, "link-check");
		t.setDaemon(true);
		t.start();
	}
	private static void broken(Component parent, String url, String why) {
		JTextField field = new JTextField(url, Math.min(46, Math.max(24, url.length())));
		field.setEditable(false);
		JPanel p = new JPanel(new java.awt.BorderLayout(6, 8));
		p.add(new javax.swing.JLabel("<html><div style='width:420px'><b>This link isn't working right now.</b><br><br>"
				+ why.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"), java.awt.BorderLayout.NORTH);
		p.add(field, java.awt.BorderLayout.CENTER);
		Object[] options = {"Try again", "Open anyway", "Copy the address", "Close"};
		int c = dev.gatehouse.look.InWindow.showOptionDialog(parent, p, "Link not working", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
		if (c == 0) openChecked(parent, url);
		else if (c == 1) openOrShow(parent, url);
		else if (c == 2) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(url), null);
	}
	/** Tries a web address: "" when it answers (even with "sign in first"), else what is wrong, in words for a person.
	 *  Blocking: a few seconds at most. */
	public static String diagnose(String url) {
		URI u;
		try { u = URI.create(url.trim()); } catch (RuntimeException e) { return "The address itself is malformed, so it can't be opened."; }
		String host = u.getHost();
		if (host == null || host.isEmpty()) return "The address has no site in it, so it can't be opened.";
		boolean tailnet = tailnet(host);
		try { java.net.InetAddress.getByName(host); }
		catch (IOException e) {
			if (tailnet) return "The name " + host + " is on your Tailscale network, and this device can't look it up: Tailscale isn't running or signed in here.";
			return online() ? "The site's name (" + host + ") can't be found. The address may be mistyped, or the site no longer exists."
					: "This device isn't online: no website can be reached. Check its Wi-Fi or network cable, then Try again.";
		}
		for (String method : new String[] {"HEAD", "GET"}) {
			try {
				java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.toURL().openConnection();
				c.setRequestMethod(method); c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setInstanceFollowRedirects(true);
				c.setRequestProperty("User-Agent", "Gatehouse link check");
				int code = c.getResponseCode();
				c.disconnect();
				if (code == 405 || code == 501) continue;   // (it doesn't take HEAD: ask with GET)
				if (code < 400 || code == 401 || code == 403 || code == 429) return "";   // (it is there; it may want a sign-in)
				if (code == 404 || code == 410) return "The site " + host + " is up, but this page isn't there any more (error " + code + "): it has moved or been removed.";
				if (code >= 500) return "The site " + host + " is having trouble of its own (error " + code + "). That is on its side; try again later.";
				return "The site " + host + " answered, but not with the page (error " + code + ").";
			} catch (javax.net.ssl.SSLException e) {
				return "The site " + host + " answered, but its security certificate wasn't accepted, so the browser would warn about it too. Check this device's date and time.";
			} catch (java.net.SocketTimeoutException e) {
				return tailnet ? "Your machine at " + host + " isn't answering: it may be asleep or off, or Tailscale isn't connected on this device."
						: "The site " + host + " isn't answering (it took too long). It may be down, or this network is blocking it.";
			} catch (java.net.ConnectException e) {
				return tailnet ? "Your machine at " + host + " is there but refused: the program behind this link isn't running on it (Gatekeeper, or its web panel turned off)."
						: "The site " + host + " refused the connection: it is down, or this network is blocking it.";
			} catch (IOException | RuntimeException e) {
				return "The site " + host + " couldn't be reached (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").";
			}
		}
		return "";
	}
	/** Is it an address on Tailscale (100.64.0.0/10, or a *.ts.net name)? */
	static boolean tailnet(String host) {
		if (host.endsWith(".ts.net")) return true;
		String[] p = host.split("\\.");
		if (p.length != 4 || !host.matches("[0-9.]+")) return false;
		try { int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]); return a == 100 && b >= 64 && b <= 127; } catch (NumberFormatException e) { return false; }
	}
	/** Can this device reach the internet at all (a well-known name looks up)? */
	static boolean online() {
		for (String h : new String[] {"github.com", "cloudflare.com"}) try { java.net.InetAddress.getByName(h); return true; } catch (IOException e) { /* (the next) */ }
		return false;
	}

	private static boolean desktop(String what, boolean folder) {
		try {
			if (!java.awt.Desktop.isDesktopSupported()) return false;
			java.awt.Desktop d = java.awt.Desktop.getDesktop();
			if (folder) { if (!d.isSupported(java.awt.Desktop.Action.OPEN)) return false; d.open(new java.io.File(what)); }
			else { if (!d.isSupported(java.awt.Desktop.Action.BROWSE)) return false; d.browse(URI.create(what)); }
			return true;
		} catch (IOException | RuntimeException e) { return false; }
	}
	/** Runs an opener: taken when it starts and either ends well or is still going after five seconds (a browser that stays up). */
	private static boolean run(List<String> cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			return !p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() == 0;
		} catch (IOException | RuntimeException e) { return false; }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
	}
}
