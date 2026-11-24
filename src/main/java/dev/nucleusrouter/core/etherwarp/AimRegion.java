package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The valid aim region for warping onto one target block from one eye position (SPEC §2.3,
 * "robust aim"): the set of look directions that land exactly on that block.
 *
 * Method: sample a grid of points on every face of the target that faces the eye, plus a ring
 * of points just outside each face, and cast a real ray at each. A sample is valid when its ray's
 * first hit is the target within range. From the valid samples we get:
 * <ul>
 *   <li>the region's angular size (solid angle of the valid face cells, and the equivalent cone width), and</li>
 *   <li>the best aim point: the valid direction furthest (in angle) from any invalid one, i.e. the most
 *       forgiving place to aim. Its margin is how far your aim can drift before the warp goes wrong.</li>
 * </ul>
 * Margins are only as fine as the sample grid (about 1/n of the face as seen from the eye).
 */
public final class AimRegion {
	/**
	 * @param landable          the target has room above (if false, no aim on it can work)
	 * @param samples           rays cast
	 * @param validSamples      rays that landed on the target
	 * @param solidAngleSr      angular area of the valid part of the visible faces, in steradians
	 * @param coneDegrees       full width of a circular cone with the same angular area (easy to picture)
	 * @param bestPoint         the most forgiving point to aim at (null if none valid)
	 * @param bestDirection     direction from the eye to {@code bestPoint}
	 * @param bestMarginDegrees how far aim can drift from {@code bestDirection} before leaving the region
	 */
	public record Result(boolean landable, int samples, int validSamples, double solidAngleSr, double coneDegrees,
			Vec3 bestPoint, Vec3 bestDirection, double bestMarginDegrees) {
		public boolean reachable() { return landable && validSamples > 0; }
	}

	private AimRegion() {}

	private record Sample(Vec3 point, Vec3 dir, boolean valid, boolean onFace, double cellSolidAngle) {}

	public static Result compute(Vec3 eye, int tx, int ty, int tz, Etherwarp.Params p, SolidQuery solid, int gridPerFace) {
		int n = Math.max(2, gridPerFace);
		boolean landable = Etherwarp.hasRoomAbove(tx, ty, tz, p.requiredAirAbove(), solid);
		List<Sample> samples = new ArrayList<>();

		for (Face f : Face.values()) {
			if (!faceVisible(f, eye, tx, ty, tz)) continue;
			// Face plane coordinate and two in-plane axes
			for (int i = -1; i <= n; i++) {
				for (int j = -1; j <= n; j++) {
					double u = (i + 0.5) / n, v = (j + 0.5) / n;
					boolean onFace = i >= 0 && i < n && j >= 0 && j < n;
					Vec3 point = facePoint(f, tx, ty, tz, u, v);
					Vec3 toPoint = point.sub(eye);
					double dist = toPoint.length();
					if (dist < 1e-9) continue;
					Vec3 dir = toPoint.scale(1 / dist);
					VoxelRay.Hit hit = VoxelRay.cast(eye, dir, p.range(), solid);
					boolean valid = hit != null && hit.isBlock(tx, ty, tz);
					double cosTheta = Math.abs(dir.dot(f.normal()));
					double cellSa = onFace ? (1.0 / (n * n)) * cosTheta / (dist * dist) : 0;
					samples.add(new Sample(point, dir, valid, onFace, cellSa));
				}
			}
		}

		int validCount = 0;
		double solidAngle = 0;
		for (Sample s : samples) {
			if (s.valid) validCount++;
			if (s.valid && s.onFace) solidAngle += s.cellSolidAngle;
		}

		Sample best = null;
		double bestMargin = -1;
		for (Sample s : samples) {
			if (!s.valid || !s.onFace) continue;
			double margin = Double.POSITIVE_INFINITY;
			for (Sample o : samples) {
				if (o.valid) continue;
				double a = s.dir.angleDeg(o.dir);
				if (a < margin) margin = a;
			}
			if (margin > bestMargin) { bestMargin = margin; best = s; }
		}

		// Cone with the same solid angle: omega = 2*pi*(1 - cos(halfAngle))
		double cos = 1 - solidAngle / (2 * Math.PI);
		double cone = 2 * Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
		return new Result(landable, samples.size(), validCount, solidAngle, cone,
				best != null ? best.point : null, best != null ? best.dir : null, best != null ? Math.min(bestMargin, 180) : 0);
	}

	/** A face is visible when the eye is strictly on its outer side. */
	static boolean faceVisible(Face f, Vec3 eye, int x, int y, int z) {
		return switch (f) {
			case WEST -> eye.x() < x;
			case EAST -> eye.x() > x + 1;
			case DOWN -> eye.y() < y;
			case UP -> eye.y() > y + 1;
			case NORTH -> eye.z() < z;
			case SOUTH -> eye.z() > z + 1;
		};
	}

	/** Point on face {@code f} of block (x, y, z) at face coordinates (u, v); values outside 0..1 lie just beyond the edge. */
	static Vec3 facePoint(Face f, int x, int y, int z, double u, double v) {
		return switch (f) {
			case WEST -> new Vec3(x, y + u, z + v);
			case EAST -> new Vec3(x + 1, y + u, z + v);
			case DOWN -> new Vec3(x + u, y, z + v);
			case UP -> new Vec3(x + u, y + 1, z + v);
			case NORTH -> new Vec3(x + u, y + v, z);
			case SOUTH -> new Vec3(x + u, y + v, z + 1);
		};
	}
}
