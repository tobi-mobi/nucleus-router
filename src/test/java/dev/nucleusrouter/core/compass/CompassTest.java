package dev.nucleusrouter.core.compass;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CompassTest {
	private static final Vec3 TARGET = new Vec3(400, 60, 600);

	/** Particles from {@code eye} toward {@code target}, 0.5 apart for {@code length} blocks, with some jitter. */
	private static List<Vec3> trail(Vec3 eye, Vec3 target, double length, double jitter, Random rnd) {
		Vec3 d = target.sub(eye).normalize();
		List<Vec3> pts = new ArrayList<>();
		for (double t = 1; t <= length; t += 0.5) {
			Vec3 p = eye.add(d.scale(t));
			pts.add(new Vec3(p.x() + jitter * rnd.nextGaussian(), p.y() + jitter * rnd.nextGaussian(), p.z() + jitter * rnd.nextGaussian()));
		}
		return pts;
	}

	@Test
	void fitsATrailAndPointsAwayFromThePlayer() {
		Vec3 eye = new Vec3(500, 100.27, 500);
		CompassLine l = CompassLine.fit(trail(eye, TARGET, 8, 0.0, new Random(1)), eye, "minecraft:happy_villager", 2);
		assertNotNull(l);
		assertTrue(l.dir().dot(TARGET.sub(eye).normalize()) > 0.9999, "direction " + l.dir());
		assertEquals(0, l.distanceTo(TARGET), 1e-6);
		assertEquals(Math.toRadians(0.5), l.sigmaRad(), 1e-9, "a perfect trail gets the minimum error");
		assertNull(CompassLine.fit(List.of(eye, eye), eye, "x", 2), "too few points");
	}

	@Test
	void twoGoodUsesFindTheTarget() {
		Random rnd = new Random(7);
		Vec3 a = new Vec3(500, 100.27, 500), b = new Vec3(300, 90.27, 480);
		List<CompassLine> lines = List.of(
				CompassLine.fit(trail(a, TARGET, 8, 0.05, rnd), a, "p", 2),
				CompassLine.fit(trail(b, TARGET, 8, 0.05, rnd), b, "p", 2));
		Triangulation.Estimate e = Triangulation.solve(lines);
		assertTrue(e.ok(), e.message());
		assertTrue(e.point().sub(TARGET).length() < e.radius(), "error " + e.point().sub(TARGET).length() + " radius " + e.radius());
		assertTrue(e.radius() < 15, "radius " + e.radius());
		assertFalse(e.inconsistent());
	}

	@Test
	void moreUsesShrinkTheUncertainty() {
		Random rnd = new Random(3);
		Vec3[] spots = {new Vec3(500, 100, 500), new Vec3(300, 90, 480), new Vec3(420, 70, 750), new Vec3(250, 60, 650)};
		List<CompassLine> lines = new ArrayList<>();
		double prev = Double.POSITIVE_INFINITY;
		for (Vec3 s : spots) {
			lines.add(CompassLine.fit(trail(s, TARGET, 8, 0.1, rnd), s, "p", 2));
			Triangulation.Estimate e = Triangulation.solve(lines);
			if (lines.size() >= 2) {
				assertTrue(e.ok());
				assertTrue(e.radius() <= prev + 1e-9, "radius grew: " + e.radius() + " > " + prev);
				prev = e.radius();
			}
		}
		assertTrue(Triangulation.solve(lines).point().sub(TARGET).length() < 6);
	}

	@Test
	void parallelLinesAndSingleLinesGiveNoEstimate() {
		Vec3 a = new Vec3(500, 100, 500), b = new Vec3(500, 100, 510);
		CompassLine l1 = CompassLine.fit(trail(a, a.add(new Vec3(-100, 0, 0)), 8, 0, new Random(1)), a, "p", 2);
		CompassLine l2 = CompassLine.fit(trail(b, b.add(new Vec3(-100, 0, 0)), 8, 0, new Random(1)), b, "p", 2);
		assertFalse(Triangulation.solve(List.of(l1)).ok());
		assertFalse(Triangulation.solve(List.of(l1, l2)).ok());
	}

	@Test
	void linesToDifferentTargetsAreFlagged() {
		Random rnd = new Random(5);
		Vec3 a = new Vec3(500, 100, 500), b = new Vec3(300, 90, 480), c = new Vec3(420, 70, 750);
		List<CompassLine> lines = List.of(
				CompassLine.fit(trail(a, TARGET, 8, 0.02, rnd), a, "p", 2),
				CompassLine.fit(trail(b, TARGET, 8, 0.02, rnd), b, "p", 2),
				CompassLine.fit(trail(c, new Vec3(700, 60, 300), 8, 0.02, rnd), c, "p", 2));
		Triangulation.Estimate e = Triangulation.solve(lines);
		assertTrue(e.inconsistent(), "worst miss " + e.worstMiss());
		assertTrue(Triangulation.outliers(lines, e).size() >= 1);
	}

	@Test
	void picksTheTrailOutOfOtherParticles() {
		Random rnd = new Random(11);
		Vec3 eye = new Vec3(500, 100, 500);
		Map<String, List<Vec3>> byType = new LinkedHashMap<>();
		List<Vec3> cloud = new ArrayList<>();
		for (int i = 0; i < 40; i++) cloud.add(eye.add(new Vec3(rnd.nextGaussian() * 4, rnd.nextGaussian() * 4, rnd.nextGaussian() * 4)));
		byType.put("minecraft:smoke", cloud);
		byType.put("minecraft:happy_villager", trail(eye, TARGET, 8, 0.05, rnd));
		CompassLine l = CompassLine.pickBest(byType, eye, 5, 2, List.of());
		assertNotNull(l);
		assertEquals("minecraft:happy_villager", l.particle());
		assertNull(CompassLine.pickBest(byType, eye, 5, 2, List.of("minecraft:smoke")), "a cloud is not a trail");
	}

	@Test
	void suggestsASpotToTheSide() {
		Vec3 a = new Vec3(500, 100, 500);
		CompassLine l = CompassLine.fit(trail(a, TARGET, 8, 0, new Random(1)), a, "p", 2);
		Vec3 s = Triangulation.suggestNextSpot(List.of(l), null, new Vec3(512, 100, 512), 202, 823, 202, 823);
		Vec3 offset = s.sub(a);
		assertEquals(60, offset.length(), 1e-6);
		assertEquals(0, offset.dot(l.dir()), 1e-6 * 60 + Math.abs(l.dir().y()) * 60, "sideways");
	}
}
