package dev.octoberjourney.popup;

import dev.gatehouse.Tests;
import java.nio.file.Files;
import java.nio.file.Path;

/** R90: the button pictures follow the maker of the controller that is connected (read from a stand-in /sys/class/input). */
public final class TestPad {
	public static void main(String[] a) throws Exception {
		Tests t = new Tests("controller makers");
		Path sys = Files.createTempDirectory("gatehouse-input-");
		try {
			dev(sys, "input3", "28de");   // the Steam Deck's own controls
			t.check("the Steam Deck alone shows Xbox's buttons", "xbox".equals(Pad.linuxFamily(sys)));
			dev(sys, "input9", "054c");   // a DualSense plugged in
			t.check("a PlayStation controller plugged in shows PlayStation's", "playstation".equals(Pad.linuxFamily(sys)));
			Files.writeString(sys.resolve("input9/id/vendor"), "057e\n");
			t.check("a Nintendo one shows Nintendo's", "nintendo".equals(Pad.linuxFamily(sys)));
			t.check("no input devices at all: Xbox's", "xbox".equals(Pad.linuxFamily(sys.resolve("missing"))));
			// R90 follow-up: the controller pressed last wins; plugging one in or out decides only until then
			dev(sys, "js0/device", "054c"); dev(sys, "js1/device", "28de"); dev(sys, "js2/device", "045e");   // (jsN/device is its input device)
			t.check("a PlayStation joystick device is PlayStation", "playstation".equals(Pad.jsFamily(sys.resolve("js0"))));
			t.check("Steam's virtual pad says nothing", "".equals(Pad.jsFamily(sys.resolve("js1"))));
			t.check("an Xbox one is Xbox", "xbox".equals(Pad.jsFamily(sys.resolve("js2"))));
			String[] seen = {null, null};
			Pad.connected(seen, "playstation");
			t.check("plugging a PlayStation controller in shows its shapes", "playstation".equals(dev.gatehouse.look.GameShell.family()));
			Pad.pressedBy(seen, "xbox");
			t.check("pressing the Deck's own buttons then shows Xbox's", "xbox".equals(dev.gatehouse.look.GameShell.family()));
			Pad.connected(seen, "playstation");
			t.check("and it stays so while nothing is plugged in or out", "xbox".equals(dev.gatehouse.look.GameShell.family()));
			Pad.pressedBy(seen, "playstation");
			t.check("pressing the PlayStation controller switches back", "playstation".equals(dev.gatehouse.look.GameShell.family()));
			dev.gatehouse.look.GameShell.family("playstation");
			t.check("the windows are told", "playstation".equals(dev.gatehouse.look.GameShell.family()));
			dev.gatehouse.look.GameShell.family("something else");
			t.check("an unknown maker falls back to Xbox's", "xbox".equals(dev.gatehouse.look.GameShell.family()));
		} finally {
			try (var s = Files.walk(sys)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); }
		}
		t.done();
	}
	private static void dev(Path sys, String name, String vendor) throws Exception {
		Files.createDirectories(sys.resolve(name + "/id"));
		Files.writeString(sys.resolve(name + "/id/vendor"), vendor + "\n");
	}
}
