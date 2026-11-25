package dev.nucleusrouter.core.cost;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.game.StopCosts;
import dev.nucleusrouter.game.Structure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostModelTest {
	private final NrConfig cfg = new NrConfig();

	@Test
	void baseWarpAddsHalfThePing() {
		CostModel m = new CostModel(cfg, 160);
		assertEquals(0.20 + 0.08, m.baseSeconds(), 1e-9);
		cfg.cost.useMeasuredPing = false;
		assertEquals(0.20 + 0.05, new CostModel(cfg, 160).baseSeconds(), 1e-9, "fallback ping 100 when measuring is off");
		cfg.cost.useMeasuredPing = true;
		assertEquals(0.20 + 0.05, new CostModel(cfg, 0).baseSeconds(), 1e-9, "fallback when nothing was measured");
	}

	@Test
	void turnTimeMatchesTheSpecDefaults() {
		CostModel m = new CostModel(cfg, 100);
		assertEquals(0, m.turnSeconds(0), 1e-12);
		assertEquals(0.40, m.turnSeconds(90), 1e-9, "0.40 s extra at 90 degrees");
		assertEquals(0.40 * Math.pow(0.5, 1.5), m.turnSeconds(-45), 1e-9, "direction doesn't matter");
		assertTrue(m.turnSeconds(10) < m.turnSeconds(20) && m.turnSeconds(20) < m.turnSeconds(150), "always increasing");
	}

	@Test
	void missChanceGrowsWithTurnAndShrinksWithTargetSize() {
		CostModel m = new CostModel(cfg, 100);
		double straightSmall = m.missProbability(0, 1.0);
		double turnedSmall = m.missProbability(90, 1.0);
		double turnedBig = m.missProbability(90, 6.0);
		assertTrue(straightSmall < 0.01, "a straight chain onto a 1 degree target almost never misses: " + straightSmall);
		assertTrue(turnedSmall > 0.8, "a 90 degree flick onto a 1 degree target usually misses: " + turnedSmall);
		assertTrue(turnedBig < 0.05, "a big target is safe even after a flick: " + turnedBig);
		assertEquals(1.0, m.missProbability(0, 0), "no valid aim = always misses");
		for (double a = 0; a < 180; a += 15) {
			assertTrue(m.missProbability(a + 15, 2) >= m.missProbability(a, 2));
			assertTrue(m.missProbability(a, 3) <= m.missProbability(a, 2));
		}
	}

	@Test
	void warpCostAddsItAllUp() {
		CostModel m = new CostModel(cfg, 100);
		double expected = 0.25 + 0.40 + m.missProbability(90, 2) * 1.5;
		assertEquals(expected, m.warpSeconds(90, 2), 1e-9);
		assertTrue(m.warpSeconds(0, 3) < m.warpSeconds(30, 3), "straight lines are king");
		assertEquals(1.0, m.warpNucleusSeconds(), 1e-9, "confirmed: under a second");
	}

	@Test
	void manualSegmentsCarryTheirConfiguredTimes() {
		assertEquals(1.0 + 25 + 0.5, StopCosts.seconds(Structure.JUNGLE_TEMPLE, cfg), 1e-9);
		assertEquals(20 + 0.5, StopCosts.seconds(Structure.KHAZAD_DUM, cfg), 1e-9);
		assertEquals(0.5, StopCosts.seconds(Structure.GOBLIN_QUEENS_DEN, cfg), 1e-9, "pickup; the /warp nucleus exit is route travel");
		assertEquals(4 * 1.0 + 0.5 + 10, StopCosts.seconds(Structure.MINES_OF_DIVAN, cfg), 1e-9, "keepers, crystal, moving inside");
		cfg.stops.templeManualSeconds = 40;
		assertEquals(1.0 + 40 + 0.5, StopCosts.seconds(Structure.JUNGLE_TEMPLE, cfg), 1e-9);
	}
}
