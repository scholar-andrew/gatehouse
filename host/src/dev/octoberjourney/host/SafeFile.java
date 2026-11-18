package dev.octoberjourney.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * R29 (5): Gatekeeper's settings are never left half-written. A file is written whole beside the old one and then swapped
 * in, so a crash or a full disk mid-write leaves the old one as it was. For the files it would hurt to lose (its settings,
 * who may come in, players, web panel accounts) the copy that is being replaced is kept first, in "backups" beside them:
 * one an hour at most, the newest ten of each.
 */
final class SafeFile {
	private SafeFile() {}

	/** The files whose earlier copies are kept. */
	private static final List<String> KEPT = List.of("host.json", "devices.json", "players.json", "users.json");
	private static final long EVERY = 3_600_000L;
	private static final int KEEP = 10;

	static void write(Path file, String text) throws IOException {
		Path parent = file.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		String name = file.getFileName().toString();
		if (KEPT.contains(name) && Files.isRegularFile(file)) keep(file, parent.resolve("backups"), name);
		Path tmp = parent.resolve(name + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
		catch (IOException notAtomicHere) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
	}

	/** The copy about to be replaced, kept if the last one kept is an hour old (and it isn't the same). */
	private static void keep(Path file, Path backups, String name) {
		try {
			Files.createDirectories(backups);
			String base = name.replaceAll("\\.json$", "");
			List<Path> had = new ArrayList<>();
			try (java.util.stream.Stream<Path> s = Files.list(backups)) { s.filter(p -> p.getFileName().toString().startsWith(base + "-")
					&& p.getFileName().toString().endsWith(".json")).sorted().forEach(had::add); }
			Path last = had.isEmpty() ? null : had.get(had.size() - 1);
			if (last != null && System.currentTimeMillis() - Files.getLastModifiedTime(last).toMillis() < EVERY) return;
			if (last != null && java.util.Arrays.equals(Files.readAllBytes(last), Files.readAllBytes(file))) return;
			String when = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Files.copy(file, backups.resolve(base + "-" + when + ".json"), StandardCopyOption.REPLACE_EXISTING);
			had.add(backups.resolve(base + "-" + when + ".json"));
			while (had.size() > KEEP) Files.deleteIfExists(had.remove(0));
		} catch (IOException | RuntimeException ignored) { /* a backup that can't be made doesn't stop the save */ }
	}
}
