package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Vec3;

/**
 * Picks where to stand for a stop (SPEC §3.5): a block you can etherwarp onto whose eye position is within
 * {@code reach} of the thing you interact with (an NPC's body, or a crystal's barrier box), with the most
 * open view around it (good lines to warp in and out). Ties go to the spot closer to the target.
 */
public final class StopPicker {
	/** Rays used to judge how open a spot is. */
	static final int VIEW_RAYS = 64;
	/** A view ray counts as open if it travels at least this far. */
	static final double OPEN_DISTANCE = 15;

	/**
	 * @param standFeet    where to stand (feet), or null if no spot is in reach
	 * @param openness     share of view rays that are open (0..1)
	 * @param distance     eye distance to the target
	 */
	public record Pick(Vec3 standFeet, double openness, double distance) {
		public boolean found() { return standFeet != null; }
	}

	private StopPicker() {}

	/**
	 * Best standing spot to reach the box {@code target} = {minX, minY, minZ, maxX, maxY, maxZ} (block
	 * coordinates, inclusive; an NPC is a 1x2x1 box at its feet) from within {@code reach} blocks of the eye.
	 */
	public static Pick pick(PlanWorld world, double[] target, double reach, double eyeHeight, int requiredAir) {
		Vec3[] dirs = Directions.sphere(VIEW_RAYS);
		int r = (int) Math.ceil(reach) + 2;
		Pick best = null;
		for (int x = (int) Math.floor(target[0]) - r; x <= (int) Math.floor(target[3]) + r; x++)
			for (int z = (int) Math.floor(target[2]) - r; z <= (int) Math.floor(target[5]) + r; z++)
				for (int y = (int) Math.floor(target[1]) - r - 2; y <= (int) Math.floor(target[4]) + r; y++) {
					if (!world.goodTarget(x, y, z) || !Etherwarp.hasRoomAbove(x, y, z, requiredAir, world)) continue;
					Vec3 feet = Etherwarp.landingFeet(x, y, z);
					Vec3 eye = Etherwarp.eye(feet, eyeHeight);
					double d = boxDistance(target, eye);
					if (d > reach) continue;
					int open = 0;
					for (Vec3 dir : dirs) {
						VoxelRay.Hit h = VoxelRay.cast(eye, dir, OPEN_DISTANCE, world);
						if (h == null) open++;
					}
					double openness = open / (double) VIEW_RAYS;
					if (best == null || openness > best.openness() + 1e-9 || (Math.abs(openness - best.openness()) <= 1e-9 && d < best.distance()))
						best = new Pick(feet, openness, d);
				}
		return best != null ? best : new Pick(null, 0, Double.POSITIVE_INFINITY);
	}

	/** Distance from a point to an axis-aligned block box (inclusive block corners; 0 inside). */
	static double boxDistance(double[] b, Vec3 p) {
		double dx = Math.max(0, Math.max(b[0] - p.x(), p.x() - (b[3] + 1)));
		double dy = Math.max(0, Math.max(b[1] - p.y(), p.y() - (b[4] + 1)));
		double dz = Math.max(0, Math.max(b[2] - p.z(), p.z() - (b[5] + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** An NPC standing with its feet at {@code feet}: a 1x2x1 box around it. */
	public static double[] npcBox(Vec3 feet) {
		return new double[]{feet.x() - 0.5, feet.y(), feet.z() - 0.5, feet.x() - 0.5, feet.y() + 1, feet.z() - 0.5};
	}
}
