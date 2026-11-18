package dev.gatehouse.look;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** A setup guide both programs show: the steps down the left, each with a mark for how it stands, and one step at a
 *  time on the right with what to do, buttons that do or open it, and Back and Next. The steps are asked for again
 *  every few seconds while the guide is on screen, so a step ticks by itself once it's done (in this program or
 *  somewhere else), and the guide says so.
 *  A step may have a picture: a file /wizard/&lt;step key&gt;.png inside the program is shown above its text when it
 *  is there (none are yet). */
public final class Wizard extends JPanel {
	/** One step. state: "ok" (done), "todo" (not done), "warn" (works, but worth changing), "info" (nothing to check),
	 *  "wait" (an earlier step comes first). actions: {what the button says, what to tell the program}, any number. */
	public static final class Step {
		public final String key, title, text, state, detail;
		public final List<String[]> actions = new ArrayList<>();
		public Step(String key, String title, String state, String text,
				String detail) { this.key = key; this.title = title; this.state = state; this.text = text; this.detail = detail == null ? "" : detail; }
		public Step action(String label, String id) { actions.add(new String[] {label, id}); return this; }
		String signature() { StringBuilder s = new StringBuilder(key + "|" + title + "|" + state + "|" + text + "|"
				+ detail); for (String[] a : actions) s.append('|').append(a[0]).append('>').append(a[1]); return s.toString(); }
	}

	private final Supplier<List<Step>> load;
	private final Consumer<String> act;
	private final Class<?> artFrom;
	private final JPanel list = new JPanel(), page = new JPanel(new BorderLayout(0, 10));
	private final JLabel count = new JLabel(" "), looked = new JLabel(" ");
	private final JButton back = new JButton("‹ Back"), next = new JButton("Next ›"), close = new JButton("Close the guide");
	private List<Step> steps = new ArrayList<>();
	private String at = null, drawn = "";
	private boolean loading, placed;
	private final Timer timer;
	private static final int SIDE = 250;
	private int textWidth = 360;

