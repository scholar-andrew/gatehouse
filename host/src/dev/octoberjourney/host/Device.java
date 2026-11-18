package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import dev.gatehouse.look.Quiet;

/**
 * What kind of machine this Gatekeeper is on (a desktop, a laptop, a Steam Deck, a ROG Ally...), so a launcher can
 * show the right picture for it in its list (dev.gatehouse.look.DeviceIcon has the kinds and draws them).
 *
 * It is worked out once, when Gatekeeper starts, from what the machine says about itself, and only read:
 *   Windows  its model, maker and case type, asked of Windows through its own PowerShell (Win32_ComputerSystem,
 *            Win32_SystemEnclosure);
 *   Linux    the same from /sys/class/dmi/id (product_name, sys_vendor, chassis_type);
 *   macOS    its model name (sysctl hw.model), and whether it has a battery.
 * The owner can say otherwise in Options ("This machine"): host.json "device_kind". Nothing is found: no kind, and
 * the launcher shows the Gatekeeper's initials as before.
 */
final class Device {
	private Device() {}
	private static volatile String found = "";

	/** The kind launchers are told: the owner's choice, else what was found, else "". */
	static String kind() {
		String chosen = setting("device_kind", "");
		return dev.gatehouse.look.DeviceIcon.known(chosen) ? chosen : found;
	}

	/** Works it out, in the background (asking Windows takes a second or two). */
	static void start() {
		Thread t = new Thread(() -> {
			try { found = detect(); } catch (RuntimeException e) { found = ""; }
			log("this machine: " + (found.isEmpty() ? "no kind recognised" : dev.gatehouse.look.DeviceIcon.name(found))
					+ (setting("device_kind", "").isEmpty() ? "" : " (Options says: " + dev.gatehouse.look.DeviceIcon.name(kind()) + ")"));
		}, "device");
		t.setDaemon(true);
		t.start();
	}

	private static List<String> run(String... command) {
		List<String> out = new ArrayList<>();
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			p.getOutputStream().close();
			if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return out; }
			for (String line : new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\\R")) out.add(line.trim());
		} catch (IOException e) { /* that program isn't there */ }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		return out;
	}

	static String detect() {
		String forced = System.getenv("OJ_DEVICE");   // (testing)
		if (forced != null) return dev.gatehouse.look.DeviceIcon.known(forced.trim()) ? forced.trim() : "";
		String os = System.getProperty("os.name", "");
		if (os.startsWith("Mac")) {
			List<String> m = run("sysctl", "-n", "hw.model");
			String model = m.isEmpty() ? "" : m.get(0);
			boolean battery = String.join(" ", run("pmset", "-g", "batt")).contains("InternalBattery");
			return model.startsWith("MacBook") || battery ? "maclaptop" : model.startsWith("iMac") ? "macdesktop"
					: model.startsWith("Macmini") ? "macmini" : model.isEmpty() ? "" : "macdesktop";
		}
		if (WIN) {
			List<String> l = run("powershell", "-NoProfile", "-NonInteractive", "-Command",
					"$c = Get-CimInstance Win32_ComputerSystem; $c.Manufacturer; $c.Model; ((Get-CimInstance Win32_SystemEnclosure).ChassisTypes -join ',')");
			return l.size() < 3 ? "" : classify(l.get(0), l.get(1), l.get(2));
		}
		Path dmi = Path.of("/sys/class/dmi/id");
		return classify(read(dmi.resolve("sys_vendor")).trim(), read(dmi.resolve("product_name")).trim(), read(dmi.resolve("chassis_type")).trim());
	}

	/** From a maker, a model and the case types the machine reports (SMBIOS numbers, comma-separated). */
	static String classify(String maker, String model, String chassis) {
		String s = (maker + " " + model).toLowerCase(java.util.Locale.ROOT);
		if (s.contains("valve") && (s.contains("jupiter") || s.contains("galileo")) || s.contains("steam deck")) return "steamdeck";
		if (s.contains("rog ally") || s.contains("rc71l") || s.contains("rc72l")) return "rogally";
		for (String h : new String[] {"legion go", "claw", "ayaneo", "gpd win", "onexplayer", "aokzoe", "ayn loki"}) if (s.contains(h)) return "handheld";
		List<Integer> types = new ArrayList<>();
		for (String t : chassis.split("[^0-9]+")) if (!t.isEmpty()) try { types.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) { Quiet.seen(ignored); }
		for (int t : types) if (t == 8 || t == 9 || t == 10 || t == 14 || t == 31 || t == 32) return "laptop";
		for (int t : types) if (t == 11 || t == 30) return "handheld";
		for (int t : types) if (t == 35 || t == 36) return "minipc";
		for (int t : types) if (t == 17 || t == 23 || t == 28 || t == 29) return "server";
		for (int t : types) if (t == 3 || t == 4 || t == 5 || t == 6 || t == 7 || t == 13 || t == 15 || t == 16 || t == 24) return t == 13
				&& s.contains("apple") ? "macdesktop" : "desktop";
		return "";
	}

	/** GET /admin/device: {kind, chosen, found}. POST {kind: an id, or "" for automatic}. */
	static boolean admin(HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/device")) return false;
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			String want = Json.str(b.get("kind"));
			if (!want.isEmpty() && !dev.gatehouse.look.DeviceIcon.known(want)) { reply(x, 200, m("result", "refused", "message",
					"No such kind of machine.")); return true; }
			if (want.isEmpty()) cfg.remove("device_kind"); else cfg.put("device_kind", want);
			ServerTools.saveCfg();
			log("this machine: Options now says " + (want.isEmpty() ? "automatic" : dev.gatehouse.look.DeviceIcon.name(want)));
		}
		reply(x, 200, m("result", "ok", "kind", kind(), "chosen", setting("device_kind", ""), "found", found));
		return true;
	}
}
