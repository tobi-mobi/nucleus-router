package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegPlannerTest {
	private final NrConfig cfg = new NrConfig();

	private LegPlanner planner(PlanWorld w) {
		return new LegPlanner(w, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, 150), LegPlanner.Settings.fromConfig(cfg));
	}

	/** A world from a solid function; every solid block is a good target unless listed in {@code bad}. */
	private static PlanWorld world(Solid s, Set<Long> bad) {
		return new PlanWorld() {
			@Override public boolean solid(int x, int y, int z) { return s.at(x, y, z); }
			@Override public boolean goodTarget(int x, int y, int z) { return s.at(x, y, z) && !bad.contains(LegPlanner.blockKey(x, y, z)); }
		};
	}

	interface Solid { boolean at(int x, int y, int z); }

	/**
	 * Floor at y 100, ceiling at y 130, outside walls, and 2x2 pillars of different heights every 12 blocks
	 * (like the bumps and ledges of real caves: things you can aim at from far away).
	 */
	private static boolean hall(int x, int y, int z) {
		if (y <= 100 || y >= 130 || x < 0 || x > 300 || z < -40 || z > 40) return true;
		int px = Math.floorMod(x, 12), pz = Math.floorMod(z + 6, 12);
		int top = 101 + Math.floorMod(x / 12 * 5 + z / 12 * 3, 6);
		return px < 2 && pz < 2 && y <= top;
	}

	private static void checkChain(LegPlanner.Leg leg, Vec3 start, Vec3 goal, double r, NrConfig cfg, PlanWorld w) {
		assertEquals(LegPlanner.Status.FOUND, leg.status(), leg.toString());
		Vec3 at = start;
		for (LegPlanner.Warp warp : leg.warps()) {
			assertEquals(at, warp.fromFeet(), "warps chain up");
			// Replaying the planned aim with the real etherwarp model lands exactly there
			Etherwarp.Result real = Etherwarp.warp(Etherwarp.eye(at, cfg.etherwarp.eyeHeight), warp.aimDir(), Etherwarp.Params.fromConfig(cfg), w);
			assertTrue(real.valid());
			assertEquals(warp.landingFeet(), real.landingFeet());
			at = warp.landingFeet();
		}
		assertTrue(at.sub(goal).length() <= r, "ends within the goal radius");
	}

	@Test
	void crossesAHallInAFewWarps() {
		PlanWorld w = world(LegPlannerTest::hall, Set.of());
		Vec3 start = new Vec3(5.5, 101, 0.5), goal = new Vec3(250.5, 101, 0.5);
		LegPlanner.Leg leg = planner(w).plan(start, null, goal, 3);
		checkChain(leg, start, goal, 3, cfg, w);
		assertTrue(leg.warps().size() >= 4 && leg.warps().size() <= 9, "warps " + leg.warps().size());
		System.out.printf("[bench] hall 245 blocks: %d warps, %.2f s, %d expansions, %d priced, first route %.1f ms, total %.1f ms%n",
				leg.warps().size(), leg.seconds(), leg.expansions(), leg.edgesPriced(), leg.firstMicros() / 1000.0, leg.micros() / 1000.0);
	}

	@Test
	void goesAroundAWall() {
		cfg.plan.legTimeLimitMs = 5000; // correctness test, not a speed test (the wall world is a worst case)
		// A wall across the hall at x = 120, open only near z = 30..39
		Solid s = (x, y, z) -> hall(x, y, z) || (x >= 120 && x <= 122 && z < 30);
		PlanWorld w = world(s, Set.of());
		Vec3 start = new Vec3(60.5, 101, -20.5), goal = new Vec3(180.5, 101, -20.5);
		LegPlanner.Leg leg = planner(w).plan(start, null, goal, 3);
		checkChain(leg, start, goal, 3, cfg, w);
		boolean throughGap = false;
		for (LegPlanner.Warp warp : leg.warps()) if (warp.landingFeet().z() >= 25) throughGap = true;
		assertTrue(throughGap || leg.warps().stream().anyMatch(x -> x.landingFeet().y() > 101), "had to go around the wall");
	}

	@Test
	void avoidsTargetsThatAreNotGood() {
		// Floor is only a good target on the stripe z = 0; everything else is "respawning"
		Set<Long> bad = new HashSet<>();
		for (int x = 0; x <= 300; x++) for (int z = -40; z <= 40; z++) if (z != 0) bad.add(LegPlanner.blockKey(x, 100, z));
		PlanWorld w = world(LegPlannerTest::hall, bad);
		Vec3 start = new Vec3(5.5, 101, 0.5), goal = new Vec3(150.5, 101, 0.5);
		LegPlanner.Leg leg = planner(w).plan(start, null, goal, 3);
		assertEquals(LegPlanner.Status.FOUND, leg.status());
		for (LegPlanner.Warp warp : leg.warps()) assertFalse(bad.contains(LegPlanner.blockKey(warp.tx(), warp.ty(), warp.tz())), "landed on a bad target");
	}

	@Test
	void reportsNoPathAndAlreadyThere() {
		// Start sealed in a 3-block cell
		Solid s = (x, y, z) -> !(x == 0 && z == 0 && (y == 101 || y == 102 || y == 103));
		LegPlanner p = planner(world(s, Set.of()));
		LegPlanner.Leg leg = p.plan(new Vec3(0.5, 101, 0.5), null, new Vec3(100.5, 101, 0.5), 3);
		assertFalse(leg.ok());
		assertEquals(LegPlanner.Status.ALREADY_THERE, p.plan(new Vec3(0.5, 101, 0.5), null, new Vec3(1.5, 101, 0.5), 3).status());
	}

	@Test
	void turnsArePriced() {
		PlanWorld w = world(LegPlannerTest::hall, Set.of());
		Vec3 start = new Vec3(5.5, 101, 0.5), goal = new Vec3(60.5, 101, 0.5);
		LegPlanner.Leg facing = planner(w).plan(start, new Vec3(1, -0.1, 0), goal, 3);
		LegPlanner.Leg away = planner(w).plan(start, new Vec3(-1, -0.1, 0), goal, 3);
		assertTrue(away.seconds() > facing.seconds() + 0.2, "turning around costs: " + facing.seconds() + " vs " + away.seconds());
	}

	@Test
	void changedBlocksOnlyDropNearbyCacheEntries() {
		PlanWorld w = world(LegPlannerTest::hall, Set.of());
		cfg.plan.legTimeLimitMs = 60000; // effort-bounded only, so both runs do the same work
		LegPlanner p = planner(w);
		Vec3 start = new Vec3(5.5, 101, 0.5), goal = new Vec3(250.5, 101, 0.5);
		LegPlanner.Leg first = p.plan(start, null, goal, 3);
		int cached = p.cacheSize();
		assertTrue(cached > 0);
		int missesBefore = p.cacheMisses();
		LegPlanner.Leg again = p.plan(start, null, goal, 3);
		assertEquals(first.warps(), again.warps(), "same world, same plan");
		assertEquals(missesBefore, p.cacheMisses(), "second plan is served from the cache");
		assertTrue(again.micros() < first.micros(), "and is faster: " + again.micros() + " vs " + first.micros());

		// A block changes far away from the start: entries near the start survive
		p.invalidate(250, 100, 30);
		assertTrue(p.cacheSize() < cached, "entries that read that section are dropped");
		assertTrue(p.cacheSize() > 0, "the rest stay");
	}

	/** Benchmark on a larger synthetic cave: pillars and floating platforms between floor and ceiling. */
	@Test
	void benchmarkSyntheticCave() {
		Solid cave = (x, y, z) -> {
			if (y <= 40 || y >= 180 || x < 200 || x > 830 || z < 200 || z > 830) return true;
			int cx = Math.floorMod(x, 37), cz = Math.floorMod(z, 41);
			if (cx < 4 && cz < 4) return true; // pillars
			int h = (int) (Math.abs(Math.sin(x * 0.05) * Math.cos(z * 0.07)) * 60) + 60;
			return y == h && Math.floorMod(x * 7 + z * 13, 11) < 6; // patchy platforms
		};
		PlanWorld w = world(cave, Set.of());
		cfg.plan.legTimeLimitMs = 2000; // measures speed (printed), doesn't enforce it: a busy machine mustn't fail the build
		LegPlanner p = planner(w);
		double[][] legs = {{513.5, 41, 526.5, 700.5, 41, 700.5}, {300.5, 41, 300.5, 513.5, 41, 526.5}, {250.5, 41, 780.5, 780.5, 41, 250.5}};
		for (double[] l : legs) {
			Vec3 a = new Vec3(l[0], l[1], l[2]), b = new Vec3(l[3], l[4], l[5]);
			LegPlanner.Leg leg = p.plan(a, null, b, 3);
			System.out.printf("[bench] cave %.0f blocks: %s, %d warps, %.2f s, %d expansions, %d priced, first route %.1f ms, total %.1f ms%n",
					a.sub(b).length(), leg.status(), leg.warps().size(), leg.seconds(), leg.expansions(), leg.edgesPriced(), leg.firstMicros() / 1000.0, leg.micros() / 1000.0);
			assertEquals(LegPlanner.Status.FOUND, leg.status());
		}
	}
}
