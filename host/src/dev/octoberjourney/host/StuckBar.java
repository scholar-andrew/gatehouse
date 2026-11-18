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
 * The red bar along the top of Gatekeeper's window when the Gatekeeper behind it stops answering, with Restart
 * Gatekeeper (R29). It asks every ten seconds; three misses in a row show it, an answer hides it. Moved out of
 * HostHelper.panel() as it was (R36).
 */
final class StuckBar {
	private StuckBar() {}

	/** The page "p" with the bar above it (hidden until needed). */
	static JPanel around(JPanel p, JFrame f) {
		// (R29: when the Gatekeeper behind this window stops answering, a bar says so, with Restart Gatekeeper)
		JPanel stuckBar = new JPanel(new BorderLayout(12, 0));
		stuckBar.setBackground(new Color(0x4A1F22));
		stuckBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE5484D)),
				BorderFactory.createEmptyBorder(8, 14, 8, 14)));
		JLabel stuckText = new JLabel("<html><b>Gatekeeper isn't answering.</b> If it has frozen it restarts itself within a few minutes; or restart it now. Servers keep running.</html>");
		stuckText.setForeground(new Color(0xFFD7D9));
		JButton stuckGo = new JButton("Restart Gatekeeper");
		stuckBar.add(stuckText, BorderLayout.CENTER); stuckBar.add(stuckGo, BorderLayout.EAST);
		stuckBar.setVisible(false);
		stuckGo.addActionListener(ev -> {
			stuckGo.setEnabled(false); stuckText.setText("<html><b>Restarting Gatekeeper\u2026</b></html>");
			new Thread(() -> {
				String said = restartKeeper();
				SwingUtilities.invokeLater(() -> { stuckGo.setEnabled(true); stuckText.setText("<html>" + (said.isEmpty()
						? "<b>Gatekeeper was restarted.</b> This bar goes once it answers." : "<b>Couldn't restart it:</b> " + said.replace("<", "&lt;")) + "</html>"); });
			}, "restart-keeper").start();
		});
		if (System.getenv("OJ_PANEL_PNG") == null && farUrl == null) {
			int[] misses = {0};
			new Timer(10_000, ev -> { if (f.isDisplayable()) new Thread(() -> {
				boolean ok = Watchdog.alive();
				SwingUtilities.invokeLater(() -> { misses[0] = ok ? 0 : misses[0]
						+ 1; boolean show = misses[0] >= 3; if (stuckBar.isVisible() != show) { stuckBar.setVisible(show); if (show) stuckGo.setEnabled(true); } });
			}, "alive").start(); }).start();
		}
		JPanel withBar = new JPanel(new BorderLayout()); withBar.setOpaque(false);
		withBar.add(stuckBar, BorderLayout.NORTH); withBar.add(p, BorderLayout.CENTER);
		return withBar;
	}
}
