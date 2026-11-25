package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A ROUGH run-time estimate for the lobby evaluator (SPEC §4), until the real planner exists
 * (milestone 3). Every leg is a straight line covered by etherwarps of {@code range x legEfficiency}
 * blocks each; each warp costs the base warp time, half the ping and an average 45-degree turn.
 * Stop times come from the config. The best order is searched over all orders with King Yolkar
 * before the Goblin Queen, and each transition may go via /warp nucleus instead.
 */
public final class RunEstimate {
	/**
	 * @param seconds   total estimated run time
	 * @param order     visiting order
	 * @param viaWarp   for each stop in {@code order}: reached with /warp nucleus first
	 */
	public record Result(double seconds, List<Structure> order, List<Boolean> viaWarp) {}

	private RunEstimate() {}

	/** Null if any structure's position is missing. */
	public static Result estimate(Map<Structure, Vec3> pos, Map<Structure, List<Vec3>> npcs, Vec3 nucleus, NrConfig cfg, double legEfficiency, int pingMs) {
		for (Structure s : Structure.values()) if (pos.get(s) == null) return null;
		double perWarp = cfg.cost.baseWarpSeconds + pingMs / 2000.0
				+ cfg.cost.turnSecondsAt90 * Math.pow(0.5, cfg.cost.turnExponent);
		double perBlock = 1.0 / Math.max(1, cfg.etherwarp.range * legEfficiency);
		Structure[] all = Structure.values();
		List<Structure> bestOrder = null;
		List<Boolean> bestVia = null;
		double best = Double.MAX_VALUE;
		for (List<Structure> order : permutations(List.of(all))) {
			if (order.indexOf(Structure.KING_YOLKAR) > order.indexOf(Structure.GOBLIN_QUEENS_DEN)) continue;
			double t = 0;
			Vec3 at = nucleus;
			List<Boolean> via = new ArrayList<>();
			for (Structure s : order) {
				Vec3 to = pos.get(s);
				double direct = legSeconds(at, to, perWarp, perBlock);
				double warp = cfg.hollows.warpNucleusSeconds + legSeconds(nucleus, to, perWarp, perBlock);
				via.add(warp < direct);
				t += Math.min(direct, warp) + stopSeconds(s, npcs.get(s), cfg, perWarp, perBlock);
				at = to;
			}
			t += Math.min(cfg.hollows.warpNucleusSeconds, legSeconds(at, nucleus, perWarp, perBlock)); // back to the nucleus
			if (t < best) { best = t; bestOrder = order; bestVia = via; }
		}
		return new Result(best, bestOrder, bestVia);
	}

	static double legSeconds(Vec3 a, Vec3 b, double perWarp, double perBlock) {
		double d = b.sub(a).length();
		return Math.ceil(d * perBlock) * perWarp;
	}

	/** Time spent at a stop: interactions, pickups, fights, the manual temple, walking between keepers. */
	static double stopSeconds(Structure s, List<Vec3> npcs, NrConfig cfg, double perWarp, double perBlock) {
		NrConfig.Stops st = cfg.stops;
		return switch (s) {
			case MINES_OF_DIVAN -> {
				double t = 4 * st.npcInteractionSeconds + st.crystalPickupSeconds;
				if (npcs != null && npcs.size() > 1) for (int i = 1; i < npcs.size(); i++) t += legSeconds(npcs.get(i - 1), npcs.get(i), perWarp, perBlock);
				yield t;
			}
			case PRECURSOR_CITY -> st.npcInteractionSeconds + st.crystalPickupSeconds;
			case KING_YOLKAR -> st.npcInteractionSeconds;
			case GOBLIN_QUEENS_DEN -> st.crystalPickupSeconds + st.queenDenExitTargetSeconds;
			case JUNGLE_TEMPLE -> st.npcInteractionSeconds + st.templeManualSeconds + st.crystalPickupSeconds;
			case KHAZAD_DUM -> st.balFightSeconds + st.crystalPickupSeconds;
		};
	}

	private static List<List<Structure>> permutations(List<Structure> items) {
		List<List<Structure>> out = new ArrayList<>();
		permute(new ArrayList<>(items), 0, out);
		return out;
	}

	private static void permute(List<Structure> a, int k, List<List<Structure>> out) {
		if (k == a.size()) { out.add(List.copyOf(a)); return; }
		for (int i = k; i < a.size(); i++) {
			java.util.Collections.swap(a, k, i);
			permute(a, k + 1, out);
			java.util.Collections.swap(a, k, i);
		}
	}
}
