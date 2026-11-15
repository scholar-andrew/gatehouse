package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCSS as the program that hosts the Minecraft server (see ServerHost): its window (mcss.exe: started, asked to close,
 * or ended), and its own API on this machine for the server's state, start and stop, with the helper's key or sign-in.
 * Everything about MCSS is in this file. Settings, all in host.json: mcss_exe, mcss_panel (default
 * http://127.0.0.1:25560), mcss_api_key and mcss_user (the helper's access), mcss_args (testing), and the server's
 * mcss_server id (else read from mcss_server_config.json in the server's folder).
 */
final class McssHost implements ServerHost {
	// ------------------------------------------------------------------ MCSS itself (the program)

	private Path mcssExe() { return Path.of(setting("mcss_exe", "")); }

	private List<ProcessHandle> processes() {
		List<ProcessHandle> out = new ArrayList<>();
		String want = mcssExe().toAbsolutePath().toString();
		ProcessHandle.allProcesses().forEach(h -> h.info().command().ifPresent(c -> { if (c.equalsIgnoreCase(want)) out.add(h); }));
		return out;
	}

	@Override public boolean running() { return !processes().isEmpty(); }

	@Override public String[] start() {
		if (!processes().isEmpty()) return new String[] {"ok", "MCSS is already running"};
		try {
			Path exe = mcssExe();
			List<String> cmd = new ArrayList<>(List.of(exe.toString()));
			String extra = setting("mcss_args", "");   // (testing: arguments for a stand-in program)
			if (!extra.isEmpty()) cmd.addAll(List.of(extra.split(" ")));
			new ProcessBuilder(cmd).directory(exe.getParent().toFile()).start();
			for (int i = 0; i < 20 && processes().isEmpty(); i++) Thread.sleep(500);
			return processes().isEmpty() ? new String[] {"failed", "MCSS didn't start"} : new String[] {"ok", "MCSS started"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't start MCSS: " + e.getMessage()}; }
	}

	/** MCSS's own "when MCSS is closed" setting (mcssClosePreference in resources/mcss_config.json): HideToTaskbar,
	 *  MinimizeToTaskbar, or an exit choice. Only an exit choice makes a close request quit MCSS properly. */
	private String closePreference() {
		Path c = mcssExe().getParent().resolve("resources").resolve("mcss_config.json");
		return Json.str(Json.map(Json.parse(read(c))).get("mcssClosePreference"));
	}

	/** How a close works with MCSS's current setting: "close" (its X button exits MCSS, so it's asked to close) or
	 *  "force" (its X button only hides it, so closing means ending it). The launcher labels its button by this. */
	@Override public String closeMode() {
		String pref = closePreference();
		return pref.contains("Hide") || pref.contains("Minimize") ? "force" : "close";
	}

	/** Closes MCSS: refused while the Minecraft server runs. With MCSS's exit setting it asks the window to close
	 *  (exactly as its X button, which then quits MCSS the way the tray menu's Close does) and waits up to 30 seconds,
	 *  never forcing. With a hide setting it ends MCSS, but only when the launcher asked for a force close (its button
	 *  said "Force close MCSS"). Only the configured mcss.exe is touched. */
	@Override public String[] close(boolean force) {
		List<ProcessHandle> ps = processes();
		if (ps.isEmpty()) return new String[] {"ok", "MCSS isn't running"};
		if (anyServerRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then close MCSS"};
		if (closeMode().equals("force")) {
			if (!force) return new String[] {"refused", "MCSS is set to hide when closed (" + closePreference() + "), so closing it means force-closing it: use Force close MCSS. "
					+ "(To close it properly instead, set MCSS's close option to exit MCSS, in MCSS's settings on the server PC.)"};
			for (ProcessHandle h : ps) h.destroyForcibly();
			try { for (int i = 0; i < 20 && !processes().isEmpty(); i++) Thread.sleep(250); } catch (InterruptedException ignored) {}
			return processes().isEmpty() ? new String[] {"ok", "MCSS force-closed (its X button is set to hide it). The Minecraft server was already stopped, so the world is safe."}
					: new String[] {"failed", "MCSS wouldn't close"};
		}
		try {
			for (ProcessHandle h : ps) {
				// Windows: ask the window to close, like its X button (taskkill without /F sends WM_CLOSE; never /F).
				new ProcessBuilder("taskkill", "/PID", String.valueOf(h.pid())).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
			}
			for (int i = 0; i < 60 && !processes().isEmpty(); i++) Thread.sleep(500);
			return processes().isEmpty() ? new String[] {"ok", "MCSS closed properly"}
					: new String[] {"failed", "MCSS was asked to close but is still open after 30 seconds (it may be asking something on the server PC's screen). It wasn't forced."};
		} catch (Exception e) { return new String[] {"failed", "Couldn't close MCSS: " + e.getMessage()}; }
	}

	// ------------------------------------------------------------------ the Minecraft server, through MCSS's API

	private String serverId() {
		String id = serverSetting("mcss_server", "mcss_server", "");
		return !id.isEmpty() ? id : Json.str(Json.map(Json.parse(read(serverDir().resolve("mcss_server_config.json")))).get("guid"));
	}

	/** One call to MCSS's own API on this PC, with the helper's key (host.json "mcss_api_key", set in the panel). */
	private String mcssApi(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + path).openConnection();
		c.setConnectTimeout(3000); c.setReadTimeout(8000); c.setRequestMethod(method);
		c.setRequestProperty("apiKey", setting("mcss_api_key", ""));
		if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		int code = c.getResponseCode();
		if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
		try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	/** MCSS's state for the server: 0 off, 1 on, 2 restarting, 3 starting, 4 stopping; -1 unknown (no key, MCSS closed). */
	@Override public int serverStatus() {
		try { return (int) Double.parseDouble(Json.str(Json.map(Json.parse(mcssApi("GET", "/api/v2/servers/" + serverId(), null))).get("status"))); }
		catch (IOException | RuntimeException e) { return -1; }
	}

	@Override public String[] serverAction(boolean start) {
		if (setting("mcss_api_key", "").isEmpty()) return new String[] {"refused", "The helper has no MCSS key yet: the owner sets it in the host panel (Set MCSS key)"};
		if (processes().isEmpty()) return new String[] {"refused", "MCSS isn't running: start it first (Start MCSS)"};
		try {
			mcssApi("POST", "/api/v2/servers/" + serverId() + "/execute/action", "{\"action\":" + (start ? 2 : 1) + "}");
			return new String[] {"ok", start ? "Start requested: the server takes a minute or so to come up" : "Stop requested: the server saves the world and shuts down"};
		} catch (IOException e) {
			return new String[] {"failed", e.getMessage().contains("401") ? "MCSS doesn't know the helper's key (the owner sets it again in the host panel)"
					: e.getMessage().contains("403") ? "The helper's MCSS key isn't allowed to " + (start ? "start" : "stop") + " this server (the owner gives the key that permission in MCSS's panel, under API Keys)"
					: "MCSS said no (" + e.getMessage() + ")"};
		}
	}

	// ------------------------------------------------------------------ the helper's access to MCSS

	@Override public boolean hasAccess() { return !setting("mcss_api_key", "").isEmpty(); }
	@Override public String user() { return setting("mcss_user", ""); }

	/** How MCSS takes the helper's key: "yes" (it can see the server), "forbidden" (MCSS knows the key, but it has no
	 *  right to this server), "unknown" (MCSS doesn't know the key), "no" (MCSS isn't answering). */
	@Override public String accessState() {
		try { mcssApi("GET", "/api/v2/servers/" + serverId(), null); return "yes"; }
		catch (IOException e) { return String.valueOf(e.getMessage()).contains("403") ? "forbidden" : String.valueOf(e.getMessage()).contains("401") ? "unknown" : "no"; }
	}

	@Override public void setKey(String key) {
		cfg.remove("mcss_user");
		cfg.put("mcss_api_key", key);
		try { Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8); } catch (IOException e) { log("couldn't save host.json: " + e); }
		log(!hasAccess() ? "the helper's MCSS key was cleared" : "the helper's MCSS key was set");
	}

	/** MCSS's own sign-in (POST /auth, as its web panel does) hands back a key, and that is what's kept (host.json). */
	@Override public String[] signIn(String user, String password) {
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + "/auth").openConnection();
			c.setConnectTimeout(3000); c.setReadTimeout(8000); c.setRequestMethod("POST"); c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			try (OutputStream o = c.getOutputStream()) { o.write(Json.write(m("username", user, "password", password)).getBytes(StandardCharsets.UTF_8)); }
			int code = c.getResponseCode();
			if (code == 401 || code == 403) { log("MCSS sign-in as " + user + " was refused"); return new String[] {"refused", "MCSS didn't accept that username and password"}; }
			if (code < 200 || code >= 300) return new String[] {"failed", "MCSS answered " + code};
			String key;
			try (InputStream in = c.getInputStream()) { key = Json.str(Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("apiKey")); }
			if (key.isEmpty()) return new String[] {"failed", "MCSS signed in but handed back no key"};
			cfg.put("mcss_api_key", key); cfg.put("mcss_user", user);
			Files.writeString(dir.resolve("host.json"), Json.write(cfg), StandardCharsets.UTF_8);
			boolean works = serverStatus() >= 0;
			log("the helper signed in to MCSS as " + user + (works ? "" : " (but that account can't see the server)"));
			return new String[] {works ? "ok" : "failed", works ? "Signed in as " + user : "Signed in as " + user + ", but that account can't see the server (give it access to it in MCSS's panel)"};
		} catch (IOException e) {
			return new String[] {"failed", "MCSS's panel isn't answering (is MCSS running?)"};
		}
	}

	/** What MCSS answers to the helper's key: status codes and MCSS's own error text; never the key. */
	@Override public Map<String, Object> diagnose() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("server_id", serverId());
		for (String q : new String[] {"/api/v2/servers", "/api/v2/servers/" + serverId(), "/api/v2/keys"}) {
			try {
				HttpURLConnection c = (HttpURLConnection) new URL(setting("mcss_panel", "http://127.0.0.1:25560") + q).openConnection();
				c.setConnectTimeout(3000); c.setReadTimeout(8000);
				c.setRequestProperty("apiKey", setting("mcss_api_key", ""));
				int code = c.getResponseCode();
				String bodyText = "";
				if (code >= 400) try (InputStream in = c.getErrorStream()) { if (in != null) bodyText = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
				else if (q.equals("/api/v2/servers")) try (InputStream in = c.getInputStream()) {
					String list = new String(in.readAllBytes(), StandardCharsets.UTF_8);
					out.put("servers_listed", Json.list(Json.parse(list)).size());
					out.put("this_server_listed", list.contains(serverId()));
				}
				out.put(q, code + (bodyText.isEmpty() ? "" : " " + (bodyText.length() > 200 ? bodyText.substring(0, 200) : bodyText)) + " " + String.valueOf(c.getHeaderField("WWW-Authenticate")));
			} catch (IOException e) { out.put(q, "error: " + e); }
		}
		return out;
	}
}
