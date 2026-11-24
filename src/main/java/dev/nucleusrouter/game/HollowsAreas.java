package dev.nucleusrouter.game;

import java.util.Set;

/**
 * Sidebar location names that only appear inside the Crystal Hollows. Used only as the last
 * fallback for "am I in the Hollows?" when neither the Mod API nor the tab list answers.
 * [VERIFY] Spelling is checked with /nr where while standing in each area.
 */
public final class HollowsAreas {
	public static final String GOBLIN_HOLDOUT = "Goblin Holdout";
	public static final String GOBLIN_QUEENS_DEN = "Goblin Queen's Den";

	public static final Set<String> ALL = Set.of(
			"Crystal Hollows", "Crystal Nucleus",
			"Jungle", "Jungle Temple",
			"Mithril Deposits", "Mines of Divan",
			"Precursor Remnants", "Lost Precursor City",
			GOBLIN_HOLDOUT, GOBLIN_QUEENS_DEN,
			"Magma Fields", "Khazad-dûm",
			"Fairy Grotto", "Dragon's Lair");

	private HollowsAreas() {}
}
