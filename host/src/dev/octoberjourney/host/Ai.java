package dev.octoberjourney.host;

import static dev.octoberjourney.host.HostHelper.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
 * It also knows about Gatekeeper itself (R60 follow-up): its version, its GitHub page and guide, what each section of
 * its window is for; and, only when the owner asks it in Gatekeeper's own window (Help, Ask Gatekeeper AI), where its
 * files and servers are on this machine. Folders are never given to it for a chat room, which players read.
 * The Ollama it uses can be set in Options (address and model; another machine's Ollama gets the questions then).
 * Or the built-in AI (R66, BuiltinAi): opt-in, Gatekeeper's own small model, for machines without Ollama.
 * When it can't answer (off, or no model), an @ai message still gets one short reply saying why (R60); and a
 * Gatekeeper without an AI of its own passes @ai questions to the Gatekeeper it joined, if that one has one
 * (POST /v1/ai/ask, constellation members only; host.json "ai_forward", on by default).
 * In a chat room it also reads that room (R65): recent messages, and counts Gatekeeper works out exactly (messages per
 * person, files offered, who mentioned a word), so it can answer questions about the conversation. Only the room asked
 * in, which everyone there can already read.
 */
final class Ai {
	private Ai() {}
	static final String NAME = "Gatekeeper AI";
	private static final Map<String, Long> busy = new ConcurrentHashMap<>();

	static boolean on() { return "true".equals(setting("ai", "false")); }
	static String url() { return setting("ollama_url", "http://127.0.0.1:11434").replaceAll("/+$", ""); }
	/** Is the address this machine itself (so nothing leaves it)? */
	static boolean local(String u) { return u.matches("(?i)https?://(127\\.\\d+\\.\\d+\\.\\d+|localhost|\\[::1\\])(:\\d+)?/?"); }
	static final List<String> models = new java.util.concurrent.CopyOnWriteArrayList<>();   // (what Ollama said it has, last asked)
	static String model() { return builtinChosen() ? BuiltinAi.MODEL_NAME : setting("ollama_model", "qwen2.5:3b"); }
	/** Answers come from the built-in AI (opted in) rather than Ollama. */
	static boolean builtinChosen() { return "builtin".equals(setting("ai_engine", "ollama")); }
	static boolean forward() { return !"false".equals(setting("ai_forward", "true")); }

	/** Does Ollama answer here, with the model? "" when it does, else what is missing, in words. */
	static String ready() {
		if (builtinChosen()) {
			String no = BuiltinAi.notHere();
			if (!no.isEmpty()) return no;
			return BuiltinAi.installed() ? "" : "The built-in AI isn't downloaded yet: Options, AI helper, Get the built-in AI";
		}
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(url() + "/api/tags").openConnection();
			c.setConnectTimeout(1500); c.setReadTimeout(3000);
			String t = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			List<String> have = new java.util.ArrayList<>();
			for (Object o : Json.list(Json.map(Json.parse(t)).get("models"))) have.add(Json.str(Json.map(o).get("name")));
			models.clear(); models.addAll(have);
			if (have.contains(model())) return "";
			return "Ollama answers, but doesn't have " + model() + (have.isEmpty() ? "" : " (it has " + String.join(", ", have) + ")")
					+ ". In a terminal on that machine: ollama pull " + model();
		} catch (IOException | RuntimeException e) {
			models.clear();
			return local(url()) ? "Ollama isn't answering on this machine (" + url() + "): install it from ollama.com, or start it"
					: "Ollama isn't answering at " + url() + ": is it on, and does it listen beyond its own machine (OLLAMA_HOST=0.0.0.0)?";
		}
	}

	/** One answer from the model, or "" when it couldn't give one. Blocking (up to about a minute and a half). */
	static String ask(String system, String prompt) { return ask(system, prompt, 220); }
	static String ask(String system, String prompt, int words) { return ask(system, prompt, words, 4096); }
	static String ask(String system, String prompt, int words, int context) {
		if (builtinChosen()) return BuiltinAi.notHere().isEmpty() && BuiltinAi.installed() ? BuiltinAi.answer(system, prompt, words) : "";
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(url() + "/api/generate").openConnection();
			c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(2000); c.setReadTimeout(90_000);
			c.setRequestProperty("Content-Type", "application/json");
			c.getOutputStream().write(Json.write(m("model", model(), "system", system, "prompt", prompt, "stream", false,
					"options", m("temperature", 0.2, "num_predict", words, "num_ctx", context))).getBytes(StandardCharsets.UTF_8));
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
		int count = servers().size();
		b.append("It hosts ").append(count == 0 ? "no Minecraft servers yet" : count == 1 ? "1 Minecraft server" : count + " Minecraft servers").append(".\n");
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
		b.append(about());
		b.append("How players join: in the Gatehouse launcher, open this Gatekeeper, pick the server, press Install (once) then Launch and Play. "
				+ "A server that is off: press Start if the host allowed it, else Request server. Not on the whitelist: Request whitelist. "
				+ "Can't connect: the server's Connection tab has a network check that says what is in the way (Tailscale, the host's machine asleep, the server off).\n");
		return b.toString();
	}

	/** Gatekeeper itself, for the model: what anyone may know (no folders, addresses or passwords). */
	static String about() {
		return "About Gatekeeper: Gatekeeper is the hosting half of Gatehouse (players use the Gatehouse launcher; the host runs Gatekeeper). "
				+ "This one is version " + ownVersion() + ". "
				+ "It runs its servers " + (builtin() ? "itself" : "through MC Server Soft (MCSS)") + ". "
				+ "Its GitHub page (source, releases, problems): " + dev.gatehouse.look.Links.REPO + " . The user guide: " + dev.gatehouse.look.Links.GUIDE + " .\n"
				+ "Gatekeeper's window: along the top SERVERS (a card for each server: Start, Stop, and Manage, with tabs such as Connection (with a network check), "
				+ "Pack (the mods players get, Find mods on Modrinth), Backups, Game settings and Console; New server is here too), PLAYERS (who may join and what each may do), "
				+ "CHAT (chat rooms with players) and UPDATES (the program's updates and their notes). The menu on the left: Host setup (this machine: MCSS or "
				+ "Gatekeeper running servers itself, folders), Activity log (what Gatekeeper did), Web panel (manage it from a phone or browser), Constellation "
				+ "and Systems (other machines with Gatehouse), History of servers, Options (notifications to a phone, the AI helper, crash watch), Setup guide, "
				+ "and Help (User guide, GitHub page, Ask Gatekeeper AI).\n";
	}
	/** Where things are on this machine: only for the owner's own questions in Gatekeeper's window, never a chat room. */
	static String places() {
		StringBuilder b = new StringBuilder("Where things are on this machine (only the owner sees this): Gatekeeper's folder " + dir.toAbsolutePath()
				+ " (its settings host.json, its log host.log, the players and devices it knows in devices.json, packs it builds in packs). ");
		b.append("New servers go in ").append(serversDir()).append(". ");
		String keep = SERVER.get();
		try {
			for (Object o : servers()) {
				Map<String, Object> s = Json.map(o);
				SERVER.set(Json.str(s.get("id")));
				String d = Json.str(s.get("dir"));
				if (!d.isEmpty()) b.append("Server \"").append(Json.str(s.get("name"))).append("\" is in ").append(d).append(", its backups in ").append(ServerTools.backupDir()).append(". ");
			}
		} catch (RuntimeException ignored) { dev.gatehouse.look.Quiet.seen(ignored); }
		finally { SERVER.set(keep); }
		return b.append("\n").toString();
	}

	/** The owner's own question, from Gatekeeper's window: answered with everything, folders included. Blocking. */
	static String ownerAsks(String question) {
		if (question.isBlank()) return "Ask something, like \"where are my backups?\" or \"how do I add a mod?\".";
		String why = ready();
		if (!why.isEmpty()) return why + ".";
		String a = ask("You are Gatekeeper AI, helping the owner of a small private game server group use Gatekeeper, the program they host with. "
				+ "Answer plainly in at most five short sentences, only from the facts given; name the section and button to use. "
				+ "If the facts don't say, say you don't know and point to the user guide or the GitHub page. Never invent buttons, folders or versions.",
				"Facts:\n" + facts() + places() + "\nQuestion from the owner: " + question.strip(), 320);
		return a.isEmpty() ? "The AI didn't answer just now (" + (builtinChosen() ? "the built-in AI" : "Ollama at " + url()) + "). Try again in a moment." : a;
	}

	private static final Map<String, Long> toldOff = new ConcurrentHashMap<>();   // (room -> when it last said it can't answer)
	static final String CHAT_RULES = "Answer in at most four short sentences, plainly, only from the facts and the chat transcript given. "
			+ "Numbers under 'Exact counts' are correct: use them, never count yourself. If the facts don't say, say you don't know and suggest asking the host. "
			+ "You cannot start, stop, install, allow, ban or change anything: if asked to, say so and say which button or request does it. "
			+ "Never invent servers, players, messages or commands.";

	/** A chat message: answered in its room when it starts with @ai. Off the caller's thread. */
	static void heard(String roomId, String who, String text) {
		if (who.equals(NAME)) return;
		String t = text.strip();
		if (!t.regionMatches(true, 0, "@ai", 0, 3) || (t.length() > 3 && Character.isLetterOrDigit(t.charAt(3)))) return;
		String question = t.substring(3).replaceFirst("^[\\s:,]+", "");
		Long since = busy.get(roomId);
		if (since != null && System.currentTimeMillis() - since < 120_000) return;   // (one question at a time in a room)
		busy.put(roomId, System.currentTimeMillis());
		Thread th = new Thread(() -> {
			try {
				String room = Json.str(Chat.all().get(roomId));
				if (question.isEmpty()) { Chat.say(roomId, NAME, "ai", "Ask me something after @ai, like \"@ai is the server up?\" or \"@ai who has shared files here?\". I'm a small AI: I can be wrong."); return; }
				String context = roomContext(roomId, question);
				String answer;
				String mine = on() ? ready() : "off";
				if (mine.isEmpty()) {
					answer = ask("You are Gatekeeper AI, a helper in the chat room \"" + room + "\" of a small private game server group. " + CHAT_RULES,
							"Facts:\n" + facts() + context + "\nQuestion from " + who + ": " + question, 220, 8192);
					if (answer.isEmpty()) answer = "I couldn't answer just now (the AI on the host's machine didn't reply). Ask the host.";
				} else {
					// (no AI of its own here: the Gatekeeper it joined may have one)
					answer = forward() ? askLead(room, who, question, facts() + context) : "";
					if (answer.isEmpty()) {
						Long told = toldOff.get(roomId);
						if (told != null && System.currentTimeMillis() - told < 10 * 60_000) return;   // (said once; not again for 10 minutes)
						toldOff.put(roomId, System.currentTimeMillis());
						answer = mine.equals("off")
								? "The AI helper is off on this Gatekeeper (" + setting("name", "this one") + "). The host can turn it on in Options, AI helper."
								: "The AI helper can't answer here right now: " + mine + ".";
					}
				}
				Chat.say(roomId, NAME, "ai", answer.length() > 600 ? answer.substring(0, 597) + "..." : answer);
			} finally { busy.remove(roomId); }
		}, "ai-chat");
		th.setDaemon(true);
		th.start();
	}

	/** The room, for the model (R65): exact counts worked out here, then the recent messages. Only this room. */
	static String roomContext(String roomId, String question) {
		List<Object> all = Chat.recent(roomId);
		Map<String, Integer> perWho = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		List<String> files = new java.util.ArrayList<>();
		java.util.Set<String> sharers = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		String first = "", last = "";
		int people = 0;
		for (Object o : all) {
			Map<String, Object> msg = Json.map(o);
			String w = Json.str(msg.get("who")), when = Json.str(msg.get("when"));
			if (w.equals(NAME)) continue;
			if (first.isEmpty()) first = when;
			last = when;
			perWho.merge(w, 1, Integer::sum);
			if (msg.get("file") != null) {
				Map<String, Object> f = Json.map(msg.get("file"));
				sharers.add(w);
				files.add(w + " shared " + Json.str(f.get("name")) + " (" + Json.str(f.get("size")) + " bytes) at " + when);
			}
		}
		people = perWho.size();
		StringBuilder b = new StringBuilder("\nExact counts for this chat room (worked out by Gatekeeper, correct): ");
		b.append(perWho.values().stream().mapToInt(Integer::intValue).sum()).append(" messages from ").append(people).append(" people");
		if (!first.isEmpty()) b.append(", from ").append(first).append(" to ").append(last);
		b.append(". Messages per person: ");
		perWho.forEach((w, n) -> b.append(w).append(' ').append(n).append("; "));
		b.append("\nFiles shared in this room: ").append(files.isEmpty() ? "none." : files.size() + " (" + String.join("; ", files.subList(Math.max(0, files.size() - 15), files.size())) + ").")
				.append(" People who shared files: ").append(sharers.isEmpty() ? "nobody." : String.join(", ", sharers) + ".");
		// "how many mentioned X", "who said X": counted exactly
		java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?i)(?:mention(?:ed|s|ing)?|said|says|talk(?:ed|ing)? about|asked about)\\s+(?:the\\s+word\\s+)?[\"'\u201c]?([\\p{L}\\p{N}][\\p{L}\\p{N} '_.-]{0,40}?)[\"'\u201d]?\\s*(?:\\?|$|\\bin\\b|\\bhere\\b)").matcher(question);
		if (mm.find()) {
			String term = mm.group(1).strip();
			java.util.Set<String> whoSaid = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			int times = 0;
			for (Object o : all) {
				Map<String, Object> msg = Json.map(o);
				String w = Json.str(msg.get("who")), txt = Json.str(msg.get("text"));
				if (w.equals(NAME) || txt.regionMatches(true, 0, "@ai", 0, 3)) continue;
				if (txt.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) { times++; whoSaid.add(w); }
			}
			b.append(" Mentions of \"").append(term).append("\": ").append(times).append(" messages by ").append(whoSaid.size()).append(" people")
					.append(whoSaid.isEmpty() ? "." : " (" + String.join(", ", whoSaid) + ").");
		}
		b.append("\nThe latest messages in this room, oldest first:\n");
		List<Object> tail = all.subList(Math.max(0, all.size() - 60), all.size());
		for (Object o : tail) {
			Map<String, Object> msg = Json.map(o);
			String w = Json.str(msg.get("who"));
			if (w.equals(NAME) || Json.str(msg.get("text")).regionMatches(true, 0, "@ai", 0, 3)) continue;   // (earlier questions to the AI only confuse a small model)
			String txt = msg.get("file") != null ? "[offered a file: " + Json.str(Json.map(msg.get("file")).get("name")) + "]" : Json.str(msg.get("text"));
			b.append(Json.str(msg.get("when"))).append(' ').append(w).append(": ").append(txt.length() > 200 ? txt.substring(0, 197) + "..." : txt).append('\n');
		}
		return b.toString();
	}

	/** Asks the Gatekeeper this one joined (its constellation lead), or "" when that isn't possible. */
	private static String askLead(String room, String who, String question, String factsAndRoom) {
		String lead = setting("constellation_lead", "");
		if (lead.isEmpty()) return "";
		try {
			HttpURLConnection c = (HttpURLConnection) new URL(lead.replaceAll("/+$", "") + "/v1/ai/ask").openConnection();
			c.setConnectTimeout(3000); c.setReadTimeout(150_000); c.setRequestMethod("POST"); c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			c.setRequestProperty("X-Device", setting("constellation_id", "")); c.setRequestProperty("X-Token", setting("constellation_token", ""));
			c.getOutputStream().write(Json.write(m("room", room, "who", who, "question", question, "facts", factsAndRoom,
					"from", setting("name", ""))).getBytes(StandardCharsets.UTF_8));
			if (c.getResponseCode() != 200) return "";
			Map<String, Object> r = Json.map(Json.parse(new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
			return Json.str(r.get("answer")).strip();
		} catch (IOException | RuntimeException e) { return ""; }
	}
	/** POST /v1/ai/ask: a Gatekeeper that joined this one asks on behalf of its room (constellation members only). */
	static void forwarded(com.sun.net.httpserver.HttpExchange x, String asker, Map<String, Object> d) throws IOException {
		if (!ServerTools.yes(d.get("constellation"))) { reply(x, 200, m("answer", "", "why", "Only a Gatekeeper that joined this one may ask its AI.")); return; }
		String mine = on() ? ready() : "off";
		if (!mine.isEmpty()) { reply(x, 200, m("answer", "", "why", mine)); return; }
		Map<String, Object> b = body(x);
		String q = Json.str(b.get("question")), facts = Json.str(b.get("facts"));
		if (q.isBlank() || facts.length() > 60_000) { reply(x, 200, m("answer", "", "why", "nothing to answer")); return; }
		String a = ask("You are Gatekeeper AI, a helper in the chat room \"" + Json.str(b.get("room")) + "\" on the Gatekeeper \"" + Json.str(b.get("from"))
				+ "\" of a small private game server group (answering for it from another machine). " + CHAT_RULES,
				"Facts:\n" + facts + "\nQuestion from " + Json.str(b.get("who")) + ": " + q, 220, 8192);
		log("AI helper: answered a question for " + asker);
		reply(x, 200, m("answer", a));
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

	/** /admin/ai: GET how it stands; POST {on?, url?, model?} changes it. /admin/ai/ask: POST {question} -> {answer}. */
	static boolean admin(com.sun.net.httpserver.HttpExchange x, String path) throws IOException {
		if (path.equals("/admin/ai/ask")) {
			reply(x, 200, m("answer", ownerAsks(Json.str(body(x).get("question")))));
			return true;
		}
		if (!path.equals("/admin/ai")) return false;
		String said = "";
		if (x.getRequestMethod().equals("POST")) {
			Map<String, Object> b = body(x);
			if (b.containsKey("on")) {
				cfg.put("ai", ServerTools.yes(b.get("on")) ? "true" : "false");
				log("AI helper turned " + (on() ? "on" : "off"));
			}
			if (b.containsKey("url")) {
				String u = Json.str(b.get("url")).strip().replaceAll("/+$", "");
				if (u.isEmpty()) cfg.remove("ollama_url");
				else if (!u.matches("(?i)https?://([A-Za-z0-9.\\-]+|\\[[0-9a-f:]+\\])(:\\d{1,5})?")) said = "That isn't an address like http://127.0.0.1:11434, so it wasn't kept.";
				else { cfg.put("ollama_url", u); log("AI helper: Ollama at " + u); }
			}
			if (b.containsKey("engine")) {
					String e = Json.str(b.get("engine"));
					if (e.equals("builtin") && !BuiltinAi.notHere().isEmpty()) said = BuiltinAi.notHere();
					else if (e.equals("builtin") && !BuiltinAi.installed()) said = "Get the built-in AI first.";
					else if (e.equals("builtin") || e.equals("ollama")) { cfg.put("ai_engine", e); log("AI helper: answers from " + (e.equals("builtin") ? "the built-in AI" : "Ollama")); }
				}
			if (b.containsKey("forward")) cfg.put("ai_forward", ServerTools.yes(b.get("forward")) ? "true" : "false");
			if (b.containsKey("model")) {
				String md = Json.str(b.get("model")).strip();
				if (md.isEmpty()) cfg.remove("ollama_model");
				else if (!md.matches("[A-Za-z0-9._:/\\-]{1,80}")) said = "That isn't a model name like qwen2.5:3b, so it wasn't kept.";
				else { cfg.put("ollama_model", md); log("AI helper: model " + md); }
			}
			ServerTools.saveCfg();
		}
		String problem = ready();
		// (a machine with no AI of its own, like a Steam Deck: say first that @ai still gets answered, by the Gatekeeper it joined)
		if (!problem.isEmpty() && forward() && !setting("constellation_lead", "").isEmpty())
			problem = "No AI on this machine, so @ai questions in its chat rooms are answered by "
					+ (AutoUpdate.leadName.isBlank() ? "the Gatekeeper it joined" : AutoUpdate.leadName + ", the Gatekeeper it joined")
					+ ". Nothing to set up here. (" + problem + ")";
		reply(x, 200, m("on", on(), "model", model(), "url", url(), "local", local(url()), "models", new java.util.ArrayList<>(models),
				"problem", problem, "said", said, "facts", facts(), "engine", builtinChosen() ? "builtin" : "ollama", "forward", forward(),
				"lead", !setting("constellation_lead", "").isEmpty(), "builtin", BuiltinAi.state()));
		return true;
	}
	/** /admin/ai/builtin POST {action: get|remove}: the owner opts in (fetch) or out (remove). The fetch runs in the background. */
	static boolean builtinAdmin(com.sun.net.httpserver.HttpExchange x, String path) throws IOException {
		if (!path.equals("/admin/ai/builtin")) return false;
		if (x.getRequestMethod().equals("POST")) {
			String act = Json.str(body(x).get("action"));
			if (act.equals("get")) {
				String no = BuiltinAi.notHere();
				if (!no.isEmpty()) { reply(x, 200, m("result", "refused", "message", no)); return true; }
				if (!BuiltinAi.fetching().isEmpty()) { reply(x, 200, m("result", "ok", "message", "Already fetching.")); return true; }
				Thread t = new Thread(() -> {
					try { BuiltinAi.fetch(); cfg.put("ai_engine", "builtin"); ServerTools.saveCfg(); }
					catch (IOException | RuntimeException e) { log("AI helper: the built-in AI wasn't fetched: " + e.getMessage()); lastFetchProblem = e.getMessage(); }
				}, "builtin-ai-fetch");
				t.setDaemon(true); t.start();
				lastFetchProblem = "";
				reply(x, 200, m("result", "ok", "message", "Fetching the built-in AI (" + BuiltinAi.sizes() + ")."));
				return true;
			}
			if (act.equals("remove")) {
				BuiltinAi.remove();
				cfg.put("ai_engine", "ollama"); ServerTools.saveCfg();
				reply(x, 200, m("result", "ok", "message", "The built-in AI was removed; answers come from Ollama again."));
				return true;
			}
		}
		Map<String, Object> s = new java.util.LinkedHashMap<>(BuiltinAi.state());
		s.put("problem", lastFetchProblem);
		reply(x, 200, s);
		return true;
	}
	private static volatile String lastFetchProblem = "";
}
