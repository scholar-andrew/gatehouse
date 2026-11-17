package dev.gatehouse;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import dev.gatehouse.look.AppIcon;
import dev.octoberjourney.host.HostHelper;
import dev.octoberjourney.popup.LaunchPopup;

/**
 * Gatehouse is one program with two halves: Play (the launcher players use, "Gatehouse") and Host (the part that
 * looks after the Minecraft servers on a machine, "Gatekeeper"). This is where it starts, and it only decides which
 * half was asked for; each half then runs exactly as it did when the two were separate programs.
 *
 *   --serve, --panel, --minimized, --uninstall-command     the Host half (see HostHelper)
 *   --choose                                               the first screen: Play or Host
 *   anything else                                          the Play half (see LaunchPopup)
 *   nothing at all                                         the Host half's window when this file is a Gatekeeper's own
 *                                                          (it is called oj-host.jar, or has host.json beside it);
 *                                                          otherwise the first screen
 *
 * The file is published under both of its old names (the pack's extras/launch-popup.jar and gatekeeper/oj-host.jar),
 * the same bytes, so a device with either half keeps updating the way it always did.
 */
public final class Main {
	static final boolean WIN = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	static final Color GREY = new Color(0x8F98A0), GREEN = new Color(0x8BD46E), AMBER = new Color(0xE9B949);

	public static void main(String[] args) throws Exception {
		String first = args.length > 0 ? args[0] : "";
		if (first.equals("--choose")) { choose(null); return; }
		if (List.of("--serve", "--panel", "--minimized", "--uninstall-command").contains(first) || (args.length == 0 && isKeeperFile())) { HostHelper.main(args); return; }
		if (args.length == 0) { choose(null); return; }
		LaunchPopup.main(args);
	}

