package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.nucleusrouter.game.SetPoints;
import dev.nucleusrouter.platform.structure.SetPointStore;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /nr setpoint [<stop>] | list | clear <stop>|all} (roadmap 5.1): your own stop points, kept for every lobby.
 */
final class SetPointCommands {
	private static final SuggestionProvider<FabricClientCommandSource> IDS = (ctx, b) -> {
		for (String id : SetPoints.IDS) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id);
		return b.buildFuture();
	};
	private static final SuggestionProvider<FabricClientCommandSource> IDS_OR_ALL = (ctx, b) -> {
		for (String id : SetPoints.IDS) if (id.startsWith(b.getRemainingLowerCase())) b.suggest(id);
		if ("all".startsWith(b.getRemainingLowerCase())) b.suggest("all");
		return b.buildFuture();
	};

	private SetPointCommands() {}

	static LiteralArgumentBuilder<FabricClientCommandSource> node() {
		return literal("setpoint")
				.executes(ctx -> say(ctx.getSource(), SetPointStore.setNearest()))
				.then(literal("list").executes(ctx -> {
					ctx.getSource().sendFeedback(Component.literal("Your stop points (used in every lobby):").withStyle(ChatFormatting.GOLD));
					for (String line : SetPointStore.list()) ctx.getSource().sendFeedback(Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
					return 1;
				}))
				.then(literal("clear").then(argument("stop", StringArgumentType.word()).suggests(IDS_OR_ALL)
						.executes(ctx -> say(ctx.getSource(), SetPointStore.clear(StringArgumentType.getString(ctx, "stop"))))))
				.then(argument("stop", StringArgumentType.word()).suggests(IDS)
						.executes(ctx -> say(ctx.getSource(), SetPointStore.set(StringArgumentType.getString(ctx, "stop")))));
	}

	private static int say(FabricClientCommandSource src, String msg) {
		src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GRAY));
		return 1;
	}
}
