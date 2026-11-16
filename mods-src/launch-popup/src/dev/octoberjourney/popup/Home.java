package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** The Gatehouse launcher's own window (what the "Gatehouse Minecraft Launcher" Steam shortcut opens):
 *    --home &lt;Prism's instances folder&gt; --prism &lt;how to start Prism Launcher&gt; [--play &lt;instance id&gt;]
 *  Three levels:
 *    1. the Gatekeepers this device knows (the ones its installed packs name, and any added here by address, pairing
 *       link or pairing file), each with whether it answers;
 *    2. the servers one Gatekeeper looks after (its catalog, GET /v1/catalog), each with whether it's on and whether its
 *       pack is installed here;
 *    3. a server's own page: the pack's launcher, reached with Play (Prism starts the instance, the update runs first
 *       and its window appears). Its Menu button comes back here.
 *  With --play there's no window to begin with: that's a pack's own Steam shortcut, and the game starts straight away
 *  when it can. This program stays until Prism closes, so Steam sees the game as running.
 *  What it remembers is in a "gatehouse" folder beside Prism's instances: gatekeepers.json (name and address of each),
 *  and for each Gatekeeper a small folder with this device's id and secret for it (the same ones the packs use, so a
 *  Gatekeeper sees one launcher per device). */
final class Home {
	private Home() {}

