package dev.deckmods.chatlift;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Steam's floating keyboard for every text box, off the Steam Deck. On a Deck, SDL already asks Steam for its keyboard,
 * placed beside the text box, whenever the game starts text input. Elsewhere it doesn't, so this does the same when a
 * Steam controller is being used under Steam: a text box gaining focus opens the keyboard next to it, losing focus
 * closes it. Mouse and keyboard play is left alone. It also keeps Controlify's own on-screen keyboard for the other
 * controllers only (see {@link ControlifyBridge}).
 */
public final class SteamKeyboard {
	private static final boolean CONTROLIFY = FabricLoader.getInstance().isModLoaded("controlify");
	private static final boolean DECK = ChatLift.isSteamDeck();

	private static int x, y, w, h;
	private static boolean hasArea;
	private static Object owner;
	private static int pendingTicks = -1;
	private static boolean open;

	private SteamKeyboard() {}

	/** The text box's rectangle in window coordinates, as Minecraft hands it to SDL. */
	public static void onArea(int ax, int ay, int aw, int ah) {
		x = ax; y = ay; w = aw; h = ah; hasArea = true;
	}

	/** Text input started for a text box (owner). The keyboard opens a moment later, once the box has its final area. */
	public static void onStart(Object newOwner) {
		if (newOwner != owner || !open) {
			owner = newOwner;
			pendingTicks = 2;
		}
	}

	public static void onStop() {
		pendingTicks = -1;
		owner = null;
		if (open) {
			open = false;
			ChatLift.openLink(ChatLift.closeUrl());
		}
	}

	/** Every client tick. */
	public static void tick() {
		boolean steamController = CONTROLIFY && ControlifyBridge.steamControllerInUse();
		if (CONTROLIFY) ControlifyBridge.applyKeyboardPreference();
		if (pendingTicks < 0) return;
		if (--pendingTicks > 0) return;
		pendingTicks = -1;
		if (DECK || !ChatLift.underSteam() || !steamController || !"auto".equals(ChatLift.mode())) return;
		String url = ChatLift.openUrl();
		if (url.isEmpty()) return;
		if (hasArea && !url.contains("?")) {
			url += "?XPosition=" + x + "&YPosition=" + y + "&Width=" + Math.max(w, 1) + "&Height=" + Math.max(h, 1) + "&Mode=0";
		}
		open = true;
		ChatLift.openLink(url);
	}
}
