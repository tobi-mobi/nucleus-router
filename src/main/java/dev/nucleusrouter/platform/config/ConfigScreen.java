package dev.nucleusrouter.platform.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.nucleusrouter.core.config.NrConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** YACL config screen. Every option mirrors a field in {@link NrConfig}. */
public final class ConfigScreen {
	private static final String VERIFY = " [VERIFY: not yet confirmed in-game]";

	private ConfigScreen() {}

	public static Screen create(Screen parent) {
		NrConfig c = ConfigManager.get();
		NrConfig d = new NrConfig(); // defaults

		ConfigCategory general = ConfigCategory.createBuilder()
				.name(Component.literal("General"))
				.option(boolOpt("Chat notices", "Short local chat messages such as \"Entered Crystal Hollows\". Only you see them; nothing is sent to the server.", d.general.chatNotices, () -> c.general.chatNotices, v -> c.general.chatNotices = v))
				.option(boolOpt("Save lobbies to disk", "Keep each lobby's remembered terrain on disk, so a crash or reconnect into the same lobby keeps what was learned (mined ores stay remembered).", d.general.saveLobbies, () -> c.general.saveLobbies, v -> c.general.saveLobbies = v))
				.build();

		ConfigCategory hollows = ConfigCategory.createBuilder()
				.name(Component.literal("Hollows"))
				.option(intOpt("Min X", "West edge of the Crystal Hollows (confirmed in-game).", d.hollows.minX, () -> c.hollows.minX, v -> c.hollows.minX = v, 0, 1024))
				.option(intOpt("Max X", "East edge of the Crystal Hollows (confirmed in-game).", d.hollows.maxX, () -> c.hollows.maxX, v -> c.hollows.maxX = v, 0, 1024))
				.option(intOpt("Min Y", "Lowest Y of the Crystal Hollows (confirmed in-game).", d.hollows.minY, () -> c.hollows.minY, v -> c.hollows.minY = v, -64, 320))
				.option(intOpt("Max Y", "Highest Y of the Crystal Hollows (confirmed in-game).", d.hollows.maxY, () -> c.hollows.maxY, v -> c.hollows.maxY = v, -64, 320))
				.option(intOpt("Min Z", "North edge of the Crystal Hollows (confirmed in-game).", d.hollows.minZ, () -> c.hollows.minZ, v -> c.hollows.minZ = v, 0, 1024))
				.option(intOpt("Max Z", "South edge of the Crystal Hollows (confirmed in-game).", d.hollows.maxZ, () -> c.hollows.maxZ, v -> c.hollows.maxZ = v, 0, 1024))
				.option(dblOpt("/warp nucleus time (s)", "What a /warp nucleus costs you, from deciding to warp until you can act at the nucleus (opening chat and sending the command; arrival itself is instant). Confirmed in-game: under a second.", d.hollows.warpNucleusSeconds, () -> c.hollows.warpNucleusSeconds, v -> c.hollows.warpNucleusSeconds = v, 0, 30))
				.option(dblOpt("Nucleus spawn X", "Where /warp nucleus puts you (confirmed in-game).", d.hollows.nucleusSpawnX, () -> c.hollows.nucleusSpawnX, v -> c.hollows.nucleusSpawnX = v, 0, 1024))
				.option(dblOpt("Nucleus spawn Y", "Where /warp nucleus puts you (confirmed in-game).", d.hollows.nucleusSpawnY, () -> c.hollows.nucleusSpawnY, v -> c.hollows.nucleusSpawnY = v, 0, 320))
				.option(dblOpt("Nucleus spawn Z", "Where /warp nucleus puts you (confirmed in-game).", d.hollows.nucleusSpawnZ, () -> c.hollows.nucleusSpawnZ, v -> c.hollows.nucleusSpawnZ = v, 0, 1024))
				.option(intOpt("Lobby closes, earliest day", "Lobbies close after roughly 15 to 20 Minecraft days.", d.hollows.lobbyCloseDayMin, () -> c.hollows.lobbyCloseDayMin, v -> c.hollows.lobbyCloseDayMin = v, 1, 100))
				.option(intOpt("Lobby closes, latest day", "Lobbies close after roughly 15 to 20 Minecraft days.", d.hollows.lobbyCloseDayMax, () -> c.hollows.lobbyCloseDayMax, v -> c.hollows.lobbyCloseDayMax = v, 1, 100))
				.build();

		ConfigCategory stops = ConfigCategory.createBuilder()
				.name(Component.literal("Stops"))
				.option(dblOpt("Queen's den exit limit (s)", "You must be back in Goblin Holdout within this many seconds of taking the Amber crystal.", d.stops.queenDenExitLimitSeconds, () -> c.stops.queenDenExitLimitSeconds, v -> c.stops.queenDenExitLimitSeconds = v, 0.5, 10))
				.option(dblOpt("Queen's den exit target (s)", "Planned den exits must fit in this time (safety margin below the limit).", d.stops.queenDenExitTargetSeconds, () -> c.stops.queenDenExitTargetSeconds, v -> c.stops.queenDenExitTargetSeconds = v, 0.1, 10))
				.option(boolOpt("/warp nucleus leaves the den in time", "If on, the planner may use /warp nucleus to leave the Queen's den (confirmed in-game: you keep the crystal).", d.stops.warpNucleusCountsAsLeavingDen, () -> c.stops.warpNucleusCountsAsLeavingDen, v -> c.stops.warpNucleusCountsAsLeavingDen = v))
				.option(dblOpt("Jungle Temple parkour (s)", "Time for the manual temple section, door guardian to crystal and lever.", d.stops.templeManualSeconds, () -> c.stops.templeManualSeconds, v -> c.stops.templeManualSeconds = v, 0, 300))
				.option(intOpt("Den exit: blocks above the crystal", "Going this many blocks above the Amber crystal counts as leaving the Queen's castle (confirmed in-game: 30). Every side of the castle counts too.", d.stops.denExitHeightAboveCrystal, () -> c.stops.denExitHeightAboveCrystal, v -> c.stops.denExitHeightAboveCrystal = v, 1, 100))
				.option(dblOpt("Mines of Divan inside (s)", "Moving between the 4 keepers and the crystal. The route only plans travel between structures. Estimate.", d.stops.divanInsideSeconds, () -> c.stops.divanInsideSeconds, v -> c.stops.divanInsideSeconds = v, 0, 120))
				.option(dblOpt("Precursor City inside (s)", "The short warp from Professor Robot to the Sapphire and back out. Estimate.", d.stops.precursorInsideSeconds, () -> c.stops.precursorInsideSeconds, v -> c.stops.precursorInsideSeconds = v, 0, 60))
				.option(boolOpt("Show arrival spots", "Draw each structure's arrival spot (blue block). Off by default: the route view shows where to warp.", d.stops.showStops, () -> c.stops.showStops, v -> c.stops.showStops = v))
				.option(dblOpt("NPC reach (blocks)", "How far from your eyes you can click an NPC. Stop spots are picked within this. Confirmed: 3.", d.stops.npcReach, () -> c.stops.npcReach, v -> c.stops.npcReach = v, 1, 6))
				.option(dblOpt("Crystal reach (blocks)", "How far from your eyes to a crystal's barrier box you can take the crystal. Confirmed: 3.", d.stops.crystalReach, () -> c.stops.crystalReach, v -> c.stops.crystalReach = v, 1, 8))
				.option(dblOpt("Bal fight (s)", "Time to kill Bal.", d.stops.balFightSeconds, () -> c.stops.balFightSeconds, v -> c.stops.balFightSeconds = v, 0, 300))
				.option(dblOpt("NPC hand-in (s)", "Time per NPC interaction (keepers, Professor Robot, King Yolkar, Door Guardian).", d.stops.npcInteractionSeconds, () -> c.stops.npcInteractionSeconds, v -> c.stops.npcInteractionSeconds = v, 0, 30))
				.option(dblOpt("Crystal pickup (s)", "Time to grab a crystal once you're next to it.", d.stops.crystalPickupSeconds, () -> c.stops.crystalPickupSeconds, v -> c.stops.crystalPickupSeconds = v, 0, 30))
				.build();

		ConfigCategory etherwarp = ConfigCategory.createBuilder()
				.name(Component.literal("Etherwarp"))
				.option(intOpt("Range (blocks)", "57 plus 1 per Transmission Tuner, up to 61.", d.etherwarp.range, () -> c.etherwarp.range, v -> c.etherwarp.range = v, 57, 61))
				.option(dblOpt("Eye height (blocks)", "Crouching eye height above your feet that the server uses for the etherwarp ray. Measured in-game: 1.27. /nr debug ray stats suggests a value from your own warps.", d.etherwarp.eyeHeight, () -> c.etherwarp.eyeHeight, v -> c.etherwarp.eyeHeight = v, 1.0, 1.8))
				.option(intOpt("Aim precision (samples per face)", "How finely the mod measures how forgiving a warp's aim is. Higher is more exact but slower.", d.etherwarp.aimSamplesPerFace, () -> c.etherwarp.aimSamplesPerFace, v -> c.etherwarp.aimSamplesPerFace = v, 2, 32))
				.option(boolOpt("Walk-through blocks are invisible", "Blocks you can walk through (no collision box: grass, flowers, torches, water, lava...) don't exist for etherwarp. Confirmed in-game for all of those.", d.etherwarp.noCollisionPassesThrough, () -> c.etherwarp.noCollisionPassesThrough, v -> c.etherwarp.noCollisionPassesThrough = v))
				.option(stringList("Ray passes through (extra)", "Blocks that don't exist for etherwarp: the ray goes through them and they count as air above a landing spot (water, lava, torches, confirmed in-game).", d.etherwarp.rayPassesThrough, () -> c.etherwarp.rayPassesThrough, v -> c.etherwarp.rayPassesThrough = v))
				.option(intOpt("Air needed above target", "Air blocks needed above a block to land on it.", d.etherwarp.requiredAirAbove, () -> c.etherwarp.requiredAirAbove, v -> c.etherwarp.requiredAirAbove = v, 1, 3))
				.build();

		ConfigCategory bombs = ConfigCategory.createBuilder()
				.name(Component.literal("Bob-ombs"))
				.option(dblOpt("Throw range (blocks)", "Straight-line distance before the bomb drops straight down. Measured in-game: about 32.", d.bombs.throwRange, () -> c.bombs.throwRange, v -> c.bombs.throwRange = v, 1, 100))
				.option(boolOpt("Drops straight down at max range", "At max range the bomb falls straight down until it hits something (confirmed in-game).", d.bombs.dropsStraightDownAtMaxRange, () -> c.bombs.dropsStraightDownAtMaxRange, v -> c.bombs.dropsStraightDownAtMaxRange = v))
				.option(dblOpt("Fuse (s)", "Fuse after impact. Measured in-game: about 2.3 s.", d.bombs.fuseSeconds, () -> c.bombs.fuseSeconds, v -> c.bombs.fuseSeconds = v, 0, 10))
				.option(dblOpt("Blast radius (blocks)", "Every breakable block whose centre is within this distance of the blast centre breaks. Measured in-game: 7.", d.bombs.blastRadius, () -> c.bombs.blastRadius, v -> c.bombs.blastRadius = v, 1, 20))
				.option(Option.<NrConfig.BlastCentre>createBuilder()
						.name(Component.literal("Blast centre"))
						.description(OptionDescription.of(Component.literal("Where the blast is centred: the centre of the block the bomb hit (confirmed in-game for direct hits), or the exact impact point.")))
						.binding(d.bombs.blastCentre, () -> c.bombs.blastCentre, v -> c.bombs.blastCentre = v)
						.controller(opt -> EnumControllerBuilder.create(opt).enumClass(NrConfig.BlastCentre.class))
						.build())
				.option(intOpt("Dropped bomb: centre blocks above landing", "For a bomb that fell at max range: the blast centre is this many blocks above the block it landed on." + VERIFY, d.bombs.dropCentreBlocksAbove, () -> c.bombs.dropCentreBlocksAbove, v -> c.bombs.dropCentreBlocksAbove = v, 0, 3))
				.option(dblOpt("Flight speed (blocks/s)", "How fast a thrown bomb flies. Measured in-game: about 25.", d.bombs.flightBlocksPerSecond, () -> c.bombs.flightBlocksPerSecond, v -> c.bombs.flightBlocksPerSecond = v, 1, 200))
				.option(stringList("Hard Stone blocks", "Block ids that count as Hard Stone and stay gone when broken (confirmed: minecraft:stone).", d.bombs.hardStoneBlocks, () -> c.bombs.hardStoneBlocks, v -> c.bombs.hardStoneBlocks = v))
				.option(stringList("Bomb-immune blocks", "Block ids bombs never break. Start with * to match a suffix, e.g. *_log.", d.bombs.bombImmuneBlocks, () -> c.bombs.bombImmuneBlocks, v -> c.bombs.bombImmuneBlocks = v))
				.build();

		ConfigCategory placing = ConfigCategory.createBuilder()
				.name(Component.literal("Placing"))
				.option(dblOpt("Reach (blocks)", "Reach used when planning block placements.", d.placing.reach, () -> c.placing.reach, v -> c.placing.reach = v, 1, 6))
				.option(stringList("Placed block types (fallback)", "Blocks you place are detected automatically, whatever the type. These types also count as placed when they appear in air without the mod seeing you place them.", d.placing.placedBlockTypes, () -> c.placing.placedBlockTypes, v -> c.placing.placedBlockTypes = v))
				.option(boolOpt("Allowed everywhere outside structures", "If on, the planner may place blocks anywhere outside structure regions." + VERIFY, d.placing.allowedEverywhereOutsideStructures, () -> c.placing.allowedEverywhereOutsideStructures, v -> c.placing.allowedEverywhereOutsideStructures = v))
				.build();

		ConfigCategory cost = ConfigCategory.createBuilder()
				.name(Component.literal("Cost model"))
				.option(dblOpt("Base warp time (s)", "Sneak + click + server round trip, before ping.", d.cost.baseWarpSeconds, () -> c.cost.baseWarpSeconds, v -> c.cost.baseWarpSeconds = v, 0, 5))
				.option(boolOpt("Use measured ping", "Add half your measured ping to every warp.", d.cost.useMeasuredPing, () -> c.cost.useMeasuredPing, v -> c.cost.useMeasuredPing = v))
				.option(intOpt("Fallback ping (ms)", "Used when ping can't be measured or measuring is off.", d.cost.fallbackPingMs, () -> c.cost.fallbackPingMs, v -> c.cost.fallbackPingMs = v, 0, 2000))
				.option(dblOpt("Turn time at 90 degrees (s)", "Extra time for a 90 degree turn. 0 degrees costs nothing.", d.cost.turnSecondsAt90, () -> c.cost.turnSecondsAt90, v -> c.cost.turnSecondsAt90 = v, 0, 5))
				.option(dblOpt("Turn curve exponent", "Turn time = (time at 90) x (angle / 90) ^ exponent. Higher means small turns are cheaper.", d.cost.turnExponent, () -> c.cost.turnExponent, v -> c.cost.turnExponent = v, 0.5, 4))
				.option(dblOpt("Aim spread without turning (deg)", "How far off your aim typically is even on a straight chain of warps. Used for the chance to miss a small target.", d.cost.aimErrorBaseDeg, () -> c.cost.aimErrorBaseDeg, v -> c.cost.aimErrorBaseDeg = v, 0, 10))
				.option(dblOpt("Aim spread per degree turned", "Extra aim spread for each degree you turn. 0.02 = about 2 degrees after a 90 degree turn. Bigger = the planner avoids sharp turns onto small targets more.", d.cost.aimErrorPerDeg, () -> c.cost.aimErrorPerDeg, v -> c.cost.aimErrorPerDeg = v, 0, 1))
				.option(dblOpt("Miss recovery (s)", "Time lost when a warp lands in the wrong spot.", d.cost.missRecoverySeconds, () -> c.cost.missRecoverySeconds, v -> c.cost.missRecoverySeconds = v, 0, 30))
				.build();

		String names = " A name tag matches if it contains one of these as whole words (case and colours ignored)." + VERIFY;
		ConfigCategory structures = ConfigCategory.createBuilder()
				.name(Component.literal("Structures"))
				.option(boolOpt("Show found structures", "Draw a beam and the name of each found structure, through walls.", d.structures.showMarkers, () -> c.structures.showMarkers, v -> c.structures.showMarkers = v))
				.option(boolOpt("Rough position from area name", "When no name tag has been seen yet, note where you stood while the scoreboard showed the structure's area (only listed in /nr structures, never drawn; it was usually wrong).", d.structures.useAreaHints, () -> c.structures.useAreaHints, v -> c.structures.useAreaHints = v))
				.option(boolOpt("Show structure list on screen", "A small list of the run's structures: green found, yellow approximate (go closer), red missing.", d.hud.showStructureList, () -> c.hud.showStructureList, v -> c.hud.showStructureList = v))
				.option(boolOpt("Hide list when all are found", "Hide the list 10 seconds after every structure is found.", d.hud.hideWhenAllFound, () -> c.hud.hideWhenAllFound, v -> c.hud.hideWhenAllFound = v))
				.option(intOpt("List position X", "Distance from the left edge of the screen (GUI pixels).", d.hud.x, () -> c.hud.x, v -> c.hud.x = v, 0, 4000))
				.option(intOpt("List position Y", "Distance from the top edge of the screen (GUI pixels).", d.hud.y, () -> c.hud.y, v -> c.hud.y = v, 0, 4000))
				.option(dblOpt("Explored radius (blocks)", "Columns within this distance of you count as explored (roughly how far NPC name tags are seen).", d.exploration.visitRadius, () -> c.exploration.visitRadius, v -> c.exploration.visitRadius = v, 8, 128))
				.option(boolOpt("Show zones", "Draw each found structure's no-bomb / no-placing zone as a red box. Sizes: /nr zones." + VERIFY, d.structures.showZones, () -> c.structures.showZones, v -> c.structures.showZones = v))
				.option(boolOpt("Find structures from their crystals", "Learn each structure's crystal box the first time you find the structure, then find it from the crystal alone in later lobbies (no need to visit the NPCs).", d.structures.learnFromCrystals, () -> c.structures.learnFromCrystals, v -> c.structures.learnFromCrystals = v))
				.option(boolOpt("Use Skyblocker's waypoints", "Read Skyblocker's Crystal Hollows waypoints (its compass solver, chat coordinates, shared waypoints) as approximate positions until a name tag or crystal is seen. Read-only.", d.structures.useSkyblocker, () -> c.structures.useSkyblocker, v -> c.structures.useSkyblocker = v))
				.option(Option.<NrConfig.SkyblockerWaypoints>createBuilder()
						.name(Component.literal("Skyblocker's waypoints"))
						.description(OptionDescription.of(Component.literal("Which of Skyblocker's own Crystal Hollows waypoints are still drawn. SHOW: all. HIDE_RUN_STRUCTURES: hide the 6 run structures this mod already marks, keep the rest (Fairy Grotto, Corleone...). HIDE_ALL: hide every one. Skyblocker still collects them either way, so this mod can still use them.")))
						.binding(d.structures.skyblockerWaypoints, () -> c.structures.skyblockerWaypoints, v -> c.structures.skyblockerWaypoints = v)
						.controller(opt -> EnumControllerBuilder.create(opt).enumClass(NrConfig.SkyblockerWaypoints.class))
						.build())
				.option(intOpt("Crystal box: minimum barriers", "The Queen's den and Khazad-dum crystals sit in a box of barrier blocks. A clump of at least this many barriers counts as that box.", d.structures.crystalBoxMinBarriers, () -> c.structures.crystalBoxMinBarriers, v -> c.structures.crystalBoxMinBarriers = v, 4, 10000))
				.option(intOpt("Scan every (ticks)", "How often nearby name tags are checked. 20 ticks = 1 second.", d.structures.scanEveryTicks, () -> c.structures.scanEveryTicks, v -> c.structures.scanEveryTicks = v, 1, 200))
				.option(stringList("Mines of Divan name tags", "The 4 keepers." + names, d.structures.minesOfDivanNames, () -> c.structures.minesOfDivanNames, v -> c.structures.minesOfDivanNames = v))
				.option(stringList("Precursor City name tags", "Professor Robot." + names, d.structures.precursorCityNames, () -> c.structures.precursorCityNames, v -> c.structures.precursorCityNames = v))
				.option(stringList("King Yolkar name tags", names, d.structures.kingYolkarNames, () -> c.structures.kingYolkarNames, v -> c.structures.kingYolkarNames = v))
				.option(stringList("Goblin Queen's Den name tags", "The Goblin Queen." + names, d.structures.goblinQueenNames, () -> c.structures.goblinQueenNames, v -> c.structures.goblinQueenNames = v))
				.option(stringList("Jungle Temple name tags", "The Kalhuiki Door Guardian." + names, d.structures.jungleTempleNames, () -> c.structures.jungleTempleNames, v -> c.structures.jungleTempleNames = v))
				.option(stringList("Khazad-dum name tags", "Bal." + names, d.structures.khazadDumNames, () -> c.structures.khazadDumNames, v -> c.structures.khazadDumNames = v))
				.build();

		ConfigCategory lobby = ConfigCategory.createBuilder()
				.name(Component.literal("Lobby check"))
				.option(boolOpt("Chat notices", "Say in chat when a rule starts suggesting another lobby, with the reason. /nr lobby shows all rules any time.", d.lobby.notify, () -> c.lobby.notify, v -> c.lobby.notify = v))
				.option(boolOpt("Rule: structures too spread out", "Warn if reaching some structure needs a leg longer than the limit below, counting /warp nucleus as a shortcut (each structure is reached from the closest other structure or from the nucleus).", d.lobby.spreadRule, () -> c.lobby.spreadRule, v -> c.lobby.spreadRule = v))
				.option(intOpt("Max spread (blocks)", "Longest allowed leg in blocks.", d.lobby.maxSpreadBlocks, () -> c.lobby.maxSpreadBlocks, v -> c.lobby.maxSpreadBlocks = v, 50, 1000))
				.option(boolOpt("Rule: lobby day too high", "Warn if the lobby is older than the day below (lobbies close around day 15-20, so a setup pays back less).", d.lobby.dayRule, () -> c.lobby.dayRule, v -> c.lobby.dayRule = v))
				.option(intOpt("Warn after day", "Lobby day above which to warn.", d.lobby.warnAfterDay, () -> c.lobby.warnAfterDay, v -> c.lobby.warnAfterDay = v, 0, 30))
				.option(boolOpt("Rule: explored but structures missing", "Warn if most of the Hollows is explored and structures are still missing.", d.lobby.coverageRule, () -> c.lobby.coverageRule, v -> c.lobby.coverageRule = v))
				.option(dblOpt("Explored share to warn at (0-1)", "0.75 = warn once 75% is explored with structures still missing.", d.lobby.coverageWarnFraction, () -> c.lobby.coverageWarnFraction, v -> c.lobby.coverageWarnFraction = v, 0.1, 1))
				.option(boolOpt("Rule: run too slow", "Warn if the estimated run time is above the limit below. Rough estimate until the route planner exists.", d.lobby.runTimeRule, () -> c.lobby.runTimeRule, v -> c.lobby.runTimeRule = v))
				.option(intOpt("Max run time (s)", "240 = 4 minutes.", d.lobby.warnRunSeconds, () -> c.lobby.warnRunSeconds, v -> c.lobby.warnRunSeconds = v, 30, 1800))
				.option(dblOpt("Rough estimate: warp efficiency", "Share of the full etherwarp range an average warp covers. Only used by the rough estimate.", d.lobby.legEfficiency, () -> c.lobby.legEfficiency, v -> c.lobby.legEfficiency = v, 0.2, 1))
				.build();

		ConfigCategory run = ConfigCategory.createBuilder()
				.name(Component.literal("Run mode"))
				.option(boolOpt("Route line", "A thin line along the route (dashed where you fly yourself).", d.run.showLine, () -> c.run.showLine, v -> c.run.showLine = v))
				.option(intOpt("Warp targets shown", "How many of the next warp target blocks are highlighted and numbered.", d.run.targetsShown, () -> c.run.targetsShown, v -> c.run.targetsShown = v, 1, 30))
				.option(boolOpt("Aim dot", "A dot on the most forgiving spot to aim at, on the next target.", d.run.aimDot, () -> c.run.aimDot, v -> c.run.aimDot = v))
				.build();

		ConfigCategory compass = ConfigCategory.createBuilder()
				.name(Component.literal("Wishing Compass"))
				.option(boolOpt("Show in the world", "Draw compass lines, the estimated target and the suggested next spot.", d.compass.showInWorld, () -> c.compass.showInWorld, v -> c.compass.showInWorld = v))
				.option(dblOpt("Good enough radius (blocks)", "Once the target is known this precisely, no more compass uses are suggested.", d.compass.goodRadiusBlocks, () -> c.compass.goodRadiusBlocks, v -> c.compass.goodRadiusBlocks = v, 1, 100))
				.option(dblOpt("Collect particles for (s)", "How long after a compass use particles are collected." + VERIFY, d.compass.captureSeconds, () -> c.compass.captureSeconds, v -> c.compass.captureSeconds = v, 0.5, 15))
				.option(dblOpt("Collect within (blocks)", "Particles further away from you are ignored.", d.compass.captureRadius, () -> c.compass.captureRadius, v -> c.compass.captureRadius = v, 5, 128))
				.option(intOpt("Minimum particles", "A trail needs at least this many particles.", d.compass.minPoints, () -> c.compass.minPoints, v -> c.compass.minPoints = v, 3, 100))
				.option(stringList("Trail particle types", "Only these particle types can form the trail, e.g. minecraft:happy_villager. Empty = any (the straightest trail wins)." + VERIFY, d.compass.particleTypes, () -> c.compass.particleTypes, v -> c.compass.particleTypes = v))
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(Component.literal("Nucleus Router"))
				.category(general).category(hollows).category(stops).category(etherwarp).category(bombs).category(placing).category(cost).category(structures).category(lobby).category(run).category(compass)
				.save(ConfigManager::save)
				.build()
				.generateScreen(parent);
	}

