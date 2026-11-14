package dev.deckmods.chatlift;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.InputMode;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.config.settings.GlobalSettings;
import dev.isxander.controlify.config.settings.profile.GenericControllerSettings;
import dev.isxander.controlify.controller.ControllerEntity;

/**
 * Everything that touches Controlify, kept apart so the mod still loads without it. Only used when Controlify is present.
 */
final class ControlifyBridge {
	private static Object lastController;

	private ControlifyBridge() {}

	private static boolean isSteam(ControllerEntity c) {
		String path = c.info().type().namespace().getPath();
		return path.contains("steam");
	}

	/** A Steam Controller (or Steam Deck controls) is connected, current, and the game is in controller mode. */
	static boolean steamControllerInUse() {
		try {
			ControlifyApi api = ControlifyApi.get();
			if (!api.currentInputMode().isController()) return false;
			return api.getCurrentController().map(ControlifyBridge::isSteam).orElse(false);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Controlify's on-screen keyboard only for controllers without Steam's keyboard: off for Steam controllers (Steam's
	 * keyboard is better and opens by itself), on for the rest. Set once each time the current controller changes, so a
	 * choice made in Controlify's settings for that controller still holds until it changes again.
	 */
	static void applyKeyboardPreference() {
		try {
			ControllerEntity c = ControlifyApi.get().getCurrentController().orElse(null);
			if (c != null && isSteam(c)) allowSteamKeyboardTyping();
			if (c == null || c == lastController) return;
			lastController = c;
			GenericControllerSettings.KeyboardSettings k = c.settings().generic.keyboard;
			boolean want = !isSteam(c);
			if (k.showOnScreenKeyboard != want) {
				k.showOnScreenKeyboard = want;
				ChatLift.LOG.info("[chatlift] {}: Controlify on-screen keyboard {}", c.info().type().namespace(), want ? "on" : "off (Steam keyboard)");
			}
		} catch (Throwable t) {
			// Controlify changed shape: leave its settings alone.
		}
	}

	/**
	 * Steam's keyboard types by sending ordinary key presses. With Controlify's "mixed input" off, those presses flip the
	 * game to keyboard-and-mouse mode: the text never lands and the controller stops working until you touch the mouse.
	 * The Deck has mixed input on, which is why it works there; turn it on wherever a Steam controller is used.
	 */
	private static void allowSteamKeyboardTyping() {
		Controlify ctl = Controlify.instance();
		GlobalSettings g = ctl.config().getSettings().globalSettings();
		if (!g.mixedInput) {
			g.mixedInput = true;
			ctl.config().markDirty();
			ctl.config().saveIfDirty();
			ChatLift.LOG.info("[chatlift] Controlify mixed input on, so Steam's keyboard can type with a Steam controller");
		}
		ControlifyApi api = ControlifyApi.get();
		if (api.currentInputMode() == InputMode.CONTROLLER) api.setInputMode(InputMode.MIXED);
	}
}
