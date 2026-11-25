package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LobbyEvaluatorTest {
	private static Map<Structure, Vec3> compactLobby() {
		Map<Structure, Vec3> m = new EnumMap<>(Structure.class);
		m.put(Structure.MINES_OF_DIVAN, new Vec3(590, 60, 590));
		m.put(Structure.PRECURSOR_CITY, new Vec3(590, 60, 450));
		m.put(Structure.KING_YOLKAR, new Vec3(450, 120, 590));
		m.put(Structure.GOBLIN_QUEENS_DEN, new Vec3(440, 110, 610));
		m.put(Structure.JUNGLE_TEMPLE, new Vec3(450, 100, 450));
		m.put(Structure.KHAZAD_DUM, new Vec3(520, 40, 610));
		return m;
	}

	private static LobbyEvaluator.Reason rule(List<LobbyEvaluator.Reason> rs, LobbyEvaluator.Rule r) {
		return rs.stream().filter(x -> x.rule() == r).findFirst().orElseThrow();
	}

	@Test
	void aGoodLobbyGivesNoWarnings() {
		NrConfig cfg = new NrConfig();
		var facts = new LobbyEvaluator.Facts(compactLobby(), Map.of(), List.of(), 3, 0.4, 150);
		List<LobbyEvaluator.Reason> rs = LobbyEvaluator.evaluate(facts, cfg);
		assertEquals(4, rs.size());
		assertFalse(LobbyEvaluator.anyWarning(rs), rs.toString());
		assertTrue(rule(rs, LobbyEvaluator.Rule.RUN_TIME).checked());
	}

	@Test
	void eachRuleWarnsWithItsReason() {
		NrConfig cfg = new NrConfig();
		Map<Structure, Vec3> spread = new EnumMap<>(compactLobby());
		spread.put(Structure.KHAZAD_DUM, new Vec3(800, 40, 800));
		var facts = new LobbyEvaluator.Facts(spread, Map.of(), List.of(Structure.JUNGLE_TEMPLE), 9, 0.8, 150);
		List<LobbyEvaluator.Reason> rs = LobbyEvaluator.evaluate(facts, cfg);
		assertTrue(rule(rs, LobbyEvaluator.Rule.SPREAD).warn());
		assertTrue(rule(rs, LobbyEvaluator.Rule.SPREAD).message().contains("Khazad"), rule(rs, LobbyEvaluator.Rule.SPREAD).message());
		assertTrue(rule(rs, LobbyEvaluator.Rule.DAY).warn());
		assertTrue(rule(rs, LobbyEvaluator.Rule.COVERAGE).warn());
		assertTrue(rule(rs, LobbyEvaluator.Rule.COVERAGE).message().contains("Jungle Temple"));
	}

	@Test
	void rulesCanBeTurnedOff() {
		NrConfig cfg = new NrConfig();
		cfg.lobby.dayRule = false;
		cfg.lobby.coverageRule = false;
		var facts = new LobbyEvaluator.Facts(compactLobby(), Map.of(), List.of(Structure.JUNGLE_TEMPLE), 12, 0.9, 150);
		List<LobbyEvaluator.Reason> rs = LobbyEvaluator.evaluate(facts, cfg);
		assertFalse(rule(rs, LobbyEvaluator.Rule.DAY).checked());
		assertFalse(rule(rs, LobbyEvaluator.Rule.COVERAGE).warn());
	}

	@Test
	void runEstimateKeepsKingBeforeQueenAndUsesWarpNucleus() {
		NrConfig cfg = new NrConfig();
		Vec3 nucleus = new Vec3(513.5, 106, 526.5);
		RunEstimate.Result r = RunEstimate.estimate(compactLobby(), Map.of(), nucleus, cfg, 0.7, 150);
		assertNotNull(r);
		assertTrue(r.order().indexOf(Structure.KING_YOLKAR) < r.order().indexOf(Structure.GOBLIN_QUEENS_DEN));
		// Manual stops alone: temple 25 + Bal 20 + others; the total must at least cover those
		assertTrue(r.seconds() > 45 && r.seconds() < 240, "seconds " + r.seconds());

		Map<Structure, Vec3> missing = new EnumMap<>(compactLobby());
		missing.remove(Structure.KHAZAD_DUM);
		assertNull(RunEstimate.estimate(missing, Map.of(), nucleus, cfg, 0.7, 150));
	}

	@Test
	void farApartStructuresMakeTheRunSlower() {
		NrConfig cfg = new NrConfig();
		Vec3 nucleus = new Vec3(513.5, 106, 526.5);
		Map<Structure, Vec3> far = new EnumMap<>(Structure.class);
		far.put(Structure.MINES_OF_DIVAN, new Vec3(800, 60, 800));
		far.put(Structure.PRECURSOR_CITY, new Vec3(800, 60, 220));
		far.put(Structure.KING_YOLKAR, new Vec3(220, 120, 800));
		far.put(Structure.GOBLIN_QUEENS_DEN, new Vec3(220, 110, 760));
		far.put(Structure.JUNGLE_TEMPLE, new Vec3(220, 100, 220));
		far.put(Structure.KHAZAD_DUM, new Vec3(700, 40, 800));
		double near = RunEstimate.estimate(compactLobby(), Map.of(), nucleus, cfg, 0.7, 150).seconds();
		double slow = RunEstimate.estimate(far, Map.of(), nucleus, cfg, 0.7, 150).seconds();
		assertTrue(slow > near, slow + " vs " + near);
	}
}
