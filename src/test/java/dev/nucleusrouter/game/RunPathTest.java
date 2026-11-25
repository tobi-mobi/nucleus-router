package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunPathTest {
	private static StopPlan.Stop stop(String id, StopPlan.Kind kind, double x, double z) {
		Vec3 p = new Vec3(x, 100, z);
		return new StopPlan.Stop(id, id, null, kind, p, p, 1, 0, false, "");
	}

	private static LegPlanner.Warp warp(double x, double z) {
		Vec3 land = new Vec3(x, 100, z);
		return new LegPlanner.Warp(null, (int) Math.floor(x), 99, (int) Math.floor(z), land, null, land, 2, 0, 0, 0.3);
	}

	private static LegPlanner.Leg leg(LegPlanner.Warp... w) {
		return new LegPlanner.Leg(LegPlanner.Status.FOUND, List.of(w), 1, 0, 0, 0, 0);
	}

	/** nucleus > king (2 warps) > queen (1 warp) > warp nucleus home; then a run with an unexplored leg. */
	private RunPath path() {
		List<StopPlan.Stop> stops = List.of(stop("nucleus", StopPlan.Kind.NUCLEUS, 0.5, 0.5), stop("king", StopPlan.Kind.STRUCTURE, 100.5, 0.5),
				stop("queen", StopPlan.Kind.STRUCTURE, 230.5, 0.5));
		RouteOrder.Plan plan = new RouteOrder.Plan(List.of(
				new RouteOrder.Step("nucleus", "king", false, 2, 1),
				new RouteOrder.Step("king", "queen", false, 1, 1),
				new RouteOrder.Step("queen", "nucleus", true, 1, 0)), 7, List.of());
		Map<String, LegPlanner.Leg> legs = Map.of(
				"nucleus>king", leg(warp(50.5, 0.5), warp(100.5, 0.5)),
				"king>queen", leg(warp(230.5, 0.5)));
		return RunPath.build(plan, stops, legs, Set.of());
	}

	@Test
	void flattensTheRouteIntoWhatToDo() {
		RunPath p = path();
		assertEquals(List.of(RunPath.Kind.WARP, RunPath.Kind.WARP, RunPath.Kind.STOP, RunPath.Kind.WARP, RunPath.Kind.STOP, RunPath.Kind.WARP_NUCLEUS),
				p.nodes().stream().map(RunPath.Node::kind).toList());
		assertEquals("king", p.nodes().get(2).label());
		assertEquals(List.of(0, 1, 3), p.nextWarps(0, 10));
	}

	@Test
	void movesOnAsYouLand() {
		RunPath p = path();
		int i = p.advance(0, new Vec3(0.5, 100, 0.5));
		assertEquals(0, i, "at the nucleus: nothing done yet");
		i = p.advance(i, new Vec3(50.7, 100, 0.4));
		assertEquals(1, i, "landed on the first target");
		i = p.advance(i, new Vec3(100.5, 100, 0.5));
		assertEquals(3, i, "landed on the second target, which is also the King's stop");
		i = p.advance(i, new Vec3(230.5, 100, 0.5));
		assertEquals(5, i, "next: /warp nucleus");
		assertEquals(RunPath.Kind.WARP_NUCLEUS, p.nextInstruction(i).kind());
		i = p.advance(i, new Vec3(0.5, 100, 0.5));
		assertEquals(6, i, "back at the nucleus: done");
	}

	@Test
	void offTheRouteNothingChangesButLandingElsewhereOnItJumps() {
		RunPath p = path();
		assertEquals(0, p.advance(0, new Vec3(40, 120, 40)), "off the route: no re-plan, no change");
		assertEquals(0, p.nearest(new Vec3(45, 100, 3)), "the nearest route spot is the first target");
		assertEquals(5, p.advance(0, new Vec3(230.5, 100, 0.5)), "landed further along (on the Queen's spot): jump there, Queen reached");
	}

	/** In-game 2026-11-25: in the Mines, 28 m from the stop spot (on the crystal box), it still said "fly there". */
	@Test
	void arrivingAnywhereInABigStructureCounts() {
		List<StopPlan.Stop> stops = List.of(stop("nucleus", StopPlan.Kind.NUCLEUS, 0.5, 0.5), stop("divan", StopPlan.Kind.STRUCTURE, 200.5, 0.5));
		RouteOrder.Plan plan = new RouteOrder.Plan(List.of(new RouteOrder.Step("nucleus", "divan", false, 9, 15), new RouteOrder.Step("divan", "nucleus", true, 1, 0)), 25, List.of());
		RunPath p = RunPath.build(plan, stops, Map.of(), Set.of("nucleus>divan"));
		assertEquals(2, p.advance(0, new Vec3(200.5, 90, 26)), "28 m away (and lower) is inside the structure");
		assertEquals(0, p.advance(0, new Vec3(200.5, 100, 60)), "60 m away is not");
	}

	@Test
	void unexploredLegsAreFlownByHand() {
		List<StopPlan.Stop> stops = List.of(stop("nucleus", StopPlan.Kind.NUCLEUS, 0.5, 0.5), stop("khazad", StopPlan.Kind.STRUCTURE, 200.5, 0.5));
		RouteOrder.Plan plan = new RouteOrder.Plan(List.of(new RouteOrder.Step("nucleus", "khazad", false, 9, 20), new RouteOrder.Step("khazad", "nucleus", true, 1, 0)), 30, List.of());
		RunPath p = RunPath.build(plan, stops, Map.of(), Set.of("nucleus>khazad"));
		assertEquals(List.of(RunPath.Kind.FLY, RunPath.Kind.STOP, RunPath.Kind.WARP_NUCLEUS), p.nodes().stream().map(RunPath.Node::kind).toList());
		assertTrue(p.nodes().get(0).label().contains("yourself"));
		assertEquals(2, p.advance(0, new Vec3(197, 100, 0.5)), "arriving near Khazad-dum passes the fly and the stop");
	}
}
