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
