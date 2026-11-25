package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeeperLayoutTest {
	/** Keeper positions from two of the user's real lobbies (mini111AA and mini113DU). */
	private static StructureMap lobby(double cx, double cy, double cz, String... only) {
		StructureMap m = new StructureMap();
		Object[][] k = {{"Keeper of Lapis", 33, 3}, {"Keeper of Emerald", 3, -33}, {"Keeper of Diamond", -33, -3}, {"Keeper of Gold", -3, 33}};
		for (Object[] e : k) {
			String name = (String) e[0];
			if (only.length > 0 && !java.util.List.of(only).contains(name)) continue;
			m.observeNametag(Structure.MINES_OF_DIVAN, name, cx + (int) e[1], cy, cz + (int) e[2], 0, true);
		}
		return m;
	}

	@Test
	void oneKeeperIsEnoughOnceTheLayoutIsKnown() {
		KeeperLayout layout = KeeperLayout.learn(lobby(631, 87, 343).npcs(Structure.MINES_OF_DIVAN));
		assertNotNull(layout);

		// Another lobby: only the Gold keeper seen
		StructureMap other = lobby(607, 89, 371, "Keeper of Gold");
		assertFalse(StructureMap.exact(other.locate(Structure.MINES_OF_DIVAN)), "1 keeper alone: centre is only rough");
		Vec3 centre = layout.centreFrom(other.npcs(Structure.MINES_OF_DIVAN));
		assertEquals(new Vec3(607, 89, 371), centre);
		other.setDerived(Structure.MINES_OF_DIVAN, centre);
		StructureMap.Location l = other.locate(Structure.MINES_OF_DIVAN);
		assertEquals(StructureMap.Source.LEARNED, l.source());
		assertTrue(StructureMap.exact(l));
		assertEquals(new Vec3(640, 89, 374), layout.keeperAt("Keeper of Lapis", centre), "and where the others stand");
	}

	@Test
	void needsAllFourDifferentKeepers() {
		assertNull(KeeperLayout.learn(lobby(0, 0, 0, "Keeper of Gold", "Keeper of Lapis").npcs(Structure.MINES_OF_DIVAN)));
		StructureMap all = lobby(631, 87, 343);
		assertTrue(StructureMap.exact(all.locate(Structure.MINES_OF_DIVAN)), "all 4 seen: exact centre");
		assertEquals(new Vec3(631, 87, 343), all.locate(Structure.MINES_OF_DIVAN).pos());
	}
}
