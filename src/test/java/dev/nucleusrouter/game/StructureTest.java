package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructureTest {
	private final AnchorMatcher matcher = new AnchorMatcher(new NrConfig().structures);

	@Test
	void matchesNameTagsAsWholeWordsIgnoringColours() {
		assertEquals(Structure.KING_YOLKAR, matcher.match("§6King Yolkar"));
		assertEquals(Structure.PRECURSOR_CITY, matcher.match("§bprofessor robot"));
		assertEquals(Structure.JUNGLE_TEMPLE, matcher.match("Kalhuiki Door Guardian"));
		assertEquals(Structure.MINES_OF_DIVAN, matcher.match("§eKeeper of Lapis"));
		assertEquals(Structure.KHAZAD_DUM, matcher.match("§8[§7Lv100§8] §cBal §a1.5M§f/§a2M§c❤"));
		assertNull(matcher.match("Balloon Vendor"), "Bal must be a whole word");
		assertNull(matcher.match("§eCLICK"));
		assertNull(matcher.match(""));
		assertNull(matcher.match(null));
	}

	@Test
	void configuredNamesAreUsed() {
		NrConfig.Structures cfg = new NrConfig().structures;
		cfg.kingYolkarNames = List.of("Goblin King");
		AnchorMatcher m = new AnchorMatcher(cfg);
		assertEquals(Structure.KING_YOLKAR, m.match("The Goblin King"));
		assertNull(m.match("King Yolkar"));
	}

	@Test
	void areaNamesMapToStructures() {
		assertEquals(Structure.MINES_OF_DIVAN, Structure.byArea("Mines of Divan"));
		assertEquals(Structure.GOBLIN_QUEENS_DEN, Structure.byArea("Goblin Queen's Den"));
		assertNull(Structure.byArea("Goblin Holdout"), "covers a whole quarter, not King Yolkar");
		assertNull(Structure.byArea(null));
		assertEquals(Structure.KING_YOLKAR, Structure.byKey("king"));
		assertEquals(Structure.KHAZAD_DUM, Structure.byKey("KHAZAD_DUM"));
	}

	@Test
	void manualBeatsNameTagBeatsArea() {
		StructureMap m = new StructureMap();
		assertNull(m.locate(Structure.JUNGLE_TEMPLE));
		assertTrue(m.observeArea(Structure.JUNGLE_TEMPLE, 300, 80, 300));
		assertFalse(m.observeArea(Structure.JUNGLE_TEMPLE, 320, 80, 300));
		StructureMap.Location area = m.locate(Structure.JUNGLE_TEMPLE);
		assertEquals(StructureMap.Source.AREA, area.source());
		assertEquals(new Vec3(310, 80, 300), area.pos());
		assertTrue(area.uncertainty() >= 16);
		assertTrue(m.missing().contains(Structure.JUNGLE_TEMPLE), "a rough area doesn't count as found");

		assertTrue(m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 305, 70, 290, 0));
		assertFalse(m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 305, 70, 290, 1));
		assertEquals(StructureMap.Source.NPC, m.locate(Structure.JUNGLE_TEMPLE).source());
		assertEquals(new Vec3(305, 70, 290), m.locate(Structure.JUNGLE_TEMPLE).pos());
		assertFalse(m.missing().contains(Structure.JUNGLE_TEMPLE));

		m.setManual(Structure.JUNGLE_TEMPLE, 1, 2, 3);
		assertEquals(StructureMap.Source.MANUAL, m.locate(Structure.JUNGLE_TEMPLE).source());
		m.clear(Structure.JUNGLE_TEMPLE);
		assertNull(m.locate(Structure.JUNGLE_TEMPLE));
	}

	@Test
	void fourKeepersAreKeptApartAndAveraged() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Diamond", 500, 50, 400, 0);
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Diamond", 500.4, 50, 400, 0); // same NPC, jitter
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Lapis", 540, 50, 400, 0);
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Gold", 520, 50, 380, 0);
		m.observeNametag(Structure.MINES_OF_DIVAN, "Keeper of Emerald", 520, 50, 420, 0);
		assertEquals(4, m.npcs(Structure.MINES_OF_DIVAN).size());
		StructureMap.Location l = m.locate(Structure.MINES_OF_DIVAN);
		assertEquals(520.05, l.pos().x(), 1e-9);
		assertEquals(400, l.pos().z(), 1e-9);
		assertTrue(l.detail().startsWith("4 of 4"), l.detail());
	}

	@Test
	void aMovingBossStaysOneAnchor() {
		StructureMap m = new StructureMap();
		for (int i = 0; i < 300; i++) m.observeNametag(Structure.KHAZAD_DUM, "Bal", 600 + (i % 20), 40, 600, i);
		assertEquals(1, m.npcs(Structure.KHAZAD_DUM).size());
		assertEquals(609.5, m.locate(Structure.KHAZAD_DUM).pos().x(), 1.5);
	}

	/** In-game 2026-11-24 the temple marker sat beside the Door Guardian: the body under the tag wins. */
	@Test
	void theNpcBodyBeatsAFloatingTag() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 300, 72, 300, 0, false);
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 302.5, 70, 301, 1, true);
		assertEquals(new Vec3(302.5, 70, 301), m.locate(Structure.JUNGLE_TEMPLE).pos(), "tag-only sighting dropped");
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 300, 72, 300, 2, false);
		assertEquals(new Vec3(302.5, 70, 301), m.locate(Structure.JUNGLE_TEMPLE).pos(), "a later tag-only sighting is ignored");
	}

	/** In-game 2026-11-24: two Door Guardians a few blocks apart; the marker ended up between them. */
	@Test
	void twoDoorGuardiansAreNotAveraged() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 300.5, 70, 300.5, 0, true);
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 305.5, 70, 300.5, 1, true);
		m.observeNametag(Structure.JUNGLE_TEMPLE, "Kalhuiki Door Guardian", 300.5, 70, 300.5, 2, true);
		assertEquals(2, m.npcs(Structure.JUNGLE_TEMPLE).size());
		assertEquals(new Vec3(300.5, 70, 300.5), m.locate(Structure.JUNGLE_TEMPLE).pos(), "on the first guardian, not between them");
	}

	@Test
	void crystalBeatsBalAndSkyblockerIsTheFallback() {
		StructureMap m = new StructureMap();
		m.setSkyblocker(Structure.KHAZAD_DUM, new double[]{600, 50, 600}, false);
		assertEquals(StructureMap.Source.SKYBLOCKER, m.locate(Structure.KHAZAD_DUM).source());
		m.observeNametag(Structure.KHAZAD_DUM, "Bal", 610, 40, 610, 0, true);
		assertEquals(StructureMap.Source.NPC, m.locate(Structure.KHAZAD_DUM).source());
		assertTrue(m.setCrystal(Structure.KHAZAD_DUM, new int[]{620, 100, 470}, new int[]{626, 106, 476}));
		StructureMap.Location l = m.locate(Structure.KHAZAD_DUM);
		assertEquals(StructureMap.Source.CRYSTAL, l.source());
		assertEquals(new Vec3(623.5, 103.5, 473.5), l.pos(), "centre of the barrier box");
		assertFalse(m.setCrystal(Structure.KHAZAD_DUM, new int[]{620, 100, 470}, new int[]{626, 106, 476}), "same box again");
		assertTrue(m.setSkyblocker(Structure.KHAZAD_DUM, null, false));
		assertEquals(StructureMap.Source.CRYSTAL, m.locate(Structure.KHAZAD_DUM).source());
	}

	@Test
	void findsTheBarrierBoxAmongStrayBarriers() {
		List<int[]> blocks = new java.util.ArrayList<>();
		// Hollow 7x7x7 box shell around the crystal at 479..485, 108..114, 527..533
		for (int x = 479; x <= 485; x++) for (int y = 108; y <= 114; y++) for (int z = 527; z <= 533; z++)
			if (x == 479 || x == 485 || y == 108 || y == 114 || z == 527 || z == 533) blocks.add(new int[]{x, y, z});
		// A few stray barriers elsewhere
		blocks.add(new int[]{500, 100, 500});
		blocks.add(new int[]{501, 101, 500});
		blocks.add(new int[]{450, 90, 520});
		List<BarrierBoxes.Box> boxes = BarrierBoxes.group(blocks);
		assertEquals(3, boxes.size());
		BarrierBoxes.Box b = BarrierBoxes.pickCrystalBox(boxes, 20, 481, 114, 534);
		assertNotNull(b);
		assertArrayEquals(new int[]{479, 108, 527}, b.min());
		assertArrayEquals(new int[]{485, 114, 533}, b.max());
		assertEquals(482.5, b.centreX());
		assertNull(BarrierBoxes.pickCrystalBox(boxes, 1000, 0, 0, 0));
	}

	@Test
	void clearAllForgetsEverything() {
		StructureMap m = new StructureMap();
		m.observeNametag(Structure.KING_YOLKAR, "King Yolkar", 1, 2, 3, 0);
		long v = m.version();
		m.clearAll();
		assertTrue(m.version() > v);
		assertTrue(m.located().isEmpty());
		assertEquals(Structure.values().length, m.missing().size());
	}
}