	private static Option<Double> dblOpt(String name, String desc, double def, Supplier<Double> get, Consumer<Double> set, double min, double max) {
		return Option.<Double>createBuilder()
				.name(Component.literal(name))
				.description(OptionDescription.of(Component.literal(desc)))
				.binding(def, get, set)
				.controller(opt -> DoubleFieldControllerBuilder.create(opt).range(min, max))
				.build();
	}

	private static Option<Integer> intOpt(String name, String desc, int def, Supplier<Integer> get, Consumer<Integer> set, int min, int max) {
		return Option.<Integer>createBuilder()
				.name(Component.literal(name))
				.description(OptionDescription.of(Component.literal(desc)))
				.binding(def, get, set)
				.controller(opt -> IntegerFieldControllerBuilder.create(opt).range(min, max))
				.build();
	}

	private static Option<Boolean> boolOpt(String name, String desc, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
		return Option.<Boolean>createBuilder()
				.name(Component.literal(name))
				.description(OptionDescription.of(Component.literal(desc)))
				.binding(def, get, set)
				.controller(TickBoxControllerBuilder::create)
				.build();
	}

	private static ListOption<String> stringList(String name, String desc, List<String> def, Supplier<List<String>> get, Consumer<List<String>> set) {
		return ListOption.<String>createBuilder()
				.name(Component.literal(name))
				.description(OptionDescription.of(Component.literal(desc)))
				.binding(new ArrayList<>(def), () -> new ArrayList<>(get.get()), v -> set.accept(new ArrayList<>(v)))
				.controller(StringControllerBuilder::create)
				.initial("minecraft:")
				.collapsed(true)
				.build();
	}
}
