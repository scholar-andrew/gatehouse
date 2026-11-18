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
		JOptionPane.showMessageDialog(parent, p, "Open this page", JOptionPane.PLAIN_MESSAGE);
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
