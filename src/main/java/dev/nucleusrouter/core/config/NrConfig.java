package dev.nucleusrouter.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable from SPEC.md §2, with the spec's defaults.
 * Plain Java (no Minecraft imports) so planners and tests can use it directly.
 *
 * Values marked [VERIFY] are unconfirmed game facts. When the user confirms one in-game,
 * update the default here AND the "Confirmed game facts" table in ROADMAP.md.
 *
 * Saved as JSON by the platform layer. Field names are the JSON keys, so renaming a field
 * resets that value for existing users.
 */
public final class NrConfig {
	public static final int CURRENT_VERSION = 3;
	public int configVersion = CURRENT_VERSION;

	public General general = new General();
	public Hollows hollows = new Hollows();
	public Stops stops = new Stops();

	/** Mod behaviour (not game mechanics). */
	public static final class General {
		/** Show short local chat notices, e.g. "Entered Crystal Hollows". Only you see them; nothing is sent. */
		public boolean chatNotices = true;
		/** Save each lobby's remembered terrain to disk, so a crash or reconnect into the same lobby keeps it. */
		public boolean saveLobbies = true;
	}
	public Etherwarp etherwarp = new Etherwarp();
	public Bombs bombs = new Bombs();
	public Placing placing = new Placing();
	public Cost cost = new Cost();

	/** §2.1 Crystal Hollows and the run. */
	public static final class Hollows {
		/** [VERIFY] Hollows bounds (community value). */
		public int minX = 202, maxX = 823;
		public int minY = 31, maxY = 188;
		public int minZ = 202, maxZ = 823;
		/** [VERIFY] Seconds from typing /warp nucleus until you can act again at the nucleus. */
		public double warpNucleusSeconds = 2.5;
		/** Lobbies close after roughly this many Minecraft days (lobby day = dayTime / 24000). */
		public int lobbyCloseDayMin = 15;
		public int lobbyCloseDayMax = 20;
	}

	/** §2.2 The stops: manual-segment times and the Queen's den rule. */
	public static final class Stops {
		/** Time limit to be back in "Goblin Holdout" after taking the Amber crystal. */
		public double queenDenExitLimitSeconds = 3.0;
		/** The planned den exit must fit inside this (margin below the limit). */
		public double queenDenExitTargetSeconds = 2.5;
		/** [VERIFY] Does /warp nucleus count as leaving the den in time? Default: no. */
		public boolean warpNucleusCountsAsLeavingDen = false;
		/** Jungle Temple parkour, done by hand (abilities don't work inside). */
		public double templeManualSeconds = 25.0;
		/** Bal fight at Khazad-dum. */
		public double balFightSeconds = 20.0;
		/** NPC hand-ins (each keeper, Professor Robot, King Yolkar, Door Guardian). Estimate; tune to taste. */
		public double npcInteractionSeconds = 1.0;
		/** Picking up a crystal once you're next to it. Estimate; tune to taste. */
		public double crystalPickupSeconds = 0.5;
	}

	/** §2.3 Etherwarp. */
	public static final class Etherwarp {
		/** 57 base + 1 per Transmission Tuner, max 61. */
		public int range = 61;
		/**
		 * Eye height above the feet that the server uses while crouching. Confirmed 2026-11-24 from 16 real
		 * warps: 1.235 to 1.290 fits all of them; 1.27 is modern Minecraft's crouching eye height.
		 */
		public double eyeHeight = 1.27;
		/** Air blocks needed above a target to land on it. */
		public int requiredAirAbove = 2;
		/** Aim-region precision: sample grid per visible target face (n x n). Higher is finer but slower. */
		public int aimSamplesPerFace = 8;
		/**
		 * Blocks that don't exist for etherwarp: the ray passes through them and they count as air above a
		 * landing block. Confirmed in-game 2026-11-24: water, lava, torches.
		 */
		public List<String> rayPassesThrough = new ArrayList<>(List.of("minecraft:water", "minecraft:lava", "minecraft:bubble_column",
				"minecraft:torch", "minecraft:wall_torch"));
		/**
		 * Treat every block you can walk through (no collision box) as not existing for etherwarp.
		 * Matches everything confirmed in-game 2026-11-24: water, lava, torches, grass, flowers.
		 */
		public boolean noCollisionPassesThrough = true;
	}

