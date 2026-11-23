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
		assertEquals(1.54, c.etherwarp.eyeHeight);
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
}
