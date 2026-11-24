package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.context.CommandContext;
import dev.nucleusrouter.core.etherwarp.EyeHeightFit;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.debug.DebugRay;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** {@code /nr debug ...} commands. */
final class DebugCommands {
	private DebugCommands() {}

	static int toggleRay(CommandContext<FabricClientCommandSource> ctx) {
		boolean on = DebugRay.toggle();
		ctx.getSource().sendFeedback(Component.literal(on
				? "Debug ray ON. Green box = where a warp from your aim lands (red = blocked above). Blue dot = most forgiving aim point. Your real warps are now checked; see /nr debug ray stats."
				: "Debug ray OFF.").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return 1;
	}

	static int rayStats(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		DebugRay.Stats s = DebugRay.stats();
		src.sendFeedback(Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal("Debug ray stats").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
		kv(src, "Real warps checked", s.warps() + "  (correct " + s.matches() + ", wrong " + s.mismatches() + ", clicks without teleport " + s.noTeleport() + ")");
		EyeHeightFit.Suggestion e = s.eye();
		if (e.hasData()) {
			kv(src, "Eye height suggestion", String.format("%.3f  (fits %d of %d warps; anything from %.3f to %.3f fits equally)", e.height(), e.explained(), e.total(), e.low(), e.high()));
		} else {
			kv(src, "Eye height suggestion", "need some warps first");
		}
		kv(src, "Current eye height setting", String.format("%.3f", ConfigManager.get().etherwarp.eyeHeight));
		kv(src, "Click to teleport delay", s.medianDelayMs() < 0 ? "no warps yet" : "median " + s.medianDelayMs() + " ms, fastest " + s.minDelayMs() + " ms");
		kv(src, "Overlay drawing cost", String.format("%.3f ms per frame", s.renderMicros() / 1000.0));
		return 1;
	}

	static int rayClear(CommandContext<FabricClientCommandSource> ctx) {
		DebugRay.clear();
		ctx.getSource().sendFeedback(Component.literal("Debug ray stats cleared.").withStyle(ChatFormatting.GRAY));
		return 1;
	}

	static int rayApply(CommandContext<FabricClientCommandSource> ctx) {
		EyeHeightFit.Suggestion e = DebugRay.stats().eye();
		if (!e.hasData()) {
			ctx.getSource().sendError(Component.literal("No suggestion yet: turn on /nr debug ray and do some warps first."));
			return 0;
		}
		double old = ConfigManager.get().etherwarp.eyeHeight;
		ConfigManager.get().etherwarp.eyeHeight = e.height();
		ConfigManager.save();
		ctx.getSource().sendFeedback(Component.literal(String.format("Eye height changed from %.3f to %.3f and saved.", old, e.height())).withStyle(ChatFormatting.GREEN));
		return 1;
	}

	static int calibrateBomb(CommandContext<FabricClientCommandSource> ctx) {
		ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.debug.BombCalibration.arm()).withStyle(ChatFormatting.YELLOW));
		return 1;
	}

	static int calibrateBombCancel(CommandContext<FabricClientCommandSource> ctx) {
		ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.debug.BombCalibration.cancel()).withStyle(ChatFormatting.GRAY));
		return 1;
	}

	static int dump(CommandContext<FabricClientCommandSource> ctx) {
		ctx.getSource().sendFeedback(Component.literal("Saving dump...").withStyle(ChatFormatting.GRAY));
		dev.nucleusrouter.platform.world.WorldCapture.dump()
				.thenAccept(msg -> dev.nucleusrouter.platform.NucleusRouterClient.showLocal(Component.literal(msg).withStyle(ChatFormatting.GREEN)))
				.exceptionally(dev.nucleusrouter.platform.NucleusRouterClient::reportAsyncError);
		return 1;
	}

	private static void kv(FabricClientCommandSource src, String key, String value) {
		src.sendFeedback(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}
}
