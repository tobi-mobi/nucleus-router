package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out the eye height the server really uses (SPEC §6, [VERIFY] in ROADMAP.md).
 *
 * For each real warp we know where the feet were, the look direction, and which block the
 * player actually landed on. We try every eye height in a range and keep the ones whose ray
 * hits that same block. Over several warps, the heights that explain the most warps win.
 */
public final class EyeHeightFit {
	public static final double MIN = 1.00, MAX = 1.80, STEP = 0.005;

	/** One real warp. {@code ax, ay, az} is the block actually landed on. */
	public record Observation(Vec3 feet, Vec3 direction, int ax, int ay, int az) {}

	/** Eye heights (on the {@link #STEP} grid) whose ray hits the actual block, as [low, high] ranges. */
	public static List<double[]> matchingRanges(Observation o, double range, SolidQuery solid) {
		List<double[]> out = new ArrayList<>();
		double start = Double.NaN, last = Double.NaN;
		for (int i = 0; i <= steps(); i++) {
			double h = height(i);
			VoxelRay.Hit hit = VoxelRay.cast(Etherwarp.eye(o.feet(), h), o.direction(), range, solid);
			boolean match = hit != null && hit.isBlock(o.ax(), o.ay(), o.az());
			if (match) {
				if (Double.isNaN(start)) start = h;
				last = h;
			} else if (!Double.isNaN(start)) {
				out.add(new double[]{start, last});
				start = Double.NaN;
			}
		}
		if (!Double.isNaN(start)) out.add(new double[]{start, last});
		return out;
	}

	/**
	 * @param height    suggested eye height (middle of the best-supported range), NaN if no data
	 * @param explained how many warps that height explains
	 * @param total     warps considered
	 * @param low       lower end of the best-supported range
	 * @param high      upper end
	 */
	public record Suggestion(double height, int explained, int total, double low, double high) {
		public boolean hasData() { return !Double.isNaN(height); }
	}

	/** Picks the eye height that explains the most warps; ties are resolved by taking the middle of the widest run. */
	public static Suggestion suggest(List<List<double[]>> perWarp) {
		int n = steps() + 1;
		int[] counts = new int[n];
		for (List<double[]> ranges : perWarp) {
			for (int i = 0; i < n; i++) {
				double h = height(i);
				for (double[] r : ranges) {
					if (h >= r[0] - 1e-9 && h <= r[1] + 1e-9) { counts[i]++; break; }
				}
			}
		}
		int best = 0;
		for (int c : counts) best = Math.max(best, c);
		if (best == 0) return new Suggestion(Double.NaN, 0, perWarp.size(), Double.NaN, Double.NaN);

		// Widest contiguous run with the best count
		int runStart = -1, bestStart = -1, bestLen = 0;
		for (int i = 0; i <= n; i++) {
			boolean in = i < n && counts[i] == best;
			if (in && runStart < 0) runStart = i;
			if (!in && runStart >= 0) {
				if (i - runStart > bestLen) { bestLen = i - runStart; bestStart = runStart; }
				runStart = -1;
			}
		}
		double low = height(bestStart), high = height(bestStart + bestLen - 1);
		return new Suggestion(Math.round((low + high) / 2 / STEP) * STEP, best, perWarp.size(), low, high);
	}

	private static int steps() { return (int) Math.round((MAX - MIN) / STEP); }
	private static double height(int i) { return MIN + i * STEP; }

	private EyeHeightFit() {}
}