	static Path own() {
		try { return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()); } catch (Exception e) { return Path.of("."); }
	}
	/** Is this file a Gatekeeper's own copy (so that opening it with nothing asked means its window, as before)? */
	static boolean isKeeperFile() {
		Path me = own();
		return System.getenv("OJ_HOST_DIR") != null || (me.getFileName() != null && me.getFileName().toString().equalsIgnoreCase("oj-host.jar")) || (me.getParent() != null && Files.isRegularFile(me.getParent().resolve("host.json")));
	}

	// ------------------------------------------------------------------ where each half is on this device

	/** Prism Launcher's data folders that could hold Gatehouse (OJ_PRISM_ROOT, for testing, replaces them). */
	static List<Path> prismRoots() {
		List<Path> out = new ArrayList<>();
		String forced = System.getenv("OJ_PRISM_ROOT"), home = System.getProperty("user.home", "");
		if (forced != null && !forced.isEmpty()) { out.add(Path.of(forced)); return out; }
		if (WIN) { String ad = System.getenv("APPDATA"); if (ad != null) out.add(Path.of(ad, "PrismLauncher")); }
		else { out.add(Path.of(home, ".local/share/PrismLauncher")); out.add(Path.of(home, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")); }
		return out;
	}
	/** The script that opens the Play half as its shortcut does (Gatehouse's own, else one a pack carries), or null. */
	static Path playScript() {
		String name = WIN ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh";
		for (Path root : prismRoots()) {
			Path own = root.resolve("gatehouse/app/extras").resolve(name);
			if (Files.isRegularFile(own)) return own;
			try (java.util.stream.Stream<Path> s = Files.list(root.resolve("instances"))) {
				for (Path inst : (Iterable<Path>) s.sorted()::iterator) if (Files.isRegularFile(inst.resolve("minecraft/extras").resolve(name))) return inst.resolve("minecraft/extras").resolve(name);
			} catch (IOException | RuntimeException none) { /* no instances there */ }
		}
		return null;
	}
	/** The folder the Host half is installed in (the one this file is in, when it is a Gatekeeper's), or null. OJ_KEEPER_DIR, for testing, replaces the usual places. */
	static Path keeperDir() {
		String forced = System.getenv("OJ_KEEPER_DIR"), home = System.getProperty("user.home", "");
		List<Path> places = new ArrayList<>();
		if (forced != null && !forced.isEmpty()) places.add(Path.of(forced));
		else {
			if (own().getParent() != null) places.add(own().getParent());
			if (WIN) { places.add(Path.of("C:\\Minecraft\\GatehouseKeeper")); String la = System.getenv("LOCALAPPDATA"); if (la != null) places.add(Path.of(la, "Programs", "GatehouseKeeper")); }
			else places.add(Path.of(home, ".local/share/GatehouseKeeper"));
		}
		for (Path p : places) if (Files.isRegularFile(p.resolve("oj-host.jar")) && Files.isRegularFile(p.resolve("host.json"))) return p;
		return null;
	}
	/** Is that Gatekeeper's background part answering on this machine right now? */
	static boolean keeperRunning(Path dir) {
		int port = 25570;
		try {
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*\"?(\\d+)").matcher(Files.readString(dir.resolve("host.json"), StandardCharsets.UTF_8));
			if (m.find()) port = Integer.parseInt(m.group(1));
		} catch (IOException | RuntimeException ignored) {}
		try (java.net.Socket s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500); return true; } catch (IOException e) { return false; }
	}
	static String java() {
		String j = ProcessHandle.current().info().command().orElse(WIN ? "javaw.exe" : "java");
		return WIN ? j.replaceAll("(?i)java\\.exe$", "javaw.exe") : j;
	}

	/** What starts each half, or sets it up. Nothing here is run when OJ_HOME_DRY is set (testing): it is printed. */
	static List<String> openPlay(Path script) {
		return WIN ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString()) : List.of("bash", script.toString());
	}
	static List<String> openHost(Path dir) { return List.of(java(), "-jar", dir.resolve("oj-host.jar").toString(), "--panel"); }
	/** Setting a half up runs Gatehouse's own install script, which this program carries, in a window of its own
	 *  where it says what it does and asks before fetching anything. */
	static List<String> setUp(boolean host) throws IOException {
		String name = WIN ? "gatehouse-install.ps1" : "gatehouse-install.sh";
		Path to = Path.of(System.getProperty("java.io.tmpdir"), "gatehouse-setup", name);
		Files.createDirectories(to.getParent());
		try (InputStream in = Main.class.getResourceAsStream("/" + name)) {
			if (in == null) throw new IOException("this program doesn't carry its install script");
			Files.write(to, in.readAllBytes());
		}
		if (WIN) return List.of("cmd.exe", "/c", "start", "Setting up " + (host ? "Gatekeeper" : "Gatehouse"), "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-NoExit", "-File", to.toString(), host ? "-Gatekeeper" : "-Gatehouse");
		String cmd = "bash '" + to + "' " + (host ? "--gatekeeper" : "") + "; echo; echo 'Done. You can close this window.'; read -r _";
		for (String[] term : new String[][] {{"konsole", "-e"}, {"gnome-terminal", "--"}, {"xfce4-terminal", "-x"}, {"xterm", "-e"}})
			for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
				if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, term[0]))) return List.of(term[0], term[1], "bash", "-c", cmd);
		throw new IOException("no terminal program was found to run it in. Run this in a terminal yourself: bash " + to + (host ? " --gatekeeper" : ""));
	}
	static String run(List<String> cmd) {
		if (System.getenv("OJ_HOME_DRY") != null) { System.out.println("would run: " + String.join(" ", cmd)); return ""; }
		try { new ProcessBuilder(cmd).start(); return ""; } catch (IOException e) { return e.getMessage(); }
	}

	// ------------------------------------------------------------------ the first screen: Play or Host

	/** One half's card: what it is, whether it is set up here, and the button that opens it or sets it up. */
	private static JPanel card(String title, String what, String state, Color colour, String button, Runnable press) {
		JPanel p = new JPanel(new BorderLayout(0, 10));
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(16, 16, 16, 16)));
		JLabel t = new JLabel(title);
		t.setFont(t.getFont().deriveFont(Font.BOLD, 20f));
		JLabel w = new JLabel("<html><div style='width:250px'>" + what + "</div></html>");
		JLabel s = new JLabel(state);
		s.setForeground(colour);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		for (Component c : new Component[] {t, javax.swing.Box.createVerticalStrut(6), w, javax.swing.Box.createVerticalStrut(10), s}) { if (c instanceof JLabel l) l.setAlignmentX(Component.LEFT_ALIGNMENT); text.add(c); }
		JButton b = new JButton(button);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
		b.addActionListener(e -> press.run());
		p.add(text, BorderLayout.CENTER); p.add(b, BorderLayout.SOUTH);
		return p;
	}

	/** Builds the first screen's two cards as things stand now. said: where a press says what happened. */
	static JPanel cards(Component owner, JLabel said) {
		Path script = playScript(), keeper = keeperDir();
		boolean up = keeper != null && keeperRunning(keeper);
		JPanel both = new JPanel(new GridLayout(1, 2, 12, 0));
		java.util.function.BiConsumer<Boolean, String> setUpIt = (host, name) -> {
			if (JOptionPane.showConfirmDialog(owner, "<html><div style='width:400px'>Set up " + name + " on this device? Its setup opens in a window of its own, says what it will do, and asks before it fetches anything.</div></html>", "Set up " + name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			String trouble;
			try { trouble = run(setUp(host)); } catch (IOException e) { trouble = e.getMessage(); }
			said.setForeground(trouble.isEmpty() ? GREY : AMBER);
			said.setText("<html><div style='width:600px'>" + (trouble.isEmpty() ? name + "'s setup is open in its own window. When it has finished, this screen shows it as set up." : "Couldn't start the setup: " + trouble) + "</div></html>");
		};
		both.add(card("Play", "<b>Gatehouse.</b> Pick a server, keep its mods up to date, and start the game. For anyone who plays.",
				script != null ? "Set up on this device" : "Not set up on this device yet", script != null ? GREEN : GREY, script != null ? "Open Gatehouse" : "Set up Gatehouse\u2026",
				() -> { if (script == null) { setUpIt.accept(false, "Gatehouse"); return; } String t = run(openPlay(script)); said.setForeground(t.isEmpty() ? GREY : AMBER); said.setText(t.isEmpty() ? "Opening Gatehouse\u2026" : "Couldn't open Gatehouse: " + t); }));
		both.add(card("Host", "<b>Gatekeeper.</b> Looks after the Minecraft servers on this machine: who may join and control them, starting and stopping, backups. For whoever runs the servers.",
				keeper == null ? "Not set up on this device yet" : up ? "Set up here, and running" : "Set up here, but its background part isn't running", keeper == null ? GREY : up ? GREEN : AMBER, keeper != null ? "Open Gatekeeper" : "Set up Gatekeeper\u2026",
				() -> { if (keeper == null) { setUpIt.accept(true, "Gatekeeper"); return; } String t = run(openHost(keeper)); said.setForeground(t.isEmpty() ? GREY : AMBER); said.setText(t.isEmpty() ? "Opening Gatekeeper\u2026" : "Couldn't open Gatekeeper: " + t); }));
		return both;
	}

	/** Shows the first screen: in a window of its own (owner null: the program was opened for it), or over a half's window. */
	public static void choose(Window owner) {
		if (GraphicsEnvironment.isHeadless()) {
			Path s = playScript(), k = keeperDir();
			System.out.println("Play (Gatehouse): " + (s == null ? "not set up" : "set up, " + s));
			System.out.println("Host (Gatekeeper): " + (k == null ? "not set up" : "set up in " + k + (keeperRunning(k) ? ", running" : ", not running")));
			return;
		}
		Runnable ui = () -> {
			if (owner == null) dev.gatehouse.look.SteamLook.apply();
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
			JLabel head = new JLabel("Gatehouse");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 22f));
			JLabel sub = new JLabel("One program, two halves. Open the one you need on this device; a half that isn't set up here yet can be set up from its button.");
			sub.setForeground(GREY);
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(head, BorderLayout.NORTH); top.add(sub, BorderLayout.SOUTH);
			JLabel said = new JLabel(" ");
			said.setForeground(GREY);
			JPanel middle = new JPanel(new BorderLayout());
			middle.add(cards(p, said), BorderLayout.CENTER);
			p.add(top, BorderLayout.NORTH); p.add(middle, BorderLayout.CENTER); p.add(said, BorderLayout.SOUTH);
			String shot = System.getenv("OJ_CHOOSE_PNG");
			if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the screen instead of showing it)
				JFrame t = new JFrame(); t.setContentPane(p); t.pack();
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
				try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot)); } catch (IOException e) { e.printStackTrace(); }
				t.dispose();
				if (owner == null) System.exit(0);
				return;
			}
			// (looked at again every few seconds: a half being set up in its own window turns up here when it is done)
			Timer again = new Timer(4000, e -> { middle.removeAll(); middle.add(cards(p, said), BorderLayout.CENTER); middle.revalidate(); middle.repaint(); });
			if (owner == null) {
				JFrame f = new JFrame("Gatehouse");
				f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
				AppIcon.apply(f, false, 0, false);
				f.setContentPane(p); f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);
				again.start();
			} else {
				JDialog d = new JDialog(owner, "Gatehouse: Play or Host", java.awt.Dialog.ModalityType.MODELESS);
				d.setContentPane(p); d.pack(); d.setLocationRelativeTo(owner);
				d.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { again.stop(); } });
				d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
				d.setVisible(true);
				again.start();
			}
		};
		if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
	}
}
