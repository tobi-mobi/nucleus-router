package dev.nucleusrouter.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compares a saved {@link StructureTemplate} with the current world around the same structure's anchor,
 * for every {@link Orientation} and small shifts (anchors can sit a block or two off). Only distinctive
 * blocks count (not the random cave around the structure). The best orientation is how the structure
 * is turned in this lobby.
 */
public final class TemplateMatcher {
	/** Compare at most this many distinctive blocks (evenly spread) to keep it fast. */
	static final int MAX_SAMPLES = 6000;
	/** A score needs at least this many compared (seen) blocks to count. */
	public static final int MIN_COMPARED = 150;

	/**
	 * @param score    share of compared distinctive blocks that match (0..1)
	 * @param compared how many template blocks could be compared (seen in both)
	 * @param shiftX   anchor shift in world blocks that gave the best score (also Y, Z)
	 */
	public record Match(Orientation orientation, double score, int compared, int shiftX, int shiftY, int shiftZ) {}

	private TemplateMatcher() {}

	/** All orientations, best first. {@code shift} is how far (blocks) the anchor may be off on each axis. */
	public static List<Match> match(StructureTemplate t, int anchorX, int anchorY, int anchorZ, int shift, StructureTemplate.BlockLookup world) {
		List<int[]> samples = samples(t);
		List<Match> out = new ArrayList<>();
		for (Orientation o : Orientation.values()) {
			Match best = null;
			for (int sx = -shift; sx <= shift; sx++) for (int sy = -shift; sy <= shift; sy++) for (int sz = -shift; sz <= shift; sz++) {
				int matched = 0, compared = 0;
				for (int[] s : samples) {
					int[] xz = o.apply(s[0], s[2]);
					String w = world.at(anchorX + sx + xz[0], anchorY + sy + s[1], anchorZ + sz + xz[1]);
					if (w == null || w.equals(StructureTemplate.UNSEEN)) continue;
					compared++;
					if (w.equals(t.palette.get(s[3]))) matched++;
				}
				double score = compared == 0 ? 0 : matched / (double) compared;
				if (compared >= MIN_COMPARED && (best == null || score > best.score())) best = new Match(o, score, compared, sx, sy, sz);
			}
			out.add(best != null ? best : new Match(o, 0, 0, 0, 0, 0));
		}
		out.sort(Comparator.comparingDouble(Match::score).reversed());
		return out;
	}

	/** {dx, dy, dz, paletteIndex} of distinctive template blocks, thinned to at most {@link #MAX_SAMPLES}. */
	static List<int[]> samples(StructureTemplate t) {
		List<int[]> all = new ArrayList<>();
		int r = t.radius, i = 0;
		for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
			int p = t.data[i++];
			if (StructureTemplate.distinctive(t.palette.get(p))) all.add(new int[]{dx, dy, dz, p});
		}
		if (all.size() <= MAX_SAMPLES) return all;
		List<int[]> thin = new ArrayList<>(MAX_SAMPLES);
		double step = all.size() / (double) MAX_SAMPLES;
		for (int k = 0; k < MAX_SAMPLES; k++) thin.add(all.get((int) (k * step)));
		return thin;
	}

	/** True if the best match is clearly better than the runner-up. */
	public static boolean confident(List<Match> ranked) {
		if (ranked.isEmpty() || ranked.get(0).compared() < MIN_COMPARED) return false;
		double best = ranked.get(0).score(), second = ranked.size() > 1 ? ranked.get(1).score() : 0;
		return best >= 0.6 && best - second >= 0.15;
	}
}