	/** §2.4 Bob-ombs. */
	public static final class Bombs {
		/** [VERIFY] Straight-line throw distance before it drops straight down. */
		public double throwRange = 45.0;
		/** [VERIFY] At max range the bomb drops straight down (true) rather than vanishing. */
		public boolean dropsStraightDownAtMaxRange = true;
		/** Fuse after impact (2.5 to 3 s). */
		public double fuseSeconds = 3.0;
		/** [VERIFY] Blast sphere radius. */
		public double blastRadius = 8.0;
		/** [VERIFY] Where the blast sphere is centred. */
		public BlastCentre blastCentre = BlastCentre.IMPACT_BLOCK_CENTRE;
		/** [VERIFY] Which vanilla blocks count as Hard Stone (broken for good). */
		public List<String> hardStoneBlocks = new ArrayList<>(List.of("minecraft:stone"));
		/** Blocks bombs never break. An entry starting with '*' matches by suffix, e.g. "*_log". */
		public List<String> bombImmuneBlocks = new ArrayList<>(List.of(
				"*_log", "*_wood", "*_planks", "*_stem", "*_hyphae", "*_fence",
				"minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol",
				"minecraft:rooted_dirt", "minecraft:mud", "minecraft:dirt_path", "minecraft:farmland",
				"minecraft:mycelium", "minecraft:muddy_mangrove_roots",
				"minecraft:bedrock", "minecraft:barrier"));
	}

	public enum BlastCentre { IMPACT_BLOCK_CENTRE, IMPACT_POINT }

	/** §2.5 Placing blocks. */
	public static final class Placing {
		/** Block reach used for planned placements. */
		public double reach = 4.5;
		/** [VERIFY] Placing allowed everywhere in the Hollows outside structure regions. */
		public boolean allowedEverywhereOutsideStructures = true;
		/**
		 * Blocks you place are detected automatically, whatever their type. This list is a fallback:
		 * these types also count as placed when they appear in air without the mod seeing you place them
		 * (e.g. placed by another player).
		 */
		public List<String> placedBlockTypes = new ArrayList<>(List.of("minecraft:cobblestone"));
	}

	/** §2.6 Cost model (all in seconds). */
	public static final class Cost {
		/** Sneak + click + server round trip, before ping. */
		public double baseWarpSeconds = 0.20;
		/** Add measured ping / 2 to every warp. */
		public boolean useMeasuredPing = true;
		/** Ping used when none can be measured, or when useMeasuredPing is off. */
		public int fallbackPingMs = 100;
		/** Extra time for a 90 degree turn (0 s at 0 degrees). */
		public double turnSecondsAt90 = 0.40;
		/** Shape of the turn-time curve: time = turnSecondsAt90 * (angle / 90)^exponent. */
		public double turnExponent = 1.5;
		/** Time lost when a warp lands in the wrong spot. */
		public double missRecoverySeconds = 1.5;
	}

	/**
	 * Updates a config saved by an older version, keeping the user's own changes.
	 * Returns one readable line per change.
	 */
	public List<String> migrate() {
		List<String> notes = new ArrayList<>();
		if (configVersion < 2) {
			if (etherwarp != null) {
				// 1.54 was the untested spec default; only replace it if the user never changed it
				if (etherwarp.eyeHeight == 1.54) { etherwarp.eyeHeight = 1.27; notes.add("etherwarp.eyeHeight 1.54 -> 1.27 (measured in-game)"); }
				if (etherwarp.rayPassesThrough != null) {
					for (String t : List.of("minecraft:torch", "minecraft:wall_torch")) {
						if (!etherwarp.rayPassesThrough.contains(t)) { etherwarp.rayPassesThrough.add(t); notes.add("etherwarp.rayPassesThrough + " + t); }
					}
				}
			}
			configVersion = 2;
		}
		if (configVersion < 3) {
			// Confirmed in-game 2026-11-24: bombs don't break fences
			if (bombs != null && bombs.bombImmuneBlocks != null && !bombs.bombImmuneBlocks.contains("*_fence")) {
				bombs.bombImmuneBlocks.add("*_fence");
				notes.add("bombs.bombImmuneBlocks + *_fence");
			}
			configVersion = 3;
		}
		return notes;
	}

