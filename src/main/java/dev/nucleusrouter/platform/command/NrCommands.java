package dev.nucleusrouter.platform.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.nucleusrouter.core.location.LocationParser;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.config.ConfigScreen;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client-side {@code /nr} commands (also {@code /nucleusrouter}). These run only on your client;
 * nothing is sent to the server.
 */
public final class NrCommands {
	private NrCommands() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			for (String root : List.of("nr", "nucleusrouter")) {
				LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal(root)
						.executes(NrCommands::help)
						.then(literal("help").executes(NrCommands::help))
						.then(literal("where").executes(ctx -> where(ctx, false))
								.then(literal("raw").executes(ctx -> where(ctx, true))))
						.then(literal("world").executes(WorldCommands::stats)
								.then(literal("here").executes(WorldCommands::here))
								.then(literal("save").executes(WorldCommands::save)))
						.then(literal("debug").then(literal("ray").executes(DebugCommands::toggleRay)
								.then(literal("stats").executes(DebugCommands::rayStats))
								.then(literal("clear").executes(DebugCommands::rayClear))
								.then(literal("apply").executes(DebugCommands::rayApply))))
						.then(literal("calibrate").then(literal("bomb").executes(DebugCommands::calibrateBomb)
								.then(literal("cancel").executes(DebugCommands::calibrateBombCancel))))
						.then(StructureCommands.structuresNode())
						.then(StructureCommands.structureNode())
						.then(literal("dump").executes(DebugCommands::dump))
						.then(literal("config").executes(NrCommands::openConfig))
						.then(literal("reload").executes(NrCommands::reload));
				dispatcher.register(cmd);
			}
		});
	}

	private static int help(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		src.sendFeedback(header("Nucleus Router commands"));
		line(src, "/nr where", "where am I: Hollows?, lobby id, lobby day, ping");
		line(src, "/nr where raw", "same, plus the raw tab list / scoreboard text");
		line(src, "/nr world", "remembered terrain: lobby, sections, block classes");
		line(src, "/nr world here", "what the mod remembers about the block you look at and the one you stand on");
		line(src, "/nr world save", "save this lobby's terrain now");
		line(src, "/nr debug ray", "show where an etherwarp would land; checks your real warps");
		line(src, "/nr debug ray stats", "predicted vs actual, eye height suggestion, warp delay");
		line(src, "/nr debug ray apply", "use the suggested eye height");
		line(src, "/nr calibrate bomb", "measure one real Bob-omb blast against the model");
		line(src, "/nr structures", "which run structures are found in this lobby, and where");
		line(src, "/nr structure set <name>", "mark a structure where you stand (divan, precursor, king, queen, temple, khazad)");
		line(src, "/nr structure clear <name|all>", "forget a structure so it's found again");
		line(src, "/nr dump", "save this lobby's terrain + settings to a file for offline testing");
		line(src, "/nr config", "open the settings screen");
		line(src, "/nr reload", "reload settings from config/nucleusrouter.json");
		return 1;
	}

	private static int where(CommandContext<FabricClientCommandSource> ctx, boolean raw) {
		FabricClientCommandSource src = ctx.getSource();
		LocationState s = LocationTracker.state();
		src.sendFeedback(header("Location"));
		kv(src, "In Crystal Hollows", s.inHollows() ? "yes" : "no", s.inHollows() ? ChatFormatting.GREEN : ChatFormatting.RED);
		kv(src, "Decided by", s.hollowsSource() != null ? s.hollowsSource().name() : "nothing (no source answered)", ChatFormatting.WHITE);
		kv(src, "Lobby id", s.serverId() != null ? s.serverId() : "unknown", ChatFormatting.WHITE);
		kv(src, "Scoreboard area", s.scoreboardArea() != null ? s.scoreboardArea() : "unknown", ChatFormatting.WHITE);
		kv(src, "Lobby day", s.lobbyDay() + "  (clock " + s.clockTicks() + " ticks)", ChatFormatting.WHITE);
		kv(src, "Ping", s.pingMs() + " ms  (from " + s.pingSource().name() + ")", ChatFormatting.WHITE);
		kv(src, "Hypixel Mod API", LocationTracker.modApiInstalled() ? LocationTracker.modApiRaw() : "not installed", ChatFormatting.GRAY);
		if (raw) {
			src.sendFeedback(Component.literal("Scoreboard title: " + LocationParser.clean(LocationTracker.lastScoreboardTitle())).withStyle(ChatFormatting.GRAY));
			for (String l : LocationTracker.lastScoreboardLines()) src.sendFeedback(Component.literal("  sb| " + LocationParser.clean(l)).withStyle(ChatFormatting.GRAY));
			for (String l : LocationTracker.lastTabLines()) {
				String c = LocationParser.clean(l);
				if (!c.isEmpty()) src.sendFeedback(Component.literal("  tab| " + c).withStyle(ChatFormatting.DARK_GRAY));
			}
			src.sendFeedback(Component.literal("(Also written to the game log.)").withStyle(ChatFormatting.GRAY));
			dev.nucleusrouter.platform.NucleusRouterClient.LOG.info("[Nucleus Router] /nr where raw: state={} modApi={} sbTitle={} sb={} tab={}",
					s, LocationTracker.modApiRaw(), LocationTracker.lastScoreboardTitle(), LocationTracker.lastScoreboardLines(), LocationTracker.lastTabLines());
		}
		return 1;
	}

	private static int openConfig(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft client = ctx.getSource().getClient();
		// The chat screen closes after the command runs, so open ours on the next tick.
		client.schedule(() -> client.gui.setScreen(ConfigScreen.create(null)));
		return 1;
	}

	private static int reload(CommandContext<FabricClientCommandSource> ctx) {
		ConfigManager.load();
		ctx.getSource().sendFeedback(Component.literal("Settings reloaded. Check the game log for any values that were fixed.").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static Component header(String title) {
		return Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal(title).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
	}

	private static void line(FabricClientCommandSource src, String cmd, String what) {
		src.sendFeedback(Component.literal("  " + cmd).withStyle(ChatFormatting.YELLOW).append(Component.literal("  " + what).withStyle(ChatFormatting.GRAY)));
	}

	private static void kv(FabricClientCommandSource src, String key, String value, ChatFormatting color) {
		src.sendFeedback(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(color)));
	}
}
