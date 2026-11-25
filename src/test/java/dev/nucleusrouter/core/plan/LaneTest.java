package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LaneTest {
	private final NrConfig cfg = new NrConfig();
	private final Etherwarp.Params p = Etherwarp.Params.fromConfig(cfg);
	private final CostModel cost = new CostModel(cfg, 150);

	/** A straight tunnel: floor at y 99 (x 0..300, z -3..3), nothing else. */
	private static final SolidQuery FLOOR = (x, y, z) -> y == 99 && x >= 0 && x <= 300 && z >= -3 && z <= 3;

	/** Only the given blocks are solid (placed target blocks floating in a cleared space). */
	private static SolidQuery blocks(Set<Long> solid) {
		return (x, y, z) -> solid.contains(Lane.pack(x, y, z));
	}

	@Test
	void flatFloorTunnelKeepsTheSameAim() {
		Lane.Spec s = new Lane.Spec(new int[]{0, 99, 0}, new int[]{20, 0, 0}, 6);
		Lane.Result r = Lane.evaluate(s, p, FLOOR, 6);
		assertTrue(r.valid(), r.problem());
		assertEquals(0, Lane.firstFailingWarp(s, r.direction(), p, FLOOR), "the best direction works for every warp");
		assertTrue(r.direction().pitchDeg() > 0, "looking slightly down at the floor");

		// A flat floor is seen at a grazing angle: very little room. A raised target block 20 away has its
		// side face in view too, so it's much easier to hit.
		Set<Long> raised = new HashSet<>();
		for (int k = 1; k <= 6; k++) raised.add(Lane.pack(20 * k, 100, 0));
		SolidQuery withRaised = (x, y, z) -> FLOOR.solid(x, y, z) || raised.contains(Lane.pack(x, y, z));
		Lane.Result up = Lane.evaluate(new Lane.Spec(new int[]{0, 99, 0}, new int[]{20, 1, 0}, 1), p, withRaised, 6);
		assertTrue(up.valid(), up.problem());
		assertTrue(up.marginDeg() > 2 * r.marginDeg(), "raised " + up.marginDeg() + " vs floor " + r.marginDeg());
		System.out.printf("[lane] 20-block steps: floor margin %.2f deg, raised block margin %.2f deg%n", r.marginDeg(), up.marginDeg());
	}

	@Test
	void placedTargetsInClearedSpace() {
		Set<Long> solid = new HashSet<>();
		Lane.Spec s = new Lane.Spec(new int[]{0, 100, 0}, new int[]{30, 2, 7}, 4);
		for (int k = 0; k <= 4; k++) { int[] t = s.target(k); solid.add(Lane.pack(t[0], t[1], t[2])); }
		Lane.Result r = Lane.evaluate(s, p, blocks(solid), 6);
		assertTrue(r.valid(), r.problem());
		assertTrue(r.coneDeg() > 0);
	}

	@Test
	void aWallInOneWarpsWayIsFound() {
		Lane.Spec s = new Lane.Spec(new int[]{0, 99, 0}, new int[]{20, 0, 0}, 5);
		// A wall across the tunnel between targets 2 and 3
		SolidQuery walled = (x, y, z) -> FLOOR.solid(x, y, z) || (x == 50 && y >= 100 && y <= 110);
		Lane.Result r = Lane.evaluate(s, p, walled, 6);
		assertFalse(r.valid());
		assertEquals(3, r.failingWarp(), r.problem());
	}

	@Test
	void missingTargetOrNoRoomIsExplained() {
		Lane.Spec beyond = new Lane.Spec(new int[]{250, 99, 0}, new int[]{30, 0, 0}, 3);
		Lane.Result r = Lane.evaluate(beyond, p, FLOOR, 4);
		assertFalse(r.valid());
		assertEquals(2, r.failingWarp(), "target 2 (x 310) is past the end of the floor");
		assertTrue(r.problem().contains("not a solid block"));

		SolidQuery lowCeiling = (x, y, z) -> FLOOR.solid(x, y, z) || (y == 101 && x == 40 && z == 0);
		r = Lane.evaluate(new Lane.Spec(new int[]{0, 99, 0}, new int[]{20, 0, 0}, 3), p, lowCeiling, 4);
		assertFalse(r.valid());
		assertTrue(r.problem().contains("no room"), r.problem());
	}

	@Test
	void designPicksAWholeNumberStepThatFitsTheRange() {
		// Cleared space with only the floor; the designer may place target blocks one above the floor
		long t0 = System.nanoTime();
		List<Lane.Choice> best = Lane.design(new int[]{0, 99, 0}, new int[]{120, 100, 0}, 30, p, FLOOR, cost, Lane.Options.defaults().withPlaceTargets(true));
		double ms = (System.nanoTime() - t0) / 1e6;
		assertFalse(best.isEmpty());
		Lane.Choice c = best.get(0);
		assertTrue(c.spec().stepLength() <= p.range() - 2 + 1e-9, "step " + c.spec().stepLength());
		int[] end = c.spec().end();
		assertTrue(Math.abs(end[0] - 120) <= 2 && Math.abs(end[1] - 100) <= 2 && Math.abs(end[2]) <= 2, "ends near the wanted block");
		assertTrue(c.result().marginDeg() >= 0.3);
		for (int i = 1; i < best.size(); i++) assertTrue(best.get(i).seconds() >= best.get(i - 1).seconds(), "cheapest first");
		assertTrue(Double.isFinite(c.seconds()));
		System.out.printf("[lane] 120 blocks over a floor: %s, margin %.2f deg, %.2f s; designed in %.0f ms%n", c.spec(), c.result().marginDeg(), c.seconds(), ms);
	}

	@Test
	void designOnTheBareFloorFindsNothingForgivingEnough() {
		List<Lane.Choice> best = Lane.design(new int[]{0, 99, 0}, new int[]{120, 99, 0}, 30, p, FLOOR, cost, Lane.Options.defaults());
		assertTrue(best.isEmpty() || best.get(0).result().marginDeg() >= 0.3);
	}

	@Test
	void candidatesAreWholeNumberSteps() {
		for (Lane.Spec s : Lane.candidates(new int[]{0, 0, 0}, new int[]{100, 7, -33}, 59, 1, 2)) {
			int[] e = s.end();
			assertTrue(Math.abs(e[0] - 100) <= 1 && Math.abs(e[1] - 7) <= 1 && Math.abs(e[2] + 33) <= 1);
			assertTrue(s.stepLength() <= 59 + 1e-9);
		}
	}

	@Test
	void clearanceCoversTheRaysAndHeadroomButNotTargets() {
		Lane.Spec s = new Lane.Spec(new int[]{0, 99, 0}, new int[]{20, 0, 0}, 2);
		Lane.Result r = Lane.evaluate(s, p, FLOOR, 6);
		Set<Long> c = Lane.clearance(s, r.direction(), 1.0, p);
		assertFalse(c.contains(Lane.pack(20, 99, 0)), "targets stay solid");
		assertFalse(c.contains(Lane.pack(40, 99, 0)));
		assertTrue(c.contains(Lane.pack(20, 100, 0)) && c.contains(Lane.pack(20, 101, 0)), "room to stand on a target");
		assertTrue(c.contains(Lane.pack(0, 100, 0)), "room at the start");
		assertTrue(c.contains(Lane.pack(10, 100, 0)) || c.contains(Lane.pack(10, 101, 0)), "the ray's path halfway");
	}

	@Test
	void packRoundTrips() {
		int[][] pts = {{0, 0, 0}, {-5, 31, 822}, {1_000_000, -1_000_000, -1}};
		for (int[] q : pts) assertArrayEquals(q, Lane.unpack(Lane.pack(q[0], q[1], q[2])));
	}
}
