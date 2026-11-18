package dev.octoberjourney.popup;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The games a server can be for, as Gatehouse sees them (R34; docs/other-games.md). A Gatekeeper's catalog says each
 * server's game ("game"). Gatehouse plays only Minecraft so far, through Prism Launcher; a server of another game is
 * shown, with its game named, but has nothing to install or play here yet.
 *
 * Nothing is Minecraft by default: Gatehouse starts neutral, and Minecraft's own parts (Prism Launcher, the Minecraft
 * account, the Open Prism Launcher button) show only once this device has a Minecraft server installed or a Gatekeeper
 * has shown it one. A Gatekeeper from before games names none; it only ever had Minecraft servers, so its are Minecraft.
 */
final class Games {
	private Games() {}
	/** The games this Gatehouse can play, by id, with their names. */
	static final Map<String, String> PLAYABLE = Map.of("minecraft", "Minecraft");
	/** Names of games Gatehouse knows of but can't play yet, for saying so. */
	private static final Map<String, String> NAMES = Map.of("minecraft", "Minecraft", "valheim", "Valheim", "palworld", "Palworld",
			"terraria", "Terraria", "factorio", "Factorio", "zomboid", "Project Zomboid", "7dtd", "7 Days to Die");
	/** The games this device's Gatekeepers have shown it servers of (since this window opened). */
	static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

	/** The game a catalog entry is for. */
	static String of(Map<String, Object> server) {
		Object g = server.get("game");
		if (g == null) return "minecraft";   // (a Gatekeeper from before games: it only had Minecraft servers)
		return String.valueOf(g).trim();
	}
	/** Notes the games in a catalog's servers. */
	static void seen(Iterable<?> servers) {
		for (Object o : servers) if (o instanceof Map<?, ?> m) { @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) m; SEEN.add(of(s)); }
	}
	static boolean playable(String game) { return PLAYABLE.containsKey(game); }
	/** A game's name for people; "an unknown game" when it has none. */
	static String name(String game) {
		if (game == null || game.isEmpty()) return "an unknown game";
		return NAMES.getOrDefault(game, game.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + game.substring(1));
	}
	/** Is Minecraft part of this device's use: a Minecraft server installed here, or one shown by a Gatekeeper? */
	static boolean minecraftHere(boolean installedAny) { return installedAny || SEEN.contains("minecraft"); }
}
