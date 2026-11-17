package dev.gatehouse.look;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * Chat, as the launcher and Gatekeeper both show it: the rooms as cards across the top (each with its picture, its name
 * and a red count of messages not seen yet), what was said in the one chosen, and a line to write in with Send, Paste
 * and Attach.
 *
 * A message that offers a file shows the file's name, kind and size with View and Save a copy. Nothing is fetched until
 * one of those is pressed. View shows a picture in the chat; a video, a sound or any other file is opened with this
 * device's own program for it. Paste takes what is on the clipboard: words go into the line; a picture, or a copied
 * file (a video, a sound, anything), is offered to the room.
 *
 * A short sound plays when a message arrives in a room while it isn't the one being read here, unless that is
 * switched off. Where each room has been read up to, and the switch, are kept by the window (Store).
 *
 * The window says how to reach its rooms (Backend): the launcher across all its Gatekeepers, Gatekeeper its own.
 */
public final class ChatView extends JPanel {
	/** A room: key (the window's own), its name, the Gatekeeper it belongs to (for a launcher with several), the number
	 *  of its newest message, and its picture (null for none: its initials are shown). */
	public record Room(String key, String name, String group, long last, Supplier<Image> picture) {}
	/** A message. file*: the file it offers, if any. */
	public record Msg(long n, String when, String who, String text, String fileId, String fileName, long fileSize) {}
	/** What a room answered: its messages after the one asked from, the number of its newest, and what to tell (a refusal). */
	public record Batch(List<Msg> msgs, long next, String said) {}
	public interface Backend {
		List<Room> rooms() throws Exception;
		Batch talk(Room room, long after, String text) throws Exception;
		byte[] file(Room room, String id) throws Exception;
		/** Offers a file to the room; what to tell. */
		String offer(Room room, String name, byte[] data) throws Exception;
	}
	public interface Store { String get(String key); void put(String key, String value); }

	private final Backend backend;
	private final Store store;
	private final BooleanSupplier onScreen;
	private final IntConsumer unread;
	private final JPanel strip = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
	private final Track list = new Track();
	private final JScrollPane scroll;
	private final JTextField line = new JTextField();
	private final JLabel said = new JLabel(" ");
	private final JCheckBox sound = new JCheckBox("Sound for new messages");
	private final Map<String, RoomCard> cards = new LinkedHashMap<>();
	private final Map<String, Long> heard = new java.util.concurrent.ConcurrentHashMap<>();   // (newest message number at the last look, per room)
	private volatile List<Room> rooms = new ArrayList<>();
	private volatile String chosen = "";
	private volatile long shown = 0;
	private volatile boolean first = true, polling = false;
	private Thread loop;