	/**
	 * Clamps values into sane ranges and returns one readable line per fix,
	 * so a hand-edited config can never crash the planner.
	 */
	public List<String> validate() {
		List<String> fixes = new ArrayList<>();
		if (general == null) { general = new General(); fixes.add("general section missing, reset to defaults"); }
		if (hollows == null) { hollows = new Hollows(); fixes.add("hollows section missing, reset to defaults"); }
		if (stops == null) { stops = new Stops(); fixes.add("stops section missing, reset to defaults"); }
		if (etherwarp == null) { etherwarp = new Etherwarp(); fixes.add("etherwarp section missing, reset to defaults"); }
		if (bombs == null) { bombs = new Bombs(); fixes.add("bombs section missing, reset to defaults"); }
		if (placing == null) { placing = new Placing(); fixes.add("placing section missing, reset to defaults"); }
		if (cost == null) { cost = new Cost(); fixes.add("cost section missing, reset to defaults"); }

		Hollows h = hollows;
		if (h.minX > h.maxX) { int t = h.minX; h.minX = h.maxX; h.maxX = t; fixes.add("hollows minX > maxX, swapped"); }
		if (h.minY > h.maxY) { int t = h.minY; h.minY = h.maxY; h.maxY = t; fixes.add("hollows minY > maxY, swapped"); }
		if (h.minZ > h.maxZ) { int t = h.minZ; h.minZ = h.maxZ; h.maxZ = t; fixes.add("hollows minZ > maxZ, swapped"); }
		h.warpNucleusSeconds = clamp(fixes, "hollows.warpNucleusSeconds", h.warpNucleusSeconds, 0, 30);
		if (h.lobbyCloseDayMin > h.lobbyCloseDayMax) { h.lobbyCloseDayMin = h.lobbyCloseDayMax; fixes.add("hollows.lobbyCloseDayMin > lobbyCloseDayMax, lowered"); }

		stops.queenDenExitLimitSeconds = clamp(fixes, "stops.queenDenExitLimitSeconds", stops.queenDenExitLimitSeconds, 0.5, 10);
		stops.queenDenExitTargetSeconds = clamp(fixes, "stops.queenDenExitTargetSeconds", stops.queenDenExitTargetSeconds, 0.1, stops.queenDenExitLimitSeconds);
		stops.templeManualSeconds = clamp(fixes, "stops.templeManualSeconds", stops.templeManualSeconds, 0, 300);
		stops.balFightSeconds = clamp(fixes, "stops.balFightSeconds", stops.balFightSeconds, 0, 300);
		stops.npcInteractionSeconds = clamp(fixes, "stops.npcInteractionSeconds", stops.npcInteractionSeconds, 0, 30);
		stops.crystalPickupSeconds = clamp(fixes, "stops.crystalPickupSeconds", stops.crystalPickupSeconds, 0, 30);

		etherwarp.range = (int) clamp(fixes, "etherwarp.range", etherwarp.range, 57, 61);
		etherwarp.eyeHeight = clamp(fixes, "etherwarp.eyeHeight", etherwarp.eyeHeight, 1.0, 1.8);
		etherwarp.aimSamplesPerFace = (int) clamp(fixes, "etherwarp.aimSamplesPerFace", etherwarp.aimSamplesPerFace, 2, 32);
		etherwarp.requiredAirAbove = (int) clamp(fixes, "etherwarp.requiredAirAbove", etherwarp.requiredAirAbove, 1, 3);

		bombs.throwRange = clamp(fixes, "bombs.throwRange", bombs.throwRange, 1, 100);
		bombs.fuseSeconds = clamp(fixes, "bombs.fuseSeconds", bombs.fuseSeconds, 0, 10);
		bombs.blastRadius = clamp(fixes, "bombs.blastRadius", bombs.blastRadius, 1, 20);
		if (bombs.blastCentre == null) { bombs.blastCentre = BlastCentre.IMPACT_BLOCK_CENTRE; fixes.add("bombs.blastCentre unknown, reset"); }
		if (bombs.hardStoneBlocks == null) { bombs.hardStoneBlocks = new Bombs().hardStoneBlocks; fixes.add("bombs.hardStoneBlocks missing, reset"); }
		if (bombs.bombImmuneBlocks == null) { bombs.bombImmuneBlocks = new Bombs().bombImmuneBlocks; fixes.add("bombs.bombImmuneBlocks missing, reset"); }

		if (etherwarp.rayPassesThrough == null) { etherwarp.rayPassesThrough = new Etherwarp().rayPassesThrough; fixes.add("etherwarp.rayPassesThrough missing, reset"); }
		if (placing.placedBlockTypes == null) { placing.placedBlockTypes = new Placing().placedBlockTypes; fixes.add("placing.placedBlockTypes missing, reset"); }
		placing.reach = clamp(fixes, "placing.reach", placing.reach, 1, 6);

		cost.baseWarpSeconds = clamp(fixes, "cost.baseWarpSeconds", cost.baseWarpSeconds, 0, 5);
		cost.fallbackPingMs = (int) clamp(fixes, "cost.fallbackPingMs", cost.fallbackPingMs, 0, 2000);
		cost.turnSecondsAt90 = clamp(fixes, "cost.turnSecondsAt90", cost.turnSecondsAt90, 0, 5);
		cost.turnExponent = clamp(fixes, "cost.turnExponent", cost.turnExponent, 0.5, 4);
		cost.missRecoverySeconds = clamp(fixes, "cost.missRecoverySeconds", cost.missRecoverySeconds, 0, 30);
		return fixes;
	}

	private static double clamp(List<String> fixes, String name, double v, double min, double max) {
		if (Double.isNaN(v)) { fixes.add(name + " was not a number, set to " + min); return min; }
		if (v < min) { fixes.add(name + " = " + v + " is below " + min + ", clamped"); return min; }
		if (v > max) { fixes.add(name + " = " + v + " is above " + max + ", clamped"); return max; }
		return v;
	}
}
