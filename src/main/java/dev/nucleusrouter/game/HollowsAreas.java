package dev.nucleusrouter.game;

import java.util.Set;

/**
 * Sidebar location names that only appear inside the Crystal Hollows. Used only as the last
 * fallback for "am I in the Hollows?" when neither the Mod API nor the tab list answers.
 * Confirmed 2026-11-24 with /nr where in the areas the user visited.
 */
public final class HollowsAreas {
	public static final String GOBLIN_HOLDOUT = "Goblin Holdout";
	/**
	 * Shown for the whole den area. NOT the same as the 3 s Amber timer zone, which is only the
	 * Queen's castle in the centre (confirmed 2026-11-24). Don't use this name as the den-exit check.
	 */
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
