package dev.gatehouse.look;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.beans.PropertyChangeListener;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * R97: questions and messages shown inside the program's own window, not in a window of their own. Each takes the
 * same arguments and gives the same answers as Swing's JOptionPane (and JFileChooser's open and save), so the rest of
 * the code is unchanged: the same box, drawn on a dimmed layer over the window it belongs to, and waited for the same
 * way (the window keeps drawing meanwhile). Esc or the controller's B answers as Cancel or closing would. Where there is
 * no showing window to put it in (or it is asked off Swing's thread), it falls back to Swing's own window.
 *
 * The layer is marked on the window ("navModal"), so the controller moves only among its buttons while it is shown.
 */
public final class InWindow {
	private InWindow() {}

	// ---- JOptionPane's ways, the same answers
	public static void showMessageDialog(Component parent, Object message) { showMessageDialog(parent, message, "Message", JOptionPane.INFORMATION_MESSAGE); }
	public static void showMessageDialog(Component parent, Object message, String title, int messageType) { showMessageDialog(parent, message, title, messageType, null); }
	public static void showMessageDialog(Component parent, Object message, String title, int messageType, Icon icon) {
		JOptionPane p = new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION, icon);
		if (!show(parent, p, title)) JOptionPane.showMessageDialog(parent, message, title, messageType, icon);
	}
	public static int showConfirmDialog(Component parent, Object message) { return showConfirmDialog(parent, message, "Select an Option", JOptionPane.YES_NO_CANCEL_OPTION); }
	public static int showConfirmDialog(Component parent, Object message, String title, int optionType) { return showConfirmDialog(parent, message, title, optionType, JOptionPane.QUESTION_MESSAGE); }
	public static int showConfirmDialog(Component parent, Object message, String title, int optionType, int messageType) { return showConfirmDialog(parent, message, title, optionType, messageType, null); }
	public static int showConfirmDialog(Component parent, Object message, String title, int optionType, int messageType, Icon icon) {
		JOptionPane p = new JOptionPane(message, messageType, optionType, icon);
		if (!show(parent, p, title)) return JOptionPane.showConfirmDialog(parent, message, title, optionType, messageType, icon);
		Object v = p.getValue();
		return v instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
	}
	public static int showOptionDialog(Component parent, Object message, String title, int optionType, int messageType, Icon icon, Object[] options, Object initial) {
		JOptionPane p = new JOptionPane(message, messageType, optionType, icon, options, initial);
		p.setInitialValue(initial);
		if (!show(parent, p, title)) return JOptionPane.showOptionDialog(parent, message, title, optionType, messageType, icon, options, initial);
		Object v = p.getValue();
		if (v == null || v == JOptionPane.UNINITIALIZED_VALUE) return JOptionPane.CLOSED_OPTION;
		if (options == null) return v instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
		for (int i = 0; i < options.length; i++) if (options[i].equals(v)) return i;
		return JOptionPane.CLOSED_OPTION;
	}
	public static String showInputDialog(Component parent, Object message) { return (String) showInputDialog(parent, message, "Input", JOptionPane.QUESTION_MESSAGE, null, null, null); }
	public static String showInputDialog(Component parent, Object message, String title, int messageType) { return (String) showInputDialog(parent, message, title, messageType, null, null, null); }
	public static Object showInputDialog(Component parent, Object message, String title, int messageType, Icon icon, Object[] values, Object initial) {
		JOptionPane p = new JOptionPane(message, messageType, JOptionPane.OK_CANCEL_OPTION, icon);
		p.setWantsInput(true); p.setSelectionValues(values); p.setInitialSelectionValue(initial);
		if (!show(parent, p, title)) return JOptionPane.showInputDialog(parent, message, title, messageType, icon, values, initial);
		Object v = p.getInputValue();
		return v == JOptionPane.UNINITIALIZED_VALUE || !(p.getValue() instanceof Integer i && i == JOptionPane.OK_OPTION) ? null : v;
	}

	// ---- JFileChooser's open and save, the same answers
	public static int showOpenDialog(JFileChooser fc, Component parent) { return chooser(fc, parent, JFileChooser.OPEN_DIALOG); }
	public static int showSaveDialog(JFileChooser fc, Component parent) { return chooser(fc, parent, JFileChooser.SAVE_DIALOG); }
	private static int chooser(JFileChooser fc, Component parent, int type) {
		Layer l = layer(parent);
		if (l == null) return type == JFileChooser.OPEN_DIALOG ? fc.showOpenDialog(parent) : fc.showSaveDialog(parent);
		fc.setDialogType(type);
		int[] answer = {JFileChooser.CANCEL_OPTION};
		SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		java.awt.event.ActionListener al = e -> {
			answer[0] = JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand()) ? JFileChooser.APPROVE_OPTION : JFileChooser.CANCEL_OPTION;
			loop.exit();
		};
		fc.addActionListener(al);
		fc.rescanCurrentDirectory();
		String title = fc.getDialogTitle() != null ? fc.getDialogTitle() : type == JFileChooser.OPEN_DIALOG ? "Open" : "Save";
		l.open(fc, title, () -> { answer[0] = JFileChooser.CANCEL_OPTION; loop.exit(); }, loop);
		fc.removeActionListener(al);
		return answer[0];
	}

	/** Shows a JOptionPane on a layer in its window and waits for its answer; false when there is no window for it. */
	private static boolean show(Component parent, JOptionPane p, String title) {
		Layer l = layer(parent);
		if (l == null) return false;
		SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		PropertyChangeListener pl = e -> { if (JOptionPane.VALUE_PROPERTY.equals(e.getPropertyName()) && e.getNewValue() != JOptionPane.UNINITIALIZED_VALUE) loop.exit(); };
		p.addPropertyChangeListener(pl);
		l.open(p, title, () -> p.setValue(null), loop);
		p.removePropertyChangeListener(pl);
		return true;
	}

	/** Where a box for this parent goes, or null: on Swing's thread, in a window that is showing. */
	private static Layer layer(Component parent) {
		if (java.awt.GraphicsEnvironment.isHeadless() || !SwingUtilities.isEventDispatchThread() || Boolean.getBoolean("gatehouse.popups")) return null;
		Window w = parent == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
				: parent instanceof Window pw ? pw : SwingUtilities.getWindowAncestor(parent);
		if (!(w instanceof RootPaneContainer rp) || !w.isShowing()) return null;
		JLayeredPane lp = rp.getLayeredPane();
		if (lp.getWidth() < 200 || lp.getHeight() < 150) return null;   // (too small to hold it: a window of its own)
		return new Layer(w, rp, lp);
	}

	/** The dimmed layer over the window, with the box in its middle. */
	private record Layer(Window w, RootPaneContainer rp, JLayeredPane lp) {
		void open(JComponent content, String title, Runnable cancel, SecondaryLoop loop) {
			Component before = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
			Object outer = rp.getRootPane().getClientProperty("navModal");
			JPanel scrim = new JPanel(new GridBagLayout()) {
				@Override protected void paintComponent(Graphics g) { g.setColor(new Color(0, 0, 0, 150)); g.fillRect(0, 0, getWidth(), getHeight()); }
			};
			scrim.setOpaque(false);
			scrim.addMouseListener(new java.awt.event.MouseAdapter() {});   // (clicks don't reach the window beneath)
			scrim.addMouseWheelListener(e -> {});
			JPanel card = new JPanel(new BorderLayout(0, 8));
			card.setBackground(SteamLook.BACK);
			card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SteamLook.LINE, 1), BorderFactory.createEmptyBorder(12, 16, 14, 16)));
			if (title != null && !title.isEmpty()) {
				JLabel t = new JLabel(title); t.setForeground(SteamLook.TEXT); t.setFont(t.getFont().deriveFont(java.awt.Font.BOLD, t.getFont().getSize2D() + 2f));
				card.add(t, BorderLayout.NORTH);
			}
			content.setOpaque(false);
			card.add(content, BorderLayout.CENTER);
			card.setFocusCycleRoot(true);
			// (Esc answers as closing the box would)
			card.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "inWindowClose");
			card.getActionMap().put("inWindowClose", new javax.swing.AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { cancel.run(); } });
			// (a box taller or wider than the window scrolls)
			java.awt.Dimension pref = card.getPreferredSize();
			int maxW = lp.getWidth() - 40, maxH = lp.getHeight() - 40;
			Component shown = card;
			if (pref.width > maxW || pref.height > maxH) {
				javax.swing.JScrollPane sp = new javax.swing.JScrollPane(card);
				sp.setBorder(null); sp.getVerticalScrollBar().setUnitIncrement(16);
				sp.setPreferredSize(new java.awt.Dimension(Math.min(pref.width + 18, maxW), Math.min(pref.height + 4, maxH)));
				shown = sp;
			}
			scrim.add(shown);
			scrim.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			java.awt.event.ComponentAdapter follow = new java.awt.event.ComponentAdapter() {
				@Override public void componentResized(java.awt.event.ComponentEvent e) { scrim.setBounds(0, 0, lp.getWidth(), lp.getHeight()); scrim.revalidate(); }
			};
			lp.addComponentListener(follow);
			java.awt.event.WindowAdapter gone = new java.awt.event.WindowAdapter() {
				@Override public void windowClosed(java.awt.event.WindowEvent e) { cancel.run(); loop.exit(); }
				@Override public void windowClosing(java.awt.event.WindowEvent e) { cancel.run(); }
			};
			w.addWindowListener(gone);
			lp.add(scrim, Integer.valueOf(JLayeredPane.MODAL_LAYER + depth(lp)));
			rp.getRootPane().putClientProperty("navModal", card);
			lp.revalidate(); lp.repaint();
			SwingUtilities.invokeLater(() -> {
				if (content instanceof JOptionPane op) op.selectInitialValue();
				else { Component f = card.getFocusTraversalPolicy().getDefaultComponent(card); if (f != null) f.requestFocusInWindow(); }
			});
			loop.enter();
			w.removeWindowListener(gone);
			lp.removeComponentListener(follow);
			lp.remove(scrim);
			rp.getRootPane().putClientProperty("navModal", outer);
			lp.revalidate(); lp.repaint();
			if (before != null && before.isShowing()) before.requestFocusInWindow();
		}
		/** One box over another (a question asked from a box) goes above it. */
		private static int depth(Container lp) {
			int n = 0;
			for (Component c : lp.getComponents()) if (((JLayeredPane) lp).getLayer(c) >= JLayeredPane.MODAL_LAYER) n++;
			return n;
		}
	}

	/**
	 * R97: what used to be a window of its own (a server's pack, New server, Add a Gatekeeper, Connect, Files, Play or
	 * Host), as a sheet inside the window it was opened from: a title with a close button, its contents, a dimmed window
	 * behind. It is used like the JDialog it replaces (setContentPane, pack, setVisible, dispose, addWindowListener, which
	 * hears windowClosing and windowClosed); a modal one waits in setVisible(true) until it is closed. Where there is no
	 * showing window to put it in, it is a JDialog as before.
	 */
	public static final class Sheet extends JPanel {
		private final Component over;
		private final String title;
		private final boolean modal;
		private final JPanel body = new JPanel(new BorderLayout());
		private final java.util.List<java.awt.event.WindowListener> listeners = new java.util.ArrayList<>();
		private JPanel scrim;
		private javax.swing.JScrollPane scroller;
		private JLayeredPane lp;
		private RootPaneContainer rp;
		private Object outer;
		private Component before;
		private javax.swing.JDialog fallback;
		private SecondaryLoop loop;
		private java.awt.event.ComponentAdapter follow;
		private boolean disposed;

		public Sheet(Component over, String title, boolean modal) {
			super(new BorderLayout(0, 8));
			this.over = over; this.title = title; this.modal = modal;
			setBackground(SteamLook.BACK);
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SteamLook.LINE, 1), BorderFactory.createEmptyBorder(10, 14, 12, 14)));
			JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false);
			JLabel t = new JLabel(title); t.setForeground(SteamLook.TEXT); t.setFont(t.getFont().deriveFont(java.awt.Font.BOLD, t.getFont().getSize2D() + 2f));
			javax.swing.JButton x = new javax.swing.JButton("Close");
			x.setToolTipText("Closes this and goes back to the window (Esc)");
			x.putClientProperty("nav", "back");
			x.addActionListener(e -> close());
			head.add(t, BorderLayout.CENTER); head.add(x, BorderLayout.EAST);
			add(head, BorderLayout.NORTH);
			body.setOpaque(false);
			add(body, BorderLayout.CENTER);
			setFocusCycleRoot(true);
			getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "sheetClose");
			getActionMap().put("sheetClose", new javax.swing.AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { close(); } });
		}
		public void setContentPane(Container c) { body.removeAll(); body.add(c, BorderLayout.CENTER); if (fallback != null) fallback.setContentPane(c); revalidate(); repaint(); }
		public Container getContentPane() { return body.getComponentCount() > 0 ? (Container) body.getComponent(0) : body; }
		public void pack() { if (fallback != null) { fallback.pack(); return; } setPreferredSize(null); fit(); }
		public void setLocationRelativeTo(Component c) { if (fallback != null) fallback.setLocationRelativeTo(c); }
		public void setDefaultCloseOperation(int op) { /* (closing always disposes it) */ }
		public void addWindowListener(java.awt.event.WindowListener l) { listeners.add(l); if (fallback != null) fallback.addWindowListener(l); }
		@Override public void setSize(int w, int h) { if (fallback != null) fallback.setSize(w, h); else super.setSize(w, h); }
		@Override public void setSize(java.awt.Dimension d) { setSize(d.width, d.height); }
		/** What it asks to be: its contents' size, at least its minimum (as a window's would be). */
		@Override public java.awt.Dimension getPreferredSize() {
			java.awt.Dimension p = super.getPreferredSize();
			if (isMinimumSizeSet()) { java.awt.Dimension m = getMinimumSize(); p = new java.awt.Dimension(Math.max(p.width, m.width), Math.max(p.height, m.height)); }
			return p;
		}

		/** Its own window (a JDialog) when there is no window to put it in. */
		private boolean place() {
			if (scrim != null || fallback != null) return true;
			Window w = over == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() : over instanceof Window ow ? ow : SwingUtilities.getWindowAncestor(over);
			if (java.awt.GraphicsEnvironment.isHeadless() || Boolean.getBoolean("gatehouse.popups") || !(w instanceof RootPaneContainer r) || !w.isShowing()
					|| r.getLayeredPane().getWidth() < 300 || r.getLayeredPane().getHeight() < 200) {
				fallback = new javax.swing.JDialog(w, title, modal ? java.awt.Dialog.ModalityType.APPLICATION_MODAL : java.awt.Dialog.ModalityType.MODELESS);
				fallback.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
				Container c = getContentPane(); body.removeAll(); fallback.setContentPane(c);
				for (java.awt.event.WindowListener l : listeners) fallback.addWindowListener(l);
				fallback.pack(); fallback.setLocationRelativeTo(w);
				return true;
			}
			rp = r; lp = r.getLayeredPane();
			scrim = new JPanel(new GridBagLayout()) {
				@Override protected void paintComponent(Graphics g) { g.setColor(new Color(0, 0, 0, 150)); g.fillRect(0, 0, getWidth(), getHeight()); }
			};
			scrim.setOpaque(false);
			scrim.addMouseListener(new java.awt.event.MouseAdapter() {});
			scrim.addMouseWheelListener(e -> {});
			scroller = new javax.swing.JScrollPane(this);
			scroller.setBorder(null); scroller.setOpaque(false); scroller.getViewport().setOpaque(false);
			scroller.getVerticalScrollBar().setUnitIncrement(16);
			scrim.add(scroller);
			follow = new java.awt.event.ComponentAdapter() { @Override public void componentResized(java.awt.event.ComponentEvent e) { scrim.setBounds(0, 0, lp.getWidth(), lp.getHeight()); fit(); } };
			return true;
		}
		/** As big as its contents want, but never bigger than the window (then it scrolls). */
		private void fit() {
			if (scroller == null || lp == null) return;
			java.awt.Dimension p = getPreferredSize();
			int w = Math.min(p.width, lp.getWidth() - 32), h = Math.min(p.height, lp.getHeight() - 32);
			scroller.setPreferredSize(new java.awt.Dimension(Math.max(200, w + (p.height > lp.getHeight() - 32 ? 18 : 0)), Math.max(120, h)));
			scrim.revalidate(); scrim.repaint();
		}
		@Override public void setVisible(boolean on) {
			if (scroller == null && fallback == null && !on) { super.setVisible(false); return; }
			if (!on) { if (fallback != null) fallback.setVisible(false); else if (scrim != null && scrim.getParent() != null) take(); return; }
			if (disposed) return;
			place();
			if (fallback != null) { fallback.setVisible(true); return; }
			before = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
			outer = rp.getRootPane().getClientProperty("navModal");
			scrim.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			int n = 0; for (Component c : lp.getComponents()) if (lp.getLayer(c) >= JLayeredPane.MODAL_LAYER) n++;
			lp.add(scrim, Integer.valueOf(JLayeredPane.MODAL_LAYER + n));
			lp.addComponentListener(follow);
			rp.getRootPane().putClientProperty("navModal", this);
			fit();
			lp.revalidate(); lp.repaint();
			SwingUtilities.invokeLater(() -> { Component f = getFocusTraversalPolicy().getDefaultComponent(this); if (f != null) f.requestFocusInWindow(); });
			if (modal) { loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop(); loop.enter(); }
		}
		private void take() {
			lp.removeComponentListener(follow);
			lp.remove(scrim);
			if (rp.getRootPane().getClientProperty("navModal") == this) rp.getRootPane().putClientProperty("navModal", outer);
			lp.revalidate(); lp.repaint();
			if (before != null && before.isShowing()) before.requestFocusInWindow();
		}
		/** The close button or Esc: as a window's close box, then gone. */
		private void close() {
			Window w = fallback != null ? fallback : SwingUtilities.getWindowAncestor(this);
			if (w != null) { java.awt.event.WindowEvent e = new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSING); for (java.awt.event.WindowListener l : new java.util.ArrayList<>(listeners)) l.windowClosing(e); }
			dispose();
		}
		public void dispose() {
			if (disposed) return;
			disposed = true;
			if (fallback != null) { fallback.dispose(); return; }
			Window w = SwingUtilities.getWindowAncestor(this);
			if (scrim != null && scrim.getParent() != null) take();
			if (loop != null) loop.exit();
			if (w == null) return;   // (never shown: nobody to tell)
			java.awt.event.WindowEvent e = new java.awt.event.WindowEvent(w, java.awt.event.WindowEvent.WINDOW_CLOSED);
			for (java.awt.event.WindowListener l : new java.util.ArrayList<>(listeners)) l.windowClosed(e);
		}
	}
}
