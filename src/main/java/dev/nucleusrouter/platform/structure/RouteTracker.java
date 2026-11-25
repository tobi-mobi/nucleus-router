package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.game.RouteOrder;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.debug.DebugRay;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plans the run (roadmap 3.4): stops (one per structure), every leg between them with the leg planner
 * (in parallel, one planner per worker thread, on one immutable terrain snapshot), then the fastest order.
 * Started with {@code /nr route}; never on the client thread.
 */
public final class RouteTracker {
	/** Arrival anywhere within this many blocks of a stop's spot is fine (you can walk the rest). */
	static final double ARRIVE_RADIUS = 2.5;

	/**
	 * A planned run: the order plus the etherwarp chain of each leg. Legs that couldn't be planned on the
	 * terrain (not explored yet, or too hard) are in {@code estimated} with a rough straight-line time.
	 */
	public record Route(RouteOrder.Plan plan, List<StopPlan.Stop> stops, Map<String, LegPlanner.Leg> legs,
			Map<String, Double> estimated, String lobby, long millis) {
		public LegPlanner.Leg leg(String from, String to) { return legs.get(from + ">" + to); }
		public boolean isEstimated(String from, String to) { return estimated.containsKey(from + ">" + to); }
	}

	private static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
		Thread t = new Thread(r, "NucleusRouter-Route");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private static volatile Route current;
	private static volatile boolean running;

	private RouteTracker() {}

	public static Route current() { return current; }
	public static boolean running() { return running; }

	/** Ping for the cost model: the measured click-to-teleport delay if there is one, else the location's ping. */
	private static int pingMs() {
		long measured = DebugRay.stats().medianDelayMs();
		return measured > 0 ? (int) measured : LocationTracker.state().pingMs();
	}

