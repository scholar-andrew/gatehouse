package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import dev.gatehouse.look.Quiet;

/**
 * The servers on Gatekeeper's first screen, as cards like the launcher's: each with its picture, whether it is on and who is playing, and Open, Pack and Manage; the panel beside them says more about the one in focus (its performance too).
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class ServerCardsUi {
	private ServerCardsUi() {}

	/** Returns what fills the first screen from Gatekeeper's /admin/overview answer (it is rebuilt only when that changed). */
	static java.util.function.Consumer<Map<String, Object>> build(
			JFrame f,
			// the first screen: its list, the first thing Enter goes to, and how a row is made
			JPanel overList, JButton[] firstEnter, java.util.function.BiFunction<String, String, Object[]> overRow,
			// going into a server's page and back: the window's levels, its heading, Back, and whether a server is open
			java.util.function.Consumer<String[]> openServer, JPanel stack, java.awt.CardLayout levels, JLabel head, JButton backB,
			boolean[] inServer,
			// which server the server page is about, and its Start/Stop
			javax.swing.JComboBox<String> serverPick, List<String> serverIds, JButton serverRun, boolean[] serverUpNow, JLabel serverState,
			// Server setup, and looking again
			Runnable setup, Runnable refresh) {
		// The servers as cards, like the launcher's: each with its picture, whether it's on and who is playing, and
		// Open, Pack and Manage; the panel beside them says more about the one the selection or the mouse is on.
		JPanel cardGrid = new JPanel();
		dev.gatehouse.look.GameCard.grid(cardGrid);
		dev.gatehouse.look.GameCard.Side cardSide = new dev.gatehouse.look.GameCard.Side();
		{
			java.util.function.Consumer<dev.gatehouse.look.GameCard> before = dev.gatehouse.look.GameCard.onHot;   // (the launcher's, when this window is shown inside it)
			dev.gatehouse.look.GameCard.onHot = card -> { if (SwingUtilities.isDescendingFrom(card,
					cardGrid)) cardSide.show(card); else if (before != null) before.accept(card); };
		}
		// (R25: the panel beside the cards is given room to say everything, however few servers there are)
		JPanel cardsBoth = new JPanel(new BorderLayout(14, 0)) {
			@Override public java.awt.Dimension getPreferredSize() { java.awt.Dimension d = super.getPreferredSize(); return new java.awt.Dimension(d.width, Math.max(d.height, 400)); }
			@Override public java.awt.Dimension getMaximumSize() { return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
		};
		cardsBoth.setOpaque(false);
		cardsBoth.add(cardGrid, BorderLayout.CENTER); cardsBoth.add(cardSide, BorderLayout.EAST);
		cardsBoth.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		Map<String, java.awt.Image> cardPics = new java.util.concurrent.ConcurrentHashMap<>();
		@SuppressWarnings("unchecked") java.util.function.Consumer<Map<String, Object>>[] fillOverviewRef = new java.util.function.Consumer[1];
		String[] overShown = {""};
		java.util.function.Consumer<Map<String, Object>> fillOverview = r -> {
			// (rebuilt only when something on it changed, so a button isn't replaced under the mouse mid-click)
			String overSig = Json.write(r);
			if (overSig.equals(overShown[0]) && overList.getComponentCount() > 0) return;
			overShown[0] = overSig;
			overList.removeAll();
			firstEnter[0] = null;
			// What runs the servers, in one line (R38: it used to lead the page with MCSS's full path, now in its tip)
			boolean up = Boolean.TRUE.equals(r.get("mcss"));
			String exe = Json.str(r.get("mcss_exe")), mph0 = Json.str(r.get("mcss_phase"));
			JLabel mLine = new JLabel(builtin() ? "\u25CF  Gatekeeper runs the servers itself" : exe.isEmpty()
					? "\u25CF  MCSS isn't set up yet: Server setup, in Host setup (menu)" : "\u25CF  MCSS: " + (mph0.equals("starting")
					? "starting\u2026" : mph0.equals("closing") ? "closing\u2026" : up ? "running" : "not running") + (Boolean.TRUE.equals(
					r.get("has_key")) ? "" : ". Gatekeeper isn't signed in to it yet (Host setup, in the menu)."));
			mLine.setForeground(builtin() || !up ? new Color(0xA9B5C1) : new Color(0x8BD46E));
			mLine.setToolTipText(exe.isEmpty() ? null : exe);
			mLine.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			overList.add(mLine);
			JLabel h2 = new JLabel(builtin() || Boolean.TRUE.equals(r.get("native_ok")) ? "Servers" : "Servers in this MCSS");
			h2.setFont(h2.getFont().deriveFont(Font.BOLD, dev.gatehouse.look.SteamLook.pt(14f)));
			h2.setBorder(BorderFactory.createEmptyBorder(12, 0, 6, 0));
			h2.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			overList.add(h2);
			List<Object> sv = Json.list(r.get("servers"));
			if (sv.isEmpty()) { JLabel none = new JLabel("None yet. Gatekeeper opens Server setup for you; it's also in Host setup, in the menu."); none.setForeground(new Color(0xA9B5C1)); overList.add(none); }
			String hotWas = cardSide.showing() == null ? "" : cardSide.showing().name.getText();
			cardGrid.removeAll(); cardSide.clear();
			dev.gatehouse.look.GameCard firstCard = null;
			for (Object o : sv) {
				Map<String, Object> s = Json.map(o);
				boolean on = Boolean.TRUE.equals(s.get("running")), gone = Boolean.TRUE.equals(s.get("gone"));
				String players = Json.str(s.get("players")).replaceAll("\\.0$", "");
				String ph = Json.str(s.get("phase"));
				boolean nat = Boolean.TRUE.equals(s.get("native"));
				String id = Json.str(s.get("id")), nm = Json.str(s.get("name"));
				JButton openB = dev.gatehouse.look.SteamLook.main(new JButton("Open"));
				openB.setToolTipText("This server's page: how it is, its pack" + (nat ? ", and its console, settings, backups and tasks" : ""));
				if (firstEnter[0] == null) firstEnter[0] = openB;
				openB.addActionListener(ev -> openServer.accept(new String[] {id, nm, String.valueOf(nat), ""}));
				// (R38: Start or Stop on the card itself; Pack and Manage are tabs on the server's page, which Open shows)
				boolean busy = ph.equals("starting") || ph.equals("stopping");
				JButton manageB = new JButton(on ? "Stop" : "Start");
				manageB.setEnabled(!busy);
				manageB.setToolTipText(on ? "Stops this server: anyone playing is disconnected (the world is saved first)" : "Starts this server");
				manageB.addActionListener(ev -> {
					if (on && dev.gatehouse.look.InWindow.showConfirmDialog(f, "Stop " + nm + "? Anyone playing is disconnected (the world is saved first).",
							"Minecraft server", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
					manageB.setEnabled(false);
					new Thread(() -> {
						Map<String, Object> did;
						try { did = Json.map(Json.parse(call("POST", (on ? "/admin/server/stop" : "/admin/server/start") + "?server=" + id, "{}"))); }
						catch (Exception ex) { did = Map.of("message", "Couldn't ask Gatekeeper: is it running?"); }
						String said = Json.str(did.get("message")), result = Json.str(did.get("result"));
						if (!result.equals("ok") && !said.isEmpty()) SwingUtilities.invokeLater(() -> dev.gatehouse.look.InWindow.showMessageDialog(f,
								"<html><div style='width:380px'>" + said.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>", nm, JOptionPane.PLAIN_MESSAGE));
					}, "card-run").start();
				});
				dev.gatehouse.look.GameCard card = new dev.gatehouse.look.GameCard(nm, new java.awt.Component[] {manageB, openB});
				String stateText = gone ? "Deleted in MC Server Soft: launchers are no longer shown it" : on ? "Running, " + players
						+ " playing" : ph.equals("starting") ? "Starting\u2026" : ph.equals("stopping") ? "Shutting down\u2026" : "Off";
				card.state.setText(stateText);
				card.state.setForeground(gone ? new Color(0xFF7B72) : on ? new Color(0x8BD46E) : ph.equals("starting")
						|| ph.equals("stopping") ? new Color(0xE9B949) : new Color(0xA9B5C1));
				if (farUrl == null) {   // (its picture: the one chosen for it here, or its own server-icon.png)
					String tag = Icons.tag(id), pk = id + "|" + tag;
					if (!tag.isEmpty()) { java.awt.Image img = cardPics.computeIfAbsent(pk,
							k -> { try { byte[] pb = Icons.png(id); java.awt.Image im = pb == null ? null
							: javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pb)); return im == null
							? new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
							: im; } catch (IOException ex) { return new java.awt.image.BufferedImage(1, 1,
							java.awt.image.BufferedImage.TYPE_INT_ARGB); } }); if (img.getWidth(null) > 1) card.picture(img);
						// (R75: an animated one plays while the card is lit)
						java.awt.Image anim = cardPics.computeIfAbsent(pk + "|gif", k -> { byte[] gb = Icons.gif(id); return gb == null
								? new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB) : java.awt.Toolkit.getDefaultToolkit().createImage(gb); });
						if (!(anim instanceof java.awt.image.BufferedImage)) card.moving(anim); }
				}
				List<String[]> facts = facts(r, s);   // (see facts(), below)
				card.facts(facts);
				if (gone) {
					// MCSS has deleted it: said so, and Manage's place is taken by the one thing left to do with it.
					for (java.awt.event.ActionListener al : manageB.getActionListeners()) manageB.removeActionListener(al);
					manageB.setText("Remove from Gatekeeper"); manageB.setEnabled(true); dev.gatehouse.look.SteamLook.danger(manageB);
					manageB.setToolTipText("Takes it off Gatekeeper's list. There are no files left to delete.");
					manageB.addActionListener(ev -> {
						if (dev.gatehouse.look.InWindow.showConfirmDialog(f, "Remove " + nm + " from Gatekeeper? MCSS has already deleted it.",
								"Remove from Gatekeeper", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
						new Thread(() -> {
							String msg;
							try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/tools/delete?server=" + id,
									"{\"files\":false}"))).get("message")); } catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
							String m2 = msg;
							try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview",
									null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); dev.gatehouse.look.InWindow.showMessageDialog(f, "<html><div style='width:380px'>" + m2.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"); }); } catch (Exception ignored) { Quiet.seen(ignored); }
						}, "remove-gone").start();
					});
				}
				cardGrid.add(card);
				if (firstCard == null || nm.equals(hotWas)) firstCard = card;
			}
			if (!sv.isEmpty()) overList.add(cardsBoth);
			if (firstCard != null) cardSide.show(firstCard);
			cardGrid.revalidate(); cardGrid.repaint();
			JButton setupHere = new JButton("Server setup…");
			setupHere.setToolTipText("Which MCSS and which of its servers Gatekeeper looks after, and this machine's Tailscale address");
			setupHere.addActionListener(ev -> setup.run());
			JButton newHere = new JButton("New server\u2026");
			boolean natOk = Boolean.TRUE.equals(r.get("native_ok"));
			newHere.setEnabled(natOk);
			newHere.setToolTipText(natOk ? "Makes a Minecraft server from nothing: Gatekeeper fetches its program and sets it up" : ServerTools.HERE_MCSS);
			newHere.addActionListener(ev -> ServerToolsUi.newServer(f, () -> new Thread(() -> { try { Map<String,
					Object> again = Json.map(Json.parse(call("GET", "/admin/overview",
					null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); }); } catch (Exception ignored) { Quiet.seen(ignored); } }, "overview").start()));
			JPanel setupLine = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 8));
			setupLine.add(newHere);
			setupLine.add(setupHere);
			setupLine.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			if (Json.str(r.get("host")).equals("mcss")) {
				// Windows with MCSS: Gatekeeper may run servers of its own as well, if the owner says so.
				javax.swing.JCheckBox own = new javax.swing.JCheckBox("Gatekeeper may also run servers itself, beside MCSS's", natOk);
				own.setToolTipText("New server then makes servers in " + Json.str(r.get("native_folder"))
						+ " that Gatekeeper runs itself, with Manage for each. MCSS's own servers stay MCSS's.");
				own.addActionListener(ev -> new Thread(() -> {
					String msg;
					try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/tools/native", Json.write(m("on",
							own.isSelected()))))).get("message")); } catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
					String m2 = msg;
					try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview",
							null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); dev.gatehouse.look.InWindow.showMessageDialog(f, "<html><div style='width:380px'>" + m2 + "</div></html>", "Servers run by Gatekeeper", JOptionPane.PLAIN_MESSAGE); }); } catch (Exception ignored) { Quiet.seen(ignored); }
				}, "native").start());
				setupLine.add(own);
				if (!natOk) { JLabel inMcss = new JLabel("Until then, New server and Manage are done in MC Server Soft"); inMcss.setForeground(new Color(0xA9B5C1)); setupLine.add(inMcss); }
			}
			overList.add(setupLine);
			overList.revalidate(); overList.repaint();
		};
		fillOverviewRef[0] = fillOverview;
		Runnable loadOverview = () -> new Thread(() -> {
			try { Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/overview",
					null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverview.accept(r); }); } catch (Exception ignored) { Quiet.seen(ignored); }
		}, "overview").start();
		backB.addActionListener(e -> { inServer[0] = false; head.setText("Gatekeeper"); backB.setVisible(false); levels.show(stack,
				"overview"); loadOverview.run(); });
		new Timer(3000, e -> { if (!inServer[0]) loadOverview.run(); }).start();
		loadOverview.run();
		serverPick.addActionListener(e -> {
			int i = serverPick.getSelectedIndex();
			String id = i >= 0 && i < serverIds.size() ? serverIds.get(i) : "";
			if (!id.equals(panelServer) && serverIds.size() > 1) { panelServer = id; refresh.run(); }
		});
		serverRun.addActionListener(e -> {
			boolean up = serverUpNow[0];
			if (up && dev.gatehouse.look.InWindow.showConfirmDialog(f,
					"Stop the Minecraft server? Anyone playing is disconnected (the world is saved first).", "Minecraft server",
					JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
			new Thread(() -> {
				String msg;
				try { msg = Json.str(Json.map(Json.parse(call("POST", up ? "/admin/server/stop" : "/admin/server/start",
						"{}"))).get("message")); } catch (IOException ex) { msg = "Gatekeeper isn't running."; }
				String m2 = msg;
				SwingUtilities.invokeLater(() -> serverState.setText(m2));
				try { Thread.sleep(4000); } catch (InterruptedException ignored) { Quiet.seen(ignored); }
				refresh.run();
			}, "server-run").start();
		});
		return fillOverview;
	}

	/** What the panel beside the server cards says about one server (and its Overview tab on its own page): whether it
	 *  is on, who is playing, its port, what runs it, its folder, and how it is doing. r is /admin/overview, s one of
	 *  its servers. */
	static List<String[]> facts(Map<String, Object> r, Map<String, Object> s) {
		boolean on = Boolean.TRUE.equals(s.get("running")), gone = Boolean.TRUE.equals(s.get("gone")), nat = Boolean.TRUE.equals(s.get("native"));
		String players = Json.str(s.get("players")).replaceAll("\\.0$", ""), ph = Json.str(s.get("phase"));
		List<String[]> facts = new ArrayList<>();
		facts.add(new String[] {"Now", gone ? "Deleted in MC Server Soft" : on ? "Running" : ph.equals("starting") ? "Starting"
				: ph.equals("stopping") ? "Shutting down" : "Off"});
		if (on) facts.add(new String[] {"Playing", players.isEmpty() ? "0" : players});
		facts.add(new String[] {"Port", Json.str(s.get("port"))});
		facts.add(new String[] {"Run by", Json.str(r.get("host")).equals("mcss") ? (nat ? "Gatekeeper itself" : "MC Server Soft") : "Gatekeeper itself"});
		facts.add(new String[] {"Folder", Json.str(s.get("dir"))});
		// (R34: how it is doing, now and over the last hour)
		Map<String, Object> pf = Json.map(s.get("perf"));
		if (on && !pf.isEmpty()) {
			long mem = (long) Double.parseDouble(Json.str(pf.get("mem")).isEmpty() ? "-1" : Json.str(pf.get("mem"))),
					memPeak = (long) Double.parseDouble(Json.str(pf.get("mem_peak")).isEmpty() ? "-1" : Json.str(pf.get("mem_peak")));
			int cpu = (int) Double.parseDouble(Json.str(pf.get("cpu")).isEmpty() ? "-1" : Json.str(pf.get("cpu"))),
					cpuPeak = (int) Double.parseDouble(Json.str(pf.get("cpu_peak")).isEmpty() ? "-1" : Json.str(pf.get("cpu_peak")));
			int lag = (int) Double.parseDouble(Json.str(pf.get("lag")).isEmpty() ? "0" : Json.str(pf.get("lag"))),
					mins = (int) Double.parseDouble(Json.str(pf.get("minutes")).isEmpty() ? "0" : Json.str(pf.get("minutes")));
			java.util.function.LongFunction<String> gb = n -> n >= 1024 ? String.format(java.util.Locale.ROOT, "%.1f GB", n / 1024.0) : n + " MB";
			if (mem >= 0) facts.add(new String[] {"Memory", gb.apply(mem) + (memPeak > mem ? " (up to " + gb.apply(memPeak)
					+ " in the last " + (mins >= 60 ? "hour" : mins + " min") + ")" : "")});
			if (cpu >= 0) facts.add(new String[] {"Processor", cpu + "%" + (cpuPeak > cpu ? " (up to " + cpuPeak + "%)" : "")});
			facts.add(new String[] {"Keeping up", lag == 0 ? "Yes: no lag warnings in the last " + (mins >= 60 ? "hour" : mins
					+ " min") : lag + " \"can't keep up\" warning" + (lag == 1 ? "" : "s") + " in the last " + (mins >= 60 ? "hour" : mins + " min")});
		}
		return facts;
	}
}
