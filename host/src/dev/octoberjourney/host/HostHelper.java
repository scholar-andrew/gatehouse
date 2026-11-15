package dev.octoberjourney.host;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;

/**
 * The October Journey host helper, on the PC that runs the server. Two modes:
 *
 *   --serve  (started at sign-in by a scheduled task) Listens on the Tailscale address only, for the launch pop-up's
 *            "Start MCSS" and "Close MCSS". Launchers introduce themselves (a device id they made, a secret only they
 *            know, a name); each new one waits until the owner allows it in the panel. Requests are matched to the
 *            secret and, for identification, to the Tailscale device they came from (tailscale whois). Also listens on
 *            127.0.0.1 for the panel.
 *   --panel  The owner's window: the launchers that have asked, with Allow MCSS control / Revoke / Forget, and a log.
 *
 * Its folder (default C:\Minecraft\OctoberJourneyHost, or OJ_HOST_DIR) holds host.json (settings), devices.json (the
 * launchers, with a hash of each secret, never the secret) and host.log. Closing MCSS is refused while the Minecraft
 * server is running, so the world is never cut off mid-save: stop the server first.
 */
public final class HostHelper {
	static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	static Path dir;
	static Map<String, Object> cfg;

	public static void main(String[] args) throws Exception {
		dir = Path.of(System.getenv().getOrDefault("OJ_HOST_DIR", "C:\\Minecraft\\OctoberJourneyHost"));
		Files.createDirectories(dir);
		cfg = Json.map(Json.parse(read(dir.resolve("host.json"))));
		String mode = args.length > 0 ? args[0] : "--panel";
		if (mode.equals("--serve")) serve();
		else panel();
	}

	static String setting(String key, String def) { Object v = cfg.get(key); return v == null || v.toString().isEmpty() ? def : v.toString(); }
	static int port() { return Integer.parseInt(setting("port", "25570")); }

	// ------------------------------------------------------------------ devices

	/** devices.json: { "<device id>": {name, os, tailscale, ip, token_sha256, granted, first_seen, last_seen} }. */
	static synchronized Map<String, Object> devices() { return Json.map(Json.parse(read(dir.resolve("devices.json")))); }

