package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.geom.Vec3;

/**
 * Exact voxel traversal (Amanatides and Woo, 1987): visits every block the ray passes through,
 * in order, with no sampling gaps (SPEC §2.3).
 */
public final class VoxelRay {
	/**
	 * @param x, y, z  the block hit
	 * @param face     the face the ray entered through (null if the ray started inside the block)
	 * @param distance distance from the origin to where the ray entered the block
	 * @param point    that entry point
	 */
	public record Hit(int x, int y, int z, Face face, double distance, Vec3 point) {
		public boolean isBlock(int bx, int by, int bz) { return x == bx && y == by && z == bz; }
	}

	private VoxelRay() {}

	/**
	 * First solid block along the ray whose entry point is within {@code maxDistance}, or null.
	 * The block containing the origin is checked too.
	 */
	public static Hit cast(Vec3 origin, Vec3 direction, double maxDistance, SolidQuery solid) {
		Vec3 d = direction.normalize();
		int x = (int) Math.floor(origin.x()), y = (int) Math.floor(origin.y()), z = (int) Math.floor(origin.z());

		int stepX = sign(d.x()), stepY = sign(d.y()), stepZ = sign(d.z());
		double tDeltaX = stepX != 0 ? 1.0 / Math.abs(d.x()) : Double.POSITIVE_INFINITY;
		double tDeltaY = stepY != 0 ? 1.0 / Math.abs(d.y()) : Double.POSITIVE_INFINITY;
		double tDeltaZ = stepZ != 0 ? 1.0 / Math.abs(d.z()) : Double.POSITIVE_INFINITY;
		double tMaxX = firstBoundary(origin.x(), x, stepX, d.x());
		double tMaxY = firstBoundary(origin.y(), y, stepY, d.y());
		double tMaxZ = firstBoundary(origin.z(), z, stepZ, d.z());

		double t = 0;
		Face entered = null;
		int maxSteps = (int) Math.ceil(maxDistance) * 3 + 6;
		for (int i = 0; i < maxSteps; i++) {
			if (t > maxDistance) return null;
			if (solid.solid(x, y, z)) return new Hit(x, y, z, entered, t, origin.add(d.scale(t)));
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				x += stepX; t = tMaxX; tMaxX += tDeltaX;
				entered = stepX > 0 ? Face.WEST : Face.EAST;
			} else if (tMaxY <= tMaxZ) {
				y += stepY; t = tMaxY; tMaxY += tDeltaY;
				entered = stepY > 0 ? Face.DOWN : Face.UP;
			} else {
				z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ;
				entered = stepZ > 0 ? Face.NORTH : Face.SOUTH;
			}
		}
		return null;
	}

	private static int sign(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }

	private static double firstBoundary(double o, int cell, int step, double d) {
		if (step > 0) return (cell + 1 - o) / d;
		if (step < 0) return (o - cell) / -d;
		return Double.POSITIVE_INFINITY;
	}
}
