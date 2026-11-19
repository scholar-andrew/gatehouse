package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import dev.gatehouse.look.Quiet;

/**
 * Share pairing in Gatekeeper's window: what to give someone so their launcher can find this Gatekeeper (the address, a link carrying its name too, or the same as a small file). None of them lets a launcher do anything: it still introduces itself and waits to be allowed.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class PairingUi {
	private PairingUi() {}

	static void wire(JButton pairB, JLabel pairNote, JFrame f) {
		// Share pairing: what to give someone so their launcher can find this Gatekeeper. The address by itself, a
		// link that carries its name too (pasted into the launcher's Add Gatekeeper), or the same as a small file to
		// send. None of them lets a launcher do anything: it still introduces itself and waits to be allowed.
		String[] shareLink = {"", "", ""};   // (the invite link, and this Gatekeeper's name, as the running Gatekeeper has them)
		pairB.addActionListener(e -> {
			String addr, name;
			try {
				Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup/scan", "{}")));
				if (Json.str(r.get("bind")).isEmpty()) { pairNote.setText("No Tailscale address yet: use Server setup first."); return; }
				addr = Json.str(r.get("bind")) + ":" + Json.str(r.get("port"));
				shareLink[2] = ServerTools.yes(r.get("https")) ? Json.str(r.get("fingerprint")) : "";   // (R79)
				shareLink[0] = Json.str(r.get("share_link"));
				shareLink[1] = Json.str(r.get("name"));
			} catch (Exception ex) { pairNote.setText("Couldn't ask Gatekeeper: is it running?"); return; }
			name = shareLink[1];
			if (name.isEmpty()) try { name = java.net.InetAddress.getLocalHost().getHostName(); } catch (IOException ex) { name = "Gatekeeper"; }
			// (with the Tailscale invite link from Host setup, when there is one: "ts" in the link, "tailscale_share" in the file)
			String share = shareLink[0];
			String link = "gatehouse://pair/" + addr + "?name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
					+ (share.isEmpty() ? "" : "&ts=" + java.net.URLEncoder.encode(share, StandardCharsets.UTF_8))
					+ (shareLink[2].isEmpty() ? "" : "&fp=" + shareLink[2]), nm = name;
			javax.swing.JTextField addrF = new javax.swing.JTextField(addr, 34), linkF = new javax.swing.JTextField(link, 34);
			addrF.setEditable(false); linkF.setEditable(false);
			JButton copyA = new JButton("Copy"), copyL = new JButton("Copy"), save = new JButton("Save pairing file\u2026");
			JLabel said = new JLabel(" ");
			said.setForeground(new Color(0x8BD46E));
			java.util.function.BiConsumer<String, String> clip = (text,
					what) -> { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null); said.setText(what + " copied."); };
			copyA.addActionListener(ev -> clip.accept(addr, "Address"));
			copyL.addActionListener(ev -> clip.accept(link, "Pairing link"));
			save.setToolTipText("A small file to send to someone: they open it from Add Gatekeeper in their launcher");
			save.addActionListener(ev -> {
				javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
				fc.setSelectedFile(new java.io.File(nm.replaceAll("[^A-Za-z0-9 ._-]", "").trim() + ".gatekeeper"));
				if (fc.showSaveDialog(f) != javax.swing.JFileChooser.APPROVE_OPTION) return;
				Map<String, Object> pf = m("gatehouse_pairing", 1, "name", nm, "address", addr);
				if (!share.isEmpty()) pf.put("tailscale_share", share);
				if (!shareLink[2].isEmpty()) pf.put("fingerprint", shareLink[2]);   // (R79)
				try { Files.writeString(fc.getSelectedFile().toPath(), Json.write(pf)
						+ "\n", StandardCharsets.UTF_8); said.setText("Saved " + fc.getSelectedFile().getName() + "."); }
				catch (IOException ex) { said.setForeground(new Color(0xFF7B72)); said.setText("Couldn't save: " + ex.getMessage()); }
			});
			JPanel form = new JPanel();
			form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
			java.util.function.Function<String,
					JLabel> lab = s -> { JLabel l = new JLabel(s); l.setFont(l.getFont().deriveFont(Font.BOLD)); l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0)); return l; };
			JPanel ra = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); ra.add(addrF); ra.add(copyA);
			JPanel rl = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); rl.add(linkF); rl.add(copyL);
			JPanel rs = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); rs.add(save); rs.add(said);
			JLabel about = new JLabel("<html><div style='width:460px'>Give one of these to someone whose launcher should connect to this Gatekeeper (\"" + nm.replace("<", "&lt;") + "\"). In their Gatehouse launcher: Add Gatekeeper, then paste it or open the file. "
					+ (share.isEmpty() ? "Their device has to be able to reach this machine over Tailscale (Host setup has a place for the invite link that lets it)" : "The link and the file carry your Tailscale invite, so their device can reach this machine")
					+ ", and it can do nothing here until you allow it in Access Manager.</div></html>");
			for (javax.swing.JComponent c : new javax.swing.JComponent[] {about, lab.apply("Address"), ra,
					lab.apply("Pairing link (carries this Gatekeeper's name)"), rl, lab.apply("Pairing file"),
					rs}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); }
			String shot = System.getenv("OJ_PANEL_PNG");
			if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the form instead of the dialog)
				JFrame tf = new JFrame(); form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); tf.setContentPane(form); tf.pack();
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(form.getWidth(), form.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics(); form.paint(g); g.dispose();
				try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + "-pairing.png")); } catch (IOException ex) { ex.printStackTrace(); }
				tf.dispose();
				return;
			}
			JOptionPane.showMessageDialog(f, form, "Share pairing", JOptionPane.PLAIN_MESSAGE);
		});
	}
}
