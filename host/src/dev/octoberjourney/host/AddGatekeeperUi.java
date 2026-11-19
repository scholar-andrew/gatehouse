package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * R76 (R74's follow-up): "Add a Gatekeeper" on the Gatekeepers page walks the owner through putting another Gatekeeper
 * on that page, one step at a time, doing here what can be done here and watching for what has to be done at the
 * other machine. Nothing is given away from here: the other machine's owner still lets this one in, or lets it manage,
 * at that machine (see Manage). Two ways round:
 *   - the other machine joins this one: this machine's address to copy; then Let it in, here, when it asks; then, at
 *     the other machine, "Let that Gatekeeper manage this one".
 *   - this one joins the other: its address, and Join, here; then, at the other machine, "Allow in" and "Let it manage".
 * Each step looks again every two seconds and moves on by itself once it is done.
 */
final class AddGatekeeperUi {
	private AddGatekeeperUi() {}

	private static JLabel text(String html, float size, boolean bold, Color c) {
		JLabel l = new JLabel("<html><div style='width:520px'>" + html + "</div></html>");
		l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, dev.gatehouse.look.SteamLook.pt(size)));
		l.setForeground(c); l.setAlignmentX(0f); l.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		return l;
	}
	private static JPanel page(String step, String title) {
		JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(14, 18, 10, 18));
		p.add(text(step, 12f, true, new Color(0x66C0F4)));
		p.add(text(title, 17f, true, new Color(0xE6EDF5)));
		return p;
	}
	private static JPanel row(java.awt.Component... cs) {
		JPanel r = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4)); r.setOpaque(false); r.setAlignmentX(0f);
		for (java.awt.Component c : cs) r.add(c);
		return r;
	}
	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

	/** Opens the walk-through over the window. after: run when it is closed (the page looks again). */
	static void open(java.awt.Component over, Runnable after) {
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(over), "Add a Gatekeeper", java.awt.Dialog.ModalityType.MODELESS);
		CardLayout cards = new CardLayout();
		JPanel deck = new JPanel(cards);
		Color dim = new Color(0xA9B5C1), good = new Color(0x8BD46E), warn = new Color(0xE9B949);
		Map<String, Object> me = ServerToolsUi.ask("GET", "/admin/manage/list", null);
		String myAddress = Json.str(me.get("address")), myName = Json.str(me.get("name")).isEmpty() ? "this Gatekeeper" : Json.str(me.get("name"));
		java.util.Set<String> before = new java.util.HashSet<>();   // (Gatekeepers already in: not the one being added)
		for (Object o : Json.list(ServerToolsUi.ask("GET", "/admin/constellation", null).get("clients"))) {
			Map<String, Object> k = Json.map(o);
			if (Json.str(k.get("membership")).equals("member")) before.add(Json.str(k.get("id")));
		}
		String[] watching = {""};   // which step is being watched: "ask", "manage", "joined"
		String[] who = {"", ""};    // the other one's id here (way 1) and its name
		Timer[] tick = {null};

		// ---- 0: which way round
		JPanel p0 = page("Step 1", "How should the two Gatekeepers meet?");
		p0.add(text("A Gatekeeper shows up on this page once its owner lets this one manage it. First the two join one constellation: "
				+ "one of them joins the other. Either way works; pick the one that is easier from where you are.", 13f, false, dim));
		JButton wayIn = dev.gatehouse.look.SteamLook.main(new JButton("The other machine joins this one"));
		JButton wayOut = new JButton("This one joins the other machine");
		boolean joinedAlready = ServerTools.yes(me.get("joined"));
		wayOut.setEnabled(!joinedAlready);
		if (joinedAlready) wayOut.setToolTipText("This Gatekeeper has already joined another one's constellation (Constellation page)");
		p0.add(Box(12)); p0.add(row(wayIn)); p0.add(text("Best when this is the main machine, like the PC that runs your servers.", 12.5f, false, dim));
		p0.add(Box(6)); p0.add(row(wayOut)); p0.add(text(joinedAlready ? "This Gatekeeper has already joined another one, so it can't join a second."
				: "Best when the other machine is the main one.", 12.5f, false, dim));
		deck.add(p0, "0");

		// ---- way 1, step 2: the other machine joins this one
		JPanel p1 = page("Step 2 of 4", "On the other machine, join this Gatekeeper");
		p1.add(text("On the other machine, open Gatekeeper, then the menu, <b>Constellation</b>. Paste this address into the box beside <b>Join</b>, and press Join:", 13f, false, dim));
		JTextField addr = new JTextField(myAddress.isEmpty() ? "(this machine has no Tailscale address yet: Host setup)" : myAddress, 22);
		addr.setEditable(false);
		JButton copy = new JButton("Copy");
		copy.addActionListener(e -> { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(addr.getText()), null); copy.setText("Copied"); });
		p1.add(row(addr, copy));
		JLabel s1 = text("Waiting for it to ask to join…", 13f, true, warn);
		JButton letIn = dev.gatehouse.look.SteamLook.main(new JButton("Let it in")); letIn.setVisible(false);
		p1.add(Box(8)); p1.add(s1); p1.add(row(letIn));
		deck.add(p1, "1");

		// ---- way 1, step 3 (and way 2's): the other machine lets this one manage it
		JPanel p2 = page("Step 3 of 4", "On the other machine, let this one manage it");
		JLabel p2say = text("", 13f, false, dim);
		p2.add(p2say);
		JLabel s2 = text("Waiting for it to let this one manage it…", 13f, true, warn);
		p2.add(Box(8)); p2.add(s2);
		deck.add(p2, "2");

		// ---- way 2, step 2: this one joins the other
		JPanel p3 = page("Step 2 of 4", "Join the other Gatekeeper");
		p3.add(text("On the other machine, its address is under the menu's <b>Constellation</b> (or Host setup). Type it here, like 100.x.y.z:25570:", 13f, false, dim));
		JTextField theirs = new JTextField(22);
		JButton join = dev.gatehouse.look.SteamLook.main(new JButton("Join"));
		JLabel s3 = text(" ", 13f, false, dim);
		p3.add(row(theirs, join)); p3.add(s3);
		deck.add(p3, "3");

		// ---- the end
		JPanel p4 = page("Step 4 of 4", "Done");
		JLabel s4 = text("", 13f, false, good);
		JButton close = dev.gatehouse.look.SteamLook.main(new JButton("Close"));
		p4.add(s4); p4.add(Box(10)); p4.add(row(close));
		deck.add(p4, "4");

		JButton cancel = new JButton("Cancel");
		JPanel foot = new JPanel(new BorderLayout()); foot.setBorder(BorderFactory.createEmptyBorder(0, 18, 12, 18));
		JLabel footSay = text("Nothing here gives anything away: the other machine's owner decides, at that machine.", 11.5f, false, dim);
		foot.add(footSay, BorderLayout.CENTER); foot.add(cancel, BorderLayout.EAST);
		JPanel all = new JPanel(new BorderLayout()); all.add(deck, BorderLayout.CENTER); all.add(foot, BorderLayout.SOUTH);
		d.setContentPane(all);

		Runnable finish = () -> { if (tick[0] != null) tick[0].stop(); d.dispose(); if (after != null) after.run(); };
		cancel.addActionListener(e -> finish.run());
		close.addActionListener(e -> finish.run());
		d.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent e) { if (tick[0] != null) tick[0].stop(); } });

		// (what is looked at every two seconds, for the step shown)
		Runnable look = () -> ServerToolsUi.later(() -> {
			String w = watching[0];
			if (w.equals("ask")) {
				Map<String, Object> c = ServerToolsUi.ask("GET", "/admin/constellation", null);
				for (Object o : Json.list(c.get("clients"))) {
					Map<String, Object> k = Json.map(o);
					String ms = Json.str(k.get("membership"));
					if (!Json.str(k.get("type")).equals("Gatekeeper") || before.contains(Json.str(k.get("id")))) continue;
					if (ms.equals("asked") || ms.equals("member")) {
						String id = Json.str(k.get("id")), nm = Json.str(k.get("name"));
						SwingUtilities.invokeLater(() -> {
							who[0] = id; who[1] = nm;
							if (ms.equals("member")) { watching[0] = "manage"; toManage(p2say, nm, true); cards.show(deck, "2"); return; }
							s1.setText("<html><div style='width:520px'><b>" + esc(nm) + "</b> asks to join. Is that the other machine?</div></html>"); s1.setForeground(good);
							letIn.setText("Let " + nm + " in"); letIn.setVisible(true); d.pack();
						});
						return;
					}
				}
			} else if (w.equals("manage") || w.equals("joined")) {
				Map<String, Object> l = ServerToolsUi.ask("GET", "/admin/manage/list", null);
				for (Object o : Json.list(l.get("gatekeepers"))) {
					Map<String, Object> g = Json.map(o);
					String key = Json.str(g.get("key"));
					if (w.equals("manage") ? key.equals(who[0]) : key.equals("lead")) {
						SwingUtilities.invokeLater(() -> {
							watching[0] = "";
							s4.setText("<html><div style='width:520px'><b>" + esc(Json.str(g.get("name"))) + "</b> is on your Gatekeepers page now. Press Open on its card to manage its servers, players and chat"
									+ " from here.</div></html>");
							cards.show(deck, "4"); cancel.setVisible(false); d.pack();
						});
						return;
					}
				}
				if (w.equals("joined")) {
					Map<String, Object> c = Json.map(ServerToolsUi.ask("GET", "/admin/constellation", null).get("lead"));
					String nm = Json.str(c.get("name"));
					boolean member = ServerTools.yes(c.get("member"));
					SwingUtilities.invokeLater(() -> { if (member) s2.setText("<html><div style='width:520px'>" + (nm.isEmpty() ? "It" : esc(nm))
							+ " has let this one in. Waiting for it to let this one manage it…</div></html>"); });
				}
			}
		});
		tick[0] = new Timer(2000, e -> look.run());
		tick[0].start();

		wayIn.addActionListener(e -> { watching[0] = "ask"; cards.show(deck, "1"); d.pack(); look.run(); });
		wayOut.addActionListener(e -> { cards.show(deck, "3"); d.pack(); theirs.requestFocusInWindow(); });
		letIn.addActionListener(e -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/constellation", m("allow", who[0], "value", true));
			SwingUtilities.invokeLater(() -> {
				if (!Json.str(r.get("result")).equals("ok") && !Json.str(r.get("result")).isEmpty()) { s1.setText(esc(Json.str(r.get("message")))); s1.setForeground(warn); return; }
				watching[0] = "manage"; toManage(p2say, who[1], true); cards.show(deck, "2"); d.pack();
			});
		}));
		join.addActionListener(e -> {
			String a = theirs.getText().trim();
			if (a.isEmpty()) { s3.setText("Type the other Gatekeeper's address first."); s3.setForeground(warn); return; }
			join.setEnabled(false); s3.setText("Joining…"); s3.setForeground(dim);
			ServerToolsUi.later(() -> {
				Map<String, Object> r = ServerToolsUi.ask("POST", "/admin/constellation", m("join", a));
				SwingUtilities.invokeLater(() -> {
					join.setEnabled(true);
					if (Json.str(r.get("result")).equals("refused") || Json.str(r.get("result")).equals("failed")) { s3.setText("<html><div style='width:520px'>" + esc(Json.str(r.get("message"))) + "</div></html>"); s3.setForeground(warn); return; }
					watching[0] = "joined"; toManage(p2say, myName, false); cards.show(deck, "2"); d.pack();
				});
			});
		});

		d.pack();
		d.setMinimumSize(new java.awt.Dimension(600, 330));
		d.setLocationRelativeTo(over);
		if (System.getenv("OJ_PANEL_PNG") != null) { shots(d, deck, cards, p2say, myName); finish.run(); return; }
		d.setVisible(true);
	}
	/** Step 3's words: what to do at the other machine. otherJoined: the other one joined this one (way 1). */
	private static void toManage(JLabel say, String name, boolean otherJoined) {
		say.setText("<html><div style='width:520px'>" + (otherJoined
				? "On <b>" + esc(name) + "</b>, in its Gatekeeper: the menu, <b>Constellation</b>, and tick <b>Let that Gatekeeper manage this one</b>. "
						+ "It can be unticked there at any time."
				: "On the other machine, in its Gatekeeper: the menu, <b>Constellation</b>. Click <b>" + esc(name) + "</b> in its list, press <b>Allow in</b>, "
						+ "then <b>Let it manage</b>.") + "</div></html>");
	}
	private static java.awt.Component Box(int h) { return javax.swing.Box.createVerticalStrut(h); }

	/** Testing only (OJ_PANEL_PNG): a picture of each step. */
	private static void shots(JDialog d, JPanel deck, CardLayout cards, JLabel p2say, String myName) {
		String base = System.getenv("OJ_PANEL_PNG");
		toManage(p2say, "Steam Deck", true);
		for (String s : new String[] {"0", "1", "2", "3"}) {
			cards.show(deck, s);
			d.pack(); d.setSize(Math.max(600, d.getWidth()), Math.max(330, d.getHeight()));
			java.awt.Container c = d.getContentPane();
			c.setSize(d.getSize()); c.doLayout(); c.validate();
			layoutAll(c);
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = img.createGraphics(); c.paint(g); g.dispose();
			try { javax.imageio.ImageIO.write(img, "png", new java.io.File(base + "-addgk-" + s + ".png")); } catch (java.io.IOException e) { e.printStackTrace(); }
		}
	}
	private static void layoutAll(java.awt.Container c) { c.doLayout(); for (java.awt.Component k : c.getComponents()) if (k instanceof java.awt.Container cc) layoutAll(cc); }
}
