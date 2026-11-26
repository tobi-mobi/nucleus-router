package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SetupStepsTest {
	private static final NrConfig CFG = new NrConfig();
	private static final SetupWorld WORLD = new SetupWorld() {
		@Override public VoxelClass classAt(int x, int y, int z) { return LobbySetupTest.lobby(x, y, z); }
		@Override public boolean inZone(int x, int y, int z) { return false; }
		@Override public boolean explored(int x, int z) { return true; }
	};
	private static LobbySetup.Result plan;

	/** A small lobby: the nucleus, King Yolkar and the Queen's den (King before Queen), in solid Hard Stone. */
	@BeforeAll
	static void planIt() {
		List<StopPlan.Stop> all = LobbySetupTest.stops();
		List<StopPlan.Stop> stops = List.of(all.get(0), all.get(3), all.get(4));
		CFG.setup.lobbyTimeLimitMs = 2000;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			plan = LobbySetup.plan(stops, WORLD, Map.of(), CFG, 150, pool, 2, (a, b, c) -> {});
		} finally {
			pool.shutdownNow();
		}
		assertFalse(plan.setups().isEmpty(), "the lobby needs tunnels");
		System.out.printf("[setup-steps] plan %.0f ms, %d legs, %d bombs, %d placed, problems %s%n", plan.totalMs(), plan.setups().size(), plan.bombs(), plan.placements(), plan.problems());
	}

	/** The world as the player changes it. */
	private static final class Sim implements SolidQuery {
		final Set<Long> gone = new HashSet<>(), placed = new HashSet<>();

		@Override public boolean solid(int x, int y, int z) {
			long k = Lane.pack(x, y, z);
			if (placed.contains(k)) return true;
			if (gone.contains(k)) return false;
			return WORLD.solid(x, y, z);
		}

		void blast(Vec3 c, double r) {
			int R = (int) Math.ceil(r);
			int cx = (int) Math.floor(c.x()), cy = (int) Math.floor(c.y()), cz = (int) Math.floor(c.z());
			for (int x = cx - R; x <= cx + R; x++)
				for (int y = cy - R; y <= cy + R; y++)
					for (int z = cz - R; z <= cz + R; z++)
						if (SetupSteps.inBlast(c, r, new int[]{x, y, z}) && WORLD.clearable(x, y, z)) {
							gone.add(Lane.pack(x, y, z));
							placed.remove(Lane.pack(x, y, z));
						}
		}

		void doStep(SetupSteps.Step s) {
			switch (s.kind()) {
				case THROW -> blast(s.bomb().centre(), CFG.bombs.blastRadius);
				case MINE -> { for (int[] q : s.blocks()) gone.add(Lane.pack(q[0], q[1], q[2])); }
				case PLACE -> { int[] q = s.blocks().get(0); placed.add(Lane.pack(q[0], q[1], q[2])); }
			}
		}
	}

	@Test
	void orderIsBombsThenPlacing() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		assertEquals(plan.bombs(), steps.count(SetupSteps.Kind.THROW));
		assertEquals(plan.placements(), steps.count(SetupSteps.Kind.PLACE));
		int firstPlace = -1, lastBombOrMine = -1;
		for (int i = 0; i < steps.size(); i++) {
			if (steps.steps().get(i).kind() == SetupSteps.Kind.PLACE) { if (firstPlace < 0) firstPlace = i; }
			else lastBombOrMine = i;
		}
		assertTrue(firstPlace < 0 || lastBombOrMine < firstPlace, "every bomb and mining step before the first placement");
		assertEquals("B1", steps.steps().get(0).number());
	}

	@Test
	void followsTheWorldStepByStep() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		steps.update(world, (x, y, z) -> true, 40);
		assertEquals(0, steps.current(), "nothing done yet");
		int confirmed = 0;
		while (!steps.finished()) {
			SetupSteps.Step s = steps.currentStep();
			int before = steps.current();
			world.doStep(s);
			steps.update(world, (x, y, z) -> true, 40);
			if (steps.current() == before) {
				// A throw whose blocks were all broken already (by earlier throws) can't be seen: confirm by hand
				assertEquals(SetupSteps.Kind.THROW, s.kind(), "only throws may need a confirm: " + s.number());
				steps.confirmCurrent();
				confirmed++;
			}
		}
		assertEquals(steps.size(), steps.doneCount());
		assertTrue(confirmed <= steps.count(SetupSteps.Kind.THROW) / 4, "most throws are seen by themselves; confirmed " + confirmed);
		// After the whole setup every planned lane works
		Set<Long> placed = world.placed;
		SolidQuery post = (x, y, z) -> world.solid(x, y, z);
		var checker = new dev.nucleusrouter.core.setup.LegDesigner(WORLD, dev.nucleusrouter.core.etherwarp.Etherwarp.Params.fromConfig(CFG),
				new dev.nucleusrouter.core.cost.CostModel(CFG, 150), CFG);
		for (LobbySetup.LegSetup leg : plan.setups()) assertNull(checker.validate(leg.design().lanes(), post), leg.key());
		assertFalse(placed.isEmpty());
	}

	/** A placement's block may still stand before the bombs: it isn't ticked off until the bombs are done. */
	@Test
	void placementsWaitForTheBombs() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		steps.update(world, (x, y, z) -> true, steps.size());
		for (int i = 0; i < steps.size(); i++)
			if (steps.steps().get(i).kind() == SetupSteps.Kind.PLACE) assertFalse(steps.done(i), steps.steps().get(i).number());
	}

	@Test
	void unloadedTerrainNeverCountsAsDone() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		world.doStep(steps.currentStep());
		steps.update(world, (x, y, z) -> false, 40);
		assertEquals(0, steps.current());
		steps.update(world, (x, y, z) -> true, 40);
		assertEquals(1, steps.current());
	}

	@Test
	void doneOutOfOrderAndBack() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		// Do a later throw first (one whose blast doesn't reach the first's blocks): the current step stays on the
		// first, but the later one is ticked off
		SetupSteps.Step first = steps.steps().get(0);
		int later = -1;
		for (int i = 2; i < steps.size() && later < 0; i++) {
			SetupSteps.Step s = steps.steps().get(i);
			if (s.kind() == SetupSteps.Kind.THROW && s.bomb().centre().sub(first.bomb().centre()).length() > 3 * CFG.bombs.blastRadius) later = i;
		}
		assertTrue(later > 0);
		world.doStep(steps.steps().get(later));
		steps.update(world, (x, y, z) -> true, steps.size());
		assertEquals(0, steps.current());
		assertTrue(steps.done(later));
		assertSame(first, steps.confirmCurrent());
		assertEquals(1, steps.current());
		assertSame(first, steps.back());
		assertEquals(0, steps.current());
	}

	@Test
	void aThrowCountsOnceAllItsBlocksAreGone() {
		int[][] blocks = new int[10][];
		for (int i = 0; i < 10; i++) blocks[i] = new int[]{600 + i, 100, 400};
		SetupSteps.Step s = new SetupSteps.Step(SetupSteps.Kind.THROW, "B1", 0, null, List.of(blocks), null);
		Set<Long> gone = new HashSet<>();
		SolidQuery w = (x, y, z) -> !gone.contains(Lane.pack(x, y, z));
		for (int i = 0; i < 9; i++) gone.add(Lane.pack(600 + i, 100, 400));
		assertFalse(SetupSteps.isDone(s, w, (x, y, z) -> true), "9 of 10: a needed block still stands");
		gone.add(Lane.pack(609, 100, 400));
		assertTrue(SetupSteps.isDone(s, w, (x, y, z) -> true));
	}

	@Test
	void miningIsGroupedNearby() {
		List<Long> mine = List.of(Lane.pack(0, 0, 0), Lane.pack(1, 0, 0), Lane.pack(3, 0, 0), Lane.pack(20, 0, 0), Lane.pack(21, 0, 0));
		List<List<int[]>> g = SetupSteps.groups(mine);
		assertEquals(2, g.size());
		assertEquals(3, g.get(0).size());
	}

	// ------------------------------------------------------------------ 4.7: repair and verify

	/** The simulated world as a SetupWorld (for re-planning on "the terrain as it is now"). */
	private static SetupWorld asSetupWorld(Sim sim) {
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) {
				long k = Lane.pack(x, y, z);
				if (sim.placed.contains(k)) return VoxelClass.PLACED;
				if (sim.gone.contains(k)) return VoxelClass.AIR;
				return WORLD.classAt(x, y, z);
			}
			@Override public boolean inZone(int x, int y, int z) { return false; }
			@Override public boolean explored(int x, int z) { return true; }
		};
	}

	private static SetupSteps.Step firstThrow(SetupSteps steps) {
		for (SetupSteps.Step s : steps.steps()) if (s.kind() == SetupSteps.Kind.THROW && !s.blocks().isEmpty()) return s;
		throw new AssertionError("no throw");
	}

	@Test
	void judgesWhatAThrowDid() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		SetupSteps.Step t = firstThrow(steps);
		Sim w = new Sim();
		assertEquals(SetupRepair.Outcome.FIZZLED, SetupRepair.judge(t, List.of(), w).outcome());
		// As planned
		Sim done = new Sim();
		done.doStep(t);
		List<int[]> broken = new java.util.ArrayList<>();
		for (long k : done.gone) broken.add(Lane.unpack(k));
		assertEquals(SetupRepair.Outcome.DONE, SetupRepair.judge(t, broken, done).outcome());
		// 20 blocks off
		Sim off = new Sim();
		Vec3 c = t.bomb().centre().add(new Vec3(0, 0, 20));
		off.blast(c, CFG.bombs.blastRadius);
		List<int[]> b2 = new java.util.ArrayList<>();
		for (long k : off.gone) b2.add(Lane.unpack(k));
		SetupRepair.Judgement j = SetupRepair.judge(t, b2, off);
		assertEquals(SetupRepair.Outcome.MISSED, j.outcome());
		assertEquals(20, j.offBy(), 1.0);
		// Right place, smaller blast
		Sim small = new Sim();
		small.blast(t.bomb().centre(), 3);
		List<int[]> b3 = new java.util.ArrayList<>();
		for (long k : small.gone) b3.add(Lane.unpack(k));
		assertEquals(SetupRepair.Outcome.PARTIAL, SetupRepair.judge(t, b3, small).outcome());
	}

	/**
	 * The first bomb fizzles (near a structure, say). Only that leg's remaining bomb work is planned again, with no
	 * blast near the fizzle; once the bombs are done the placing is planned again on the real terrain; at the end
	 * every lane works.
	 */
	@Test
	void fizzledBombIsReplannedAndTheSetupStillWorks() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		SetupSteps.Step fizzled = steps.currentStep();
		assertEquals(SetupSteps.Kind.THROW, fizzled.kind());
		int before = steps.count(SetupSteps.Kind.THROW);
		int[] numbers = {steps.lastNumber(SetupSteps.Kind.THROW), steps.lastNumber(SetupSteps.Kind.MINE)};
		List<SetupSteps.Step> fresh = SetupRepair.replanBombs(fizzled.leg(), steps.designs().get(fizzled.leg()), asSetupWorld(world),
				List.of(fizzled.bomb().centre()), CFG, numbers);
		for (SetupSteps.Step s : fresh)
			if (s.kind() == SetupSteps.Kind.THROW)
				assertTrue(s.bomb().centre().sub(fizzled.bomb().centre()).length() > SetupRepair.FIZZLE_RADIUS, "no bomb near the fizzle");
		steps.replaceOpenBombWork(fizzled.leg(), fresh);
		assertNotSame(fizzled, steps.currentStep(), "the fizzled throw is gone from the list");
		assertNotEquals(fizzled.number(), steps.currentStep().number(), "new steps get new numbers");
		System.out.printf("[setup-steps] after a fizzle: %d throws (was %d)%n", steps.count(SetupSteps.Kind.THROW), before);

		// Do all the bomb work
		boolean replacedPlacing = false;
		while (!steps.finished()) {
			if (steps.bombWorkDone() && !replacedPlacing) {
				List<SetupSteps.Step> left = SetupRepair.leftovers(steps.designs(), world, steps.lastNumber(SetupSteps.Kind.MINE));
				if (!left.isEmpty()) { steps.insertBeforePlacing(left); continue; }
				var placing = LobbySetup.placeAll(steps.designs(), asSetupWorld(world), Set.of(), CFG, 150, i -> {});
				for (var p : placing) assertTrue(p.impossible().isEmpty());
				steps.replaceOpenPlacements(SetupSteps.placeSteps(placing, 0));
				replacedPlacing = true;
				continue;
			}
			SetupSteps.Step s = steps.currentStep();
			world.doStep(s);
			int at = steps.current();
			steps.update(world, (x, y, z) -> true, 60);
			if (steps.current() == at) steps.confirmCurrent();
		}
		var checker = new dev.nucleusrouter.core.setup.LegDesigner(WORLD, dev.nucleusrouter.core.etherwarp.Etherwarp.Params.fromConfig(CFG),
				new dev.nucleusrouter.core.cost.CostModel(CFG, 150), CFG);
		for (var d : steps.designs()) assertNull(checker.validate(d.lanes(), world), "every lane works after the repaired setup");
	}

	@Test
	void skippedThrowsLeaveMiningBeforeThePlacing() {
		SetupSteps steps = SetupSteps.of(plan, CFG.bombs.blastRadius);
		Sim world = new Sim();
		steps.confirmCurrent(); // B1 "done" by hand, but never thrown
		while (!steps.bombWorkDone()) {
			world.doStep(steps.currentStep());
			int at = steps.current();
			steps.update(world, (x, y, z) -> true, 60);
			if (steps.current() == at) steps.confirmCurrent();
		}
		List<SetupSteps.Step> left = SetupRepair.leftovers(steps.designs(), world, steps.lastNumber(SetupSteps.Kind.MINE));
		assertFalse(left.isEmpty(), "B1's blocks are still there: mine them");
		int n = steps.size();
		steps.insertBeforePlacing(left);
		assertEquals(n + left.size(), steps.size());
		assertEquals(SetupSteps.Kind.MINE, steps.currentStep().kind());
	}

	@Test
	void verifyFindsWarpsThatDontWorkYet() {
		var p = dev.nucleusrouter.core.etherwarp.Etherwarp.Params.fromConfig(CFG);
		List<SetupRepair.WarpCheck> checks = new java.util.ArrayList<>();
		for (LobbySetup.LegSetup leg : plan.setups()) checks.addAll(SetupRepair.lanes(leg.key(), leg.design().lanes()));
		assertFalse(checks.isEmpty());
		// Before the setup (solid rock): every warp fails
		List<SetupRepair.Failure> before = SetupRepair.verify(checks, p, WORLD::solid);
		assertEquals(checks.size(), before.size());
		// After the planned setup: none
		SolidQuery after = WORLD.after(plan.blasted(), plan.placed());
		assertTrue(SetupRepair.verify(checks, p, after).isEmpty());
		// One target block missing: that warp is reported, with the reason
		SetupRepair.WarpCheck w = checks.get(0);
		long gone = Lane.pack(w.target()[0], w.target()[1], w.target()[2]);
		SolidQuery missing = (x, y, z) -> Lane.pack(x, y, z) != gone && after.solid(x, y, z);
		List<SetupRepair.Failure> f = SetupRepair.verify(checks, p, missing);
		assertFalse(f.isEmpty());
		assertEquals(w, f.get(0).warp());
		assertEquals("the target block is missing", f.get(0).why());
	}
}