	public ChatView(Backend backend, Store store, BooleanSupplier onScreen, IntConsumer unread, JComponent... extra) {
		super(new BorderLayout(0, 8));
		this.backend = backend; this.store = store; this.onScreen = onScreen; this.unread = unread;
		setOpaque(false);
		strip.setOpaque(false);
		chosen = nz(store.get("chosen"));
		sound.setOpaque(false);
		sound.setSelected(!"off".equals(store.get("sound")));
		sound.setToolTipText("A short sound when a message arrives in a room you aren't reading here");
		sound.addActionListener(e -> store.put("sound", sound.isSelected() ? "on" : "off"));
		JPanel top = new JPanel(new BorderLayout(0, 2)); top.setOpaque(false);
		top.add(strip, BorderLayout.CENTER);
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
		JPanel hold = new JPanel(new BorderLayout()); hold.setOpaque(false); hold.add(list, BorderLayout.NORTH);
		Track holder = new Track(); holder.setLayout(new BorderLayout()); holder.setOpaque(false); holder.add(hold, BorderLayout.NORTH);
		scroll = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createLineBorder(GameShell.a(GameShell.TEXT, 30)));
		scroll.getViewport().setBackground(SteamLook.DEEP);
		JButton send = new JButton("Send"), paste = new JButton("Paste"), attach = new JButton("Attach…");
		send.setToolTipText("Sends what is written (Enter does too)");
		paste.setToolTipText("Words on the clipboard go into the line; a copied picture, video, sound or other file is offered to the room");
		attach.setToolTipText("Offers a file from this device to the room. Others see it in the chat and choose whether to view or save it.");
		send.addActionListener(e -> say());
		line.addActionListener(e -> say());
		paste.addActionListener(e -> paste(true));
		attach.addActionListener(e -> attach());
		// (Ctrl+V in the line: a picture or a copied file is offered, as Paste does; words paste as usual)
		line.addKeyListener(new java.awt.event.KeyAdapter() {
			@Override public void keyPressed(java.awt.event.KeyEvent e) {
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_V && (e.isControlDown() || e.isMetaDown()) && paste(false)) e.consume();
			}
		});
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); buttons.setOpaque(false);
		buttons.add(send); buttons.add(paste); buttons.add(attach);
		for (JComponent x : extra) buttons.add(x);
		JPanel bottom = new JPanel(new BorderLayout(6, 4)); bottom.setOpaque(false);
		bottom.add(line, BorderLayout.CENTER); bottom.add(buttons, BorderLayout.EAST);
		JPanel under = new JPanel(new BorderLayout()); under.setOpaque(false);
		said.setForeground(GameShell.DIM);
		under.add(said, BorderLayout.CENTER); under.add(sound, BorderLayout.EAST);
		bottom.add(under, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH);
		empty("Looking for rooms…");
	}
	private static String nz(String s) { return s == null ? "" : s; }
	/** The room being read ("" for none). */
	public String chosen() { return chosen; }
	/** Shows what is told under the line. */
	public void tell(String text) { SwingUtilities.invokeLater(() -> said.setText(text == null || text.isEmpty() ? " " : text)); }
	/** Looks again now (when its page is opened). */
	public void poke() { Thread t = new Thread(this::round, "chat-now"); t.setDaemon(true); t.start(); }
	/** After a room was emptied: shows it afresh. */
	public void reset() { shown = 0; SwingUtilities.invokeLater(() -> { list.removeAll(); list.revalidate(); list.repaint(); }); poke(); }
	/** Testing: one look, on this thread. */
	public void roundNow() { round(); }

	@Override public void addNotify() {
		super.addNotify();
		if (loop != null) return;
		loop = new Thread(() -> {
			while (true) {
				try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
				java.awt.Window w = SwingUtilities.getWindowAncestor(this);
				if (w != null && !w.isDisplayable()) return;
				round();
			}
		}, "chat");
		loop.setDaemon(true);
		if (System.getenv("OJ_POPUP_PNG") == null && System.getenv("OJ_PANEL_PNG") == null) loop.start();
		poke();
	}

	// ---------------------------------------------------------------- the looking

	private long seen(String key) { try { return Long.parseLong(nz(store.get("seen:" + key)).trim()); } catch (NumberFormatException e) { return 0; } }
	private synchronized void round() {
		if (polling) return;
		polling = true;
		try {
			List<Room> got;
			try { got = backend.rooms(); } catch (Exception e) { got = null; }
			if (got != null) rooms = got;
			List<Room> now = rooms;
			boolean reading = onScreen.getAsBoolean();
			// (a sound for a message that came in a room not being read, after the first look)
			boolean ding = false;
			for (Room r : now) {
				Long was = heard.put(r.key(), r.last());
				if (!first && was != null && r.last() > was && (!reading || !r.key().equals(chosen))) ding = true;
			}
			first = false;
			if (ding && sound.isSelected()) Chime.message();
			if (chosen.isEmpty() || now.stream().noneMatch(r -> r.key().equals(chosen))) { if (!now.isEmpty()) choose(now.get(0).key(), false); }
			Room r = room(chosen);
			Batch b = null;
			if (r != null && reading) { try { b = backend.talk(r, shown, null); } catch (Exception e) { b = null; } }
			Batch bb = b;
			List<Room> rs = now;
			Runnable ui = () -> { cards(rs); if (bb != null) take(r, bb); else if (rs.isEmpty()) empty("No rooms yet. A room is there for each Gatekeeper and each of its servers this device is let onto."); count(); };
			if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
		} finally { polling = false; }
	}
	private Room room(String key) { for (Room r : rooms) if (r.key().equals(key)) return r; return null; }
	private void count() {
		int n = 0;
		for (Room r : rooms) if (r.last() > seen(r.key()) && !(r.key().equals(chosen) && onScreen.getAsBoolean())) n++;
		unread.accept(n);
		for (RoomCard c : cards.values()) c.repaint();
	}
	private void choose(String key, boolean look) {
		if (key.equals(chosen)) return;
		chosen = key; shown = 0;
		store.put("chosen", key);
		Runnable ui = () -> { list.removeAll(); list.revalidate(); list.repaint(); for (RoomCard c : cards.values()) c.repaint(); };
		if (SwingUtilities.isEventDispatchThread()) ui.run(); else SwingUtilities.invokeLater(ui);
		if (look) poke();
	}
	private void take(Room r, Batch b) {
		if (r == null || !r.key().equals(chosen)) return;
		if (shown == 0) list.removeAll();
		boolean atEnd = scroll.getVerticalScrollBar().getValue() + scroll.getVerticalScrollBar().getVisibleAmount() >= scroll.getVerticalScrollBar().getMaximum() - 24;
		for (Msg m : b.msgs()) list.add(message(r, m));
		while (list.getComponentCount() > 200) list.remove(0);
		if (list.getComponentCount() == 0) empty("Nothing said here yet.");
		shown = Math.max(shown, b.next());
		if (b.next() > seen(r.key())) store.put("seen:" + r.key(), String.valueOf(b.next()));
		if (b.said() != null && !b.said().isEmpty()) said.setText(b.said());
		list.revalidate(); list.repaint();
		if (atEnd || !b.msgs().isEmpty()) SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()));
		count();
	}
	private void empty(String text) {
		if (list.getComponentCount() > 0 && !(list.getComponent(0) instanceof JLabel)) return;
		list.removeAll();
		JLabel l = new JLabel(text); l.setForeground(GameShell.DIM); l.setBorder(BorderFactory.createEmptyBorder(8, 2, 8, 2)); l.setAlignmentX(LEFT_ALIGNMENT);
		list.add(l); list.revalidate(); list.repaint();
	}
	private void say() {
		String t = line.getText().trim();
		Room r = room(chosen);
		if (t.isEmpty() || r == null) return;
		line.setText("");
		Thread th = new Thread(() -> {
			try { Batch b = backend.talk(r, shown, t); SwingUtilities.invokeLater(() -> { take(r, b); said.setText(b.said() == null || b.said().isEmpty() ? " " : b.said()); }); }
			catch (Exception e) { tell("Not sent: " + why(e)); SwingUtilities.invokeLater(() -> { if (line.getText().isEmpty()) line.setText(t); }); }
		}, "chat-say");
		th.setDaemon(true); th.start();
	}
	private static String why(Exception e) { return e.getMessage() == null ? "the Gatekeeper isn't answering" : e.getMessage(); }

	// ---------------------------------------------------------------- the room cards

	private void cards(List<Room> rs) {
		List<String> keys = new ArrayList<>();
		for (Room r : rs) keys.add(r.key());
		if (!keys.equals(new ArrayList<>(cards.keySet()))) {
			strip.removeAll(); cards.clear();
			boolean groups = rs.stream().map(Room::group).distinct().count() > 1;
			for (Room r : rs) { RoomCard c = new RoomCard(r, groups); cards.put(r.key(), c); strip.add(c); }
			strip.revalidate(); strip.repaint();
		} else for (Room r : rs) cards.get(r.key()).room = r;
	}
	private final class RoomCard extends JButton {
		Room room; final boolean group; Image pic; boolean asked;
		RoomCard(Room r, boolean group) {
			room = r; this.group = group;
			setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false); setRolloverEnabled(true);
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			setToolTipText(group ? r.group() + ": " + r.name() : r.name());
			getAccessibleContext().setAccessibleName(r.name());
			addActionListener(e -> choose(room.key(), true));
		}
		@Override public Dimension getPreferredSize() { return new Dimension(216, 58); }
		@Override protected void paintComponent(Graphics g0) {
			if (!asked && room.picture() != null) {
				asked = true;
				Thread t = new Thread(() -> { Image i = null; try { i = room.picture().get(); } catch (RuntimeException ignored) {} Image got = i; SwingUtilities.invokeLater(() -> { pic = got; repaint(); }); }, "room-picture");
				t.setDaemon(true); t.start();
			}
			Graphics2D g = GameShell.smooth(g0);
			boolean sel = room.key().equals(chosen), over = getModel().isRollover();
			int w = getWidth(), h = getHeight();
			g.setColor(sel ? new Color(0x2B4563) : over ? new Color(0x26394F) : new Color(0x22344A));
			g.fill(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 12, 12));
			g.setStroke(new BasicStroke(sel || isFocusOwner() ? 2f : 1f));
			g.setColor(sel ? GameShell.ACC : isFocusOwner() ? GameShell.a(GameShell.ACC, 190) : GameShell.a(GameShell.TEXT, 30));
			g.draw(new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 12, 12));
			int s = h - 16, x = 8, y = 8;
			java.awt.Shape clip = g.getClip();
			g.clip(new RoundRectangle2D.Double(x, y, s, s, 9, 9));
			if (pic != null) g.drawImage(pic, x, y, s, s, null);
			else {
				int hue = room.name().hashCode();
				g.setColor(Color.getHSBColor(((hue & 0xffff) % 360) / 360f, 0.5f, 0.45f)); g.fillRect(x, y, s, s);
				String in = initials(room.name());
				g.setFont(GameShell.font(15f, true)); g.setColor(Color.WHITE);
				g.drawString(in, x + s / 2f - g.getFontMetrics().stringWidth(in) / 2f, y + s / 2f + 5.5f);
			}
			g.setClip(clip);
			int tx = x + s + 10, room2 = w - tx - 8;
			long n = room.last() > seen(room.key()) && !(sel && onScreen.getAsBoolean()) ? Math.max(1, room.last() - seen(room.key())) : 0;
			if (n > 0) room2 -= 26;
			g.setFont(GameShell.font(14f, true)); g.setColor(GameShell.TEXT);
			g.drawString(fit(g, room.name(), room2), tx, group ? 26 : 34);
			if (group) { g.setFont(GameShell.font(12f, false)); g.setColor(GameShell.DIM); g.drawString(fit(g, room.group(), room2), tx, 43); }
			if (n > 0) {
				String t = n > 99 ? "99" : String.valueOf(n);
				g.setColor(GameShell.RED); g.fill(new Ellipse2D.Double(w - 30, h / 2.0 - 10, 20, 20));
				g.setFont(GameShell.font(11.5f, true)); g.setColor(Color.WHITE); g.drawString(t, w - 20 - g.getFontMetrics().stringWidth(t) / 2f, h / 2f + 4.5f);
			}
			g.dispose();
		}
	}
	private static String initials(String name) {
		StringBuilder sb = new StringBuilder();
		for (String p : name.trim().split("\\s+")) if (!p.isEmpty() && Character.isLetterOrDigit(p.charAt(0)) && sb.length() < 2) sb.append(Character.toUpperCase(p.charAt(0)));
		return sb.length() == 0 ? "#" : sb.toString();
	}
	private static String fit(Graphics2D g, String s, int w) {
		if (g.getFontMetrics().stringWidth(s) <= w) return s;
		while (s.length() > 1 && g.getFontMetrics().stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
		return s + "…";
	}

	// ---------------------------------------------------------------- a message

	/** What kind of file a name is: picture, video, sound or file. */
	public static String kind(String name) {
		String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
		if (n.matches(".*\\.(png|jpe?g|gif|bmp)$")) return "picture";
		if (n.matches(".*\\.(webp|heic|avif|tiff?)$")) return "picture file";
		if (n.matches(".*\\.(mp4|webm|mov|mkv|avi|m4v)$")) return "video";
		if (n.matches(".*\\.(mp3|ogg|oga|wav|m4a|flac|aac|opus)$")) return "sound";
		return "file";
	}
	static String size(long n) { return n < 1024 ? n + " bytes" : n < 1048576 ? (n / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0); }

	private JComponent message(Room r, Msg m) {
		JPanel p = new JPanel(new BorderLayout(0, 3)); p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		p.setAlignmentX(LEFT_ALIGNMENT);
		JLabel head = new JLabel("<html><b>" + esc(m.who()) + "</b>&nbsp;&nbsp;<span style='color:#8F98A0'>" + esc(m.when()) + "</span></html>");
		head.setForeground(GameShell.TEXT);
		p.add(head, BorderLayout.NORTH);
		if (m.fileId() == null || m.fileId().isEmpty()) {
			JTextArea t = new JTextArea(m.text()); t.setEditable(false); t.setLineWrap(true); t.setWrapStyleWord(true); t.setOpaque(false);
			t.setForeground(GameShell.TEXT); t.setBorder(null); t.setFocusable(true);
			p.add(t, BorderLayout.CENTER);
		} else p.add(attachment(r, m), BorderLayout.CENTER);
		return p;
	}
	private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;"); }
	private JComponent attachment(Room r, Msg m) {
		String k = kind(m.fileName());
		JPanel box = new JPanel(new BorderLayout(0, 6)); box.setOpaque(false);
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); row.setOpaque(false);
		JLabel what = new JLabel("offered a " + (k.equals("picture file") ? "picture" : k) + ":  " + m.fileName() + "  (" + size(m.fileSize()) + ")");
		what.setForeground(GameShell.TEXT);
		JButton view = new JButton(k.equals("picture") ? "View" : "Open"), keep = new JButton("Save a copy…");
		view.setToolTipText(k.equals("picture") ? "Fetches the picture and shows it here. Nothing is fetched before you press this." : "Fetches it and opens it with this device's own program for it");
		keep.setToolTipText("Fetches it and saves it where you choose");
		row.add(what); row.add(view); row.add(keep);
		box.add(row, BorderLayout.NORTH);
		JLabel shownPic = new JLabel(); box.add(shownPic, BorderLayout.CENTER);
		view.addActionListener(e -> {
			view.setEnabled(false);
			fetch(r, m, bytes -> {
				if (k.equals("picture")) {
					try {
						BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
						if (img == null) throw new java.io.IOException("not a picture this can show");
						double sc = Math.min(1.0, Math.min(460.0 / img.getWidth(), 300.0 / img.getHeight()));
						Image small = img.getScaledInstance(Math.max(1, (int) (img.getWidth() * sc)), Math.max(1, (int) (img.getHeight() * sc)), Image.SCALE_SMOOTH);
						shownPic.setIcon(new javax.swing.ImageIcon(small));
						view.setText("Open"); view.setEnabled(true);
						for (java.awt.event.ActionListener al : view.getActionListeners()) view.removeActionListener(al);
						view.setToolTipText("Opens it full size with this device's picture viewer");
						view.addActionListener(e2 -> open(m, bytes));
						box.revalidate(); list.revalidate();
					} catch (java.io.IOException ex) { open(m, bytes); view.setEnabled(true); }
				} else { open(m, bytes); view.setEnabled(true); }
			}, () -> view.setEnabled(true));
		});
		keep.addActionListener(e -> {
			javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
			fc.setSelectedFile(new java.io.File(m.fileName()));
			if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
			java.io.File to = fc.getSelectedFile();
			fetch(r, m, bytes -> { try { Files.write(to.toPath(), bytes); tell("Saved: " + to); } catch (java.io.IOException ex) { tell("Not saved: " + ex.getMessage()); } }, () -> {});
		});
		return box;
	}
	private void fetch(Room r, Msg m, java.util.function.Consumer<byte[]> then, Runnable failed) {
		tell("Fetching " + m.fileName() + "…");
		Thread t = new Thread(() -> {
			try {
				byte[] b = backend.file(r, m.fileId());
				if (b == null) throw new java.io.IOException("it is no longer kept (a file is kept for a day)");
				tell(" ");
				SwingUtilities.invokeLater(() -> then.accept(b));
			} catch (Exception e) { tell("Couldn't fetch " + m.fileName() + ": " + why(e)); SwingUtilities.invokeLater(failed); }
		}, "chat-file");
		t.setDaemon(true); t.start();
	}
	/** Opens a fetched file with this device's own program for it (from a folder of its own in the temporary files). */
	private void open(Msg m, byte[] bytes) {
		try {
			Path d = Path.of(System.getProperty("java.io.tmpdir"), "gatehouse-chat");
			Files.createDirectories(d);
			String safe = m.fileName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
			Path f = d.resolve(m.fileId().replaceAll("[^A-Za-z0-9\\-]", "") + "-" + safe);
			Files.write(f, bytes);
			if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) java.awt.Desktop.getDesktop().open(f.toFile());
			else new ProcessBuilder("xdg-open", f.toString()).start();
		} catch (Exception e) { tell("Couldn't open it here: " + why(e) + ". Save a copy instead."); }
	}

	// ---------------------------------------------------------------- paste and attach

	/** Paste: words into the line; a picture or copied files offered to the room. True when something was offered. */
	private boolean paste(boolean wordsToo) {
		Room r = room(chosen);
		Transferable t;
		try { t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null); } catch (IllegalStateException e) { tell("The clipboard is busy: try again."); return false; }
		if (t == null) { if (wordsToo) tell("The clipboard is empty."); return false; }
		try {
			if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
				@SuppressWarnings("unchecked") List<java.io.File> fs = (List<java.io.File>) t.getTransferData(DataFlavor.javaFileListFlavor);
				if (r == null) { tell("Choose a room first."); return true; }
				for (java.io.File f : fs) if (f.isFile()) offer(r, f.getName(), f.toPath());
				return true;
			}
			if (t.isDataFlavorSupported(DataFlavor.imageFlavor)) {
				Image img = (Image) t.getTransferData(DataFlavor.imageFlavor);
				if (r == null) { tell("Choose a room first."); return true; }
				BufferedImage b = new BufferedImage(Math.max(1, img.getWidth(null)), Math.max(1, img.getHeight(null)), BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = b.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose();
				ByteArrayOutputStream o = new ByteArrayOutputStream();
				javax.imageio.ImageIO.write(b, "png", o);
				offerBytes(r, "pasted picture " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")) + ".png", o.toByteArray());
				return true;
			}
			if (wordsToo && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
				String s = (String) t.getTransferData(DataFlavor.stringFlavor);
				line.replaceSelection(s); line.requestFocusInWindow();
				return true;
			}
		} catch (Exception e) { tell("Couldn't take that from the clipboard: " + why(e)); return true; }
		if (wordsToo) tell("Nothing on the clipboard that can go in a chat. A video or sound works when you copy the file itself.");
		return false;
	}
	private void attach() {
		Room r = room(chosen);
		if (r == null) { tell("Choose a room first."); return; }
		javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
		fc.setMultiSelectionEnabled(true);
		fc.setDialogTitle("Offer files to " + r.name());
		if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
		for (java.io.File f : fc.getSelectedFiles()) offer(r, f.getName(), f.toPath());
	}
	private void offer(Room r, String name, Path f) {
		try { if (Files.size(f) > 512L * 1024 * 1024) { tell(name + " is too big to offer from here."); return; } offerBytes(r, name, Files.readAllBytes(f)); }
		catch (java.io.IOException e) { tell(name + " couldn't be read: " + e.getMessage()); }
	}
	private void offerBytes(Room r, String name, byte[] bytes) {
		tell("Offering " + name + " (" + size(bytes.length) + ")…");
		Thread th = new Thread(() -> {
			try { tell(backend.offer(r, name, bytes)); } catch (Exception e) { tell(name + " wasn't offered: " + why(e)); }
			poke();
		}, "chat-offer");
		th.setDaemon(true); th.start();
	}

	/** A panel as wide as the place it is shown in, so long lines wrap rather than run off. */
	private static final class Track extends JPanel implements Scrollable {
		@Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
		@Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
		@Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
		@Override public boolean getScrollableTracksViewportWidth() { return true; }
		@Override public boolean getScrollableTracksViewportHeight() { return false; }
	}
}
