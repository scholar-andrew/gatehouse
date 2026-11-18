package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AI helper (R60): a local model through Ollama on this machine (http://127.0.0.1:11434 unless host.json
 * "ollama_url" says otherwise; model "ollama_model", qwen2.5:3b by default, the one Jarvis uses). Off until the owner
 * turns it on (Options). Nothing leaves this machine. It does two things, and only talks:
 *   1. In chat, a message starting with @ai is answered in the same room, as "Gatekeeper AI", from what Gatekeeper
 *      knows: its servers, their state and who is on, and how players join. It can't start, stop or allow anything,
 *      and says so when asked to.
 *   2. When a server ends without being asked to, the last lines it printed are explained in plain words, in the
 *      activity log and (with phone notifications on) on the owner's phone.
 * A small local model is quick but not always right: everything it says is marked as the AI's.
 */
final class Ai {
	private Ai() {}
	static final String NAME = "Gatekeeper AI";
	private static final Map<String, Long> busy = new ConcurrentHashMap<>();

	static boolean on() { return "true".equals(setting("ai", "false")); }
	static String url() { return setting("ollama_url", "http://127.0.0.1:11434").replaceAll("/+$", ""); }
	static String model() { return setting("ollama_model", "qwen2.5:3b"); }

	/** Does Ollama answer here, with the model? "" when it does, else what is missing, in words. */
	static String ready() {
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(url() + "/api/tags").openConnection();
			c.setConnectTimeout(1500); c.setReadTimeout(3000);
			String t = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			for (Object o : Json.list(Json.map(Json.parse(t)).get("models"))) if (model().equals(Json.str(Json.map(o).get("name")))) return "";
			return "Ollama answers, but doesn't have " + model() + " (in a terminal: ollama pull " + model() + ")";
		} catch (IOException | RuntimeException e) { return "Ollama isn't answering on this machine (" + url() + "): install it from ollama.com, or start it"; }
	}

	/** One answer from the model, or "" when it couldn't give one. Blocking (up to about a minute and a half). */
	static String ask(String system, String prompt) {
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(url() + "/api/generate").openConnection();
			c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(2000); c.setReadTimeout(90_000);
			c.setRequestProperty("Content-Type", "application/json");
			c.getOutputStream().write(Json.write(m("model", model(), "system", system, "prompt", prompt, "stream", false,
					"options", m("temperature", 0.2, "num_predict", 220))).getBytes(StandardCharsets.UTF_8));
			if (c.getResponseCode() != 200) return "";
			String t = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return Json.str(Json.map(Json.parse(t)).get("response")).strip();
		} catch (IOException | RuntimeException e) { return ""; }
	}

	/** What Gatekeeper knows, for the model: plain lines, no addresses of players, no folders, no passwords. */
	static String facts() {
		StringBuilder b = new StringBuilder();
		b.append("Gatekeeper ").append(ownVersion()).append(", called \"").append(setting("name", "this Gatekeeper")).append("\".\n");
		String keep = SERVER.get();
		try {
			for (Object o : servers()) {
				Map<String, Object> s = Json.map(o);
				SERVER.set(Json.str(s.get("id")));
				Game g = Game.of(s);
				String phase = serverPhase();
				Map<String, String> on = onlineBy.getOrDefault(serverKey(), Map.of());
				String state = switch (phase) { case "online" -> "is ON: up and running, players can join it now"; case "starting" -> "is STARTING: it will be on in a minute";
						case "stopping" -> "is STOPPING"; default -> "is OFF: nobody can join until it is started"; };
				b.append("Server \"").append(Json.str(s.get("name"))).append("\" (game ").append(Game.nameOf(g)).append(") ").append(state)
						.append(phase.equals("online") ? "; players on it now: " + (on.isEmpty() ? "nobody" : String.join(", ", on.keySet())) : "")
						.append(g == null ? "" : ", who may join: " + (g.allowKind().equals("password") ? "anyone with its password" : "players on its " + g.allowWord()))
						.append(".\n");
			}
		} finally { SERVER.set(keep); }
		b.append("How players join: in the Gatehouse launcher, open this Gatekeeper, pick the server, press Install (once) then Launch and Play. "
				+ "A server that is off: press Start if the host allowed it, else Request server. Not on the whitelist: Request whitelist. "
				+ "Can't connect: the server's Connection tab has a network check that says what is in the way (Tailscale, the host's machine asleep, the server off).\n");
		return b.toString();
	}

	/** A chat message: answered in its room when it starts with @ai and the helper is on. Off the caller's thread. */
	static void heard(String roomId, String who, String text) {
		if (!on() || who.equals(NAME)) return;
		String t = text.strip();
		if (!t.regionMatches(true, 0, "@ai", 0, 3) || (t.length() > 3 && Character.isLetterOrDigit(t.charAt(3)))) return;
		String question = t.substring(3).replaceFirst("^[\\s:,]+", "");
		Long since = busy.get(roomId);
		if (since != null && System.currentTimeMillis() - since < 120_000) return;   // (one question at a time in a room)
		busy.put(roomId, System.currentTimeMillis());
		Thread th = new Thread(() -> {
			try {
				String room = Json.str(Chat.all().get(roomId));
				String answer = question.isEmpty() ? "" : ask("You are Gatekeeper AI, a helper in the chat room \"" + room + "\" of a small private game server group. "
						+ "Answer in at most three short sentences, plainly, only from the facts given. If the facts don't say, say you don't know and suggest asking the host. "
						+ "You cannot start, stop, install, allow, ban or change anything: if asked to, say so and say which button or request does it. Never invent servers, players or commands.",
						"Facts:\n" + facts() + "\n" + who + " asks: " + question);
				if (question.isEmpty()) answer = "Ask me something after @ai, like \"@ai is the server up?\" or \"@ai why can't I join?\". I'm a small AI on the host's machine: I can be wrong.";
				if (answer.isEmpty()) answer = "I couldn't answer just now (the AI on the host's machine didn't reply). Ask the host.";
				Chat.say(roomId, NAME, "ai", answer.length() > 480 ? answer.substring(0, 477) + "..." : answer);
			} finally { busy.remove(roomId); }
		}, "ai-chat");
		th.setDaemon(true);
		th.start();
	}

	/** A server that ended without being asked to: the last lines it printed, explained in plain words (log and phone). */
	static void explainCrash(String name, List<String> lastLines, int code) {
		if (!on() || lastLines.isEmpty()) return;
		Thread th = new Thread(() -> {
			// (only lines that report a problem go to the model, with a few before each: a small model given nothing to go on
			// guesses a cause, so with no such line it isn't asked, and that is said plainly)
			List<String> tail = lastLines.subList(Math.max(0, lastLines.size() - 60), lastLines.size());
			java.util.regex.Pattern bad = java.util.regex.Pattern.compile("(?i)\\b(error|exception|fatal|crash|failed|caused by|cannot|can't|unable|out of memory)\\b");
			java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
			for (int i = 0; i < tail.size(); i++) if (bad.matcher(tail.get(i)).find()) for (int j = Math.max(0, i - 2); j <= Math.min(tail.size() - 1, i + 3); j++) picked.add(tail.get(j));
			String why;
			if (picked.isEmpty()) why = "";
			else {
				why = ask("You explain why a game server stopped, to its owner who is not a programmer. The lines given are the ones from its log that report a problem. "
						+ "In two short sentences: what the problem means in plain words, then one thing to try. Only from these lines; no code, no jargon.",
						"Server \"" + name + "\" stopped unexpectedly. The problem lines from its log:\n" + String.join("\n", picked));
				if (why.isEmpty()) return;
			}
			if (why.isEmpty()) { log("AI on why " + name + " stopped: its log shows no error, so there is nothing to explain (exit code " + code + ")"); return; }
			log("AI on why " + name + " stopped: " + why.replace('\n', ' '));
			Notify.down(name, "Why, perhaps (AI): " + (why.length() > 300 ? why.substring(0, 297) + "..." : why));
		}, "ai-explain");
		th.setDaemon(true);
		th.start();
	}

	/** /admin/ai: GET how it stands; POST {on} turns it on or off (the machine's own window only). */
	static boolean admin(com.sun.net.httpserver.HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/ai")) return false;
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			cfg.put("ai", ServerTools.yes(b.get("on")) ? "true" : "false");
			ServerTools.saveCfg();
			log("AI helper turned " + (on() ? "on" : "off"));
		}
		reply(x, 200, m("on", on(), "model", model(), "url", url(), "problem", ready(), "facts", facts()));
		return true;
	}
}