	/** A pack installed in Prism: {instance id, name, address to join, game folder, its Gatekeeper's address or ""}. */
	private static List<String[]> installed(Path instances) {
		List<String[]> out = new ArrayList<>();
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Path mc = Files.isDirectory(d.resolve("minecraft")) ? d.resolve("minecraft") : d.resolve(".minecraft");
				String profile = LaunchPopup.readFile(mc.resolve("extras/profile.json"));
				if (profile.isBlank()) continue;
				String name = str(ServerControl.asMap(LaunchPopup.parseJson(profile)).get("name")), address = "";
				try { if (LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("extras/servers.json"))) instanceof List<?> l && !l.isEmpty()) address = str(ServerControl.asMap(l.get(0)).get("address")); } catch (RuntimeException ignored) {}
				String keeper = "";
				try { keeper = norm(new ServerControl(mc).helperUrl); } catch (RuntimeException ignored) {}
				out.add(new String[] {d.getFileName().toString(), name.isEmpty() ? d.getFileName().toString() : name, address, mc.toString(), keeper});
			}
		} catch (IOException ignored) {}
		return out;
	}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	/** Runs one of the Connections buttons' calls through the shared "press" handling, once. */
	private static final class ActionListenerOnce {
		static void run(JButton b, java.util.function.BiConsumer<JButton, java.util.concurrent.Callable<Map<String, Object>>> press, java.util.concurrent.Callable<Map<String, Object>> call) {
			JButton proxy = new JButton();
			press.accept(proxy, call);
			b.setEnabled(false);
			proxy.doClick(0);
		}
	}

	/** A list's panel that is always exactly as wide as the scrolling area it sits in, so a row's long status line is
	 *  shortened (with its full text on hover) and the buttons on its right stay in view. */
	private static final class Fitted extends JPanel implements javax.swing.Scrollable {
		Fitted() { super(new BorderLayout()); }
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) { return 16; }
		@Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) { return 96; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
	/** An address as it's kept: http://host:port, no slash at the end. Empty for nothing. */
	static String norm(String url) {
		String u = url == null ? "" : url.trim().replaceAll("/+$", "");
		if (u.isEmpty()) return "";
		if (!u.matches("(?i)https?://.*")) u = "http://" + u;
		try { java.net.URI x = java.net.URI.create(u); if (x.getHost() == null) return ""; return x.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + x.getHost() + ":" + (x.getPort() > 0 ? x.getPort() : 25570); }
		catch (RuntimeException e) { return ""; }
	}
	private static String shown(String url) { return url.replaceFirst("(?i)^https?://", ""); }

	/** What was pasted or opened to add a Gatekeeper: an address, a pairing link (gatehouse://pair/&lt;address&gt;?name=...) or
	 *  the text of a pairing file ({"address": ..., "name": ...}). Returns {address, name} or null. */
	static String[] parsePairing(String text) {
		String t = text == null ? "" : text.trim();
		if (t.isEmpty()) return null;
		if (t.startsWith("{")) {
			Map<String, Object> m = ServerControl.asMap(LaunchPopup.parseJson(t));
			String a = norm(str(m.get("address")));
			return a.isEmpty() ? null : new String[] {a, str(m.get("name"))};
		}
		String name = "";
		if (t.toLowerCase(java.util.Locale.ROOT).startsWith("gatehouse://pair/")) {
			t = t.substring("gatehouse://pair/".length());
			int q = t.indexOf('?');
			if (q >= 0) {
				for (String kv : t.substring(q + 1).split("&")) if (kv.startsWith("name=")) try { name = java.net.URLDecoder.decode(kv.substring(5), StandardCharsets.UTF_8); } catch (RuntimeException ignored) {}
				t = t.substring(0, q);
			}
		}
		String a = norm(t);
		return a.isEmpty() ? null : new String[] {a, name};
	}

	/** This device's line to one Gatekeeper: a small folder of its own (so the launcher's usual code can talk to it),
	 *  given the id and secret an installed pack already uses for that Gatekeeper when there is one. */
	private static ServerControl client(Path home, String url, List<String[]> packs) {
		Path dir = home.resolve("keepers").resolve(shown(url).replaceAll("[^A-Za-z0-9.-]", "_"));
		try {
			Files.createDirectories(dir.resolve("extras"));
			Files.writeString(dir.resolve("extras/server-control.json"), "{\"helper_url\": \"" + ServerControl.jsonEsc(url) + "\"}\n", StandardCharsets.UTF_8);
			if (!Files.isRegularFile(dir.resolve("server-access.json"))) for (String[] p : packs) if (p[4].equals(url)) {
				ServerControl sc = new ServerControl(Path.of(p[3]));
				if (sc.deviceId != null && !sc.deviceId.isEmpty() && sc.deviceToken != null && sc.deviceToken.length() >= 32) {
					Files.writeString(dir.resolve("server-access.json"), "{\"device_id\": \"" + ServerControl.jsonEsc(sc.deviceId) + "\", \"device_token\": \"" + ServerControl.jsonEsc(sc.deviceToken) + "\"}\n", StandardCharsets.UTF_8);
					break;
				}
			}
		} catch (IOException | RuntimeException ignored) {}
		return new ServerControl(dir);
	}

	/** A Gatekeeper's catalog: {"name": ..., "servers": [...]}; null when it doesn't answer; "_refused" set when it says no. */
	private static Map<String, Object> catalog(ServerControl c) {
		try {
			Map<String, Object> r = c.helper("GET", "/v1/catalog", null);
			if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("GET", "/v1/catalog", null); }
			if ("404".equals(str(r.get("_code")))) { r = new LinkedHashMap<>(); r.put("_old", "yes"); return r; }   // (a Gatekeeper from before catalogs)
			if (!"200".equals(str(r.get("_code")))) r.put("_refused", str(r.get("error")).isEmpty() ? "it said no (" + str(r.get("_code")) + ")" : str(r.get("error")));
			return r;
		} catch (IOException | RuntimeException e) { return null; }
	}

	static void run(String[] args) throws Exception {
		Path instances = Path.of(args[1]), home = instances.toAbsolutePath().getParent().resolve("gatehouse");
		String prism = "prismlauncher", play = null;
		for (int i = 2; i + 1 < args.length; i += 2) { if (args[i].equals("--prism")) prism = args[i + 1]; else if (args[i].equals("--play")) play = args[i + 1]; }
		String prismCmd = prism;
		String openAt = null;   // (a Gatekeeper to open on: the one whose server's Menu button brought us back)
		while (true) {
			PackInstall.refreshPlain(instances);
			List<String[]> packs = installed(instances);
			if (play != null) {
				String want = play; play = null;
				String[] hit = null;
				for (String[] e : packs) if (e[0].equals(want)) hit = e;
				if (hit != null) { LaunchPopup.startGame(prismCmd, hit, true); openAt = back(packs); if (openAt == null) return; continue; }
			}
			String[][] chosen = {null};
			boolean[] openPrism = {false};
			String[] again = {null};
			window(instances, home, packs, openAt, chosen, openPrism, again);
			if ("\u0000restart".equals(again[0])) { openAt = null; continue; }   // (Restart: the main window again, from the top)
			if ("\u0000relaunch".equals(again[0])) System.exit(LaunchPopup.RELAUNCH);   // (a newer Gatehouse is in place: the shortcut's script opens it)
			if (again[0] != null) { openAt = again[0]; continue; }
			if (chosen[0] != null) LaunchPopup.startGame(prismCmd, chosen[0], false);
			else if (openPrism[0]) LaunchPopup.run(List.of(prismCmd));
			else return;
			if (System.getenv("OJ_HOME_DRY") != null) return;
			openAt = back(installed(instances));
			if (openAt == null) return;
		}
	}

	/** Did a pack's launcher ask to come back to this menu (its Menu button leaves a "back-to-menu" note in the game
	 *  folder)? Returns that pack's Gatekeeper ("" for none), or null when nobody asked. */
	private static String back(List<String[]> packs) {
		String at = null;
		for (String[] e : packs) {
			Path note = Path.of(e[3]).resolve("back-to-menu");
			if (Files.exists(note)) { try { Files.deleteIfExists(note); } catch (IOException ignored) {} at = e[4]; }
		}
		return at;
	}

	// ---- what's remembered
	private static List<String[]> savedKeepers(Path home) {
		List<String[]> out = new ArrayList<>();
		if (LaunchPopup.parseJson(LaunchPopup.readFile(home.resolve("gatekeepers.json"))) instanceof List<?> l)
			for (Object o : l) { String u = norm(str(ServerControl.asMap(o).get("url"))); if (!u.isEmpty()) out.add(new String[] {u, str(ServerControl.asMap(o).get("name"))}); }
		return out;
	}
	private static void saveKeepers(Path home, List<String[]> keepers) {
		StringBuilder sb = new StringBuilder("[");
		for (String[] k : keepers) sb.append(sb.length() > 1 ? ",\n " : "\n ").append("{\"url\": \"").append(ServerControl.jsonEsc(k[0])).append("\", \"name\": \"").append(ServerControl.jsonEsc(k[1])).append("\"}");
		try { Files.createDirectories(home); Files.writeString(home.resolve("gatekeepers.json"), sb.append("\n]\n").toString(), StandardCharsets.UTF_8); } catch (IOException ignored) {}
	}

	// ---- the window
	private static void window(Path instances, Path home, List<String[]> packs, String openAt, String[][] chosen, boolean[] openPrism, String[] again) throws Exception {
		// The Gatekeepers: the ones remembered, and any an installed pack names that isn't remembered yet.
		List<String[]> keepers = savedKeepers(home);
		boolean added = false;
		for (String[] p : packs) if (!p[4].isEmpty()) { boolean have = false; for (String[] k : keepers) if (k[0].equals(p[4])) have = true; if (!have) { keepers.add(new String[] {p[4], ""}); added = true; } }
		if (added) saveKeepers(home, keepers);
		boolean loose = false;
		for (String[] p : packs) if (p[4].isEmpty()) loose = true;
		boolean hasLoose = loose;
		java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
		String shot = System.getenv("OJ_POPUP_PNG");
		SwingUtilities.invokeAndWait(() -> {
			dev.gatehouse.look.SteamLook.apply();
			JFrame f = new JFrame(LaunchPopup.TOOL);
			f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 12));
			p.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
			JLabel head = new JLabel(LaunchPopup.TOOL + " Minecraft Launcher");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 20f));
			JLabel sub = new JLabel(" ");
			sub.setFont(sub.getFont().deriveFont(14f));
			JButton backB = new JButton("‹ Gatekeepers");
			backB.setToolTipText("Back to the list of Gatekeepers");
			backB.setVisible(false);
			JPanel headRow = new JPanel(new BorderLayout(10, 0));
			headRow.add(head, BorderLayout.CENTER); headRow.add(backB, BorderLayout.EAST);
			JPanel top = new JPanel(new BorderLayout(0, 4));
			// Gatehouse's own update, asked about here (apart from any pack's, which its server's launcher asks about).
			JPanel ownUpdate = new JPanel(new BorderLayout(10, 0));
			ownUpdate.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x3D6FD6)), BorderFactory.createEmptyBorder(8, 12, 8, 8)));
			JLabel ownUpdateText = new JLabel(" ");
			JButton ownUpdateGo = new LaunchPopup.PlayButton("Update " + LaunchPopup.TOOL), ownUpdateLater = new JButton("Later");
			ownUpdateGo.setToolTipText("Fetches the newer " + LaunchPopup.TOOL + " and reopens it. No game or pack is touched.");
			ownUpdateLater.setToolTipText("Carry on with this one; you'll be asked again the next time " + LaunchPopup.TOOL + " is opened");
			JPanel ownUpdateButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			ownUpdateButtons.add(ownUpdateLater); ownUpdateButtons.add(ownUpdateGo);
			ownUpdate.add(ownUpdateText, BorderLayout.CENTER); ownUpdate.add(ownUpdateButtons, BorderLayout.EAST);
			ownUpdate.setVisible(false);
			top.add(headRow, BorderLayout.NORTH); top.add(sub, BorderLayout.CENTER); top.add(ownUpdate, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			CardLayout cards = new CardLayout();
			JPanel deck = new JPanel(cards);
			JPanel keeperList = new JPanel(), serverList = new JPanel(), connList = new JPanel();
			javax.swing.JTabbedPane level1 = new javax.swing.JTabbedPane(), level2 = new javax.swing.JTabbedPane();
			for (JPanel l : new JPanel[] {keeperList, serverList, connList}) l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
			for (Object[] c : new Object[][] {{keeperList, "keepers"}, {serverList, "Servers"}, {connList, "Connections"}}) {
				JPanel inner = new Fitted();   // (as wide as the window, never wider: a long line is cut short, not the buttons pushed out)
				inner.add((JPanel) c[0], BorderLayout.NORTH);
				JScrollPane sc = new JScrollPane(inner);
				sc.setBorder(null);
				sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
				sc.getVerticalScrollBar().setUnitIncrement(16);
				if (c[1].equals("keepers")) level1.addTab("Gatekeepers", sc); else level2.addTab((String) c[1], sc);
			}
			// Gatehouse's own notes, newest on top: always there to read, and in front when Gatehouse has just updated.
			Map<String, Object> own = Notes.own();
			String seenFile = "gatehouse-version-seen.txt", ownVersion = Notes.version(own), seenVersion = LaunchPopup.readFile(home.resolve(seenFile)).trim();
			boolean fresh = !ownVersion.isEmpty() && !ownVersion.equals(seenVersion);
			level1.addTab(fresh && !seenVersion.isEmpty() ? "Gatehouse updates (new)" : "Gatehouse updates", Notes.panel(own, "Gatehouse", "No notes came with this copy of Gatehouse."));
			level1.addChangeListener(ev -> {
				if (level1.getSelectedIndex() != 1 || ownVersion.isEmpty()) return;
				try { Files.createDirectories(home); Files.writeString(home.resolve(seenFile), ownVersion + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {}
				level1.setTitleAt(1, "Gatehouse updates");
			});
			// A Gatekeeper's own notes, asked of it: the third tab beside its servers.
			JPanel keeperNotes = new JPanel(new BorderLayout());
			level2.addTab("Gatekeeper updates", keeperNotes);
			deck.add(level1, "keepers");
			deck.add(level2, "servers");
			deck.setPreferredSize(new Dimension(680, 330));
			p.add(deck, BorderLayout.CENTER);
			List<Runnable> polls = new ArrayList<>();   // (what the open level asks again every few seconds)
			Runnable[] showKeepers = {null};
			java.util.function.Consumer<String> showServers;

			// A row: a name, a line under it with a dot, and things on the right.
			java.util.function.BiFunction<String, Component[], Object[]> row = (name, right) -> {
				JPanel r = new JPanel(new BorderLayout(14, 0));
				r.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2F4860)), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
				JLabel n = new JLabel(name);
				n.setFont(n.getFont().deriveFont(Font.BOLD, 17f));
				JLabel state = new JLabel(" ", LaunchPopup.dot(LaunchPopup.colour("off")), JLabel.LEFT);
				state.setIconTextGap(8);
				state.setFont(state.getFont().deriveFont(13f));
				JPanel text = new JPanel(new BorderLayout(0, 4));
				text.setOpaque(false);
				text.add(n, BorderLayout.NORTH); text.add(state, BorderLayout.SOUTH);
				text.setMinimumSize(new Dimension(60, 10));
				text.setPreferredSize(new Dimension(300, 46));   // (the line under the name takes what room there is; it doesn't ask for more)
				JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
				east.setOpaque(false);
				for (Component c : right) east.add(c);
				r.add(text, BorderLayout.CENTER); r.add(east, BorderLayout.EAST);
				r.setAlignmentX(Component.LEFT_ALIGNMENT);
				r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
				return new Object[] {r, n, state};
			};
			java.util.function.BiConsumer<JLabel, String[]> set = (label, st) -> { Runnable ui = () -> { label.setIcon(LaunchPopup.dot(LaunchPopup.colour(st[0]))); label.setText(st[1]); label.setToolTipText(st[1]); };
				if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui); };

			// ---- level 2: one Gatekeeper's servers
			showServers = url -> {
				polls.clear();
				serverList.removeAll();
				String kname = "";
				for (String[] k : keepers) if (k[0].equals(url)) kname = k[1];
				head.setText(url.isEmpty() ? "Other packs" : kname.isEmpty() ? "Gatekeeper at " + shown(url) : kname);
				sub.setText(url.isEmpty() ? "Packs on this device that no Gatekeeper looks after." : "Pick a server to play. The pack updates first, as always.");
				backB.setVisible(true);
				Map<String, Object[]> rows = new LinkedHashMap<>();   // server id -> {state label, pack or null, Play button, name label}
				java.util.function.BiConsumer<String, String[]> add = (id, pack) -> {
					LaunchPopup.PlayButton go = new LaunchPopup.PlayButton("Launch");   // (not "Play": it opens the server's launcher, which has Play)
					go.setToolTipText("Updates this pack, then shows its window before the game starts");
					JLabel missing = new JLabel("Not installed on this device");
					missing.setForeground(new Color(0x8F98A0));
					JButton install = new JButton("Install");
					install.setToolTipText("Sets this server's pack up in Prism Launcher on this device. The mods download on the first launch.");
					install.setVisible(false);
					Object[] r = row.apply(pack != null ? pack[1] : id, new Component[] {missing, install, go});
					go.setVisible(pack != null); missing.setVisible(pack == null);
					if (pack != null) go.addActionListener(ev -> { chosen[0] = pack; f.dispose(); });
					serverList.add((JPanel) r[0]); serverList.add(Box.createVerticalStrut(8));
					rows.put(id, new Object[] {r[2], pack, go, r[1], missing, install});
				};
				for (String[] pk : packs) if (pk[4].equals(url)) add.accept(pk[0].startsWith("gatehouse-") ? pk[0].substring("gatehouse-".length()) : pk[0], pk);
				ServerControl c = url.isEmpty() ? null : client(home, url, packs);
				level2.setSelectedIndex(0);
				level2.setEnabledAt(2, c != null);
				keeperNotes.removeAll();
				JLabel asking = new JLabel(c == null ? " " : "Asking the Gatekeeper\u2026");
				asking.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 0));
				keeperNotes.add(asking, BorderLayout.NORTH);
				if (c != null) {
					Runnable about = () -> {
						Map<String, Object> r;
						try { r = c.helper("GET", "/v1/about", null); if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("GET", "/v1/about", null); } } catch (IOException | RuntimeException e) { r = null; }
						Map<String, Object> got = r;
						Runnable ui = () -> {
							keeperNotes.removeAll();
							if (got != null && "200".equals(str(got.get("_code")))) keeperNotes.add(Notes.panel(got, "Gatekeeper", "This Gatekeeper sent no notes."), BorderLayout.CENTER);
							else { JLabel no = new JLabel(got == null ? "This Gatekeeper isn't answering, so its notes can't be shown." : "This Gatekeeper is from before it kept notes of its own (before Gatekeeper 2.0.0)."); no.setForeground(new Color(0x8F98A0)); no.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 0)); keeperNotes.add(no, BorderLayout.NORTH); }
							keeperNotes.revalidate(); keeperNotes.repaint();
						};
						if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
					};
					if (shot != null) about.run(); else new Thread(about, "about").start();
				}
				Runnable poll = () -> {
					Map<String, Object> cat = c == null ? null : catalog(c);
					Runnable ui = () -> {
						if (cat != null && cat.get("servers") instanceof List<?> l) {
							String nm = str(cat.get("name"));
							for (String[] k : keepers) if (k[0].equals(url) && !nm.isEmpty() && !nm.equals(k[1])) { k[1] = nm; saveKeepers(home, keepers); head.setText(nm); }
							for (Object o : l) {
								Map<String, Object> s = ServerControl.asMap(o);
								String id = str(s.get("id"));
								if (!rows.containsKey(id)) { add.accept(id, null); serverList.revalidate(); }
								Object[] r = rows.get(id);
								if (r[1] == null) ((JLabel) r[3]).setText(str(s.get("name")).isEmpty() ? id : str(s.get("name")));
								boolean on = "true".equals(str(s.get("online")));
								String profile = str(s.get("profile"));
								String kind = str(s.get("kind")), version = str(s.get("minecraft_version"));
								boolean plain = profile.isEmpty() && kind.equals("vanilla");
								String phase = str(s.get("phase"));
								set.accept((JLabel) r[0], new String[] {on ? "ok" : phase.equals("starting") || phase.equals("stopping") ? "warn" : "off", (on ? "Online" : phase.equals("starting") ? "Starting\u2026" : phase.equals("stopping") ? "Shutting down\u2026" : "Off") + (version.isEmpty() ? "" : ", Minecraft " + version) + (r[1] != null ? ""
										: plain ? (version.isEmpty() ? ". A plain server, but its version isn't known yet (it has to have been started once)." : ". A plain server: Install sets up a connection to it on this device.")
										: profile.isEmpty() ? ". It has mods, and its pack isn't published for launchers to install: ask the owner." : ". Install sets its pack up on this device.")});
								JButton ib = (JButton) r[5];
								if (r[1] == null && plain && !version.isEmpty() && !ib.isVisible() && ib.getActionListeners().length == 0) {
									ib.setVisible(true); ((JLabel) r[4]).setVisible(false);
									ib.setToolTipText("Makes a plain Minecraft " + version + " instance in Prism Launcher that joins this server");
									String plainName = str(s.get("name")).isEmpty() ? id : str(s.get("name")), addr = str(s.get("address"));
									ib.addActionListener(ev -> {
										int ok = JOptionPane.showConfirmDialog(f, "<html><div style='width:420px'>Set up <b>" + LaunchPopup.esc(plainName) + "</b> on this device?<br><br>It makes a plain Minecraft " + LaunchPopup.esc(version)
												+ " instance in Prism Launcher with this server (" + LaunchPopup.esc(addr) + ") in its server list. No mods. Prism downloads the game the first time you press Play. "
												+ "Its own launcher window can add a Steam shortcut afterwards; Prism Launcher has to be installed already.</div></html>", "Install", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
										if (ok != JOptionPane.OK_OPTION) return;
										try { PackInstall.vanilla(instances, id, plainName, addr, version, c); again[0] = url; f.dispose(); }
										catch (IOException | RuntimeException ex) { sub.setText("Not set up: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage())); }
									});
								}
								if (r[1] == null && !profile.isEmpty() && !ib.isVisible() && ib.getActionListeners().length == 0) {
									ib.setVisible(true); ((JLabel) r[4]).setVisible(false);
									ib.addActionListener(ev -> {
										ib.setEnabled(false);
										sub.setText("Looking at the pack\u2026");
										new Thread(() -> {
											String[] what; String problem = null;
											try { what = PackInstall.look(profile); } catch (IOException | RuntimeException ex) { what = null; problem = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
											String[] w = what; String pr = problem;
											SwingUtilities.invokeLater(() -> {
												if (w == null) { ib.setEnabled(true); sub.setText("Couldn't read the pack's profile: " + pr); return; }
												int ok = JOptionPane.showConfirmDialog(f, "<html><div style='width:420px'>Install <b>" + LaunchPopup.esc(w[1]) + "</b> on this device?<br><br>It makes a Prism Launcher instance (\"" + LaunchPopup.esc(w[0]) + "\") and downloads its setup files from<br>"
														+ LaunchPopup.esc(w[2]) + "<br><br>The mods download the first time you press Launch. No Steam shortcut is added; Prism Launcher has to be installed already.</div></html>", "Install", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
												if (ok != JOptionPane.OK_OPTION) { ib.setEnabled(true); sub.setText("Pick a server to play. The pack updates first, as always."); return; }
												new Thread(() -> {
													String err = null;
													try { PackInstall.install(instances, profile, c, m -> SwingUtilities.invokeLater(() -> sub.setText(m))); }
													catch (IOException | RuntimeException ex) { err = ex.getMessage() == null ? ex.toString() : ex.getMessage(); }
													String e2 = err;
													SwingUtilities.invokeLater(() -> {
														if (e2 != null) { ib.setEnabled(true); sub.setText("Not installed: " + e2); return; }
														again[0] = url; f.dispose();   // (the window comes straight back, with the pack listed and Play beside it)
													});
												}, "install").start();
											});
										}, "install-look").start();
									});
								}
							}
						} else for (Object[] r : rows.values()) if (r[1] != null) {
							// (No catalog: asleep, an older Gatekeeper, or refused. What this device can see for itself.)
							String[] pk = (String[]) r[1];
							new Thread(() -> { ServerControl sc = new ServerControl(Path.of(pk[3])); String[] st = sc.ban.equals("blacklist") ? new String[] {"off", "Not shown: this launcher is blacklisted"} : !sc.configured() ? new String[] {"off", "No server set up for this pack"} : sc.status(); set.accept((JLabel) r[0], st); }, "server-status").start();
						}
						// ---- Connections: the machine, MCSS and each server, each with the buttons this device is allowed.
						connList.removeAll();
						if (c != null) {
							boolean answering = cat != null && !cat.containsKey("_refused");
							Runnable[] again2 = {null};
							// (a press: ask Gatekeeper, say what it said, then look again)
							java.util.function.BiConsumer<JButton, java.util.concurrent.Callable<Map<String, Object>>> press = (b, call) -> b.addActionListener(ev -> {
								b.setEnabled(false);
								new Thread(() -> {
									String m;
									try { Map<String, Object> r = call.call(); m = !str(r.get("message")).isEmpty() ? str(r.get("message")) : !str(r.get("error")).isEmpty() ? str(r.get("error")) : "Done."; }
									catch (Exception ex) { m = "Couldn't reach the Gatekeeper."; }
									String msg = m;
									SwingUtilities.invokeLater(() -> sub.setText(msg));
									if (again2[0] != null) again2[0].run();
								}, "connection-act").start();
							});
							java.util.function.BiPredicate<String, String> sure = (title, text) -> JOptionPane.showConfirmDialog(f, "<html><div style='width:380px'>" + text + "</div></html>", title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION;
							// the machine
							JButton wakeB = new JButton("Wake"), sleepB = new JButton("Sleep");
							wakeB.setToolTipText("Wakes the server machine (Wake on LAN: works from the owner's home network)");
							sleepB.setToolTipText("Puts the server machine to sleep. Only while no Minecraft server is running.");
							Object[] mr = row.apply("Server machine", new Component[] {wakeB, sleepB});
							set.accept((JLabel) mr[2], answering ? new String[] {"ok", "Awake"} : new String[] {"off", "Not answering: asleep or off"});
							String[] wakePack = null;
							for (String[] pk : packs) if (pk[4].equals(url) && new ServerControl(Path.of(pk[3])).canWake()) wakePack = pk;
							String[] wp = wakePack;
							wakeB.setVisible(!answering && wp != null);
							sleepB.setVisible(answering && "true".equals(str(cat.get("perm_machine"))));
							if (wp != null) wakeB.addActionListener(ev -> { try { new ServerControl(Path.of(wp[3])).wake(); sub.setText("Wake signal sent: it can take a minute."); } catch (IOException ex) { sub.setText("Couldn't send the wake signal: " + ex.getMessage()); } });
							sleepB.addActionListener(ev -> { if (sure.test("Sleep", "Put the server machine to sleep?")) { ActionListenerOnce.run(sleepB, press, () -> c.helper("POST", "/v1/pc/sleep", "{}")); } });
							connList.add((JPanel) mr[0]); connList.add(Box.createVerticalStrut(8));
							if (answering && !cat.containsKey("_old")) {
								// MCSS
								String mph = str(cat.get("mcss_phase"));
								boolean mUp = mph.equals("running") || mph.equals("closing"), mBusy = mph.equals("starting") || mph.equals("closing"), force = "force".equals(str(cat.get("close")));
								JButton mb = new JButton(mUp ? (force ? "Force close MCSS" : "Close MCSS") : "Start MCSS");
								mb.setToolTipText(mUp ? "Closes MCSS. A running Minecraft server is stopped first (the world is saved)." : "Starts MCSS (the program that runs the Minecraft servers) on the server machine");
								Object[] cr = row.apply("MCSS (the server software)", new Component[] {mb});
								set.accept((JLabel) cr[2], new String[] {mBusy ? "warn" : mUp ? "ok" : "off", mph.equals("starting") ? "Starting\u2026" : mph.equals("closing") ? "Closing\u2026" : mUp ? "Running" : "Not running"});
								mb.setVisible("true".equals(str(cat.get("perm_mcss"))));
								mb.setEnabled(!mBusy);
								mb.addActionListener(ev -> { if (!mUp || sure.test("Close MCSS", "Close MCSS? Any Minecraft server that is running is stopped first (the world is saved), and anyone playing is disconnected.")) ActionListenerOnce.run(mb, press, () -> mUp ? c.mcssClose(force) : c.mcssStart()); });
								connList.add((JPanel) cr[0]); connList.add(Box.createVerticalStrut(8));
								// each server
								if (cat.get("servers") instanceof List<?> sl) for (Object o : sl) {
									Map<String, Object> s = ServerControl.asMap(o);
									String sid = str(s.get("id")), ph = str(s.get("phase")), snm = str(s.get("name")).isEmpty() ? sid : str(s.get("name"));
									boolean sUp = ph.equals("online") || ph.equals("stopping"), sBusy = ph.equals("starting") || ph.equals("stopping");
									JButton sb = new JButton(sUp ? "Stop server" : "Start server");
									sb.setToolTipText(sUp ? "Stops this Minecraft server: the world is saved first, and anyone playing is disconnected" : "Starts this Minecraft server. Gatekeeper starts MCSS first if it isn't running.");
									Object[] sr = row.apply(snm, new Component[] {sb});
									set.accept((JLabel) sr[2], new String[] {sBusy ? "warn" : ph.equals("online") ? "ok" : "off", ph.equals("online") ? "Online" : ph.equals("starting") ? "Starting\u2026" : ph.equals("stopping") ? "Shutting down\u2026" : ph.equals("nomcss") ? "Off (MCSS isn't running)" : "Off"});
									sb.setVisible("true".equals(str(s.get("allowed"))));
									sb.setEnabled(!sBusy);
									sb.addActionListener(ev -> { if (!sUp || sure.test("Stop server", "Stop " + LaunchPopup.esc(snm) + "? The world is saved first, and anyone playing is disconnected.")) ActionListenerOnce.run(sb, press, () -> c.helperAs(sid, "POST", sUp ? "/v1/server/stop" : "/v1/server/start", "{}")); });
									connList.add((JPanel) sr[0]); connList.add(Box.createVerticalStrut(8));
								}
							}
							again2[0] = () -> { for (Runnable r : new ArrayList<>(polls)) r.run(); };
						}
						connList.revalidate(); connList.repaint();
						if (c != null && cat == null) sub.setText("This Gatekeeper isn't answering (its machine may be asleep or off). Installed packs can still be started.");
						else if (cat != null && cat.containsKey("_refused")) sub.setText("This Gatekeeper answered, but " + str(cat.get("_refused")) + ".");
						else if (cat != null && rows.isEmpty()) sub.setText("This Gatekeeper looks after no servers yet.");
					};
					if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
				};
				polls.add(poll);
				if (shot != null) poll.run(); else new Thread(poll, "catalog").start();
				cards.show(deck, "servers");
				serverList.revalidate(); serverList.repaint();
				SwingUtilities.invokeLater(() -> Nav.home(f));
			};

			// ---- level 1: the Gatekeepers
			showKeepers[0] = () -> {
				polls.clear();
				keeperList.removeAll();
				head.setText(LaunchPopup.TOOL + " Minecraft Launcher");
				sub.setText(keepers.isEmpty() && !hasLoose ? "No Gatekeeper yet. Add one with the address, link or file its owner gave you." : "Pick a Gatekeeper to see its servers.");
				backB.setVisible(false);
				for (String[] k : keepers) {
					LaunchPopup.PlayButton open = new LaunchPopup.PlayButton("Open");
					open.setToolTipText("Shows the servers this Gatekeeper looks after");
					JButton remove = new JButton("Remove");
					remove.setToolTipText("Forgets this Gatekeeper on this device (packs already installed stay)");
					boolean used = false;
					for (String[] pk : packs) if (pk[4].equals(k[0])) used = true;
					remove.setVisible(!used);
					Object[] r = row.apply(k[1].isEmpty() ? "Gatekeeper at " + shown(k[0]) : k[1], new Component[] {remove, open});
					open.addActionListener(ev -> showServers.accept(k[0]));
					remove.addActionListener(ev -> { keepers.remove(k); saveKeepers(home, keepers); showKeepers[0].run(); });
					keeperList.add((JPanel) r[0]); keeperList.add(Box.createVerticalStrut(8));
					ServerControl c = client(home, k[0], packs);
					Runnable poll = () -> {
						Map<String, Object> cat = catalog(c);
						String[] st;
						if (cat == null) st = new String[] {"off", shown(k[0]) + ": not answering (its machine may be asleep or off)"};
						else if (cat.containsKey("_old")) st = new String[] {"ok", shown(k[0]) + ": answering"};
						else if (cat.containsKey("_refused")) st = new String[] {"warn", shown(k[0]) + ": " + str(cat.get("_refused"))};
						else {
							int n = cat.get("servers") instanceof List<?> l ? l.size() : 0, on = 0;
							if (cat.get("servers") instanceof List<?> l) for (Object o : l) if ("true".equals(str(ServerControl.asMap(o).get("online")))) on++;
							st = new String[] {"ok", shown(k[0]) + ": " + n + " server" + (n == 1 ? "" : "s") + (n == 0 ? "" : ", " + on + " online")};
							String nm = str(cat.get("name"));
							if (!nm.isEmpty() && !nm.equals(k[1])) { k[1] = nm; saveKeepers(home, keepers); SwingUtilities.invokeLater(() -> ((JLabel) r[1]).setText(nm)); }
						}
						set.accept((JLabel) r[2], st);
					};
					polls.add(poll);
					if (shot != null) poll.run(); else new Thread(poll, "catalog").start();
				}
				if (hasLoose) {
					LaunchPopup.PlayButton open = new LaunchPopup.PlayButton("Open");
					Object[] r = row.apply("Other packs", new Component[] {open});
					((JLabel) r[2]).setText("Packs on this device that no Gatekeeper looks after");
					open.addActionListener(ev -> showServers.accept(""));
					keeperList.add((JPanel) r[0]); keeperList.add(Box.createVerticalStrut(8));
				}
				cards.show(deck, "keepers");
				keeperList.revalidate(); keeperList.repaint();
				SwingUtilities.invokeLater(() -> Nav.home(f));
			};
			backB.addActionListener(ev -> showKeepers[0].run());
			Nav.tag(backB, "back");

			// ---- the bottom: add a Gatekeeper, Gatehouse's own Steam shortcut, Prism Launcher, Quit
			JButton addB = new JButton("Add Gatekeeper…");
			addB.setToolTipText("Connects this launcher to another Gatekeeper, with the address, pairing link or pairing file its owner gave you");
			JButton prismB = new LaunchPopup.PlayButton("Open Prism Launcher", LaunchPopup.SLATE, Color.WHITE, 14f), quit = new LaunchPopup.PlayButton("Quit", LaunchPopup.QUIT_RED, Color.WHITE, 14f);
			prismB.setToolTipText("Prism Launcher itself: accounts, other instances, settings");
			quit.setToolTipText("Closes the launcher");
			prismB.addActionListener(ev -> { openPrism[0] = true; f.dispose(); });
			// Restart: the main window again from the top (what's installed is read again, the Gatekeepers are asked again,
			// and a newer Gatehouse is looked for again).
			JButton restartB = new LaunchPopup.PlayButton("Restart", LaunchPopup.YELLOW, LaunchPopup.INK, 14f);
			restartB.setToolTipText("Opens the launcher again from the start, looking for a newer Gatehouse");
			restartB.addActionListener(ev -> { again[0] = "\u0000restart"; f.dispose(); });
			Nav.tag(restartB, "restart");
			Nav.tag(quit, "quit");
			Nav.start();
			quit.addActionListener(ev -> f.dispose());
			addB.addActionListener(ev -> {
				javax.swing.JTextField field = new javax.swing.JTextField(30);
				JButton file = new JButton("Open pairing file…");
				file.addActionListener(e2 -> {
					javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
					fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Gatekeeper pairing file (.gatekeeper)", "gatekeeper", "json"));
					if (fc.showOpenDialog(f) == javax.swing.JFileChooser.APPROVE_OPTION) { String[] got = parsePairing(LaunchPopup.readFile(fc.getSelectedFile().toPath())); field.setText(got == null ? "" : "gatehouse://pair/" + shown(got[0]) + (got[1].isEmpty() ? "" : "?name=" + java.net.URLEncoder.encode(got[1], StandardCharsets.UTF_8))); }
				});
				JPanel form = new JPanel(new BorderLayout(0, 8));
				form.add(new JLabel("<html><div style='width:420px'>Paste the address or pairing link the Gatekeeper's owner gave you (in Gatekeeper: Status, Share pairing), or open the pairing file they sent. This device has to be in that owner's Tailscale network.</div></html>"), BorderLayout.NORTH);
				JPanel line = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); line.add(field); line.add(file);
				form.add(line, BorderLayout.CENTER);
				if (JOptionPane.showConfirmDialog(f, form, "Add Gatekeeper", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
				String[] got = parsePairing(field.getText());
				if (got == null) { JOptionPane.showMessageDialog(f, "That doesn't look like a Gatekeeper's address, pairing link or pairing file.", "Add Gatekeeper", JOptionPane.PLAIN_MESSAGE); return; }
				for (String[] k : keepers) if (k[0].equals(got[0])) { showServers.accept(k[0]); return; }
				sub.setText("Asking the Gatekeeper at " + shown(got[0]) + "…");
				new Thread(() -> {
					ServerControl c = client(home, got[0], packs);
					String state = null;
					try { state = c.hello(); } catch (IOException | RuntimeException ignored) {}
					String st = state;
					SwingUtilities.invokeLater(() -> {
						if (st == null || st.isEmpty()) { sub.setText("Pick a Gatekeeper to see its servers."); JOptionPane.showMessageDialog(f, "<html><div style='width:400px'>No Gatekeeper answers at " + shown(got[0]) + ". Is its machine awake, and is this device in its owner's Tailscale network?</div></html>", "Add Gatekeeper", JOptionPane.PLAIN_MESSAGE); return; }
						keepers.add(new String[] {got[0], got[1]});
						saveKeepers(home, keepers);
						showServers.accept(got[0]);
					});
				}, "add-gatekeeper").start();
			});
			JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
			right.add(prismB); right.add(restartB); right.add(quit);
			JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			left.add(addB);
			JPanel bottom = new JPanel(new BorderLayout());
			bottom.add(left, BorderLayout.WEST); bottom.add(right, BorderLayout.EAST);
			List<Path> folders = new ArrayList<>();
			for (String[] e : packs) folders.add(Path.of(e[3]));
			Path launcherAt = SteamUpdate.launcherHome(folders);
			JPanel south = new JPanel(new BorderLayout(0, 10));
			if (launcherAt != null) south.add(LaunchPopup.steamRow(launcherAt, "launcher", "Update " + LaunchPopup.TOOL + "'s Steam config", f, () -> ((LaunchPopup.PlayButton) quit).halo()), BorderLayout.NORTH);
			south.add(bottom, BorderLayout.SOUTH);
			p.add(south, BorderLayout.SOUTH);
			f.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent ev) { closed.countDown(); } });
			f.setContentPane(p);
			if (openAt != null && (openAt.isEmpty() ? hasLoose : true)) showServers.accept(openAt);
			else { showKeepers[0].run(); if (fresh && !seenVersion.isEmpty()) level1.setSelectedIndex(1); }
			// Opened directly (not on the way back from a server): look for a newer Gatehouse; with one in place, close
			// and let the shortcut's script open it. (GATEHOUSE_UPDATED: just reopened for that, so not again.)
			// It asks first: the line under the heading says a newer one is there (which, and how big), with Update
			// Gatehouse and Later. OJ_GATEHOUSE_NEWER (testing): pretend one was found, "version|bytes".
			String pretend = System.getenv("OJ_GATEHOUSE_NEWER");
			if (pretend != null || (openAt == null && shot == null && System.getenv("GATEHOUSE_UPDATED") == null)) {
				Runnable look = () -> {
					String[] found = pretend != null ? new String[] {"", "", "", pretend.replaceAll("^.*\\|", ""), pretend.replaceAll("\\|.*$", "")} : Notes.newer(instances);
					if (found == null) return;
					Runnable ui = () -> {
						long bytes = 0; try { bytes = Long.parseLong(found[3]); } catch (NumberFormatException ignored) {}
						String have = Notes.version(Notes.own());
						ownUpdateText.setText("<html><b>A " + LaunchPopup.TOOL + " update is ready" + (found[4].isEmpty() || found[4].equals(have) ? "" : ": " + LaunchPopup.esc(found[4])) + ".</b> You have " + LaunchPopup.esc(have) + "." + (bytes > 0 ? " " + Math.max(1, Math.round(bytes / 1024.0)) + " KB to download." : "") + "</html>");
						ownUpdateLater.addActionListener(ev -> { ownUpdate.setVisible(false); f.validate(); });
						ownUpdateGo.addActionListener(ev -> {
							ownUpdateGo.setEnabled(false); ownUpdateLater.setEnabled(false);
							ownUpdateText.setText("Fetching the newer " + LaunchPopup.TOOL + "\u2026");
							new Thread(() -> {
								boolean ok = Notes.fetch(found);
								SwingUtilities.invokeLater(() -> {
									if (ok) { again[0] = "\u0000relaunch"; f.dispose(); return; }
									ownUpdateText.setText("<html>Couldn't fetch it just now. Nothing was changed; it will be offered again next time.</html>");
									ownUpdateLater.setText("Close"); ownUpdateLater.setEnabled(true);
								});
							}, "self-update").start();
						});
						ownUpdate.setVisible(true);
						f.validate();
						Nav.land(ownUpdateGo);
					};
					if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
				};
				if (shot != null) look.run(); else new Thread(look, "self-update").start();
			}
			f.pack();
			LaunchPopup.fit(f, false);
			if (shot != null && !shot.isEmpty()) {   // (testing: pictures instead of the window: <file>, and <file>-servers.png for the first Gatekeeper)
				try {
					level1.setSelectedIndex(1);
					f.validate();
					java.awt.image.BufferedImage gi = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
					java.awt.Graphics2D gg = gi.createGraphics(); p.paint(gg); gg.dispose();
					javax.imageio.ImageIO.write(gi, "png", new java.io.File(shot.replaceAll("\\.png$", "") + "-gatehouse.png"));
					level1.setSelectedIndex(0);
					for (int n = 0; n < 4; n++) {
						if (n == 1) { if (keepers.isEmpty()) break; showServers.accept(keepers.get(0)[0]); }
						if (n == 2) level2.setSelectedIndex(1);
						if (n == 3) level2.setSelectedIndex(2);
						f.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
						javax.imageio.ImageIO.write(img, "png", new java.io.File(n == 0 ? shot : shot.replaceAll("\\.png$", "") + (n == 1 ? "-servers.png" : n == 2 ? "-connections.png" : "-gatekeeper.png")));
					}
				} catch (IOException ex) { ex.printStackTrace(); }
				f.dispose();
				return;
			}
			Timer timer = new Timer(6000, ev -> { if (f.isDisplayable()) for (Runnable r : new ArrayList<>(polls)) new Thread(r, "catalog").start(); else ((Timer) ev.getSource()).stop(); });
			timer.start();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
			LaunchPopup.bringForwardOnce(f);
			SwingUtilities.invokeLater(() -> Nav.home(f));
		});
		closed.await();
	}
}
