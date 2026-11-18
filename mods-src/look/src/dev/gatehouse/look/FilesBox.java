package dev.gatehouse.look;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * The files on offer in a chatroom: who offered what and when, saving a copy of one, and offering one of your own.
 * It only shows and asks; how the list is fetched and a file sent or fetched is the caller's (a launcher asks its
 * Gatekeeper over the network, Gatekeeper's own window asks the Gatekeeper on its machine).
 *   list    rows of {id, name, size in words, who, when}
 *   fetch   an id -> the file, or null
 *   send    a name and the file -> what the Gatekeeper said
 */
public final class FilesBox {
	private FilesBox() {}

	public static void show(Component over, String room, String limit, Supplier<List<String[]>> list, Function<String, byte[]> fetch,
			BiFunction<String, byte[], String> send) {
		JDialog d = new JDialog(SwingUtilities.getWindowAncestor(over), "Files: " + room, JDialog.ModalityType.APPLICATION_MODAL);
		DefaultTableModel model = new DefaultTableModel(new Object[] {"File", "Size", "From", "When"},
				0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
		JTable table = new JTable(model);
		table.setRowHeight(30);
		List<String[]>[] rows = new List[] {List.of()};
		JLabel said = new JLabel(" ");
		said.setForeground(new Color(0xA9B5C1));
		Runnable load = () -> new Thread(() -> {
			List<String[]> got = list.get();
			SwingUtilities.invokeLater(() -> { rows[0] = got; model.setRowCount(0); for (String[] r : got) model.addRow(new Object[] {r[1],
					r[2], r[3], r[4]}); if (got.isEmpty()) said.setText("Nothing is on offer in this room."); });
		}, "files").start();
		JButton save = new JButton("Save a copy…"), offer = new JButton("Offer a file…"), close = new JButton("Close");
		save.addActionListener(e -> {
			int i = table.getSelectedRow();
			if (i < 0 || i >= rows[0].size()) { said.setText("Click a file first."); return; }
			String[] r = rows[0].get(i);
			JFileChooser fc = new JFileChooser();
			fc.setDialogTitle("Save a copy of " + r[1]);
			fc.setSelectedFile(new File(r[1]));
			if (fc.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
			File to = fc.getSelectedFile();
			said.setText("Fetching…");
			new Thread(() -> {
				String out;
				try { byte[] b = fetch.apply(r[0]); if (b == null) out = "It couldn't be fetched: it may no longer be kept."; else { Files.write(to.toPath(), b); out = "Saved as " + to.getName() + ". Only open files from people you trust."; } }
				catch (Exception ex) { out = "Couldn't save it there: " + ex.getMessage(); }
				String show = out;
				SwingUtilities.invokeLater(() -> said.setText(show));
			}, "file-save").start();
		});
		offer.addActionListener(e -> {
			JFileChooser fc = new JFileChooser();
			fc.setDialogTitle("Offer a file to " + room);
			if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
			File from = fc.getSelectedFile();
			said.setText("Sending…");
			new Thread(() -> {
				String out;
				try { out = from.length() > 512L * 1024 * 1024 ? "That file is too big." : send.apply(from.getName(), Files.readAllBytes(from.toPath())); }
				catch (Exception ex) { out = "That file couldn't be read: " + ex.getMessage(); }
				String show = out;
				SwingUtilities.invokeLater(() -> { said.setText(show); load.run(); });
			}, "file-send").start();
		});
		close.addActionListener(e -> d.dispose());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		buttons.add(save); buttons.add(offer); buttons.add(close);
		JPanel bottom = new JPanel(new BorderLayout(0, 6));
		bottom.add(buttons, BorderLayout.NORTH); bottom.add(said, BorderLayout.CENTER);
		bottom.add(new JLabel("<html><div style='width:520px;color:#A9B5C1'>A file you offer is kept on the Gatekeeper for a day, for everyone in this room to save a copy of" + (limit.isEmpty() ? "" : " (" + limit + " at most)") + ". Nothing is put on anyone's device unless they save it.</div></html>"), BorderLayout.SOUTH);
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		p.add(new JScrollPane(table), BorderLayout.CENTER); p.add(bottom, BorderLayout.SOUTH);
		d.setContentPane(p);
		d.setSize(620, 420);
		d.setLocationRelativeTo(over);
		load.run();
		d.setVisible(true);
	}
}
