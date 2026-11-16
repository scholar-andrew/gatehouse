package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** Notes about the programs themselves, kept apart from any pack's notes. There are four kinds of notes in all:
 *    Gatehouse (this launcher)       its own, inside this program (source/gatehouse.json); shown on the main window
 *    Gatekeeper                      its own, asked of each Gatekeeper (GET /v1/about); shown beside that Gatekeeper's servers
 *    a pack (the game on a device)   the pack's, shown in that server's launcher under Client updates
 *    a server                        the server's, shown in that server's launcher under Server updates
 *  Also here: Gatehouse fetching a newer copy of itself when it's opened directly. */
final class Notes {
	private Notes() {}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	/** Gatehouse's own version and notes: {"version": ..., "changes": [{version, date, notes: [...]}, ...]}, newest first. */
	static Map<String, Object> own() {
		try (InputStream in = Notes.class.getResourceAsStream("/gatehouse-notes.json")) {
			return in == null ? new java.util.LinkedHashMap<>() : ServerControl.asMap(LaunchPopup.parseJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) { return new java.util.LinkedHashMap<>(); }
	}
	static String version(Map<String, Object> notes) { return str(notes.get("version")); }

	/** A scrolling list of a program's notes, newest on top; "none" is said when there are none to show. */
	static JComponent panel(Map<String, Object> notes, String title, String none) {
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
		List<Component> rows = new ArrayList<>();
		String v = version(notes);
		if (!v.isEmpty()) { JLabel now = new JLabel(title + " " + v + " is what's running."); now.setForeground(new Color(0x8F98A0)); now.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0)); rows.add(now); }
		if (notes.get("changes") instanceof List<?> l) for (Object o : l) {
			Map<String, Object> c = ServerControl.asMap(o);
			rows.add(LaunchPopup.heading(str(c.get("version")) + (str(c.get("date")).isEmpty() ? "" : " (" + str(c.get("date")) + ")")));
			if (c.get("notes") instanceof List<?> ns) for (Object n : ns) rows.add(LaunchPopup.text("• " + n));
		}
		if (rows.size() <= (v.isEmpty() ? 0 : 1)) { JLabel e = new JLabel(none); e.setForeground(new Color(0x8F98A0)); e.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0)); rows.add(e); }
		for (Component r : rows) { ((JComponent) r).setAlignmentX(Component.LEFT_ALIGNMENT); list.add(r); }
		JPanel top = new JPanel(new BorderLayout());
		top.add(list, BorderLayout.NORTH);
		JScrollPane sc = new JScrollPane(top);
		sc.setBorder(null);
		sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sc.getVerticalScrollBar().setUnitIncrement(16);
		return sc;
	}

	/** Looks for a newer Gatehouse where the installed packs get their updates (each pack's pack-sources.txt, in order;
	 *  the launcher is part of every pack, as extras/launch-popup.jar, with its hash in update-sizes.json), and if there
	 *  is one, fetches it, checks it against that hash, and puts it in place of the newest copy here (the one the
	 *  Steam shortcut's script runs). True when a newer one is now in place: the launcher should close and be reopened.
	 *  Nothing found, nothing reachable, or anything going wrong: false, and nothing is changed.
	 *  (The main window does this in two steps, newer() then fetch(), asking in between.) */
	static boolean selfUpdate(Path instances) {
		String[] found = newer(instances);
		return found != null && fetch(found);
	}

	/** The fetching half: {where the pack is served, the hash wanted, the pack folder here to put it in, ...} from newer(). */
	static boolean fetch(String[] found) {
		try {
			byte[] jar = LaunchPopup.fetchBytes(found[0] + "/extras/launch-popup.jar");
			if (!found[1].equals(LaunchPopup.sha256(jar))) return false;
			Path dst = Path.of(found[2]).resolve("extras/launch-popup.jar"), tmp = Path.of(found[2]).resolve("extras/launch-popup.jar.part");
			Files.write(tmp, jar);
			try { Files.move(tmp, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } finally { Files.deleteIfExists(tmp); }   // (in use, as when run straight from there on Windows: left as it was)
			return true;
		} catch (Exception e) { if (System.getenv("OJ_DEBUG") != null) e.printStackTrace(); return false; }
	}

	/** The looking half: null when this is the newest Gatehouse there is (or nothing could be reached), else
	 *  {where the pack is served, the hash wanted, the pack folder here to put it in, its size in bytes or "",
	 *  its version or ""}. Nothing is fetched or changed. */
	static String[] newer(Path instances) {
		try {
			Path me = Path.of(Notes.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (!Files.isRegularFile(me)) return null;
			String mine = LaunchPopup.sha256(Files.readAllBytes(me));
			boolean deck = false;
			try { String p = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim(); deck = p.equals("Jupiter") || p.equals("Galileo"); } catch (IOException | RuntimeException ignored) {}
			List<Path> packs = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(instances)) { s.filter(Files::isDirectory).map(d -> d.resolve("minecraft")).filter(m -> Files.isRegularFile(m.resolve("pack-sources.txt")) && Files.isRegularFile(m.resolve("extras/launch-popup.jar"))).forEach(packs::add); }
			if (packs.isEmpty()) return null;
			// (the newest copy among them is the one that gets run)
			Path target = packs.get(0);
			for (Path p : packs) if (Files.getLastModifiedTime(p.resolve("extras/launch-popup.jar")).compareTo(Files.getLastModifiedTime(target.resolve("extras/launch-popup.jar"))) > 0) target = p;
			List<String> tried = new ArrayList<>();
			for (Path p : packs) for (String line : Files.readAllLines(p.resolve("pack-sources.txt"), StandardCharsets.UTF_8)) {
				String src = line.strip().replaceAll("/+$", "");
				if (src.isEmpty() || src.startsWith("#") || tried.contains(src)) continue;
				tried.add(src);
				for (String variant : deck ? new String[] {"deck", "main"} : new String[] {"main"}) try {
					String base = src + "/" + variant;
					Map<String, Object> sizes = ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(base + "/update-sizes.json"), StandardCharsets.UTF_8)));
					Map<String, Object> entry = ServerControl.asMap(ServerControl.asMap(sizes.get("files")).get("extras/launch-popup.jar"));
					String want = str(entry.get("hash"));
					if (want.isEmpty()) continue;
					if (want.equals(mine)) return null;   // (this is the newest there is)
					return new String[] {base, want, target.toString(), str(entry.get("size")).replaceAll("\\..*$", ""), str(sizes.get("gatehouse"))};
				} catch (IOException | RuntimeException next) { if (System.getenv("OJ_DEBUG") != null) next.printStackTrace(); /* the next place */ }
			}
		} catch (Exception e) { if (System.getenv("OJ_DEBUG") != null) e.printStackTrace(); }
		return null;
	}
}
