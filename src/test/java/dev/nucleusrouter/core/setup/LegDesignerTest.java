package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegDesignerTest {
	private final NrConfig cfg = new NrConfig();
	private final Etherwarp.Params p = Etherwarp.Params.fromConfig(cfg);
	private final CostModel cost = new CostModel(cfg, 150);

	interface Classes { VoxelClass at(int x, int y, int z); }

	/** Solid Hard Stone from x -10..200, y 80..170, z -60..60 (unseen outside), a small air pocket at the start. */
	static VoxelClass rock(int x, int y, int z) {
		if (x < -10 || x > 200 || y < 80 || y > 170 || z < -60 || z > 60) return VoxelClass.UNKNOWN;
		if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && y >= 100 && y <= 102) return VoxelClass.AIR;
		return VoxelClass.HARD_STONE;
	}

	static SetupWorld world(Classes c, List<int[]> zones) {
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return c.at(x, y, z); }
			@Override public boolean inZone(int x, int y, int z) { return SetupWorld.inAny(zones, x, y, z); }
			@Override public boolean explored(int x, int z) { return true; }
		};
	}

	@Test
	void pathsStartWithTheStraightLineAndBendAtMostThreeTimes() {
		List<List<int[]>> paths = LegDesigner.paths(new int[]{0, 99, 0}, new int[]{120, 99, 0}, 3, 16);
		assertEquals(2, paths.get(0).size(), "straight first");
		for (List<int[]> path : paths) assertTrue(path.size() - 2 <= 3);
		assertTrue(paths.size() > 100);
		assertTrue(LegDesigner.paths(new int[]{0, 99, 0}, new int[]{120, 99, 0}, 0, 16).size() == 1);
	}

	@Test
	void solidHardStoneGetsAStraightTunnel() {
		SetupWorld w = world(LegDesignerTest::rock, List.of());
		LegDesigner.Result r = new LegDesigner(w, p, cost, cfg).design(new int[]{0, 99, 0}, new int[]{120, 99, 0});
		LegDesigner.Design d = r.best();
		assertNotNull(d, "found a design; tried " + r.pathsTried());
		assertTrue(d.valid(), d.problem());
		assertEquals(0, d.bends(), "nothing in the way: straight");
		int[] end = d.end();
		assertTrue(Math.abs(end[0] - 120) <= 2 && Math.abs(end[1] - 99) <= 2 && Math.abs(end[2]) <= 2);
		assertFalse(d.toClear().isEmpty(), "the tunnel has to be cleared");
		for (long k : d.toClear()) { int[] q = Lane.unpack(k); assertEquals(VoxelClass.HARD_STONE, w.classAt(q[0], q[1], q[2])); }
		System.out.printf("[design] 120 blocks of Hard Stone: %d warps in %d lane(s), %.2f s, clear %d blocks, place %d; %d paths in %.0f ms%n",
				d.warps(), d.lanes().size(), d.runSeconds(), d.toClear().size(), d.placements().size(), r.pathsTried(), r.millis());
	}

	@Test
	void goesAroundAnOreWall() {
		// A gemstone vein (respawns, can't be cleared) across the straight line, open above y 118
		Classes c = (x, y, z) -> (x >= 58 && x <= 62 && y <= 118 && Math.abs(z) <= 60) && rock(x, y, z) != VoxelClass.UNKNOWN
				? VoxelClass.RESPAWNING_SOLID : rock(x, y, z);
		SetupWorld w = world(c, List.of());
		LegDesigner.Result r = new LegDesigner(w, p, cost, cfg).design(new int[]{0, 99, 0}, new int[]{120, 99, 0});
		LegDesigner.Design d = r.best();
		assertNotNull(d, "found a way around; tried " + r.pathsTried() + ", blocked " + r.pathsBlocked());
		assertTrue(d.bends() >= 1, "had to bend");
		for (long k : d.toClear()) { int[] q = Lane.unpack(k); assertNotEquals(VoxelClass.RESPAWNING_SOLID, w.classAt(q[0], q[1], q[2])); }
		for (LegDesigner.PlannedLane l : d.lanes())
			for (int k = 1; k <= l.spec().warps(); k++) {
				int[] t = l.spec().target(k);
				assertNotEquals(VoxelClass.RESPAWNING_SOLID, w.classAt(t[0], t[1], t[2]), "never a target on the vein");
			}
		System.out.printf("[design] around an ore wall: %d bends, %d warps, %.2f s, clear %d; %d paths (%d blocked) in %.0f ms%n",
				d.bends(), d.warps(), d.runSeconds(), d.toClear().size(), r.pathsTried(), r.pathsBlocked(), r.millis());
	}

	@Test
	void nothingThroughUnbreakableWalls() {
		// Bedrock-like wall everywhere across the leg: no design
		Classes c = (x, y, z) -> x >= 58 && x <= 62 && rock(x, y, z) != VoxelClass.UNKNOWN ? VoxelClass.UNBREAKABLE : rock(x, y, z);
		NrConfig quick = new NrConfig();
		quick.setup.legTimeLimitMs = 3000;
		LegDesigner.Result r = new LegDesigner(world(c, List.of()), p, cost, quick).design(new int[]{0, 99, 0}, new int[]{120, 99, 0});
		assertNull(r.best());
	}

	@Test
	void validationCatchesSomethingInTheWay() {
		SetupWorld w = world(LegDesignerTest::rock, List.of());
		LegDesigner designer = new LegDesigner(w, p, cost, cfg);
		LegDesigner.Design d = designer.design(new int[]{0, 99, 0}, new int[]{120, 99, 0}).best();
		assertNotNull(d);
		SolidQuery post = w.after(d.toClear(), d.placements());
		assertNull(designer.validate(d.lanes(), post));
		// One of the cleared blocks right in the middle of the first warp stays (e.g. a bomb didn't get it)
		LegDesigner.PlannedLane l = d.lanes().get(0);
		var eye = l.spec().eye(0, p.eyeHeight());
		var mid = eye.add(l.direction().scale(8));
		Set<Long> cleared = new HashSet<>(d.toClear());
		cleared.remove(Lane.pack((int) Math.floor(mid.x()), (int) Math.floor(mid.y()), (int) Math.floor(mid.z())));
		assertNotNull(designer.validate(d.lanes(), w.after(cleared, d.placements())));
	}

	@Test
	void zoneBlocksAreCounted() {
		// A zone covering the end of the leg
		SetupWorld w = world(LegDesignerTest::rock, List.of(new int[]{100, 80, -20, 160, 140, 20}));
		LegDesigner.Design d = new LegDesigner(w, p, cost, cfg).design(new int[]{0, 99, 0}, new int[]{120, 99, 0}).best();
		assertNotNull(d);
		assertTrue(d.inZone() > 0);
	}
}