	/** Starts planning; {@code done} gets a readable result on the client thread. */
	public static boolean start(java.util.function.Consumer<Route> done) {
		if (running || !LocationTracker.state().inHollows()) return false;
		running = true;
		NrConfig cfg = ConfigManager.get();
		StructureMap map = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(StructureTracker.map()), StructureMap.class);
		var layout = KeeperLayouts.layout();
		String lobby = StructureTracker.lobby();
		int ping = pingMs();
		var visited = ExplorationTracker.coverage().visitedCopy();
		PlanWorld.Columns explored = cfg.plan.onlyExploredTerrain ? visited::test : (x, z) -> true;
		long t0 = System.currentTimeMillis();
		WorldCapture.snapshot().thenComposeAsync(snap -> plan(snap, map, layout, cfg, ping, explored), POOL)
				.whenComplete((plan, err) -> Minecraft.getInstance().execute(() -> {
					running = false;
					if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
					Route r = new Route(plan.plan(), plan.stops(), plan.legs(), plan.estimated(), lobby, System.currentTimeMillis() - t0);
					current = r;
					done.accept(r);
				}));
		return true;
	}

	private record Planned(RouteOrder.Plan plan, List<StopPlan.Stop> stops, Map<String, LegPlanner.Leg> legs, Map<String, Double> estimated) {}

	private static CompletableFuture<Planned> plan(WorldSnapshot snap, StructureMap map, dev.nucleusrouter.game.KeeperLayout layout, NrConfig cfg, int ping,
			PlanWorld.Columns explored) {
		// Arrival spots may be anywhere near the structure; the warps on the way only land on explored ground
		List<StopPlan.Stop> stops = StopPlan.build(map, layout, PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks)), cfg);
		PlanWorld world = PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks), explored);
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);
		// One planner (with its own caches) per worker thread
		ThreadLocal<LegPlanner> planners = ThreadLocal.withInitial(() ->
				new LegPlanner(world, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, ping), LegPlanner.Settings.fromConfig(cfg, cfg.plan.routeLegTimeLimitMs)));
		Map<String, LegPlanner.Leg> legs = new java.util.concurrent.ConcurrentHashMap<>();
		List<CompletableFuture<Void>> jobs = new ArrayList<>();
		for (String[] pair : RouteOrder.neededLegs(stops, cfg)) {
			StopPlan.Stop from = byId.get(pair[0]), to = byId.get(pair[1]);
			if (from == null || to == null || !from.ready() || !to.ready()) continue;
			jobs.add(CompletableFuture.runAsync(() -> {
				LegPlanner.Leg leg = planners.get().plan(from.standFeet(), null, to.standFeet(), ARRIVE_RADIUS);
				legs.put(pair[0] + ">" + pair[1], leg);
			}, POOL));
		}
		CostModel cost = new CostModel(cfg, ping);
		double perWarp = cost.warpSeconds(45, 2.0);
		double perBlock = 1.0 / (cfg.etherwarp.range * cfg.plan.estimateWarpEfficiency);
		return CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).thenApply(v -> {
			// Legs not found on the terrain get a rough straight-line estimate, so there's always an order
			Map<String, Double> estimated = new HashMap<>();
			for (String[] pair : RouteOrder.neededLegs(stops, cfg)) {
				LegPlanner.Leg l = legs.get(pair[0] + ">" + pair[1]);
				if (l != null && l.ok()) continue;
				StopPlan.Stop from = byId.get(pair[0]), to = byId.get(pair[1]);
				if (from == null || to == null) continue;
				dev.nucleusrouter.core.geom.Vec3 a = from.standFeet() != null ? from.standFeet() : from.target();
				dev.nucleusrouter.core.geom.Vec3 b = to.standFeet() != null ? to.standFeet() : to.target();
				estimated.put(pair[0] + ">" + pair[1], Math.ceil(a.sub(b).length() * perBlock) * perWarp);
			}
			RouteOrder.Plan plan = RouteOrder.solve(stops, (a, b) -> {
				LegPlanner.Leg l = legs.get(a + ">" + b);
				if (l != null && l.ok()) return l.seconds();
				Double e = estimated.get(a + ">" + b);
				return e != null ? e : Double.POSITIVE_INFINITY;
			}, cfg);
			NucleusRouterClient.LOG.info("[Nucleus Router] Route: {} legs tried, {} found on the terrain, {} estimated ({}), total {} s, order {}",
					legs.size(), legs.values().stream().filter(LegPlanner.Leg::ok).count(), estimated.size(),
					legs.entrySet().stream().filter(e -> !e.getValue().ok()).map(e -> e.getKey() + "=" + e.getValue().status()).toList(),
					plan.seconds(), plan.steps().stream().map(RouteOrder.Step::toId).toList());
			return new Planned(plan, stops, Map.copyOf(legs), Map.copyOf(estimated));
		});
	}

	/** "3 etherwarps + 2 teleport stretches (12 clicks)" */
	static String describe(LegPlanner.Leg leg) {
		if (leg == null) return "0 moves";
		long ew = leg.warps().stream().filter(w -> !w.isTeleport()).count();
		long tp = leg.warps().stream().filter(LegPlanner.Warp::isTeleport).count();
		int clicks = leg.warps().stream().filter(LegPlanner.Warp::isTeleport).mapToInt(LegPlanner.Warp::clicks).sum();
		if (tp == 0) return ew + " etherwarps";
		if (ew == 0) return clicks + " teleports (" + tp + " directions)";
		return ew + " etherwarps + " + clicks + " teleports";
	}

	/** Chat summary of a planned route. */
	public static void report(Route r) {
		Map<String, String> names = new HashMap<>();
		for (StopPlan.Stop s : r.stops()) names.put(s.id(), s.label());
		names.put("temple.exit", "front of the temple");
		RouteOrder.Plan p = r.plan();
		long found = r.legs().values().stream().filter(LegPlanner.Leg::ok).count();
		NucleusRouterClient.showLocal(Component.literal(String.format("Route planned in %.1f s: %d legs planned on the terrain, %d estimated", r.millis() / 1000.0, found, r.estimated().size()))
				.withStyle(ChatFormatting.AQUA));
		long structures = r.stops().stream().filter(s -> s.kind() == StopPlan.Kind.STRUCTURE).count();
		if (!p.hasRoute()) {
			NucleusRouterClient.showLocal(Component.literal(structures == 0 ? "No route yet: find the structures first (see /nr structures)."
					: "No route: no stop could be reached (see the game log).").withStyle(ChatFormatting.RED));
			return;
		}
		int n = 1;
		for (RouteOrder.Step s : p.steps()) {
			String to = s.toId().equals("nucleus") ? "back to the nucleus" : names.getOrDefault(s.toId(), s.toId());
			String from = s.viaWarpNucleus() ? "nucleus" : s.fromId();
			LegPlanner.Leg leg = r.leg(from, s.toId());
			boolean est = r.isEstimated(from, s.toId());
			String warps = est ? "fly there yourself (not explored yet, time estimated)" : describe(leg);
			String how = s.viaWarpNucleus() ? "/warp nucleus" + (s.toId().equals("nucleus") ? "" : ", then " + warps)
					: warps + (s.fromId().equals("temple.exit") ? " from the front of the temple" : "");
			String wait = s.waitSeconds() > 0 ? String.format(" + %.0f s there", s.waitSeconds()) : "";
			NucleusRouterClient.showLocal(Component.literal(String.format("  %d. %s: ", n++, to)).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(String.format("%s (%.1f s)%s", how, s.travelSeconds(), wait)).withStyle(ChatFormatting.GRAY)));
		}
		NucleusRouterClient.showLocal(Component.literal(String.format("  Total: about %s", dev.nucleusrouter.game.LobbyEvaluator.mmss(p.seconds()))).withStyle(ChatFormatting.AQUA));
		if (!r.estimated().isEmpty())
			NucleusRouterClient.showLocal(Component.literal("  Routes are only planned over ground you've explored. Fly the unplanned moves once (see /nr explored map), then run /nr route again.")
					.withStyle(ChatFormatting.GRAY));
		if (!p.unreachable().isEmpty()) {
			List<String> u = new ArrayList<>();
			for (String id : p.unreachable()) u.add(names.getOrDefault(id, id));
			NucleusRouterClient.showLocal(Component.literal("  Not reachable on the current terrain (needs a setup, milestone 4): " + String.join(", ", u)).withStyle(ChatFormatting.GOLD));
		}
	}
}
