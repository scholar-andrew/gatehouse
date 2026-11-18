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
 * Part of Gatekeeper's Options page: ending the background Gatekeeper, forgetting its settings, and uninstalling it.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class EndUi {
	private EndUi() {}

	static void addTo(JPanel options, JFrame f, java.util.function.BiConsumer<String, Boolean> optLine, Path keeperDir) {
		// Ending the background Gatekeeper (this window is only a view of it): for when it should not be running.
		optLine.accept("End Gatekeeper", true);
		optLine.accept("Gatekeeper runs in the background whether this window is open or not; closing the window stops nothing. Ending it stops it until you next sign in, or until it is started again (Start Gatekeeper in the launcher on this machine, or opening this window). While it is ended, launchers can't see or start this machine's servers.", false);
		JButton endB = new JButton("End Gatekeeper\u2026");
		JLabel endSaid = new JLabel(" ");
		JPanel endRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
		endRow.add(endB); endRow.add(javax.swing.Box.createHorizontalStrut(10)); endRow.add(endSaid); endRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(endRow);
		endB.addActionListener(ev -> {
			if (JOptionPane.showConfirmDialog(f,
					"<html><div style='width:400px'>End Gatekeeper on this machine now?<br><br>Launchers lose sight of its servers until it is started again. Servers MC Server Soft runs keep running. This window closes too.</div></html>", "End Gatekeeper", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
			new Thread(() -> {
				Map<String, Object> r;
				try { r = Json.map(Json.parse(call("POST", "/admin/quit", "{}"))); } catch (Exception e) { r = m("result", "failed",
						"message", "Gatekeeper didn't answer: it may not be running."); }
				Map<String, Object> said = r;
				SwingUtilities.invokeLater(() -> {
					if ("ok".equals(Json.str(said.get("result")))) { f.dispose(); System.exit(0); }
					endSaid.setForeground(new Color(0xE9B949));
					endSaid.setText("<html><div style='width:420px'>" + Json.str(said.get("message")).replace("<", "&lt;") + "</div></html>");
				});
			}, "end").start();
		});
		optLine.accept("Uninstall", true);
		optLine.accept("Removes Gatekeeper from this machine: it is stopped, and " + say("its start-up task, its Start menu shortcut",
				"its start-up service, its entry in the applications menu")
				+ " and its program are taken away. MC Server Soft, your Minecraft servers and their worlds are not touched; launchers simply stop getting an answer from here. If Gatekeeper is in your Steam library, remove it there afterwards.", false);
		javax.swing.JCheckBox forgetSettings = new javax.swing.JCheckBox("Also remove its settings: who is allowed to do what, its key for MCSS, and its log");
		forgetSettings.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		JButton uninstall = new JButton("Uninstall Gatekeeper\u2026");
		uninstall.setToolTipText("Asks you to type confirm first");
		uninstall.setForeground(new Color(0xFF7B72));
		JPanel uninstallRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 8));
		uninstallRow.add(uninstall); uninstallRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		JLabel uninstallSaid = new JLabel(" "); uninstallSaid.setForeground(new Color(0x8F98A0)); uninstallSaid.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(forgetSettings); options.add(uninstallRow); options.add(uninstallSaid);
		if (viewOnly) {   // (still listed, as what can't be done from here always is)
			forgetSettings.setEnabled(false); uninstall.setEnabled(false);
			uninstall.setToolTipText("Not from here");
			uninstallSaid.setText("<html><div style='width:470px'>Not from this window: the launcher opened it, and from there Gatekeeper's own folder can't be reached. Open Gatekeeper from its own entry in the applications menu (on a Steam Deck: in Desktop Mode) to uninstall it.</div></html>");
		}
		uninstall.addActionListener(ev -> {
			javax.swing.JTextField typed = new javax.swing.JTextField(16);
			JPanel askConfirm = new JPanel(new BorderLayout(0, 8));
			askConfirm.add(new JLabel("<html><div style='width:400px'>This removes Gatekeeper from this machine"
					+ (forgetSettings.isSelected() ? ", with its settings" : "") + ". Type <b>confirm</b> to go ahead.</div></html>"), BorderLayout.NORTH);
			askConfirm.add(typed, BorderLayout.CENTER);
			if (JOptionPane.showConfirmDialog(f, askConfirm, "Uninstall Gatekeeper", JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
			if (!typed.getText().trim().equalsIgnoreCase("confirm")) { uninstallSaid.setText("Nothing was removed: the word typed wasn't \"confirm\"."); return; }
			try {
				// Its own install script (carried inside this program, written out beside its settings), with -Uninstall,
				// in a window of its own so what it did can be read. It stops Gatekeeper, this window included.
				List<String> cmd = uninstallCommand(keeperDir, forgetSettings.isSelected());
				if (cmd == null) { uninstallSaid.setText(say("The uninstall script isn't here. Use gatehouse-install.ps1 -Uninstall -Gatekeeper instead.", "The uninstall script isn't here. Use install-keeper.sh --uninstall instead.")); return; }
				if (System.getenv("OJ_UNINSTALL_DRY") != null) { uninstallSaid.setText("<html><div style='width:760px'>would run: "
						+ String.join(" ", cmd).replace("&", "&amp;").replace("<", "&lt;")
						+ "</div></html>"); System.out.println("would run: " + String.join(" ", cmd)); return; }
				if (!WIN) {
					// (no window of its own here: the script's answer is shown, and this window stays until it's closed)
					uninstall.setEnabled(false);
					uninstallSaid.setText("Removing Gatekeeper\u2026");
					new Thread(() -> {
						String out = commandSays(cmd.toArray(new String[0]));
						String told = out == null ? "The uninstall script didn't finish." : out.trim();
						SwingUtilities.invokeLater(() -> uninstallSaid.setText("<html><div style='width:760px'>" + told.replace("&",
								"&amp;").replace("<", "&lt;").replace("\n", "<br>") + "<br><br>You can close this window.</div></html>"));
					}, "uninstall").start();
					return;
				}
				new ProcessBuilder(cmd).start();
				uninstallSaid.setText("Removing Gatekeeper in its own window. This one closes now.");
				new javax.swing.Timer(1500, e2 -> System.exit(0)).start();
			} catch (IOException | RuntimeException ex) { uninstallSaid.setText("Couldn't start the uninstall: " + ex.getMessage()); }
		});
	}
}
