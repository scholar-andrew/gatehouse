package dev.gatehouse;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gatehouse.look.AppIcon;

/**
 * How the two halves show up on the desktop.
 *
 * <p>R16, shortcuts: each half makes or mends its own shortcut when it starts, pointing at where it is now and with its
 * castle (Gatehouse's yellow, Gatekeeper's blue) instead of Java's cup. On Windows a small, readable PowerShell script
 * beside the program does the writing (Windows' own way to make a shortcut); it runs only when something changed. On
 * Linux and SteamOS the menu entries get their icon and the name their windows go by.
 *
 * <p>R15, names (Linux and SteamOS only for now): the windows tell the desktop they are "Gatehouse" or "Gatekeeper",
 * and the processes are named so in the system monitor ("Gatekeeper (bg)" for the part that runs in the background:
 * Linux allows 15 letters). Windows waits until updates are signed: there, the name comes from the program file that
 * runs, which is Java's own.
 *
 * <p>Nothing here runs while testing (OJ_HOME_DRY, OJ_HOST_DIR, OJ_KEEPER_DIR, OJ_PRISM_ROOT, OJ_PANEL_PNG).
 */
public final class Shortcuts {
	private Shortcuts() {}

	static boolean testing() {
		for (String k : new String[] {"OJ_HOME_DRY", "OJ_HOST_DIR", "OJ_KEEPER_DIR", "OJ_PRISM_ROOT", "OJ_PANEL_PNG"}) if (System.getenv(k) != null) return true;
		return false;
	}

	// ------------------------------------------------------------------ names (Linux)

	/** First thing at start, before any window: on Linux, the name the windows and this process go by. */
	public static void name(String[] args) {
		if (Main.WIN || System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) return;
		String first = args.length > 0 ? args[0] : "";
		if (first.equals("--build-pack") || first.equals("--uninstall-command")) return;
		boolean serve = first.equals("--serve"), keeper = serve || first.equals("--panel") || first.equals("--minimized") || (args.length == 0 && Main.isKeeperFile());
		String app = keeper ? "Gatekeeper" : "Gatehouse";
		// (the system monitor's name: the main thread's, at most 15 letters)
		try { Files.writeString(Path.of("/proc/self/task/" + ProcessHandle.current().pid() + "/comm"), serve ? "Gatekeeper (bg)" : app, StandardCharsets.US_ASCII); } catch (IOException | RuntimeException ignored) {}
		if (serve) return;
		// (the windows' class, which the desktop matches to the menu entry's StartupWMClass for its name and icon. Java
		// names it after the program's main class unless told otherwise; the program file lets this be told: Add-Opens)
		try {
			java.awt.Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
			java.lang.reflect.Field f = tk.getClass().getDeclaredField("awtAppClassName");
			f.setAccessible(true);
			f.set(null, app);
		} catch (Throwable notThisToolkit) { /* Wayland-only, headless or a Java that won't allow it: the name stays Java's */ }
	}

	// ------------------------------------------------------------------ shortcuts

	/** Makes or mends this half's shortcut, in the background. prism: Prism Launcher's program, for Gatehouse's (or null). */
	public static void ensure(boolean keeper, String prism) {
		if (testing()) return;
		Thread t = new Thread(() -> { try { if (Main.WIN) windows(keeper, prism); else linux(); } catch (Exception ignored) {} }, "shortcuts");
		t.setDaemon(true);
		t.start();
	}

