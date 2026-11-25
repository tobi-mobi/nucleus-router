package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Leg designer (SPEC §3.4 steps 1-4 and 8, roadmap 4.2): the ideal tunnel for one leg, as straight lanes.
 * <ol>
 *   <li>Candidate paths from the leg start to its end with 0-3 bends (each straight part is one lane, each bend
 *       one aim change): straight; one bend sideways/up/down; a parallel detour; out-across-back.</li>
 *   <li>Lanes are looked for in the <em>ideal</em> world (everything clearable gone), so they can only be blocked
 *       by what can't be cleared: ores and gemstones (respawning), structure and unbreakable blocks, unseen
 *       terrain. Targets may be placed, never on respawning or unexplored blocks.</li>
 *   <li>Each lane gets its whole-number step and most forgiving look direction ({@link Lane#design}).</li>
 *   <li>What must be cleared = every lane's ray corridor (with an aim tolerance) and room to stand, that is solid
 *       and clearable now. Target blocks that aren't there yet are placements.</li>
 *   <li>The design is then checked in the <em>post-setup</em> world (exactly those blocks cleared and placed),
 *       and scored: run time + a small weight for setup effort.</li>
 * </ol>
 * Bombs (4.3) usually clear more than needed; {@link #validate} re-checks a design against the real result.
 */
public final class LegDesigner {
	/** One lane of a design, with the look direction to hold. */
	public record PlannedLane(Lane.Spec spec, Vec3 direction, double marginDeg, double turnDeg, double seconds) {}

	/**
	 * A designed leg.
	 *
	 * @param lanes      lanes in order (the first starts on the leg's start block)
	 * @param toClear    blocks that must be cleared (solid and clearable now), packed with {@link Lane#pack}
	 * @param placements target blocks that must be placed (not there now), packed
	 * @param inZone     how many of {@code toClear} are inside a structure zone (cleared by nearby bombs or mined)
	 * @param runSeconds expected time to run the leg
	 * @param score      run time + setup effort weights (lower is better)
	 * @param problem    null when the design passed the post-setup check
	 */
	public record Design(List<PlannedLane> lanes, Set<Long> toClear, Set<Long> placements, int inZone, double runSeconds,
			double score, String problem) {
		public boolean valid() { return problem == null; }
		public int bends() { return Math.max(0, lanes.size() - 1); }
		public int warps() { return lanes.stream().mapToInt(l -> l.spec().warps()).sum(); }
		public int[] end() { return lanes.get(lanes.size() - 1).spec().end(); }
	}

	/** All designs that passed, best first, plus how many paths were tried. */
	public record Result(List<Design> designs, int pathsTried, int pathsBlocked, boolean timedOut, double millis) {
		public Design best() { return designs.isEmpty() ? null : designs.get(0); }
	}

	private final SetupWorld world;
	private final Etherwarp.Params params;
	private final CostModel cost;
	private final NrConfig.Setup cfg;
	private final double minMarginDeg;
	private final SolidQuery ideal;
	private final Map<String, Lane.Choice> laneCache = new HashMap<>();

	public LegDesigner(SetupWorld world, Etherwarp.Params params, CostModel cost, NrConfig cfg) {
		this.world = world;
		this.params = params;
		this.cost = cost;
		this.cfg = cfg.setup;
		this.minMarginDeg = cfg.plan.minAimMarginDeg;
		this.ideal = world.ideal();
	}

	/**
	 * Designs the leg from standing on {@code start} to standing on (within 2 blocks of) {@code goal}.
	 * Both are the blocks under your feet.
	 */
	public Result design(int[] start, int[] goal) {
		return design(start, goal, cfg.legTimeLimitMs);
	}

	/** Same, with its own time limit (the whole-lobby plan shares one budget between all legs). */
	public Result design(int[] start, int[] goal, long timeLimitMs) {
		long t0 = System.nanoTime();
		long deadline = t0 + Math.max(1, timeLimitMs) * 1_000_000L;
		List<List<int[]>> paths = paths(start, goal, cfg.maxBends, cfg.bendOffsetBlocks);
		List<Design> out = new ArrayList<>();
		int tried = 0, blocked = 0;
		boolean timedOut = false;
		for (List<int[]> path : paths) {
			if (System.nanoTime() > deadline) { timedOut = true; break; }
			double bestScore = out.stream().mapToDouble(Design::score).min().orElse(Double.POSITIVE_INFINITY);
			if (lowerBound(path) >= bestScore) continue; // can't beat what we have
			tried++;
			if (!roughlyOpen(path)) { blocked++; continue; }
			Design d = chain(path);
			if (d != null && d.valid()) out.add(d);
		}
		out.sort(Comparator.comparingDouble(Design::score));
		return new Result(out, tried, blocked, timedOut, (System.nanoTime() - t0) / 1e6);
	}

	// ------------------------------------------------------------------ candidate paths

	/**
	 * Candidate paths (vertex lists from start to goal), fewest bends first. Bends are offset sideways (horizontal,
	 * across the leg) and up/down by 1x and 2x {@code offset}.
	 */
	public static List<List<int[]>> paths(int[] a, int[] b, int maxBends, int offset) {
		Vec3 A = vec(a), B = vec(b), d = B.sub(A);
		Vec3 side = new Vec3(-d.z(), 0, d.x());
		side = side.length() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 up = new Vec3(0, 1, 0);
		List<Vec3> offs = new ArrayList<>();
		for (int i = -2; i <= 2; i++)
			for (int j = -2; j <= 2; j++)
				if (i != 0 || j != 0) offs.add(side.scale(i * offset).add(up.scale(j * offset)));
		offs.sort(Comparator.comparingDouble(Vec3::length));

		List<List<int[]>> out = new ArrayList<>();
		out.add(List.of(a, b));
		if (maxBends >= 1)
			for (Vec3 o : offs)
				for (double t : new double[]{0.5, 1 / 3.0, 2 / 3.0})
					add(out, a, b, A.add(d.scale(t)).add(o));
		if (maxBends >= 2)
			for (Vec3 o : offs) {
				add(out, a, b, A.add(d.scale(1 / 3.0)).add(o), A.add(d.scale(2 / 3.0)).add(o)); // parallel detour
				add(out, a, b, A.add(o), B.add(o));                                              // out, across, back
			}
		if (maxBends >= 3)
			for (Vec3 o : offs)
				add(out, a, b, A.add(o), A.add(d.scale(0.5)).add(o.scale(2)), B.add(o));
		return out;
	}

	private static void add(List<List<int[]>> out, int[] a, int[] b, Vec3... mids) {
		List<int[]> path = new ArrayList<>();
		path.add(a);
		for (Vec3 m : mids) path.add(new int[]{(int) Math.round(m.x()), (int) Math.round(m.y()), (int) Math.round(m.z())});
		path.add(b);
		for (int i = 1; i < path.size(); i++) if (dist(path.get(i - 1), path.get(i)) < 4) return; // bends too close together
		out.add(path);
	}

	/** Fastest a path could possibly be: fewest warps for its length, plus the re-aim at every bend. */
	private double lowerBound(List<int[]> path) {
		double len = 0;
		for (int i = 1; i < path.size(); i++) len += dist(path.get(i - 1), path.get(i));
		int warps = (int) Math.ceil(len / (params.range() - 2));
		int bends = path.size() - 2;
		double perBend = cost.warpSeconds(1, 90) - cost.baseSeconds();
		return Math.max(warps, path.size() - 1) * cost.baseSeconds() + bends * perBend;
	}

	/** Quick check: a straight line between consecutive vertices isn't blocked by anything that can't be cleared. */
	private boolean roughlyOpen(List<int[]> path) {
		for (int i = 1; i < path.size(); i++) {
			Vec3 from = vec(path.get(i - 1)).add(new Vec3(0.5, 1 + params.eyeHeight(), 0.5));
			Vec3 to = vec(path.get(i)).add(new Vec3(0.5, 1.0, 0.5));
			Vec3 dir = to.sub(from);
			double len = dir.length();
			if (len < 1e-6) continue;
			VoxelRay.Hit hit = VoxelRay.cast(from, dir.scale(1 / len), Math.max(0, len - 1.0), ideal);
			if (hit != null) return false;
		}
		return true;
	}

	// ------------------------------------------------------------------ one path into lanes

	/** Turns one path into lanes (each starting where the previous one really ended), then checks the result. */
	private Design chain(List<int[]> path) {
		List<PlannedLane> lanes = new ArrayList<>();
		int[] cur = path.get(0);
		Vec3 prevDir = null;
		for (int i = 1; i < path.size(); i++) {
			int[] to = path.get(i);
			Vec3 seg = vec(to).sub(vec(cur));
			if (seg.length() < 1) continue;
			double turn = prevDir == null ? cfg.firstTurnDeg : prevDir.angleDeg(seg.normalize());
			Lane.Choice c = bestLane(cur, to, turn, i == path.size() - 1);
			if (c == null) return null;
			Vec3 dir = c.result().direction();
			double realTurn = prevDir == null ? cfg.firstTurnDeg : prevDir.angleDeg(dir);
			lanes.add(new PlannedLane(c.spec(), dir, c.result().marginDeg(), realTurn, Lane.seconds(c.spec(), c.result(), realTurn, cost)));
			prevDir = dir;
			cur = c.spec().end();
		}
		if (lanes.isEmpty()) return null;
		return finish(lanes);
	}

	/** The cheapest lane from {@code from} to near {@code to} whose targets may be used (cached). */
	private Lane.Choice bestLane(int[] from, int[] to, double turnDeg, boolean last) {
		int turnBucket = (int) Math.round(turnDeg / 15);
		String key = from[0] + "," + from[1] + "," + from[2] + ">" + to[0] + "," + to[1] + "," + to[2] + "@" + turnBucket;
		if (laneCache.containsKey(key)) return laneCache.get(key);
		Lane.Options o = new Lane.Options(last ? 2 : 1, 2, 2.0, 3, 8, minMarginDeg, true);
		Lane.Choice best = null;
		for (Lane.Choice c : Lane.design(from, to, turnDeg, params, ideal, cost, o)) {
			if (targetsAllowed(c.spec())) { best = c; break; }
		}
		laneCache.put(key, best);
		return best;
	}

	private boolean targetsAllowed(Lane.Spec s) {
		for (int k = 1; k <= s.warps(); k++) {
			int[] t = s.target(k);
			if (!world.targetAllowed(t[0], t[1], t[2])) return false;
		}
		return true;
	}

	/** Clearance, placements, post-setup check and score. */
	private Design finish(List<PlannedLane> lanes) {
		Set<Long> toClear = new LinkedHashSet<>(), targets = new LinkedHashSet<>(), placements = new LinkedHashSet<>();
		for (PlannedLane l : lanes)
			for (int k = 1; k <= l.spec().warps(); k++) { int[] t = l.spec().target(k); targets.add(Lane.pack(t[0], t[1], t[2])); }
		for (PlannedLane l : lanes)
			for (long key : Lane.clearance(l.spec(), l.direction(), cfg.corridorToleranceDeg, params)) {
				if (targets.contains(key)) continue;
				int[] p = Lane.unpack(key);
				if (world.solid(p[0], p[1], p[2]) && world.clearable(p[0], p[1], p[2])) toClear.add(key);
			}
		for (long key : targets) {
			int[] p = Lane.unpack(key);
			if (!world.solid(p[0], p[1], p[2])) placements.add(key);
		}
		int inZone = 0;
		for (long key : toClear) { int[] p = Lane.unpack(key); if (world.inZone(p[0], p[1], p[2])) inZone++; }

		String problem = validate(lanes, world.after(toClear, placements));
		double run = lanes.stream().mapToDouble(PlannedLane::seconds).sum();
		double effort = toClear.size() * cfg.weightPerClearedBlock + placements.size() * cfg.weightPerPlacedBlock
				+ inZone * cfg.weightPerMinedBlock;
		return new Design(List.copyOf(lanes), toClear, placements, inZone, run, run + effort, problem);
	}

	/**
	 * Checks lanes in a post-setup world: every lane must still work with its planned look direction and keep at
	 * least the minimum aim margin. Returns null if fine, else what's wrong.
	 */
	public String validate(List<PlannedLane> lanes, SolidQuery post) {
		for (int i = 0; i < lanes.size(); i++) {
			PlannedLane l = lanes.get(i);
			int bad = Lane.firstFailingWarp(l.spec(), l.direction(), params, post);
			if (bad != 0) return "lane " + (i + 1) + ", warp " + bad + " fails after the setup";
			Lane.Result r = Lane.evaluate(l.spec(), params, post, 3);
			if (!r.valid() || r.marginDeg() < minMarginDeg)
				return String.format("lane %d: aim margin only %.2f deg after the setup", i + 1, r.marginDeg());
		}
		return null;
	}

	/**
	 * The post-setup check after the bombs (SPEC §3.4 step 3): the world with every blasted and mined block gone
	 * and the target blocks that aren't there any more (or never were) placed. Blasts clear more than the design
	 * asked for, so this can remove natural targets (they become placements) or open new lines of sight.
	 *
	 * @param placements target blocks to place after the bombs, packed
	 * @param problem    null if every lane still works
	 */
	public record AfterBombs(Set<Long> placements, String problem) {
		public boolean valid() { return problem == null; }
	}

	public AfterBombs afterBombs(Design d, Set<Long> blasted) {
		SolidQuery bombed = world.after(blasted, Set.of());
		Set<Long> placements = new LinkedHashSet<>();
		int[] o = d.lanes().get(0).spec().origin(); // the block the leg starts on must survive too
		if (!bombed.solid(o[0], o[1], o[2])) placements.add(Lane.pack(o[0], o[1], o[2]));
		for (PlannedLane l : d.lanes())
			for (int k = 1; k <= l.spec().warps(); k++) {
				int[] t = l.spec().target(k);
				if (!bombed.solid(t[0], t[1], t[2])) placements.add(Lane.pack(t[0], t[1], t[2]));
			}
		return new AfterBombs(placements, validate(d.lanes(), world.after(blasted, placements)));
	}

	/** The path the bomb planner follows: the leg start, then every lane's end. */
	public static List<int[]> path(Design d) {
		List<int[]> out = new ArrayList<>();
		out.add(d.lanes().get(0).spec().origin());
		for (PlannedLane l : d.lanes()) out.add(l.spec().end());
		return out;
	}

	private static Vec3 vec(int[] p) { return new Vec3(p[0], p[1], p[2]); }

	private static double dist(int[] a, int[] b) {
		double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
