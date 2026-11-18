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
 * Part of Gatekeeper's Options page: how this Gatekeeper updates (normal, auto or constellation), and which open launchers take part.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class UpdatingUi {
	private UpdatingUi() {}

	static void addTo(JPanel options, JFrame f, java.util.function.BiConsumer<String, Boolean> optLine) {
		optLine.accept("Updating", true);
		javax.swing.JRadioButton modeNormal = new javax.swing.JRadioButton("Normal: update when I press Update Gatekeeper", true),
				modeAuto = new javax.swing.JRadioButton("Auto: update by itself as soon as a new version is out"),
				modeGroup = new javax.swing.JRadioButton("Constellation: wait for the open launchers, then update together");
		javax.swing.ButtonGroup modeGroupOf = new javax.swing.ButtonGroup();
		for (javax.swing.JRadioButton rb : new javax.swing.JRadioButton[] {modeNormal, modeAuto,
				modeGroup}) { modeGroupOf.add(rb); rb.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT); options.add(rb); }
		optLine.accept("Normal is what it is unless you choose otherwise. Auto: Gatekeeper looks every minute and updates itself, waiting only for a server it runs itself to be off. Constellation: when a new version is out, Gatekeeper waits until every open launcher that is in constellation mode too has seen it, waits for any server it runs itself to be off, and then they all update at once. Each launcher chooses its own mode in its own Options; the Constellation tab shows which is in which.", false);
		JLabel autoSaid = new JLabel(" ");
		autoSaid.setForeground(new Color(0xA9B5C1)); autoSaid.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(autoSaid);
		boolean[] autoFilling = {false};
		Runnable autoRead = () -> new Thread(() -> {
			try {
				Map<String, Object> a = Json.map(Json.parse(call("GET", "/admin/auto", null)));
				List<Object> open = Json.list(a.get("launchers"));
				String md = Json.str(a.get("mode"));
				SwingUtilities.invokeLater(() -> { autoFilling[0] = true; (md.equals("auto") ? modeAuto : md.equals("constellation")
						? modeGroup : modeNormal).setSelected(true); autoFilling[0] = false; autoSaid.setText("<html><div style='width:470px'>" + Json.str(a.get("status")).replace("<", "&lt;") + (md.equals("constellation") ? " Launchers open in constellation mode right now: " + (open.isEmpty() ? "none" : open.toString().replaceAll("[\\[\\]]", "").replace("<", "&lt;")) + "." : "") + "</div></html>"); });
			} catch (Exception ignored) { Quiet.seen(ignored); }
		}, "update-mode").start();
		java.awt.event.ActionListener pickMode = ev -> { if (autoFilling[0]) return; String want = modeAuto.isSelected() ? "auto"
				: modeGroup.isSelected() ? "constellation" : "normal"; new Thread(() -> { try { call("POST", "/admin/auto",
				Json.write(m("mode", want))); } catch (Exception ignored) { Quiet.seen(ignored); } autoRead.run(); }, "update-mode").start(); };
		modeNormal.addActionListener(pickMode); modeAuto.addActionListener(pickMode); modeGroup.addActionListener(pickMode);
		if (System.getenv("OJ_PANEL_PNG") == null) { autoRead.run(); new Timer(5000, ev -> { if (options.isShowing()) autoRead.run(); }).start(); }
	}
}
