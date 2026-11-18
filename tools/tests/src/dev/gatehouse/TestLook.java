package dev.gatehouse;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** The notes list (R18, R29): versions not seen are boxed, two or more get a summary on top, a list opens at its top,
 *  and each box is no taller than its lines need. Also the shortcut icon (R16) reads back as an icon. */
public class TestLook {
	public static void main(String[] a) throws Exception {
		Tests t = new Tests("notes and icons");
		Map<String, Object> notes = new LinkedHashMap<>();
		List<Object> ch = new ArrayList<>();
		for (int i = 5; i >= 1; i--) ch.add(Map.of("version", "9.0." + i, "date", "2026-01-0" + i, "notes", List.of("A change in 9.0." + i + ", long enough to wrap onto a second line when the list is narrow, as notes often are.", "Another line.")));
		notes.put("version", "9.0.5"); notes.put("changes", ch);
		Object[] got = new Object[4];
		SwingUtilities.invokeAndWait(() -> {
			JScrollPane sp = dev.gatehouse.look.NotesView.build(notes, "head", "9.0.2", "none");
			sp.setSize(700, 500);
			for (int k = 0; k < 3; k++) layAll(sp);
			List<JTextArea> lines = new ArrayList<>(); find(sp, lines);
			got[0] = lines.stream().anyMatch(l -> l.getText().startsWith("• 9.0.5:"));   // (the summary's line for 9.0.5)
			got[1] = sp.getVerticalScrollBar().getValue();
			// (no box taller than its parts plus its margins)
			boolean tight = true;
			for (Component c : boxes(sp)) {
				int sum = 0; for (Component k : ((Container) c).getComponents()) sum += k.getHeight();
				java.awt.Insets in = ((Container) c).getInsets();
				if (c.getHeight() > sum + in.top + in.bottom + 4) tight = false;
			}
			got[2] = tight;
			got[3] = boxes(sp).size();
		});
		t.check("a summary of what's new when two or more versions are unseen", Boolean.TRUE.equals(got[0]));
		t.check("the list opens at its top", Integer.valueOf(0).equals(got[1]));
		t.check("each box is as tall as its lines, no taller", Boolean.TRUE.equals(got[2]));
		t.check("three new versions boxed, plus the summary", Integer.valueOf(4).equals(got[3]));
		byte[] ico = Shortcuts.ico(true);
		t.check("the Gatekeeper icon is an .ico with six sizes", ico.length > 1000 && ico[2] == 1 && ico[4] == 6);
		t.done();
	}
	static void layAll(Component c) { c.doLayout(); if (c instanceof Container k) for (Component x : k.getComponents()) layAll(x); }
	static void find(Component c, List<JTextArea> out) { if (c instanceof JTextArea ta) out.add(ta); if (c instanceof Container k) for (Component x : k.getComponents()) find(x, out); }
	static List<Component> boxes(Component c) { List<Component> out = new ArrayList<>(); if (c.getClass().getName().endsWith("NotesView$Box")) out.add(c); if (c instanceof Container k) for (Component x : k.getComponents()) out.addAll(boxes(x)); return out; }
}
