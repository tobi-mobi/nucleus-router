package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CrystalSignatureTest {
	private static long key(int x, int y, int z) { return ((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF); }

	/** Random cave, a 5x5x5 barrier box at (bx, by, bz) and a structure around it ('style' picks the blocks). */
	private static Map<Long, String> lobby(int bx, int by, int bz, String style, long seed) {
		Map<Long, String> w = new HashMap<>();
		Random rnd = new Random(seed);
		for (int x = bx - 15; x <= bx + 20; x++) for (int y = by - 15; y <= by + 20; y++) for (int z = bz - 15; z <= bz + 20; z++)
			w.put(key(x, y, z), rnd.nextInt(8) == 0 ? "minecraft:coal_ore" : "minecraft:stone");
		// Floor and pillars of the structure: not symmetric
		for (int x = bx - 6; x <= bx + 10; x++) for (int z = bz - 6; z <= bz + 8; z++) w.put(key(x, by - 1, z), style);
		for (int y = by; y <= by + 8; y++) { w.put(key(bx - 5, y, bz - 5), "minecraft:polished_andesite"); w.put(key(bx + 9, y, bz + 2), "minecraft:chiseled_stone_bricks"); }
		for (int x = bx; x < bx + 5; x++) for (int y = by; y < by + 5; y++) for (int z = bz; z < bz + 5; z++)
			if (x == bx || x == bx + 4 || y == by || y == by + 4 || z == bz || z == bz + 4) w.put(key(x, y, z), "minecraft:barrier");
			else w.put(key(x, y, z), "minecraft:air");
		return w;
	}

	private static BarrierBoxes.Box boxIn(Map<Long, String> w, int bx, int by, int bz) {
		List<int[]> barriers = new ArrayList<>();
		for (int x = bx; x < bx + 5; x++) for (int y = by; y < by + 5; y++) for (int z = bz; z < bz + 5; z++)
			if ("minecraft:barrier".equals(w.get(key(x, y, z)))) barriers.add(new int[]{x, y, z});
		return BarrierBoxes.group(barriers).get(0);
	}

	@Test
	void learnedInOneLobbyFoundInAnother() {
		// Lobby A: Mines of Divan with its keepers' centre 7 blocks east and 1 below the box corner
		Map<Long, String> a = lobby(500, 60, 500, "minecraft:stone_bricks", 1);
		BarrierBoxes.Box boxA = boxIn(a, 500, 60, 500);
		StructureTemplate around = StructureTemplate.capture("divan", 500, 60, 500, CrystalSignature.SURROUNDINGS_RADIUS, (x, y, z) -> a.get(key(x, y, z)));
		CrystalSignature divan = new CrystalSignature("divan", CrystalSignature.sizeOf(boxA), around, new double[]{7.5, -1, 2.5}, null, "mini1A");

		// A different structure learned too (other blocks)
		Map<Long, String> other = lobby(300, 60, 300, "minecraft:mossy_cobblestone", 2);
		CrystalSignature temple = new CrystalSignature("temple", new int[]{5, 5, 5},
				StructureTemplate.capture("temple", 300, 60, 300, CrystalSignature.SURROUNDINGS_RADIUS, (x, y, z) -> other.get(key(x, y, z))),
				new double[]{0, 0, 0}, null, "mini1A");

		// Lobby B: the Mines are somewhere else, the cave around them is different
		Map<Long, String> b = lobby(650, 80, 320, "minecraft:stone_bricks", 99);
		BarrierBoxes.Box boxB = boxIn(b, 650, 80, 320);
		CrystalSignature.Identified id = CrystalSignature.identify(boxB, List.of(temple, divan), (x, y, z) -> b.get(key(x, y, z)));
		assertNotNull(id);
		assertEquals("divan", id.signature().structure());
		assertEquals(new Vec3(657.5, 79, 322.5), id.signature().anchorFrom(boxB.min()), "keepers' centre without visiting them");

		// A barrier box with unknown surroundings is not claimed
		Map<Long, String> unknown = lobby(400, 50, 700, "minecraft:red_wool", 5);
		assertNull(CrystalSignature.identify(boxIn(unknown, 400, 50, 700), List.of(temple, divan), (x, y, z) -> unknown.get(key(x, y, z))));
	}

	@Test
	void structureMapPrefersTheNpcButUsesTheLearnedSpotOtherwise() {
		StructureMap m = new StructureMap();
		m.setCrystal(Structure.PRECURSOR_CITY, new int[]{600, 50, 400}, new int[]{604, 54, 404});
		assertNull(m.locate(Structure.PRECURSOR_CITY), "a bare crystal box doesn't place a non-crystal structure");
		m.setDerived(Structure.PRECURSOR_CITY, new Vec3(610, 50, 402));
		assertEquals(StructureMap.Source.LEARNED, m.locate(Structure.PRECURSOR_CITY).source());
		assertTrue(StructureMap.exact(m.locate(Structure.PRECURSOR_CITY)), "learned offsets are exact (no rotation)");
		m.observeNametag(Structure.PRECURSOR_CITY, "Professor Robot", 610.3, 50, 402.1, 0, true);
		assertEquals(StructureMap.Source.NPC, m.locate(Structure.PRECURSOR_CITY).source());
	}
}
