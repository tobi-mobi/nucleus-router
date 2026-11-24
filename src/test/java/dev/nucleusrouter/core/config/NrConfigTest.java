package dev.nucleusrouter.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NrConfigTest {
	@Test
	void defaultsMatchSpec() {
		NrConfig c = new NrConfig();
		assertTrue(c.validate().isEmpty(), "defaults must already be valid");
		assertEquals(61, c.etherwarp.range);
		assertEquals(1.27, c.etherwarp.eyeHeight, "measured in-game 2026-11-24");
		assertTrue(c.etherwarp.rayPassesThrough.containsAll(List.of("minecraft:water", "minecraft:lava", "minecraft:torch", "minecraft:wall_torch")));
		assertEquals(45.0, c.bombs.throwRange);
		assertEquals(8.0, c.bombs.blastRadius);
		assertEquals(List.of("minecraft:stone"), c.bombs.hardStoneBlocks);
		assertEquals(2.5, c.hollows.warpNucleusSeconds);
		assertFalse(c.stops.warpNucleusCountsAsLeavingDen);
		assertEquals(0.20, c.cost.baseWarpSeconds);
		assertEquals(1.5, c.cost.missRecoverySeconds);
		assertEquals(4.5, c.placing.reach);
	}

	@Test
	void badValuesAreClampedWithReadableMessages() {
		NrConfig c = new NrConfig();
		c.etherwarp.range = 80;
		c.hollows.minX = 900;
		c.bombs = null;
		List<String> fixes = c.validate();
		assertEquals(61, c.etherwarp.range);
		assertTrue(c.hollows.minX < c.hollows.maxX);
		assertNotNull(c.bombs);
		assertEquals(3, fixes.size(), fixes.toString());
	}

	@Test
	void oldConfigsGetTheMeasuredEyeHeightAndTorches() {
		NrConfig old = new NrConfig();
		old.configVersion = 1;
		old.etherwarp.eyeHeight = 1.54;
		old.etherwarp.rayPassesThrough = new java.util.ArrayList<>(List.of("minecraft:water", "minecraft:lava"));
		List<String> notes = old.migrate();
		assertEquals(1.27, old.etherwarp.eyeHeight);
		assertTrue(old.etherwarp.rayPassesThrough.contains("minecraft:torch"));
		assertEquals(NrConfig.CURRENT_VERSION, old.configVersion);
		assertEquals(3, notes.size(), notes.toString());

		// A value the user chose themselves is kept
		NrConfig custom = new NrConfig();
		custom.configVersion = 1;
		custom.etherwarp.eyeHeight = 1.30;
		custom.migrate();
		assertEquals(1.30, custom.etherwarp.eyeHeight);
		assertTrue(custom.migrate().isEmpty(), "migrating twice changes nothing");
	}
}
