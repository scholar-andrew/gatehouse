package dev.octoberjourney.popup;

import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * Systems, from the launcher: every machine this one knows of and how it stands with each (a neighbor, remembered,
 * or both), with Remember and Forget. A system is what one machine has of the program: its Gatehouse, its
 * Gatekeeper, or both.
 *
 * Which system this launcher is part of: when there is a Gatekeeper on this machine and the launcher is linked to it,
 * they are one system under the Gatekeeper's id, and what the system remembers is kept by the Gatekeeper (the
 * launcher asks it, and passes on the systems it meets at other Gatekeepers). With no Gatekeeper here, the launcher
 * is the whole system: it goes by its own install id, and keeps what it remembers in systems.json in its own folder.
 *
 * Neighbors are learned from each Gatekeeper this launcher is connected to (POST /v1/systems), which answers only a
 * launcher its owner has let in somewhere.
 */
final class SystemsBox {
	private SystemsBox() {}
	private static String str(Object o) { return o == null ? "" : o.toString(); }
	private static boolean yes(Object o) { return Boolean.TRUE.equals(o) || "true".equals(str(o)); }
	private static String sid(Object o) { String s = str(o).toLowerCase(java.util.Locale.ROOT); return s.matches("[0-9a-f\\-]{16,64}") ? s : ""; }

