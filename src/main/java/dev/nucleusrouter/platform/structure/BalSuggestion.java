package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.setup.BalPlacement;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.game.RouteOrder;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * {@code /nr bal} (roadmap 5.2, optional): one block to place in Khazad-dum so the route's last warps into the Bal
 * fight become one etherwarp. Needs a planned route ({@code /nr route}). Drawn until placed or {@code /nr bal clear}.
 * Render-only: shows where to stand, which face to click and where to warp from.
 */
public final class BalSuggestion {
	private static volatile BalPlacement.Suggestion shown;
	private static volatile boolean running;
	private static int ticks;

	private BalSuggestion() {}

	public static void init() {
		WorldDraw.addLayerWhile(() -> shown != null && LocationTracker.state().inHollows(), BalSuggestion::draw);
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % 10 == 0) checkPlaced(c); });
	}

	public static void clear() { shown = null; }

	/** Starts the search; returns a message for chat. */
	public static String start() {
		NrConfig cfg = dev.nucleusrouter.platform.config.ConfigManager.get();
		if (!cfg.placing.balSuggestion) return "The Bal block suggestion is off (Settings > Placing).";
		if (!LocationTracker.state().inHollows()) return "Only works in the Crystal Hollows.";
		if (running) return "Already looking.";
		RouteTracker.Route route = RouteTracker.current();
		if (route == null) return "Plan the route first: /nr route";
		RouteOrder.Step into = route.plan().steps().stream().filter(s -> s.toId().equals("khazad")).findFirst().orElse(null);
		StopPlan.Stop khazad = route.stops().stream().filter(s -> s.id().equals("khazad")).findFirst().orElse(null);
		if (into == null || khazad == null || khazad.standFeet() == null) return "The route doesn't go to Khazad-dum yet (Topaz crystal box not found?).";
		LegPlanner.Leg leg = route.leg(into.viaWarpNucleus() ? "nucleus" : into.fromId(), "khazad");
		if (leg == null || !leg.ok()) return "The route's leg into Khazad-dum isn't planned on the terrain (fly there once, then /nr route).";
		List<LegPlanner.Warp> warps = leg.warps();
		Vec3 goal = khazad.standFeet();
		SetupPreview.Inputs in = SetupPreview.inputs();
		running = true;
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			long t = System.nanoTime();
			SetupWorld w = SetupWorld.of(snap, in.zones(), in.explored(), in.noPlace());
			BalPlacement.Suggestion s = BalPlacement.suggest(w, warps, goal, cfg);
			NucleusRouterClient.LOG.info("[Nucleus Router] Bal suggestion in {} ms: {}", (System.nanoTime() - t) / 1_000_000, s.found() ? "found" : s.reason());
			return s;
		}).whenComplete((s, err) -> Minecraft.getInstance().execute(() -> {
			running = false;
			if (err != null) { NucleusRouterClient.reportAsyncError(err); return; }
			if (!s.found()) {
				NucleusRouterClient.showLocal(Component.literal("Bal block: nothing to suggest: " + s.reason() + ".").withStyle(ChatFormatting.GRAY));
				return;
			}
			shown = s;
			int[] b = s.block();
			NucleusRouterClient.showLocal(Component.literal(String.format(Locale.ROOT,
					"Bal block: place one block at %d %d %d (yellow). Then warp from the cyan spot (warp %d of that leg) straight onto it: %d warp%s fewer, about %.1f s saved. Aim margin %.1f deg. After placing, /nr route again.",
					b[0], b[1], b[2], s.fromWarp() + 1, s.warpsSaved(), s.warpsSaved() == 1 ? "" : "s", s.secondsSaved(), s.marginDeg())).withStyle(ChatFormatting.GOLD));
		}));
		return "Looking for a block to place near Bal...";
	}

	private static void checkPlaced(Minecraft mc) {
		BalPlacement.Suggestion s = shown;
		if (s == null || mc.level == null) return;
		int[] b = s.block();
		if (!mc.level.getBlockState(new BlockPos(b[0], b[1], b[2])).isAir()) {
			shown = null;
			NucleusRouterClient.showLocal(Component.literal("Bal block placed. Type /nr route to use it.").withStyle(ChatFormatting.GREEN));
		}
	}

	private static void draw(WorldDraw draw) {
		BalPlacement.Suggestion s = shown;
		if (s == null) return;
		NrConfig cfg = dev.nucleusrouter.platform.config.ConfigManager.get();
		int[] b = s.block();
		draw.block(b[0], b[1], b[2], WorldDraw.argb(255, 0xFFEB3B), 2.5f, WorldDraw.argb(80, 0xFFEB3B), true);
		draw.text("Place for Bal", WorldDraw.mc(new Vec3(b[0] + 0.5, b[1] + 1.4, b[2] + 0.5)), WorldDraw.argb(255, 0xFFEB3B), 0.6f, true);
		var p = s.place();
		if (p != null) {
			Vec3 f = p.standFeet();
			draw.box(new AABB(f.x() - 0.3, f.y(), f.z() - 0.3, f.x() + 0.3, f.y() + 0.1, f.z() + 0.3), WorldDraw.argb(255, 0x66FF66), 2f, WorldDraw.argb(60, 0x66FF66), true);
			draw.text("stand here to place", WorldDraw.mc(f.add(new Vec3(0, 2.1, 0))), WorldDraw.argb(255, 0x66FF66), 0.45f, true);
			draw.marker(WorldDraw.mc(p.clickPoint()), 0.12, WorldDraw.argb(255, 0xFFFFFF), WorldDraw.argb(160, 0xFFFFFF), true);
		}
		Vec3 from = s.fromFeet();
		draw.box(new AABB(from.x() - 0.5, from.y() - 1, from.z() - 0.5, from.x() + 0.5, from.y(), from.z() + 0.5), WorldDraw.argb(255, 0x00E5FF), 2f, WorldDraw.argb(50, 0x00E5FF), true);
		draw.line(WorldDraw.mc(from.add(new Vec3(0, cfg.etherwarp.eyeHeight, 0))), WorldDraw.mc(s.aimPoint()), WorldDraw.argb(160, 0x00E5FF), 1.5f, true);
	}
}
