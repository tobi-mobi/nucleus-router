package dev.nucleusrouter.platform.location;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.location.LocationParser;
import dev.nucleusrouter.core.location.LocationReading;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.game.HollowsAreas;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Works out where the player is, twice a second, on the client thread.
 * Reading the scoreboard and tab list is cheap (a few dozen strings), so it never stalls a frame.
 * Everything else reads {@link #state()}, which is an immutable snapshot.
 */
public final class LocationTracker {
	private static final int UPDATE_EVERY_TICKS = 10;

	private static volatile LocationState state = LocationState.NONE;
	private static volatile LocationReading modApiReading;
	private static volatile String modApiRaw = "(no Mod API packet yet)";
	private static boolean modApiInstalled;
	private static int tickCounter;

	// Last raw inputs, kept for /nr where
	private static volatile List<String> lastTabLines = List.of();
	private static volatile List<String> lastScoreboardLines = List.of();
	private static volatile String lastScoreboardTitle = "";

	private LocationTracker() {}

	public static LocationState state() { return state; }
	public static boolean modApiInstalled() { return modApiInstalled; }
	public static String modApiRaw() { return modApiRaw; }
	public static List<String> lastTabLines() { return lastTabLines; }
	public static List<String> lastScoreboardLines() { return lastScoreboardLines; }
	public static String lastScoreboardTitle() { return lastScoreboardTitle; }

	public static void init() {
		modApiInstalled = FabricLoader.getInstance().isModLoaded("hypixel-mod-api");
		if (modApiInstalled) {
			try {
				HypixelApiBridge.install();
				NucleusRouterClient.LOG.info("[Nucleus Router] Listening for Hypixel Mod API location packets (listen only, nothing sent).");
			} catch (Throwable t) {
				modApiInstalled = false;
				NucleusRouterClient.LOG.warn("[Nucleus Router] Hypixel Mod API found but could not hook into it ({}). Using tab list and scoreboard only.", t.toString());
			}
		} else {
			NucleusRouterClient.LOG.info("[Nucleus Router] Hypixel Mod API not installed. Using tab list and scoreboard for location.");
		}

		dev.nucleusrouter.platform.debug.Perf.onTick(client -> {
			if (++tickCounter % UPDATE_EVERY_TICKS == 0) update(client);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			modApiReading = null;
			modApiRaw = "(no Mod API packet yet)";
			state = LocationState.NONE;
		});
	}

	/** Called from the Mod API's thread. Only stores the reading; the next tick picks it up. */
	static void onModApiLocation(String serverName, String serverType, String mode, String map) {
		modApiRaw = "server=" + serverName + " type=" + serverType + " mode=" + mode + " map=" + map;
		modApiReading = LocationParser.fromModApi(serverName, serverType, mode, map);
	}

	private static void update(Minecraft client) {
		ClientPacketListener connection = client.getConnection();
		if (client.level == null || client.player == null || connection == null) {
			state = LocationState.NONE;
			return;
		}
		NrConfig cfg = ConfigManager.get();

		List<String> tabLines = new ArrayList<>();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			Component name = info.getTabListDisplayName();
			if (name != null) tabLines.add(name.getString());
		}
		lastTabLines = List.copyOf(tabLines);

		Scoreboard scoreboard = client.level.getScoreboard();
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		List<String> sbLines = new ArrayList<>();
		String sbTitle = "";
		if (sidebar != null) {
			sbTitle = sidebar.getDisplayName().getString();
			List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));
			entries.removeIf(PlayerScoreEntry::isHidden);
			entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
			for (PlayerScoreEntry e : entries) {
				PlayerTeam team = scoreboard.getPlayersTeam(e.owner());
				sbLines.add(PlayerTeam.formatNameForTeam(team, e.ownerName()).getString());
			}
		}
		lastScoreboardLines = List.copyOf(sbLines);
		lastScoreboardTitle = sbTitle;

		PlayerInfo self = connection.getPlayerInfo(client.player.getUUID());
		int latency = self != null ? self.getLatency() : 0;

		LocationState next = LocationState.resolve(
				modApiReading,
				LocationParser.fromTabList(tabLines),
				LocationParser.fromScoreboard(sbTitle, sbLines, HollowsAreas.ALL),
				client.level.getDefaultClockTime(), true,
				latency, cfg.cost.useMeasuredPing, cfg.cost.fallbackPingMs);

		LocationState prev = state;
		state = next;
		if (prev.inHollows() != next.inHollows() || (next.inHollows() && !Objects.equals(prev.serverId(), next.serverId()))) {
			announce(client, cfg, next);
		}
	}

	private static void announce(Minecraft client, NrConfig cfg, LocationState s) {
		String msg = s.inHollows()
				? "Entered Crystal Hollows: lobby " + (s.serverId() != null ? s.serverId() : "(unknown)") + ", day " + s.lobbyDay() + " (detected via " + s.hollowsSource() + ")"
				: "Left Crystal Hollows";
		NucleusRouterClient.LOG.info("[Nucleus Router] {}", msg);
		if (cfg.general.chatNotices) {
			NucleusRouterClient.showLocal(Component.literal(msg).withStyle(ChatFormatting.GRAY));
		}
	}
}