	static synchronized void saveDevices(Map<String, Object> d) throws IOException {
		Path tmp = dir.resolve("devices.json.tmp");
		Files.writeString(tmp, Json.write(d), StandardCharsets.UTF_8);
		Files.move(tmp, dir.resolve("devices.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static synchronized void log(String s) {
		try { Files.writeString(dir.resolve("host.log"), LocalDateTime.now().format(TIME) + "  " + s + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
		catch (IOException ignored) {}
	}

	/** A launcher as the log names it: its own name, and the Tailscale device it came from when known. */
	static String who(Map<String, Object> d) {
		String ts = Json.str(d.get("tailscale"));
		return Json.str(d.get("name")) + (ts.isEmpty() ? "" : " / " + ts);
	}

	static String sha256(String s) {
		try {
			StringBuilder b = new StringBuilder();
			for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
			return b.toString();
		} catch (Exception e) { throw new IllegalStateException(e); }
	}

	/** The Tailscale device a request came from, as Tailscale names it (empty if unknown). */
	static String tailscaleName(String ip) {
		try {
			Process p = new ProcessBuilder(setting("tailscale_exe", "C:\\Program Files\\Tailscale\\tailscale.exe"), "whois", "--json", ip).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(5, TimeUnit.SECONDS);
			Map<String, Object> node = Json.map(Json.map(Json.parse(out)).get("Node"));
			String name = Json.str(node.get("ComputedName"));
			if (name.isEmpty()) name = Json.str(node.get("Name")).replaceAll("\\..*$", "");
			Map<String, Object> hi = Json.map(node.get("Hostinfo"));
			String os = Json.str(hi.get("OS"));
			return name.isEmpty() ? "" : name + (os.isEmpty() ? "" : " (" + os + ")");
		} catch (Exception e) { return ""; }
	}

	// ------------------------------------------------------------------ MCSS

	static Path mcssExe() { return Path.of(setting("mcss_exe", "C:\\Minecraft\\MCSS\\mcss.exe")); }

	static List<ProcessHandle> mcssProcesses() {
		List<ProcessHandle> out = new ArrayList<>();
		String want = mcssExe().toAbsolutePath().toString();
		ProcessHandle.allProcesses().forEach(h -> h.info().command().ifPresent(c -> { if (c.equalsIgnoreCase(want)) out.add(h); }));
		return out;
	}

	static boolean minecraftRunning() {
		String hp = setting("minecraft", "127.0.0.1:25565");
		int c = hp.lastIndexOf(':');
		try (Socket s = new Socket()) { s.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 1500); return true; }
		catch (IOException | RuntimeException e) { return false; }
	}

	static String[] startMcss() {
		if (!mcssProcesses().isEmpty()) return new String[] {"ok", "MCSS is already running"};
		try {
			Path exe = mcssExe();
			List<String> cmd = new ArrayList<>(List.of(exe.toString()));
			String extra = setting("mcss_args", "");   // (testing: arguments for a stand-in program)
			if (!extra.isEmpty()) cmd.addAll(List.of(extra.split(" ")));
			new ProcessBuilder(cmd).directory(exe.getParent().toFile()).start();
			for (int i = 0; i < 20 && mcssProcesses().isEmpty(); i++) Thread.sleep(500);
			return mcssProcesses().isEmpty() ? new String[] {"failed", "MCSS didn't start"} : new String[] {"ok", "MCSS started"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't start MCSS: " + e.getMessage()}; }
	}

	/** Closes MCSS: refused while the Minecraft server runs. Asks its window to close (as the X button would), and only
	 *  if it's still there after 20 seconds ends it. Only processes of the configured mcss.exe are touched. */
	static String[] closeMcss() {
		List<ProcessHandle> ps = mcssProcesses();
		if (ps.isEmpty()) return new String[] {"ok", "MCSS isn't running"};
		if (minecraftRunning()) return new String[] {"refused", "The Minecraft server is still running: stop it first (Server controls, Stop server), then close MCSS"};
		try {
			for (ProcessHandle h : ps) {
				// Windows: ask the window to close, like its X button (taskkill without /F sends WM_CLOSE).
				new ProcessBuilder("taskkill", "/PID", String.valueOf(h.pid())).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
			}
			for (int i = 0; i < 40 && !mcssProcesses().isEmpty(); i++) Thread.sleep(500);
			if (!mcssProcesses().isEmpty()) {
				for (ProcessHandle h : mcssProcesses()) h.destroyForcibly();
				Thread.sleep(1000);
				return mcssProcesses().isEmpty() ? new String[] {"ok", "MCSS closed (it had to be ended: it didn't close its window)"} : new String[] {"failed", "MCSS wouldn't close"};
			}
			return new String[] {"ok", "MCSS closed"};
		} catch (Exception e) { return new String[] {"failed", "Couldn't close MCSS: " + e.getMessage()}; }
	}

	// ------------------------------------------------------------------ the helper

	static void serve() throws Exception {
		log("helper starting (port " + port() + ")");
		// The panel's side, on this PC only.
		HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
		local.createContext("/admin/", HostHelper::admin);
		local.start();
		// The launchers' side, on the Tailscale address only. Tailscale may still be starting at sign-in: retried.
		String bind = setting("bind", "100.91.134.102");
		while (true) {
			try {
				HttpServer remote = HttpServer.create(new InetSocketAddress(bind, port()), 0);
				remote.createContext("/v1/", HostHelper::launcher);
				remote.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
				remote.start();
				log("listening on " + bind + ":" + port());
				break;
			} catch (IOException e) { Thread.sleep(10_000); }
		}
	}

	static void reply(HttpExchange x, int code, Map<String, Object> body) throws IOException {
		byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
		x.getResponseHeaders().set("Content-Type", "application/json");
		x.sendResponseHeaders(code, b.length);
		try (OutputStream o = x.getResponseBody()) { o.write(b); }
	}

	static Map<String, Object> body(HttpExchange x) throws IOException {
		try (InputStream in = x.getRequestBody()) { return Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))); }
	}

	static Map<String, Object> m(Object... kv) {
		Map<String, Object> o = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
		return o;
	}

	/** Launchers: POST /v1/hello {id, token, name, os}; GET /v1/mcss; POST /v1/mcss/start; POST /v1/mcss/close.
	 *  Every call carries the id and token (in the body for hello, headers X-Device / X-Token otherwise). */
	static void launcher(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath(), ip = x.getRemoteAddress().getAddress().getHostAddress();
			Map<String, Object> devs = devices();
			if (path.equals("/v1/hello") && x.getRequestMethod().equals("POST")) {
				Map<String, Object> b = body(x);
				String id = Json.str(b.get("id")), token = Json.str(b.get("token"));
				if (!id.matches("[0-9a-fA-F-]{16,64}") || token.length() < 32) { reply(x, 400, m("error", "bad hello")); return; }
				Map<String, Object> d = Json.map(devs.get(id));
				if (!d.isEmpty() && !sha256(token).equals(d.get("token_sha256"))) { reply(x, 403, m("state", "mismatch", "error", "this device id is already known with another secret")); log("rejected hello for " + id + " from " + ip + ": wrong secret"); return; }
				boolean fresh = d.isEmpty();
				if (fresh) { d.put("token_sha256", sha256(token)); d.put("granted", false); d.put("first_seen", LocalDateTime.now().format(TIME)); }
				d.put("name", Json.str(b.get("name")).replaceAll("[^\\w .()\\-]", "").trim());
				d.put("os", Json.str(b.get("os")).replaceAll("[^\\w .()\\-]", "").trim());
				d.put("ip", ip);
				String ts = tailscaleName(ip);
				if (!ts.isEmpty()) d.put("tailscale", ts);
				d.put("last_seen", LocalDateTime.now().format(TIME));
				devs.put(id, d);
				saveDevices(devs);
				if (fresh) log("new launcher asked for MCSS control: " + who(d) + " (" + ip + ")");
				reply(x, 200, m("state", Boolean.TRUE.equals(d.get("granted")) ? "granted" : "pending"));
				return;
			}
			String id = x.getRequestHeaders().getFirst("X-Device"), token = x.getRequestHeaders().getFirst("X-Token");
			Map<String, Object> d = Json.map(devs.get(id == null ? "" : id));
			if (d.isEmpty() || token == null || !sha256(token).equals(d.get("token_sha256"))) { reply(x, 401, m("state", "unknown", "error", "say hello first")); return; }
			boolean granted = Boolean.TRUE.equals(d.get("granted"));
			if (path.equals("/v1/mcss") && x.getRequestMethod().equals("GET")) {
				reply(x, 200, m("state", granted ? "granted" : "pending", "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning()));
				return;
			}
			if (!granted) { reply(x, 403, m("state", "pending", "error", "not allowed yet: the owner allows this device in the host panel")); return; }
			String[] r;
			if (path.equals("/v1/mcss/start") && x.getRequestMethod().equals("POST")) r = startMcss();
			else if (path.equals("/v1/mcss/close") && x.getRequestMethod().equals("POST")) r = closeMcss();
			else { reply(x, 404, m("error", "no such call")); return; }
			log((path.endsWith("start") ? "start" : "close") + " MCSS asked by " + who(d) + ": " + r[1]);
			reply(x, 200, m("result", r[0], "message", r[1], "mcss", !mcssProcesses().isEmpty()));
		} catch (Exception e) {
			log("error: " + e);
			try { reply(x, 500, m("error", "the helper had a problem")); } catch (IOException ignored) {}
		}
	}

	/** The panel: GET /admin/devices, POST /admin/grant {id, granted}, POST /admin/forget {id}, GET /admin/log. */
	static void admin(HttpExchange x) throws IOException {
		try {
			String path = x.getRequestURI().getPath();
			if (path.equals("/admin/devices")) {
				Map<String, Object> devs = devices();
				for (Object v : devs.values()) Json.map(v).remove("token_sha256");
				reply(x, 200, m("devices", devs, "mcss", !mcssProcesses().isEmpty(), "minecraft", minecraftRunning(), "port", port(), "bind", setting("bind", "100.91.134.102")));
			} else if (path.equals("/admin/grant") || path.equals("/admin/forget")) {
				Map<String, Object> b = body(x), devs = devices();
				String id = Json.str(b.get("id"));
				Map<String, Object> d = Json.map(devs.get(id));
				if (d.isEmpty()) { reply(x, 404, m("error", "unknown device")); return; }
				if (path.endsWith("forget")) { devs.remove(id); log("forgot " + who(d)); }
				else { boolean g = Boolean.TRUE.equals(b.get("granted")) || "true".equals(Json.str(b.get("granted"))); d.put("granted", g); log((g ? "allowed MCSS control for " : "revoked MCSS control from ") + who(d)); }
				saveDevices(devs);
				reply(x, 200, m("ok", true));
			} else if (path.equals("/admin/log")) {
				String[] lines = read(dir.resolve("host.log")).split("\\R");
				List<Object> last = new ArrayList<>();
				for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) if (!lines[i].isBlank()) last.add(lines[i]);
				reply(x, 200, m("log", last));
			} else reply(x, 404, m("error", "no such call"));
		} catch (Exception e) {
			try { reply(x, 500, m("error", e.toString())); } catch (IOException ignored) {}
		}
	}

	// ------------------------------------------------------------------ the panel

	static String call(String method, String path, String body) throws IOException {
		HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + path).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(5000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) { return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8); }
	}

