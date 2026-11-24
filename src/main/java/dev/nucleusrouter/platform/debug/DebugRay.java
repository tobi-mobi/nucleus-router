package dev.nucleusrouter.platform.debug;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.AimRegion;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.EyeHeightFit;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.render.WorldDraw;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * {@code /nr debug ray} (SPEC §6): shows where an etherwarp from your current aim would land, and
 * checks the model against your real warps.
 *
 * Render-only: it only <em>observes</em> your right-clicks (to remember what was predicted at that
 * moment) and the server's teleport. It never cancels, changes or sends anything.
 *
 * Cost: one ray (about 60 block reads) per frame while on; the aim region (a few hundred rays) only
 * when the target block changes, at most 4 times a second; the eye-height check once per real warp.
 */
public final class DebugRay {
	private static final long WARP_TIMEOUT_MS = 2000;
	private static final long AIM_REFRESH_MS = 250;
	private static final int MAX_LOG = 200;

	private static boolean enabled;

	// live overlay state (client thread)
	private static Etherwarp.Result live;
	private static AimRegion.Result aim;
	private static long aimTargetKey = Long.MIN_VALUE;
	private static long aimComputedAt;

	// warp logging (client thread)
	private record Pending(long clickMs, Vec3 feet, Vec3 dir, double eyeHeight, Etherwarp.Result predicted) {}
	private static Pending pending;
	private static int warps, matches, mismatches, noTeleport;
	private static final List<List<double[]>> eyeRanges = new ArrayList<>();
	private static final List<Long> delays = new ArrayList<>();

