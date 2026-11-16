package dev.octoberjourney.host;

import java.util.Map;

/**
 * The seam between Gatehouse Keeper and whatever program hosts the Minecraft server on this machine (docs/profiles.md,
 * stage 2). The Keeper asks for these things and nothing else; it never reaches the host program any other way.
 * MCSS (McssHost) is the only implementation so far. Another host program, or the Keeper running servers itself, is a
 * second implementation of this, with the rest of the Keeper unchanged.
 *
 * Results that are told to a person come back as {result, message}: result is "ok", "refused" (not allowed right now,
 * with why), "failed", or "wait" where noted.
 */
interface ServerHost {
	/** Is the host program itself running? */
	boolean running();
	/** Starts the host program (already running counts as ok). */
	String[] start();
	/** How closing it works right now: "close" (it quits when asked) or "force" (it has to be ended). */
	String closeMode();
	/** Closes the host program; refused while the Minecraft server runs. force: allow ending it when closeMode is "force". */
	String[] close(boolean force);

	/** The Minecraft server's state: 0 off, 1 on, 2 restarting, 3 starting, 4 stopping; -1 unknown. */
	int serverStatus();
	/** Asks for the Minecraft server to start or stop. */
	String[] serverAction(boolean start);

	/** Sends the Minecraft server a console command (as typed in its console, no leading slash). */
	String[] command(String command);

	/** Does the Keeper have access (a key or a sign-in) to the host program? */
	boolean hasAccess();
	/** How the host program takes that access: "yes", "forbidden" (known, but no right to this server), "unknown"
	 *  (not recognised), "no" (not answering). */
	String accessState();
	/** The account the Keeper is signed in as, or empty (a key was entered, or nothing). */
	String user();
	/** Signs the Keeper in with an account on the host program; what's handed back is kept, never the password. */
	String[] signIn(String user, String password);
	/** Sets (or, when empty, clears) the Keeper's key for the host program. */
	void setKey(String key);
	/** Troubleshooting: what the host program answers to the Keeper's access. Never contains the access itself. */
	Map<String, Object> diagnose();
}
