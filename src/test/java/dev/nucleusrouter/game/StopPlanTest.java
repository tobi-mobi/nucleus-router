package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.plan.StopPicker;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.core.world.WorldStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StopPlanTest {
	private final NrConfig cfg = new NrConfig();

	/** Floor at y 100; a low ceiling (y 104) over x < 0, open sky elsewhere. */
	private static final PlanWorld ROOM = new PlanWorld() {
		@Override public boolean solid(int x, int y, int z) { return y <= 100 || (x < 0 && y >= 104); }
		@Override public boolean goodTarget(int x, int y, int z) { return solid(x, y, z); }
	};

	@Test
	void picksAnOpenSpotWithinReachOfTheNpc() {
		Vec3 npc = new Vec3(0.5, 101, 0.5);
		StopPicker.Pick p = StopPicker.pick(ROOM, StopPicker.npcBox(npc), 3.0, cfg.etherwarp.eyeHeight, 2);
		assertTrue(p.found());
		assertTrue(p.distance() <= 3.0);
		assertTrue(p.standFeet().x() >= 0, "prefers the open side, not under the low ceiling: " + p.standFeet());
	}

	@Test
	void noSpotWhenNothingIsInReach() {
		PlanWorld air = new PlanWorld() {
			@Override public boolean solid(int x, int y, int z) { return false; }
			@Override public boolean goodTarget(int x, int y, int z) { return false; }
		};
		assertFalse(StopPicker.pick(air, StopPicker.npcBox(new Vec3(0.5, 101, 0.5)), 3, 1.27, 2).found());
	}

	@Test
	void denExitIsWarpNucleusWhenItsFastEnough() {
		assertEquals(StopPlan.DenExit.WARP_NUCLEUS, StopPlan.denExit(cfg), "1 s warp, counts as leaving (confirmed)");
		cfg.hollows.warpNucleusSeconds = 2.8;
		assertEquals(StopPlan.DenExit.ETHERWARP_UP, StopPlan.denExit(cfg), "slower than the 2.5 s target");
	}

	@Test
	void stopsForFoundStructures() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.KING_YOLKAR, "King Yolkar", 5.5, 101, 5.5, 0, true);
		m.setCrystal(Structure.KHAZAD_DUM, new int[]{20, 101, 20}, new int[]{30, 113, 30});
		List<StopPlan.Stop> stops = StopPlan.build(m, null, ROOM, cfg);
		assertEquals(StopPlan.Kind.NUCLEUS, stops.get(0).kind());
		StopPlan.Stop king = stops.stream().filter(s -> s.id().equals("king")).findFirst().orElseThrow();
		assertTrue(king.ready());
		StopPlan.Stop bal = stops.stream().filter(s -> s.id().equals("khazad.bal")).findFirst().orElseThrow();
		assertEquals(new Vec3(25.5, 114, 25.5), bal.standFeet(), "on top of the crystal box");
		assertTrue(bal.manual());
		assertEquals(3, stops.size(), "nucleus, King, Bal: nothing for structures not found");
	}

	/** On the user's real lobby (bench/, git-ignored): every keeper and Professor Robot get a stop spot. */
	@Test
	void realLobbyStops() throws Exception {
		Path file = Path.of("bench", "mini92AD.nrw");
		Assumptions.assumeTrue(Files.exists(file), "no real lobby in bench/");
		WorldSnapshot snap;
		try (InputStream in = Files.newInputStream(file)) { snap = WorldStore.read(in, BlockClassifier.fromConfig(cfg)).model().snapshot(); }
		StructureMap m = new StructureMap();
		String[] names = {"Keeper of Diamond", "Keeper of Gold", "Keeper of Lapis", "Keeper of Emerald"};
		double[][] pos = {{588.5, 89, 442.5}, {618.5, 89, 478.5}, {654.5, 89, 448.5}, {624.5, 89, 412.5}};
		for (int i = 0; i < 4; i++) m.observeNametag(Structure.MINES_OF_DIVAN, names[i], pos[i][0], pos[i][1], pos[i][2], 0, true);
		m.observeNametag(Structure.PRECURSOR_CITY, "Professor Robot", 714.5, 118, 600.5, 0, true);
		long t = System.nanoTime();
		List<StopPlan.Stop> stops = StopPlan.build(m, null, PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks)), cfg);
		System.out.printf("[bench-real] %d stops in %.0f ms%n", stops.size(), (System.nanoTime() - t) / 1e6);
		for (StopPlan.Stop s : stops) System.out.printf("[bench-real]   %s: stand %s open %.2f%n", s.label(), s.standFeet(), s.openness());
		assertEquals(6, stops.size());
		for (StopPlan.Stop s : stops) assertTrue(s.ready(), s.label() + " has a spot");
	}
}
