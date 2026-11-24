package dev.nucleusrouter.core.compass;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A straight line fitted through the particles of one Wishing Compass use (SPEC §4).
 *
 * @param origin    centre of the particles (a point on the line)
 * @param dir       unit direction, pointing away from where you used the compass
 * @param from      where you stood (eye) when using it
 * @param rms       how far particles sit from the line on average (blocks)
 * @param extent    length of the particle trail along the line (blocks)
 * @param points    number of particles used
 * @param sigmaRad  estimated direction error (radians, one standard deviation)
 * @param particle  particle type the line was made of
 */
public record CompassLine(Vec3 origin, Vec3 dir, Vec3 from, double rms, double extent, int points, double sigmaRad, String particle) {
	/** Direction error never assumed smaller than this (half a degree). */
	public static final double MIN_SIGMA_RAD = Math.toRadians(0.5);

	/** Distance from {@code p} to this line. */
	public double distanceTo(Vec3 p) {
		Vec3 d = p.sub(origin);
		double t = d.dot(dir);
		return d.sub(dir.scale(t)).length();
	}

	/** Position of {@code p} along the line, measured from {@link #origin}. */
	public double along(Vec3 p) {
		return p.sub(origin).dot(dir);
	}

	/**
	 * Fits a line through {@code pts} (principal axis). Returns null for fewer than 3 points or a trail
	 * shorter than {@code minExtent}. The direction is chosen so it points away from {@code eye}.
	 */
	public static CompassLine fit(List<Vec3> pts, Vec3 eye, String particle, double minExtent) {
		int n = pts.size();
		if (n < 3) return null;
		double cx = 0, cy = 0, cz = 0;
		for (Vec3 p : pts) { cx += p.x(); cy += p.y(); cz += p.z(); }
		Vec3 c = new Vec3(cx / n, cy / n, cz / n);
		double xx = 0, xy = 0, xz = 0, yy = 0, yz = 0, zz = 0;
		for (Vec3 p : pts) {
			double dx = p.x() - c.x(), dy = p.y() - c.y(), dz = p.z() - c.z();
			xx += dx * dx; xy += dx * dy; xz += dx * dz; yy += dy * dy; yz += dy * dz; zz += dz * dz;
		}
		double[][] m = {{xx, xy, xz}, {xy, yy, yz}, {xz, yz, zz}};
		Vec3 start = c.sub(eye);
		if (start.length() < 1e-6) start = new Vec3(1, 0.3, 0.2);
		Vec3 d = Mat3.largestEigenvector(m, start.normalize());
		if (d == null) return null;

		double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE, sq = 0;
		for (Vec3 p : pts) {
			Vec3 r = p.sub(c);
			double t = r.dot(d);
			tMin = Math.min(tMin, t);
			tMax = Math.max(tMax, t);
			double perp2 = r.dot(r) - t * t;
			sq += Math.max(0, perp2);
		}
		double extent = tMax - tMin;
		if (extent < minExtent) return null;
		// Point the line away from where the compass was used
		double toMax = c.add(d.scale(tMax)).sub(eye).length(), toMin = c.add(d.scale(tMin)).sub(eye).length();
		if (toMin > toMax) d = d.scale(-1);
		double rms = Math.sqrt(sq / n);
		// Slope error of a least-squares line through n evenly spread points: sigma * sqrt(12 / n) / extent
		double sigma = Math.max(MIN_SIGMA_RAD, Math.atan(Math.max(rms, 0.05) * Math.sqrt(12.0 / n) / extent));
		return new CompassLine(c, d, eye, rms, extent, n, sigma, particle);
	}

	/**
	 * Picks the compass trail out of everything captured after a use: for each particle type (only
	 * {@code allowed} ones if that list isn't empty) with at least {@code minPoints} particles, fits a line,
	 * and keeps the straightest one (smallest direction error). Null if no type forms a line.
	 */
	public static CompassLine pickBest(Map<String, List<Vec3>> byType, Vec3 eye, int minPoints, double minExtent, Collection<String> allowed) {
		CompassLine best = null;
		for (Map.Entry<String, List<Vec3>> e : byType.entrySet()) {
			if (allowed != null && !allowed.isEmpty() && !allowed.contains(e.getKey())) continue;
			if (e.getValue().size() < minPoints) continue;
			CompassLine l = fit(e.getValue(), eye, e.getKey(), minExtent);
			if (l == null || l.rms() > 1.5) continue; // a cloud, not a trail
			if (best == null || l.sigmaRad() < best.sigmaRad()) best = l;
		}
		return best;
	}
}
