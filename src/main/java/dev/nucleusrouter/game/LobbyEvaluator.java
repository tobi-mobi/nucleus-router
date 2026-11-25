package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a lobby is worth setting up (SPEC §4). Each rule is configurable and can be turned
 * off; the result always lists every rule with its reason, never just a verdict.
 */
public final class LobbyEvaluator {
	public enum Rule { SPREAD, DAY, COVERAGE, RUN_TIME }

	/**
	 * @param warn    true if this rule says "consider another lobby"
	 * @param checked false if the rule is off or can't be judged yet
	 * @param message one line for the player
	 */
	public record Reason(Rule rule, boolean warn, boolean checked, String message) {}

	/**
	 * Everything the rules look at.
	 *
	 * @param positions        known structure positions (exact or approximate)
	 * @param npcs             keeper positions etc., for stop times
	 * @param missing          required structures not found yet
	 * @param lobbyDay         current lobby day (-1 unknown)
	 * @param visitedFraction  share of the Hollows explored (0..1)
	 * @param pingMs           ping used for the run estimate
	 */
	public record Facts(Map<Structure, Vec3> positions, Map<Structure, List<Vec3>> npcs, List<Structure> missing,
			long lobbyDay, double visitedFraction, int pingMs) {}

	private LobbyEvaluator() {}

	public static List<Reason> evaluate(Facts f, NrConfig cfg) {
		NrConfig.Lobby l = cfg.lobby;
		List<Reason> out = new ArrayList<>();

		// Spread: the longest leg you'd have to travel, where /warp nucleus can shorten any leg
		// (user 2026-11-25: the route doesn't have to go straight from one structure to the next)
		Vec3 nucleusSpawn = new Vec3(cfg.hollows.nucleusSpawnX, cfg.hollows.nucleusSpawnY, cfg.hollows.nucleusSpawnZ);
		if (!l.spreadRule) out.add(new Reason(Rule.SPREAD, false, false, "Spread rule is off"));
		else if (f.positions().isEmpty()) out.add(new Reason(Rule.SPREAD, false, false, "Spread: needs a structure found"));
		else {
			Leg worst = longestLeg(f.positions(), nucleusSpawn);
			boolean warn = worst.blocks() > l.maxSpreadBlocks;
			out.add(new Reason(Rule.SPREAD, warn, true, String.format("Spread: longest leg is %s, %d blocks (%s %d)%s", worst.describe(),
					Math.round(worst.blocks()), warn ? "over" : "within", l.maxSpreadBlocks, f.missing().isEmpty() ? "" : ", not all structures found yet")));
		}

		// Day: high-day lobbies close soon after the setup is done
		if (!l.dayRule) out.add(new Reason(Rule.DAY, false, false, "Day rule is off"));
		else if (f.lobbyDay() < 0) out.add(new Reason(Rule.DAY, false, false, "Day: lobby day unknown"));
		else {
			boolean warn = f.lobbyDay() > l.warnAfterDay;
			out.add(new Reason(Rule.DAY, warn, true, String.format("Day: lobby day %d (%s day %d; lobbies close around day %d-%d)",
					f.lobbyDay(), warn ? "over" : "within", l.warnAfterDay, cfg.hollows.lobbyCloseDayMin, cfg.hollows.lobbyCloseDayMax)));
		}

		// Coverage: explored a lot and still missing structures
		if (!l.coverageRule) out.add(new Reason(Rule.COVERAGE, false, false, "Coverage rule is off"));
		else if (f.missing().isEmpty()) out.add(new Reason(Rule.COVERAGE, false, true, String.format("Coverage: all structures found (%.0f%% explored)", f.visitedFraction() * 100)));
		else {
			boolean warn = f.visitedFraction() >= l.coverageWarnFraction;
			StringBuilder names = new StringBuilder();
			for (Structure s : f.missing()) names.append(names.length() > 0 ? ", " : "").append(s.displayName);
			out.add(new Reason(Rule.COVERAGE, warn, true, String.format("Coverage: %.0f%% explored, still missing %s%s", f.visitedFraction() * 100, names,
					warn ? String.format(" (warn from %.0f%%)", l.coverageWarnFraction * 100) : "")));
		}

		// Run time: rough estimate until the real planner exists
		if (!l.runTimeRule) out.add(new Reason(Rule.RUN_TIME, false, false, "Run time rule is off"));
		else {
			Map<Structure, Vec3> pos = f.positions();
			Vec3 nucleus = new Vec3(cfg.hollows.nucleusSpawnX, cfg.hollows.nucleusSpawnY, cfg.hollows.nucleusSpawnZ);
			RunEstimate.Result r = RunEstimate.estimate(pos, f.npcs(), nucleus, cfg, l.legEfficiency, f.pingMs());
			if (r == null) out.add(new Reason(Rule.RUN_TIME, false, false, "Run time: needs all structures found"));
			else {
				boolean warn = r.seconds() > l.warnRunSeconds;
				out.add(new Reason(Rule.RUN_TIME, warn, true, String.format("Run time (rough): about %s (%s %s)",
						mmss(r.seconds()), warn ? "over" : "within", mmss(l.warnRunSeconds))));
			}
		}
		return out;
	}

	/** A leg of the route: from a structure (or the nucleus) to a structure. */
	public record Leg(Structure from, Structure to, boolean viaNucleus, double blocks) {
		public String describe() {
			return viaNucleus ? "/warp nucleus to " + to.displayName : from.displayName + " to " + to.displayName;
		}
	}

	/**
	 * The longest unavoidable leg: for every structure, the shortest way to reach it from any other structure
	 * (straight, or /warp nucleus then from the nucleus); the worst of those. With one structure it is the
	 * distance from the nucleus. This is what decides how far you must travel, not the straight distance
	 * between the two furthest structures.
	 */
	public static Leg longestLeg(Map<Structure, Vec3> positions, Vec3 nucleus) {
		Leg worst = null;
		for (Map.Entry<Structure, Vec3> to : positions.entrySet()) {
			Leg best = new Leg(null, to.getKey(), true, to.getValue().sub(nucleus).length());
			for (Map.Entry<Structure, Vec3> from : positions.entrySet()) {
				if (from.getKey() == to.getKey()) continue;
				double d = from.getValue().sub(to.getValue()).length();
				if (d < best.blocks()) best = new Leg(from.getKey(), to.getKey(), false, d);
			}
			if (worst == null || best.blocks() > worst.blocks()) worst = best;
		}
		return worst;
	}

	public static boolean anyWarning(List<Reason> reasons) {
		for (Reason r : reasons) if (r.warn()) return true;
		return false;
	}

	public static String mmss(double seconds) {
		long s = Math.round(seconds);
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
