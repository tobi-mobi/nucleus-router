package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SetPointsTest {
	private final NrConfig cfg = new NrConfig();

	/** Flat floor at y 100 (stand at feet y 101), open above. */
	private static final PlanWorld FLOOR = new PlanWorld() {
		@Override public boolean solid(int x, int y, int z) { return y <= 100; }
		@Override public boolean goodTarget(int x, int y, int z) { return solid(x, y, z); }
	};

	@Test
	void carriesOverToAnotherLobbyAsABlockOffset() {
		SetPoints sp = new SetPoints();
		// Lobby 1: King at 100.5/101/200.5, you stand 3 east, 2 north of him
		SetPoints.Point p = sp.record("king", new Vec3(100.5, 101, 200.5), new Vec3(103.3, 101, 198.7), Orientation.R0, 0);
		assertEquals(3, p.dx());
		assertEquals(0, p.dy());
		assertEquals(-2, p.dz());
		// Lobby 2: King somewhere else
		Vec3 f = sp.feet("king", new Vec3(400.5, 90, 50.5), Orientation.R0);
		assertEquals(new Vec3(403.5, 90, 48.5), f);
	}

	@Test
	void followsTheStructureWhenItIsTurned() {
		SetPoints sp = new SetPoints();
		sp.record("temple", new Vec3(0.5, 100, 0.5), new Vec3(5.5, 101, 0.5), Orientation.R90, 0);
		// Recorded 5 east in a lobby turned 90 deg; in an unturned one that's 5 north
		Vec3 f = sp.feet("temple", new Vec3(0.5, 100, 0.5), Orientation.R0);
		assertEquals(new Vec3(0.5, 101, -4.5), f);
		// and back in the same orientation it's where you stood
		assertEquals(new Vec3(5.5, 101, 0.5), sp.feet("temple", new Vec3(0.5, 100, 0.5), Orientation.R90));
	}

	@Test
	void slightlyBelowABlockTopStillCountsAsStandingOnIt() {
		assertArrayEquals(new int[]{2, 101, 3}, SetPoints.feetBlock(new Vec3(2.9, 100.9999, 3.1)));
	}

	@Test
	void stopPlanUsesYourPointWhenYouCanWarpOntoIt() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.KING_YOLKAR, "King Yolkar", 5.5, 101, 5.5, 0, true);
		SetPoints sp = new SetPoints();
		sp.record("king", new Vec3(5.5, 101, 5.5), new Vec3(7.5, 101, 5.5), Orientation.R0, 0);
		StopPlan.Stop king = byId(StopPlan.build(m, null, FLOOR, cfg, sp), "king");
		assertEquals(new Vec3(7.5, 101, 5.5), king.standFeet());
		assertTrue(king.note().endsWith("(your stop point)"), king.note());
	}

	@Test
	void fallsBackToTheAutomaticSpotWhenYourPointIsBlocked() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.KING_YOLKAR, "King Yolkar", 5.5, 101, 5.5, 0, true);
		SetPoints sp = new SetPoints();
		// A spot in mid-air (nothing to warp onto) in this lobby
		sp.record("king", new Vec3(5.5, 101, 5.5), new Vec3(7.5, 110, 5.5), Orientation.R0, 0);
		StopPlan.Stop king = byId(StopPlan.build(m, null, FLOOR, cfg, sp), "king");
		assertNotNull(king.standFeet());
		assertEquals(101, king.standFeet().y(), 1e-9, "automatic spot on the floor");
		assertTrue(king.note().contains("blocked"), king.note());
		// Without your points nothing changes
		StopPlan.Stop plain = byId(StopPlan.build(m, null, FLOOR, cfg), "king");
		assertEquals(king.standFeet(), plain.standFeet());
	}

	@Test
	void templeFrontSpotComesFromYourPoint() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 40.5, 101, 0.5, 0, true);
		SetPoints sp = new SetPoints();
		sp.record("temple.exit", new Vec3(40.5, 101, 0.5), new Vec3(40.5, 95, 20.5), Orientation.R0, 0);
		StopPlan.Stop exit = byId(StopPlan.build(m, null, FLOOR, cfg, sp), "temple.exit");
		assertEquals(new Vec3(40.5, 95, 20.5), exit.standFeet());
		assertEquals(exit.standFeet(), exit.target());
	}

	@Test
	void minesAndKhazadNeedTheirCrystalBox() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.KHAZAD_DUM, "Bal", 20.5, 101, 20.5, 0, true);
		assertNull(StopPlan.anchor("khazad", m), "Bal walks around; only the Topaz box is a fixed point");
		m.setCrystal(Structure.KHAZAD_DUM, new int[]{20, 101, 20}, new int[]{30, 113, 30});
		assertEquals(new Vec3(25.5, 107.5, 25.5), StopPlan.anchor("khazad", m));
		assertNull(StopPlan.anchor("divan", m));
	}

	private static StopPlan.Stop byId(List<StopPlan.Stop> stops, String id) {
		return stops.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
	}
}
