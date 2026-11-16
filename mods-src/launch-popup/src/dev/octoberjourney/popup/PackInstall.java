package dev.octoberjourney.popup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Sets up a server's pack as a Prism Launcher instance, from the launcher's own menu (Install, beside a server its
 *  Gatekeeper lists that isn't on this device yet). It does what setup does for the instance, and no more:
 *    - reads the server's profile (the address its Gatekeeper gives: a config.json, docs/profiles.md)
 *    - makes instances/&lt;instance id&gt; with instance.cfg (the name, the update before each launch, the launch wrapper)
 *      and mmc-pack.json (its Minecraft and loader versions)
 *    - puts the update script, the launch wrapper, the launcher's own script and packwiz-installer-bootstrap.jar
 *      (checked against the profile's SHA-256) in its game folder, from the profile's repository
 *    - writes pack-sources.txt (where updates come from)
 *  The mods themselves arrive on the first launch, as with setup. It adds no Steam shortcut and doesn't touch Prism
 *  Launcher's own settings. Prism Launcher has to be installed already. */
final class PackInstall {
	private PackInstall() {}

	private static String str(Object o) { return o == null ? "" : o.toString(); }

	/** Sets up a connection to a plain (unmodded) server its Gatekeeper lists: a Prism Launcher instance of plain
	 *  Minecraft in the server's version, with the server in its server list, and enough beside it for the launcher's
	 *  menu to list it with Play (which starts it and joins). No mods, no update step, nothing downloaded here: Prism
	 *  fetches the game itself the first time it starts. Returns the instance id. */
	static String vanilla(Path instances, String serverId, String name, String address, String version, ServerControl keeper) throws IOException {
		if (!version.matches("[\\w.\\-]{1,32}")) throw new IOException("the Gatekeeper doesn't know this server's Minecraft version yet (start the server once, then try again)");
		String id = "gatehouse-" + serverId.replaceAll("[^A-Za-z0-9._-]", "-");
		Path inst = instances.resolve(id), mc = inst.resolve("minecraft");
		if (Files.isRegularFile(inst.resolve("instance.cfg"))) throw new IOException("that server is already set up here (" + id + ")");
		Files.createDirectories(mc.resolve("extras"));
		Files.writeString(inst.resolve("mmc-pack.json"), "{\n    \"components\": [\n        { \"uid\": \"net.minecraft\", \"version\": \"" + version + "\", \"important\": true }\n    ],\n    \"formatVersion\": 1\n}\n", StandardCharsets.UTF_8);
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		String nl = win ? "\r\n" : "\n";
		// Its launcher window before the game (the same one a pack has, with nothing to update): the launcher, a small
		// script Prism runs before the game, and the launch wrapper, all borrowed from a pack installed here.
		boolean window = equip(instances, mc);
		List<String> lines = new ArrayList<>(List.of("[General]", "name=" + name + " (Minecraft " + version + ")"));
		if (window) {
			lines.add("OverrideCommands=true");
			lines.add(win ? "PreLaunchCommand=\"powershell -NoProfile -ExecutionPolicy Bypass -File \\\"$INST_MC_DIR\\\\plain-launch.ps1\\\"\"" : "PreLaunchCommand=bash $INST_MC_DIR/plain-launch.sh");
			if (Files.isRegularFile(mc.resolve(win ? "launch-wrapper.exe" : "launch-wrapper.sh")))
				lines.add(win ? "WrapperCommand=\"\\\"" + mc.toAbsolutePath().toString().replace('\\', '/') + "/launch-wrapper.exe\\\"\"" : "WrapperCommand=bash $INST_MC_DIR/launch-wrapper.sh");
		}
		lines.addAll(List.of("ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default"));
		Files.writeString(inst.resolve("instance.cfg"), String.join(nl, lines) + nl, StandardCharsets.UTF_8);
		// The server list (servers.dat: uncompressed NBT, one entry).
		java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream o = new java.io.DataOutputStream(b);
		o.writeByte(10); o.writeUTF("");
		o.writeByte(9); o.writeUTF("servers"); o.writeByte(10); o.writeInt(1);
		o.writeByte(8); o.writeUTF("name"); o.writeUTF(name);
		o.writeByte(8); o.writeUTF("ip"); o.writeUTF(address);
		o.writeByte(0);
		o.writeByte(0);
		Files.write(mc.resolve("servers.dat"), b.toByteArray());
		// What the launcher's menu reads: its name, the address to join, and which Gatekeeper looks after it.
		Files.writeString(mc.resolve("extras/profile.json"), "{\"name\": \"" + ServerControl.jsonEsc(name) + "\", \"instance_id\": \"" + ServerControl.jsonEsc(id) + "\", \"plain\": true}\n", StandardCharsets.UTF_8);
		Files.writeString(mc.resolve("extras/servers.json"), "[\n {\"name\": \"" + ServerControl.jsonEsc(name) + "\", \"address\": \"" + ServerControl.jsonEsc(address) + "\"}\n]\n", StandardCharsets.UTF_8);
		if (keeper != null) {
			Files.writeString(mc.resolve("extras/server-control.json"), "{\"helper_url\": \"" + ServerControl.jsonEsc(keeper.helperUrl) + "\", \"minecraft\": \"" + ServerControl.jsonEsc(address) + "\", \"server\": \"" + ServerControl.jsonEsc(serverId) + "\"}\n", StandardCharsets.UTF_8);
			if (keeper.deviceId != null && !keeper.deviceId.isEmpty() && keeper.deviceToken != null && keeper.deviceToken.length() >= 32)
				Files.writeString(mc.resolve("server-access.json"), "{\"device_id\": \"" + ServerControl.jsonEsc(keeper.deviceId) + "\", \"device_token\": \"" + ServerControl.jsonEsc(keeper.deviceToken)
						+ "\", \"identities\": {\"" + ServerControl.jsonEsc(keeper.helperUrl) + "\": {\"id\": \"" + ServerControl.jsonEsc(keeper.deviceId) + "\", \"token\": \"" + ServerControl.jsonEsc(keeper.deviceToken) + "\"}}}\n", StandardCharsets.UTF_8);
		}
		return id;
	}

	/** Gives a plain server's game folder what its launcher window needs, copied from a pack installed on this device
	 *  (the pack's update keeps those current): the launcher itself, the script Prism runs before the game, the launch
	 *  wrapper, the script the Steam shortcuts run, and Gatehouse's art and the controller layout for a Steam shortcut.
	 *  Only what differs is copied. False when no installed pack has them yet (the server then starts with no window). */
	static boolean equip(Path instances, Path mc) {
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		String ext = win ? "ps1" : "sh";
		Path from = null;
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Path m = d.resolve("minecraft");
				if (!m.toAbsolutePath().equals(mc.toAbsolutePath()) && Files.isRegularFile(m.resolve("extras/plain-launch." + ext)) && Files.isRegularFile(m.resolve("extras/launch-popup.jar")) && Files.isRegularFile(m.resolve("packwiz-installer-bootstrap.jar"))) { from = m; break; }
			}
		} catch (IOException e) { return false; }
		if (from == null) return Files.isRegularFile(mc.resolve("plain-launch." + ext)) && Files.isRegularFile(mc.resolve("extras/launch-popup.jar"));
		String[][] files = {{"extras/launch-popup.jar", "extras/launch-popup.jar"}, {"extras/plain-launch." + ext, "plain-launch." + ext}, {"extras/gatehouse-launcher." + ext, "extras/gatehouse-launcher." + ext},
				{win ? "launch-wrapper.exe" : "launch-wrapper.sh", win ? "launch-wrapper.exe" : "launch-wrapper.sh"}, {"steam/controller_neptune.vdf", "steam/controller_neptune.vdf"},
				{"steam/art-launcher/cover.png", "steam/art/cover.png"}, {"steam/art-launcher/hero.png", "steam/art/hero.png"}, {"steam/art-launcher/logo.png", "steam/art/logo.png"},
				{"steam/art-launcher/icon.png", "steam/art/icon.png"}, {"steam/art-launcher/wide.png", "steam/art/wide.png"}};
		for (String[] f : files) try {
			Path src = from.resolve(f[0]), dst = mc.resolve(f[1]);
			if (!Files.isRegularFile(src)) continue;
			if (Files.isRegularFile(dst) && Files.size(dst) == Files.size(src) && java.util.Arrays.equals(Files.readAllBytes(src), Files.readAllBytes(dst))) continue;
			Files.createDirectories(dst.getParent());
			Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			if (!win && f[1].endsWith(".sh")) dst.toFile().setExecutable(true, false);
		} catch (IOException ignored) {}   // (a file in use: next time)
		return Files.isRegularFile(mc.resolve("plain-launch." + ext)) && Files.isRegularFile(mc.resolve("extras/launch-popup.jar"));
	}

	/** Keeps every plain server's borrowed files current (the launcher's menu calls this each time it opens). */
	static void refreshPlain(Path instances) {
		try (java.util.stream.Stream<Path> s = Files.list(instances)) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
				Path mc = d.resolve("minecraft");
				if ("true".equals(str(ServerControl.asMap(LaunchPopup.parseJson(LaunchPopup.readFile(mc.resolve("extras/profile.json")))).get("plain")))) equip(instances, mc);
			}
		} catch (IOException | RuntimeException ignored) {}
	}

	/** What a profile would install, for the question before it: {instance id, pack name, repository address}. */
	static String[] look(String profileUrl) throws IOException {
		Map<String, Object> cfg = ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(profileUrl), StandardCharsets.UTF_8)));
		String id = str(cfg.get("instance_id")), repo = str(cfg.get("repo_raw"));
		if (!id.matches("[A-Za-z0-9._-]{1,64}") || !repo.matches("(?i)https?://.+")) throw new IOException("that isn't a pack's profile (no instance or repository in it)");
		return new String[] {id, str(cfg.get("pack_name")).isEmpty() ? id : str(cfg.get("pack_name")), repo};
	}

	/** Installs it. keeper: this device's line to the Gatekeeper that listed the server (its id and secret are given to
	 *  the pack, so the Gatekeeper sees one launcher), or null. Returns the instance id. */
	static String install(Path instances, String profileUrl, ServerControl keeper, Consumer<String> say) throws IOException {
		boolean win = System.getProperty("os.name", "").startsWith("Windows");
		say.accept("Reading the pack's profile…");
		Map<String, Object> cfg = ServerControl.asMap(LaunchPopup.parseJson(new String(LaunchPopup.fetchBytes(profileUrl), StandardCharsets.UTF_8)));
		String id = str(cfg.get("instance_id")), repo = str(cfg.get("repo_raw")).replaceAll("/+$", ""), name = str(cfg.get("instance_name"));
		if (!id.matches("[A-Za-z0-9._-]{1,64}") || !repo.matches("(?i)https?://.+")) throw new IOException("that isn't a pack's profile (no instance or repository in it)");
		Path inst = instances.resolve(id), mc = inst.resolve("minecraft");
		if (Files.isRegularFile(inst.resolve("instance.cfg"))) throw new IOException("that pack is already installed here (" + id + ")");
		// Everything is fetched first, so a failed download leaves nothing half made.
		say.accept("Downloading the pack's setup files…");
		byte[] mmc = LaunchPopup.fetchBytes(repo + "/" + (str(cfg.get("mmc_pack")).isEmpty() ? "clients/mmc-pack.json" : str(cfg.get("mmc_pack"))));
		byte[] sync = LaunchPopup.fetchBytes(repo + "/clients/" + (win ? "pack-sync.ps1" : "pack-sync.sh"));
		byte[] wrapper = LaunchPopup.fetchBytes(repo + "/clients/" + (win ? "launch-wrapper.exe" : "launch-wrapper.sh"));
		byte[] menu = LaunchPopup.fetchBytes(repo + "/clients/" + (win ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh"));
		byte[] boot = LaunchPopup.fetchBytes(repo + "/clients/packwiz-installer-bootstrap.jar");
		String want = str(cfg.get("bootstrap_sha256"));
		try { if (want.isEmpty() || !LaunchPopup.sha256(boot).equalsIgnoreCase(want)) throw new IOException("packwiz-installer-bootstrap.jar does not match the profile's checksum: nothing was installed"); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IOException("couldn't check the download"); }
		say.accept("Making the instance…");
		Files.createDirectories(mc.resolve("extras"));
		Files.write(inst.resolve("mmc-pack.json"), mmc);
		List<String> lines = new ArrayList<>(List.of("[General]", "name=" + (name.isEmpty() ? id : name), "OverrideCommands=true"));
		if (win) {
			lines.add("PreLaunchCommand=\"powershell -NoProfile -ExecutionPolicy Bypass -File \\\"$INST_MC_DIR\\\\pack-sync.ps1\\\"\"");
			// (The wrapper's full path with forward slashes: Prism drops a backslash here, and the placeholder isn't relied on.)
			lines.add("WrapperCommand=\"\\\"" + mc.toAbsolutePath().toString().replace('\\', '/') + "/launch-wrapper.exe\\\"\"");
		} else {
			lines.add("PreLaunchCommand=bash $INST_MC_DIR/pack-sync.sh");
			lines.add("WrapperCommand=bash $INST_MC_DIR/launch-wrapper.sh");
		}
		lines.addAll(List.of("ConfigVersion=1.3", "InstanceType=OneSix", "iconKey=default"));
		Files.writeString(inst.resolve("instance.cfg"), String.join(win ? "\r\n" : "\n", lines) + (win ? "\r\n" : "\n"), StandardCharsets.UTF_8);
		Path s = mc.resolve(win ? "pack-sync.ps1" : "pack-sync.sh"), w = mc.resolve(win ? "launch-wrapper.exe" : "launch-wrapper.sh"), m = mc.resolve("extras").resolve(win ? "gatehouse-launcher.ps1" : "gatehouse-launcher.sh");
		Files.write(s, sync); Files.write(w, wrapper); Files.write(m, menu);
		if (!win) for (Path x : new Path[] {s, w, m}) x.toFile().setExecutable(true, false);
		Files.write(mc.resolve("packwiz-installer-bootstrap.jar"), boot);
		StringBuilder sources = new StringBuilder("# Tried in order; the first that answers is used.\n");
		if (cfg.get("pack_sources") instanceof List<?> l) for (Object o : l) sources.append(str(o)).append('\n');
		Files.writeString(mc.resolve("pack-sources.txt"), sources.toString(), StandardCharsets.UTF_8);
		// This device's id and secret for the Gatekeeper, the same as the menu uses: one launcher there, not two.
		if (keeper != null && keeper.deviceId != null && !keeper.deviceId.isEmpty() && keeper.deviceToken != null && keeper.deviceToken.length() >= 32)
			Files.writeString(mc.resolve("server-access.json"), "{\"device_id\": \"" + ServerControl.jsonEsc(keeper.deviceId) + "\", \"device_token\": \"" + ServerControl.jsonEsc(keeper.deviceToken)
					+ "\", \"identities\": {\"" + ServerControl.jsonEsc(keeper.helperUrl) + "\": {\"id\": \"" + ServerControl.jsonEsc(keeper.deviceId) + "\", \"token\": \"" + ServerControl.jsonEsc(keeper.deviceToken) + "\"}}}\n", StandardCharsets.UTF_8);
		// So the menu lists it straight away (the pack's first update brings the real ones).
		Files.writeString(mc.resolve("extras/profile.json"), "{\"name\": \"" + ServerControl.jsonEsc(str(cfg.get("pack_name")).isEmpty() ? id : str(cfg.get("pack_name"))) + "\", \"instance_id\": \"" + ServerControl.jsonEsc(id) + "\"}\n", StandardCharsets.UTF_8);
		if (keeper != null) Files.writeString(mc.resolve("extras/server-control.json"), "{\"helper_url\": \"" + ServerControl.jsonEsc(keeper.helperUrl) + "\"}\n", StandardCharsets.UTF_8);
		return id;
	}
}