	private DebugRay() {}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> { onUse(player); return InteractionResult.PASS; });
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> { onUse(player); return InteractionResult.PASS; });
		WorldDraw.addLayer((draw, ctx) -> { if (enabled) drawOverlay(draw); });
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> checkTimeout());
	}

	public static boolean toggle() {
		enabled = !enabled;
		pending = null;
		return enabled;
	}

	public static boolean enabled() { return enabled; }

	private static Etherwarp.Params params() { return Etherwarp.Params.fromConfig(ConfigManager.get()); }

	private static LiveSolid liveSolid(Minecraft mc) {
		return new LiveSolid(mc.level, new HashSet<>(ConfigManager.get().etherwarp.rayPassesThrough));
	}

	private static Vec3 feetOf(LocalPlayer p) { return new Vec3(p.getX(), p.getY(), p.getZ()); }
	private static Vec3 dirOf(LocalPlayer p) { return Vec3.fromYawPitch(p.getYRot(), p.getXRot()); }

	// ------------------------------------------------------------------ overlay

	private static void drawOverlay(WorldDraw draw) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		NrConfig cfg = ConfigManager.get();
		Etherwarp.Params params = params();
		LiveSolid solid = liveSolid(mc);
		Vec3 eye = Etherwarp.eye(feetOf(p), params.eyeHeight());
		live = Etherwarp.warp(eye, dirOf(p), params, solid);

		if (live.hit() == null) {
			aim = null;
			aimTargetKey = Long.MIN_VALUE;
			return;
		}
		VoxelRay.Hit h = live.hit();
		boolean ok = live.valid();
		int stroke = ok ? WorldDraw.argb(255, 0x33FF66) : WorldDraw.argb(255, 0xFF4444);
		int fill = ok ? WorldDraw.argb(60, 0x33FF66) : WorldDraw.argb(60, 0xFF4444);
		draw.block(h.x(), h.y(), h.z(), stroke, 3f, fill, true);

		// Aim region: recompute when the target block changes, throttled
		long key = (((long) h.x() & 0x1FFFFF) << 42) | (((long) h.y() & 0x1FFFFF) << 21) | ((long) h.z() & 0x1FFFFF);
		long now = System.currentTimeMillis();
		if (key != aimTargetKey && now - aimComputedAt > AIM_REFRESH_MS) {
			aim = AimRegion.compute(eye, h.x(), h.y(), h.z(), params, solid, Math.min(cfg.etherwarp.aimSamplesPerFace, 8));
			aimTargetKey = key;
			aimComputedAt = now;
		}

		String label;
		if (ok) {
			draw.marker(WorldDraw.mc(live.landingFeet()).add(0, 0.05, 0), 0.25, WorldDraw.argb(255, 0xFFFFFF), WorldDraw.argb(90, 0xFFFFFF), true);
			label = String.format("%.1f blocks", h.distance());
			if (aim != null && aim.reachable() && key == aimTargetKey) {
				label += String.format("  |  aim margin %.1f°  |  target %.1f° wide", aim.bestMarginDegrees(), aim.coneDegrees());
				draw.marker(WorldDraw.mc(aim.bestPoint()), 0.08, WorldDraw.argb(255, 0x33CCFF), WorldDraw.argb(200, 0x33CCFF), true);
			}
		} else {
			label = String.format("%.1f blocks  |  blocked above, no warp", h.distance());
		}
		// Grow the label with distance so it stays readable on far targets
		float scale = (float) (0.5 * Math.max(1.0, h.distance() / 8.0));
		draw.text(label, new net.minecraft.world.phys.Vec3(h.x() + 0.5, h.y() + 1.3 + scale, h.z() + 0.5), WorldDraw.argb(255, ok ? 0xFFFFFF : 0xFF8888), scale, true);
	}

	// ------------------------------------------------------------------ warp logging

	private static boolean holdingEtherwarpItem(Player p) {
		String name = p.getMainHandItem().getHoverName().getString();
		return name.contains("Aspect of the Void") || name.contains("Aspect of the End");
	}

	/** You right-clicked. If it's a sneaking etherwarp, remember what we predicted right now. */
	private static void onUse(Player player) {
		if (!enabled) return;
		Minecraft mc = Minecraft.getInstance();
		if (player != mc.player || mc.level == null || !player.isShiftKeyDown() || !holdingEtherwarpItem(player)) return;
		LocalPlayer p = mc.player;
		Etherwarp.Params params = params();
		Vec3 feet = feetOf(p), dir = dirOf(p);
		pending = new Pending(System.currentTimeMillis(), feet, dir, params.eyeHeight(),
				Etherwarp.warp(Etherwarp.eye(feet, params.eyeHeight()), dir, params, liveSolid(mc)));
	}

	/** Called by {@code ClientPacketListenerMixin} after the server moved the player. */
	public static void onServerTeleport() {
		if (!enabled || pending == null) return;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		Pending w = pending;
		pending = null;
		long delay = System.currentTimeMillis() - w.clickMs();
		if (p == null || mc.level == null || delay > WARP_TIMEOUT_MS) return;

		// Landing feet are at (x + 0.5, y + 1, z + 0.5) of the target block
		int ax = (int) Math.floor(p.getX()), ay = (int) Math.floor(p.getY() - 0.5), az = (int) Math.floor(p.getZ());
		warps++;
		delays.add(delay);
		VoxelRay.Hit predicted = w.predicted().hit();
		boolean match = w.predicted().valid() && predicted.isBlock(ax, ay, az);
		if (match) matches++; else mismatches++;

		List<double[]> ranges = EyeHeightFit.matchingRanges(new EyeHeightFit.Observation(w.feet(), w.dir(), ax, ay, az), params().range(), liveSolid(mc));
		if (eyeRanges.size() >= MAX_LOG) eyeRanges.remove(0);
		eyeRanges.add(ranges);
		if (delays.size() > MAX_LOG) delays.remove(0);

		String pred = predicted == null ? "nothing in range" : predicted.x() + " " + predicted.y() + " " + predicted.z() + (w.predicted().valid() ? "" : " (blocked above)");
		String eyeOk = ranges.isEmpty() ? "no eye height 1.0-1.8 explains it" : "eye heights that fit: " + fmtRanges(ranges);
		NucleusRouterClient.LOG.info("[Nucleus Router] Warp #{}: predicted {}, landed {} {} {} -> {} | delay {} ms | feet {} yaw {} pitch {} eye {} | {}",
				warps, pred, ax, ay, az, match ? "MATCH" : "MISMATCH", delay, w.feet(), String.format("%.2f", w.dir().yawDeg()),
				String.format("%.2f", w.dir().pitchDeg()), w.eyeHeight(), eyeOk);
		NucleusRouterClient.showLocal(Component.literal("Warp #" + warps + ": ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(match ? "prediction correct" : "MISMATCH (predicted " + pred + ", landed " + ax + " " + ay + " " + az + ")")
						.withStyle(match ? ChatFormatting.GREEN : ChatFormatting.RED))
				.append(Component.literal("  " + delay + " ms").withStyle(ChatFormatting.GRAY)));
	}

	private static void checkTimeout() {
		if (pending != null && System.currentTimeMillis() - pending.clickMs() > WARP_TIMEOUT_MS) {
			Pending w = pending;
			pending = null;
			noTeleport++;
			boolean expected = !w.predicted().valid();
			NucleusRouterClient.LOG.info("[Nucleus Router] Click without teleport. Prediction was {} -> {}", w.predicted().outcome(), expected ? "consistent" : "MISMATCH");
			if (!expected) {
				mismatches++;
				NucleusRouterClient.showLocal(Component.literal("No teleport, but the model predicted a valid warp").withStyle(ChatFormatting.RED));
			}
		}
	}

	private static String fmtRanges(List<double[]> ranges) {
		StringBuilder sb = new StringBuilder();
		for (double[] r : ranges) sb.append(String.format("%.3f-%.3f ", r[0], r[1]));
		return sb.toString().trim();
	}

	// ------------------------------------------------------------------ stats for commands

	public record Stats(int warps, int matches, int mismatches, int noTeleport, EyeHeightFit.Suggestion eye,
			long medianDelayMs, long minDelayMs, double renderMicros) {}

	public static Stats stats() {
		List<Long> sorted = new ArrayList<>(delays);
		sorted.sort(null);
		long median = sorted.isEmpty() ? -1 : sorted.get(sorted.size() / 2);
		long min = sorted.isEmpty() ? -1 : sorted.get(0);
		return new Stats(warps, matches, mismatches, noTeleport, EyeHeightFit.suggest(eyeRanges), median, min, WorldDraw.avgFrameMicros());
	}

	public static void clear() {
		warps = matches = mismatches = noTeleport = 0;
		eyeRanges.clear();
		delays.clear();
		pending = null;
	}
}
