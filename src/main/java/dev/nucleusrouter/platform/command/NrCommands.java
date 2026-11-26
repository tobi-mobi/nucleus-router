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
						.then(StructureCommands.designNode())
						.then(literal("setup").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.SetupPreview.startLobby()).withStyle(ChatFormatting.GRAY));
							return 1;
						})
								.then(literal("start").executes(ctx -> {
									ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.SetupGuide.start()).withStyle(ChatFormatting.AQUA));
									return 1;
								}))
								.then(literal("stop").executes(ctx -> {
									dev.nucleusrouter.platform.structure.SetupGuide.stop();
									ctx.getSource().sendFeedback(Component.literal("Guided setup off (progress is forgotten; /nr setup start begins again, steps already done tick off by themselves).").withStyle(ChatFormatting.GRAY));
									return 1;
								}))
								.then(literal("skip").executes(ctx -> {
									String m = dev.nucleusrouter.platform.structure.SetupGuide.confirm();
									ctx.getSource().sendFeedback(Component.literal(m != null ? m : "Guided setup is off: /nr setup start").withStyle(ChatFormatting.GRAY));
									return 1;
								}))
								.then(literal("back").executes(ctx -> {
									ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.SetupGuide.back()).withStyle(ChatFormatting.GRAY));
									return 1;
								}))
								.then(literal("replan").executes(ctx -> {
									ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.SetupGuide.replanNow()).withStyle(ChatFormatting.GRAY));
									return 1;
								})))
						.then(literal("verify").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.RouteVerifier.start()).withStyle(ChatFormatting.GRAY));
							return 1;
						})
								.then(literal("clear").executes(ctx -> {
									dev.nucleusrouter.platform.structure.RouteVerifier.clear();
									ctx.getSource().sendFeedback(Component.literal("Verify boxes hidden.").withStyle(ChatFormatting.GRAY));
									return 1;
								})))
						.then(ZoneCommands.captureNode())
						.then(ExploreCommands.node())
						.then(literal("lobby").executes(NrCommands::lobby))
						.then(literal("stops").executes(NrCommands::stops))
						.then(literal("run").executes(ctx -> {
									ctx.getSource().sendFeedback(Component.literal(dev.nucleusrouter.platform.structure.RunTracker.start()).withStyle(ChatFormatting.AQUA));
									return 1;
								})
								.then(literal("stop").executes(ctx -> {
									dev.nucleusrouter.platform.structure.RunTracker.stop();
									ctx.getSource().sendFeedback(Component.literal("Run mode off.").withStyle(ChatFormatting.GRAY));
									return 1;
								}))
								.then(literal("den").executes(ctx -> {
									dev.nucleusrouter.platform.structure.RunHud.startDenForTest();
									ctx.getSource().sendFeedback(Component.literal("Test: Queen's den countdown started (leave the den area, fly 30 blocks above the crystal or /warp nucleus to stop it).").withStyle(ChatFormatting.GRAY));
									return 1;
								})))
						.then(literal("route").executes(ctx -> {
							boolean started = dev.nucleusrouter.platform.structure.RouteTracker.start(dev.nucleusrouter.platform.structure.RouteTracker::report);
							ctx.getSource().sendFeedback(Component.literal(started ? "Planning the route in the background..." : "Already planning, or not in the Crystal Hollows.")
									.withStyle(started ? ChatFormatting.GRAY : ChatFormatting.RED));
							return 1;
						}))
						.then(ZoneCommands.zonesNode())
						.then(ZoneCommands.zoneNode())
						.then(literal("compass").executes(ctx -> { dev.nucleusrouter.platform.compass.CompassTracker.status(); return 1; })
								.then(literal("undo").executes(ctx -> { dev.nucleusrouter.platform.compass.CompassTracker.undoAndReport(); return 1; }))
								.then(literal("clear").executes(ctx -> {
									dev.nucleusrouter.platform.compass.CompassTracker.clearAll();
									ctx.getSource().sendFeedback(Component.literal("Compass lines cleared.").withStyle(ChatFormatting.GREEN));
									return 1;
								})))
						.then(literal("inspect").executes(InspectCommand::run))
						.then(literal("dump").executes(DebugCommands::dump))
						.then(literal("hud").executes(ctx -> {
							Minecraft client = ctx.getSource().getClient();
							client.schedule(() -> client.gui.setScreen(new dev.nucleusrouter.platform.structure.HudEditScreen(null)));
							return 1;
						}))
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
		line(src, "/nr run | run stop", "show the planned route while you run it: numbered warp targets, timer, splits");
		line(src, "/nr run den", "try the Queen's den countdown (it starts by itself after taking the Amber crystal)");
		line(src, "/nr design <structure> | design clear", "test: design the setup (lanes, bombs, mining, placing) from where you stand to a structure");
		line(src, "/nr setup", "plan the whole lobby's setup (tunnels, bombs, placing) and the run order, within 5 s; shown in the world");
		line(src, "/nr setup start | stop | skip | back | replan", "guided setup: one step at a time (bombs, mining, placing); steps tick off by themselves, key ] confirms/skips; a throw that goes wrong re-plans its leg");
		line(src, "/nr verify | verify clear", "check every planned etherwarp (setup tunnels and /nr route) against the world now; failing ones in red");
		line(src, "/nr route", "plan the run: order of the structures, when to /warp nucleus, total time");
		line(src, "/nr stops", "where the route will stop at each NPC and crystal (blue blocks in the world)");
		line(src, "/nr lobby", "is this lobby worth setting up? every check with its reason");
		line(src, "/nr explored [map]", "how much of the Hollows you've explored and where to go next; map opens a map (hover for details)");
		line(src, "/nr zones", "each found structure's no-bomb / no-placing zone, and whether you're inside");
		line(src, "/nr zone <name> corner1 | corner2 | reset", "measure a zone by standing at two opposite corners");
		line(src, "/nr capture <name> [radius]", "save the blocks around a structure (to learn its shape and orientation)");
		line(src, "/nr compass", "Wishing Compass lines and the estimated target");
		line(src, "/nr compass undo | clear", "remove the last compass line, or all of them");
		line(src, "/nr inspect", "what the block you look at is, and every entity near it (names, items)");
		line(src, "/nr dump", "save this lobby's terrain + settings to a file for offline testing");
		line(src, "/nr hud", "drag the on-screen panels (structure list, run panel) where you want them");
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

	private static int lobby(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		LocationState s = LocationTracker.state();
		src.sendFeedback(header("Lobby check" + (s.serverId() != null ? " (" + s.serverId() + ", day " + s.lobbyDay() + ")" : "")));
		if (!s.inHollows()) {
			src.sendFeedback(Component.literal("  Not in the Crystal Hollows.").withStyle(ChatFormatting.GRAY));
			return 1;
		}
		var reasons = dev.nucleusrouter.platform.structure.LobbyWatcher.evaluate();
		for (var r : reasons) {
			String mark = !r.checked() ? "-" : r.warn() ? "!" : "ok";
			ChatFormatting col = !r.checked() ? ChatFormatting.DARK_GRAY : r.warn() ? ChatFormatting.GOLD : ChatFormatting.GREEN;
			src.sendFeedback(Component.literal("  [" + mark + "] ").withStyle(col).append(Component.literal(r.message()).withStyle(r.checked() ? ChatFormatting.WHITE : ChatFormatting.GRAY)));
		}
		src.sendFeedback(Component.literal(dev.nucleusrouter.game.LobbyEvaluator.anyWarning(reasons)
				? "  Verdict: consider another lobby (see the [!] lines)." : "  Verdict: nothing against this lobby so far.").withStyle(ChatFormatting.AQUA));
		src.sendFeedback(Component.literal("  The run time is a rough straight-line estimate until the route planner exists.").withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	private static int stops(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		dev.nucleusrouter.platform.structure.StopsTracker.maybeRecompute(true);
		var list = dev.nucleusrouter.platform.structure.StopsTracker.stops();
		src.sendFeedback(header("Stops (" + list.size() + ")"));
		if (list.isEmpty()) src.sendFeedback(Component.literal("  None yet: find structures first (or run this again in a moment).").withStyle(ChatFormatting.GRAY));
		for (var s : list) {
			String where = s.standFeet() == null ? "no spot in reach found (terrain not loaded?)"
					: Math.round(s.standFeet().x() - 0.5) + " " + Math.round(s.standFeet().y()) + " " + Math.round(s.standFeet().z() - 0.5);
			String wait = s.waitSeconds() > 0 ? String.format(", %.1f s there%s", s.waitSeconds(), s.manual() ? " (by hand)" : "") : "";
			src.sendFeedback(Component.literal("  " + s.label() + ": ").withStyle(ChatFormatting.GRAY)
					.append(Component.literal(where + wait).withStyle(s.ready() ? ChatFormatting.WHITE : ChatFormatting.RED))
					.append(Component.literal("  " + s.note()).withStyle(ChatFormatting.DARK_GRAY)));
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
