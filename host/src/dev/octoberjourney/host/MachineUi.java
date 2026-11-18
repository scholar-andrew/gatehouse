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
 * Part of Gatekeeper's Options page: what kind of machine this is (the picture launchers show for this Gatekeeper), worked out by itself or chosen here.
 * Moved out of HostHelper.panel() unchanged (R36), so Gatekeeper's window is in pieces that can be read on their own.
 */
final class MachineUi {
	private MachineUi() {}

	static void addTo(JPanel options, JFrame f, java.util.function.BiConsumer<String, Boolean> optLine) {
		// What kind of machine this is: the picture launchers show for this Gatekeeper in their list.
		optLine.accept("This machine", true);
		javax.swing.JComboBox<String> deviceBox = new javax.swing.JComboBox<>();
		deviceBox.addItem("Automatic");
		for (String[] k : dev.gatehouse.look.DeviceIcon.KINDS) deviceBox.addItem(k[1]);
		JLabel deviceSaid = new JLabel(" ");
		deviceSaid.setForeground(new Color(0xA9B5C1));
		JPanel deviceRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 4));
		deviceRow.add(deviceBox); deviceRow.add(javax.swing.Box.createHorizontalStrut(10)); deviceRow.add(deviceSaid);
		deviceRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		options.add(deviceRow);
		optLine.accept("The picture launchers show for this Gatekeeper in their list of Gatekeepers. Automatic works it out from what the machine says about itself; choose a kind here if it gets it wrong.", false);
		boolean[] deviceFilling = {true};
		java.util.function.Consumer<Map<String, Object>> deviceShow = r -> SwingUtilities.invokeLater(() -> {
			deviceFilling[0] = true;
			String chosen = Json.str(r.get("chosen")), was = Json.str(r.get("found"));
			int at = 0;
			for (int i = 0; i < dev.gatehouse.look.DeviceIcon.KINDS.length; i++) if (dev.gatehouse.look.DeviceIcon.KINDS[i][0].equals(chosen)) at = i + 1;
			deviceBox.setSelectedIndex(at);
			deviceSaid.setText("Automatic finds: " + (was.isEmpty()
					? "nothing it recognises (launchers show this Gatekeeper's initials)" : dev.gatehouse.look.DeviceIcon.name(was)));
			deviceFilling[0] = false;
		});
		new Thread(() -> { try { deviceShow.accept(Json.map(Json.parse(call("GET", "/admin/device",
				null)))); } catch (Exception e) { deviceFilling[0] = false; } }, "device").start();
		deviceBox.addActionListener(ev -> {
			if (deviceFilling[0]) return;
			int i = deviceBox.getSelectedIndex();
			String id = i <= 0 ? "" : dev.gatehouse.look.DeviceIcon.KINDS[i - 1][0];
			new Thread(() -> { try { deviceShow.accept(Json.map(Json.parse(call("POST", "/admin/device", Json.write(m("kind",
					id)))))); } catch (Exception ignored) { Quiet.seen(ignored); } }, "device").start();
		});
	}
}
