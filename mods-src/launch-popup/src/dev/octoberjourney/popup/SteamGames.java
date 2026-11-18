package dev.octoberjourney.popup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam on this device, as Gatehouse needs it for games other than Minecraft (R34 stage 2): where Steam is, which games
 * are installed in its libraries, which Steam account is signed in (its Steam ID, so the host can let that player onto
 * a server whose list of who may join holds Steam IDs), and starting a game through Steam, connected to a server.
 * It only reads Steam's own files (libraryfolders.vdf, appmanifest_<app>.acf, loginusers.vdf) and asks Steam to open
 * steam:// links; it changes nothing of Steam's. Where Steam isn't there, or can't be seen (a sandbox), it knows nothing.
 */
final class SteamGames {
	private SteamGames() {}
	static final boolean WIN = System.getProperty("os.name", "").startsWith("Windows");

	/** Steam's own folder on this device, or null. */
	static Path root() {
		String set = System.getenv("OJ_STEAM_ROOT");   // (testing: a stand-in Steam folder)
		if (set != null && !set.isEmpty()) return Files.isDirectory(Path.of(set)) ? Path.of(set) : null;
		List<Path> tries = new ArrayList<>();
		String home = System.getProperty("user.home", "");
		if (WIN) {
			String reg = regSteamPath();
			if (!reg.isEmpty()) tries.add(Path.of(reg));
			tries.add(Path.of("C:\\Program Files (x86)\\Steam"));
		} else {
			tries.add(Path.of(home, ".local/share/Steam"));
			tries.add(Path.of(home, ".steam/steam"));
			tries.add(Path.of(home, ".var/app/com.valvesoftware.Steam/.local/share/Steam"));
		}
		for (Path p : tries) if (Files.isDirectory(p.resolve("steamapps"))) return p;
		return null;
	}
	private static String regSteamPath() {
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes());
			Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			return m.find() ? m.group(1).trim().replace('/', '\\') : "";
		} catch (Exception e) { return ""; }
	}
	/** Steam's libraries (its own folder, and each other one in libraryfolders.vdf). */
	static List<Path> libraries() {
		List<Path> out = new ArrayList<>();
		Path r = root();
		if (r == null) return out;
		out.add(r);
		String vdf = read(r.resolve("steamapps/libraryfolders.vdf"));
		Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(vdf);
		while (m.find()) { Path p = Path.of(m.group(1).replace("\\\\", "\\")); if (!out.contains(p) && Files.isDirectory(p)) out.add(p); }
		return out;
	}
	/** Is that Steam app installed on this device? */
	static boolean installed(int app) {
		if (app <= 0) return false;
		for (Path l : libraries()) if (Files.isRegularFile(l.resolve("steamapps/appmanifest_" + app + ".acf"))) return true;
		return false;
	}
	/** The Steam account signed in here: {Steam ID, its name}, or null. The one Steam last used (MostRecent). */
	static String[] account() {
		Path r = root();
		if (r == null) return null;
		String vdf = read(r.resolve("config/loginusers.vdf"));
		Matcher m = Pattern.compile("\"(\\d{17})\"\\s*\\{([^}]*)\\}").matcher(vdf);
		String[] first = null;
		while (m.find()) {
			String body = m.group(2), name = field(body, "PersonaName");
			String[] a = {m.group(1), name.isEmpty() ? field(body, "AccountName") : name};
			if (first == null) first = a;
			if (field(body, "MostRecent").equals("1")) return a;
		}
		return first;
	}
	private static String field(String body, String k) { Matcher m = Pattern.compile("\"" + k + "\"\\s+\"([^\"]*)\"").matcher(body); return m.find() ? m.group(1) : ""; }
	/** The link that starts the game connected to a server: the game's own way ({app}, {address}), else Steam's. */
	static String joinLink(String template, int app, String address) {
		String t = template == null || template.isEmpty() ? "steam://connect/{address}" : template;
		return t.replace("{app}", String.valueOf(app)).replace("{address}", address);
	}
	/** Steam's page to install a game. */
	static String installLink(int app) { return "steam://install/" + app; }
	/** For the report to Gatekeepers: ,"steam_id":...,"steam_name":... when Steam is signed in here, else "". */
	static String reportJson() {
		String[] a = account();
		return a == null ? "" : ",\"steam_id\":\"" + a[0] + "\",\"steam_name\":\"" + ServerControl.jsonEsc(a[1]) + "\"";
	}
	private static String read(Path p) { try { return Files.readString(p); } catch (Exception e) { return ""; } }
}
