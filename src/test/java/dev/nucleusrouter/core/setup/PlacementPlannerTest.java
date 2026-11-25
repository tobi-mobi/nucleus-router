package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlacementPlannerTest {
	private final NrConfig cfg = new NrConfig();

	/** A big open hall: Hard Stone floor at y 99 and below, air above, x/z -40..40. */
	static VoxelClass hall(int x, int y, int z) {
		if (Math.abs(x) > 40 || Math.abs(z) > 40 || y < 80 || y > 140) return VoxelClass.UNKNOWN;
		return y <= 99 ? VoxelClass.HARD_STONE : VoxelClass.AIR;
	}

	private PlacementPlanner.Plan plan(SetupWorld w, Set<Long> targets, java.util.function.Predicate<SolidQuery> lanesWork) {
		List<int[]> path = List.of(new int[]{0, 99, -10}, new int[]{0, 99, 10});
		return new PlacementPlanner(w, cfg).plan(path, targets, Set.of(), lanesWork);
	}

	private static Set<Long> set(int[]... bs) {
		Set<Long> s = new LinkedHashSet<>();
		for (int[] b : bs) s.add(Lane.pack(b[0], b[1], b[2]));
		return s;
	}

	/** Every step can really be done in the world as it is at that moment. */
	private void assertDoable(SetupWorld w, PlacementPlanner.Plan plan, Set<Long> cleared) {
		Set<Long> placed = new HashSet<>();
		for (PlacementPlanner.Step s : plan.steps()) {
			SolidQuery now = (x, y, z) -> placed.contains(Lane.pack(x, y, z)) || (!cleared.contains(Lane.pack(x, y, z)) && w.solid(x, y, z));
			int[] b = s.block(), sup = s.support();
			assertFalse(now.solid(b[0], b[1], b[2]), "the spot is empty");
			assertTrue(now.solid(sup[0], sup[1], sup[2]), "the clicked block is there");
			assertEquals(1, Math.abs(b[0] - sup[0]) + Math.abs(b[1] - sup[1]) + Math.abs(b[2] - sup[2]), "support touches the block");
			if (s.pillar()) {
				assertArrayEquals(new int[]{b[0], b[1] - 1, b[2]}, sup, "pillar: placed on the block below you");
				assertFalse(now.solid(b[0], b[1] + 1, b[2]) || now.solid(b[0], b[1] + 2, b[2]), "pillar: room for your body above");
				placed.add(Lane.pack(b[0], b[1], b[2]));
				continue;
			}
			if (s.edge()) {
				// Sneaking at the edge of the pillar top: the pillar is under your feet's block
				int px = s.support()[0], py = s.support()[1], pz = s.support()[2];
				assertEquals(py + 1, (int) Math.floor(s.standFeet().y()), "standing on the pillar top");
				assertTrue(Math.abs(s.standFeet().x() - (px + 0.5)) <= 0.8 && Math.abs(s.standFeet().z() - (pz + 0.5)) <= 0.8, "still on the pillar");
				assertTrue(s.standFeet().add(new dev.nucleusrouter.core.geom.Vec3(0, PlacementPlanner.STANDING_EYE, 0)).sub(s.clickPoint()).length() <= cfg.placing.reach);
				placed.add(Lane.pack(b[0], b[1], b[2]));
				continue;
			}
			var eye = s.standFeet().add(new dev.nucleusrouter.core.geom.Vec3(0, PlacementPlanner.STANDING_EYE, 0));
			assertTrue(eye.sub(s.clickPoint()).length() <= cfg.placing.reach + 1e-9, "within reach");
			VoxelRay.Hit hit = VoxelRay.cast(eye, s.look(), cfg.placing.reach + 0.5, now);
			assertTrue(hit != null && hit.isBlock(sup[0], sup[1], sup[2]), "nothing in the way of the click");
			int fx = (int) Math.floor(s.standFeet().x()), fy = (int) Math.floor(s.standFeet().y()), fz = (int) Math.floor(s.standFeet().z());
			assertTrue(now.solid(fx, fy - 1, fz) && !now.solid(fx, fy, fz) && !now.solid(fx, fy + 1, fz), "a real spot to stand");
			assertFalse(fx == b[0] && fz == b[2] && (fy == b[1] || fy + 1 == b[1]), "not standing inside the new block");
			placed.add(Lane.pack(b[0], b[1], b[2]));
		}
	}

	@Test
	void raisedTargetOnTheFloor() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{5, 100, 0}), q -> true);
		assertTrue(p.impossible().isEmpty());
		assertEquals(1, p.steps().size());
		PlacementPlanner.Step s = p.steps().get(0);
		assertArrayEquals(new int[]{5, 99, 0}, s.support(), "placed on the floor");
		assertEquals(Face.UP, s.face());
		assertFalse(s.helper());
		assertDoable(w, p, Set.of());
	}

	@Test
	void floatingTargetGetsAHelperUnderIt() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 101, 0}), q -> true);
		assertTrue(p.impossible().isEmpty());
		assertEquals(1, p.helpers());
		assertEquals(1, p.targets());
		assertArrayEquals(new int[]{0, 100, 0}, p.steps().get(0).block(), "helper first");
		assertArrayEquals(new int[]{0, 101, 0}, p.steps().get(1).block());
		assertDoable(w, p, Set.of());
	}

	/**
	 * You can only click the top of a block below your eyes: standing on the floor (eye 101.62), a block whose
	 * top is at 102 or higher can't be built on from there. So a block 4 above the floor is pillared up to.
	 */
	@Test
	void highTargetIsPillaredUpTo() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 103, 0}), q -> true);
		assertTrue(p.impossible().isEmpty());
		assertEquals(4, p.pillarBlocks());
		assertEquals(3, p.helpers());
		for (int i = 0; i < 4; i++) assertArrayEquals(new int[]{0, 100 + i, 0}, p.steps().get(i).block(), "bottom up");
		assertDoable(w, p, Set.of());
	}

	/** A pillar under the target would block a lane: build it beside the target and place from the edge. */
	@Test
	void pillarBesideWhenUnderIsInTheWay() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 106, 0}), q -> !q.solid(0, 103, 0));
		assertTrue(p.impossible().isEmpty());
		PlacementPlanner.Step last = p.steps().get(p.steps().size() - 1);
		assertTrue(last.edge());
		assertArrayEquals(new int[]{0, 106, 0}, last.block());
		for (PlacementPlanner.Step s : p.steps()) assertFalse(s.block()[0] == 0 && s.block()[2] == 0 && s.block()[1] < 106, "nothing under the target");
		assertDoable(w, p, Set.of());
	}

	@Test
	void tooHighForAPillarIsReportedNotGuessed() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 100 + cfg.setup.maxPillarBlocks + 2, 0}), q -> true);
		assertEquals(1, p.impossible().size());
		assertTrue(p.steps().isEmpty());
	}

	@Test
	void noPillarThatBreaksALane() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		// Anything at height 104 within 1 block of the target breaks the lane: no pillar under or beside works
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 108, 0}), q -> {
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (q.solid(dx, 104, dz)) return false;
			return true;
		});
		assertEquals(1, p.impossible().size());
	}

	/** A ledge to stand on makes the same block possible. */
	@Test
	void aLedgeToStandOnHelps() {
		SetupWorld w = LegDesignerTest.world((x, y, z) -> x >= 3 && x <= 6 && y <= 102 && Math.abs(z) <= 40 && y >= 80
				? dev.nucleusrouter.core.world.VoxelClass.HARD_STONE : hall(x, y, z), List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 103, 0}), q -> true);
		assertTrue(p.impossible().isEmpty(), "stand on the ledge (top at 103) and build out");
		assertDoable(w, p, Set.of());
	}

	@Test
	void helpersThatWouldBreakALaneAreAvoided() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		// Pretend a lane ray passes through (0, 100, 0): no helper may go there
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 101, 0}), q -> !q.solid(0, 100, 0));
		assertTrue(p.impossible().isEmpty());
		for (PlacementPlanner.Step s : p.steps()) assertFalse(s.block()[0] == 0 && s.block()[1] == 100 && s.block()[2] == 0);
		assertEquals(2, p.helpers(), "goes round: one helper beside it, standing on another");
		assertDoable(w, p, Set.of());
	}

	@Test
	void placementsInAStructureZoneAreFlagged() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of(new int[]{3, 90, -3, 10, 110, 3}));
		PlacementPlanner.Plan p = plan(w, set(new int[]{5, 100, 0}, new int[]{-5, 100, 0}), q -> true);
		assertEquals(1, p.nearStructure());
	}

	@Test
	void ordersWorkAlongTheLeg() {
		SetupWorld w = LegDesignerTest.world(PlacementPlannerTest::hall, List.of());
		PlacementPlanner.Plan p = plan(w, set(new int[]{0, 100, 8}, new int[]{0, 100, -8}, new int[]{0, 100, 0}), q -> true);
		assertEquals(List.of(-8, 0, 8), p.steps().stream().map(s -> s.block()[2]).toList());
	}

	/** The whole chain on a designed leg: design, bombs, check after the bombs, then placements. */
	@Test
	void designedLegCanBePlaced() {
		NrConfig c = cfg;
		SetupWorld w = LegDesignerTest.world(BombPlannerTest::caveThenRock, List.of());
		LegDesigner designer = new LegDesigner(w, Etherwarp.Params.fromConfig(c), new CostModel(c, 150), c);
		LegDesigner.Design d = designer.design(new int[]{0, 99, 0}, new int[]{130, 99, 0}).best();
		assertNotNull(d);
		BombPlanner.Plan bombs = new BombPlanner(w, c).plan(new int[]{0, 99, 0}, LegDesigner.path(d), d.toClear());
		LegDesigner.AfterBombs after = designer.afterBombs(d, bombs.blasted());
		assertTrue(after.valid(), after.problem());
		PlacementPlanner.Plan p = new PlacementPlanner(w, c).plan(LegDesigner.path(d), after.placements(), bombs.blasted(),
				post -> designer.validate(d.lanes(), post) == null);
		System.out.printf("[place] 130-block leg: %d targets to place, %d placed + %d helpers (%d pillar blocks), %d impossible, %.0f ms%n",
				after.placements().size(), p.targets(), p.helpers(), p.pillarBlocks(), p.impossible().size(), p.millis());
		assertDoable(w, p, bombs.blasted());
		assertEquals(after.placements().size(), p.targets() + p.impossible().size(), "every target is placed or reported");
		assertTrue(p.impossible().isEmpty(), "targets over bomb craters are pillared up to");
		// With everything placed, every lane still works
		Set<Long> all = new HashSet<>();
		for (PlacementPlanner.Step s : p.steps()) all.add(Lane.pack(s.block()[0], s.block()[1], s.block()[2]));
		if (p.impossible().isEmpty()) assertNull(designer.validate(d.lanes(), w.after(bombs.blasted(), all)));
	}
}
