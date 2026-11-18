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

	static void build(JFrame f, javax.swing.JTabbedPane firstTabs) {
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
		shareSaid.setForeground(new Color(0x8F98A0));
		JLabel checkedLab = new JLabel(" ");
		checkedLab.setForeground(new Color(0x8F98A0));
		shareF.setToolTipText("The reusable invite link you made in Tailscale's admin page for sharing this machine. Kept on this PC; it goes out only in pairings you hand over.");
		JPanel shareRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		JLabel shareLab = new JLabel("Tailscale invite link for players:"); shareLab.setFont(shareLab.getFont().deriveFont(Font.BOLD));
		shareRow.add(shareLab); shareRow.add(shareF); shareRow.add(shareSave); shareRow.add(recheck);
		// This Gatekeeper's name: what launchers list it under (otherwise this machine's own name).
		javax.swing.JTextField nameF = new javax.swing.JTextField(24);
		JButton nameSave = new JButton("Save name");
		nameF.setToolTipText("What connected launchers call this Gatekeeper in their list. Empty: this machine's own name. Launchers that are open pick a change up within a few seconds.");
		JLabel nameLab = new JLabel("Name shown in launchers:"); nameLab.setFont(nameLab.getFont().deriveFont(Font.BOLD));
		JLabel nameHint = new JLabel(" "); nameHint.setForeground(new Color(0x8F98A0));
		JPanel nameRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
		nameRow.add(nameLab); nameRow.add(nameF); nameRow.add(nameSave); nameRow.add(nameHint);
		JPanel fieldRows = new JPanel();
		fieldRows.setLayout(new javax.swing.BoxLayout(fieldRows, javax.swing.BoxLayout.Y_AXIS));
		nameRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); shareRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		fieldRows.add(nameRow); fieldRows.add(javax.swing.Box.createVerticalStrut(4)); fieldRows.add(shareRow);
		JPanel shareBox = new JPanel(new BorderLayout(0, 2));
		shareBox.add(checkedLab, BorderLayout.NORTH);
		shareBox.add(fieldRows, BorderLayout.CENTER);
		shareBox.add(shareSaid, BorderLayout.SOUTH);
		hostSetup.add(stepScroll, BorderLayout.CENTER); hostSetup.add(shareBox, BorderLayout.SOUTH);
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
				String colour = st.equals("ok") ? "#3FB950" : st.equals("warn") ? "#E3B341" : na ? "#8F98A0" : "#F85149",
						word = st.equals("ok") ? (justDone ? "<b>Done just now</b>" : "Done") : st.equals("warn") ? "Worth changing" : na ? notSupported() : "To do";
				java.util.function.Function<String, String> esc = t -> t.replace("&", "&amp;").replace("<", "&lt;");
				JLabel row = new JLabel("<html><div style='width:860px'><span style='color:" + colour + "'>\u25CF</span>&nbsp; <b>"
						+ esc.apply(Json.str(s.get("title"))) + "</b> &nbsp;<span style='color:" + colour + "'>" + word + "</span>"
						+ (Json.str(s.get("detail")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;" + esc.apply(Json.str(s.get("detail"))))
						+ (Json.str(s.get("how")).isEmpty() ? "" : "<br>&nbsp;&nbsp;&nbsp;&nbsp;<span style='color:#8F98A0'>"
								+ esc.apply(Json.str(s.get("how"))) + "</span>") + "</div></html>");
				row.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0)); row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
				stepList.add(row);
				// A step Gatekeeper can do itself has a button for it (it asks first, and says what it did).
				String action = Json.str(s.get("action")), actionAsk = Json.str(s.get("action_ask"));
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
