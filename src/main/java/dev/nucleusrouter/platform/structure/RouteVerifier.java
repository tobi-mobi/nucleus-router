package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.game.LobbySetup;
import dev.nucleusrouter.game.SetupRepair;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /nr verify} ("verify route", SPEC §5, roadmap 4.7): checks every planned etherwarp against the world as it is
 * now and highlights the ones that would fail: the tunnels of the last {@code /nr setup} plan and the warps of the
 * last {@code /nr route}. Red box = the block the warp should land on; orange = what it would hit instead; a line from
 * where you warp from. Shown for {@link #SHOW_MS}, or until {@code /nr verify clear}. Runs on a snapshot off the
 * client thread.
 */
public final class RouteVerifier {
	static final long SHOW_MS = 60_000;
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "nucleusrouter-verify");
		t.setDaemon(true);
		return t;
	});

	private static volatile List<SetupRepair.Failure> shown;
	private static volatile long shownAt;
	private static volatile boolean running;

	private RouteVerifier() {}

	public static void init() {
		WorldDraw.addLayerWhile(() -> shown != null && System.currentTimeMillis() - shownAt < SHOW_MS && LocationTracker.state().inHollows(), RouteVerifier::draw);
	}

	public static void clear() { shown = null; }

	/** Starts the check; the result arrives in chat. Returns a message for the player. */
	public static String start() {
		if (!LocationTracker.state().inHollows()) return "Only in the Crystal Hollows.";
		if (running) return "Already checking, wait a moment.";
		List<SetupRepair.WarpCheck> checks = new ArrayList<>();
		String lobby = StructureTracker.lobby();
		// Tunnels of the setup plan
		LobbySetup.Result setup = SetupPreview.lobbyPlan();
		if (setup != null)
			for (LobbySetup.LegSetup leg : setup.setups()) checks.addAll(SetupRepair.lanes("tunnel " + names(leg.from(), leg.to(), null), leg.design().lanes()));
		// Warps of the planned route (terrain legs only; the setup's tunnels are above)
		RouteTracker.Route route = RouteTracker.current();
		int fromRoute = 0;
		if (route != null && Objects.equals(route.lobby(), lobby)) {
			Map<String, String> labels = new HashMap<>();
			for (StopPlan.Stop s : route.stops()) labels.put(s.id(), s.label());
			for (Map.Entry<String, LegPlanner.Leg> e : route.legs().entrySet()) {
				if (!e.getValue().ok()) continue;
				String[] p = e.getKey().split(">");
				List<SetupRepair.WarpCheck> c = SetupRepair.terrain("route " + names(p[0], p[1], labels), e.getValue());
				fromRoute += c.size();
				checks.addAll(c);
			}
		}
		if (checks.isEmpty()) return "Nothing to check: plan with /nr route or /nr setup first.";
		running = true;
		Etherwarp.Params params = Etherwarp.Params.fromConfig(ConfigManager.get());
		int total = checks.size(), routeWarps = fromRoute;
		// Now = what is there at this moment (a mined ore counts as gone)
		WorldCapture.snapshot().thenApplyAsync(snap -> SetupRepair.verify(checks, params, snap::currentlySolid), POOL)
				.whenComplete((fails, err) -> Minecraft.getInstance().execute(() -> {
					running = false;
					if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
					report(fails, total, routeWarps);
				}));
		return "Checking " + total + " planned etherwarps against the world as it is now...";
	}

	private static String names(String from, String to, Map<String, String> labels) {
		return label(from, labels) + " to " + label(to, labels);
	}

	private static String label(String id, Map<String, String> labels) {
		if (labels != null && labels.containsKey(id)) return labels.get(id);
		for (dev.nucleusrouter.game.Structure s : dev.nucleusrouter.game.Structure.values()) if (s.key.equals(id)) return s.displayName;
		return id.equals("nucleus") ? "Crystal Nucleus" : id;
	}

	private static void report(List<SetupRepair.Failure> fails, int total, int routeWarps) {
		shown = fails;
		shownAt = System.currentTimeMillis();
		if (fails.isEmpty()) {
			NucleusRouterClient.showLocal(Component.literal("All " + total + " planned etherwarps work right now.").withStyle(ChatFormatting.GREEN));
			return;
		}
		NucleusRouterClient.showLocal(Component.literal(fails.size() + " of " + total + " planned etherwarps would fail right now (red boxes in the world for a minute):")
				.withStyle(ChatFormatting.GOLD));
		int n = 0;
		for (SetupRepair.Failure f : fails) {
			if (n++ >= 8) { NucleusRouterClient.showLocal(Component.literal("  ...and " + (fails.size() - 8) + " more (all in the game log).").withStyle(ChatFormatting.GRAY)); break; }
			int[] t = f.warp().target();
			NucleusRouterClient.showLocal(Component.literal(String.format("  %s: %s (target %d %d %d)", f.warp().where(), f.why(), t[0], t[1], t[2])).withStyle(ChatFormatting.GRAY));
		}
		for (SetupRepair.Failure f : fails)
			NucleusRouterClient.LOG.info("[Nucleus Router] Verify: {}: {} (target {})", f.warp().where(), f.why(), java.util.Arrays.toString(f.warp().target()));
		if (routeWarps > 0)
			NucleusRouterClient.showLocal(Component.literal("  Route warps fail when the terrain changed since /nr route (mined, bombed, placed): /nr route again. Tunnel warps: finish the setup, or /nr setup replan.")
					.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void draw(WorldDraw draw) {
		List<SetupRepair.Failure> fails = shown;
		if (fails == null) return;
		double eye = ConfigManager.get().etherwarp.eyeHeight;
		int n = 0;
		for (SetupRepair.Failure f : fails) {
			if (n++ >= 200) break;
			int[] t = f.warp().target();
			draw.outline(new AABB(t[0], t[1], t[2], t[0] + 1, t[1] + 1, t[2] + 1), WorldDraw.argb(255, 0xFF3030), 3f, true);
			Vec3 from = f.warp().from().add(new Vec3(0, eye, 0));
			draw.line(WorldDraw.mc(from), WorldDraw.mc(new Vec3(t[0] + 0.5, t[1] + 0.5, t[2] + 0.5)), WorldDraw.argb(150, 0xFF3030), 1.5f, true);
			if (f.hit() != null) {
				int[] h = f.hit();
				draw.outline(new AABB(h[0], h[1], h[2], h[0] + 1, h[1] + 1, h[2] + 1), WorldDraw.argb(255, 0xFF9800), 2f, true);
			}
		}
	}
}
