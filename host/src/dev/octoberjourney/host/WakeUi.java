package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import dev.gatehouse.look.Quiet;

/**
 * Host setup, Wake: waking this machine from outside the home (see Wake), and, on a machine that stays awake, being
 * the relay that wakes another one.
 */
final class WakeUi {
	private WakeUi() {}

	private static final Color GREY = new Color(0xA9B5C1);

	static JPanel tab(JFrame f) {
		JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		JLabel said = new JLabel(" "); said.setForeground(GREY);

		// ---- this machine's network card
		JTextField macF = new JTextField(17);
		macF.setToolTipText("The network card that listens for the wake signal (its Physical address). Empty: Gatekeeper finds it by itself.");
		JButton macSave = new JButton("Save");
		JLabel macNote = new JLabel(" "); macNote.setForeground(GREY);
		// ---- the router
		JTextField routerF = new JTextField(26);
		routerF.setToolTipText("Your home's public name and the UDP port your router forwards to the home network, like myhome.duckdns.org:9. Empty: off.");
		JButton routerSave = new JButton("Save");
		// ---- relays for this machine
		JButton codeB = new JButton("Make a relay code…");
		codeB.setToolTipText("For another machine at home that stays awake and runs Gatekeeper: paste the code into its Host setup, Wake.");
		JPanel relayList = new JPanel(); relayList.setLayout(new BoxLayout(relayList, BoxLayout.Y_AXIS));
		// ---- machines this one wakes
		JTextField joinF = new JTextField(34);
		joinF.setToolTipText("A relay code from the other machine's Gatekeeper (Host setup, Wake, Make a relay code)");
		JButton joinB = new JButton("Wake that machine from here");
		JPanel forList = new JPanel(); forList.setLayout(new BoxLayout(forList, BoxLayout.Y_AXIS));

		Runnable[] load = new Runnable[1];
		java.util.function.Consumer<Map<String, Object>> post = body -> new Thread(() -> {
			String msg;
			Map<String, Object> r = Map.of();
			try { r = Json.map(Json.parse(call("POST", "/admin/wake/outside", Json.write(body)))); msg = Json.str(r.get("message")); }
			catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
			String m2 = msg; boolean isCode = body.containsKey("code") && "ok".equals(Json.str(r.get("result")));
			SwingUtilities.invokeLater(() -> {
				if (isCode) {
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(m2), null);
					JTextField show = new JTextField(m2, 44); show.setEditable(false);
					dev.gatehouse.look.InWindow.showMessageDialog(f, new Object[] {"<html><div style='width:440px'>Copied. Paste it into the Gatekeeper of the machine that stays awake "
							+ "(Host setup, Wake, under \"Wake another machine from here\"), while this machine is awake.<br><br>It holds that relay's secret: send it only to yourself.</div></html>", show},
							"Relay code", JOptionPane.PLAIN_MESSAGE);
					said.setText(" ");
				} else said.setText("<html><div style='width:820px'>" + m2.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
				if (body.containsKey("join")) joinF.setText("");
				load[0].run();
			});
		}, "wake-save").start();

		load[0] = () -> new Thread(() -> {
			Map<String, Object> r;
			try { r = Json.map(Json.parse(call("GET", "/admin/wake/outside", null))); } catch (Exception ex) { Quiet.seen(ex); return; }
			SwingUtilities.invokeLater(() -> {
				if (!macF.isFocusOwner()) macF.setText(Json.str(r.get("mac_set")));
				String det = Json.str(r.get("mac_detected"));
				macNote.setText(det.isEmpty() ? "Found none on a home network: fill it in." : "Found by itself: " + det);
				if (!routerF.isFocusOwner()) routerF.setText(Json.str(r.get("router")));
				relayList.removeAll();
				for (Object o : Json.list(r.get("relays"))) {
					Map<String, Object> e = Json.map(o);
					boolean joined = !Json.str(e.get("url")).isEmpty();
					String nm = Json.str(e.get("name"));
					JLabel l = new JLabel(joined ? (nm.isEmpty() ? "relay" : nm) + "  ·  " + Json.str(e.get("url")) + "  ·  since " + dev.gatehouse.look.Times.show(e.get("joined_at"), "")
							: "A code made " + dev.gatehouse.look.Times.show(e.get("made_at"), "") + (nm.isEmpty() ? "" : " for " + nm) + ", not used yet");
					if (!joined) l.setForeground(GREY);
					JButton rm = new JButton(joined ? "Remove" : "Withdraw");
					String id = Json.str(e.get("id"));
					rm.addActionListener(ev -> post.accept(m("remove_relay", id)));
					relayList.add(row(l, rm));
				}
				if (relayList.getComponentCount() == 0) { JLabel none = new JLabel("No relay yet."); none.setForeground(GREY); relayList.add(row(none)); }
				forList.removeAll();
				for (Object o : Json.list(r.get("wake_for"))) {
					Map<String, Object> e = Json.map(o);
					Object woke = e.get("woke_at");
					JLabel l = new JLabel(Json.str(e.get("name")) + "  ·  " + Json.str(e.get("mac")) + (woke == null ? "" : "  ·  last woken " + dev.gatehouse.look.Times.show(woke, "")));
					JButton test = new JButton("Wake it now"), rm = new JButton("Remove");
					String id = Json.str(e.get("id"));
					test.addActionListener(ev -> post.accept(m("test_for", id)));
					rm.addActionListener(ev -> post.accept(m("remove_for", id)));
					forList.add(row(l, test, rm));
				}
				if (forList.getComponentCount() == 0) { JLabel none = new JLabel("None: this machine wakes no other."); none.setForeground(GREY); forList.add(row(none)); }
				rows.revalidate(); rows.repaint();
			});
		}, "wake-load").start();

		macSave.addActionListener(e -> post.accept(m("mac", macF.getText().trim())));
		routerSave.addActionListener(e -> post.accept(m("router", routerF.getText().trim())));
		codeB.addActionListener(e -> {
			String label = dev.gatehouse.look.InWindow.showInputDialog(f, "Which machine will be the relay? (a name for your list, like Laptop or Pi)", "Relay code", JOptionPane.PLAIN_MESSAGE);
			if (label != null) post.accept(m("code", label.trim()));
		});
		joinB.addActionListener(e -> { if (!joinF.getText().isBlank()) { said.setText("Telling the other machine about this relay…"); post.accept(m("join", joinF.getText().trim())); } });

		rows.add(heading("Waking this machine from outside the home"));
		rows.add(note("A sleeping machine only hears its wake signal inside its own home network. Launchers allowed Machine wake/sleep learn the ways below while this machine is awake "
				+ "and keep them, so their Wake button works from anywhere afterwards. In Windows, the network card's Power Management must let it wake the computer (and often the BIOS too)."));
		rows.add(row(bold("Network card:"), macF, macSave, macNote));
		rows.add(Box.createVerticalStrut(8));
		rows.add(heading("Through your router"));
		rows.add(note("Forward a UDP port (9 is usual) in your router to this home network's broadcast address, or to this machine's address if the router keeps a fixed entry for it "
				+ "(many routers forget a sleeping machine otherwise). Then give your home's public name (a DuckDNS or No-IP name follows it when it changes) and that port here. "
				+ "Not every router allows it, and anyone who finds the port could wake this machine (only wake it: nothing else)."));
		rows.add(row(bold("Public name and port:"), routerF, routerSave));
		rows.add(Box.createVerticalStrut(8));
		rows.add(heading("Through a machine that stays awake (a relay)"));
		rows.add(note("Another machine at home that is always on (a laptop, a Raspberry Pi, an old PC) runs Gatekeeper and wakes this one when a launcher asks it with a ticket "
				+ "this machine gave it (good for 30 days). Make a code here and paste it into that machine's Gatekeeper. Launchers reach the relay the way they reach any Gatekeeper: "
				+ "Tailscale, or the internet with its own port forwarded in your router."));
		rows.add(row(codeB));
		rows.add(relayList);
		rows.add(Box.createVerticalStrut(12));
		rows.add(heading("Wake another machine from here"));
		rows.add(note("If this machine stays awake, paste another machine's relay code here and this Gatekeeper wakes that one for its launchers."));
		rows.add(row(joinF, joinB));
		rows.add(forList);
		for (Component c : rows.getComponents()) if (c instanceof javax.swing.JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel tab = new JPanel(new BorderLayout(0, 8));
		tab.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
		tab.add(rows, BorderLayout.NORTH);
		tab.add(said, BorderLayout.SOUTH);
		tab.addHierarchyListener(e -> { if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && tab.isShowing()) load[0].run(); });
		load[0].run();
		return tab;
	}

	private static JPanel row(Component... cs) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		for (Component c : cs) p.add(c);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}
	private static JLabel bold(String s) { JLabel l = new JLabel(s); l.setFont(l.getFont().deriveFont(Font.BOLD)); return l; }
	private static JLabel heading(String s) { JLabel l = bold(s); l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() + 1f)); l.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 0)); return l; }
	private static JLabel note(String s) { JLabel l = new JLabel("<html><div style='width:820px'>" + s + "</div></html>"); l.setForeground(GREY); l.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 0)); return l; }
}
