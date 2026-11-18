package dev.deckmods.chatlift;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chat Lift. On a Steam Deck: when chat opens, asks Steam to show its on-screen keyboard and raises the chat so the
 * keyboard doesn't cover it; when chat closes, hides the keyboard and the chat drops back down. Elsewhere under Steam,
 * with a Steam controller in use, chat is raised the same way for the keyboard the player opens with Steam + X.
 */
public final class ChatLift {
	static final Logger LOG = LoggerFactory.getLogger("chatlift");
	private static final String FILE = "chatlift.properties";

	/** auto = under Steam, open Steam's keyboard and lift; always = lift only; never = off. */
	private static String mode = "auto";
	private static float fraction = 0.43f;
	/** Off the Deck the Steam + X keyboard is a different size: Big Picture's (launched from Big Picture) or the desktop one. */
	private static float fractionBigPicture = 0.38f;
	private static float fractionDesktop = 0.22f;
	private static int extraPixels = 4;
	private static String openUrl = "steam://open/keyboard";
	private static String closeUrl = "steam://close/keyboard";
	private static boolean loaded;
	private static boolean lifted;

	private static final boolean DECK = isSteamDeck();
	private static final boolean BIG_PICTURE = "1".equals(System.getenv("SteamTenfoot"));

	private ChatLift() {}

	private static void load() {
		loaded = true;
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
		Properties p = new Properties();
		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path)) {
				p.load(r);
			} catch (IOException ignored) {
			}
		}
		boolean enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
		mode = p.getProperty("mode", enabled ? "auto" : "never").trim().toLowerCase(Locale.ROOT);
		try {
			fraction = Math.clamp(Float.parseFloat(p.getProperty("lift_fraction", "0.43")), 0f, 0.9f);
		} catch (NumberFormatException ignored) {
		}
		try {
			fractionBigPicture = Math.clamp(Float.parseFloat(p.getProperty("lift_fraction_big_picture", "0.38")), 0f, 0.9f);
		} catch (NumberFormatException ignored) {
		}
		try {
			fractionDesktop = Math.clamp(Float.parseFloat(p.getProperty("lift_fraction_desktop", "0.22")), 0f, 0.9f);
		} catch (NumberFormatException ignored) {
		}
		try {
			extraPixels = Integer.parseInt(p.getProperty("extra_pixels", "4"));
		} catch (NumberFormatException ignored) {
		}
		openUrl = p.getProperty("open_keyboard_url", openUrl).trim();
		closeUrl = p.getProperty("close_keyboard_url", closeUrl).trim();
		try (Writer w = Files.newBufferedWriter(path)) {
			w.write("# Chat Lift: raises chat above Steam's on-screen keyboard while you type (Steam Deck).\n");
			w.write("# Elsewhere, with a Steam controller, chat is raised for the keyboard you open with Steam + X.\n");
			w.write("# mode: auto   = under Steam, open Steam's keyboard with chat and lift only then\n");
			w.write("#       always = always lift chat, never open the keyboard\n");
			w.write("#       never  = off\n");
			w.write("mode=" + mode + "\n");
			w.write("# lift_fraction: share of the screen height to raise by on a Steam Deck\n");
			w.write("lift_fraction=" + fraction + "\n");
			w.write("# elsewhere: when the game was started from Steam's Big Picture mode, and from the normal Steam window\n");
			w.write("lift_fraction_big_picture=" + fractionBigPicture + "\n");
			w.write("lift_fraction_desktop=" + fractionDesktop + "\n");
			w.write("# extra_pixels: small extra gap above the keyboard, in GUI pixels\n");
			w.write("extra_pixels=" + extraPixels + "\n");
			w.write("# Steam links used to show and hide the keyboard (leave blank to skip)\n");
			w.write("open_keyboard_url=" + openUrl + "\n");
			w.write("close_keyboard_url=" + closeUrl + "\n");
		} catch (IOException ignored) {
		}
		LOG.info("[chatlift] mode={}, lift_fraction={}, under Steam={}, Steam Deck={}, Big Picture={}", mode, fraction, underSteam(),
				isSteamDeck(), "1".equals(System.getenv("SteamTenfoot")));
	}

	static String mode() {
		if (!loaded) load();
		return mode;
	}

	static String closeUrl() {
		if (!loaded) load();
		return closeUrl;
	}

	static String openUrl() {
		if (!loaded) load();
		return openUrl;
	}

	static boolean underSteam() {
		String a = System.getenv("SteamAppId");
		String g = System.getenv("SteamGameId");
		return (a != null && !a.isBlank()) || (g != null && !g.isBlank());
	}

	/** Steam sets SteamDeck=1 for games on a Deck; SDL itself then shows Steam's keyboard for every text box. */
	static boolean isSteamDeck() {
		if ("1".equals(System.getenv("SteamDeck"))) return true;
		try {
			String product = Files.readString(Path.of("/sys/devices/virtual/dmi/id/product_name")).trim();
			return product.equals("Jupiter") || product.equals("Galileo");
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Called once when a chat screen opens. */
	public static void onChatOpened() {
		if (!loaded) load();
		switch (mode) {
			case "always" -> lifted = true;
			case "auto" -> {
				// Deck: open Steam's keyboard and lift. Elsewhere, with a Steam controller: lift only, for the keyboard the
				// player opens with Steam + X (the one Steam opens on request can't be used with the trackpads).
				boolean deck = isSteamDeck();
				lifted = underSteam() && (deck || SteamKeyboard.steamControllerInUse());
				if (lifted && deck) openLink(openUrl);
			}
			default -> lifted = false;
		}
	}

	/** Called when the chat screen goes away. */
	public static void onChatClosed() {
		if (!loaded) load();
		if (lifted && "auto".equals(mode)) openLink(closeUrl);
		lifted = false;
	}

	/** How many GUI pixels to raise the chat by right now. */
	public static float lift(int guiHeight) {
		if (!loaded) load();
		if (!lifted) return 0f;
		float f = DECK ? fraction : BIG_PICTURE ? fractionBigPicture : fractionDesktop;
		return Math.round(guiHeight * f) + extraPixels;
	}

	/** Opens a steam:// link with the system's handler, off the game thread. */
	static void openLink(String url) {
		if (url == null || url.isEmpty()) return;
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
		Thread t = new Thread(() -> {
			try {
				ProcessBuilder pb = windows
						? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
						: new ProcessBuilder("xdg-open", url);
				Process p = pb.redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes()).trim();
				int rc = p.waitFor();
				LOG.info("[chatlift] open {} -> exit {}{}", url, rc, out.isEmpty() ? "" : " (" + out + ")");
			} catch (IOException | InterruptedException e) {
				LOG.warn("[chatlift] could not open {}: {}", url, e.toString());
			}
		}, "chatlift-steam-keyboard");
		t.setDaemon(true);
		t.start();
	}
}
