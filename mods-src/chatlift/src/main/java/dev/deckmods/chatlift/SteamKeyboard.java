package dev.deckmods.chatlift;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Steam controller handling off the Steam Deck. On a Deck, SDL asks Steam for its keyboard whenever a text box is used.
 * On Windows the keyboard Steam opens on request is Big Picture's, which the trackpads can't reach and which covers the
 * text box, so nothing is opened here: the player brings up Steam's own keyboard (Steam + X), and chat is lifted above
 * it (see {@link ChatLift}). What this does keep up to date, through {@link ControlifyBridge}: Controlify's on-screen
 * keyboard only for non-Steam controllers, and mixed input on, so Steam's keyboard can type.
 */
public final class SteamKeyboard {
	private static final boolean CONTROLIFY = FabricLoader.getInstance().isModLoaded("controlify");

	private SteamKeyboard() {}

	/** A Steam controller is the current controller and the game is in controller mode. */
	public static boolean steamControllerInUse() {
		return CONTROLIFY && ControlifyBridge.steamControllerInUse();
	}

	/** Every client tick. */
	public static void tick() {
		if (CONTROLIFY) ControlifyBridge.applyKeyboardPreference();
	}
}
