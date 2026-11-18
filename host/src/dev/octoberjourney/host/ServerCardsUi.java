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
			JLabel h1 = new JLabel(builtin() ? "What runs the servers" : "MCSS on this machine");
			h1.setFont(h1.getFont().deriveFont(Font.BOLD, 14f));
			h1.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
			h1.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			overList.add(h1);
			boolean up = Boolean.TRUE.equals(r.get("mcss"));
			String exe = Json.str(r.get("mcss_exe"));
			Object[] mr = overRow.apply(builtin() ? "Gatekeeper itself, from " + exe : exe.isEmpty()
					? "MCSS (not set up yet: Server setup, on a server's Status tab)" : exe, null);
			String mph0 = Json.str(r.get("mcss_phase"));
			((JLabel) mr[1]).setText((mph0.equals("starting") ? "Starting\u2026" : mph0.equals("closing") ? "Closing\u2026" : up
					? "Running" : "Not running") + (Boolean.TRUE.equals(r.get("has_key")) ? "" : ". Gatekeeper isn't signed in to it yet."));
			((JLabel) mr[1]).setForeground(up ? new Color(0x8BD46E) : new Color(0x8F98A0));
			if (builtin()) { ((JLabel) mr[1]).setText("MC Server Soft: " + noMcssShort()); ((JLabel) mr[1]).setForeground(new Color(0x8F98A0)); }
			overList.add((JPanel) mr[0]);
			JLabel h2 = new JLabel(builtin() || Boolean.TRUE.equals(r.get("native_ok")) ? "Servers" : "Servers in this MCSS");
			h2.setFont(h2.getFont().deriveFont(Font.BOLD, 14f));
			h2.setBorder(BorderFactory.createEmptyBorder(14, 0, 6, 0));
			h2.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			overList.add(h2);
			List<Object> sv = Json.list(r.get("servers"));
			if (sv.isEmpty()) { JLabel none = new JLabel("None yet. Gatekeeper opens Server setup for you; it's also on a server's Status tab."); none.setForeground(new Color(0x8F98A0)); overList.add(none); }
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
				JButton openB = new JButton("Open");
				openB.setToolTipText("This server's page: who may join and what each launcher may do, how it is, its pack" + (nat
						? ", and its console, settings and backups" : ""));
				if (firstEnter[0] == null) firstEnter[0] = openB;
				openB.addActionListener(ev -> openServer.accept(new String[] {id, nm, String.valueOf(nat), ""}));
				JButton manageB = new JButton("Manage\u2026");
				manageB.setEnabled(nat);
				manageB.setToolTipText(nat ? "Its console, settings, game settings, backups and scheduled tasks" : ServerTools.HERE_MCSS);
				manageB.addActionListener(ev -> openServer.accept(new String[] {id, nm, String.valueOf(nat), "manage"}));
				JButton packB = new JButton("Pack\u2026");
				packB.setToolTipText("<html>What players' launchers install for this server: its pack and the mods in it, its pictures for Steam,<br>who the server is for (private, shareable or public), and invites</html>");
				packB.addActionListener(ev -> openServer.accept(new String[] {id, nm, String.valueOf(nat), "pack"}));
				packB.setVisible(!gone);
				dev.gatehouse.look.GameCard card = new dev.gatehouse.look.GameCard(nm, new java.awt.Component[] {packB, manageB, openB});
				String stateText = gone ? "Deleted in MC Server Soft: launchers are no longer shown it" : on ? "Running, " + players
						+ " playing" : ph.equals("starting") ? "Starting\u2026" : ph.equals("stopping") ? "Shutting down\u2026" : "Off";
				card.state.setText(stateText);
				card.state.setForeground(gone ? new Color(0xFF7B72) : on ? new Color(0x8BD46E) : ph.equals("starting")
						|| ph.equals("stopping") ? new Color(0xE9B949) : new Color(0x8F98A0));
				if (farUrl == null) {   // (its picture: the one chosen for it here, or its own server-icon.png)
					String tag = Icons.tag(id), pk = id + "|" + tag;
					if (!tag.isEmpty()) { java.awt.Image img = cardPics.computeIfAbsent(pk,
							k -> { try { byte[] pb = Icons.png(id); java.awt.Image im = pb == null ? null
							: javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pb)); return im == null
							? new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
							: im; } catch (IOException ex) { return new java.awt.image.BufferedImage(1, 1,
							java.awt.image.BufferedImage.TYPE_INT_ARGB); } }); if (img.getWidth(null) > 1) card.picture(img); }
				}
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
				card.facts(facts);
				if (gone) {
					// MCSS has deleted it: said so, and Manage's place is taken by the one thing left to do with it.
					for (java.awt.event.ActionListener al : manageB.getActionListeners()) manageB.removeActionListener(al);
					manageB.setText("Remove from Gatekeeper"); manageB.setEnabled(true);
					manageB.setToolTipText("Takes it off Gatekeeper's list. There are no files left to delete.");
					manageB.addActionListener(ev -> {
						if (JOptionPane.showConfirmDialog(f, "Remove " + nm + " from Gatekeeper? MCSS has already deleted it.",
								"Remove from Gatekeeper", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
						new Thread(() -> {
							String msg;
							try { msg = Json.str(Json.map(Json.parse(call("POST", "/admin/tools/delete?server=" + id,
									"{\"files\":false}"))).get("message")); } catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
							String m2 = msg;
							try { Map<String, Object> again = Json.map(Json.parse(call("GET", "/admin/overview",
									null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + m2.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"); }); } catch (Exception ignored) { Quiet.seen(ignored); }
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
							null))); SwingUtilities.invokeLater(() -> { if (!inServer[0]) fillOverviewRef[0].accept(again); JOptionPane.showMessageDialog(f, "<html><div style='width:380px'>" + m2 + "</div></html>", "Servers run by Gatekeeper", JOptionPane.PLAIN_MESSAGE); }); } catch (Exception ignored) { Quiet.seen(ignored); }
				}, "native").start());
				setupLine.add(own);
				if (!natOk) { JLabel inMcss = new JLabel("Until then, New server and Manage are done in MC Server Soft"); inMcss.setForeground(new Color(0x8F98A0)); setupLine.add(inMcss); }
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
			if (up && JOptionPane.showConfirmDialog(f,
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
}
