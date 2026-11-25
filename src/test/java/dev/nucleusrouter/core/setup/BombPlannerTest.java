package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BombPlannerTest {
	private final NrConfig cfg = new NrConfig();
	private final Etherwarp.Params p = Etherwarp.Params.fromConfig(cfg);
	private final CostModel cost = new CostModel(cfg, 150);

	/** An open cave (x -30..40, y 100..125) and Hard Stone beyond it up to x 200; floor everywhere below y 100. */
	static VoxelClass caveThenRock(int x, int y, int z) {
		if (x < -30 || x > 200 || y < 80 || y > 170 || z < -60 || z > 60) return VoxelClass.UNKNOWN;
		if (x <= 40 && y >= 100 && y <= 125 && Math.abs(z) <= 25) return VoxelClass.AIR;
		return VoxelClass.HARD_STONE;
	}

	private record Run(SetupWorld world, LegDesigner designer, LegDesigner.Design design, BombPlanner.Plan plan) {}

	private Run run(List<int[]> zones) {
		SetupWorld w = LegDesignerTest.world(BombPlannerTest::caveThenRock, zones);
		LegDesigner designer = new LegDesigner(w, p, cost, cfg);
		LegDesigner.Design d = designer.design(new int[]{0, 99, 0}, new int[]{130, 99, 0}).best();
		assertNotNull(d);
		BombPlanner.Plan plan = new BombPlanner(w, cfg).plan(new int[]{0, 99, 0}, LegDesigner.path(d), d.toClear());
		return new Run(w, designer, d, plan);
	}

	@Test
	void clearsTheTunnelFromTheStartOn() {
		Run r = run(List.of());
		BombPlanner.Plan plan = r.plan();
		assertTrue(plan.bombs() > 0);
		for (long k : r.design().toClear()) assertTrue(plan.blasted().contains(k), "everything needed is cleared");
		for (BombPlanner.Throw t : plan.throwsInOrder()) {
			double d = t.standFeet().sub(t.centre()).length();
			assertTrue(d > cfg.bombs.blastRadius, "never stand in your own blast");
			assertTrue(t.clears() > 0);
		}
		// Work goes forward: later throws are further along on average
		List<BombPlanner.Throw> ts = plan.throwsInOrder();
		if (ts.size() >= 4) assertTrue(ts.get(ts.size() - 1).centre().x() > ts.get(0).centre().x());
		LegDesigner.AfterBombs after = r.designer().afterBombs(r.design(), plan.blasted());
		assertTrue(after.valid(), after.problem());
		System.out.printf("[bombs] 130-block leg out of a cave: %d bombs, %d mined, %d needed, %d blasted, %d placements, %.0f ms%n",
				plan.bombs(), plan.mine().size(), r.design().toClear().size(), plan.blasted().size(), after.placements().size(), plan.millis());
	}

	@Test
	void neverCentresABombInAZone() {
		int[] zone = {95, 80, -40, 200, 170, 40};
		Run r = run(List.of(zone));
		for (BombPlanner.Throw t : r.plan().throwsInOrder()) {
			int cx = (int) Math.floor(t.centre().x()), cy = (int) Math.floor(t.centre().y()), cz = (int) Math.floor(t.centre().z());
			assertFalse(r.world().inZone(cx, cy, cz), "a bomb centred in a zone fizzles");
		}
		// Deep inside the zone nothing reaches: those blocks are mined by hand
		assertFalse(r.plan().mine().isEmpty());
		for (long k : r.plan().mine()) {
			int[] q = Lane.unpack(k);
			assertTrue(q[0] >= 95 - 8, "only blocks near or in the zone are mined, got x " + q[0]);
		}
		for (long k : r.design().toClear()) assertTrue(r.plan().blasted().contains(k));
	}

	@Test
	void progressAlongAPath() {
		List<int[]> path = List.of(new int[]{0, 0, 0}, new int[]{10, 0, 0}, new int[]{10, 0, 10});
		assertEquals(5, BombPlanner.progress(path, new int[]{5, 3, 0}), 1e-9);
		assertEquals(15, BombPlanner.progress(path, new int[]{12, 0, 5}), 1e-9);
	}
}
