package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-lobby setup plan (roadmap 4.5) on a synthetic lobby: the Hollows as solid Hard Stone with a small open
 * pocket at each stop and a few ore veins (respawning, can't be cleared) across the way. The real-lobby benchmark
 * needs a lobby save from the user's game (not in the repository).
 */
class LobbySetupTest {
	/** Stop spots (feet), roughly where structures sit in a real lobby. */
	static final double[][] SPOTS = {
			{512.5, 106, 526.5}, // nucleus
			{620.5, 90, 450.5},  // divan
			{700.5, 118, 600.5}, // precursor
			{400.5, 110, 620.5}, // king
			{430.5, 115, 560.5}, // queen
			{350.5, 100, 350.5}, // temple
			{640.5, 105, 330.5}, // khazad
	};
	static final String[] IDS = {"nucleus", "divan", "precursor", "king", "queen", "temple", "khazad"};
	static final Structure[] STRUCTS = {null, Structure.MINES_OF_DIVAN, Structure.PRECURSOR_CITY, Structure.KING_YOLKAR,
			Structure.GOBLIN_QUEENS_DEN, Structure.JUNGLE_TEMPLE, Structure.KHAZAD_DUM};

	static VoxelClass lobby(int x, int y, int z) {
		if (x < 202 || x > 823 || z < 202 || z > 823 || y < 31 || y > 188) return VoxelClass.UNKNOWN;
		for (double[] s : SPOTS) {
			int sx = (int) Math.floor(s[0]), sy = (int) Math.floor(s[1]), sz = (int) Math.floor(s[2]);
			if (Math.abs(x - sx) <= 3 && Math.abs(z - sz) <= 3 && y >= sy && y <= sy + 4) return VoxelClass.AIR;
		}
		// Ore veins: thin walls the lanes must go around or over
		if (x == 560 && y >= 80 && y <= 115 && z >= 380 && z <= 520) return VoxelClass.RESPAWNING_SOLID;
		if (z == 590 && y >= 95 && y <= 125 && x >= 440 && x <= 520) return VoxelClass.RESPAWNING_SOLID;
		return VoxelClass.HARD_STONE;
	}

	static List<StopPlan.Stop> stops() {
		List<StopPlan.Stop> out = new ArrayList<>();
		for (int i = 0; i < SPOTS.length; i++) {
			Vec3 p = new Vec3(SPOTS[i][0], SPOTS[i][1], SPOTS[i][2]);
			out.add(new StopPlan.Stop(IDS[i], STRUCTS[i] == null ? "Crystal Nucleus" : STRUCTS[i].displayName, STRUCTS[i],
					i == 0 ? StopPlan.Kind.NUCLEUS : StopPlan.Kind.STRUCTURE, p, p, 1, i == 0 ? 0 : 5, false, ""));
		}
		return out;
	}

	@Test
	void wholeLobbyWithinTheBudget() {
		NrConfig cfg = new NrConfig();
		SetupWorld world = new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return lobby(x, y, z); }
			@Override public boolean inZone(int x, int y, int z) { return false; }
			@Override public boolean explored(int x, int z) { return true; }
		};
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<String> phases = new java.util.concurrent.CopyOnWriteArrayList<>();
			LobbySetup.Result r = LobbySetup.plan(stops(), world, Map.of(), cfg, 150, pool, threads,
					(phase, done, total) -> { if (phases.isEmpty() || !phases.get(phases.size() - 1).equals(phase)) phases.add(phase); });
			System.out.printf("[bench-setup] %d threads: %d legs wanted, %d designed, %d cut short; design %.0f ms, order %.0f ms, bombs %.0f ms, placing %.0f ms, total %.0f ms%n",
					threads, r.legsWanted(), r.designs().size(), r.legsTimedOut(), r.designMs(), r.orderMs(), r.bombsMs(), r.placeMs(), r.totalMs());
			System.out.printf("[bench-setup] run %.1f s over %d designed legs; %d bombs, %d mined, %d blocks placed; problems %s%n",
					r.plan().seconds(), r.setups().size(), r.bombs(), r.mined(), r.placements(), r.problems());
			assertTrue(r.plan().hasRoute(), "every structure is reachable with a setup");
			assertTrue(r.plan().unreachable().isEmpty());
			assertEquals(6, r.setups().stream().filter(s -> !s.to().equals("nucleus")).count() + countWarpNucleusArrivals(r),
					"each of the 6 structures is reached by a designed leg (or from the nucleus via /warp nucleus + a designed leg)");
			assertTrue(r.problems().isEmpty(), "final check: " + r.problems());
			// Every placed block is outside every blast (placed after all bombs)
			for (long k : r.placed()) assertFalse(r.blasted().contains(k) && !world.clearable(Lane.unpack(k)[0], Lane.unpack(k)[1], Lane.unpack(k)[2]));
			assertEquals(List.of("designing legs", "ordering", "bombs", "placing", "done"), phases.stream().distinct().toList());
			// SPEC §3.4 target: 5 s on a 6-core CPU. Allow headroom for slow test machines.
			assertTrue(r.totalMs() < cfg.setup.lobbyTimeLimitMs * 2.0, "took " + r.totalMs() + " ms");
		} finally {
			pool.shutdownNow();
		}
	}

	private static long countWarpNucleusArrivals(LobbySetup.Result r) { return 0; }

	@Test
	void terrainRoutesWinWhenTheyAreFaster() {
		NrConfig cfg = new NrConfig();
		cfg.setup.lobbyTimeLimitMs = 1500;
		SetupWorld world = new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return lobby(x, y, z); }
			@Override public boolean inZone(int x, int y, int z) { return false; }
			@Override public boolean explored(int x, int z) { return true; }
		};
		// Pretend every leg is already 1 s on the terrain: nothing needs a setup
		Map<String, Double> terrain = new java.util.HashMap<>();
		for (String[] p : RouteOrder.neededLegs(stops(), cfg)) terrain.put(p[0] + ">" + p[1], 1.0);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			LobbySetup.Result r = LobbySetup.plan(stops(), world, terrain, cfg, 150, pool, 2, (a, b, c) -> {});
			assertTrue(r.setups().isEmpty());
			assertEquals(0, r.bombs());
		} finally {
			pool.shutdownNow();
		}
	}
}