	/** load: the steps as they stand now (called off the window's own thread, every three seconds while showing).
	 *  act: told a button's id when it's pressed. closed: when the guide is closed. artFrom: the program's own class,
	 *  to find its pictures. */
	public Wizard(Supplier<List<Step>> load, Consumer<String> act, Runnable closed, Class<?> artFrom) {
		super(new BorderLayout(14, 0));
		this.load = load; this.act = act; this.artFrom = artFrom;
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		JPanel listTop = new JPanel(new BorderLayout()); listTop.add(list, BorderLayout.NORTH);
		JScrollPane ls = new JScrollPane(listTop);
		ls.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, SteamLook.LINE));
		ls.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		ls.setPreferredSize(new Dimension(SIDE, 100));
		ls.getVerticalScrollBar().setUnitIncrement(16);
		add(ls, BorderLayout.WEST);
		JPanel right = new JPanel(new BorderLayout(0, 8));
		JScrollPane ps = new JScrollPane(page);
		ps.setBorder(null);
		ps.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		ps.getVerticalScrollBar().setUnitIncrement(16);
		right.add(ps, BorderLayout.CENTER);
		looked.setForeground(SteamLook.DIM);
		JPanel nav = new JPanel(new BorderLayout());
		JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); navLeft.add(close); navLeft.add(looked);
		JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); navRight.add(count); navRight.add(back); navRight.add(next);
		nav.add(navLeft, BorderLayout.WEST); nav.add(navRight, BorderLayout.EAST);
		right.add(nav, BorderLayout.SOUTH);
		add(right, BorderLayout.CENTER);
		back.addActionListener(e -> move(-1));
		next.addActionListener(e -> { if (index() >= steps.size() - 1) closed.run(); else move(1); });
		close.addActionListener(e -> closed.run());
		timer = new Timer(3000, e -> { if (isShowing()) refresh(); });
		// (the text is as wide as the room beside the list: laid out again when the window's size changes)
		addComponentListener(new java.awt.event.ComponentAdapter() {
			@Override public void componentResized(java.awt.event.ComponentEvent e) { if (fit()) { drawn = ""; draw(); } }
		});
	}

	/** Shows the guide from where things stand: at the first step that isn't done yet. */
	public void open() { placed = false; at = null; drawn = ""; refresh(); timer.start(); }
	public void stop() { timer.stop(); }
	/** For a picture of it (testing): the steps are loaded on this thread, and it stands at the given step (or the first not done). */
	public void openNow(String key) { steps = load.get(); placed = true; at = key; if (key == null
			|| index() < 0) at = firstOpen(); java.awt.Window w = SwingUtilities.getWindowAncestor(this); if (w != null) w.validate(); fit(); drawn = ""; draw(); }

	private boolean fit() {
		// (in Swing's HTML a "px" is 1.3 screen pixels, so the room is divided by that)
		int w = getWidth() <= 0 ? 360 : Math.max(230, Math.min(600, (int) ((getWidth() - SIDE - 70) / 1.3)));
		if (w == textWidth) return false;
		textWidth = w;
		return true;
	}
	private int index() { for (int i = 0; i < steps.size(); i++) if (steps.get(i).key.equals(at)) return i; return -1; }
	private String firstOpen() {
		for (Step s : steps) if (s.state.equals("todo") || s.state.equals("warn")) return s.key;
		return steps.isEmpty() ? null : steps.get(steps.size() - 1).key;
	}
	private void move(int by) { int i = index() + by; if (i >= 0 && i < steps.size()) { at = steps.get(i).key; draw(); } }

	private void refresh() {
		if (loading) return;
		loading = true;
		new Thread(() -> {
			List<Step> got;
			try { got = load.get(); } catch (RuntimeException e) { got = null; }
			List<Step> now = got;
			SwingUtilities.invokeLater(() -> {
				loading = false;
				if (now == null) { looked.setText("Couldn't look just now."); return; }
				steps = now;
				if (!placed || index() < 0) { placed = true; at = firstOpen(); }
				looked.setText("Looks again by itself every few seconds.");
				draw();
			});
		}, "wizard").start();
	}

	private static String colour(String state) { return state.equals("ok") ? "#3FB950" : state.equals("warn") ? "#E3B341"
			: state.equals("todo") ? "#F85149" : "#A9B5C1"; }
	private static String word(String state) { return state.equals("ok") ? "Done" : state.equals("warn") ? "Works, but worth changing"
			: state.equals("todo") ? "Not done yet" : state.equals("wait") ? "An earlier step comes first" : ""; }
	private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>"); }

	private void draw() {
		StringBuilder sig = new StringBuilder(String.valueOf(at));
		for (Step s : steps) sig.append('\n').append(s.signature());
		if (sig.toString().equals(drawn)) return;   // (nothing changed: nothing flickers)
		drawn = sig.toString();
		int i = index();
		list.removeAll();
		for (int n = 0; n < steps.size(); n++) {
			Step s = steps.get(n);
			JButton b = new JButton("<html><span style='color:" + colour(s.state) + "'>●</span>&nbsp; " + (n == i ? "<b>" + esc(s.title)
					+ "</b>" : esc(s.title)) + "</html>");
			b.setHorizontalAlignment(JButton.LEFT);
			b.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			b.setContentAreaFilled(n == i); b.setFocusPainted(false);
			b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
			b.setAlignmentX(Component.LEFT_ALIGNMENT);
			String key = s.key;
			b.addActionListener(e -> { at = key; draw(); });
			list.add(b);
		}
		page.removeAll();
		if (i >= 0) {
			Step s = steps.get(i);
			JPanel body = new JPanel();
			body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
			body.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			JLabel title = new JLabel(s.title);
			title.setFont(title.getFont().deriveFont(Font.BOLD, dev.gatehouse.look.SteamLook.pt(18f)));
			boolean na = s.state.equals("na");   // (this system can't do it: its detail is what is said in place of a state)
			String says = na ? esc(s.detail.isEmpty() ? "Not supported here" : s.detail) : word(s.state);
			JLabel mark = new JLabel(says.isEmpty() ? " " : "<html><span style='color:" + colour(s.state) + "'>● &nbsp;<b>" + says + "</b></span>" + "</html>");
			mark.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
			body.add(title); body.add(mark);
			if (!s.detail.isEmpty() && !na) { JLabel d = new JLabel("<html><div style='width:" + textWidth + "px; color:#A9B5C1'>"
					+ esc(s.detail) + "</div></html>"); d.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0)); body.add(d); }
			java.net.URL art = artFrom == null ? null : artFrom.getResource("/wizard/" + (artFrom.getPackageName().endsWith(".host")
					? "gatekeeper" : "gatehouse") + "/" + s.key + ".png");
			if (art != null) { JLabel pic = new JLabel(new ImageIcon(art)); pic.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0)); body.add(pic); }
			JLabel text = new JLabel("<html><div style='width:" + textWidth + "px'>" + esc(s.text) + "</div></html>");
			body.add(text);
			if (!s.actions.isEmpty()) {
				JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
				for (String[] a : s.actions) { JButton b = new JButton(a[0]); String id = a[1]; b.addActionListener(e -> act.accept(id)); row.add(b); row.add(Box.createHorizontalStrut(8)); }
				row.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
				body.add(row);
			}
			for (Component c : body.getComponents()) ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
			page.add(body, BorderLayout.NORTH);
			count.setText("Step " + (i + 1) + " of " + steps.size());
			back.setEnabled(i > 0);
			next.setText(i >= steps.size() - 1 ? "Finish" : "Next ›");
		}
		count.setForeground(SteamLook.DIM);
		list.revalidate(); list.repaint(); page.revalidate(); page.repaint();
	}

	/** A dim note for callers that want one. */
	public static JLabel note(String text) { JLabel l = new JLabel(text); l.setForeground(new Color(0xA9B5C1)); return l; }
}
