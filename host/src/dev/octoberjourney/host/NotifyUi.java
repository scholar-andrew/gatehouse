package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.gatehouse.look.Quiet;

/**
 * Part of Gatekeeper's Options page (R34): phone notifications, the status page for friends, and crash watch. It
 * reads and writes them through Gatekeeper (/admin/notify, /admin/status-page, /admin/notify/keep). Moved out of
 * HostHelper.panel() as it was (R36), so the window's code is in smaller pieces.
 */
final class NotifyUi {
	private NotifyUi() {}

	/** Adds the section to the Options page. optLine adds a line of text (bold: a heading). */
	static void addTo(JPanel options, JFrame f, java.util.function.BiConsumer<String, Boolean> optLine) {
		// (R34) Phone notifications, the status page for friends, and crash watch.
		optLine.accept("Phone notifications", true);
		optLine.accept("Gatekeeper can tell your phone when a friend joins, when a server goes down, and when an update needs you. It uses ntfy (free, no account): install the ntfy app, add the topic below, and press Send a test. Only names and a sentence are sent. The topic works like a password: keep it to yourself.", false);
		javax.swing.JCheckBox ntOn = new javax.swing.JCheckBox("Send notifications to my phone"), ntJoin = new javax.swing.JCheckBox("When a friend joins a server"),
				ntDown = new javax.swing.JCheckBox("When a server goes down"),
						ntUpd = new javax.swing.JCheckBox("When an update needs me (or Gatekeeper restarted itself)"),
						ntChat = new javax.swing.JCheckBox("When someone writes in chat (at most one a minute for each room)");
		JLabel ntTopic = new JLabel(" "); ntTopic.setFont(ntTopic.getFont().deriveFont(Font.BOLD));
		javax.swing.JTextField ntUrl = new javax.swing.JTextField("https://ntfy.sh", 22);
		JButton ntNew = new JButton("New topic"), ntTest = new JButton("Send a test");
		JLabel ntSaid = new JLabel(" "); ntSaid.setForeground(new Color(0xA9B5C1));
		JPanel ntTopicRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
		ntTopicRow.add(new JLabel("Topic:"));
		ntTopicRow.add(ntTopic);
		ntTopicRow.add(ntNew);
		JPanel ntUrlRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));
		ntUrlRow.add(new JLabel("Server:"));
		ntUrlRow.add(ntUrl);
		ntUrlRow.add(ntTest);
		ntUrlRow.add(ntSaid);
		for (javax.swing.JComponent c : new javax.swing.JComponent[] {ntOn, ntJoin, ntDown, ntUpd, ntChat, ntTopicRow,
				ntUrlRow}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); options.add(c); }
		optLine.accept("Status page for friends", true);
		optLine.accept("A read-only page showing which servers are up and who is playing, with no sign-in. Only people on your Tailscale network can open it, as with the rest of Gatekeeper. It shows no addresses, folders or buttons.", false);
		javax.swing.JCheckBox stOn = new javax.swing.JCheckBox("Share the status page");
		JLabel stUrl = new JLabel(" "); stUrl.setForeground(new Color(0xA9B5C1));
		for (javax.swing.JComponent c : new javax.swing.JComponent[] {stOn, stUrl}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); options.add(c); }
		optLine.accept("Crash watch", true);
		optLine.accept("Gatekeeper notices when a server goes down without being asked to, and tells you (above). Tick a server to have it started again too: up to three times in ten minutes. For an MCSS server this needs Gatekeeper's MCSS key; MCSS also has a restart-on-crash setting of its own.", false);
		JPanel keepList = new JPanel();
		keepList.setLayout(new javax.swing.BoxLayout(keepList, javax.swing.BoxLayout.Y_AXIS));
		keepList.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(keepList);
		boolean[] ntFilling = {false};
		Runnable ntRead = () -> new Thread(() -> {
			try {
				Map<String, Object> nt = Json.map(Json.parse(call("GET", "/admin/notify", null))), st = Json.map(Json.parse(call("GET", "/admin/status-page", null)));
				SwingUtilities.invokeLater(() -> {
					ntFilling[0] = true;
					ntOn.setSelected(Boolean.TRUE.equals(nt.get("on")));
					ntJoin.setSelected(!Boolean.FALSE.equals(nt.get("join")));
					ntDown.setSelected(!Boolean.FALSE.equals(nt.get("down")));
					ntUpd.setSelected(!Boolean.FALSE.equals(nt.get("update")));
					ntChat.setSelected(!Boolean.FALSE.equals(nt.get("chat")));
					ntTopic.setText(Json.str(nt.get("topic")).isEmpty() ? "(made when you turn it on)" : Json.str(nt.get("topic")));
					if (!ntUrl.isFocusOwner()) ntUrl.setText(Json.str(nt.get("url")));
					for (javax.swing.JComponent c : new javax.swing.JComponent[] {ntJoin, ntDown, ntUpd, ntChat}) c.setEnabled(ntOn.isSelected());
					stOn.setSelected(Boolean.TRUE.equals(st.get("on")));
					stUrl.setText(Boolean.TRUE.equals(st.get("on")) && !Json.str(st.get("url")).isEmpty() ? "Friends open: " + Json.str(st.get("url")) : " ");
					keepList.removeAll();
					for (Object o : Json.list(nt.get("servers"))) {
						Map<String, Object> sv = Json.map(o);
						javax.swing.JCheckBox k = new javax.swing.JCheckBox("Start " + Json.str(sv.get("name"))
								+ " again when it goes down", Boolean.TRUE.equals(sv.get("keep")));
						k.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
						String sid = Json.str(sv.get("id"));
						k.addActionListener(ev -> new Thread(() -> { try { call("POST", "/admin/notify/keep", Json.write(m("server", sid,
								"on", k.isSelected()))); } catch (Exception ignored) { Quiet.seen(ignored); } }, "keep").start());
						keepList.add(k);
					}
					keepList.revalidate(); keepList.repaint();
					ntFilling[0] = false;
				});
			} catch (Exception ignored) { Quiet.seen(ignored); }
		}, "notify-read").start();
		java.awt.event.ActionListener ntSave = ev -> {
			if (ntFilling[0]) return;
			Map<String, Object> body = m("on", ntOn.isSelected(), "join", ntJoin.isSelected(), "down", ntDown.isSelected(), "update",
					ntUpd.isSelected(), "chat", ntChat.isSelected(), "url", ntUrl.getText().trim());
			new Thread(() -> { try { call("POST", "/admin/notify",
					Json.write(body)); } catch (Exception ignored) { Quiet.seen(ignored); } ntRead.run(); }, "notify-save").start();
		};
		for (javax.swing.JCheckBox c : new javax.swing.JCheckBox[] {ntOn, ntJoin, ntDown, ntUpd, ntChat}) c.addActionListener(ntSave);
		ntUrl.addActionListener(ntSave);
		ntUrl.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusLost(java.awt.event.FocusEvent e) { ntSave.actionPerformed(null); } });
		ntNew.addActionListener(ev -> {
			if (JOptionPane.showConfirmDialog(f,
					"Make a new topic? Your phone stops getting notifications until you add the new topic in the ntfy app.", "New topic",
					JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
			new Thread(() -> { try { call("POST", "/admin/notify/topic",
					"{}"); } catch (Exception ignored) { Quiet.seen(ignored); } ntRead.run(); }, "notify-topic").start();
		});
		ntTest.addActionListener(ev -> {
			ntTest.setEnabled(false); ntSaid.setText("Sending\u2026");
			new Thread(() -> {
				String said;
				try { said = Json.str(Json.map(Json.parse(call("POST", "/admin/notify/test",
						"{}"))).get("message")); } catch (Exception ex) { said = "Gatekeeper didn't answer"; }
				String s2 = said; SwingUtilities.invokeLater(() -> { ntTest.setEnabled(true); ntSaid.setText(s2); }); ntRead.run();
			}, "notify-test").start();
		});
		stOn.addActionListener(ev -> { if (ntFilling[0]) return; boolean want = stOn.isSelected(); new Thread(() -> { try { call("POST",
				"/admin/status-page", Json.write(m("on", want))); } catch (Exception ignored) { Quiet.seen(ignored); } ntRead.run(); }, "status-page").start(); });
		options.addHierarchyListener(ev -> { if ((ev.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && options.isShowing()) ntRead.run(); });
		if (System.getenv("OJ_PANEL_PNG") != null) ntRead.run();
	}
}
