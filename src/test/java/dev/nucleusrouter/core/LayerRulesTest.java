package dev.nucleusrouter.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the layering rule from CLAUDE.md: {@code core} and {@code game} must not
 * import Minecraft, Mojang or Fabric classes. Only {@code platform} may.
 */
class LayerRulesTest {
	private static final List<String> FORBIDDEN = List.of("net.minecraft.", "com.mojang.", "net.fabricmc.", "com.blaze3d.");

	@Test
	void coreAndGameHaveNoMinecraftImports() throws IOException {
		List<String> violations = new ArrayList<>();
		for (String pkg : List.of("core", "game")) {
			Path dir = Path.of("src/main/java/dev/nucleusrouter", pkg);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
					List<String> lines = Files.readAllLines(f);
					for (int i = 0; i < lines.size(); i++) {
						String line = lines.get(i).trim();
						if (!line.startsWith("import ")) continue;
						for (String bad : FORBIDDEN) {
							if (line.contains(bad)) violations.add(f + ":" + (i + 1) + " -> " + line);
						}
					}
				}
			}
		}
		assertTrue(violations.isEmpty(), "core/game must stay Minecraft-free:\n" + String.join("\n", violations));
	}
}