	/** Written the way the Gatekeeper reads it. */
	static String json(Object o) {
		if (o instanceof Map<?, ?> m) { StringBuilder sb = new StringBuilder("{"); for (Map.Entry<?, ?> e : m.entrySet()) sb.append(sb.length() > 1 ? "," : "").append('"').append(ServerControl.jsonEsc(str(e.getKey()))).append("\":").append(json(e.getValue())); return sb + "}"; }
		if (o instanceof List<?> l) { StringBuilder sb = new StringBuilder("["); for (Object v : l) sb.append(sb.length() > 1 ? "," : "").append(json(v)); return sb + "]"; }
		if (o instanceof Boolean b) return b.toString();
		return "\"" + ServerControl.jsonEsc(str(o)) + "\"";
	}
	private static Map<String, Object> read(Path f) { try { return new LinkedHashMap<>(ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(f)))); } catch (RuntimeException e) { return new LinkedHashMap<>(); } }
	private static void write(Path f, Object o) { try { Files.createDirectories(f.getParent()); Files.writeString(f, json(o) + "\n", StandardCharsets.UTF_8); } catch (IOException ignored) {} }

	/** The id of the system this launcher is part of: the Gatekeeper's on this machine once the launcher has heard it
	 *  from that Gatekeeper (system-local.json), its own install id otherwise. */
	static String systemId(Path home) {
		String taken = sid(read(home.resolve("system-local.json")).get("id"));
		return taken.isEmpty() ? Home.installId(home).toLowerCase(java.util.Locale.ROOT) : taken;
	}
	/** What a launcher that is its machine's whole system remembers: id -> {name, device, gatehouse, gatekeeper, address, since}. */
	static Map<String, Object> own(Path home) { return new LinkedHashMap<>(ServerControl.asMap(read(home.resolve("systems.json")).get("remembered"))); }
	private static void saveOwn(Path home, Map<String, Object> kept) { Map<String, Object> o = new LinkedHashMap<>(); o.put("remembered", kept); write(home.resolve("systems.json"), o); }

	private static Map<String, Object> ask(ServerControl c, String path, String body) {
		try {
			Map<String, Object> r = c.helper("POST", path, body);
			if ("401".equals(str(r.get("_code")))) { c.hello(); r = c.helper("POST", path, body); }
			return r;
		} catch (IOException | RuntimeException e) { return null; }
	}
	private static Map<String, Object> rowOf(Map<String, Object> s) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("id", sid(s.get("id"))); r.put("name", str(s.get("name"))); r.put("device", str(s.get("device"))); r.put("gatehouse", yes(s.get("gatehouse"))); r.put("gatekeeper", yes(s.get("gatekeeper")));
		r.put("address", str(s.get("address"))); r.put("via", str(s.get("via"))); r.put("neighbor", true); r.put("remembered", false); r.put("remembers_you", false);
		r.put("remembers", s.get("remembers") instanceof List<?> l ? new ArrayList<Object>(l) : new ArrayList<Object>());
		return r;
	}
	static String status(Map<String, Object> r) {
		boolean n = yes(r.get("neighbor")), k = yes(r.get("remembered"));
		return (n && k ? "Neighbor, remembered" : n ? "Neighbor" : k ? "Remembered" : "Unknown") + (yes(r.get("remembers_you")) ? " (remembers this system)" : "");
	}

	/** Asks every Gatekeeper this launcher knows. Returns {the rows, the address of the Gatekeeper on this machine or
	 *  null, how many Gatekeepers answered, how many said this launcher isn't in their constellation}. */
	static Object[] gather(Path home, List<String[]> keepers, List<String[]> packs) {
		String machine = systemId(home), name = "";
		try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ignored) {}
		Map<String, Object> local = read(home.resolve("system-local.json")), kept = own(home);
		boolean whole = sid(local.get("id")).isEmpty();   // (no Gatekeeper on this machine that this launcher has heard from)
		Map<String, Object> say = new LinkedHashMap<>();
		say.put("machine", machine); say.put("name", name); say.put("device", ""); say.put("gatehouse", true);
		say.put("remembers", whole ? new ArrayList<Object>(kept.keySet()) : local.get("remembers") instanceof List<?> l ? new ArrayList<Object>(l) : new ArrayList<Object>());
		Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
		String localUrl = null;
		int answered = 0, outside = 0;
		for (String[] k : keepers) {
			Map<String, Object> r = ask(Home.client(home, k[0], packs), "/v1/systems", json(say));
			if (r == null || !"200".equals(str(r.get("_code")))) continue;
			answered++;
			if (!"ok".equals(str(r.get("result")))) { outside++; continue; }
			if (yes(r.get("you_are_local"))) { localUrl = k[0]; continue; }   // (asked again below, once the others have answered)
			String kname = k[1].isEmpty() ? str(ServerControl.asMap(r.get("me")).get("name")) : k[1];
			if (r.get("systems") instanceof List<?> l) for (Object o : l) {
				Map<String, Object> s = ServerControl.asMap(o);
				String id = sid(s.get("id"));
				if (id.isEmpty() || id.equals(machine)) continue;
				Map<String, Object> row = rows.get(id);
				if (row == null) { row = rowOf(s); row.put("via", kname); rows.put(id, row); }
				else if (s.get("remembers") instanceof List<?> more) for (Object v : more) if (!((List<Object>) row.get("remembers")).contains(v)) ((List<Object>) row.get("remembers")).add(v);
			}
		}
		if (localUrl != null) {
			// One system with the Gatekeeper here: it is told who the launcher met elsewhere, and says how each stands.
			say.put("systems", new ArrayList<Object>(rows.values()));
			Map<String, Object> r = ask(Home.client(home, localUrl, packs), "/v1/systems", json(say));
			if (r != null && "ok".equals(str(r.get("result"))) && r.get("standing") instanceof List<?> l) {
				rows.clear();
				List<Object> remembers = new ArrayList<>();
				for (Object o : l) {
					Map<String, Object> s = ServerControl.asMap(o), row = rowOf(s);
					if (str(row.get("id")).isEmpty()) continue;
					row.put("neighbor", yes(s.get("neighbor"))); row.put("remembered", yes(s.get("remembered"))); row.put("remembers_you", yes(s.get("remembers_you")));
					if (yes(s.get("unknown"))) continue;   // (the Gatekeeper's own list has those; they are nobody to the launcher)
					if (yes(row.get("remembered"))) remembers.add(row.get("id"));
					rows.put(str(row.get("id")), row);
				}
				Map<String, Object> keep = new LinkedHashMap<>();
				keep.put("id", sid(ServerControl.asMap(r.get("me")).get("id"))); keep.put("url", localUrl); keep.put("remembers", remembers);
				if (!sid(keep.get("id")).isEmpty()) write(home.resolve("system-local.json"), keep);
			}
		} else {
			// (part of a system whose Gatekeeper, on this machine, isn't answering: what that Gatekeeper said it remembers the last time is shown, and nothing can be changed until it is on)
			if (!whole) { kept = new LinkedHashMap<>(); if (local.get("remembers") instanceof List<?> l) for (Object v : l) if (!sid(v).isEmpty()) kept.put(sid(v), new LinkedHashMap<String, Object>()); }
			if (!whole && answered > 0 && answered == keepers.size()) { try { Files.deleteIfExists(home.resolve("system-local.json")); } catch (IOException ignored) {} }   // (the Gatekeeper that was here is gone: the launcher is the whole system again)
			boolean changed = false;
			for (Map<String, Object> row : rows.values()) {
				String id = str(row.get("id"));
				row.put("remembers_you", ((List<?>) row.get("remembers")).contains(machine));
				if (kept.containsKey(id)) {
					row.put("remembered", true);
					Map<String, Object> k = new LinkedHashMap<>(ServerControl.asMap(kept.get(id)));
					String was = json(k);
					k.put("name", row.get("name")); k.put("device", row.get("device")); k.put("gatehouse", row.get("gatehouse")); k.put("gatekeeper", row.get("gatekeeper")); if (!str(row.get("address")).isEmpty()) k.put("address", row.get("address"));
					if (!was.equals(json(k))) { kept.put(id, k); changed = true; }
				}
			}
			for (Map.Entry<String, Object> e : kept.entrySet()) if (!rows.containsKey(e.getKey())) {
				if (!whole) continue;   // (its name is with the Gatekeeper that isn't answering)
				Map<String, Object> row = rowOf(ServerControl.asMap(e.getValue()));
				row.put("id", e.getKey()); row.put("neighbor", false); row.put("remembered", true);
				rows.put(e.getKey(), row);
			}
			if (changed && whole) saveOwn(home, kept);
		}
		return new Object[] {new ArrayList<>(rows.values()), localUrl, answered, outside, localUrl == null && !sid(read(home.resolve("system-local.json")).get("id")).isEmpty()};
	}

	/** Remembers a system, or forgets it. Returns what to tell the person. */
	static String remember(Path home, List<String[]> packs, String localUrl, Map<String, Object> row, boolean forget) {
		String id = str(row.get("id")), name = str(row.get("name"));
		if (localUrl == null && !sid(read(home.resolve("system-local.json")).get("id")).isEmpty()) return "This launcher and the Gatekeeper on this machine are one system, and that Gatekeeper keeps what it remembers. It isn't answering: start it (Servers, Start Gatekeeper), then try again.";
		if (localUrl != null) {
			Map<String, Object> say = new LinkedHashMap<>();
			say.put("id", id); say.put("forget", forget);
			Map<String, Object> r = ask(Home.client(home, localUrl, packs), "/v1/systems/remember", json(say));
			return r == null ? "The Gatekeeper on this machine isn't answering." : str(r.get("message")).isEmpty() ? "The Gatekeeper on this machine didn't take that." : str(r.get("message"));
		}
		Map<String, Object> kept = own(home);
		if (forget) { if (kept.remove(id) == null) return "That system isn't remembered."; saveOwn(home, kept); return name + " is no longer remembered."; }
		if (!yes(row.get("neighbor"))) return "A system can be remembered while it is in a constellation with this one. " + name + " isn't right now.";
		if (kept.size() >= 300) return "There are already 300 remembered systems: forget one first.";
		Map<String, Object> k = new LinkedHashMap<>();
		k.put("name", name); k.put("device", row.get("device")); k.put("gatehouse", row.get("gatehouse")); k.put("gatekeeper", row.get("gatekeeper")); k.put("address", row.get("address"));
		k.put("since", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
		kept.put(id, k);
		saveOwn(home, kept);
		return name + " is remembered. It stays in this list after the constellation is left. It gives that system nothing: no permission, no whitelist, no pack.";
	}

	/** The window. */
	static void show(java.awt.Component over, Path home, List<String[]> keepers, List<String[]> packs) {
		DefaultTableModel tm = new DefaultTableModel(new Object[] {"System", "Machine", "What it has", "How it stands", "Its Gatekeeper's address", "Seen through"}, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(tm);
		table.setRowHeight(26);
		table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		int[] wide = {160, 80, 160, 240, 150, 110};
		for (int i = 0; i < wide.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(wide[i]);
		List<Map<String, Object>> shown = new ArrayList<>();
		String[] localUrl = {null};
		JLabel head = new JLabel("<html><div style='width:820px'>Asking your Gatekeepers…</div></html>"), said = new JLabel(" ");
		JButton keep = new JButton("Remember"), forget = new JButton("Forget");
		keep.setToolTipText("Keeps the chosen system in this list after the constellation is left. Only a neighbor can be remembered. It gives that system nothing.");
		forget.setToolTipText("Stops remembering the chosen system");
		keep.setEnabled(false); forget.setEnabled(false);
		Runnable pick = () -> { int i = table.getSelectedRow(); Map<String, Object> r = i >= 0 && i < shown.size() ? shown.get(i) : null; keep.setEnabled(r != null && yes(r.get("neighbor")) && !yes(r.get("remembered"))); forget.setEnabled(r != null && yes(r.get("remembered"))); };
		table.getSelectionModel().addListSelectionListener(e -> pick.run());
		Runnable[] load = {null};
		load[0] = () -> new Thread(() -> {
			Object[] got = gather(home, keepers, packs);
			SwingUtilities.invokeLater(() -> {
				int was = table.getSelectedRow();
				String wasId = was >= 0 && was < shown.size() ? str(shown.get(was).get("id")) : "";
				tm.setRowCount(0); shown.clear();
				localUrl[0] = (String) got[1];
				for (Object o : (List<?>) got[0]) {
					Map<String, Object> s = ServerControl.asMap(o);
					shown.add(s);
					boolean house = yes(s.get("gatehouse")), keeper = yes(s.get("gatekeeper"));
					tm.addRow(new Object[] {str(s.get("name")), str(s.get("device")), house && keeper ? "Gatehouse and Gatekeeper" : keeper ? "Gatekeeper" : "Gatehouse", status(s), str(s.get("address")), str(s.get("via"))});
					if (!wasId.isEmpty() && wasId.equals(str(s.get("id")))) table.setRowSelectionInterval(tm.getRowCount() - 1, tm.getRowCount() - 1);
				}
				int answered = (Integer) got[2], outside = (Integer) got[3];
				String text = "A system is what one machine has of the program: its Gatehouse, its Gatekeeper, or both. A neighbor is in a constellation with this one now. A remembered one is kept by you, also after the constellation is left: choose a neighbor and press Remember. Each side decides for itself, and it gives the other nothing."
						+ (localUrl[0] != null ? " This launcher and the Gatekeeper on this machine are one system: what it remembers is kept by that Gatekeeper." : (Boolean) got[4] ? " This launcher and the Gatekeeper on this machine are one system, but that Gatekeeper isn't answering: nothing can be changed until it is on." : " This launcher is this machine's whole system.")
						+ (keepers.isEmpty() ? " Add a Gatekeeper first: neighbors are the systems in its constellation." : answered == 0 ? " None of your Gatekeepers is answering right now, so only remembered systems are shown." : outside > 0 && outside == answered ? " Your Gatekeeper hasn't let this launcher in anywhere yet, so it isn't told who else is there." : "");
				head.setText("<html><div style='width:820px'>" + LaunchPopup.esc(text) + "</div></html>");
				pick.run();
			});
		}, "systems").start();
		java.util.function.Consumer<Boolean> act = forgetIt -> {
			int i = table.getSelectedRow();
			if (i < 0 || i >= shown.size()) return;
			Map<String, Object> row = shown.get(i);
			new Thread(() -> { String told = remember(home, packs, localUrl[0], row, forgetIt); SwingUtilities.invokeLater(() -> { said.setText("<html><div style='width:600px'>" + LaunchPopup.esc(told) + "</div></html>"); load[0].run(); }); }, "remember").start();
		};
		keep.addActionListener(e -> act.accept(false));
		forget.addActionListener(e -> act.accept(true));
		JScrollPane sp = new JScrollPane(table); sp.setPreferredSize(new java.awt.Dimension(860, 300));
		JPanel lp = new JPanel(new BorderLayout(0, 6));
		lp.add(head, BorderLayout.NORTH); lp.add(sp, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)); buttons.add(keep); buttons.add(forget);
		JPanel foot = new JPanel(new BorderLayout()); foot.add(buttons, BorderLayout.WEST); foot.add(said, BorderLayout.CENTER);
		lp.add(foot, BorderLayout.SOUTH);
		load[0].run();
		JOptionPane.showMessageDialog(over, lp, "Systems", JOptionPane.PLAIN_MESSAGE);
	}
}
