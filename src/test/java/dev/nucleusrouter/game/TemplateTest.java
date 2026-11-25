package dev.nucleusrouter.game;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TemplateTest {
	@Test
	void orientationRoundTrips() {
		for (Orientation o : Orientation.values()) {
			int[] w = o.apply(3, -7);
			assertArrayEquals(new int[]{3, -7}, o.invert(w[0], w[1]), o.name());
		}
		assertArrayEquals(new int[]{0, 1}, Orientation.R90.apply(1, 0), "east turns into south");
		assertArrayEquals(new int[]{-1, 0}, Orientation.M0.apply(1, 0));
	}

	/** A small "structure" of bricks with an arch, sitting in random stone and ores. */
	private static Map<Long, String> world(int ax, int ay, int az, Orientation o, long seed) {
		Map<Long, String> w = new HashMap<>();
		Random rnd = new Random(seed);
		for (int x = -12; x <= 12; x++) for (int y = -12; y <= 12; y++) for (int z = -12; z <= 12; z++)
			w.put(key(ax + x, ay + y, az + z), rnd.nextInt(10) == 0 ? "minecraft:iron_ore" : "minecraft:stone");
		// L-shaped wall of stone bricks plus a mossy marker: not symmetric, so the orientation is unique
		for (int dx = -6; dx <= 6; dx++) for (int dy = 0; dy <= 9; dy++) put(w, ax, ay, az, o, dx, dy, -5, "minecraft:stone_bricks");
		for (int dz = -5; dz <= -1; dz++) for (int dy = 0; dy <= 9; dy++) put(w, ax, ay, az, o, -6, dy, dz, "minecraft:stone_bricks");
		for (int dy = 0; dy <= 6; dy++) put(w, ax, ay, az, o, 5, dy, 3, "minecraft:mossy_cobblestone");
		for (int dx = -5; dx <= 5; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = 0; dy <= 4; dy++) put(w, ax, ay, az, o, dx, dy, dz, "minecraft:air");
		put(w, ax, ay, az, o, 2, 0, 1, "minecraft:chest");
		return w;
	}

	private static void put(Map<Long, String> w, int ax, int ay, int az, Orientation o, int dx, int dy, int dz, String b) {
		int[] xz = o.apply(dx, dz);
		w.put(key(ax + xz[0], ay + dy, az + xz[1]), b);
	}

	private static long key(int x, int y, int z) { return ((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF); }

	@Test
	void detectsHowAStructureIsTurnedInAnotherLobby() {
		Map<Long, String> lobbyA = world(500, 100, 500, Orientation.R0, 1);
		StructureTemplate t = StructureTemplate.capture("temple", 500, 100, 500, 10, (x, y, z) -> lobbyA.get(key(x, y, z)));
		assertTrue(t.seenFraction() > 0.99);

		for (Orientation truth : List.of(Orientation.R0, Orientation.R90, Orientation.R270, Orientation.M180)) {
			Map<Long, String> lobbyB = world(300, 80, 650, truth, 99);
			// Anchor found one block off in this lobby: the shift search must absorb it
			List<TemplateMatcher.Match> ranked = TemplateMatcher.match(t, 301, 80, 650, 1, (x, y, z) -> lobbyB.get(key(x, y, z)));
			assertEquals(truth, ranked.get(0).orientation(), "ranked " + ranked);
			assertTrue(ranked.get(0).score() > 0.95, "score " + ranked.get(0).score());
			assertTrue(TemplateMatcher.confident(ranked), "ranked " + ranked);
		}
	}

	@Test
	void unseenWorldIsNotConfident() {
		Map<Long, String> lobbyA = world(500, 100, 500, Orientation.R0, 1);
		StructureTemplate t = StructureTemplate.capture("temple", 500, 100, 500, 10, (x, y, z) -> lobbyA.get(key(x, y, z)));
		List<TemplateMatcher.Match> ranked = TemplateMatcher.match(t, 0, 0, 0, 0, (x, y, z) -> null);
		assertFalse(TemplateMatcher.confident(ranked));
	}

	@Test
	void templateTextFormRoundTrips() {
		Map<Long, String> lobbyA = world(500, 100, 500, Orientation.R0, 1);
		StructureTemplate t = StructureTemplate.capture("temple", 500, 100, 500, 6, (x, y, z) -> x == 506 ? null : lobbyA.get(key(x, y, z)));
		int[] back = StructureTemplate.decodeData(t.encodeData(), t.data.length);
		assertArrayEquals(t.data, back);
		assertEquals(StructureTemplate.UNSEEN, t.at(6, 0, 0));
		assertEquals("minecraft:chest", t.at(2, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> StructureTemplate.decodeData("1*5", 4));
		int[] b = t.distinctiveBounds();
		assertNotNull(b);
		assertEquals(-6, b[0]);
	}

	@Test
	void zoneFollowsTheStructuresOrientation() {
		StructureZone z = new StructureZone(new int[]{-2, -1, -10}, new int[]{8, 5, 3});
		assertArrayEquals(new int[]{98, 49, 190, 108, 55, 203}, z.worldBox(100, 50, 200, Orientation.R0));
		// R90: (x, z) -> (-z, x): x range -3..10, z range -2..8
		assertArrayEquals(new int[]{97, 49, 198, 110, 55, 208}, z.worldBox(100, 50, 200, Orientation.R90));

		int[] c1 = {97, 49, 198}, c2 = {110, 55, 208};
		StructureZone back = StructureZone.fromWorldCorners(c1, c2, 100, 50, 200, Orientation.R90);
		assertArrayEquals(z.min(), back.min());
		assertArrayEquals(z.max(), back.max());
	}

	@Test
	void ballTouchingTheZoneMeansTheBombFizzles() {
		int[] box = {100, 50, 200, 110, 60, 210};
		assertTrue(StructureZone.ballTouches(box, 105.5, 55.5, 205.5, 1), "inside");
		// Nearest block centre in the box is x = 110.5; 7 blocks away exactly touches a radius-7 ball
		assertTrue(StructureZone.ballTouches(box, 117.5, 55.5, 205.5, 7));
		assertFalse(StructureZone.ballTouches(box, 118.5, 55.5, 205.5, 7));
		assertEquals(0, StructureZone.distance(box, 105, 55, 205));
		assertEquals(8, StructureZone.distance(box, 119, 55, 205), 1e-9);
	}

	@Test
	void structureMapGivesZoneBoxesOnlyForExactPositions() {
		StructureMap m = new StructureMap();
		StructureZone z = new StructureZone(new int[]{-1, -1, -1}, new int[]{1, 1, 1});
		m.setSkyblocker(Structure.KING_YOLKAR, new double[]{10, 20, 30}, true);
		assertNull(m.zoneBox(Structure.KING_YOLKAR, z), "Skyblocker positions are approximate");
		m.observeNametag(Structure.KING_YOLKAR, "King Yolkar", 10.7, 20, 30.2, 0, true);
		assertArrayEquals(new int[]{9, 19, 29, 11, 21, 31}, m.zoneBox(Structure.KING_YOLKAR, z));
		assertFalse(m.orientationKnown(Structure.KING_YOLKAR));
		m.setOrientation(Structure.KING_YOLKAR, Orientation.R180);
		assertEquals(Orientation.R180, m.orientation(Structure.KING_YOLKAR));
	}
}
