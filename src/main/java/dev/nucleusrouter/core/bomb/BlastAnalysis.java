package dev.nucleusrouter.core.bomb;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures a real Bob-omb blast from the blocks it removed (SPEC §6, /nr calibrate bomb), to settle
 * the [VERIFY] items "blast centre" and "distance metric / radius".
 *
 * Inputs: the removed block positions, and what the area looked like just before the throw
 * ({@link Before}). Output: measured centre and radius, which shape fits best (ball, cube or diamond),
 * breakable blocks that survived inside the blast (a hint the shape or the bomb-immune list is off),
 * and how well the current model's prediction matched.
 */
public final class BlastAnalysis {
	/** What a position was just before the throw. */
	public enum Status { EMPTY, BREAKABLE, IMMUNE, UNKNOWN }

	@FunctionalInterface
	public interface Before {
		Status at(int x, int y, int z);
	}

	public enum Metric {
		/** Ball: sqrt(dx^2 + dy^2 + dz^2). */
		EUCLIDEAN,
		/** Cube: max(|dx|, |dy|, |dz|). */
		CHEBYSHEV,
		/** Diamond: |dx| + |dy| + |dz|. */
		MANHATTAN;

		double dist(Vec3 c, int x, int y, int z) {
			double dx = Math.abs(x + 0.5 - c.x()), dy = Math.abs(y + 0.5 - c.y()), dz = Math.abs(z + 0.5 - c.z());
			return switch (this) {
				case EUCLIDEAN -> Math.sqrt(dx * dx + dy * dy + dz * dz);
				case CHEBYSHEV -> Math.max(dx, Math.max(dy, dz));
				case MANHATTAN -> dx + dy + dz;
			};
		}
	}

	/** How well "everything breakable within radius of centre" matches what was really removed (1.0 = perfect). */
	public record ShapeFit(Metric metric, String centreSource, Vec3 centre, double radius, double agreement,
			int removedAndPredicted, int predictedButSurvived, int removedButNotPredicted) {}

	public record Result(int removedCount, Vec3 centroid, int[] boxMin, int[] boxMax, Vec3 enclosingCentre,
			double enclosingRadius, ShapeFit best, List<ShapeFit> allFits, ShapeFit modelCheck,
			double modelCentreError, List<int[]> survivorsInside) {}

	private BlastAnalysis() {}

	/**
	 * @param removed         block positions that turned to air in the blast
	 * @param before          the area just before the throw
	 * @param modelCentre     where the model put the blast centre (may be null)
	 * @param modelRadius     the model's radius
	 */
	public static Result analyze(List<int[]> removed, Before before, Vec3 modelCentre, double modelRadius) {
		if (removed.isEmpty()) throw new IllegalArgumentException("no removed blocks to analyse");
		Set<Long> removedSet = new HashSet<>();
		int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
		int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		double sx = 0, sy = 0, sz = 0;
		for (int[] p : removed) {
			removedSet.add(key(p[0], p[1], p[2]));
			for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], p[a]); max[a] = Math.max(max[a], p[a]); }
			sx += p[0] + 0.5; sy += p[1] + 0.5; sz += p[2] + 0.5;
		}
		int n = removed.size();
		Vec3 centroid = new Vec3(sx / n, sy / n, sz / n);
		Vec3 boxMid = new Vec3((min[0] + max[0] + 1) / 2.0, (min[1] + max[1] + 1) / 2.0, (min[2] + max[2] + 1) / 2.0);
		Vec3 enclosing = minEnclosingCentre(removed);
		double enclosingR = 0;
		for (int[] p : removed) enclosingR = Math.max(enclosingR, Metric.EUCLIDEAN.dist(enclosing, p[0], p[1], p[2]));

		List<ShapeFit> fits = new ArrayList<>();
		List<Vec3> centres = new ArrayList<>(List.of(enclosing, boxMid));
		List<String> names = new ArrayList<>(List.of("smallest enclosing ball", "middle of bounding box"));
		if (modelCentre != null) { centres.add(modelCentre); names.add("model's predicted centre"); }
		for (Metric m : Metric.values()) {
			for (int i = 0; i < centres.size(); i++) {
				Vec3 c = centres.get(i);
				double r = 0;
				for (int[] p : removed) r = Math.max(r, m.dist(c, p[0], p[1], p[2]));
				fits.add(fit(m, names.get(i), c, r, removedSet, removed, before));
			}
		}
		ShapeFit best = fits.get(0);
		for (ShapeFit f : fits) if (f.agreement() > best.agreement() + 1e-9) best = f;

		ShapeFit model = modelCentre == null ? null : fit(Metric.EUCLIDEAN, "current model", modelCentre, modelRadius, removedSet, removed, before);
		double modelErr = modelCentre == null ? Double.NaN : modelCentre.sub(best.centre()).length();

		List<int[]> survivors = new ArrayList<>();
		forEachInside(best.metric(), best.centre(), best.radius(), (x, y, z) -> {
			if (!removedSet.contains(key(x, y, z)) && before.at(x, y, z) == Status.BREAKABLE) survivors.add(new int[]{x, y, z});
		});
		return new Result(n, centroid, min, max, enclosing, enclosingR, best, fits, model, modelErr, survivors);
	}

	private static ShapeFit fit(Metric m, String source, Vec3 c, double r, Set<Long> removedSet, List<int[]> removed, Before before) {
		int[] both = {0}, onlyPredicted = {0};
		forEachInside(m, c, r, (x, y, z) -> {
			if (removedSet.contains(key(x, y, z))) both[0]++;
			else if (before.at(x, y, z) == Status.BREAKABLE) onlyPredicted[0]++;
		});
		int onlyRemoved = removed.size() - both[0];
		int union = both[0] + onlyPredicted[0] + onlyRemoved;
		double agreement = union == 0 ? 0 : (double) both[0] / union;
		return new ShapeFit(m, source, c, r, agreement, both[0], onlyPredicted[0], onlyRemoved);
	}

	private interface Cell { void at(int x, int y, int z); }

	private static void forEachInside(Metric m, Vec3 c, double r, Cell cell) {
		int r0 = (int) Math.ceil(r) + 1;
		int cx = (int) Math.floor(c.x()), cy = (int) Math.floor(c.y()), cz = (int) Math.floor(c.z());
		for (int x = cx - r0; x <= cx + r0; x++)
			for (int y = cy - r0; y <= cy + r0; y++)
				for (int z = cz - r0; z <= cz + r0; z++)
					if (m.dist(c, x, y, z) <= r + 1e-9) cell.at(x, y, z);
	}

	/** Approximate centre of the smallest ball containing all block centres (Badoiu-Clarkson iteration). */
	static Vec3 minEnclosingCentre(List<int[]> pts) {
		int[] p0 = pts.get(0);
		Vec3 c = new Vec3(p0[0] + 0.5, p0[1] + 0.5, p0[2] + 0.5);
		for (int i = 1; i <= 2000; i++) {
			int[] far = pts.get(0);
			double best = -1;
			for (int[] p : pts) {
				double d = Metric.EUCLIDEAN.dist(c, p[0], p[1], p[2]);
				if (d > best) { best = d; far = p; }
			}
			Vec3 f = new Vec3(far[0] + 0.5, far[1] + 0.5, far[2] + 0.5);
			c = c.add(f.sub(c).scale(1.0 / (i + 1)));
		}
		return c;
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x1FFFFF) << 42 | ((long) y & 0x1FFFFF) << 21 | ((long) z & 0x1FFFFF);
	}
}
