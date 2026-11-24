package dev.octoberjourney.popup;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Waking a machine from outside the home (run_tests.ps1, with two stand-in Gatekeepers already started): one plays the
 * sleeping machine (its network card set to a made-up address), the other a relay at home whose wake signal goes to
 * a listener here (OJ_WAKE_TEST) instead of the network. A launcher, in this process, in a game folder of its own.
 *
 *   args: machine port, relay port, relay's listener port, router listener port, a work folder
 */
public final class TestWake {
	static int failed;
	static void check(String name, boolean ok) { System.out.println((ok ? "  ok      " : "  FAILED  ") + name); if (!ok) failed++; }
	static final String MAC = "02:00:5E:10:20:30";

	static Map<String, Object> call(String url, String method, String body) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(4000); c.setReadTimeout(15000); c.setRequestMethod(method);
		if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); } }
		int code = c.getResponseCode();
		InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
		Map<String, Object> r = ServerControl.asMap(LaunchPopup.parseJson(in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		r.put("_code", String.valueOf(code));
		return r;
	}
	/** Waits up to 5 s for a wake signal; true if it is one for MAC. */
	static boolean caught(DatagramSocket s) {
		try {
			byte[] b = new byte[200]; DatagramPacket p = new DatagramPacket(b, b.length);
			s.setSoTimeout(5000); s.receive(p);
			if (p.getLength() != 102) return false;
			for (int i = 0; i < 6; i++) if (b[i] != (byte) 0xFF) return false;
			String hex = MAC.replace(":", "");
			for (int i = 6; i < 102; i++) if (b[i] != (byte) Integer.parseInt(hex.substring(((i - 6) % 6) * 2, ((i - 6) % 6) * 2 + 2), 16)) return false;
			return true;
		} catch (Exception e) { return false; }
	}

	public static void main(String[] a) throws Exception {
		String pc = "http://127.0.0.1:" + a[0], relay = "http://127.0.0.1:" + a[1];
		Path game = Path.of(a[4], "game");
		Files.createDirectories(game.resolve("extras"));
		Files.writeString(game.resolve("extras/server-control.json"), "{\"helper_url\": \"http://127.0.0.9:" + a[0] + "\"}");
		try (DatagramSocket relayHears = new DatagramSocket(Integer.parseInt(a[2]), InetAddress.getByName("127.0.0.1"));
				DatagramSocket routerHears = new DatagramSocket(Integer.parseInt(a[3]), InetAddress.getByName("127.0.0.1"))) {
			// the machine: its network card, the router's forwarded port, and a relay code
			check("the machine's network card can be set", "ok".equals(ServerControl.str(call(pc + "/admin/wake/outside", "POST", "{\"mac\": \"" + MAC + "\"}").get("result"))));
			check("a router address can be set", "ok".equals(ServerControl.str(call(pc + "/admin/wake/outside", "POST", "{\"router\": \"127.0.0.1:" + a[3] + "\"}").get("result"))));
			check("a bad router address is refused", "refused".equals(ServerControl.str(call(pc + "/admin/wake/outside", "POST", "{\"router\": \"not a name\"}").get("result"))));
			String code = ServerControl.str(call(pc + "/admin/wake/outside", "POST", "{\"code\": \"Pi\"}").get("message"));
			check("a relay code is made", code.startsWith("gatehouse-wake:"));
			check("a damaged code is refused at the relay", "refused".equals(ServerControl.str(call(relay + "/admin/wake/outside", "POST", "{\"join\": \"gatehouse-wake:xyz\"}").get("result"))));
			Map<String, Object> joined = call(relay + "/admin/wake/outside", "POST", "{\"join\": \"" + code + "\"}");
			check("the relay takes the code and tells the machine where it is (" + ServerControl.str(joined.get("message")) + ")", "ok".equals(ServerControl.str(joined.get("result"))));
			List<?> rs = (List<?>) call(pc + "/admin/wake/outside", "GET", null).get("relays");
			check("the machine lists the relay with its address", rs != null && rs.size() == 1 && ServerControl.str(ServerControl.asMap(rs.get(0)).get("url")).endsWith(":" + a[1]));
			check("a second use of a code someone copied can't change the address without its secret",
					"403".equals(ServerControl.str(call(pc + "/v1/wake/register", "POST", "{\"relay\": \"" + ServerControl.str(ServerControl.asMap(rs.get(0)).get("id"))
							+ "\", \"url\": \"http://evil.example:1\", \"fp\": \"\", \"name\": \"x\", \"proof\": \"00\"}").get("_code"))));

			// a launcher: nothing before it is allowed, then the wake details and a ticket
			ServerControl c = new ServerControl(game);
			c.hello();
			c.mcssState();
			check("a launcher not allowed wake/sleep is told nothing about waking", c.wakeInfo.isEmpty() && !c.canWake());
			check("the machine allows it Machine wake/sleep", "200".equals(ServerControl.str(call(pc + "/admin/perm", "POST", "{\"id\": \"" + c.deviceId + "\", \"perm\": \"sleep\", \"value\": true}").get("_code"))));
			c.mcssState();
			check("then it learns the network card, the router and the relay", c.canWake() && c.wakeMac().equalsIgnoreCase(MAC.replace(":", ""))
					&& ServerControl.str(c.wakeInfo.get("router")).equals("127.0.0.1:" + a[3]) && c.relays().size() == 1 && c.wakesFromOutside());
			check("and keeps them for while the machine sleeps", new ServerControl(game).relays().size() == 1);

			// pressing Wake
			List<String> went = c.wake();
			check("Wake sends the signal to the router's address", went.contains("127.0.0.1:" + a[3]) && caught(routerHears));
			check("and the relay passes it on in the home (" + c.wakeNote + ")", went.stream().anyMatch(w -> w.startsWith("relay ")) && caught(relayHears));
			Map<String, Object> t = c.relays().get(0);
			String good = "{\"relay\": \"" + t.get("relay") + "\", \"launcher\": \"" + c.deviceId + "\", \"exp\": \"" + t.get("exp") + "\", \"sig\": \"";
			check("the relay refuses a changed ticket", "403".equals(ServerControl.str(call(relay + "/v1/wake", "POST", good + "00" + ServerControl.str(t.get("sig")).substring(2) + "\"}").get("_code"))));
			check("and one for another launcher", "403".equals(ServerControl.str(call(relay + "/v1/wake", "POST", good.replace(c.deviceId, "0123456789abcdef0123") + t.get("sig") + "\"}").get("_code"))));
			check("and a run-out one", "403".equals(ServerControl.str(call(relay + "/v1/wake", "POST", "{\"relay\": \"" + t.get("relay") + "\", \"launcher\": \"" + c.deviceId
					+ "\", \"exp\": \"1000\", \"sig\": \"" + t.get("sig") + "\"}").get("_code"))));

			// taking it away
			call(pc + "/admin/perm", "POST", "{\"id\": \"" + c.deviceId + "\", \"perm\": \"sleep\", \"value\": false}");
			c.mcssState();
			check("a launcher whose permission is taken away forgets how to wake it", c.wakeInfo.isEmpty() && !c.canWake());
			call(pc + "/admin/perm", "POST", "{\"id\": \"" + c.deviceId + "\", \"perm\": \"sleep\", \"value\": true}");
			call(pc + "/admin/wake/outside", "POST", "{\"remove_relay\": \"" + t.get("relay") + "\"}");
			c.mcssState();
			check("a removed relay is no longer handed out", c.relays().isEmpty() && c.canWake());
			List<?> fs = (List<?>) call(relay + "/admin/wake/outside", "GET", null).get("wake_for");
			check("the relay can stop waking the machine", fs != null && fs.size() == 1 && "ok".equals(ServerControl.str(call(relay + "/admin/wake/outside", "POST",
					"{\"remove_for\": \"" + ServerControl.str(ServerControl.asMap(fs.get(0)).get("id")) + "\"}").get("result"))));
			check("and then refuses even a good ticket", "403".equals(ServerControl.str(call(relay + "/v1/wake", "POST", good + t.get("sig") + "\"}").get("_code"))));
		}
		System.exit(failed == 0 ? 0 : 1);
	}
}
