package dev.nucleusrouter.core.compass;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Where several Wishing Compass lines meet (SPEC §4): the point with the smallest weighted sum of squared
 * distances to all lines. Each line is weighted by how sure its direction is at that distance, so a
 * sloppy line or a far-away target counts less. The uncertainty radius covers about 95% of where the
 * target can be, given the lines' direction errors.
 */
public final class Triangulation {
	/** Position errors are never assumed below this (particles sit on block-ish spots). */
	static final double MIN_POSITION_SIGMA = 0.5;

	/**
	 * @param point        best estimate, or null if the lines don't cross (fewer than 2, or parallel)
	 * @param radius       about 95% radius around {@code point}, in blocks
	 * @param worstMiss    largest distance from {@code point} to a line, in that line's standard deviations
	 * @param behind       true if the point lies behind where some compass pointed (lines disagree)
	 * @param message      short reason when {@code point} is null
	 */
	public record Estimate(Vec3 point, double radius, double worstMiss, boolean behind, String message) {
		public boolean ok() { return point != null; }
		/** The lines don't agree on one target (probably pointing at different structures). */
		public boolean inconsistent() { return ok() && (behind || worstMiss > 3.0); }
	}

	private Triangulation() {}

	public static Estimate solve(List<CompassLine> lines) {
		if (lines.size() < 2) return new Estimate(null, Double.POSITIVE_INFINITY, 0, false, "need at least 2 compass uses");
		Vec3 x = solveWeighted(lines, null);
		if (x == null) return new Estimate(null, Double.POSITIVE_INFINITY, 0, false, "the lines are (nearly) parallel; use a compass further to the side");
		double[][] cov = null;
		// Re-weight twice with the distance along each line, which sets how wide its error cone is there
		for (int it = 0; it < 3; it++) {
			double[] sigmas = sigmas(lines, x);
			Vec3 nx = solveWeighted(lines, sigmas);
			if (nx == null) break;
			x = nx;
			cov = Mat3.inverse(normalMatrix(lines, sigmas(lines, x)));
		}
		if (cov == null) return new Estimate(null, Double.POSITIVE_INFINITY, 0, false, "the lines are (nearly) parallel; use a compass further to the side");
		double radius = 2.0 * Math.sqrt(Math.max(0, Mat3.largestEigenvalue(cov)));
		double[] s = sigmas(lines, x);
		double worst = 0;
		boolean behind = false;
		for (int i = 0; i < lines.size(); i++) {
			CompassLine l = lines.get(i);
			worst = Math.max(worst, l.distanceTo(x) / s[i]);
			if (x.sub(l.from()).dot(l.dir()) < 0) behind = true;
		}
		return new Estimate(x, radius, worst, behind, null);
	}

	private static double[] sigmas(List<CompassLine> lines, Vec3 x) {
		double[] s = new double[lines.size()];
		for (int i = 0; i < s.length; i++) {
			CompassLine l = lines.get(i);
			double dist = Math.max(5, x == null ? 50 : x.sub(l.from()).length());
			s[i] = Math.max(MIN_POSITION_SIGMA, Math.tan(l.sigmaRad()) * dist);
		}
		return s;
	}

	/** Sum over lines of w (I - d d^T). */
	private static double[][] normalMatrix(List<CompassLine> lines, double[] sigmas) {
		double[][] a = new double[3][3];
		for (int i = 0; i < lines.size(); i++) {
			double w = sigmas == null ? 1 : 1 / (sigmas[i] * sigmas[i]);
			double[] d = {lines.get(i).dir().x(), lines.get(i).dir().y(), lines.get(i).dir().z()};
			for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) a[r][c] += w * ((r == c ? 1 : 0) - d[r] * d[c]);
		}
		return a;
	}

	private static Vec3 solveWeighted(List<CompassLine> lines, double[] sigmas) {
		double[][] a = normalMatrix(lines, sigmas);
		double bx = 0, by = 0, bz = 0;
		for (int i = 0; i < lines.size(); i++) {
			CompassLine l = lines.get(i);
			double w = sigmas == null ? 1 : 1 / (sigmas[i] * sigmas[i]);
			Vec3 d = l.dir(), o = l.origin();
			double t = o.dot(d);
			// (I - d d^T) o = o - d (d.o)
			bx += w * (o.x() - d.x() * t);
			by += w * (o.y() - d.y() * t);
			bz += w * (o.z() - d.z() * t);
		}
		// Parallel check on the unweighted geometry: smallest "spread" of the directions
		double[][] inv = Mat3.inverse(a);
		if (inv == null || !wellConditioned(lines)) return null;
		return Mat3.mul(inv, new Vec3(bx, by, bz));
	}

	/** False if every line points (within ~2 degrees) the same way. */
	private static boolean wellConditioned(List<CompassLine> lines) {
		Vec3 d0 = lines.get(0).dir();
		for (CompassLine l : lines) {
			double cross = Math.sqrt(Math.max(0, 1 - Math.pow(l.dir().dot(d0), 2)));
			if (cross > Math.sin(Math.toRadians(2))) return true;
		}
		return false;
	}

	/**
	 * Where to use the next compass for a better fix: sideways from the last line (at right angles to
	 * it, in the horizontal plane): 60 blocks with one line, otherwise 0.7 x the distance to the estimate,
	 * between 30 and 100 blocks. Picks the side closer to {@code centre} (the middle of the Hollows), then clamps
	 * into the given bounds.
	 */
	public static Vec3 suggestNextSpot(List<CompassLine> lines, Estimate est, Vec3 centre,
			double minX, double maxX, double minZ, double maxZ) {
		if (lines.isEmpty()) return null;
		CompassLine last = lines.get(lines.size() - 1);
		Vec3 side = new Vec3(-last.dir().z(), 0, last.dir().x());
		if (side.length() < 1e-6) side = new Vec3(1, 0, 0);
		side = side.normalize();
		double offset = est != null && est.ok() ? Math.max(30, Math.min(100, est.point().sub(last.from()).length() * 0.7)) : 60;
		Vec3 a = last.from().add(side.scale(offset)), b = last.from().sub(side.scale(offset));
		Vec3 pick = horizontalDist(a, centre) <= horizontalDist(b, centre) ? a : b;
		return new Vec3(clamp(pick.x(), minX, maxX), last.from().y(), clamp(pick.z(), minZ, maxZ));
	}

	private static double horizontalDist(Vec3 a, Vec3 b) {
		double dx = a.x() - b.x(), dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

	/** Lines that disagree with {@code est} by more than 3 standard deviations (for messages). */
	public static List<Integer> outliers(List<CompassLine> lines, Estimate est) {
		List<Integer> out = new ArrayList<>();
		if (!est.ok()) return out;
		double[] s = sigmas(lines, est.point());
		for (int i = 0; i < lines.size(); i++) if (lines.get(i).distanceTo(est.point()) / s[i] > 3.0) out.add(i);
		return out;
	}
}
