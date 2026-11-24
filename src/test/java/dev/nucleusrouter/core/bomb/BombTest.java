package dev.nucleusrouter.core.bomb;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BombTest {
	@Test
	void throwStopsAtFirstBlockInRange() {
		SolidQuery wall = (x, y, z) -> z == 20;
		BombThrow.Prediction p = BombThrow.predict(new Vec3(0.5, 101.27, 0.5), new Vec3(0, 0, 1), 45, true, wall);
		assertTrue(p.lands());
		assertFalse(p.dropped());
		assertArrayEquals(new int[]{0, 101, 20}, p.impactBlock());
		assertEquals(new Vec3(0.5, 101.5, 20.5), p.blastCentre(NrConfig.BlastCentre.IMPACT_BLOCK_CENTRE));
		assertEquals(20.0, p.blastCentre(NrConfig.BlastCentre.IMPACT_POINT).z(), 1e-9);
	}

	@Test
	void throwDropsStraightDownAtMaxRange() {
		SolidQuery floor = (x, y, z) -> y <= 60;
		BombThrow.Prediction p = BombThrow.predict(new Vec3(0.5, 101.27, 0.5), new Vec3(0, 0, 1), 45, true, floor);
		assertTrue(p.dropped());
		assertArrayEquals(new int[]{0, 60, 45}, p.impactBlock());
		assertFalse(BombThrow.predict(new Vec3(0.5, 101.27, 0.5), new Vec3(0, 0, 1), 45, false, floor).lands());
	}

	/** A solid stone area with a few immune logs, blown up by a perfect radius-8 ball. */
	@Test
	void recoversCentreRadiusAndShapeOfABall() {
		Vec3 trueCentre = new Vec3(500.5, 100.5, 500.5);
		BlastAnalysis.Before before = (x, y, z) -> (x == 503 && z == 500) ? BlastAnalysis.Status.IMMUNE : BlastAnalysis.Status.BREAKABLE;
		List<int[]> removed = new ArrayList<>();
		for (int x = 490; x <= 511; x++)
			for (int y = 90; y <= 111; y++)
				for (int z = 490; z <= 511; z++) {
					double d = Math.sqrt(Math.pow(x + 0.5 - trueCentre.x(), 2) + Math.pow(y + 0.5 - trueCentre.y(), 2) + Math.pow(z + 0.5 - trueCentre.z(), 2));
					if (d <= 8 && before.at(x, y, z) == BlastAnalysis.Status.BREAKABLE) removed.add(new int[]{x, y, z});
				}
		BlastAnalysis.Result r = BlastAnalysis.analyze(removed, before, new Vec3(501.5, 100.5, 500.5), 8);
		assertEquals(BlastAnalysis.Metric.EUCLIDEAN, r.best().metric());
		assertTrue(r.best().agreement() > 0.99, "agreement " + r.best().agreement());
		assertEquals(8.0, r.best().radius(), 0.05);
		assertTrue(r.best().centre().sub(trueCentre).length() < 0.3, "centre " + r.best().centre());
		assertTrue(r.survivorsInside().isEmpty(), "immune logs are not survivors");
		assertEquals(1.0, r.modelCentreError(), 0.3, "model was 1 block off in x");
		assertTrue(r.modelCheck().agreement() < r.best().agreement());
	}

	@Test
	void recognisesACubeAndFlagsSurvivors() {
		BlastAnalysis.Before before = (x, y, z) -> BlastAnalysis.Status.BREAKABLE;
		List<int[]> removed = new ArrayList<>();
		for (int x = -4; x <= 4; x++) for (int y = 96; y <= 104; y++) for (int z = -4; z <= 4; z++)
			if (!(x == 0 && y == 100 && z == 0)) removed.add(new int[]{x, y, z}); // one block in the middle survived
		BlastAnalysis.Result r = BlastAnalysis.analyze(removed, before, null, 8);
		assertEquals(BlastAnalysis.Metric.CHEBYSHEV, r.best().metric());
		assertEquals(1, r.survivorsInside().size());
		assertArrayEquals(new int[]{0, 100, 0}, r.survivorsInside().get(0));
		assertNull(r.modelCheck());
	}
}
