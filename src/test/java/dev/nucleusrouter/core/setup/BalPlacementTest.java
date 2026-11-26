package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.world.VoxelClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BalPlacementTest {
	private final NrConfig cfg = new NrConfig();

	/** Hard Stone floor up to y 100, plus optional extra solid blocks. */
	private static SetupWorld world(java.util.function.Predicate<int[]> extra) {
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) {
				return y <= 100 || extra.test(new int[]{x, y, z}) ? VoxelClass.HARD_STONE : VoxelClass.AIR;
			}
			@Override public boolean inZone(int x, int y, int z) { return false; }
			@Override public boolean explored(int x, int z) { return true; }
		};
	}

	private static LegPlanner.Warp warp(Vec3 from, int tx, int ty, int tz) {
		Vec3 land = new Vec3(tx + 0.5, ty + 1, tz + 0.5);
		Vec3 aim = new Vec3(tx + 0.5, ty + 0.999, tz + 0.5);
		return new LegPlanner.Warp(from, tx, ty, tz, aim, aim.sub(from.add(new Vec3(0, 1.27, 0))).normalize(), land, 2, 0, 0, 0.4);
	}

	/** Two warps into the fight; the second only because the first landed short. */
	private static List<LegPlanner.Warp> twoWarps() {
		return List.of(warp(new Vec3(0.5, 101, 0.5), 20, 100, 0), warp(new Vec3(20.5, 101, 0.5), 40, 100, 0));
	}

	@Test
	void oneBlockNearTheTopazSpotSavesAWarp() {
		BalPlacement.Suggestion s = BalPlacement.suggest(world(b -> false), twoWarps(), new Vec3(40.5, 101, 0.5), cfg);
		assertTrue(s.found(), s.reason());
		assertEquals(0, s.fromWarp());
		assertEquals(1, s.warpsSaved());
		assertEquals(0.4, s.secondsSaved(), 1e-9);
		assertTrue(s.landingFeet().sub(new Vec3(40.5, 101, 0.5)).length() <= cfg.placing.balSpotRadius + 1e-9);
		assertNotNull(s.place());
		assertTrue(s.marginDeg() >= cfg.plan.minAimMarginDeg);
	}

	@Test
	void nothingWhenAWallHidesTheTopazArea() {
		// A tall wall at x 30 between the start and the fight
		SetupWorld w = world(b -> b[0] == 30 && b[1] <= 200);
		BalPlacement.Suggestion s = BalPlacement.suggest(w, twoWarps(), new Vec3(40.5, 101, 0.5), cfg);
		assertFalse(s.found());
	}

	@Test
	void nothingToSaveWithASingleWarp() {
		BalPlacement.Suggestion s = BalPlacement.suggest(world(b -> false), List.of(warp(new Vec3(0.5, 101, 0.5), 40, 100, 0)), new Vec3(40.5, 101, 0.5), cfg);
		assertFalse(s.found());
		assertTrue(s.reason().contains("one warp"));
	}

	@Test
	void respectsNoPlaceAreas() {
		SetupWorld noPlace = new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return y <= 100 ? VoxelClass.HARD_STONE : VoxelClass.AIR; }
			@Override public boolean inZone(int x, int y, int z) { return false; }
			@Override public boolean explored(int x, int z) { return true; }
			@Override public boolean placeAllowed(int x, int y, int z) { return false; }
		};
		assertFalse(BalPlacement.suggest(noPlace, twoWarps(), new Vec3(40.5, 101, 0.5), cfg).found());
	}
}
