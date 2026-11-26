package dev.nucleusrouter.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.core.world.WorldModel;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.core.world.WorldStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance pass (roadmap 5.3) on the user's real lobby saves in {@code bench/} (git-ignored; skipped without
 * them): memory of the world model, leg planning (SPEC §3.3: typical < 50 ms, worst < 500 ms), route ordering
 * (SPEC §3.2: < 10 ms once legs are known) and the whole-lobby setup plan (SPEC §3.4: < 5 s). Prints the numbers.
 */
class PerfBenchmarkTest {
	private final NrConfig cfg = new NrConfig();

	@Test
	void realLobbies() throws Exception {
		List<String> lobbies = List.of("mini92AD", "mini111AA");
		boolean any = false;
		for (String lobby : lobbies) {
			Path nrw = Path.of("bench", lobby + ".nrw"), structures = Path.of("bench", lobby + ".structures.json");
			if (!Files.exists(nrw) || !Files.exists(structures)) continue;
			any = true;
			bench(lobby, nrw, structures);
		}
		Assumptions.assumeTrue(any, "no real lobby in bench/");
	}

	private void bench(String lobby, Path nrw, Path structuresFile) throws Exception {
		System.gc();
		Runtime rt = Runtime.getRuntime();
		long before = rt.totalMemory() - rt.freeMemory();
		long t = System.nanoTime();
		WorldModel model;
		try (InputStream in = Files.newInputStream(nrw)) { model = WorldStore.read(in, BlockClassifier.fromConfig(cfg)).model(); }
		double loadMs = (System.nanoTime() - t) / 1e6;
		System.gc();
		long after = rt.totalMemory() - rt.freeMemory();
		WorldSnapshot snap = model.snapshot();
		System.out.printf("[perf] %s: %d sections loaded in %.0f ms; model %s; heap +%.1f MB%n",
				lobby, snap.sectionCount(), loadMs, model.memoryReport(), (after - before) / 1048576.0);

		JsonObject o = JsonParser.parseString(Files.readString(structuresFile)).getAsJsonObject();
		StructureMap map = new Gson().fromJson(o.get("map"), StructureMap.class);
		PlanWorld world = PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks));
		t = System.nanoTime();
		List<StopPlan.Stop> stops = StopPlan.build(map, null, world, cfg);
		System.out.printf("[perf] %s: %d stops in %.0f ms (%s)%n", lobby, stops.size(), (System.nanoTime() - t) / 1e6,
				stops.stream().map(s -> s.id() + (s.ready() ? "" : "?")).toList());

		// Legs, one planner, one thread (route planning uses several)
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);
		LegPlanner planner = new LegPlanner(world, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, 170),
				LegPlanner.Settings.fromConfig(cfg, cfg.plan.routeLegTimeLimitMs));
		Map<String, LegPlanner.Leg> legs = new HashMap<>();
		List<Double> firsts = new ArrayList<>();
		for (String[] pair : RouteOrder.neededLegs(stops, cfg)) {
			StopPlan.Stop a = byId.get(pair[0]), b = byId.get(pair[1]);
			if (a == null || b == null || !a.ready() || !b.ready()) continue;
			LegPlanner.Leg l = planner.plan(a.standFeet(), null, b.standFeet(), 2.5);
			legs.put(pair[0] + ">" + pair[1], l);
			if (l.ok()) firsts.add(l.firstMicros() / 1000.0);
			System.out.printf("[perf]   leg %s -> %s (%.0f blocks): %s, %d warps, %.2f s, %d expansions, %d priced, first %.1f ms, total %.1f ms%n",
					pair[0], pair[1], a.standFeet().sub(b.standFeet()).length(), l.status(), l.warps().size(), l.seconds(), l.expansions(), l.edgesPriced(),
					l.firstMicros() / 1000.0, l.micros() / 1000.0);
		}
		firsts.sort(Double::compare);
		double median = firsts.isEmpty() ? 0 : firsts.get(firsts.size() / 2), worst = firsts.isEmpty() ? 0 : firsts.get(firsts.size() - 1);
		System.out.printf("[perf] %s: %d legs, %d found; first route median %.1f ms, worst %.1f ms%n",
				lobby, legs.size(), firsts.size(), median, worst);

		// Ordering, legs known
		RouteOrder.Legs known = (a, b) -> { LegPlanner.Leg l = legs.get(a + ">" + b); return l != null && l.ok() ? l.seconds() : Double.POSITIVE_INFINITY; };
		RouteOrder.solve(stops, known, cfg); // warm-up
		t = System.nanoTime();
		int reps = 20;
		RouteOrder.Plan plan = null;
		for (int i = 0; i < reps; i++) plan = RouteOrder.solve(stops, known, cfg);
		double orderMs = (System.nanoTime() - t) / 1e6 / reps;
		System.out.printf("[perf] %s: ordering %.2f ms; route %s, %.1f s%n", lobby, orderMs,
				plan.steps().stream().map(s -> (s.viaWarpNucleus() ? "warp>" : "") + s.toId()).toList(), plan.seconds());

		// Whole-lobby setup plan
		int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			SetupWorld sw = SetupWorld.of(snap, List.of(), (x, z) -> true);
			Map<String, Double> terrain = new HashMap<>();
			legs.forEach((k, l) -> { if (l.ok()) terrain.put(k, l.seconds()); });
			LobbySetup.Result r = LobbySetup.plan(stops, sw, terrain, cfg, 170, pool, threads, (p, d, n) -> {});
			System.out.printf("[perf] %s: setup on %d threads: %d legs, design %.0f ms, order %.0f ms, bombs %.0f ms, placing %.0f ms, total %.0f ms; %d bombs, %d placed, run %.1f s, problems %d%n",
					lobby, threads, r.legsWanted(), r.designMs(), r.orderMs(), r.bombsMs(), r.placeMs(), r.totalMs(), r.bombs(), r.placements(),
					r.plan().seconds(), r.problems().size());
			assertTrue(r.totalMs() < cfg.setup.lobbyTimeLimitMs * 1.5, "setup took " + r.totalMs() + " ms");
		} finally {
			pool.shutdownNow();
		}
		assertTrue(orderMs < 10 * 2, "ordering " + orderMs + " ms");
		assertTrue(worst < 500 * 2, "worst leg " + worst + " ms");
	}
}
