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
	/** Every game Gatekeeper knows, by id, in the order they are offered. */
	Map<String, Game> ALL = all();
	private static Map<String, Game> all() {
		Map<String, Game> m = new LinkedHashMap<>();
		Game mc = new Minecraft();
		m.put(mc.id(), mc);
		return java.util.Collections.unmodifiableMap(m);
	}
	/** The game a server (an entry of host.json's servers) is for, or null when it names none Gatekeeper knows. */
	static Game of(Map<String, Object> server) { return server == null ? null : named(Json.str(server.get("game"))); }
	/** A game by its id ("minecraft"), or null. */
	static Game named(String id) { return id == null ? null : ALL.get(id); }
	/** The current server's game, or null. */
	static Game here() { return of(HostHelper.server()); }
	/** A game's name for people ("Minecraft"); "no game" when there is none. */
	static String nameOf(Game g) { return g == null ? "no game" : g.name(); }

	/** Its id in host.json ("minecraft"). */
	String id();
	/** Its name for people ("Minecraft"). */
	String name();

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
