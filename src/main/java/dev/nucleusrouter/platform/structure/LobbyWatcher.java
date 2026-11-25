package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.game.LobbyEvaluator;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lobby evaluation (SPEC §4, roadmap 2.5): every 5 s in the Hollows, runs the configured rules and,
 * when a rule newly says "consider another lobby", tells you once in chat with the reason.
 * {@code /nr lobby} shows every rule at any time. Only advice: it never acts.
 */
public final class LobbyWatcher {
	private static final int EVERY_TICKS = 100;
	private static int ticks;
	private static String lobby;
	private static final Set<LobbyEvaluator.Rule> announced = EnumSet.noneOf(LobbyEvaluator.Rule.class);

	private LobbyWatcher() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % EVERY_TICKS == 0) tick(); });
	}

	/** Facts for the evaluator from what the mod knows right now (client thread). */
	public static LobbyEvaluator.Facts facts() {
		StructureMap map = StructureTracker.map();
		Map<Structure, Vec3> pos = new EnumMap<>(Structure.class);
		Map<Structure, List<Vec3>> npcs = new EnumMap<>(Structure.class);
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			if (l != null && l.source() != StructureMap.Source.AREA) pos.put(s, l.pos());
			List<Vec3> n = new ArrayList<>();
			for (StructureMap.Npc npc : map.npcs(s)) n.add(npc.pos());
			npcs.put(s, n);
		}
		LocationState loc = LocationTracker.state();
		return new LobbyEvaluator.Facts(pos, npcs, map.missing(), loc.clockTicks() >= 0 ? loc.lobbyDay() : -1,
				ExplorationTracker.coverage().visitedFraction(), loc.pingMs());
	}

	public static List<LobbyEvaluator.Reason> evaluate() {
		return LobbyEvaluator.evaluate(facts(), ConfigManager.get());
	}

	private static void tick() {
		LocationState loc = LocationTracker.state();
		if (!loc.inHollows() || loc.serverId() == null) return;
		if (!loc.serverId().equals(lobby)) { lobby = loc.serverId(); announced.clear(); }
		NrConfig cfg = ConfigManager.get();
		if (!cfg.lobby.notify || !cfg.general.chatNotices) return;
		List<String> fresh = new ArrayList<>();
		for (LobbyEvaluator.Reason r : evaluate()) {
			if (r.warn() && announced.add(r.rule())) fresh.add(r.message());
		}
		if (fresh.isEmpty()) return;
		NucleusRouterClient.showLocal(Component.literal("Consider another lobby:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (String m : fresh) NucleusRouterClient.showLocal(Component.literal("  " + m).withStyle(ChatFormatting.GOLD));
		NucleusRouterClient.showLocal(Component.literal("  (Advice only. /nr lobby shows every check; rules can be changed in settings > Lobby check.)").withStyle(ChatFormatting.GRAY));
		NucleusRouterClient.LOG.info("[Nucleus Router] Lobby {}: swap advice {}", lobby, fresh);
	}
}
