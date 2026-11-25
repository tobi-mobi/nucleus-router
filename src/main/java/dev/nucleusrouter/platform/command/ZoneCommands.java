package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.game.StructureZone;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.structure.StructureShapes;
import dev.nucleusrouter.platform.structure.StructureTracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /nr capture}, {@code /nr zones}, {@code /nr zone <name> corner1|corner2|reset} (roadmap 2.3). */
final class ZoneCommands {
	private ZoneCommands() {}

	private static final Map<Structure, int[]> firstCorner = new EnumMap<>(Structure.class);

	private static final SuggestionProvider<FabricClientCommandSource> NAMES = (ctx, b) -> {
		for (Structure s : Structure.values()) b.suggest(s.key);
		return b.buildFuture();
	};

	static LiteralArgumentBuilder<FabricClientCommandSource> captureNode() {
		return literal("capture").then(argument("name", StringArgumentType.word()).suggests(NAMES)
				.executes(ctx -> capture(ctx, 40))
				.then(argument("radius", IntegerArgumentType.integer(8, 64)).executes(ctx -> capture(ctx, IntegerArgumentType.getInteger(ctx, "radius")))));
	}

	static LiteralArgumentBuilder<FabricClientCommandSource> zonesNode() {
		return literal("zones").executes(ZoneCommands::list);
	}

	static LiteralArgumentBuilder<FabricClientCommandSource> zoneNode() {
		return literal("zone").executes(ZoneCommands::list)
				.then(argument("name", StringArgumentType.word()).suggests(NAMES)
						.then(literal("corner1").executes(ctx -> corner(ctx, 1)))
						.then(literal("corner2").executes(ctx -> corner(ctx, 2)))
						.then(literal("reset").executes(ZoneCommands::reset)));
	}

	private static Structure structure(CommandContext<FabricClientCommandSource> ctx) {
		Structure s = Structure.byKey(StringArgumentType.getString(ctx, "name"));
		if (s == null) ctx.getSource().sendError(Component.literal("Unknown structure. Use divan, precursor, king, queen, temple or khazad."));
		return s;
	}

	private static int capture(CommandContext<FabricClientCommandSource> ctx, int radius) {
		Structure s = structure(ctx);
		if (s == null) return 0;
		ctx.getSource().sendFeedback(Component.literal("Capturing " + s.displayName + " (radius " + radius + ")...").withStyle(ChatFormatting.GRAY));
		StructureShapes.capture(s, radius).thenAccept(lines -> {
			for (String l : lines) NucleusRouterClient.showLocal(Component.literal(l).withStyle(ChatFormatting.GREEN));
		}).exceptionally(t -> {
			Throwable c = t.getCause() != null ? t.getCause() : t;
			NucleusRouterClient.showLocal(Component.literal("Capture failed: " + c.getMessage()).withStyle(ChatFormatting.RED));
			return null;
		});
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		StructureMap map = StructureTracker.map();
		var p = src.getPlayer();
		src.sendFeedback(Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal("Structure zones (no bombs, no placing)").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		for (Structure s : Structure.values()) {
			StructureZone z = StructureShapes.zone(s);
			int[] b = map.zoneBox(s, z);
			String size = String.format("%dx%dx%d", z.max()[0] - z.min()[0] + 1, z.max()[1] - z.min()[1] + 1, z.max()[2] - z.min()[2] + 1);
			boolean custom = !java.util.Arrays.equals(ConfigManager.get().structures.zones.get(s.key), NrConfig.Structures.defaultZones().get(s.key));
			String line;
			if (b == null) {
				line = "not placed yet (structure not exactly found)";
			} else {
				double d = StructureZone.distance(b, p.getX(), p.getY(), p.getZ());
				line = String.format("%d %d %d to %d %d %d, %s", b[0], b[1], b[2], b[3], b[4], b[5], d == 0 ? "you are INSIDE" : Math.round(d) + " m away")
						+ (map.orientationKnown(s) ? ", " + map.orientation(s).describe() : "");
			}
			src.sendFeedback(Component.literal("  " + s.displayName + " [" + size + (custom ? ", measured" : ", default guess") + "]: ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(line).withStyle(b == null ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE)));
		}
		src.sendFeedback(Component.literal("  Measure one: stand at one corner, /nr zone <name> corner1, then the opposite corner, /nr zone <name> corner2").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int corner(CommandContext<FabricClientCommandSource> ctx, int which) {
		Structure s = structure(ctx);
		if (s == null) return 0;
		StructureMap map = StructureTracker.map();
		int[] a = map.anchorBlock(s);
		if (a == null) {
			ctx.getSource().sendError(Component.literal(s.displayName + " isn't exactly found yet, so the zone can't be stored relative to it."));
			return 0;
		}
		var p = ctx.getSource().getPlayer();
		int[] here = {p.getBlockX(), p.getBlockY(), p.getBlockZ()};
		if (which == 1) {
			firstCorner.put(s, here);
			ctx.getSource().sendFeedback(Component.literal("Corner 1 of the " + s.displayName + " zone: " + here[0] + " " + here[1] + " " + here[2]
					+ ". Now go to the opposite corner (other side, other height) and run /nr zone " + s.key + " corner2").withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		int[] c1 = firstCorner.remove(s);
		if (c1 == null) {
			ctx.getSource().sendError(Component.literal("Set corner1 first: /nr zone " + s.key + " corner1"));
			return 0;
		}
		StructureZone z = StructureZone.fromWorldCorners(c1, here, a[0], a[1], a[2], map.orientation(s));
		ConfigManager.get().structures.zones.put(s.key, new int[]{z.min()[0], z.min()[1], z.min()[2], z.max()[0], z.max()[1], z.max()[2]});
		ConfigManager.save();
		ctx.getSource().sendFeedback(Component.literal(String.format("Saved the %s zone: %d..%d, %d..%d, %d..%d blocks from its anchor. It is used in every lobby.",
				s.displayName, z.min()[0], z.max()[0], z.min()[1], z.max()[1], z.min()[2], z.max()[2])).withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int reset(CommandContext<FabricClientCommandSource> ctx) {
		Structure s = structure(ctx);
		if (s == null) return 0;
		ConfigManager.get().structures.zones.put(s.key, NrConfig.Structures.defaultZones().get(s.key));
		ConfigManager.save();
		ctx.getSource().sendFeedback(Component.literal(s.displayName + " zone reset to the default guess.").withStyle(ChatFormatting.GREEN));
		return 1;
	}
}
