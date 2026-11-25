package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunClockTest {
	private static StopPlan.Stop stop(String id, StopPlan.Kind kind, double x) {
		Vec3 p = new Vec3(x, 100, 0.5);
		return new StopPlan.Stop(id, id.equals("king") ? "King Yolkar" : id, null, kind, p, p, 1, 0, false, "");
	}

	private static LegPlanner.Warp warp(double x) {
		Vec3 land = new Vec3(x, 100, 0.5);
		return new LegPlanner.Warp(null, (int) Math.floor(x), 99, 0, land, null, land, 2, 0, 0, 0.3);
	}

	/** nucleus > king (2 warps, 2 s + 1 s there) > queen (1 warp, 1 s + 1 s) > /warp nucleus (1 s). Stops are 200 apart. */
	private static RunPath path() {
		List<StopPlan.Stop> stops = List.of(stop("nucleus", StopPlan.Kind.NUCLEUS, 0.5), stop("king", StopPlan.Kind.STRUCTURE, 100.5),
				stop("queen", StopPlan.Kind.STRUCTURE, 300.5));
		RouteOrder.Plan plan = new RouteOrder.Plan(List.of(
				new RouteOrder.Step("nucleus", "king", false, 2, 1),
				new RouteOrder.Step("king", "queen", false, 1, 1),
				new RouteOrder.Step("queen", "nucleus", true, 1, 0)), 6, List.of());
		Map<String, LegPlanner.Leg> legs = Map.of(
				"nucleus>king", new LegPlanner.Leg(LegPlanner.Status.FOUND, List.of(warp(50.5), warp(100.5)), 1, 0, 0, 0, 0),
				"king>queen", new LegPlanner.Leg(LegPlanner.Status.FOUND, List.of(warp(300.5)), 1, 0, 0, 0, 0));
		return RunPath.build(plan, stops, legs, Set.of());
	}

	@Test
	void plannedTimesAndActionsPerStop() {
		RunPath p = path();
		RunPath.StopInfo king = p.stopInfo(2), queen = p.stopInfo(4);
		assertEquals("King", king.split());
		assertEquals(2, king.plannedArrive(), 1e-9);
		assertEquals(3, king.plannedLeave(), 1e-9);
		assertEquals("Amber", queen.split());
		assertEquals(4, queen.plannedArrive(), 1e-9);
		assertEquals(5, queen.plannedLeave(), 1e-9);
		assertTrue(king.action().contains("Goblin Egg"));
		assertNull(p.stopInfo(0), "warps have no stop info");
		assertEquals(6, p.plannedSeconds(), 1e-9);
	}

	@Test
	void timerStartsWhenYouMoveAndSplitsWhenYouLeave() {
		RunPath p = path();
		Vec3 start = new Vec3(0.5, 100, 0.5);
		RunClock c = new RunClock(p, start);
		int i = 0;
		c.update(1000, start, i, i);
		assertFalse(c.started(), "standing still at the start");
		assertEquals(0, c.elapsedMs(5000));

		c.update(2000, new Vec3(3, 100, 0.5), i, i);
		assertTrue(c.started());
		c.onEtherwarp(2100);
		int n = p.advance(i, new Vec3(50.5, 100, 0.5));
		c.update(2500, new Vec3(50.5, 100, 0.5), i, n); i = n;
		c.onEtherwarp(2600);
		n = p.advance(i, new Vec3(100.5, 100, 0.5));
		c.update(4000, new Vec3(100.5, 100, 0.5), i, n); i = n;
		assertEquals(3, i, "at the King");
		RunClock.Split king = c.splits().get(0);
		assertEquals(2000, king.arriveMs());
		assertFalse(king.done(), "still at the King");

		RunClock.Status st = RunClock.status(p, i, new Vec3(100.5, 100, 0.5), 30);
		assertEquals("King Yolkar", st.atStop());
		assertTrue(st.doNow().contains("Goblin Egg"));
		assertTrue(st.next().startsWith("Etherwarp"));

		n = p.advance(i, new Vec3(300.5, 100, 0.5));
		c.update(7000, new Vec3(300.5, 100, 0.5), i, n); i = n;
		king = c.splits().get(0);
		assertEquals(5000, king.leaveMs(), "left the King when landing on the next warp");
		assertEquals(5 - 3, king.deltaSeconds(), 1e-9, "2 s behind the plan");
		assertEquals(5000, c.splits().get(1).arriveMs(), "the last warp lands at the Queen");

		st = RunClock.status(p, i, new Vec3(300.5, 100, 0.5), 30);
		assertEquals("Type /warp nucleus", st.next());

		n = p.advance(i, new Vec3(0.5, 100, 0.5));
		c.update(8000, new Vec3(0.5, 100, 0.5), i, n); i = n;
		assertTrue(c.finished());
		assertEquals(6000, c.elapsedMs(99_000), "the timer stops at the end");
		assertEquals(6000, c.splits().get(1).leaveMs(), "Amber split at /warp nucleus");
		assertEquals(2, c.etherwarps());
		c.onEtherwarp(9000);
		assertEquals(2, c.etherwarps(), "not counted after the end");
		assertEquals(3, c.plannedEtherwarps());
	}

	@Test
	void betweenStructuresShowsWhereYouAreHeading() {
		RunPath p = path();
		RunClock.Status st = RunClock.status(p, 0, new Vec3(0.5, 100, 0.5), 30);
		assertNull(st.atStop());
		assertEquals("King Yolkar", st.heading());
		assertEquals(100, st.headingDistance(), 1e-9);
		assertTrue(st.next().contains("2 moves"), st.next());
	}

	@Test
	void formatting() {
		assertEquals("1:23.4", RunClock.format(83_450));
		assertEquals("0:05.0", RunClock.format(5_000));
		assertEquals("+3.1", RunClock.formatDelta(3.1));
		assertEquals("-2.0", RunClock.formatDelta(-2));
	}
}
