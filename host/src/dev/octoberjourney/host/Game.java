package dev.octoberjourney.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A game Gatekeeper can host (R34; docs/other-games.md). Everything Gatekeeper does that depends on which game a server
 * is for goes through here: how its server is recognised and started, what its console and log say, its settings, who
 * may join it, and how a backup holds its saving still. The rest of Gatekeeper (players and their launchers, requests,
 * chat, updates, the web panel) is the same for every game.
 *
 * Nothing is assumed: each server in host.json names its game ("game"). A server without one has no game here (of()
 * gives null), and Gatekeeper says so instead of treating it as Minecraft. Servers set up before games existed were all
 * Minecraft servers, and are written down as such once, when Gatekeeper starts (HostHelper.recordGames).
 *
 * Minecraft is the only game so far.
 */
interface Game {
	/** What a server prints, kept in its folder, for games whose own log is just that (GameFile). */
	String CONSOLE_LOG = "gatekeeper-console.log";
	/** The games Gatekeeper knows (filled the first time it is asked, see all()). */
	Map<String, Game> KNOWN = new LinkedHashMap<>();
	/** Every game Gatekeeper knows, by id, in the order they are offered: Minecraft, then each definition in
	 *  Gatekeeper's folder (games/<id>.json, R34 stage 2), read once. */
	static Map<String, Game> all() {
		synchronized (KNOWN) {
			if (KNOWN.isEmpty()) {
				Game mc = new Minecraft(), pw = new Palworld();
				KNOWN.put(mc.id(), mc);
				KNOWN.put(pw.id(), pw);
				Path dir = HostHelper.dir == null ? null : HostHelper.dir.resolve("games");
				if (dir != null && java.nio.file.Files.isDirectory(dir)) try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(dir)) {
					s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(f -> { GameFile g = GameFile.load(f); if (g != null) KNOWN.putIfAbsent(g.id(), g); });
				} catch (IOException | RuntimeException ignored) { dev.gatehouse.look.Quiet.seen(ignored); }
			}
			return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(KNOWN));
		}
	}
	/** The game a server (an entry of host.json's servers) is for, or null when it names none Gatekeeper knows. */
	static Game of(Map<String, Object> server) { return server == null ? null : named(Json.str(server.get("game"))); }
	/** A game by its id ("minecraft"), or null. */
	static Game named(String id) { return id == null ? null : all().get(id); }
	/** The current server's game, or null. */
	static Game here() { return of(HostHelper.server()); }
	/** A game's name for people ("Minecraft"); "no game" when there is none. */
	static String nameOf(Game g) { return g == null ? "no game" : g.name(); }

	/** Its id in host.json ("minecraft"). */
	String id();
	/** Its name for people ("Minecraft"). */
	String name();

	// ------------------------------------------------------------------ R34 stage 2: what a game may have (Minecraft has none of these)

	/** Its dedicated server's Steam app id, for SteamCMD to install and update it; 0 for none. */
	default int steamServerApp() { return 0; }
	/** The game's own Steam app id, which players have; 0 for none. */
	default int steamGameApp() { return 0; }
	/** How a player joins from Gatehouse: a link with {app} and {address} ("steam://run/{app}//+connect {address}"), or "". */
	default String join() { return ""; }
	/** The port a new server of it gets unless another is free or chosen. */
	default String defaultPort() { return ""; }
	/** Steam's server query port for the server in that folder (it says whether it is up and who is on), or 0. */
	default int queryPort(Path d) { return 0; }
	/** Is what it prints its log (kept in CONSOLE_LOG in its folder)? */
	default boolean keepsConsole() { return false; }
	/** What its list of who may join is called ("whitelist"). */
	default String allowWord() { return "whitelist"; }
	/** What that list holds: "names" (Minecraft names), "steam_ids", "password" (no list: a password), or "none". */
	default String allowKind() { return "names"; }
	/** After its server is installed, before it first starts: what it needs set up (Palworld: its settings file). */
	default void prepare(Path d, Map<String, Object> server) throws IOException { }
	/** Is it up, by asking it (for a game whose console doesn't say so)? */
	default boolean answers(Path d) { return false; }
	/** Asks it to save its world; true when it did (a backup then needs no console commands). */
	default boolean saveNow(Path d) { return false; }
	/** Asks it to save and stop by itself; true when it took the request. */
	default boolean stopItself(Path d) { return false; }
	/** Who is on, as it says ({name, Steam ID or ""}), or null when it can't be asked. */
	default List<String[]> playersNow(Path d) { return null; }
	/** Its terms for running a server (a page), or "" when there are none to accept. */
	default String termsUrl() { return ""; }
	default String termsName() { return ""; }

	// ------------------------------------------------------------------ its server's folder and program

	/** Does that folder look like this game's server? */
	boolean looksLikeServer(Path d);
	/** The command that starts the server (an entry of host.json's servers) in its folder, or null when there is nothing to start. */
	List<String> launch(Map<String, Object> s);
	/** Why the server can't be started as it stands (its program missing, its terms not accepted...), or "" when it can. */
	String blocker(Map<String, Object> s);
	/** Has the owner accepted the terms this game's server needs before it runs (Minecraft: its EULA)? */
	boolean termsAccepted(Path d);

	// ------------------------------------------------------------------ its console and its log

	/** Is this console line the server saying it has finished starting? */
	boolean readyLine(String line);
	/** What is typed into its console to stop it (it saves first). */
	String stopCommand();
	/** Its own log file in its folder. */
	Path log(Path d);
	/** A line saying a player joined: {name, the address they joined from, the time on the line ("" if none)}, or null. */
	String[] joined(String line);
	/** A line saying a player left: their name, or null. */
	String left(String line);
	/** Is this a line saying the server is stopping (so nobody is on it any more)? */
	boolean stoppingLine(String line);
	/** How many times this text (from its log) says the server couldn't keep up. */
	int lagIn(String text);
	/** What the folder says about it: {"vanilla" or "modded", its version or ""}. */
	String[] facts(Path d);
	/** Is its world open right now (held by a running server)? */
	boolean worldInUse(Path d);

	// ------------------------------------------------------------------ its settings

	/** Its own settings (Minecraft: server.properties), as key and value. */
	Map<String, String> settings(Path d);
	/** Sets some of those, leaving the rest as they are. */
	void setSettings(Path d, Map<String, String> set) throws IOException;
	/** The port its settings give it, or "" when they don't say. */
	String port(Path d);

	// ------------------------------------------------------------------ who may join (the current server)

	/** Is this a player name this game has? */
	boolean validPlayer(String name);
	/** Who is on its list of players allowed to join, each {name, ...}. */
	List<Object> allowList(Path d);
	/** Is that list in force (only players on it can join)? null when its settings don't say. */
	Boolean allowListInForce(Path d);
	/** Puts a player on the current server's list, or takes them off. */
	String[] setAllowed(String name, boolean on);
	/** Puts a player on the current server's list with the account id their launcher gave, when the server itself can't look it up. */
	String[] allowWithId(String name, String id);
	/** Turns the current server's list on: {"done" or "not", what happened, for a person}. */
	String[] allowListOn();

	// ------------------------------------------------------------------ backups

	/** The folder (at the top of the server's) that holds its world, for a world-only backup. */
	String worldFolder(Path d);
	/** Typed into its console before its files are copied, so it holds its saving still. */
	List<String> beforeBackup();
	/** Is this console line the server saying it has saved? */
	boolean savedLine(String line);
	/** Typed into its console once the copy is made. */
	List<String> afterBackup();
}
