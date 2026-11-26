package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.LegDesigner;
import dev.nucleusrouter.core.setup.PlacementPlanner;
import dev.nucleusrouter.core.setup.SetupWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole lobby's setup plan (SPEC §3.4 "targets", roadmap 4.5). Pure logic; the caller supplies the threads.
 * <ol>
 *   <li><b>Design</b> every leg the run might use (each departure point to each structure, and home), in parallel.
 *       One time budget ({@code setup.lobbyTimeLimitMs}, 5 s) is shared out: each leg gets its slice, and no leg
 *       starts after the deadline.</li>
 *   <li><b>Order</b> the stops ({@link RouteOrder}) with, for each leg, whichever is faster: the route on the
 *       terrain as it is (from {@code /nr route}) or the designed tunnel.</li>
 *   <li><b>Setup work</b> for the legs that use a design, in route order: all bombs first (a blast would break
 *       placed blocks), then the placements against the terrain after <i>all</i> the bombs, each leg counting the
 *       blocks placed for earlier legs. Helpers and pillars must keep every lane of every leg working.</li>
 *   <li><b>Final check</b>: every designed lane against the world after all of it.</li>
 * </ol>
 */
public final class LobbySetup {
	/** Progress for the HUD: what's happening and how far. */
	public interface Progress { void update(String phase, int done, int total); }

	/** The setup of one leg that the route uses. */
	public record LegSetup(String from, String to, LegDesigner.Design design, BombPlanner.Plan bombs, PlacementPlanner.Plan placing) {
		public String key() { return from + ">" + to; }
	}

	/**
	 * @param plan          the run order (terrain or designed legs, whichever is faster)
	 * @param designs       every leg designed, by "from>to" (missing: none found in time)
	 * @param setups        the setup work, in route order (only legs that use a design)
	 * @param problems      lanes that fail the final check, or placements that couldn't be solved (empty = fine)
	 * @param legsWanted    legs to design
	 * @param legsTimedOut  legs whose search was cut short by the budget
	 */
	public record Result(RouteOrder.Plan plan, Map<String, LegDesigner.Design> designs, List<LegSetup> setups, Set<Long> blasted,
			Set<Long> placed, List<String> problems, int legsWanted, int legsTimedOut, double designMs, double orderMs,
			double bombsMs, double placeMs, double totalMs) {
		public boolean ok() { return problems.isEmpty() && plan.hasRoute(); }
		public int bombs() { return setups.stream().mapToInt(s -> s.bombs().bombs()).sum(); }
		public int mined() { return setups.stream().mapToInt(s -> s.bombs().mine().size()).sum(); }
		public int placements() { return setups.stream().mapToInt(s -> s.placing().steps().size()).sum(); }
	}

	private LobbySetup() {}

	/**
	 * @param terrain  seconds of legs already found on the existing terrain by "from>to" (may be empty)
	 * @param threads  how many legs run at once on {@code pool} (to share the budget out)
	 */
	public static Result plan(List<StopPlan.Stop> stops, SetupWorld world, Map<String, Double> terrain, NrConfig cfg, int pingMs,
			Executor pool, int threads, Progress progress) {
		long t0 = System.nanoTime();
		long budgetMs = cfg.setup.lobbyTimeLimitMs;
		// Leave a slice of the budget for ordering, bombs and placements
		long designBudgetMs = Math.max(200, (long) (budgetMs * 0.5));
		long designDeadline = t0 + designBudgetMs * 1_000_000L;
		Etherwarp.Params params = Etherwarp.Params.fromConfig(cfg);
		CostModel cost = new CostModel(cfg, pingMs);
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);

