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

	/** A planned run: the order plus the etherwarp chain of each leg. */
	public record Route(RouteOrder.Plan plan, List<StopPlan.Stop> stops, Map<String, LegPlanner.Leg> legs, String lobby, long millis) {
		public LegPlanner.Leg leg(String from, String to) { return legs.get(from + ">" + to); }
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
		long t0 = System.currentTimeMillis();
		WorldCapture.snapshot().thenComposeAsync(snap -> plan(snap, map, layout, cfg, ping), POOL)
				.whenComplete((plan, err) -> Minecraft.getInstance().execute(() -> {
					running = false;
					if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
					Route r = new Route(plan.plan(), plan.stops(), plan.legs(), lobby, System.currentTimeMillis() - t0);
					current = r;
					done.accept(r);
				}));
		return true;
	}

	private record Planned(RouteOrder.Plan plan, List<StopPlan.Stop> stops, Map<String, LegPlanner.Leg> legs) {}

	private static CompletableFuture<Planned> plan(WorldSnapshot snap, StructureMap map, dev.nucleusrouter.game.KeeperLayout layout, NrConfig cfg, int ping) {
		PlanWorld world = PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks));
		List<StopPlan.Stop> stops = StopPlan.build(map, layout, world, cfg);
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);
		// One planner (with its own caches) per worker thread
		ThreadLocal<LegPlanner> planners = ThreadLocal.withInitial(() ->
				new LegPlanner(world, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, ping), LegPlanner.Settings.fromConfig(cfg)));
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
		return CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).thenApply(v -> {
			RouteOrder.Plan plan = RouteOrder.solve(stops, (a, b) -> {
				LegPlanner.Leg l = legs.get(a + ">" + b);
				return l != null && l.ok() ? l.seconds() : Double.POSITIVE_INFINITY;
			}, cfg);
			NucleusRouterClient.LOG.info("[Nucleus Router] Route: {} legs planned, {} found, total {} s, unreachable {}",
					legs.size(), legs.values().stream().filter(LegPlanner.Leg::ok).count(), plan.seconds(), plan.unreachable());
			return new Planned(plan, stops, Map.copyOf(legs));
		});
	}

	/** Chat summary of a planned route. */
	public static void report(Route r) {
		Map<String, String> names = new HashMap<>();
		for (StopPlan.Stop s : r.stops()) names.put(s.id(), s.label());
		names.put("temple.exit", "front of the temple");
		RouteOrder.Plan p = r.plan();
		long found = r.legs().values().stream().filter(LegPlanner.Leg::ok).count();
		NucleusRouterClient.showLocal(Component.literal(String.format("Route planned in %.1f s (%d of %d legs found on the terrain)", r.millis() / 1000.0, found, r.legs().size()))
				.withStyle(ChatFormatting.AQUA));
		if (p.steps().isEmpty()) {
			NucleusRouterClient.showLocal(Component.literal("No route yet: find the structures first (see /nr structures).").withStyle(ChatFormatting.RED));
			return;
		}
		int n = 1;
		for (RouteOrder.Step s : p.steps()) {
			String to = s.toId().equals("nucleus") ? "back to the nucleus" : names.getOrDefault(s.toId(), s.toId());
			LegPlanner.Leg leg = s.viaWarpNucleus() ? r.leg("nucleus", s.toId()) : r.leg(s.fromId(), s.toId());
			int warps = leg == null ? 0 : leg.warps().size();
			String how = s.viaWarpNucleus() ? "/warp nucleus" + (s.toId().equals("nucleus") ? "" : ", then " + warps + " warps")
					: warps + " warps" + (s.fromId().equals("temple.exit") ? " from the front of the temple" : "");
			String wait = s.waitSeconds() > 0 ? String.format(" + %.0f s there", s.waitSeconds()) : "";
			NucleusRouterClient.showLocal(Component.literal(String.format("  %d. %s: ", n++, to)).withStyle(ChatFormatting.WHITE)
					.append(Component.literal(String.format("%s (%.1f s)%s", how, s.travelSeconds(), wait)).withStyle(ChatFormatting.GRAY)));
		}
		NucleusRouterClient.showLocal(Component.literal(String.format("  Total: about %s", dev.nucleusrouter.game.LobbyEvaluator.mmss(p.seconds()))).withStyle(ChatFormatting.AQUA));
		if (!p.unreachable().isEmpty()) {
			List<String> u = new ArrayList<>();
			for (String id : p.unreachable()) u.add(names.getOrDefault(id, id));
			NucleusRouterClient.showLocal(Component.literal("  Not reachable on the current terrain (needs a setup, milestone 4): " + String.join(", ", u)).withStyle(ChatFormatting.GOLD));
		}
	}
}
