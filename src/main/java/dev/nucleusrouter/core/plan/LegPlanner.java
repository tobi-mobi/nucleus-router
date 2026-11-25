package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.AimRegion;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Leg planner A (SPEC §3.3): the cheapest chain of etherwarps over the existing terrain from a start spot
 * to anywhere within {@code goalRadius} of a goal point.
 * <ul>
 *   <li><b>Where a warp can go</b>: from each spot, rays are cast like a real etherwarp, in an even spread
 *       over all directions plus a denser cone toward the goal. Every landable block a ray hits first is a
 *       candidate (a "landing node"); unseen blocks and respawning blocks are not targets (see
 *       {@link PlanWorld}).</li>
 *   <li><b>Search state</b> = (landing block, direction bucket you arrive facing), so turns are priced by
 *       {@link CostModel}. A* with the admissible heuristic {@code ceil(distance / reach) * baseWarp}.</li>
 *   <li><b>Lazy edges</b>: a candidate warp first enters the queue at an optimistic price (no miss risk).
 *       Only when it reaches the front is its real aim region measured ({@link AimRegion}, the expensive
 *       part); if the real price is higher it goes back in the queue. Warps with an aim margin below the
 *       minimum are dropped.</li>
 *   <li><b>Caches</b>: ray fans and aim regions are cached per spot/target. Each cache entry remembers the
 *       16x16x16 sections its rays read; {@link #invalidate} drops only the entries that read the changed
 *       block's section.</li>
 * </ul>
 * Second kind of move (user 2026-11-25): a stretch of normal Aspect of the Void teleports (turn roughly, then
 * spam clicks along one direction), priced by {@link CostModel#teleportSeconds}. It needs no aim precision, so
 * it wins whenever the aim has to change; constant-aim etherwarp chains still win when they exist.
 * <p>
 * One planner instance is used by one thread at a time (a worker thread, never the client thread).
 */
public final class LegPlanner {
	public record Settings(int sphereRays, int coneRays, double coneDeg, int aimSamplesPerFace, double minMarginDeg,
			int maxExpansions, long timeLimitMs, double minHopBlocks, double typicalHopBlocks,
			boolean teleport, double teleportBlocks, int teleportMaxClicks) {
		public static Settings fromConfig(NrConfig cfg) {
			return fromConfig(cfg, cfg.plan.legTimeLimitMs);
		}

		/** From the config, with a different time limit per leg (route planning runs in the background and may take longer). */
		public static Settings fromConfig(NrConfig cfg, long timeLimitMs) {
			NrConfig.Plan p = cfg.plan;
			return new Settings(p.sphereRays, p.coneRays, p.coneDegrees, p.aimSamplesPerFace, p.minAimMarginDeg,
					p.maxExpansions, timeLimitMs, 3.0, p.typicalHopBlocks,
					cfg.teleport.enabled, cfg.teleport.blocksPerClick, cfg.teleport.maxClicks);
		}
	}

	/**
	 * One planned warp.
	 *
	 * @param aimPoint  the most forgiving point on the target to aim at
	 * @param aimDir    direction from the eye to {@code aimPoint}
	 * @param marginDeg how far aim may drift from {@code aimDir} and still land
	 * @param turnDeg   turn from the previous look direction (0 for the first warp if no start look given)
	 */
	public record Warp(Vec3 fromFeet, int tx, int ty, int tz, Vec3 aimPoint, Vec3 aimDir, Vec3 landingFeet,
			double marginDeg, double turnDeg, double missChance, double seconds, Move move, int clicks) {
		/** An etherwarp. */
		public Warp(Vec3 fromFeet, int tx, int ty, int tz, Vec3 aimPoint, Vec3 aimDir, Vec3 landingFeet,
				double marginDeg, double turnDeg, double missChance, double seconds) {
			this(fromFeet, tx, ty, tz, aimPoint, aimDir, landingFeet, marginDeg, turnDeg, missChance, seconds, Move.ETHERWARP, 0);
		}

		public boolean isTeleport() { return move == Move.TELEPORT; }
	}

	/**
	 * How a move is made: an etherwarp onto a target block, or a stretch of normal Aspect of the Void teleports
	 * ({@code clicks} times along {@code aimDir}; {@code tx..tz} is then the block your feet end in).
	 */
	public enum Move { ETHERWARP, TELEPORT }

	public enum Status { FOUND, ALREADY_THERE, NO_PATH, GAVE_UP }

	/**
	 * Result of one leg. {@code warps} is empty unless FOUND. {@code firstMicros} is how long the first route
	 * took to find; {@code micros} includes the refinement passes.
	 */
	public record Leg(Status status, List<Warp> warps, double seconds, int expansions, int edgesPriced, long micros, long firstMicros) {
		public boolean ok() { return status == Status.FOUND || status == Status.ALREADY_THERE; }
	}

	// ------------------------------------------------------------------ setup

	private PlanWorld world;
	private final Etherwarp.Params params;
	private final CostModel cost;
	private final Settings settings;
	private final Vec3[] sphere, cone, tpSphere, tpCone;
	/** Max distance the feet can move in one warp: range plus eye height plus standing on top. */
	private final double reach;

	public LegPlanner(PlanWorld world, Etherwarp.Params params, CostModel cost, Settings settings) {
		this.world = world;
		this.params = params;
		this.cost = cost;
		this.settings = settings;
		this.sphere = Directions.sphere(settings.sphereRays());
		this.cone = Directions.cone(settings.coneRays(), settings.coneDeg());
		this.tpSphere = Directions.sphere(48);
		this.tpCone = Directions.cone(32, 25);
		this.reach = params.range() + params.eyeHeight() + 2;
	}

	/** Swap in a newer snapshot. Call {@link #invalidate} for every block that changed between them. */
	public void setWorld(PlanWorld w) { this.world = w; }

	// ------------------------------------------------------------------ caches

	private record FanKey(long node) {}
	private record ConeKey(long node, long goal) {}
	private record AimKey(long node, long target) {}
	private record Candidate(int x, int y, int z, Vec3 dir) {}
	private record TpKey(long node, long goal) {}
	/** One reachable point of a teleport stretch: {@code clicks} clicks along {@code dir}. */
	private record Hop(Vec3 feet, Vec3 dir, int clicks) {}

	private final Map<Object, Object> cache = new HashMap<>();
	private final Map<Long, Set<Object>> bySection = new HashMap<>();
	private int cacheHits, cacheMisses;

	/** Forget every cached ray result that read block (x, y, z)'s section. */
	public void invalidate(int x, int y, int z) {
		Set<Object> keys = bySection.remove(section(x >> 4, y >> 4, z >> 4));
		if (keys != null) for (Object k : keys) cache.remove(k);
	}

	public int cacheSize() { return cache.size(); }
	public int cacheHits() { return cacheHits; }
	public int cacheMisses() { return cacheMisses; }

	/** A solid query that records which sections it reads, so the entry can be invalidated later. */
	private final class Recorder implements SolidQuery {
		final Set<Long> sections = new HashSet<>();
		long last = Long.MIN_VALUE;

		@Override public boolean solid(int x, int y, int z) {
			long s = section(x >> 4, y >> 4, z >> 4);
			if (s != last) { sections.add(s); last = s; }
			return world.solid(x, y, z);
		}

		void index(Object key) {
			for (long s : sections) bySection.computeIfAbsent(s, k -> new HashSet<>()).add(key);
		}
	}

	private static long section(int sx, int sy, int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
	}

	static long blockKey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	@SuppressWarnings("unchecked")
	private List<Candidate> fan(Object key, Vec3 eye, Vec3[] dirs, Vec3 axis) {
		Object hit = cache.get(key);
		if (hit != null) { cacheHits++; return (List<Candidate>) hit; }
		cacheMisses++;
		Recorder rec = new Recorder();
		Map<Long, Candidate> found = new LinkedHashMap<>();
		for (Vec3 d0 : dirs) {
			Vec3 d = axis == null ? d0 : Directions.rotateTo(d0, axis);
			VoxelRay.Hit h = VoxelRay.cast(eye, d, params.range(), rec);
			if (h == null) continue;
			found.putIfAbsent(blockKey(h.x(), h.y(), h.z()), new Candidate(h.x(), h.y(), h.z(), d));
		}
		List<Candidate> out = List.copyOf(found.values());
		cache.put(key, out);
		rec.index(key);
		return out;
	}

	private record GoalKey(long node, long goal) {}

	/** Rays from {@code eye} at the top of every block within {@code goalRadius} of the goal (cached). */
	@SuppressWarnings("unchecked")
	private List<Candidate> goalRays(long node, long goalKey, Vec3 eye, Vec3 goal, double goalRadius) {
		GoalKey key = new GoalKey(node, goalKey);
		Object hit = cache.get(key);
		if (hit != null) { cacheHits++; return (List<Candidate>) hit; }
		cacheMisses++;
		Recorder rec = new Recorder();
		Map<Long, Candidate> found = new LinkedHashMap<>();
		int r = (int) Math.ceil(goalRadius);
		int gx = (int) Math.floor(goal.x()), gy = (int) Math.floor(goal.y()), gz = (int) Math.floor(goal.z());
		for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r - 1; dy <= r - 1; dy++) {
			int x = gx + dx, y = gy + dy, z = gz + dz;
			if (Etherwarp.landingFeet(x, y, z).sub(goal).length() > goalRadius) continue;
			Vec3 d = new Vec3(x + 0.5, y + 1, z + 0.5).sub(eye);
			if (d.length() < 1e-6 || d.length() > params.range() + 1) continue;
			d = d.normalize();
			VoxelRay.Hit h = VoxelRay.cast(eye, d, params.range(), rec);
			if (h != null && h.isBlock(x, y, z)) found.putIfAbsent(blockKey(x, y, z), new Candidate(x, y, z, d));
		}
		List<Candidate> out = List.copyOf(found.values());
		cache.put(key, out);
		rec.index(key);
		return out;
	}

	/** Teleport nodes are told apart from etherwarp landing blocks in the search state. */
	private static final long TP_NODE_SALT = 0x5A5A_0000_0000_0000L;
	/** Clicks along one direction that become search nodes (plus the last one). */
	private static final int[] HOP_CLICKS = {1, 2, 3, 4, 6, 8, 10, 14, 20};

	/** Points reachable by normal teleport stretches from {@code feet} (cached per spot and goal). */
	@SuppressWarnings("unchecked")
	private List<Hop> hops(long node, long goalKey, Vec3 feet, Vec3 toGoal) {
		TpKey key = new TpKey(node, goalKey);
		Object hit = cache.get(key);
		if (hit != null) { cacheHits++; return (List<Hop>) hit; }
		cacheMisses++;
		Recorder rec = new Recorder();
		List<Vec3> dirs = new ArrayList<>(List.of(tpSphere));
		if (toGoal.length() > 1e-6) {
			Vec3 axis = toGoal.normalize();
			dirs.add(axis);
			for (Vec3 d : tpCone) dirs.add(Directions.rotateTo(d, axis));
		}
		List<Hop> out = new ArrayList<>();
		for (Vec3 d : dirs) {
			List<Vec3> pts = dev.nucleusrouter.core.etherwarp.NormalTeleport.stretch(feet, d, settings.teleportBlocks(), settings.teleportMaxClicks(), rec);
			for (int k = 1; k <= pts.size(); k++) {
				boolean keep = k == pts.size();
				for (int c : HOP_CLICKS) if (c == k) keep = true;
				if (keep && pts.get(k - 1).sub(feet).length() >= settings.minHopBlocks()) out.add(new Hop(pts.get(k - 1), d, k));
			}
		}
		List<Hop> copy = List.copyOf(out);
		cache.put(key, copy);
		rec.index(key);
		return copy;
	}

	private AimRegion.Result aim(long node, Vec3 eye, int tx, int ty, int tz) {
		AimKey key = new AimKey(node, blockKey(tx, ty, tz));
		Object hit = cache.get(key);
		if (hit != null) { cacheHits++; return (AimRegion.Result) hit; }
		cacheMisses++;
		Recorder rec = new Recorder();
		AimRegion.Result r = AimRegion.compute(eye, tx, ty, tz, params, rec, settings.aimSamplesPerFace());
		cache.put(key, r);
		rec.index(key);
		return r;
	}

	// ------------------------------------------------------------------ search

	private record State(long node, int bucket) {}

	private static final class Entry {
		final double f, g;
		/** Remaining distance to the goal: tie-breaker between equal prices (closest first). */
		final double left;
		final State state;
		final Entry parent;
		final Warp warp;       // null for the start
		final boolean priced;  // real aim region measured
		final Candidate cand;  // for unpriced entries
		final Vec3 feet, look;

		Entry(double f, double g, double left, State state, Entry parent, Warp warp, boolean priced, Candidate cand, Vec3 feet, Vec3 look) {
			this.f = f; this.g = g; this.left = left; this.state = state; this.parent = parent; this.warp = warp;
			this.priced = priced; this.cand = cand; this.feet = feet; this.look = look;
		}
	}

	/** The best aim point is within this many degrees of the ray that found the target (faces are small). */
	static final double OPTIMISTIC_TURN_SLACK_DEG = 10;

	/**
	 * Estimated time left: {@code distance / typicalHop * baseWarp}. With {@code typicalHop} at the full reach
	 * this is the spec's admissible heuristic (always the cheapest route, but slow: real hops are often much
	 * shorter than the 61-block range, e.g. ~10 blocks onto flat floor). The default 30 steers the search
	 * toward the goal and finds a good route quickly; it may occasionally miss a slightly cheaper one.
	 */
	private CoarseField field;

	private double heuristic(Vec3 feet, Vec3 goal, double goalRadius, double hop) {
		double dist = field != null ? field.distance(feet) : feet.sub(goal).length();
		double d = Math.max(0, dist - goalRadius);
		return d / Math.min(reach, hop) * cost.baseSeconds();
	}

	private static double turn(Vec3 from, Vec3 to) {
		return from == null ? 0 : from.angleDeg(to);
	}

	/**
	 * Cheapest warp chain from {@code startFeet} (facing {@code startLook}, may be null) to any spot whose feet
	 * are within {@code goalRadius} of {@code goal}.
	 * <p>
	 * Anytime search: a first, very goal-directed pass (typical hop 8 blocks) finds a good route fast; while
	 * time is left, passes with longer typical hops (16, then {@code typicalHopBlocks}) look more carefully,
	 * reusing the caches. The cheapest route found is returned.
	 */
	public Leg plan(Vec3 startFeet, Vec3 startLook, Vec3 goal, double goalRadius) {
		long t0 = System.nanoTime();
		if (startFeet.sub(goal).length() <= goalRadius)
			return new Leg(Status.ALREADY_THERE, List.of(), 0, 0, 0, (System.nanoTime() - t0) / 1000, 0);
		long deadline = t0 + settings.timeLimitMs() * 1_000_000L;
		field = CoarseField.build(startFeet, goal, world); // steer around walls
		double finalHop = Math.min(reach, settings.typicalHopBlocks());
		Leg best = null, last = null;
		int expansions = 0, priced = 0, cap = settings.maxExpansions();
		long firstMicros = -1;
		double prevHop = 0;
		for (double hop : new double[]{8, 16, finalHop}) {
			hop = Math.min(hop, finalHop);
			if (hop <= prevHop) continue;
			prevHop = hop;
			if (System.nanoTime() > deadline) break;
			last = search(startFeet, startLook, goal, goalRadius, hop, deadline, cap);
			expansions += last.expansions();
			priced += last.edgesPriced();
			if (firstMicros < 0 && last.status() == Status.FOUND) firstMicros = (System.nanoTime() - t0) / 1000;
			// Refinement passes get a bounded effort (relative to the first pass), so a leg can't eat the whole budget
			if (best == null) cap = Math.min(settings.maxExpansions(), Math.max(REFINE_MIN_EXPANSIONS, REFINE_FACTOR * last.expansions()));
			if (last.status() == Status.FOUND && (best == null || last.seconds() < best.seconds() - 1e-9)) best = last;
			if (last.status() == Status.NO_PATH) break; // searched everything reachable: no route
			if (best == null) break;                    // even the quick pass gave up
		}
		long micros = (System.nanoTime() - t0) / 1000;
		if (best != null) return new Leg(Status.FOUND, best.warps(), best.seconds(), expansions, priced, micros, firstMicros);
		return new Leg(last == null ? Status.GAVE_UP : last.status(), List.of(), 0, expansions, priced, micros, -1);
	}

	static final int REFINE_FACTOR = 4, REFINE_MIN_EXPANSIONS = 300;

	private Leg search(Vec3 startFeet, Vec3 startLook, Vec3 goal, double goalRadius, double hop, long deadline, int maxExpansions) {
		long t0 = System.nanoTime();

		long goalKey = blockKey((int) Math.floor(goal.x()), (int) Math.floor(goal.y()), (int) Math.floor(goal.z()));
		// Ties are common (many spots cost the same warp); break them toward the goal
		PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> {
			int c = Double.compare(a.f, b.f);
			return c != 0 ? c : Double.compare(a.left, b.left);
		});
		Map<State, Double> closed = new HashMap<>();
		Map<Long, Double> bestAtNode = new HashMap<>();
		double dominanceSlack = cost.turnSeconds(90);
		long startNode = Long.MIN_VALUE; // the start isn't a landing block
		Vec3 startLookN = startLook == null ? null : startLook.normalize();
		open.add(new Entry(heuristic(startFeet, goal, goalRadius, hop), 0, startFeet.sub(goal).length(), new State(startNode, Directions.bucket(startLookN)), null, null, true, null, startFeet, startLookN));

		int expansions = 0, priced = 0;
		while (!open.isEmpty()) {
			Entry e = open.poll();
			if (!e.priced) {
				// Measure the real aim region now; re-queue at the real price
				Candidate c = e.cand;
				Entry p = e.parent;
				Vec3 eye = Etherwarp.eye(p.feet, params.eyeHeight());
				AimRegion.Result a = aim(p.state.node(), eye, c.x(), c.y(), c.z());
				priced++;
				if (!a.reachable() || a.bestDirection() == null || a.bestMarginDegrees() < settings.minMarginDeg()) continue;
				double turnDeg = turn(p.look, a.bestDirection());
				double miss = cost.missProbability(turnDeg, a.bestMarginDegrees());
				double secs = cost.warpSeconds(turnDeg, a.bestMarginDegrees());
				Vec3 land = Etherwarp.landingFeet(c.x(), c.y(), c.z());
				Warp w = new Warp(p.feet, c.x(), c.y(), c.z(), a.bestPoint(), a.bestDirection(), land, a.bestMarginDegrees(), turnDeg, miss, secs);
				double g = p.g + secs;
				open.add(new Entry(g + heuristic(land, goal, goalRadius, hop), g, land.sub(goal).length(), new State(blockKey(c.x(), c.y(), c.z()), Directions.bucket(a.bestDirection())),
						p, w, true, null, land, a.bestDirection()));
				continue;
			}
			Double seen = closed.get(e.state);
			if (seen != null && seen <= e.g + 1e-9) continue;
			// Arriving facing another way can only save turn time later; if this spot was already reached
			// cheaper by more than a 90-degree turn costs, this arrival can't win
			Double nodeBest = bestAtNode.get(e.state.node());
			if (nodeBest != null && e.g > nodeBest + dominanceSlack) continue;
			closed.put(e.state, e.g);
			if (nodeBest == null || e.g < nodeBest) bestAtNode.put(e.state.node(), e.g);

			if (e.warp != null && e.feet.sub(goal).length() <= goalRadius) {
				List<Warp> warps = new ArrayList<>();
				for (Entry x = e; x != null && x.warp != null; x = x.parent) warps.add(x.warp);
				Collections.reverse(warps);
				return new Leg(Status.FOUND, warps, e.g, expansions, priced, (System.nanoTime() - t0) / 1000, -1);
			}
			if (++expansions > maxExpansions || System.nanoTime() > deadline)
				return new Leg(Status.GAVE_UP, List.of(), 0, expansions, priced, (System.nanoTime() - t0) / 1000, -1);

			// Candidate warps from here: even fan + cone toward the goal
			Vec3 eye = Etherwarp.eye(e.feet, params.eyeHeight());
			long node = e.state.node() == Long.MIN_VALUE ? blockKey((int) Math.floor(e.feet.x()), (int) Math.floor(e.feet.y()) - 1, (int) Math.floor(e.feet.z())) : e.state.node();
			Vec3 toGoal = goal.sub(eye);
			List<Candidate> cands = new ArrayList<>();
			// Goal in range: aim straight at the blocks the goal stands on, so the last hop isn't left to chance
			if (toGoal.length() <= params.range() + goalRadius + 2) cands.addAll(goalRays(node, goalKey, eye, goal, goalRadius));
			cands.addAll(fan(new FanKey(node), eye, sphere, null));
			if (toGoal.length() > 1e-6) cands.addAll(fan(new ConeKey(node, goalKey), eye, cone, toGoal.normalize()));
			// One candidate per 2x2x2 cell of landing spots (near-duplicates add cost, not options)
			Set<Long> dedupe = new HashSet<>();
			for (Candidate c : cands) {
				if (!dedupe.add(blockKey(c.x() >> 1, c.y() >> 1, c.z() >> 1))) continue;
				if (!world.goodTarget(c.x(), c.y(), c.z())) continue;
				if (!Etherwarp.hasRoomAbove(c.x(), c.y(), c.z(), params.requiredAirAbove(), world)) continue;
				Vec3 land = Etherwarp.landingFeet(c.x(), c.y(), c.z());
				if (land.sub(e.feet).length() < settings.minHopBlocks()) continue;
				// Lower bound: no miss risk, and the real aim point may be a few degrees closer than this ray
				double lowTurn = Math.max(0, turn(e.look, c.dir()) - OPTIMISTIC_TURN_SLACK_DEG);
				double optimistic = e.g + (lowTurn > 0 ? cost.warpSeconds(lowTurn, 180) : cost.baseSeconds());
				open.add(new Entry(optimistic + heuristic(land, goal, goalRadius, hop), optimistic, land.sub(goal).length(), null, e, null, false, c, null, null));
			}

			// Normal teleport stretches: priced exactly right away (no aim region needed)
			if (settings.teleport()) {
				Set<Long> seenTp = new HashSet<>();
				for (Hop h : hops(node, goalKey, e.feet, toGoal)) {
					Vec3 f = h.feet();
					if (!seenTp.add(blockKey((int) Math.floor(f.x()) >> 1, (int) Math.floor(f.y()) >> 1, (int) Math.floor(f.z()) >> 1))) continue;
					double turnDeg = turn(e.look, h.dir());
					double secs = cost.teleportSeconds(turnDeg, h.clicks());
					int fx = (int) Math.floor(f.x()), fy = (int) Math.floor(f.y()), fz = (int) Math.floor(f.z());
					Warp w = new Warp(e.feet, fx, fy, fz, null, h.dir(), f, 0, turnDeg, 0, secs, Move.TELEPORT, h.clicks());
					double g = e.g + secs;
					open.add(new Entry(g + heuristic(f, goal, goalRadius, hop), g, f.sub(goal).length(),
							new State(blockKey(fx, fy, fz) ^ TP_NODE_SALT, Directions.bucket(h.dir())), e, w, true, null, f, h.dir()));
				}
			}
		}
		return new Leg(Status.NO_PATH, List.of(), 0, expansions, priced, (System.nanoTime() - t0) / 1000, -1);
	}
}