		// 1. Design the legs
		List<String[]> wanted = new ArrayList<>();
		for (String[] pair : RouteOrder.neededLegs(stops, cfg)) {
			StopPlan.Stop a = byId.get(pair[0]), b = byId.get(pair[1]);
			if (a != null && b != null && a.ready() && b.ready()) wanted.add(pair);
		}
		long perLegMs = Math.max(100, designBudgetMs * Math.max(1, threads) / Math.max(1, wanted.size()));
		ThreadLocal<LegDesigner> designers = ThreadLocal.withInitial(() -> new LegDesigner(world, params, cost, cfg));
		Map<String, LegDesigner.Design> designs = new ConcurrentHashMap<>();
		AtomicInteger done = new AtomicInteger(), timedOut = new AtomicInteger();
		progress.update("designing legs", 0, wanted.size());
		List<CompletableFuture<Void>> jobs = new ArrayList<>();
		for (String[] pair : wanted) {
			jobs.add(CompletableFuture.runAsync(() -> {
				long left = (designDeadline - System.nanoTime()) / 1_000_000L;
				if (left > 20) {
					LegDesigner.Result r = designers.get().design(block(byId.get(pair[0])), block(byId.get(pair[1])), Math.min(perLegMs, left));
					if (r.timedOut()) timedOut.incrementAndGet();
					if (r.best() != null) designs.put(pair[0] + ">" + pair[1], r.best());
				} else {
					timedOut.incrementAndGet();
				}
				progress.update("designing legs", done.incrementAndGet(), wanted.size());
			}, pool));
		}
		CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).join();
		double designMs = (System.nanoTime() - t0) / 1e6;

		// 2. Order: per leg, the faster of the terrain route and the designed tunnel
		long t1 = System.nanoTime();
		progress.update("ordering", 0, 1);
		Map<String, Boolean> useDesign = new HashMap<>();
		RouteOrder.Plan plan = RouteOrder.solve(stops, (a, b) -> {
			String k = a + ">" + b;
			double t = terrain.getOrDefault(k, Double.POSITIVE_INFINITY);
			LegDesigner.Design d = designs.get(k);
			double ds = d != null ? d.runSeconds() : Double.POSITIVE_INFINITY;
			return Math.min(t, ds);
		}, cfg);
		List<String[]> used = new ArrayList<>();
		for (RouteOrder.Step s : plan.steps()) {
			String from = s.viaWarpNucleus() ? "nucleus" : s.fromId();
			if (s.toId().equals("nucleus") && s.viaWarpNucleus()) continue;
			String k = from + ">" + s.toId();
			LegDesigner.Design d = designs.get(k);
			if (d != null && d.runSeconds() < terrain.getOrDefault(k, Double.POSITIVE_INFINITY)) used.add(new String[]{from, s.toId()});
		}
		// Legs that meet at a stop can get in each other's way: a target block of one leg on the ray of another.
		// Such a leg is designed again with the other legs' targets as fixed blocks (once; left over clashes
		// are reported by the final check).
		List<String> problems = new ArrayList<>();
		SolidQuery ideal = world.ideal();
		LegDesigner checker = new LegDesigner(world, params, cost, cfg);
		for (String[] pair : used) {
			String k = pair[0] + ">" + pair[1];
			Set<Long> others = new HashSet<>();
			for (String[] o : used) if (o != pair) others.addAll(targets(designs.get(o[0] + ">" + o[1]).lanes()));
			Set<Long> own = targets(designs.get(k).lanes());
			SolidQuery withOthers = (x, y, z) -> { long q = Lane.pack(x, y, z); return others.contains(q) || own.contains(q) || ideal.solid(x, y, z); };
			if (checker.validate(designs.get(k).lanes(), withOthers) == null) continue;
			SetupWorld fixed = fixedBlocks(world, others);
			LegDesigner.Result again = new LegDesigner(fixed, params, cost, cfg).design(block(byId.get(pair[0])), block(byId.get(pair[1])), perLegMs);
			if (again.best() != null) designs.put(k, again.best());
			else problems.add(name(byId, k) + ": in the way of another leg's target blocks, and no other design found");
		}
		double orderMs = (System.nanoTime() - t1) / 1e6;

		// 3a. Bombs for every used leg (independent, in parallel), then all blasts together
		long t2 = System.nanoTime();
		progress.update("bombs", 0, used.size());
		Map<String, BombPlanner.Plan> bombs = new ConcurrentHashMap<>();
		AtomicInteger bombsDone = new AtomicInteger();
		List<CompletableFuture<Void>> bjobs = new ArrayList<>();
		for (String[] pair : used) {
			bjobs.add(CompletableFuture.runAsync(() -> {
				LegDesigner.Design d = designs.get(pair[0] + ">" + pair[1]);
				bombs.put(pair[0] + ">" + pair[1], new BombPlanner(world, cfg).plan(block(byId.get(pair[0])), LegDesigner.path(d), d.toClear()));
				progress.update("bombs", bombsDone.incrementAndGet(), used.size());
			}, pool));
		}
		CompletableFuture.allOf(bjobs.toArray(new CompletableFuture[0])).join();
		Set<Long> blasted = new HashSet<>();
		for (BombPlanner.Plan b : bombs.values()) blasted.addAll(b.blasted());
		double bombsMs = (System.nanoTime() - t2) / 1e6;

		// 3b. Placements in route order, after all bombs, counting earlier legs' blocks; every lane must keep working
		long t3 = System.nanoTime();
		List<LegDesigner.Design> usedDesigns = new ArrayList<>();
		for (String[] pair : used) usedDesigns.add(designs.get(pair[0] + ">" + pair[1]));
		List<PlacementPlanner.Plan> placing = placeAll(usedDesigns, world, blasted, cfg, pingMs, i -> progress.update("placing", i, used.size()));
		Set<Long> placed = new LinkedHashSet<>();
		List<LegSetup> setups = new ArrayList<>();
		for (int i = 0; i < used.size(); i++) {
			String[] pair = used.get(i);
			String k = pair[0] + ">" + pair[1];
			PlacementPlanner.Plan p = placing.get(i);
			for (PlacementPlanner.Step st : p.steps()) placed.add(Lane.pack(st.block()[0], st.block()[1], st.block()[2]));
			if (!p.impossible().isEmpty()) problems.add(name(byId, k) + ": " + p.impossible().size() + " block(s) can't be placed");
			setups.add(new LegSetup(pair[0], pair[1], designs.get(k), bombs.get(k), p));
		}
		double placeMs = (System.nanoTime() - t3) / 1e6;

		// 4. Final check: every designed lane in the world after all the setup work
		SolidQuery post = world.after(blasted, placed);
		for (LegSetup s : setups) {
			String bad = checker.validate(s.design().lanes(), post);
			if (bad != null) problems.add(name(byId, s.key()) + ": " + bad);
		}
		progress.update("done", 1, 1);
		return new Result(plan, Map.copyOf(designs), List.copyOf(setups), blasted, placed, List.copyOf(problems), wanted.size(),
				timedOut.get(), designMs, orderMs, bombsMs, placeMs, (System.nanoTime() - t0) / 1e6);
	}

	/**
	 * Placements for designed legs, in order, against {@code world} with {@code blasted} gone: each leg's target
	 * blocks that aren't there, counting the blocks placed for earlier legs. A helper or pillar may not break a lane
	 * that works without it (checked against all legs' lanes, with every target in place; only lanes near the new
	 * blocks are re-checked). Also used by the guided setup to re-plan the placing against the real terrain once
	 * every bomb is done ({@code blasted} empty, {@code world} = the terrain now).
	 */
	public static List<PlacementPlanner.Plan> placeAll(List<LegDesigner.Design> legs, SetupWorld world, Set<Long> blasted, NrConfig cfg, int pingMs,
			java.util.function.IntConsumer progress) {
		Etherwarp.Params params = Etherwarp.Params.fromConfig(cfg);
		CostModel cost = new CostModel(cfg, pingMs);
		LegDesigner checker = new LegDesigner(world, params, cost, cfg);
		List<LegDesigner.PlannedLane> allLanes = new ArrayList<>();
		for (LegDesigner.Design d : legs) allLanes.addAll(d.lanes());
		Set<Long> allTargets = targets(allLanes);
		SolidQuery baseline = world.after(blasted, allTargets);
		List<LegDesigner.PlannedLane> working = new ArrayList<>();
		for (LegDesigner.PlannedLane l : allLanes) if (checker.validate(List.of(l), baseline) == null) working.add(l);
		Set<Long> placed = new LinkedHashSet<>();
		List<PlacementPlanner.Plan> out = new ArrayList<>();
		for (int i = 0; i < legs.size(); i++) {
			progress.accept(i);
			LegDesigner.Design d = legs.get(i);
			SetupWorld now = world.changed(blasted, Set.copyOf(placed));
			LegDesigner.AfterBombs after = new LegDesigner(now, params, cost, cfg).afterBombs(d, Set.of());
			PlacementPlanner.Plan p = new PlacementPlanner(now, cfg).plan(LegDesigner.path(d), after.placements(), Set.of(),
					(post, added) -> {
						List<LegDesigner.PlannedLane> near = new ArrayList<>();
						for (LegDesigner.PlannedLane l : working) if (near(l, added)) near.add(l);
						return near.isEmpty() || checker.validate(near, (x, y, z) -> allTargets.contains(Lane.pack(x, y, z)) || post.solid(x, y, z)) == null;
					});
			for (PlacementPlanner.Step st : p.steps()) placed.add(Lane.pack(st.block()[0], st.block()[1], st.block()[2]));
			out.add(p);
		}
		return out;
	}

	/**
	 * A block could touch the lane: inside the box from its start to its end, grown by 3 blocks (rays start at
	 * eye height above the landings, and the aim region reaches a little past the targets).
	 */
	static boolean near(LegDesigner.PlannedLane l, Set<Long> blocks) {
		int[] a = l.spec().origin(), e = l.spec().end();
		int x0 = Math.min(a[0], e[0]) - 3, x1 = Math.max(a[0], e[0]) + 3;
		int y0 = Math.min(a[1], e[1]) - 3, y1 = Math.max(a[1], e[1]) + 5;
		int z0 = Math.min(a[2], e[2]) - 3, z1 = Math.max(a[2], e[2]) + 3;
		for (long k : blocks) {
			int[] b = Lane.unpack(k);
			if (b[0] >= x0 && b[0] <= x1 && b[1] >= y0 && b[1] <= y1 && b[2] >= z0 && b[2] <= z1) return true;
		}
		return false;
	}

	/** Every lane's start block and targets, packed. */
	static Set<Long> targets(List<LegDesigner.PlannedLane> lanes) {
		Set<Long> out = new HashSet<>();
		for (LegDesigner.PlannedLane l : lanes)
			for (int k = 0; k <= l.spec().warps(); k++) {
				int[] t = l.spec().target(k);
				out.add(Lane.pack(t[0], t[1], t[2]));
			}
		return out;
	}

	/** {@code world} with {@code blocks} as solid blocks the setup must not remove (other legs' targets). */
	static SetupWorld fixedBlocks(SetupWorld world, Set<Long> blocks) {
		return new SetupWorld() {
			@Override public dev.nucleusrouter.core.world.VoxelClass classAt(int x, int y, int z) {
				return blocks.contains(Lane.pack(x, y, z)) ? dev.nucleusrouter.core.world.VoxelClass.UNBREAKABLE : world.classAt(x, y, z);
			}
			@Override public boolean inZone(int x, int y, int z) { return world.inZone(x, y, z); }
			@Override public boolean explored(int x, int z) { return world.explored(x, z); }
		};
	}

	/** The block under a stop's arrival spot. */
	static int[] block(StopPlan.Stop s) {
		Vec3 f = s.standFeet();
		return new int[]{(int) Math.floor(f.x()), (int) Math.floor(f.y()) - 1, (int) Math.floor(f.z())};
	}

	private static String name(Map<String, StopPlan.Stop> byId, String key) {
		String[] p = key.split(">");
		return label(byId, p[0]) + " to " + label(byId, p[1]);
	}

	private static String label(Map<String, StopPlan.Stop> byId, String id) {
		StopPlan.Stop s = byId.get(id);
		return s != null ? s.label() : id;
	}
}
