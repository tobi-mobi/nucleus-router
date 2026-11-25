package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;

/**
 * Fixed time spent at each stop (SPEC §2.2, §2.6), from the config: NPC hand-ins, crystal pickups, the
 * Jungle Temple parkour (done by hand), the Bal fight and the Queen's den exit. Travel between the four
 * Mines of Divan keepers is route travel, not part of this.
 */
public final class StopCosts {
	private StopCosts() {}

	public static double seconds(Structure s, NrConfig cfg) {
		NrConfig.Stops st = cfg.stops;
		return switch (s) {
			case MINES_OF_DIVAN -> 4 * st.npcInteractionSeconds + st.crystalPickupSeconds; // 4 keepers + the Jade crystal
			case PRECURSOR_CITY -> st.npcInteractionSeconds + st.crystalPickupSeconds;
			case KING_YOLKAR -> st.npcInteractionSeconds;
			case GOBLIN_QUEENS_DEN -> st.crystalPickupSeconds + st.queenDenExitTargetSeconds;
			case JUNGLE_TEMPLE -> st.npcInteractionSeconds + st.templeManualSeconds + st.crystalPickupSeconds;
			case KHAZAD_DUM -> st.balFightSeconds + st.crystalPickupSeconds;
		};
	}

	/** True for stops whose time is a hand-done "manual segment" (drawn dashed in run mode). */
	public static boolean hasManualSegment(Structure s) {
		return s == Structure.JUNGLE_TEMPLE || s == Structure.KHAZAD_DUM;
	}
}
