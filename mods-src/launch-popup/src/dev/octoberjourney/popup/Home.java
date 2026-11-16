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
			List<String[]> packs = installed(instances);
			if (play != null) {
				String want = play; play = null;
				String[] hit = null;
				for (String[] e : packs) if (e[0].equals(want)) hit = e;
				if (hit != null) { LaunchPopup.startGame(prismCmd, hit, true); openAt = back(packs); if (openAt == null) return; continue; }
			}
			String[][] chosen = {null};
			boolean[] openPrism = {false};
			window(instances, home, packs, openAt, chosen, openPrism);
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
	private static void window(Path instances, Path home, List<String[]> packs, String openAt, String[][] chosen, boolean[] openPrism) throws Exception {
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
			top.add(headRow, BorderLayout.NORTH); top.add(sub, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			CardLayout cards = new CardLayout();
			JPanel deck = new JPanel(cards);
			JPanel keeperList = new JPanel(), serverList = new JPanel();
			for (JPanel l : new JPanel[] {keeperList, serverList}) l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
			for (Object[] c : new Object[][] {{keeperList, "keepers"}, {serverList, "servers"}}) {
				JPanel inner = new JPanel(new BorderLayout());
				inner.add((JPanel) c[0], BorderLayout.NORTH);
				JScrollPane sc = new JScrollPane(inner);
				sc.setBorder(null);
				sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
				sc.getVerticalScrollBar().setUnitIncrement(16);
				deck.add(sc, (String) c[1]);
			}
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
				JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
				east.setOpaque(false);
				for (Component c : right) east.add(c);
				r.add(text, BorderLayout.CENTER); r.add(east, BorderLayout.EAST);
				r.setAlignmentX(Component.LEFT_ALIGNMENT);
				r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
				return new Object[] {r, n, state};
			};
			java.util.function.BiConsumer<JLabel, String[]> set = (label, st) -> { Runnable ui = () -> { label.setIcon(LaunchPopup.dot(LaunchPopup.colour(st[0]))); label.setText(st[1]); };
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
					LaunchPopup.PlayButton go = new LaunchPopup.PlayButton("Play");
					go.setToolTipText("Updates this pack, then shows its window before the game starts");
					JLabel missing = new JLabel("Not installed on this device");
					missing.setForeground(new Color(0x8F98A0));
					Object[] r = row.apply(pack != null ? pack[1] : id, new Component[] {missing, go});
					go.setVisible(pack != null); missing.setVisible(pack == null);
					if (pack != null) go.addActionListener(ev -> { chosen[0] = pack; f.dispose(); });
					serverList.add((JPanel) r[0]); serverList.add(Box.createVerticalStrut(8));
					rows.put(id, new Object[] {r[2], pack, go, r[1], missing});
				};
				for (String[] pk : packs) if (pk[4].equals(url)) add.accept(pk[0], pk);
				ServerControl c = url.isEmpty() ? null : client(home, url, packs);
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
								set.accept((JLabel) r[0], new String[] {on ? "ok" : "off", (on ? "Online" : "Off") + (r[1] == null ? ". To play here, install its pack on this device (run setup for it)." : "")});
							}
						} else for (Object[] r : rows.values()) if (r[1] != null) {
							// (No catalog: asleep, an older Gatekeeper, or refused. What this device can see for itself.)
							String[] pk = (String[]) r[1];
							new Thread(() -> { ServerControl sc = new ServerControl(Path.of(pk[3])); String[] st = sc.ban.equals("blacklist") ? new String[] {"off", "Not shown: this launcher is blacklisted"} : !sc.configured() ? new String[] {"off", "No server set up for this pack"} : sc.status(); set.accept((JLabel) r[0], st); }, "server-status").start();
						}
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
			};
			backB.addActionListener(ev -> showKeepers[0].run());

			// ---- the bottom: add a Gatekeeper, Gatehouse's own Steam shortcut, Prism Launcher, Quit
			JButton addB = new JButton("Add Gatekeeper…");
			addB.setToolTipText("Connects this launcher to another Gatekeeper, with the address, pairing link or pairing file its owner gave you");
			JButton prismB = new LaunchPopup.PlayButton("Open Prism Launcher", LaunchPopup.SLATE, Color.WHITE, 14f), quit = new LaunchPopup.PlayButton("Quit", LaunchPopup.QUIT_RED, Color.WHITE, 14f);
			prismB.setToolTipText("Prism Launcher itself: accounts, other instances, settings");
			quit.setToolTipText("Closes the launcher");
			prismB.addActionListener(ev -> { openPrism[0] = true; f.dispose(); });
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
			right.add(prismB); right.add(quit);
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
			if (openAt != null && (openAt.isEmpty() ? hasLoose : true)) showServers.accept(openAt); else showKeepers[0].run();
			f.pack();
			LaunchPopup.fit(f, false);
			if (shot != null && !shot.isEmpty()) {   // (testing: pictures instead of the window: <file>, and <file>-servers.png for the first Gatekeeper)
				try {
					for (int n = 0; n < 2; n++) {
						if (n == 1) { if (keepers.isEmpty()) break; showServers.accept(keepers.get(0)[0]); }
						f.validate();
						java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.getWidth(), p.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
						java.awt.Graphics2D g = img.createGraphics(); p.paint(g); g.dispose();
						javax.imageio.ImageIO.write(img, "png", new java.io.File(n == 0 ? shot : shot.replaceAll("\\.png$", "") + "-servers.png"));
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
		});
		closed.await();
	}
}
