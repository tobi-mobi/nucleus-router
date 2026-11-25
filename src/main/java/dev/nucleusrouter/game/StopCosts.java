package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;

/**
 * Time spent at each structure (SPEC §2.2, §2.6), from the config: NPC hand-ins, crystal pickups, moving
 * around inside (the Mines' keepers, Precursor's short warp to the Sapphire), the Jungle Temple parkour
 * (by hand), the Bal fight and the Queen's den exit. The route only plans travel between structures.
 */
public final class StopCosts {
	private StopCosts() {}

	public static double seconds(Structure s, NrConfig cfg) {
		NrConfig.Stops st = cfg.stops;
		return switch (s) {
			case MINES_OF_DIVAN -> 4 * st.npcInteractionSeconds + st.crystalPickupSeconds + st.divanInsideSeconds; // 4 keepers, the Jade crystal, moving inside
			case PRECURSOR_CITY -> st.npcInteractionSeconds + st.crystalPickupSeconds + st.precursorInsideSeconds;
			case KING_YOLKAR -> st.npcInteractionSeconds;
			// Leaving with /warp nucleus is route travel; an etherwarp exit is planned to take up to the target
			case GOBLIN_QUEENS_DEN -> st.crystalPickupSeconds
					+ (StopPlan.denExit(cfg) == StopPlan.DenExit.ETHERWARP_UP ? st.queenDenExitTargetSeconds : 0);
			case JUNGLE_TEMPLE -> st.npcInteractionSeconds + st.templeManualSeconds + st.crystalPickupSeconds;
			case KHAZAD_DUM -> st.balFightSeconds + st.crystalPickupSeconds;
		};
	}

	/** True for stops whose time is a hand-done "manual segment" (drawn dashed in run mode). */
	public static boolean hasManualSegment(Structure s) {
		return s == Structure.JUNGLE_TEMPLE || s == Structure.KHAZAD_DUM;
	}
}
