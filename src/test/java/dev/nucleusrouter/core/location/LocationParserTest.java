package dev.nucleusrouter.core.location;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LocationParserTest {
	private static final Set<String> SUB_AREAS = Set.of("Goblin Holdout", "Khazad-dûm", "Crystal Nucleus");

	@Test
	void cleanStripsColourCodesAndEmojiFiller() {
		assertEquals("⏣ Goblin Holdout", LocationParser.clean("§7⏣ §6Goblin§6 Holdout🎂"));
		assertEquals("Khazad-dûm", LocationParser.clean(" §cKhazad-dûm "));
		assertEquals("", LocationParser.clean(null));
	}

	@Test
	void serverIdsFromDifferentSourcesAgree() {
		assertEquals("mini123AB", LocationParser.canonicalServerId("m123AB"));
		assertEquals("mini123AB", LocationParser.canonicalServerId("mini123AB"));
		assertEquals("mega12A", LocationParser.canonicalServerId("M12A"));
		assertNull(LocationParser.canonicalServerId("  "));
	}

	@Test
	void modApi() {
		LocationReading r = LocationParser.fromModApi("mini123AB", "SKYBLOCK", "crystal_hollows", "Crystal Hollows");
		assertTrue(r.inHollows());
		assertEquals("mini123AB", r.serverId());
		assertFalse(LocationParser.fromModApi("mini1A", "SKYBLOCK", "mining_3", "Dwarven Mines").inHollows());
		assertFalse(LocationParser.fromModApi("mini1A", "BEDWARS", "crystal_hollows", null).inHollows());
	}

	@Test
	void tabList() {
		LocationReading r = LocationParser.fromTabList(List.of(
				"§b§lArea: §7Crystal Hollows", " Server: §8mini45C", "Ping: 87ms", "Some player"));
		assertNotNull(r);
		assertTrue(r.inHollows());
		assertEquals("mini45C", r.serverId());
		assertEquals(87, r.pingMs());
		assertNull(LocationParser.fromTabList(List.of("Steve", "Alex")));
		assertFalse(LocationParser.fromTabList(List.of("Area: Hub")).inHollows());
	}

	@Test
	void scoreboard() {
		List<String> lines = List.of("§711/24/26 §8m45C", " ", " Late Winter 5th", " §72:40pm ☀", " §7⏣ §6Goblin Holdout", "Purse: 1,000");
		LocationReading r = LocationParser.fromScoreboard("§e§lSKYBLOCK", lines, SUB_AREAS);
		assertNotNull(r);
		assertTrue(r.inHollows());
		assertEquals("mini45C", r.serverId());
		assertEquals("Goblin Holdout", r.area());
		assertNull(LocationParser.fromScoreboard("BED WARS", lines, SUB_AREAS));
		assertFalse(LocationParser.fromScoreboard("SKYBLOCK", List.of("⏣ Village"), SUB_AREAS).inHollows());
		assertEquals("Village", LocationParser.fromScoreboard("SKYBLOCK", List.of("⏣ Village"), SUB_AREAS).area());
	}

	/** Real sidebar from 2026-11-24 in the Mines of Divan: the area symbol is a custom-font character. */
	@Test
	void scoreboardWithCustomFontSymbol() {
		Set<String> areas = Set.of("Mines of Divan", "Goblin Holdout");
		List<String> real = List.of("11/24/26 §8m89R", "Late Winter 13th", "5:30am", " §7 §bMines of Divan",
				"Purse: 50,000", "Bits: 70,335", "Slayer Quest", "Revenant Horror III", "Boss slain!", "(4)", "www.hypixel.net");
		LocationReading r = LocationParser.fromScoreboard("SKYBLOCK", real, areas);
		assertEquals("Mines of Divan", r.area());
		assertTrue(r.inHollows());
		assertEquals("mini89R", r.serverId());
		// Unknown area outside the list is still found via the custom-font marker
		assertEquals("Dwarven Village", LocationParser.scoreboardArea(List.of("Purse: 5", " Dwarven Village"), areas));
		// Exact known name even with no marker at all
		assertEquals("Goblin Holdout", LocationParser.scoreboardArea(List.of("Bits: 1", "goblin holdout"), areas));
	}

	@Test
	void resolvePrefersModApiThenTabThenScoreboard() {
		LocationReading api = new LocationReading(LocationReading.Source.MOD_API, true, "mini1A", "Crystal Hollows", null);
		LocationReading tab = new LocationReading(LocationReading.Source.TAB_LIST, false, "mini2B", "Hub", 50);
		LocationReading sb = new LocationReading(LocationReading.Source.SCOREBOARD, false, "mini3C", "Village", null);

		LocationState s = LocationState.resolve(api, tab, sb, 48_000 + 100, true, 0, true, 100);
		assertTrue(s.inHollows());
		assertEquals(LocationReading.Source.MOD_API, s.hollowsSource());
		assertEquals("mini1A", s.serverId());
		assertEquals("Village", s.scoreboardArea());
		assertEquals(2, s.lobbyDay());
		assertEquals(50, s.pingMs());
		assertEquals(LocationState.PingSource.TAB_WIDGET, s.pingSource());

		s = LocationState.resolve(null, tab, sb, 0, true, 42, true, 100);
		assertEquals(LocationReading.Source.TAB_LIST, s.hollowsSource());
		assertEquals("mini2B", s.serverId());
		assertEquals(42, s.pingMs());

		// Hypixel's fake 1 ms latency is ignored
		s = LocationState.resolve(null, null, sb, 0, true, 1, true, 130);
		assertEquals(130, s.pingMs());
		assertEquals(LocationState.PingSource.FALLBACK_CONFIG, s.pingSource());

		s = LocationState.resolve(null, null, sb, 0, false, 0, false, 120);
		assertEquals(LocationReading.Source.SCOREBOARD, s.hollowsSource());
		assertEquals(-1, s.lobbyDay());
		assertEquals(120, s.pingMs());
		assertEquals(LocationState.PingSource.FALLBACK_CONFIG, s.pingSource());

		assertEquals(LocationState.NONE.inHollows(), LocationState.resolve(null, null, null, 0, false, 0, true, 100).inHollows());
	}

	@Test
	void lobbyDay() {
		assertEquals(0, LocationState.lobbyDay(23_999));
		assertEquals(1, LocationState.lobbyDay(24_000));
		assertEquals(15, LocationState.lobbyDay(15 * 24_000 + 5));
	}
}
