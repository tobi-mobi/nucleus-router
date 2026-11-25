package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.structure.StructureTracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /nr structures} and {@code /nr structure set|clear <name>} (roadmap 2.1). */
final class StructureCommands {
	private StructureCommands() {}

	private static final SuggestionProvider<FabricClientCommandSource> NAMES = (ctx, b) -> {
		for (Structure s : Structure.values()) b.suggest(s.key);
		return b.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> NAMES_OR_ALL = (ctx, b) -> {
		for (Structure s : Structure.values()) b.suggest(s.key);
		b.suggest("all");
		return b.buildFuture();
	};

	static LiteralArgumentBuilder<FabricClientCommandSource> structuresNode() {
		return literal("structures").executes(StructureCommands::list);
	}

	static LiteralArgumentBuilder<FabricClientCommandSource> structureNode() {
		return literal("structure").executes(StructureCommands::list)
				.then(literal("set").then(argument("name", StringArgumentType.word()).suggests(NAMES).executes(StructureCommands::set)))
				.then(literal("clear").then(argument("name", StringArgumentType.word()).suggests(NAMES_OR_ALL).executes(StructureCommands::clear)));
	}

	static int list(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		StructureMap map = StructureTracker.map();
		src.sendFeedback(Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal("Structures in lobby " + (StructureTracker.lobby() != null ? StructureTracker.lobby() : "?")).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		var player = Minecraft.getInstance().player;
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			Component value;
			if (l == null) {
				value = Component.literal("not found").withStyle(ChatFormatting.RED);
			} else {
				Vec3 p = l.pos();
				String dist = player != null ? "  " + Math.round(p.sub(new Vec3(player.getX(), player.getY(), player.getZ())).length()) + " m away" : "";
				ChatFormatting colour = switch (l.source()) {
					case MANUAL -> ChatFormatting.AQUA;
					case CRYSTAL, NPC, LEARNED -> ChatFormatting.GREEN;
					case SKYBLOCKER, AREA -> ChatFormatting.YELLOW;
				};
				String how = switch (l.source()) {
					case MANUAL -> "set by you";
					case CRYSTAL, NPC, LEARNED, SKYBLOCKER -> l.detail();
					case AREA -> "rough, +-" + Math.round(l.uncertainty()) + " blocks (" + l.detail() + ")";
				};
				value = Component.literal(StructureTracker.fmt(p.x(), p.y(), p.z()) + dist).withStyle(colour)
						.append(Component.literal("  " + how).withStyle(ChatFormatting.GRAY));
			}
			src.sendFeedback(Component.literal("  " + s.displayName + " [" + s.key + "]: ").withStyle(ChatFormatting.GRAY).append(value));
		}
		src.sendFeedback(Component.literal("  Learned crystals: " + dev.nucleusrouter.platform.structure.CrystalScanner.summary()).withStyle(ChatFormatting.DARK_GRAY));
		src.sendFeedback(Component.literal("  Skyblocker: " + dev.nucleusrouter.platform.structure.SkyblockerBridge.status()).withStyle(ChatFormatting.DARK_GRAY));
		src.sendFeedback(Component.literal("  Set by hand: /nr structure set <name> (where you stand). Forget: /nr structure clear <name|all>").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int set(CommandContext<FabricClientCommandSource> ctx) {
		Structure s = Structure.byKey(StringArgumentType.getString(ctx, "name"));
		if (s == null) return unknown(ctx);
		if (!StructureTracker.setHere(s)) {
			ctx.getSource().sendError(Component.literal("Only works inside the Crystal Hollows (see /nr where)."));
			return 0;
		}
		var p = ctx.getSource().getPlayer();
		ctx.getSource().sendFeedback(Component.literal(s.displayName + " set to where you stand: " + StructureTracker.fmt(p.getX(), p.getY(), p.getZ())
				+ ". This overrides name tags until you clear it.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int clear(CommandContext<FabricClientCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		if (name.equalsIgnoreCase("all")) {
			StructureTracker.clear(null);
			ctx.getSource().sendFeedback(Component.literal("Forgot all structures in this lobby. Name tags will find them again.").withStyle(ChatFormatting.GREEN));
			return 1;
		}
		Structure s = Structure.byKey(name);
		if (s == null) return unknown(ctx);
		StructureTracker.clear(s);
		ctx.getSource().sendFeedback(Component.literal("Forgot " + s.displayName + ". Its name tag will find it again when it's loaded.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int unknown(CommandContext<FabricClientCommandSource> ctx) {
		StringBuilder sb = new StringBuilder();
		for (Structure s : Structure.values()) sb.append(sb.length() > 0 ? ", " : "").append(s.key);
		ctx.getSource().sendError(Component.literal("Unknown structure. Use one of: " + sb));
		return 0;
	}
}
