package dev.nucleusrouter.game;

/**
 * The region around a structure where Bob-ombs centred inside it do nothing and blocks can't be placed (SPEC §2.4, §2.5;
 * user 2026-11-24: placing works everywhere except close to the large structures). Stored as a box
 * relative to the anchor block, in the structure's own (template) orientation, so one box works in
 * every lobby. Exact bounds are [VERIFY]: defaults are generous, and the user can measure them.
 *
 * @param min {dx, dy, dz} lower corner offset (inclusive)
 * @param max {dx, dy, dz} upper corner offset (inclusive)
 */
public record StructureZone(int[] min, int[] max) {
	/** World-space box {minX, minY, minZ, maxX, maxY, maxZ} (inclusive blocks) for an anchor block and orientation. */
	public int[] worldBox(int ax, int ay, int az, Orientation o) {
		int[] a = o.apply(min[0], min[2]), b = o.apply(max[0], max[2]);
		return new int[]{ax + Math.min(a[0], b[0]), ay + min[1], az + Math.min(a[1], b[1]),
				ax + Math.max(a[0], b[0]), ay + max[1], az + Math.max(a[1], b[1])};
	}

	/** Zone from two world corners the user stood at, turned back into template orientation. */
	public static StructureZone fromWorldCorners(int[] c1, int[] c2, int ax, int ay, int az, Orientation o) {
		int[] a = o.invert(c1[0] - ax, c1[2] - az), b = o.invert(c2[0] - ax, c2[2] - az);
		return new StructureZone(
				new int[]{Math.min(a[0], b[0]), Math.min(c1[1], c2[1]) - ay, Math.min(a[1], b[1])},
				new int[]{Math.max(a[0], b[0]), Math.max(c1[1], c2[1]) - ay, Math.max(a[1], b[1])});
	}

	/**
	 * Does a bomb with its blast centre at (cx, cy, cz) do nothing? Confirmed in-game 2026-11-25: only when the
	 * centre is <b>inside</b> the zone. A bomb centred just outside still breaks blocks inside the zone.
	 */
	public static boolean fizzles(int[] box, double cx, double cy, double cz) {
		return cx >= box[0] && cx < box[3] + 1 && cy >= box[1] && cy < box[4] + 1 && cz >= box[2] && cz < box[5] + 1;
	}

	/**
	 * True if any block whose centre is within {@code r} of (cx, cy, cz) lies in the world box. Not the fizzle
	 * rule (see {@link #fizzles}); kept for planners that want to keep blasts away from a structure.
	 */
	public static boolean ballTouches(int[] box, double cx, double cy, double cz, double r) {
		// Block centres span [min+0.5, max+0.5]; nearest centre-space point to the ball centre
		double nx = clamp(cx, box[0] + 0.5, box[3] + 0.5), ny = clamp(cy, box[1] + 0.5, box[4] + 0.5), nz = clamp(cz, box[2] + 0.5, box[5] + 0.5);
		double dx = nx - cx, dy = ny - cy, dz = nz - cz;
		return dx * dx + dy * dy + dz * dz <= r * r + 1e-9;
	}

	/** Distance from a point to the world box (0 inside). */
	public static double distance(int[] box, double x, double y, double z) {
		double dx = Math.max(0, Math.max(box[0] - x, x - (box[3] + 1)));
		double dy = Math.max(0, Math.max(box[1] - y, y - (box[4] + 1)));
		double dz = Math.max(0, Math.max(box[2] - z, z - (box[5] + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static boolean contains(int[] box, int x, int y, int z) {
		return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
	}

	private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
