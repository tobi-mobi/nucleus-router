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
	public static final int CURRENT_VERSION = 6;
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
	public Structures structures = new Structures();
	public Compass compass = new Compass();
	public Exploration exploration = new Exploration();
	public Lobby lobby = new Lobby();
	public Hud hud = new Hud();

	/** §2.1 Crystal Hollows and the run. */
	public static final class Hollows {
		/** Hollows bounds. Confirmed 2026-11-24: you can stand from x/z 202 to 823 and y 31 to 187. */
		public int minX = 202, maxX = 823;
		public int minY = 31, maxY = 188;
		public int minZ = 202, maxZ = 823;
		/**
		 * Seconds a /warp nucleus costs, from deciding to warp until you can act at the nucleus.
		 * Confirmed 2026-11-24: less than a second in total, arrival is instant.
		 */
		public double warpNucleusSeconds = 1.0;
		/** Where /warp nucleus puts you (feet position). Confirmed 2026-11-24. */
		public double nucleusSpawnX = 513.5, nucleusSpawnY = 106, nucleusSpawnZ = 526.5;
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
		/**
		 * Leaving the Queen's castle upwards: this many blocks above the Amber crystal counts as out
		 * (confirmed 2026-11-24: 30). Every side of the castle counts as out too.
		 */
		public int denExitHeightAboveCrystal = 30;
		/** /warp nucleus right after taking the Amber crystal keeps it (confirmed 2026-11-24). */
		public boolean warpNucleusCountsAsLeavingDen = true;
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
		/**
		 * Straight-line throw distance before the bomb drops straight down. Measured 2026-11-24: a wall
		 * 31.7 blocks away was hit directly; aimed at a wall 39.7 away it dropped after about 32.2 blocks.
		 */
		public double throwRange = 32.0;
		/** At max range the bomb drops straight down (confirmed 2026-11-24). */
		public boolean dropsStraightDownAtMaxRange = true;
		/** Fuse after impact. Estimated 2026-11-24 from 4 throws: about 2.3 s fuse plus the flight. */
		public double fuseSeconds = 2.3;
		/** Bomb flight speed, estimated 2026-11-24 from 4 throws (about 25 blocks per second). */
		public double flightBlocksPerSecond = 25.0;
		/**
		 * Blast radius: every breakable block whose centre is within this distance of the blast centre breaks.
		 * Confirmed 2026-11-24: 6 throws, all a ball of radius 7.0 (100% match on clean hits).
		 */
		public double blastRadius = 7.0;
		/** Where the blast is centred. Confirmed 2026-11-24 for direct hits: centre of the block hit. */
		public BlastCentre blastCentre = BlastCentre.IMPACT_BLOCK_CENTRE;
		/**
		 * [VERIFY] For a bomb that dropped at max range, the blast centre is this many blocks above the block it
		 * landed on. One throw onto a flat crater floor measured 1 exactly; one into a deeper crater suggested 2.
		 */
		public int dropCentreBlocksAbove = 1;
		/** Which vanilla blocks count as Hard Stone (broken for good). Confirmed: minecraft:stone. */
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
	 * §3.5, §4 Finding structures. A name tag matches if it contains one of these phrases as whole words
	 * (case and colours ignored). [VERIFY] the exact name tags in-game; edit these if one isn't detected.
	 */
	public static final class Structures {
		/** Draw found structures in the world (through walls) with their names. */
		public boolean showMarkers = true;
		/**
		 * Note the scoreboard area name as a rough position when no name tag has been seen. Off by default:
		 * the user found it wrong most of the time (2026-11-24). Never drawn in the world.
		 */
		public boolean useAreaHints = false;
		/**
		 * Learn each structure's crystal box (and the offset to its NPC) the first time it is found, then find
		 * it from the crystal alone in later lobbies, as soon as the terrain loads.
		 */
		public boolean learnFromCrystals = true;
		/** Also use Skyblocker's Crystal Hollows waypoints (read-only) as approximate positions. */
		public boolean useSkyblocker = true;
		/**
		 * Which of Skyblocker's own Crystal Hollows waypoints Skyblocker still draws (less clutter).
		 * Only the drawing is skipped; Skyblocker keeps collecting them and this mod keeps reading them.
		 */
		public SkyblockerWaypoints skyblockerWaypoints = SkyblockerWaypoints.HIDE_RUN_STRUCTURES;
		/**
		 * The Queen's den and Khazad-dum crystals sit in a box of barrier blocks (seen in-game 2026-11-24).
		 * A clump of at least this many barriers counts as such a box.
		 */
		public int crystalBoxMinBarriers = 20;
		/** How often to look at nearby name tags, in ticks (20 = once a second). */
		public int scanEveryTicks = 10;
		public List<String> minesOfDivanNames = new ArrayList<>(List.of("Keeper of"));
		public List<String> precursorCityNames = new ArrayList<>(List.of("Professor Robot"));
		public List<String> kingYolkarNames = new ArrayList<>(List.of("King Yolkar"));
		public List<String> goblinQueenNames = new ArrayList<>(List.of("Goblin Queen"));
		public List<String> jungleTempleNames = new ArrayList<>(List.of("Kalhuiki Door Guardian"));
		public List<String> khazadDumNames = new ArrayList<>(List.of("Bal"));

		/**
		 * Compare captures from other lobbies to detect if a structure is rotated or mirrored. Off by default:
		 * confirmed in-game 2026-11-25 that structures don't rotate, so a match could only be wrong.
		 */
		public boolean detectOrientation = false;
		/** Draw each found structure's no-bomb / no-placing zone as a red box. */
		public boolean showZones = true;
		/**
		 * [VERIFY] No-bomb / no-placing zone per structure, as {minDx, minDy, minDz, maxDx, maxDy, maxDz}
		 * blocks from the anchor block (NPC, keepers' centre or crystal), in the structure's own orientation.
		 * Defaults are a generous guess; measure with /nr zone and /nr calibrate bomb near the structure.
		 * Keys: divan, precursor, king, queen, temple, khazad.
		 */
		public java.util.Map<String, int[]> zones = defaultZones();

		public static java.util.Map<String, int[]> defaultZones() {
			java.util.Map<String, int[]> m = new java.util.LinkedHashMap<>();
			for (String k : List.of("divan", "precursor", "king", "queen", "temple", "khazad")) m.put(k, new int[]{-40, -40, -40, 40, 40, 40});
			return m;
		}
	}

	/** On-screen list of structures (found / approximate / missing). */
	public static final class Hud {
		public boolean showStructureList = true;
		/** Hide the list 10 s after every structure is found. */
		public boolean hideWhenAllFound = true;
		/** Top-left corner of the list, in GUI pixels. */
		public int x = 4, y = 40;
	}

	/** §4 Lobby evaluation: when to suggest another lobby. Each rule can be turned off. */
	public static final class Lobby {
		/** Show a chat notice when a rule starts saying "consider another lobby". */
		public boolean notify = true;
		public boolean spreadRule = true;
		/** Warn if two structures are further apart than this (blocks). */
		public int maxSpreadBlocks = 250;
		public boolean dayRule = true;
		/** Warn if the lobby day is above this (lobbies close around day 15-20). */
		public int warnAfterDay = 6;
		public boolean coverageRule = true;
		/** Warn if this share of the Hollows is explored and structures are still missing (0..1). */
		public double coverageWarnFraction = 0.75;
		public boolean runTimeRule = true;
		/** Warn if the rough run estimate is above this (seconds). Target is 2-4 minutes. */
		public int warnRunSeconds = 240;
		/**
		 * Rough estimate only: average share of the full etherwarp range one warp covers along a leg.
		 * Replaced by the real planner in milestone 3.
		 */
		public double legEfficiency = 0.7;
	}

	/** §4 Exploration coverage. */
	public static final class Exploration {
		/**
		 * Columns within this many blocks of you count as explored. Estimate: about the distance at which
		 * NPC name tags (and so structures) are found. Tune to taste.
		 */
		public double visitRadius = 48;
	}

	/**
	 * §4 Wishing Compass solver. [VERIFY] how the compass trail looks in-game: which particle type, how
	 * long it lasts, and whether every use points at the same structure. The mod picks the straightest
	 * particle trail by itself, so these rarely need changing.
	 */
	public static final class Compass {
		/** Item name that marks a Wishing Compass use (right-click). */
		public String itemName = "Wishing Compass";
		/** How long after a use particles are collected, in seconds. */
		public double captureSeconds = 3.0;
		/** Particles further than this from you are ignored, in blocks. */
		public double captureRadius = 40;
		/** A trail needs at least this many particles. */
		public int minPoints = 5;
		/** Particle types that can form the trail, e.g. "minecraft:happy_villager". Empty = any type (the straightest wins). */
		public List<String> particleTypes = new ArrayList<>();
		/** Once the estimate is this sure (95% radius, blocks), stop suggesting another compass. */
		public double goodRadiusBlocks = 10;
		/** Draw compass lines, the estimate and the suggested next spot. */
		public boolean showInWorld = true;
	}

	public enum SkyblockerWaypoints {
		/** Skyblocker draws all of them as usual. */
		SHOW,
		/** Hide the 6 run structures this mod already marks; keep the rest (Fairy Grotto, Corleone, ...). */
		HIDE_RUN_STRUCTURES,
		/** Hide all of Skyblocker's Crystal Hollows waypoints. */
		HIDE_ALL
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
		if (configVersion < 4) {
			// Bomb and /warp nucleus facts measured in-game 2026-11-24; only replace untouched old defaults
			if (bombs != null) {
				if (bombs.blastRadius == 8.0) { bombs.blastRadius = 7.0; notes.add("bombs.blastRadius 8 -> 7 (measured)"); }
				if (bombs.throwRange == 45.0) { bombs.throwRange = 32.0; notes.add("bombs.throwRange 45 -> 32 (measured)"); }
				if (bombs.fuseSeconds == 3.0) { bombs.fuseSeconds = 2.3; notes.add("bombs.fuseSeconds 3.0 -> 2.3 (measured)"); }
			}
			if (stops != null && !stops.warpNucleusCountsAsLeavingDen) {
				stops.warpNucleusCountsAsLeavingDen = true;
				notes.add("stops.warpNucleusCountsAsLeavingDen -> true (confirmed)");
			}
			configVersion = 4;
		}
		if (configVersion < 5) {
			// User 2026-11-24: rough area positions were wrong ~90% of the time
			if (structures != null && structures.useAreaHints) { structures.useAreaHints = false; notes.add("structures.useAreaHints -> off"); }
			configVersion = 5;
		}
		if (configVersion < 6) {
			// Confirmed 2026-11-24: /warp nucleus takes less than a second
			if (hollows != null && hollows.warpNucleusSeconds == 2.5) { hollows.warpNucleusSeconds = 1.0; notes.add("hollows.warpNucleusSeconds 2.5 -> 1.0 (confirmed)"); }
			configVersion = 6;
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
		if (structures == null) { structures = new Structures(); fixes.add("structures section missing, reset to defaults"); }
		if (compass == null) { compass = new Compass(); fixes.add("compass section missing, reset to defaults"); }
		if (exploration == null) { exploration = new Exploration(); fixes.add("exploration section missing, reset to defaults"); }
		if (lobby == null) { lobby = new Lobby(); fixes.add("lobby section missing, reset to defaults"); }
		if (hud == null) { hud = new Hud(); fixes.add("hud section missing, reset to defaults"); }
		hud.x = (int) clamp(fixes, "hud.x", hud.x, 0, 4000);
		hud.y = (int) clamp(fixes, "hud.y", hud.y, 0, 4000);
		lobby.maxSpreadBlocks = (int) clamp(fixes, "lobby.maxSpreadBlocks", lobby.maxSpreadBlocks, 50, 1000);
		lobby.warnAfterDay = (int) clamp(fixes, "lobby.warnAfterDay", lobby.warnAfterDay, 0, 30);
		lobby.coverageWarnFraction = clamp(fixes, "lobby.coverageWarnFraction", lobby.coverageWarnFraction, 0.1, 1);
		lobby.warnRunSeconds = (int) clamp(fixes, "lobby.warnRunSeconds", lobby.warnRunSeconds, 30, 1800);
		lobby.legEfficiency = clamp(fixes, "lobby.legEfficiency", lobby.legEfficiency, 0.2, 1);
		exploration.visitRadius = clamp(fixes, "exploration.visitRadius", exploration.visitRadius, 8, 128);

		Hollows h = hollows;
		if (h.minX > h.maxX) { int t = h.minX; h.minX = h.maxX; h.maxX = t; fixes.add("hollows minX > maxX, swapped"); }
		if (h.minY > h.maxY) { int t = h.minY; h.minY = h.maxY; h.maxY = t; fixes.add("hollows minY > maxY, swapped"); }
		if (h.minZ > h.maxZ) { int t = h.minZ; h.minZ = h.maxZ; h.maxZ = t; fixes.add("hollows minZ > maxZ, swapped"); }
		h.warpNucleusSeconds = clamp(fixes, "hollows.warpNucleusSeconds", h.warpNucleusSeconds, 0, 30);
		if (h.lobbyCloseDayMin > h.lobbyCloseDayMax) { h.lobbyCloseDayMin = h.lobbyCloseDayMax; fixes.add("hollows.lobbyCloseDayMin > lobbyCloseDayMax, lowered"); }

		stops.queenDenExitLimitSeconds = clamp(fixes, "stops.queenDenExitLimitSeconds", stops.queenDenExitLimitSeconds, 0.5, 10);
		stops.queenDenExitTargetSeconds = clamp(fixes, "stops.queenDenExitTargetSeconds", stops.queenDenExitTargetSeconds, 0.1, stops.queenDenExitLimitSeconds);
		stops.denExitHeightAboveCrystal = (int) clamp(fixes, "stops.denExitHeightAboveCrystal", stops.denExitHeightAboveCrystal, 1, 100);
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
		bombs.flightBlocksPerSecond = clamp(fixes, "bombs.flightBlocksPerSecond", bombs.flightBlocksPerSecond, 1, 200);
		bombs.dropCentreBlocksAbove = (int) clamp(fixes, "bombs.dropCentreBlocksAbove", bombs.dropCentreBlocksAbove, 0, 3);
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
		if (compass.itemName == null || compass.itemName.isBlank()) { compass.itemName = "Wishing Compass"; fixes.add("compass.itemName empty, reset"); }
		if (compass.particleTypes == null) { compass.particleTypes = new ArrayList<>(); fixes.add("compass.particleTypes missing, reset"); }
		compass.captureSeconds = clamp(fixes, "compass.captureSeconds", compass.captureSeconds, 0.5, 15);
		compass.captureRadius = clamp(fixes, "compass.captureRadius", compass.captureRadius, 5, 128);
		compass.minPoints = (int) clamp(fixes, "compass.minPoints", compass.minPoints, 3, 100);
		compass.goodRadiusBlocks = clamp(fixes, "compass.goodRadiusBlocks", compass.goodRadiusBlocks, 1, 100);
		if (structures.skyblockerWaypoints == null) { structures.skyblockerWaypoints = SkyblockerWaypoints.HIDE_RUN_STRUCTURES; fixes.add("structures.skyblockerWaypoints unknown, reset"); }
		if (structures.zones == null) { structures.zones = Structures.defaultZones(); fixes.add("structures.zones missing, reset"); }
		for (java.util.Map.Entry<String, int[]> z : Structures.defaultZones().entrySet()) {
			int[] v = structures.zones.get(z.getKey());
			if (v == null || v.length != 6) { structures.zones.put(z.getKey(), z.getValue()); fixes.add("structures.zones." + z.getKey() + " missing or not 6 numbers, reset"); continue; }
			for (int i = 0; i < 3; i++) if (v[i] > v[i + 3]) { int t = v[i]; v[i] = v[i + 3]; v[i + 3] = t; fixes.add("structures.zones." + z.getKey() + " min > max, swapped"); }
		}
		structures.crystalBoxMinBarriers = (int) clamp(fixes, "structures.crystalBoxMinBarriers", structures.crystalBoxMinBarriers, 4, 10000);
		structures.scanEveryTicks = (int) clamp(fixes, "structures.scanEveryTicks", structures.scanEveryTicks, 1, 200);
		Structures sd = new Structures();
		if (structures.minesOfDivanNames == null) { structures.minesOfDivanNames = sd.minesOfDivanNames; fixes.add("structures.minesOfDivanNames missing, reset"); }
		if (structures.precursorCityNames == null) { structures.precursorCityNames = sd.precursorCityNames; fixes.add("structures.precursorCityNames missing, reset"); }
		if (structures.kingYolkarNames == null) { structures.kingYolkarNames = sd.kingYolkarNames; fixes.add("structures.kingYolkarNames missing, reset"); }
		if (structures.goblinQueenNames == null) { structures.goblinQueenNames = sd.goblinQueenNames; fixes.add("structures.goblinQueenNames missing, reset"); }
		if (structures.jungleTempleNames == null) { structures.jungleTempleNames = sd.jungleTempleNames; fixes.add("structures.jungleTempleNames missing, reset"); }
		if (structures.khazadDumNames == null) { structures.khazadDumNames = sd.khazadDumNames; fixes.add("structures.khazadDumNames missing, reset"); }
		return fixes;
	}

	private static double clamp(List<String> fixes, String name, double v, double min, double max) {
		if (Double.isNaN(v)) { fixes.add(name + " was not a number, set to " + min); return min; }
		if (v < min) { fixes.add(name + " = " + v + " is below " + min + ", clamped"); return min; }
		if (v > max) { fixes.add(name + " = " + v + " is above " + max + ", clamped"); return max; }
		return v;
	}
}
