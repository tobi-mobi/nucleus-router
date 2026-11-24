package dev.nucleusrouter.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Queen's den and Khazad-dûm crystals sit in a big box of barrier blocks (seen in-game 2026-11-24;
 * before the crystal's mission is done the box is empty). This groups barrier blocks into connected
 * clumps (touching, diagonals included) so the crystal box can be picked out.
 */
public final class BarrierBoxes {
	/** One connected clump of barriers. {@code min}/{@code max} are inclusive block corners. */
	public record Box(int[] min, int[] max, int count) {
		public double centreX() { return (min[0] + max[0] + 1) / 2.0; }
		public double centreY() { return (min[1] + max[1] + 1) / 2.0; }
		public double centreZ() { return (min[2] + max[2] + 1) / 2.0; }
	}

	private BarrierBoxes() {}

	public static List<Box> group(List<int[]> blocks) {
		Map<Long, int[]> left = new HashMap<>();
		for (int[] b : blocks) left.put(key(b[0], b[1], b[2]), b);
		List<Box> out = new ArrayList<>();
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		while (!left.isEmpty()) {
			int[] seed = left.values().iterator().next();
			left.remove(key(seed[0], seed[1], seed[2]));
			queue.add(seed);
			int[] min = seed.clone(), max = seed.clone();
			int count = 0;
			while (!queue.isEmpty()) {
				int[] p = queue.poll();
				count++;
				for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], p[i]); max[i] = Math.max(max[i], p[i]); }
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
					int[] n = left.remove(key(p[0] + dx, p[1] + dy, p[2] + dz));
					if (n != null) queue.add(n);
				}
			}
			out.add(new Box(min, max, count));
		}
		return out;
	}

	/** The biggest clump with at least {@code minCount} barriers (closest to the point on a tie), or null. */
	public static Box pickCrystalBox(List<Box> boxes, int minCount, double x, double y, double z) {
		Box best = null;
		double bestD = 0;
		for (Box b : boxes) {
			if (b.count() < minCount) continue;
			double dx = b.centreX() - x, dy = b.centreY() - y, dz = b.centreZ() - z;
			double d = dx * dx + dy * dy + dz * dz;
			if (best == null || b.count() > best.count() || (b.count() == best.count() && d < bestD)) { best = b; bestD = d; }
		}
		return best;
	}

	private static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}
}
