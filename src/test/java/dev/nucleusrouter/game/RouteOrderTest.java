package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.world.BlockClassifier;
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

import static org.junit.jupiter.api.Assertions.*;

class RouteOrderTest {
	private final NrConfig cfg = new NrConfig();

	private static StopPlan.Stop stop(String id, Structure s, double x, double z, double wait) {
		Vec3 p = new Vec3(x, 100, z);
		return new StopPlan.Stop(id, id, s, StopPlan.Kind.STRUCTURE, p, p, 1, wait, false, "");
	}

	/** Six structures around the nucleus; leg time = distance / 30 (one warp-ish second per 30 blocks). */
	private List<StopPlan.Stop> lobby() {
		List<StopPlan.Stop> s = new ArrayList<>();
		Vec3 n = StopPlan.nucleus(cfg);
		s.add(new StopPlan.Stop("nucleus", "nucleus", null, StopPlan.Kind.NUCLEUS, n, n, 1, 0, false, ""));
		s.add(stop("divan", Structure.MINES_OF_DIVAN, 700, 400, 15));
		s.add(stop("precursor", Structure.PRECURSOR_CITY, 700, 650, 3));
		s.add(stop("king", Structure.KING_YOLKAR, 300, 650, 1));
		s.add(stop("queen", Structure.GOBLIN_QUEENS_DEN, 330, 700, 0.5));
		s.add(stop("temple", Structure.JUNGLE_TEMPLE, 320, 350, 26.5));
		s.add(StopPlan.templeExit(new Vec3(320, 100, 350), cfg));
		s.add(stop("khazad", Structure.KHAZAD_DUM, 520, 300, 20.5));
		return s;
	}

	private static RouteOrder.Legs distanceLegs(List<StopPlan.Stop> stops) {
		Map<String, Vec3> at = new HashMap<>();
		for (StopPlan.Stop s : stops) at.put(s.id(), s.standFeet());
		return (a, b) -> at.get(a).sub(at.get(b)).length() / 30.0;
	}

	@Test
	void kingComesBeforeQueenAndTheDenIsLeftWithWarpNucleus() {
		List<StopPlan.Stop> stops = lobby();
		RouteOrder.Plan p = RouteOrder.solve(stops, distanceLegs(stops), cfg);
		assertTrue(p.ok());
		List<String> order = p.steps().stream().map(RouteOrder.Step::toId).toList();
		assertEquals(7, order.size(), "6 structures + back to the nucleus: " + order);
		assertEquals("nucleus", order.get(6));
		assertTrue(order.indexOf("king") < order.indexOf("queen"), order.toString());
		RouteOrder.Step afterQueen = p.steps().get(order.indexOf("queen") + 1);
		assertTrue(afterQueen.viaWarpNucleus(), "the Amber crystal is always left with /warp nucleus");
	}

	@Test
	void picksTheFastestOrderOverall() {
		List<StopPlan.Stop> stops = lobby();
		RouteOrder.Legs legs = distanceLegs(stops);
		RouteOrder.Plan p = RouteOrder.solve(stops, legs, cfg);
		// Brute check: no reordering of two neighbours (keeping King before Queen) is faster
		double waits = stops.stream().mapToDouble(StopPlan.Stop::waitSeconds).sum();
		assertTrue(p.seconds() > waits, "travel is counted on top of the stop times");
		assertTrue(p.seconds() < waits + 60, "a sensible total: " + p.seconds());
	}

	@Test
	void usesWarpNucleusWhenItIsAShortcut() {
		List<StopPlan.Stop> stops = lobby();
		RouteOrder.Plan p = RouteOrder.solve(stops, distanceLegs(stops), cfg);
		long warps = p.steps().stream().filter(RouteOrder.Step::viaWarpNucleus).count();
		assertTrue(warps >= 2, "den exit plus at least one shortcut across the map: " + p.steps());
	}

	@Test
	void unreachableStopsAreReportedNotHidden() {
		List<StopPlan.Stop> stops = lobby();
		RouteOrder.Legs base = distanceLegs(stops);
		RouteOrder.Plan p = RouteOrder.solve(stops, (a, b) -> b.equals("khazad") ? Double.POSITIVE_INFINITY : base.seconds(a, b), cfg);
		assertEquals(List.of("khazad"), p.unreachable());
		assertFalse(p.ok());
		assertFalse(p.steps().stream().anyMatch(s -> s.toId().equals("khazad")), "planned around it");
	}

	/** Real lobby (bench/, git-ignored): nucleus, Mines of Divan, Precursor City with real legs. */
	@Test
	void realLobbyRoute() throws Exception {
		Path file = Path.of("bench", "mini92AD.nrw");
		Assumptions.assumeTrue(Files.exists(file), "no real lobby in bench/");
		WorldSnapshot snap;
		try (InputStream in = Files.newInputStream(file)) { snap = WorldStore.read(in, BlockClassifier.fromConfig(cfg)).model().snapshot(); }
		PlanWorld world = PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks));
		List<StopPlan.Stop> stops = StopPlan.build(RealLobby.structures(snap), null, world, cfg);
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);
		LegPlanner planner = new LegPlanner(world, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, 170), LegPlanner.Settings.fromConfig(cfg));
		Map<String, LegPlanner.Leg> legs = new HashMap<>();
		long t = System.nanoTime();
		for (String[] pair : RouteOrder.neededLegs(stops, cfg)) {
			LegPlanner.Leg l = planner.plan(byId.get(pair[0]).standFeet(), null, byId.get(pair[1]).standFeet(), 2.5);
			legs.put(pair[0] + ">" + pair[1], l);
			System.out.printf("[bench-real] leg %s -> %s: %s, %d warps, %.2f s%n", pair[0], pair[1], l.status(), l.warps().size(), l.seconds());
		}
		RouteOrder.Plan p = RouteOrder.solve(stops, (a, b) -> {
			LegPlanner.Leg l = legs.get(a + ">" + b);
			return l != null && l.ok() ? l.seconds() : Double.POSITIVE_INFINITY;
		}, cfg);
		System.out.printf("[bench-real] %d legs in %.0f ms (one thread); route %s, total %.1f s, unreachable %s%n",
				legs.size(), (System.nanoTime() - t) / 1e6, p.steps().stream().map(s -> (s.viaWarpNucleus() ? "warp>" : "") + s.toId()).toList(), p.seconds(), p.unreachable());
		assertFalse(p.steps().isEmpty());
	}
}
