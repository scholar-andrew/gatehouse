package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.gatehouse.look.Quiet;

/**
 * R74: the Gatekeepers page (menu, Constellation): this Gatekeeper and every other one it may manage, as cards like
 * Gatehouse's list of Gatekeepers. Open on another one opens a window on it (Manage); on this one, its Servers.
 * Which ones are listed is decided by their owners, on their own Constellation pages (see Manage).
 */
final class GatekeepersUi {
	private GatekeepersUi() {}

	/** The page. toServers: shows this Gatekeeper's own Servers. */
	static JPanel page(Runnable toServers) {
		JPanel p = new JPanel(new BorderLayout(0, 10));
		p.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 6));
		JLabel sub = new JLabel("<html><div style='width:600px'>This Gatekeeper, and the other Gatekeepers it may manage. Open shows one's servers, "
				+ "players and chat in a window of its own. A Gatekeeper is listed here once its owner lets this one manage it, on that "
				+ "Gatekeeper's Constellation page.</div></html>");
		sub.setForeground(new Color(0xA9B5C1));
		JPanel grid = new JPanel();
		dev.gatehouse.look.GameCard.grid(grid);
		JLabel said = new JLabel(" ");
		said.setForeground(new Color(0xA9B5C1));
		JButton again = new JButton("Look again");
		again.setToolTipText("Asks each Gatekeeper again how many servers it has and who is on them");
		JButton add = dev.gatehouse.look.SteamLook.main(new JButton("Add a Gatekeeper…"));   // (R76)
		add.setToolTipText("Walks you through putting another Gatekeeper on this page, a step at a time");
		JPanel topButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)); topButtons.setOpaque(false);
		topButtons.add(add); topButtons.add(again);
		JPanel top = new JPanel(new BorderLayout(10, 0)); top.setOpaque(false);
		top.add(sub, BorderLayout.CENTER); top.add(topButtons, BorderLayout.EAST);
		JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false); south.add(said, BorderLayout.NORTH);
		p.add(top, BorderLayout.NORTH);
		p.add(new javax.swing.JScrollPane(grid) {{ setBorder(BorderFactory.createEmptyBorder()); getVerticalScrollBar().setUnitIncrement(24); }}, BorderLayout.CENTER);
		p.add(south, BorderLayout.SOUTH);
		java.awt.Image[] ownPic = {null}, ownAnim = {null};
		java.util.function.Consumer<Map<String, Object>> show = r -> {
			grid.removeAll();
			if (farUrl != null) {
				said.setText("This window is on another Gatekeeper. Its list of Gatekeepers is in that machine's own window.");
				grid.revalidate(); grid.repaint(); return;
			}
			// this Gatekeeper first
			JButton ownOpen = dev.gatehouse.look.SteamLook.main(new JButton("Open"));
			ownOpen.setToolTipText("This Gatekeeper's servers");
			ownOpen.addActionListener(e -> toServers.run());
			String ownName = Json.str(r.get("name")).isEmpty() ? "This Gatekeeper" : Json.str(r.get("name"));
			dev.gatehouse.look.GameCard own = new dev.gatehouse.look.GameCard(ownName, new java.awt.Component[] {ownOpen});
			own.state.setText("This Gatekeeper"); own.state.setForeground(new Color(0x66C0F4));
			own.device(Json.str(r.get("device")));
			if (ownPic[0] != null) own.picture(ownPic[0]);
			if (ownAnim[0] != null) own.moving(ownAnim[0]);   // (R75)
			grid.add(own);
			List<Object> list = Json.list(r.get("gatekeepers"));
			for (Object o : list) {
				Map<String, Object> g = Json.map(o);
				String name = Json.str(g.get("name")), key = Json.str(g.get("key"));
				boolean answered = ServerTools.yes(g.get("answered"));
				JButton open = dev.gatehouse.look.SteamLook.main(new JButton("Open"));
				open.setToolTipText("Opens a window on " + name + ": its servers, players and chat. What it can't do from here is done at its own machine.");
				open.addActionListener(e -> ServerToolsUi.later(() -> {
					Map<String, Object> res = ServerToolsUi.ask("POST", "/admin/manage/open", key.equals("lead") ? m("lead", true) : m("id", key));
					SwingUtilities.invokeLater(() -> ServerToolsUi.say(said, res));
				}));
				dev.gatehouse.look.GameCard card = new dev.gatehouse.look.GameCard(name, new java.awt.Component[] {open});
				String servers = ServerToolsUi.num(g.get("servers")), online = ServerToolsUi.num(g.get("online"));
				card.state.setText(Json.str(g.get("address")) + ": " + (answered ? servers + (servers.equals("1") ? " server, " : " servers, ")
						+ online + " online" : Json.str(g.get("why"))));
				card.state.setForeground(answered ? new Color(0x8BD46E) : new Color(0xE9B949));
				card.device(Json.str(g.get("device")));   // (R74: what kind of machine it is on, as that Gatekeeper says; its initials when it says nothing)
				grid.add(card);
			}
			said.setText(list.isEmpty() ? (ServerTools.yes(r.get("joined"))
					? "No other Gatekeeper lets this one manage it yet. Add a Gatekeeper walks you through it."
					: "No other Gatekeeper lets this one manage it yet. Add a Gatekeeper walks you through it.") : " ");
			grid.revalidate(); grid.repaint();
		};
		Runnable load = () -> ServerToolsUi.later(() -> {
			Map<String, Object> r = ServerToolsUi.ask("GET", "/admin/manage/list", null);
			if (ownPic[0] == null && farUrl == null) try {
				byte[] b = Icons.png("gatekeeper");
				if (b != null) ownPic[0] = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
				byte[] gb = Icons.gif("gatekeeper");
				if (gb != null) ownAnim[0] = java.awt.Toolkit.getDefaultToolkit().createImage(gb);
			} catch (java.io.IOException | RuntimeException e) { Quiet.seen(e); }
			SwingUtilities.invokeLater(() -> show.accept(r));
		});
		again.addActionListener(e -> load.run());
		add.addActionListener(e -> AddGatekeeperUi.open(p, load));
		add.setVisible(farUrl == null);
		p.putClientProperty("add", (Runnable) () -> AddGatekeeperUi.open(p, null));   // (testing: pictures of its steps)
		p.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && p.isShowing()) load.run(); });
		p.putClientProperty("load", load);
		return p;
	}
}
