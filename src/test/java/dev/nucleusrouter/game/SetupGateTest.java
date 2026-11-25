package dev.nucleusrouter.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SetupGateTest {
	@Test
	void opensOnlyWhenEveryStructureIsExactlyFound() {
		StructureMap m = new StructureMap();
		SetupGate.Status s = SetupGate.check(m);
		assertFalse(s.ready());
		assertEquals(6, s.missing().size());

		for (Structure st : Structure.values()) m.observeNametag(st, st.anchorNoun, 100, 50, 100, 0, true);
		// The Mines need all 4 keepers (or a learned layout) for an exact centre
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Lapis", 133, 50, 103, 0, true);
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Emerald", 103, 50, 67, 0, true);
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Gold", 97, 50, 133, 0, true);
		m.clear(Structure.KHAZAD_DUM);
		m.setSkyblocker(Structure.KHAZAD_DUM, new double[]{600, 40, 600}, true);
		s = SetupGate.check(m);
		assertFalse(s.ready(), "a Skyblocker waypoint is only approximate");
		assertEquals(java.util.List.of(Structure.KHAZAD_DUM), s.approximate());

		m.setCrystal(Structure.KHAZAD_DUM, new int[]{620, 100, 470}, new int[]{626, 106, 476});
		s = SetupGate.check(m);
		assertTrue(s.ready());
		assertEquals(6, s.found().size());
	}
}
