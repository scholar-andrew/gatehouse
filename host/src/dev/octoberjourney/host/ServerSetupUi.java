package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
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
 * The Server setup dialog in Gatekeeper's window: MCSS's program (or the folder of servers Gatekeeper runs itself), this machine's Tailscale address, and a tick for each Minecraft server.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class ServerSetupUi {
	private ServerSetupUi() {}

	static Runnable dialog(JFrame f) {
		// Server setup: MCSS's program, this machine's Tailscale address, and a tick for each Minecraft server MCSS has.
		Runnable setup = () -> {
			javax.swing.JTextField exeF = new javax.swing.JTextField(36);
			javax.swing.JComboBox<String> addrF = new javax.swing.JComboBox<>();
			addrF.setEditable(true);
			JPanel list = new JPanel();
			list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
			JLabel problem = new JLabel(" ");
			problem.setForeground(new Color(0xFF7B72));
			List<Object[]> rows = new ArrayList<>();   // {its tick-box, the server as found}
			Runnable scan = () -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup/scan", Json.write(m("mcss_exe", exeF.getText().trim())))));
					exeF.setText(Json.str(r.get("mcss_exe")));
					list.removeAll(); rows.clear();
					boolean any = false;
					for (Object o : Json.list(r.get("servers"))) if (Boolean.TRUE.equals(Json.map(o).get("tracked"))) any = true;
					for (Object o : Json.list(r.get("servers"))) {
						Map<String, Object> s = Json.map(o);
						javax.swing.JCheckBox cb = new javax.swing.JCheckBox(Json.str(s.get("name")) + "   (port "
								+ Json.str(s.get("minecraft")).replaceAll(".*:", "") + ", " + Json.str(s.get("dir")) + ")", Boolean.TRUE.equals(s.get("tracked")) || !any);
						rows.add(new Object[] {cb, s}); list.add(cb);
					}
					problem.setText(Json.str(r.get("problem")).isEmpty() ? " " : Json.str(r.get("problem")));
					if (addrF.getItemCount() == 0) {
						if (!Json.str(r.get("bind")).isEmpty()) addrF.addItem(Json.str(r.get("bind")));
						for (Object a : Json.list(r.get("addresses"))) if (!Json.str(a).equals(Json.str(r.get("bind")))) addrF.addItem(Json.str(a));
						if (addrF.getItemCount() == 0) problem.setText((problem.getText().isBlank() ? "" : problem.getText() + " ")
								+ "Tailscale isn't running on this machine: start it, then look again (or type its 100.x.y.z address).");
					}
					list.revalidate(); list.repaint();
					java.awt.Window w = SwingUtilities.getWindowAncestor(list);
					if (w != null) w.pack();
				} catch (Exception ex) { problem.setText("Couldn't ask Gatekeeper: is it running? "
						+ say("(Task Scheduler, Gatehouse Keeper)", "(systemctl --user status gatehouse-keeper)")); }
			};
			JButton browse = new JButton("Browse\u2026"), again = new JButton("Look again");
			browse.addActionListener(ev -> {
				javax.swing.JFileChooser fc = new javax.swing.JFileChooser(!exeF.getText().isBlank() ? exeF.getText() : WIN ? "C:\\"
						: System.getProperty("user.home", "/"));
				if (builtin()) fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
				else if (WIN) fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MCSS (mcss.exe)", "exe"));
				if (dev.gatehouse.look.InWindow.showOpenDialog(fc, f) == javax.swing.JFileChooser.APPROVE_OPTION) { exeF.setText(fc.getSelectedFile().getAbsolutePath()); scan.run(); }
			});
			again.addActionListener(ev -> scan.run());
			JPanel form = new JPanel();
			form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
			JPanel exeRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); exeRow.add(exeF); exeRow.add(browse); exeRow.add(again);
			JPanel addrRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)); addrRow.add(addrF);
			java.util.function.Function<String,
					JLabel> subHead = hs -> { JLabel l = new JLabel(hs); l.setFont(l.getFont().deriveFont(Font.BOLD)); l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0)); return l; };
			for (javax.swing.JComponent c : new javax.swing.JComponent[] {subHead.apply(builtin()
					? "The folder your servers are in (one folder each)" : "MCSS on this machine (" + mcssFile() + ")"), exeRow,
					subHead.apply("This machine's Tailscale address (launchers reach Gatekeeper on it)"), addrRow,
					subHead.apply(builtin() ? "servers in that folder (tick the ones Gatekeeper looks after)"
							: "Minecraft servers in MCSS (tick the ones Gatekeeper looks after)"), list,
							problem}) { c.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); form.add(c); }
			scan.run();
			String shot = System.getenv("OJ_PANEL_PNG");
			if (shot != null && !shot.isEmpty()) {   // (testing: a picture of the form instead of the dialog)
				JFrame t = new JFrame(); form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); t.setContentPane(form); t.pack();
				java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(form.getWidth(), form.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics(); form.paint(g); g.dispose();
				try { javax.imageio.ImageIO.write(img, "png", new java.io.File(shot + "-setup.png")); } catch (IOException ex) { ex.printStackTrace(); }
				t.dispose();
				return;
			}
			if (dev.gatehouse.look.InWindow.showConfirmDialog(f, form, "Server setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			List<Object> picked = new ArrayList<>();
			for (Object[] row : rows) if (((javax.swing.JCheckBox) row[0]).isSelected()) picked.add(row[1]);
			String msg;
			try {
				Map<String, Object> r = Json.map(Json.parse(call("POST", "/admin/setup", Json.write(m("mcss_exe",
						exeF.getText().trim(), "bind", Json.str(addrF.getEditor().getItem()).trim(), "servers", picked)))));
				msg = Json.str(r.get("message"));
			} catch (Exception ex) { msg = "Couldn't ask Gatekeeper: is it running?"; }
			dev.gatehouse.look.InWindow.showMessageDialog(f, "<html><div style='width:380px'>" + msg + "</div></html>", "Server setup", JOptionPane.PLAIN_MESSAGE);
		};
		return setup;
	}
}
