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
 * The Host setup page in Gatekeeper's window: the steps this machine needs to serve players, each with how it stands, this Gatekeeper's name, and the invite link. It looks again by itself every few seconds while it is open.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class HostSetupUi {
	private HostSetupUi() {}

	static void build(JFrame f, javax.swing.JTabbedPane firstTabs, javax.swing.JComponent machineRows) {
		// Host setup: the steps this machine needs to serve players, each with how it stands, and the invite link.
		JPanel hostSetup = new JPanel(new BorderLayout(0, 8));
		hostSetup.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
		JPanel stepList = new JPanel();
		stepList.setLayout(new javax.swing.BoxLayout(stepList, javax.swing.BoxLayout.Y_AXIS));
		JPanel stepTop = new JPanel(new BorderLayout()); stepTop.add(stepList, BorderLayout.NORTH);
		JScrollPane stepScroll = new JScrollPane(stepTop); stepScroll.setBorder(null); stepScroll.getVerticalScrollBar().setUnitIncrement(16);
		javax.swing.JTextField shareF = new javax.swing.JTextField(40);
		JButton shareSave = new JButton("Save"), recheck = new JButton("Check again");
		JLabel shareSaid = new JLabel(" ");
		shareSaid.setForeground(new Color(0xA9B5C1));
		JLabel checkedLab = new JLabel(" ");
		checkedLab.setForeground(new Color(0xA9B5C1));
		shareF.setToolTipText("The reusable invite link you made in Tailscale's admin page for sharing this machine. Kept on this PC; it goes out only in pairings you hand over.");
		JPanel shareRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		JLabel shareLab = new JLabel("Tailscale invite link for players:"); shareLab.setFont(shareLab.getFont().deriveFont(Font.BOLD));
		shareRow.add(shareLab); shareRow.add(shareF); shareRow.add(shareSave); shareRow.add(recheck);
		// This Gatekeeper's name: what launchers list it under (otherwise this machine's own name).
		javax.swing.JTextField nameF = new javax.swing.JTextField(24);
		JButton nameSave = new JButton("Save name");
		nameF.setToolTipText("What connected launchers call this Gatekeeper in their list. Empty: this machine's own name. Launchers that are open pick a change up within a few seconds.");
		JLabel nameLab = new JLabel("Name shown in launchers:"); nameLab.setFont(nameLab.getFont().deriveFont(Font.BOLD));
		JLabel nameHint = new JLabel(" "); nameHint.setForeground(new Color(0xA9B5C1));
		JPanel nameRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		nameRow.add(nameLab); nameRow.add(nameF); nameRow.add(nameSave); nameRow.add(nameHint);
		JPanel fieldRows = new JPanel();
		fieldRows.setLayout(new javax.swing.BoxLayout(fieldRows, javax.swing.BoxLayout.Y_AXIS));
		nameRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); shareRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		// R79: how devices reach this Gatekeeper: Tailscale, the home network, or another private network, and which of
		// this machine's addresses on it. Chosen here, at the machine.
		String[][] nets = {{"tailscale", "Tailscale"}, {"home", "Home network (only devices at home)"}, {"private", "Another private network (ZeroTier, NetBird...)"}};
		javax.swing.JComboBox<String> netBox = new javax.swing.JComboBox<>(new String[] {nets[0][1], nets[1][1], nets[2][1]});
		javax.swing.JComboBox<String> addrBox = new javax.swing.JComboBox<>();
		JButton netSave = new JButton("Save");
		netBox.setToolTipText("<html>Tailscale: a private, encrypted network between this machine and the devices you let in (as before).<br>Home network: devices on the same Wi-Fi or cable as this machine, nothing from outside the home. The web panel stays off in this mode.<br>Another private network: one you run yourself instead of Tailscale (ZeroTier, NetBird, Headscale, Radmin VPN...).<br>Nothing is opened to the internet in any of them.</html>");
		addrBox.setToolTipText("This machine's address on that network, with the network adapter it belongs to");
		JLabel netLab = new JLabel("Devices reach this Gatekeeper over:"); netLab.setFont(netLab.getFont().deriveFont(Font.BOLD));
		JPanel netRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		netRow.add(netLab); netRow.add(netBox); netRow.add(addrBox); netRow.add(netSave);
		netRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		// R79 stage 2: HTTPS with this Gatekeeper's own certificate, and its fingerprint (what pairings carry)
		javax.swing.JCheckBox httpsBox = new javax.swing.JCheckBox("Use HTTPS (this Gatekeeper's own certificate)");
		httpsBox.setToolTipText("<html>Launchers and other Gatekeepers then talk to it encrypted, on any network, and check its certificate by the fingerprint in the pairing.<br>Launchers need the new pairing after turning it on or off, and launchers older than 3.50.0 can't use it.<br>On a home network it lets the web panel be turned on.</html>");
		JButton renewB = new JButton("New certificate\u2026");
		renewB.setToolTipText("Makes a new certificate (for example if this machine's files were copied somewhere). Every launcher needs the new pairing afterwards.");
		JLabel fpLab = new JLabel(" "); fpLab.setForeground(new Color(0xA9B5C1));
		JPanel httpsRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		httpsRow.add(httpsBox); httpsRow.add(renewB); httpsRow.add(fpLab);
		httpsRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		boolean[] httpsFilling = {false};
		java.util.List<Object> netAddrs = new java.util.ArrayList<>();
		boolean[] netFilling = {false};
		Runnable netFill = () -> {
			netFilling[0] = true;
			String mode = nets[Math.max(0, netBox.getSelectedIndex())][0], keep = addrBox.getSelectedItem() == null ? "" : String.valueOf(addrBox.getSelectedItem());
			addrBox.removeAllItems();
			for (Object o : netAddrs) {
				Map<String, Object> a = Json.map(o);
				if (!Net.fits(mode, Json.str(a.get("kind")))) continue;
				addrBox.addItem(Json.str(a.get("address")) + "  (" + Json.str(a.get("adapter")) + ")");
			}
			if (addrBox.getItemCount() == 0) addrBox.addItem(mode.equals("tailscale") ? "(no Tailscale address: is Tailscale connected?)" : "(this machine has no address on such a network)");
			for (int i = 0; i < addrBox.getItemCount(); i++) if (addrBox.getItemAt(i).equals(keep)) addrBox.setSelectedIndex(i);
			shareRow.setVisible(mode.equals("tailscale"));   // (the Tailscale invite link only matters on Tailscale)
			netFilling[0] = false;
		};
		Runnable netLoad = () -> new Thread(() -> {
			try {
				Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/network", null)));
				SwingUtilities.invokeLater(() -> {
					netAddrs.clear(); netAddrs.addAll(Json.list(r.get("addresses")));
					String mode = Json.str(r.get("network")), bind = Json.str(r.get("bind"));
					netFilling[0] = true;
					for (int i = 0; i < nets.length; i++) if (nets[i][0].equals(mode)) netBox.setSelectedIndex(i);
					netFilling[0] = false;
					netFill.run();
					for (int i = 0; i < addrBox.getItemCount(); i++) if (addrBox.getItemAt(i).startsWith(bind + " ")) addrBox.setSelectedIndex(i);
					httpsFilling[0] = true; httpsBox.setSelected(Boolean.TRUE.equals(r.get("https"))); httpsFilling[0] = false;
					String fpNow = Json.str(r.get("fingerprint"));
					renewB.setVisible(!fpNow.isEmpty());
					fpLab.setText(fpNow.isEmpty() ? "" : "Fingerprint " + fpNow.substring(0, 16) + "\u2026");
					fpLab.setToolTipText(fpNow.isEmpty() ? null : "Its whole fingerprint: " + fpNow);
				});
			} catch (Exception ignored) { Quiet.seen(ignored); }
		}, "network").start();
		netBox.addActionListener(e -> { if (!netFilling[0]) netFill.run(); });
		netSave.addActionListener(e -> {
			String mode = nets[Math.max(0, netBox.getSelectedIndex())][0], pick = addrBox.getSelectedItem() == null ? "" : String.valueOf(addrBox.getSelectedItem()).replaceAll("\\s.*$", "");
			new Thread(() -> {
				String said;
				try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/network", Json.write(m("network", mode, "bind", pick))))).get("message")); }
				catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
				String msg = said;
				SwingUtilities.invokeLater(() -> { shareSaid.setText("<html><div style='width:860px'>" + msg.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"); netLoad.run(); });   // (the steps look again by themselves)
			}, "network-save").start();
		});
		java.util.function.Consumer<Map<String, Object>> httpsPost = body -> new Thread(() -> {
			String said;
			try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/network", Json.write(body)))).get("message")); }
			catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
			String msg = said;
			SwingUtilities.invokeLater(() -> { shareSaid.setText("<html><div style='width:860px'>" + msg.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>"); netLoad.run(); });
		}, "https").start();
		httpsBox.addActionListener(e -> { if (!httpsFilling[0]) httpsPost.accept(m("https", httpsBox.isSelected())); });
		renewB.addActionListener(e -> { if (JOptionPane.showConfirmDialog(f, "<html><div style='width:420px'>Make a new certificate? Every launcher and Gatekeeper that knows this one needs the new pairing afterwards.</div></html>",
				"New certificate", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) httpsPost.accept(m("renew", true)); });
		netLoad.run();
		fieldRows.add(netRow); fieldRows.add(httpsRow); fieldRows.add(javax.swing.Box.createVerticalStrut(4));
		fieldRows.add(nameRow); fieldRows.add(javax.swing.Box.createVerticalStrut(4)); fieldRows.add(shareRow);
		JPanel shareBox = new JPanel(new BorderLayout(0, 2));
		shareBox.add(checkedLab, BorderLayout.NORTH);
		shareBox.add(fieldRows, BorderLayout.CENTER);
		shareBox.add(shareSaid, BorderLayout.SOUTH);
		hostSetup.add(stepScroll, BorderLayout.CENTER); hostSetup.add(shareBox, BorderLayout.SOUTH);
		// Server setup and Share pairing live here, beside the steps that ask for them (R38: they used to be only on a
		// server's Server Status tab, which the steps called "the Servers tab").
		Runnable serverSetup = ServerSetupUi.dialog(f);
		JButton setupB = new JButton("Server setup…"), pairB = new JButton("Share pairing…");
		JLabel pairNote = new JLabel(" "); pairNote.setForeground(new Color(0xA9B5C1));
		setupB.addActionListener(e -> serverSetup.run());   // (the steps look again by themselves within 5 seconds)
		PairingUi.wire(pairB, pairNote, f);
		JPanel toolRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		toolRow.add(setupB); toolRow.add(pairB); toolRow.add(pairNote);
		// (with Gatekeeper's and MCSS's own lines above the steps: Start MCSS, signing in to it, its key; R38)
		JPanel hostTop = new JPanel();
		hostTop.setLayout(new javax.swing.BoxLayout(hostTop, javax.swing.BoxLayout.Y_AXIS));
		toolRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); machineRows.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		machineRows.setBorder(BorderFactory.createEmptyBorder(8, 6, 6, 0));
		hostTop.add(toolRow); hostTop.add(machineRows);
		hostSetup.add(hostTop, BorderLayout.NORTH);
		// The list looks again by itself every few seconds while this tab is open, so a change made elsewhere (in
		// MCSS, in Tailscale, in a file) shows here without pressing anything. The line at the bottom says when it
		// last looked; a step that has just turned to Done says so for a minute, and the list is only redrawn when
		// something changed.
		Runnable[] stepReload = {() -> {}};
		Map<String, String> stepWas = new LinkedHashMap<>();
		Map<String, Long> stepDoneAt = new LinkedHashMap<>();
		String[] drawn = {""};
		java.util.function.Consumer<Map<String, Object>> fillSteps = r -> {
			int todo = 0, warn = 0;
			long nowMs = System.currentTimeMillis();
			StringBuilder sig = new StringBuilder();
			for (Object o : Json.list(r.get("steps"))) {
				Map<String, Object> s = Json.map(o);
				String title = Json.str(s.get("title")), st = Json.str(s.get("state")), was = stepWas.get(title);
				if (was != null && !was.equals("ok") && st.equals("ok")) stepDoneAt.put(title, nowMs);
				if (!st.equals("ok")) stepDoneAt.remove(title);
				stepWas.put(title, st);
				sig.append(title).append('|').append(st).append('|').append(Json.str(s.get("detail"))).append('|').append(Json.str(s.get("how"))).append('|').append(stepDoneAt.containsKey(title) && nowMs - stepDoneAt.get(title) < 60_000).append('|').append(Json.str(s.get("action"))).append('|').append(Json.str(s.get("action_ask"))).append('\n');
			}
			String checked = Json.str(r.get("checked"));
			boolean same = sig.toString().equals(drawn[0]);
			drawn[0] = sig.toString();
			if (!same) stepList.removeAll();
			for (Object o : Json.list(r.get("steps"))) {
				Map<String, Object> s = Json.map(o);
				String st = Json.str(s.get("state"));
				if (st.equals("todo")) todo++; else if (st.equals("warn")) warn++;
				if (same) continue;
				boolean na = st.equals("na");
				boolean justDone = stepDoneAt.containsKey(Json.str(s.get("title"))) && nowMs - stepDoneAt.get(Json.str(s.get("title"))) < 60_000;
				String colour = st.equals("ok") ? "#3FB950" : st.equals("warn") ? "#E3B341" : na ? "#A9B5C1" : "#F85149",
						word = st.equals("ok") ? (justDone ? "<b>Done just now</b>" : "Done") : st.equals("warn") ? "Worth changing" : na ? notSupported() : "To do";
				java.util.function.Function<String, String> esc = t -> t.replace("&", "&amp;").replace("<", "&lt;");
				JLabel row = new JLabel("<html><div style='width:860px'><span style='color:" + colour + "'>\u25CF</span>&nbsp; <b>"
						+ esc.apply(Json.str(s.get("title"))) + "</b> &nbsp;<span style='color:" + colour + "'>" + word + "</span>"
						+ (Json.str(s.get("detail")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;" + esc.apply(Json.str(s.get("detail"))))
						+ (Json.str(s.get("how")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;<span style='color:#A9B5C1'>"
								+ esc.apply(Json.str(s.get("how"))) + "</span>") + "</div></html>");
				row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0)); row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				stepList.add(row);
				// A step Gatekeeper can do itself has a button for it (it asks first, and says what it did).
				String action = Json.str(s.get("action")), actionAsk = Json.str(s.get("action_ask"));
				if (action.equals("server_setup") || action.equals("share_pairing")) {
					JButton does = new JButton(Json.str(s.get("action_label")));
					does.addActionListener(ev -> (action.equals("server_setup") ? setupB : pairB).doClick(0));
					JPanel br = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 22, 0));
					br.add(does); br.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
					stepList.add(br);
				}
				if (action.equals("whitelist_on")) {
					JButton does = new JButton(Json.str(s.get("action_label")));
					does.addActionListener(ev -> {
						if (JOptionPane.showConfirmDialog(f, "<html><div style='width:440px'>" + actionAsk.replace("&",
								"&amp;").replace("<", "&lt;") + "</div></html>", "Host setup", JOptionPane.OK_CANCEL_OPTION,
								JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
						does.setEnabled(false);
						new Thread(() -> {
							String said;
							try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/whitelist/on",
									"{}"))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
							String msg = said;
							SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); stepReload[0].run(); });
						}, "whitelist-on").start();
					});
					JPanel br = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 22, 0));
					br.add(does); br.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
					stepList.add(br);
				}
			}
			if (!shareF.isFocusOwner()) shareF.setText(Json.str(r.get("share_link")));
			if (!nameF.isFocusOwner()) nameF.setText(Json.str(r.get("name")));
			nameHint.setText(Json.str(r.get("name")).isEmpty() && !Json.str(r.get("machine")).isEmpty() ? "(empty: listed as \""
					+ Json.str(r.get("machine")) + "\")" : " ");
			firstTabs.setTitleAt(3, todo > 0 ? "Host setup (" + todo + " to do)" : "Host setup");
			checkedLab.setText((todo + warn == 0 ? "Everything is done. " : (todo > 0 ? todo + " to do" : "") + (todo > 0 && warn > 0
					? ", " : "") + (warn > 0 ? warn + " worth changing" : "") + ". ")
					+ (checked.isEmpty() ? "" : "Last looked at " + checked + "; ") + "it looks again every 5 seconds while this tab is open.");
			if (!same) { stepList.revalidate(); stepList.repaint(); }
		};
		Runnable loadSteps = () -> new Thread(() -> {
			try { Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/checklist", null))); SwingUtilities.invokeLater(() -> fillSteps.accept(r)); }
			catch (Exception ex) { SwingUtilities.invokeLater(() -> shareSaid.setText("Couldn't ask Gatekeeper: is it running?")); }
		}, "checklist").start();
		stepReload[0] = loadSteps;
		recheck.addActionListener(ev -> { shareSaid.setText("Checking\u2026"); loadSteps.run(); shareSaid.setText(" "); });
		shareSave.addActionListener(ev -> new Thread(() -> {
			String said;
			try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/checklist", Json.write(m("share_link",
					shareF.getText().trim()))))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
			String msg = said;
			SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); f.getRootPane().requestFocusInWindow(); loadSteps.run(); });
		}, "share-link").start());
		nameSave.addActionListener(ev -> new Thread(() -> {
			String said;
			try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/checklist", Json.write(m("name",
					nameF.getText().trim()))))).get("message")); } catch (Exception ex) { said = "Couldn't ask Gatekeeper: is it running?"; }
			String msg = said;
			SwingUtilities.invokeLater(() -> { shareSaid.setText(msg); f.getRootPane().requestFocusInWindow(); loadSteps.run(); });
		}, "name").start());
		firstTabs.addTab("Host setup", hostSetup);
		firstTabs.addTab("Web panel", scrolled(PanelUi.tab(f)));
		firstTabs.addTab("Constellation", scrolled(ConstellationUi.tab(f)));
		firstTabs.addTab("Chat", ChatUi.tab(f));   // (index 6: a page of the People section)
		firstTabs.addChangeListener(ev -> { if (firstTabs.getSelectedIndex() == 3) loadSteps.run(); });
		new javax.swing.Timer(5000,
				ev -> { if (!f.isDisplayable()) ((javax.swing.Timer) ev.getSource()).stop(); else if (firstTabs.getSelectedIndex() == 3
				&& hostSetup.isShowing()) loadSteps.run(); }).start();
		if (System.getenv("OJ_PANEL_PNG") == null) loadSteps.run();   // (so the tab's title says what's left to do)
		else try { fillSteps.accept(Json.map(Json.parse(call("GET", "/admin/checklist", null)))); } catch (Exception ignored) { Quiet.seen(ignored); }
	}
}
