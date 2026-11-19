package dev.octoberjourney.host;

import dev.gatehouse.Tests;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What Gatekeeper reads from a server's folder (R91): a Fabric server Gatekeeper has just made and nobody has started
 * yet has only Fabric's launcher (named for its Minecraft and loader versions), no libraries and no log, and must still
 * count as a Fabric server of that version, so packs and the mod browser work with it straight away.
 */
public final class TestServers {
	public static void main(String[] a) throws Exception {
		Tests t = new Tests("server folders");
		Path root = Files.createTempDirectory("gatehouse-servers-");
		try {
			// a new Fabric server, never started (as New server leaves it)
			Path fresh = Files.createDirectories(root.resolve("fresh fabric"));
			Files.createDirectories(fresh.resolve("mods"));
			Files.writeString(fresh.resolve("eula.txt"), "eula=true\n");
			Files.writeString(fresh.resolve("server.properties"), "server-port=25566\n");
			Files.write(fresh.resolve("fabric-server-mc.26.3-loader.0.20.1-launcher.1.1.2.jar"), new byte[] {0x50, 0x4b});
			t.check("a never-started Fabric server is a Fabric server", "0.20.1".equals(Packs.fabricLoader(fresh)));
			String[] f = new Minecraft().facts(fresh);
			t.check("its Minecraft version comes from the launcher's name", "26.3".equals(f[1]));
			t.check("it counts as modded", "modded".equals(f[0]));

			// once it has started: the loader it downloaded wins
			Path ran = Files.createDirectories(root.resolve("started fabric"));
			Files.writeString(ran.resolve("server.properties"), "server-port=25567\n");
			Files.write(ran.resolve("fabric-server-mc.1.21.1-loader.0.16.5-launcher.1.0.1.jar"), new byte[] {0x50, 0x4b});
			Files.createDirectories(ran.resolve("libraries/net/fabricmc/fabric-loader/0.16.9"));
			Files.createDirectories(ran.resolve("logs"));
			Files.writeString(ran.resolve("logs/latest.log"), "[12:00:00] [Server thread/INFO]: Starting minecraft server version 1.21.1\n");
			t.check("a started Fabric server keeps the loader it runs", "0.16.9".equals(Packs.fabricLoader(ran)));
			t.check("and the version it said it started as", "1.21.1".equals(new Minecraft().facts(ran)[1]));

			// a plain server is not Fabric
			Path plain = Files.createDirectories(root.resolve("plain"));
			Files.writeString(plain.resolve("server.properties"), "server-port=25568\n");
			Files.write(plain.resolve("server.jar"), new byte[] {0x50, 0x4b});
			t.check("a plain server isn't a Fabric server", Packs.fabricLoader(plain).isEmpty());
			t.check("and isn't modded", "vanilla".equals(new Minecraft().facts(plain)[0]));
		} finally {
			try (var s = Files.walk(root)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); }
		}
		t.done();
	}
}
