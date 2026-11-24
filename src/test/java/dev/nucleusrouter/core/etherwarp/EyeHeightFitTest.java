package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EyeHeightFitTest {
	/**
	 * A wall of single blocks stacked at z = 20: block y=101 is "upper", y=100 "lower".
	 * A level ray hits whichever block the eye is in front of, so the landing tells us the eye height band.
	 */
	private static final SolidQuery WALL = (x, y, z) -> z == 20 || y <= 99;

	@Test
	void levelRayPinsTheEyeToOneBlockBand() {
		Vec3 feet = new Vec3(0.5, 100, 0.5);
		// Landed on block y=101 (eye between 101 and 102) => eye height 1.0..1.8 range clipped to [1.0, 1.8]
		var ranges = EyeHeightFit.matchingRanges(new EyeHeightFit.Observation(feet, new Vec3(0, 0, 1), 0, 101, 20), 61, WALL);
		assertEquals(1, ranges.size());
		assertEquals(1.0, ranges.get(0)[0], 1e-9);
		assertEquals(1.8, ranges.get(0)[1], 1e-9);
	}

	@Test
	void slopedRaysNarrowTheEstimate() {
		// Simulate the server using eye height 1.62. Each warp is a slightly downward look at the wall;
		// the block hit depends on the true eye height.
		double trueEye = 1.62;
		List<List<double[]>> all = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Vec3 feet = new Vec3(0.5, 100, 0.5 + i * 0.37);
			double pitch = 1.0 + i * 0.29;
			Vec3 dir = Vec3.fromYawPitch(0, pitch);
			VoxelRay.Hit real = VoxelRay.cast(Etherwarp.eye(feet, trueEye), dir, 61, WALL);
			assertNotNull(real);
			all.add(EyeHeightFit.matchingRanges(new EyeHeightFit.Observation(feet, dir, real.x(), real.y(), real.z()), 61, WALL));
		}
		EyeHeightFit.Suggestion s = EyeHeightFit.suggest(all);
		assertTrue(s.hasData());
		assertEquals(12, s.explained(), "the true height explains every warp");
		assertTrue(s.low() <= trueEye + 1e-9 && s.high() >= trueEye - 1e-9, "range " + s.low() + ".." + s.high() + " must contain " + trueEye);
		assertTrue(s.high() - s.low() < 0.2, "several warps should narrow it down, got " + s.low() + ".." + s.high());
	}

	@Test
	void noDataGivesNoSuggestion() {
		assertFalse(EyeHeightFit.suggest(List.of()).hasData());
		assertFalse(EyeHeightFit.suggest(List.of(List.of())).hasData());
	}
}
