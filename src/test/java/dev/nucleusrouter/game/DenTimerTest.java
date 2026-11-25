package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DenTimerTest {
	private static final Vec3 CRYSTAL = new Vec3(481, 114, 534), NUCLEUS = new Vec3(513.5, 106, 526.5);

	@Test
	void countsDownAndStopsWhenYouAreOut() {
		DenTimer t = new DenTimer();
		t.start(10_000);
		t.update(11_000, false, 3);
		assertEquals(DenTimer.State.RUNNING, t.state());
		assertEquals(2.0, t.remaining(11_000, 3), 1e-9);
		t.update(11_800, true, 3);
		assertEquals(DenTimer.State.OUT, t.state());
		assertEquals(1.8, t.resultSeconds(), 1e-9);
		t.update(11_800 + DenTimer.SHOW_RESULT_MS + 1, false, 3);
		assertEquals(DenTimer.State.IDLE, t.state(), "the result goes away after a few seconds");
	}

	@Test
	void runsOut() {
		DenTimer t = new DenTimer();
		t.start(0);
		t.update(3_100, false, 3);
		assertEquals(DenTimer.State.TOO_LATE, t.state());
		assertEquals(0, t.remaining(3_100, 3));
	}

	@Test
	void whatCountsAsOut() {
		Vec3 inCastle = new Vec3(482, 116, 535);
		assertFalse(DenTimer.isOut(inCastle, CRYSTAL, 30, NUCLEUS, "Goblin Queen's Den"));
		assertTrue(DenTimer.isOut(new Vec3(482, 144, 535), CRYSTAL, 30, NUCLEUS, "Goblin Queen's Den"), "30 above the crystal");
		assertTrue(DenTimer.isOut(new Vec3(513.5, 106, 526.5), CRYSTAL, 30, NUCLEUS, "Crystal Nucleus"), "/warp nucleus");
		assertTrue(DenTimer.isOut(inCastle, CRYSTAL, 30, NUCLEUS, "Goblin Holdout"), "scoreboard left the den");
		assertFalse(DenTimer.isOut(inCastle, null, 30, NUCLEUS, null), "nothing known: not out");
	}

	@Test
	void startMessageIgnoresColours() {
		List<String> msgs = List.of("The Goblin King's foul stench has dissipated");
		assertTrue(DenTimer.isStartMessage("§e[NPC] §fThe Goblin King's foul stench has dissipated!", msgs));
		assertFalse(DenTimer.isStartMessage("THEY'RE STEALING THE CRYSTAL! GET THEM!", msgs));
		assertFalse(DenTimer.isStartMessage("anything", List.of(" ")));
	}
}