	/** An .ico of the castle in several sizes: the small ones as plain bitmaps (which everything reads), the largest
	 *  as a PNG (as Windows keeps 256-pixel icons). */
	static byte[] ico(boolean keeper) throws IOException {
		int[] sizes = {16, 24, 32, 48, 64, 256};
		List<byte[]> pngs = new ArrayList<>();
		for (int s : sizes) {
			BufferedImage img = AppIcon.draw(s, keeper, 0, false);
			if (s >= 256) { ByteArrayOutputStream o = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "png", o); pngs.add(o.toByteArray()); continue; }
			// (a 32-bit bitmap, rows from the bottom up, then the 1-bit mask the format still asks for: all shown, as
			// the colours carry their own see-through edges)
			int maskRow = ((s + 31) / 32) * 4;
			ByteBuffer d = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
			d.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 32).putInt(0).putInt(s * s * 4 + maskRow * s).putInt(0).putInt(0).putInt(0).putInt(0);
			for (int y = s - 1; y >= 0; y--) for (int x = 0; x < s; x++) {
				int argb = img.getRGB(x, y);
				d.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
			}
			for (int i = 0; i < maskRow * s; i++) d.put((byte) 0);
			pngs.add(d.array());
		}
		int head = 6 + 16 * sizes.length, at = head, len = head;
		for (byte[] p : pngs) len += p.length;
		ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
		for (int i = 0; i < sizes.length; i++) {
			int s = sizes[i];
			b.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(at);
			at += pngs.get(i).length;
		}
		for (byte[] p : pngs) b.put(p);
		return b.array();
	}

	private static final String SCRIPT = String.join("\r\n",
			"# Made by Gatehouse. Makes or mends one of its shortcuts: what it opens, and its castle picture.",
			"# Gatehouse runs it when it starts and a shortcut of its is missing or out of date; it changes nothing else.",
			"# What to write is handed over in GH_LINK_* variables (a command line would lose empty values and quote marks).",
			"$link = $env:GH_LINK_PATH",
			"if (-not $link) { exit 2 }",
			"if ($env:GH_LINK_ONLY_IF_THERE -eq '1' -and -not (Test-Path -LiteralPath $link)) { exit 0 }",
			"$s = (New-Object -ComObject WScript.Shell).CreateShortcut($link)",
			"$s.TargetPath = $env:GH_LINK_TARGET; $s.Arguments = [string]$env:GH_LINK_ARGUMENTS; $s.WorkingDirectory = $env:GH_LINK_WORKDIR",
			"$s.Description = [string]$env:GH_LINK_DESCRIPTION; $s.IconLocation = $env:GH_LINK_ICON + ',0'",
			"$s.Save()",
			"");

	private static void windows(boolean keeper, String prism) throws Exception {
		Path dir;
		String target, arguments, workDir, name, description;
		if (keeper) {
			dir = Main.keeperDir();
			if (dir == null) return;
			target = Main.java(); arguments = "-jar \"" + dir.resolve("oj-host.jar") + "\" --panel"; workDir = dir.toString();
			name = "Gatekeeper"; description = "Gatekeeper: the servers on this machine, and who may do what with them";
		} else {
			Path script = Main.playScript();
			if (script == null || Main.prismRoots().isEmpty()) return;
			dir = Main.prismRoots().get(0).resolve("gatehouse");
			boolean prismPath = prism != null && (prism.contains("\\") || prism.contains("/")) && Files.isRegularFile(Path.of(prism));
			target = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
			arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + script + "\"" + (prismPath ? " -Prism \"" + prism + "\"" : "") + " -Choose";
			workDir = script.getParent().toString();
			name = "Gatehouse"; description = "Gatehouse: play, or host";
		}
		Files.createDirectories(dir);
		Path ico = dir.resolve(keeper ? "gatekeeper.ico" : "gatehouse.ico"), ps = dir.resolve("make-shortcut.ps1"), done = dir.resolve(keeper ? "shortcut-gatekeeper.txt" : "shortcut-gatehouse.txt");
		byte[] icon = ico(keeper);
		if (!Files.isRegularFile(ico) || !java.util.Arrays.equals(Files.readAllBytes(ico), icon)) Files.write(ico, icon);
		if (!Files.isRegularFile(ps) || !Files.readString(ps, StandardCharsets.UTF_8).equals(SCRIPT)) Files.writeString(ps, SCRIPT, StandardCharsets.UTF_8);
		String programs = System.getenv("APPDATA") == null ? null : Path.of(System.getenv("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs").toString();
		String desktop = Path.of(System.getProperty("user.home", ""), "Desktop").toString();
		if (programs == null) return;
		Path menuLink = Path.of(programs, name + ".lnk");
		// (once for each change in where it points: not every start)
		String want = String.join("|", target, arguments, workDir, ico.toString(), "1");
		if (Files.isRegularFile(menuLink) && want.equals(readText(done))) return;
		boolean ok = run(ps, menuLink, target, arguments, workDir, ico, description, false);
		run(ps, Path.of(desktop, name + ".lnk"), target, arguments, workDir, ico, description, true);   // (one on the desktop, only if there is one)
		if (ok) Files.writeString(done, want + "\n", StandardCharsets.UTF_8);
	}
	private static String readText(Path p) { try { return Files.readString(p, StandardCharsets.UTF_8).trim(); } catch (IOException e) { return ""; } }
	private static boolean run(Path ps, Path link, String target, String arguments, String workDir, Path ico, String description, boolean onlyIfThere) throws Exception {
		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", ps.toString());
		java.util.Map<String, String> env = pb.environment();
		env.put("GH_LINK_PATH", link.toString()); env.put("GH_LINK_TARGET", target); env.put("GH_LINK_ARGUMENTS", arguments); env.put("GH_LINK_WORKDIR", workDir);
		env.put("GH_LINK_ICON", ico.toString()); env.put("GH_LINK_DESCRIPTION", description); env.put("GH_LINK_ONLY_IF_THERE", onlyIfThere ? "1" : "0");
		Process p = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		return p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
	}

	private static void linux() throws IOException {
		if (System.getenv("FLATPAK_ID") != null) return;   // (inside Prism's sandbox the menu's folders can't be seen)
		String home = System.getProperty("user.home", ""), xd = System.getenv("XDG_DATA_HOME");
		Path data = xd != null && !xd.isEmpty() ? Path.of(xd) : Path.of(home, ".local/share");
		Path icons = data.resolve("icons/hicolor/256x256/apps");
		Files.createDirectories(icons);
		for (boolean k : new boolean[] {false, true}) {
			ByteArrayOutputStream o = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(AppIcon.draw(256, k, 0, false), "png", o);
			Path f = icons.resolve(k ? "gatekeeper.png" : "gatehouse.png");
			if (!Files.isRegularFile(f) || !java.util.Arrays.equals(Files.readAllBytes(f), o.toByteArray())) Files.write(f, o.toByteArray());
		}
		Path apps = data.resolve("applications");
		mend(apps.resolve("gatehouse.desktop"), "gatehouse", "Gatehouse");
		Path gk = apps.resolve("gatekeeper.desktop"), kd = Main.keeperDir();
		if (!Files.isRegularFile(gk) && kd != null) {
			// (Gatekeeper is here but its menu entry isn't: made as its installer makes it)
			Files.createDirectories(apps);
			Files.writeString(gk, "[Desktop Entry]\nType=Application\nName=Gatekeeper\nComment=Gatehouse: who may do what on this server machine, and its status\nExec=\"" + Main.java() + "\" -jar \"" + kd.resolve("oj-host.jar") + "\" --panel\nPath=" + kd + "\nTerminal=false\nCategories=Game;\n", StandardCharsets.UTF_8);
		}
		mend(gk, "gatekeeper", "Gatekeeper");
	}
	/** A menu entry gets its icon and the window name it goes with; nothing else in it is changed. */
	private static void mend(Path desktop, String icon, String wmClass) throws IOException {
		if (!Files.isRegularFile(desktop)) return;
		String was = Files.readString(desktop, StandardCharsets.UTF_8);
		List<String> out = new ArrayList<>();
		boolean inEntry = false, iconSet = false, classSet = false;
		for (String l : was.split("\n", -1)) {
			if (l.startsWith("[")) { if (inEntry) { if (!iconSet) out.add(out.size() - countTrailingBlank(out), "Icon=" + icon); if (!classSet) out.add(out.size() - countTrailingBlank(out), "StartupWMClass=" + wmClass); iconSet = classSet = true; } inEntry = l.trim().equals("[Desktop Entry]"); out.add(l); continue; }
			if (inEntry && l.startsWith("Icon=")) { out.add("Icon=" + icon); iconSet = true; continue; }
			if (inEntry && l.startsWith("StartupWMClass=")) { out.add("StartupWMClass=" + wmClass); classSet = true; continue; }
			out.add(l);
		}
		if (inEntry) { int at = out.size() - countTrailingBlank(out); if (!classSet) out.add(at, "StartupWMClass=" + wmClass); if (!iconSet) out.add(at, "Icon=" + icon); }
		String now = String.join("\n", out);
		if (!now.equals(was)) Files.writeString(desktop, now, StandardCharsets.UTF_8);
	}
	private static int countTrailingBlank(List<String> l) { int n = 0; for (int i = l.size() - 1; i >= 0 && l.get(i).isBlank(); i--) n++; return n; }
}
