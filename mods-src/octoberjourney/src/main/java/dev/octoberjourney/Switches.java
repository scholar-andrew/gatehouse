package dev.octoberjourney;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The mods a player can switch on and off. The list and the pack's default for each come from extras/mod-toggles.json
 * (part of the pack); the player's choices are kept under "mods" in pack-options.json, beside the other choices made at
 * setup. Both files are in the game folder. The pack sync, which runs before each launch, disables the mods that are
 * off, so a change takes effect the next time the game starts.
 */
final class Switches {
	record Toggle(String slug, String modId, String name, String description, boolean pack, Boolean choice) {
		boolean wanted() { return choice != null ? choice : pack; }
		boolean loaded() { return FabricLoader.getInstance().isModLoaded(modId); }
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Switches() {}

	private static Path dir() { return FabricLoader.getInstance().getGameDir(); }

	static List<Toggle> load() {
		List<Toggle> out = new ArrayList<>();
		JsonObject choices = options().getAsJsonObject("mods");
		try (Reader r = Files.newBufferedReader(dir().resolve("extras/mod-toggles.json"), StandardCharsets.UTF_8)) {
			JsonArray list = JsonParser.parseReader(r).getAsJsonArray();
			for (JsonElement e : list) {
				JsonObject o = e.getAsJsonObject();
				String slug = o.get("slug").getAsString();
				Boolean choice = choices != null && choices.has(slug) ? choices.get(slug).getAsBoolean() : null;
				out.add(new Toggle(slug, o.get("modid").getAsString(), o.get("name").getAsString(),
						o.get("description").getAsString(), o.get("default").getAsBoolean(), choice));
			}
		} catch (IOException | RuntimeException e) {
			// No list yet (the pack hasn't synced): nothing to show.
		}
		return out;
	}

	private static JsonObject options() {
		try (Reader r = Files.newBufferedReader(dir().resolve("pack-options.json"), StandardCharsets.UTF_8)) {
			JsonElement e = JsonParser.parseReader(r);
			if (e.isJsonObject()) return e.getAsJsonObject();
		} catch (IOException | RuntimeException ignored) {
		}
		return new JsonObject();
	}

	/** Saves the player's choice for one mod, keeping everything else in pack-options.json. */
	static void save(String slug, boolean on) {
		JsonObject opts = options();
		JsonObject mods = opts.has("mods") && opts.get("mods").isJsonObject() ? opts.getAsJsonObject("mods") : new JsonObject();
		mods.addProperty(slug, on);
		opts.add("mods", mods);
		try (Writer w = Files.newBufferedWriter(dir().resolve("pack-options.json"), StandardCharsets.UTF_8)) {
			GSON.toJson(opts, w);
		} catch (IOException e) {
			throw new RuntimeException("couldn't save pack-options.json: " + e.getMessage(), e);
		}
	}
}
