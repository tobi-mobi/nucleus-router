package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Route ordering (SPEC §3.2, roadmap 3.4): the fastest order to visit every structure stop, starting and
 * ending at the nucleus, with King Yolkar before the Goblin Queen. Every move between stops is either
 * direct (an etherwarp leg) or /warp nucleus followed by a leg from the nucleus, whichever is faster:
 * <ul>
 *   <li>after the Amber crystal: always /warp nucleus (it counts as leaving the den, confirmed), unless the
 *       den exit is set to etherwarp;</li>
 *   <li>after the Jungle Temple: the teleport to the front of the temple, then a leg, or /warp nucleus;</li>
 *   <li>the run ends with /warp nucleus (or a direct leg if somehow faster).</li>
 * </ul>
 * With 6 structures there are at most 720 orders (360 with the precedence rule): all are tried once the
 * leg times are known, which takes well under a millisecond.
 */
public final class RouteOrder {
	/** Seconds for a leg between two stops (by id), or +infinity if there's no route on the terrain. */
	@FunctionalInterface
	public interface Legs {
		double seconds(String fromId, String toId);
	}

	/**
	 * One move of the run.
	 *
	 * @param fromId          where you leave from (the stop, or "temple.exit" after the temple)
	 * @param viaWarpNucleus  /warp nucleus first, then the leg from the nucleus
	 * @param travelSeconds   warp nucleus + leg time
	 * @param waitSeconds     time at the destination stop
	 */
	public record Step(String fromId, String toId, boolean viaWarpNucleus, double travelSeconds, double waitSeconds) {}

	/**
	 * @param steps      the moves in order, the last one back to the nucleus
	 * @param seconds    total estimated run time
	 * @param unreachable stop ids no order could reach on the existing terrain (they need a setup)
	 */
	public record Plan(List<Step> steps, double seconds, List<String> unreachable) {
		/** True if there is an order (it may skip unreachable stops). */
		public boolean hasRoute() { return !steps.isEmpty(); }
		public boolean ok() { return unreachable.isEmpty() && !steps.isEmpty(); }
	}

	private RouteOrder() {}

	/** The ids a leg is needed between: every departure point to every structure stop, and back to the nucleus. */
	public static List<String[]> neededLegs(List<StopPlan.Stop> stops, NrConfig cfg) {
		List<String> targets = new ArrayList<>(), departures = new ArrayList<>();
		departures.add("nucleus");
		for (StopPlan.Stop s : stops) {
			if (s.kind() != StopPlan.Kind.STRUCTURE) continue;
			targets.add(s.id());
			String dep = departure(s.id(), stops, cfg);
			if (dep != null) departures.add(dep);
		}
		List<String[]> out = new ArrayList<>();
		for (String d : departures) {
			for (String t : targets) if (!t.equals(d) && !d.equals(t)) out.add(new String[]{d, t});
			if (!d.equals("nucleus")) out.add(new String[]{d, "nucleus"});
		}
		return out;
	}

	/** Where you leave a stop from, or null if you always leave with /warp nucleus. */
	static String departure(String id, List<StopPlan.Stop> stops, NrConfig cfg) {
		if (id.equals("queen") && StopPlan.denExit(cfg) == StopPlan.DenExit.WARP_NUCLEUS) return null;
		if (id.equals("temple")) {
			for (StopPlan.Stop s : stops) if (s.id().equals("temple.exit")) return s.id();
			return null;
		}
		return id;
	}

	public static Plan solve(List<StopPlan.Stop> stops, Legs legs, NrConfig cfg) {
		Map<String, StopPlan.Stop> byId = new java.util.HashMap<>();
		List<String> targets = new ArrayList<>();
		for (StopPlan.Stop s : stops) {
			byId.put(s.id(), s);
			if (s.kind() == StopPlan.Kind.STRUCTURE) targets.add(s.id());
		}
		double warp = cfg.hollows.warpNucleusSeconds;

		// Reachability: a stop is reachable if the nucleus (or any departure point) has a leg to it
		Set<String> unreachable = new LinkedHashSet<>();
		for (String t : targets) {
			boolean any = Double.isFinite(legs.seconds("nucleus", t));
			for (String o : targets) {
				String dep = departure(o, stops, cfg);
				if (dep != null && !o.equals(t) && Double.isFinite(legs.seconds(dep, t))) any = true;
			}
			if (!any) unreachable.add(t);
		}
		List<String> visit = new ArrayList<>(targets);
		visit.removeAll(unreachable);
		if (visit.isEmpty()) return new Plan(List.of(), Double.POSITIVE_INFINITY, List.copyOf(unreachable));

		List<Step> best = null;
		double bestTotal = Double.POSITIVE_INFINITY;
		for (List<String> order : permutations(visit)) {
			int king = order.indexOf("king"), queen = order.indexOf("queen");
			if (king >= 0 && queen >= 0 && king > queen) continue;
			List<Step> steps = new ArrayList<>();
			double total = 0;
			String at = "nucleus";
			boolean atIsDeparture = true;
			for (String next : order) {
				Step s = move(at, atIsDeparture, next, byId.get(next).waitSeconds(), legs, warp);
				total += s.travelSeconds() + s.waitSeconds();
				if (total >= bestTotal) break;
				steps.add(s);
				String dep = departure(next, stops, cfg);
				at = dep != null ? dep : next;
				atIsDeparture = dep != null;
			}
			if (steps.size() < order.size()) continue;
			Step home = move(at, atIsDeparture, "nucleus", 0, legs, warp);
			total += home.travelSeconds();
			steps.add(home);
			if (total < bestTotal) { bestTotal = total; best = steps; }
		}
		return new Plan(best == null ? List.of() : best, best == null ? Double.POSITIVE_INFINITY : bestTotal, List.copyOf(unreachable));
	}

	/** From {@code at} to {@code to}: direct leg, or /warp nucleus then the leg from the nucleus. */
	private static Step move(String at, boolean canLeaveDirectly, String to, double wait, Legs legs, double warp) {
		if (at.equals("nucleus")) return new Step(at, to, false, legs.seconds("nucleus", to), wait);
		double direct = canLeaveDirectly ? legs.seconds(at, to) : Double.POSITIVE_INFINITY;
		double viaNucleus = warp + (to.equals("nucleus") ? 0 : legs.seconds("nucleus", to));
		return direct <= viaNucleus ? new Step(at, to, false, direct, wait) : new Step(at, to, true, viaNucleus, wait);
	}

	private static List<List<String>> permutations(List<String> items) {
		List<List<String>> out = new ArrayList<>();
		permute(new ArrayList<>(items), 0, out);
		return out;
	}

	private static void permute(List<String> a, int k, List<List<String>> out) {
		if (k == a.size()) { out.add(List.copyOf(a)); return; }
		for (int i = k; i < a.size(); i++) {
			Collections.swap(a, k, i);
			permute(a, k + 1, out);
			Collections.swap(a, k, i);
		}
	}
}
