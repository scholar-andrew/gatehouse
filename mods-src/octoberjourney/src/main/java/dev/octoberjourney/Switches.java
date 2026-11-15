package dev.octoberjourney;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The mods a player can switch on and off. The list, the pack's default for each and which mods need which come from
 * extras/mod-toggles.json (part of the pack); the player's choices are kept under "mods" in pack-options.json, beside
 * the other choices made at setup. Both files are in the game folder. The pack sync, which runs before each launch,
 * disables the mods that are off (and any mod whose required mod is off), so a change takes effect on the next start.
 */
final class Switches {
	record Toggle(String key, String modId, String name, String description, boolean pack, Boolean choice, List<String> needs) {
		boolean wanted() { return choice != null ? choice : pack; }
		boolean loaded() { return FabricLoader.getInstance().isModLoaded(modId); }
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Switches() {}

	private static Path dir() { return FabricLoader.getInstance().getGameDir(); }

	static List<Toggle> load() {
		List<Toggle> out = new ArrayList<>();
		JsonObject choices = options().has("mods") && options().get("mods").isJsonObject() ? options().getAsJsonObject("mods") : new JsonObject();
		try (Reader r = Files.newBufferedReader(dir().resolve("extras/mod-toggles.json"), StandardCharsets.UTF_8)) {
			JsonArray list = JsonParser.parseReader(r).getAsJsonArray();
			for (JsonElement e : list) {
				JsonObject o = e.getAsJsonObject();
				String key = o.has("key") ? o.get("key").getAsString() : o.get("slug").getAsString();
				Boolean choice = choices.has(key) ? choices.get(key).getAsBoolean() : null;
				List<String> needs = new ArrayList<>();
				if (o.has("needs")) for (JsonElement n : o.getAsJsonArray("needs")) needs.add(n.getAsString());
				out.add(new Toggle(key, o.get("modid").getAsString(), o.get("name").getAsString(),
						o.get("description").getAsString(), o.get("default").getAsBoolean(), choice, needs));
			}
		} catch (IOException | RuntimeException e) {
			// No list yet (the pack hasn't synced): nothing to show.
		}
		return out;
	}

	/** Whether each mod will be on at the next start: its own switch, and every mod it needs on too. */
	static Map<String, Boolean> effective(List<Toggle> toggles) {
		Map<String, Boolean> on = new HashMap<>();
		for (Toggle t : toggles) on.put(t.key(), t.wanted());
		for (int pass = 0; pass < toggles.size(); pass++)
			for (Toggle t : toggles)
				for (String n : t.needs()) if (Boolean.FALSE.equals(on.get(n))) on.put(t.key(), false);
		return on;
	}

	static String nameOf(List<Toggle> toggles, String key) {
		for (Toggle t : toggles) if (t.key().equals(key)) return t.name();
		return key;
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
	static void save(String key, boolean on) {
		JsonObject opts = options();
		JsonObject mods = opts.has("mods") && opts.get("mods").isJsonObject() ? opts.getAsJsonObject("mods") : new JsonObject();
		mods.addProperty(key, on);
		opts.add("mods", mods);
		try (Writer w = Files.newBufferedWriter(dir().resolve("pack-options.json"), StandardCharsets.UTF_8)) {
			GSON.toJson(opts, w);
		} catch (IOException e) {
			throw new RuntimeException("couldn't save pack-options.json: " + e.getMessage(), e);
		}
	}
}