	static void panel() throws Exception {
		if (GraphicsEnvironment.isHeadless()) { System.out.println(call("GET", "/admin/devices", null)); return; }
		SwingUtilities.invokeAndWait(() -> {
			try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
			JFrame f = new JFrame("October Journey host panel");
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			JPanel p = new JPanel(new BorderLayout(0, 10));
			p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
			JLabel head = new JLabel("Launchers that asked to control MCSS");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
			JLabel state = new JLabel(" ");
			JPanel top = new JPanel(new BorderLayout(0, 4)); top.add(head, BorderLayout.NORTH); top.add(state, BorderLayout.SOUTH);
			p.add(top, BorderLayout.NORTH);
			String[] cols = {"Launcher", "Tailscale device", "System", "First seen", "Last seen", "MCSS control"};
			DefaultTableModel model = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
			JTable table = new JTable(model);
			table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			table.setRowHeight(24);
			List<String> ids = new ArrayList<>();
			JTextArea log = new JTextArea(8, 80);
			log.setEditable(false);
			log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JPanel mid = new JPanel(new BorderLayout(0, 8));
			JScrollPane ts = new JScrollPane(table); ts.setPreferredSize(new Dimension(820, 200));
			mid.add(ts, BorderLayout.CENTER);
			JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
			JButton grant = new JButton("Allow MCSS control"), revoke = new JButton("Revoke"), forget = new JButton("Forget"), refreshB = new JButton("Refresh");
			for (JButton b : new JButton[] {grant, revoke, forget, refreshB}) buttons.add(b);
			mid.add(buttons, BorderLayout.SOUTH);
			p.add(mid, BorderLayout.CENTER);
			JPanel bottom = new JPanel(new BorderLayout(0, 4));
			bottom.add(new JLabel("Activity"), BorderLayout.NORTH);
			bottom.add(new JScrollPane(log), BorderLayout.CENTER);
			p.add(bottom, BorderLayout.SOUTH);
			Runnable refresh = () -> new Thread(() -> {
				try {
					Map<String, Object> r = Json.map(Json.parse(call("GET", "/admin/devices", null)));
					List<Object> lg = Json.list(Json.map(Json.parse(call("GET", "/admin/log", null))).get("log"));
					SwingUtilities.invokeLater(() -> {
						int sel = table.getSelectedRow();
						String keep = sel >= 0 && sel < ids.size() ? ids.get(sel) : null;
						model.setRowCount(0); ids.clear();
						for (Map.Entry<String, Object> e : Json.map(r.get("devices")).entrySet()) {
							Map<String, Object> d = Json.map(e.getValue());
							ids.add(e.getKey());
							model.addRow(new Object[] {d.get("name"), d.get("tailscale"), d.get("os"), d.get("first_seen"), d.get("last_seen"),
									Boolean.TRUE.equals(d.get("granted")) ? "Allowed" : "Waiting for you"});
							if (e.getKey().equals(keep)) table.setRowSelectionInterval(ids.size() - 1, ids.size() - 1);
						}
						state.setForeground(new Color(0x2E9D48));
						state.setText("Helper running on " + r.get("bind") + ":" + r.get("port") + ".  MCSS " + (Boolean.TRUE.equals(r.get("mcss")) ? "is running" : "isn't running")
								+ ";  Minecraft server " + (Boolean.TRUE.equals(r.get("minecraft")) ? "is running" : "is off") + ".");
						StringBuilder sb = new StringBuilder();
						for (Object l : lg) sb.append(l).append('\n');
						if (!log.getText().equals(sb.toString())) { log.setText(sb.toString()); log.setCaretPosition(log.getDocument().getLength()); }
					});
				} catch (IOException e) {
					SwingUtilities.invokeLater(() -> { state.setForeground(new Color(0xD23C3C)); state.setText("The helper isn't running (it starts when you sign in to Windows)."); });
				}
			}, "refresh").start();
			java.util.function.BiConsumer<String, String> act = (what, body) -> new Thread(() -> {
				try { call("POST", what, body); } catch (IOException e) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(f, "The helper isn't running.")); }
				refresh.run();
			}, "act").start();
			java.util.function.Supplier<String> chosen = () -> {
				int r = table.getSelectedRow();
				if (r < 0) { JOptionPane.showMessageDialog(f, "Pick a launcher in the list first."); return null; }
				return ids.get(r);
			};
			grant.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/grant", "{\"id\":\"" + id + "\",\"granted\":true}"); });
			revoke.addActionListener(e -> { String id = chosen.get(); if (id != null) act.accept("/admin/grant", "{\"id\":\"" + id + "\",\"granted\":false}"); });
			forget.addActionListener(e -> { String id = chosen.get(); if (id != null && JOptionPane.showConfirmDialog(f, "Forget this launcher? It can ask again later.", "Forget", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act.accept("/admin/forget", "{\"id\":\"" + id + "\"}"); });
			refreshB.addActionListener(e -> refresh.run());
			new Timer(3000, e -> refresh.run()).start();
			refresh.run();
			f.setContentPane(p);
			f.pack();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	static String read(Path p) {
		try { return Files.readString(p, StandardCharsets.UTF_8).replace("\uFEFF", ""); } catch (IOException e) { return ""; }
	}
}
