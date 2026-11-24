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
				.option(intOpt("Min X", "West edge of the Crystal Hollows." + VERIFY, d.hollows.minX, () -> c.hollows.minX, v -> c.hollows.minX = v, 0, 1024))
				.option(intOpt("Max X", "East edge of the Crystal Hollows." + VERIFY, d.hollows.maxX, () -> c.hollows.maxX, v -> c.hollows.maxX = v, 0, 1024))
				.option(intOpt("Min Y", "Lowest Y of the Crystal Hollows." + VERIFY, d.hollows.minY, () -> c.hollows.minY, v -> c.hollows.minY = v, -64, 320))
				.option(intOpt("Max Y", "Highest Y of the Crystal Hollows." + VERIFY, d.hollows.maxY, () -> c.hollows.maxY, v -> c.hollows.maxY = v, -64, 320))
				.option(intOpt("Min Z", "North edge of the Crystal Hollows." + VERIFY, d.hollows.minZ, () -> c.hollows.minZ, v -> c.hollows.minZ = v, 0, 1024))
				.option(intOpt("Max Z", "South edge of the Crystal Hollows." + VERIFY, d.hollows.maxZ, () -> c.hollows.maxZ, v -> c.hollows.maxZ = v, 0, 1024))
				.option(dblOpt("/warp nucleus time (s)", "Seconds from typing /warp nucleus until you can act at the nucleus." + VERIFY, d.hollows.warpNucleusSeconds, () -> c.hollows.warpNucleusSeconds, v -> c.hollows.warpNucleusSeconds = v, 0, 30))
				.option(intOpt("Lobby closes, earliest day", "Lobbies close after roughly 15 to 20 Minecraft days.", d.hollows.lobbyCloseDayMin, () -> c.hollows.lobbyCloseDayMin, v -> c.hollows.lobbyCloseDayMin = v, 1, 100))
				.option(intOpt("Lobby closes, latest day", "Lobbies close after roughly 15 to 20 Minecraft days.", d.hollows.lobbyCloseDayMax, () -> c.hollows.lobbyCloseDayMax, v -> c.hollows.lobbyCloseDayMax = v, 1, 100))
				.build();

		ConfigCategory stops = ConfigCategory.createBuilder()
				.name(Component.literal("Stops"))
				.option(dblOpt("Queen's den exit limit (s)", "You must be back in Goblin Holdout within this many seconds of taking the Amber crystal.", d.stops.queenDenExitLimitSeconds, () -> c.stops.queenDenExitLimitSeconds, v -> c.stops.queenDenExitLimitSeconds = v, 0.5, 10))
				.option(dblOpt("Queen's den exit target (s)", "Planned den exits must fit in this time (safety margin below the limit).", d.stops.queenDenExitTargetSeconds, () -> c.stops.queenDenExitTargetSeconds, v -> c.stops.queenDenExitTargetSeconds = v, 0.1, 10))
				.option(boolOpt("/warp nucleus leaves the den in time", "If on, the planner may use /warp nucleus to leave the Queen's den." + VERIFY, d.stops.warpNucleusCountsAsLeavingDen, () -> c.stops.warpNucleusCountsAsLeavingDen, v -> c.stops.warpNucleusCountsAsLeavingDen = v))
				.option(dblOpt("Jungle Temple parkour (s)", "Time for the manual temple section, door guardian to crystal and lever.", d.stops.templeManualSeconds, () -> c.stops.templeManualSeconds, v -> c.stops.templeManualSeconds = v, 0, 300))
				.option(dblOpt("Bal fight (s)", "Time to kill Bal.", d.stops.balFightSeconds, () -> c.stops.balFightSeconds, v -> c.stops.balFightSeconds = v, 0, 300))
				.option(dblOpt("NPC hand-in (s)", "Time per NPC interaction (keepers, Professor Robot, King Yolkar, Door Guardian).", d.stops.npcInteractionSeconds, () -> c.stops.npcInteractionSeconds, v -> c.stops.npcInteractionSeconds = v, 0, 30))
				.option(dblOpt("Crystal pickup (s)", "Time to grab a crystal once you're next to it.", d.stops.crystalPickupSeconds, () -> c.stops.crystalPickupSeconds, v -> c.stops.crystalPickupSeconds = v, 0, 30))
				.build();

		ConfigCategory etherwarp = ConfigCategory.createBuilder()
				.name(Component.literal("Etherwarp"))
				.option(intOpt("Range (blocks)", "57 plus 1 per Transmission Tuner, up to 61.", d.etherwarp.range, () -> c.etherwarp.range, v -> c.etherwarp.range = v, 57, 61))
				.option(dblOpt("Eye height (blocks)", "Sneaking eye height above your feet that the server uses for the etherwarp ray. /nr debug ray suggests a value." + VERIFY, d.etherwarp.eyeHeight, () -> c.etherwarp.eyeHeight, v -> c.etherwarp.eyeHeight = v, 1.0, 1.8))
				.option(stringList("Ray passes through", "Blocks the etherwarp ray goes straight through (water and lava, confirmed in-game).", d.etherwarp.rayPassesThrough, () -> c.etherwarp.rayPassesThrough, v -> c.etherwarp.rayPassesThrough = v))
				.option(intOpt("Air needed above target", "Air blocks needed above a block to land on it.", d.etherwarp.requiredAirAbove, () -> c.etherwarp.requiredAirAbove, v -> c.etherwarp.requiredAirAbove = v, 1, 3))
				.build();

		ConfigCategory bombs = ConfigCategory.createBuilder()
				.name(Component.literal("Bob-ombs"))
				.option(dblOpt("Throw range (blocks)", "Straight-line distance before the bomb drops." + VERIFY, d.bombs.throwRange, () -> c.bombs.throwRange, v -> c.bombs.throwRange = v, 1, 100))
				.option(boolOpt("Drops straight down at max range", "At max range the bomb falls straight down until it hits something." + VERIFY, d.bombs.dropsStraightDownAtMaxRange, () -> c.bombs.dropsStraightDownAtMaxRange, v -> c.bombs.dropsStraightDownAtMaxRange = v))
				.option(dblOpt("Fuse (s)", "Fuse after impact (2.5 to 3 s).", d.bombs.fuseSeconds, () -> c.bombs.fuseSeconds, v -> c.bombs.fuseSeconds = v, 0, 10))
				.option(dblOpt("Blast radius (blocks)", "Radius of the blast sphere." + VERIFY, d.bombs.blastRadius, () -> c.bombs.blastRadius, v -> c.bombs.blastRadius = v, 1, 20))
				.option(Option.<NrConfig.BlastCentre>createBuilder()
						.name(Component.literal("Blast centre"))
						.description(OptionDescription.of(Component.literal("Where the blast sphere is centred: the centre of the block the bomb hit, or the exact impact point." + VERIFY)))
						.binding(d.bombs.blastCentre, () -> c.bombs.blastCentre, v -> c.bombs.blastCentre = v)
						.controller(opt -> EnumControllerBuilder.create(opt).enumClass(NrConfig.BlastCentre.class))
						.build())
				.option(stringList("Hard Stone blocks", "Block ids that count as Hard Stone and stay gone when broken." + VERIFY, d.bombs.hardStoneBlocks, () -> c.bombs.hardStoneBlocks, v -> c.bombs.hardStoneBlocks = v))
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
				.option(dblOpt("Miss recovery (s)", "Time lost when a warp lands in the wrong spot.", d.cost.missRecoverySeconds, () -> c.cost.missRecoverySeconds, v -> c.cost.missRecoverySeconds = v, 0, 30))
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(Component.literal("Nucleus Router"))
				.category(general).category(hollows).category(stops).category(etherwarp).category(bombs).category(placing).category(cost)
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
