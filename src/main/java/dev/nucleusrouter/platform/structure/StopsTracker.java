package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Default stop points (roadmap 3.3): recomputed in the background whenever the structure map changes
 * (at most every 5 s), drawn as small blue blocks with names, listed by {@code /nr stops}.
 */
public final class StopsTracker {
	private static final int EVERY_TICKS = 100;
	private static volatile List<StopPlan.Stop> stops = List.of();
	private static long computedForVersion = -1;
	private static String computedForLobby;
	private static boolean running;
	private static int ticks;

	private StopsTracker() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % EVERY_TICKS == 0) maybeRecompute(false); });
		WorldDraw.addLayerWhile(() -> ConfigManager.get().stops.showStops && LocationTracker.state().inHollows(), StopsTracker::draw);
	}

	public static List<StopPlan.Stop> stops() { return stops; }

	/** Recompute if the structures (or lobby) changed, or always if {@code force}. */
	public static void maybeRecompute(boolean force) {
		if (running || !LocationTracker.state().inHollows()) return;
		String lobby = StructureTracker.lobby();
		StructureMap map = StructureTracker.map();
		if (!force && map.version() == computedForVersion && java.util.Objects.equals(lobby, computedForLobby)) return;
		long version = map.version();
		NrConfig cfg = ConfigManager.get();
		running = true;
		// The map is only touched on the client thread; give the worker a copy through JSON-free means: read now
		var snapshotOfMap = copy(map);
		var layout = KeeperLayouts.layout();
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			long t = System.nanoTime();
			List<StopPlan.Stop> s = StopPlan.build(snapshotOfMap, layout, PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks)), cfg);
			NucleusRouterClient.LOG.info("[Nucleus Router] Stops: {} stops in {} ms", s.size(), (System.nanoTime() - t) / 1_000_000);
			return s;
		}).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			running = false;
			if (err != null) { NucleusRouterClient.LOG.warn("[Nucleus Router] Stops failed: {}", err.toString()); return; }
			stops = s;
			computedForVersion = version;
			computedForLobby = lobby;
		}));
	}

	/** A copy of the map for the worker thread (the live one keeps changing on the client thread). */
	private static StructureMap copy(StructureMap map) {
		com.google.gson.Gson g = new com.google.gson.Gson();
		return g.fromJson(g.toJson(map), StructureMap.class);
	}

	private static void draw(WorldDraw draw) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 eye = new Vec3(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
		for (StopPlan.Stop s : stops) {
			if (s.standFeet() == null || s.kind() == StopPlan.Kind.NUCLEUS) continue;
			Vec3 f = s.standFeet();
			int bx = (int) Math.floor(f.x()), by = (int) Math.floor(f.y()) - 1, bz = (int) Math.floor(f.z());
			draw.box(new AABB(bx, by, bz, bx + 1, by + 1, bz + 1), WorldDraw.argb(255, 0x5599FF), 2f, WorldDraw.argb(70, 0x5599FF), true);
			double dist = f.sub(eye).length();
			if (dist < 96) {
				float scale = (float) (0.5 * Math.max(1.0, dist / 12.0));
				draw.text("Stop: " + s.label(), WorldDraw.mc(f.add(new Vec3(0, 0.8 + scale, 0))), WorldDraw.argb(255, 0x88BBFF), scale, true);
			}
		}
	}
}
