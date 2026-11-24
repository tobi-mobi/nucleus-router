package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.core.world.WorldModel;
import dev.nucleusrouter.core.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtherwarpTest {
	private static final Etherwarp.Params P = new Etherwarp.Params(61, 1.54, 2);

	/** Tiny test world: a set of solid blocks. */
	private static final class Blocks implements SolidQuery {
		final Set<Long> solid = new HashSet<>();
		Blocks add(int x, int y, int z) { solid.add(key(x, y, z)); return this; }
		Blocks wall(int x0, int x1, int y0, int y1, int z) {
			for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) add(x, y, z);
			return this;
		}
		Blocks floor(int x0, int x1, int y, int z0, int z1) {
			for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) add(x, y, z);
			return this;
		}
		static long key(int x, int y, int z) { return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF); }
		@Override public boolean solid(int x, int y, int z) { return solid.contains(key(x, y, z)); }
	}

	@Test
	void directionsFollowMinecraftConventions() {
		assertVec(new Vec3(0, 0, 1), Vec3.fromYawPitch(0, 0));    // south
		assertVec(new Vec3(-1, 0, 0), Vec3.fromYawPitch(90, 0));  // west
		assertVec(new Vec3(0, 0, -1), Vec3.fromYawPitch(180, 0)); // north
		assertVec(new Vec3(0, -1, 0), Vec3.fromYawPitch(0, 90));  // down
		Vec3 d = Vec3.fromYawPitch(37, -12);
		assertEquals(37, d.yawDeg(), 1e-9);
		assertEquals(-12, d.pitchDeg(), 1e-9);
	}

	@Test
	void rayHitsFirstSolidBlockAndReportsFace() {
		Blocks w = new Blocks().wall(-5, 5, 95, 110, 20);
		VoxelRay.Hit h = VoxelRay.cast(new Vec3(0.5, 101.54, 0.5), new Vec3(0, 0, 1), 61, w);
		assertNotNull(h);
		assertTrue(h.isBlock(0, 101, 20));
		assertEquals(Face.NORTH, h.face());
		assertEquals(19.5, h.distance(), 1e-9);
	}

	@Test
	void rayRespectsRange() {
		Blocks w = new Blocks().add(0, 100, 61);
		Vec3 eye = new Vec3(0.5, 100.5, 0.5);
		assertNotNull(VoxelRay.cast(eye, new Vec3(0, 0, 1), 61, w), "enters at 60.5, in range");
		assertNull(VoxelRay.cast(eye, new Vec3(0, 0, 1), 60, w), "enters at 60.5, out of range 60");
		assertNull(VoxelRay.cast(eye, new Vec3(0, 1, 0), 61, w), "nothing up there");
	}

	@Test
	void rayNeverSkipsBlocksOnDiagonals() {
		// A thin diagonal staircase: a sampled ray could slip between these, DDA can't.
		Blocks w = new Blocks();
		for (int i = 0; i < 30; i++) w.add(10 + i, 100, 10 + i);
		Vec3 eye = new Vec3(0.2, 100.5, 0.9);
		Vec3 dir = new Vec3(1, 0, 1.0001);
		VoxelRay.Hit h = VoxelRay.cast(eye, dir, 61, w);
		assertNotNull(h);
		// Every block between the eye and the hit must be air
		for (double t = 0; t < h.distance() - 1e-6; t += 0.01) {
			Vec3 p = eye.add(dir.normalize().scale(t));
			assertFalse(w.solid((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z())), "ray passed through a solid block at t=" + t);
		}
	}

	@Test
	void negativeCoordinates() {
		Blocks w = new Blocks().add(-7, -3, -12);
		VoxelRay.Hit h = VoxelRay.cast(new Vec3(-6.5, -2.5, -0.5), new Vec3(0, 0, -1), 61, w);
		assertNotNull(h);
		assertTrue(h.isBlock(-7, -3, -12));
		assertEquals(Face.SOUTH, h.face());
		assertEquals(10.5, h.distance(), 1e-9);
	}

	@Test
	void landsCentredOnTopWithTwoAirAbove() {
		Blocks w = new Blocks().floor(-10, 10, 99, -10, 30).add(0, 100, 15);
		// Feet at y=100 standing on the floor, look slightly down at the block at z=15
		Vec3 feet = new Vec3(0.5, 100, 0.5);
		Vec3 eye = Etherwarp.eye(feet, P.eyeHeight());
		Vec3 aim = new Vec3(0.5, 101, 15.5).sub(eye); // top-centre of the target
		Etherwarp.Result r = Etherwarp.warp(eye, aim, P, w);
		assertTrue(r.valid(), r.toString());
		assertTrue(r.hit().isBlock(0, 100, 15));
		assertVec(new Vec3(0.5, 101, 15.5), r.landingFeet());
	}

	@Test
	void blockedAboveIsNotAWarp() {
		Blocks w = new Blocks().add(0, 100, 15).add(0, 102, 15); // block two above the target
		Vec3 eye = new Vec3(0.5, 101.54, 0.5);
		Etherwarp.Result r = Etherwarp.warp(eye, new Vec3(0.5, 100.5, 15).sub(eye), P, w);
		assertEquals(Etherwarp.Outcome.NOT_ENOUGH_AIR_ABOVE, r.outcome());
		assertNull(r.landingFeet());
		assertEquals(Etherwarp.Outcome.NO_TARGET_IN_RANGE, Etherwarp.warp(eye, new Vec3(0, 1, 0), P, w).outcome());
	}

	@Test
	void eyeHeightChangesTheTarget() {
		// A ledge exactly at eye level: with eye 1.54 the ray (level) hits it, with eye 1.62 it passes over
		Blocks w = new Blocks().add(0, 101, 10).add(0, 100, 20);
		Vec3 feet = new Vec3(0.5, 100, 0.5);
		Vec3 level = new Vec3(0, 0, 1);
		Etherwarp.Result low = Etherwarp.warp(Etherwarp.eye(feet, 1.54), level, P, w);
		Etherwarp.Result high = Etherwarp.warp(Etherwarp.eye(feet, 2.05), level, P, w);
		assertTrue(low.hit().isBlock(0, 101, 10));
		assertNull(high.hit(), "eye at y=102.05 passes over both blocks");
	}

	@Test
	void plannedRayTreatsMinedGemstonesAsSolidButCurrentRayDoesNot() {
		WorldModel m = new WorldModel(BlockClassifier.fromConfig(new NrConfig()));
		int air = m.blockId("minecraft:air"), gem = m.blockId("minecraft:purple_stained_glass_pane"),
				stone = m.blockId("minecraft:stone"), water = m.blockId("minecraft:water");
		for (int z = 0; z <= 30; z++) m.observe(300, 100, z + 300, air);
		m.observe(300, 100, 310, gem);
		m.observe(300, 100, 310, air);     // mined, will respawn
		m.observe(300, 100, 305, water);   // invisible to etherwarp
		m.observe(300, 100, 320, stone);
		WorldSnapshot s = m.snapshot();
		Vec3 eye = new Vec3(300.5, 100.5, 300.5);
		VoxelRay.Hit planned = VoxelRay.cast(eye, new Vec3(0, 0, 1), 61, s::plannedSolid);
		VoxelRay.Hit now = VoxelRay.cast(eye, new Vec3(0, 0, 1), 61, s::currentlySolid);
		assertTrue(planned.isBlock(300, 100, 310), "planning never relies on a mined gemstone staying gone");
		assertTrue(now.isBlock(300, 100, 320), "right now the ray flies through the hole and the water");
	}

	/** In-game 2026-11-24: warping onto a wall block with a torch on it lands on the wall block. */
	@Test
	void torchesDoNotExistForEtherwarp() {
		WorldModel m = new WorldModel(BlockClassifier.fromConfig(new NrConfig()));
		int air = m.blockId("minecraft:air"), wall = m.blockId("minecraft:cobblestone_wall"), torch = m.blockId("minecraft:torch");
		for (int z = 300; z <= 330; z++) for (int y = 100; y <= 104; y++) m.observe(300, y, z, air);
		m.observe(300, 100, 310, wall);
		m.observe(300, 101, 310, torch); // torch on top of the wall
		m.observe(300, 102, 305, m.blockId("minecraft:wall_torch")); // a torch in the way of the ray
		WorldSnapshot s = m.snapshot();
		Vec3 eye = new Vec3(300.5, 102.5, 300.5);
		Etherwarp.Result r = Etherwarp.warp(eye, new Vec3(300.5, 100.9, 310.5).sub(eye), P, s::plannedSolid);
		assertTrue(r.valid(), r.toString());
		assertTrue(r.hit().isBlock(300, 100, 310));
	}

	@Test
	void aimRegionIsBigForAnOpenTargetAndShrinksWhenCovered() {
		Blocks open = new Blocks().add(0, 100, 10);
		Vec3 eye = new Vec3(0.5, 101.54, 0.5);
		AimRegion.Result r = AimRegion.compute(eye, 0, 100, 10, P, open, 8);
		assertTrue(r.reachable());
		assertTrue(r.coneDegrees() > 4, "a block 10 away is several degrees wide, got " + r.coneDegrees());
		assertTrue(r.bestMarginDegrees() > 0.5, "should have real margin, got " + r.bestMarginDegrees());
		// Best aim point is on the target
		assertTrue(r.bestPoint().x() >= 0 && r.bestPoint().x() <= 1 && r.bestPoint().z() >= 10 && r.bestPoint().z() <= 11);

		// Same target further away: smaller region
		AimRegion.Result far = AimRegion.compute(eye, 0, 100, 40, P, new Blocks().add(0, 100, 40), 8);
		assertTrue(far.solidAngleSr() < r.solidAngleSr());

		// Cover the left half of the view with a pillar in front: region shrinks but is still reachable
		Blocks covered = new Blocks().add(0, 100, 10).add(-1, 100, 9).add(-1, 101, 9);
		AimRegion.Result c = AimRegion.compute(eye, 0, 100, 10, P, covered, 8);
		assertTrue(c.solidAngleSr() <= r.solidAngleSr());

		// Fully hidden behind a wall: unreachable
		Blocks hidden = new Blocks().add(0, 100, 10).wall(-3, 3, 98, 104, 5);
		assertFalse(AimRegion.compute(eye, 0, 100, 10, P, hidden, 8).reachable());

		// No room above: not landable
		Blocks roofed = new Blocks().add(0, 100, 10).add(0, 101, 10);
		AimRegion.Result roof = AimRegion.compute(eye, 0, 100, 10, P, roofed, 8);
		assertFalse(roof.landable());
		assertFalse(roof.reachable());
	}

	@Test
	void bestAimPointActuallyLandsOnTheTarget() {
		Blocks w = new Blocks().floor(-5, 5, 99, 0, 30).add(2, 100, 12).add(1, 100, 12);
		Vec3 eye = new Vec3(0.5, 101.54, 0.5);
		AimRegion.Result r = AimRegion.compute(eye, 2, 100, 12, P, w, 10);
		assertTrue(r.reachable());
		Etherwarp.Result warp = Etherwarp.warp(eye, r.bestDirection(), P, w);
		assertTrue(warp.valid());
		assertTrue(warp.hit().isBlock(2, 100, 12));
	}

	private static void assertVec(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x(), actual.x(), 1e-9, "x");
		assertEquals(expected.y(), actual.y(), 1e-9, "y");
		assertEquals(expected.z(), actual.z(), 1e-9, "z");
	}
}
