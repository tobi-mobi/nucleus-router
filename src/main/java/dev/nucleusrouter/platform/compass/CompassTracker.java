package dev.nucleusrouter.platform.compass;

import dev.nucleusrouter.core.compass.CompassLine;
import dev.nucleusrouter.core.compass.Triangulation;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.location.LocationState;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.structure.StructureTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wishing Compass solver (SPEC §4, roadmap 2.2). When you right-click a Wishing Compass, the particles
 * the server sends during the next few seconds are collected; the straightest trail is fitted as a line.
 * Lines from several uses are crossed (least squares) to estimate the target, with an uncertainty
 * radius; while that is large, a spot to the side is suggested for the next compass.
 *
 * Read-only: it only observes your click (never cancels it) and the particle packets.
 */
public final class CompassTracker {
	private static final int MAX_POINTS = 2000;

	private static long captureStartMs = -1;
	private static Vec3 captureEye;
	private static final Map<String, List<Vec3>> captured = new LinkedHashMap<>();

	private static final List<CompassLine> lines = new ArrayList<>();
	private static Triangulation.Estimate estimate;
	private static Vec3 nextSpot;
	private static String lobby;

	private CompassTracker() {}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> { onUse(player); return InteractionResult.PASS; });
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> { onUse(player); return InteractionResult.PASS; });
		ClientTickEvents.END_CLIENT_TICK.register(CompassTracker::onTick);
		WorldDraw.addLayerWhile(() -> ConfigManager.get().compass.showInWorld && LocationTracker.state().inHollows()
				&& (!lines.isEmpty() || captureStartMs >= 0), CompassTracker::draw);
	}

	// ------------------------------------------------------------------ client thread

	private static void onUse(Player player) {
		Minecraft client = Minecraft.getInstance();
		if (player != client.player || !LocationTracker.state().inHollows()) return;
		String want = ConfigManager.get().compass.itemName.toLowerCase(Locale.ROOT);
		if (!player.getMainHandItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains(want)) return;
		captureStartMs = System.currentTimeMillis();
		captureEye = new Vec3(player.getX(), player.getEyeY(), player.getZ());
		captured.clear();
	}

	/** Called from the particle packet mixin (client thread, after the packet was handled). */
	public static void onParticle(ParticleOptions particle, double x, double y, double z) {
		if (captureStartMs < 0 || captureEye == null) return;
		if (!Minecraft.getInstance().isSameThread()) return;
		NrConfig.Compass cfg = ConfigManager.get().compass;
		double dx = x - captureEye.x(), dy = y - captureEye.y(), dz = z - captureEye.z();
		if (dx * dx + dy * dy + dz * dz > cfg.captureRadius * cfg.captureRadius) return;
		String type = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()));
		List<Vec3> pts = captured.computeIfAbsent(type, k -> new ArrayList<>());
		if (pts.size() < MAX_POINTS) pts.add(new Vec3(x, y, z));
	}

	private static void onTick(Minecraft client) {
		LocationState loc = LocationTracker.state();
		if (loc.serverId() != null && !loc.serverId().equals(lobby)) {
			if (lobby != null) clearAll();
			lobby = loc.serverId();
		}
		if (captureStartMs < 0) return;
		if (System.currentTimeMillis() - captureStartMs < ConfigManager.get().compass.captureSeconds * 1000) return;
		finishCapture();
	}

	private static void finishCapture() {
		NrConfig.Compass cfg = ConfigManager.get().compass;
		Vec3 eye = captureEye;
		captureStartMs = -1;
		StringBuilder seen = new StringBuilder();
		for (Map.Entry<String, List<Vec3>> e : captured.entrySet())
			seen.append(seen.length() > 0 ? ", " : "").append(e.getKey().replace("minecraft:", "")).append(" x").append(e.getValue().size());
		CompassLine line = CompassLine.pickBest(captured, eye, cfg.minPoints, 2.0, cfg.particleTypes);
		NucleusRouterClient.LOG.info("[Nucleus Router] Compass: used at {}, particles seen: {}; line: {}", eye, seen.length() == 0 ? "none" : seen, line);
		captured.clear();
		if (line == null) {
			show(Component.literal("Compass: no particle trail found (" + (seen.length() == 0 ? "no particles at all" : "seen: " + seen)
					+ "). Try again in the open, or send this line to the developer.").withStyle(ChatFormatting.RED));
			return;
		}
		lines.add(line);
		recompute();
		show(Component.literal(String.format("Compass line #%d recorded (%s, %d particles, direction +-%.1f deg).",
				lines.size(), line.particle().replace("minecraft:", ""), line.points(), Math.toDegrees(line.sigmaRad()))).withStyle(ChatFormatting.GREEN));
		describeEstimate();
	}

	private static void recompute() {
		estimate = Triangulation.solve(lines);
		NrConfig.Hollows h = ConfigManager.get().hollows;
		boolean good = estimate.ok() && !estimate.inconsistent() && estimate.radius() <= ConfigManager.get().compass.goodRadiusBlocks;
		nextSpot = lines.isEmpty() || good ? null : Triangulation.suggestNextSpot(lines, estimate.ok() ? estimate : null,
				new Vec3((h.minX + h.maxX) / 2.0, 0, (h.minZ + h.maxZ) / 2.0), h.minX, h.maxX, h.minZ, h.maxZ);
	}

	private static void describeEstimate() {
		if (lines.isEmpty()) return;
		if (!estimate.ok()) {
			show(Component.literal("Compass: " + estimate.message() + ". Use another one at the cyan marker (" + fmt(nextSpot) + ").").withStyle(ChatFormatting.YELLOW));
			return;
		}
		Vec3 p = estimate.point();
		String near = nearbyStructure(p, estimate.radius());
		show(Component.literal(String.format("Compass target: %s, +-%.0f blocks%s", fmt(p), estimate.radius(), near)).withStyle(ChatFormatting.AQUA));
		if (estimate.inconsistent()) {
			List<Integer> bad = Triangulation.outliers(lines, estimate);
			StringBuilder sb = new StringBuilder();
			for (int i : bad) sb.append(sb.length() > 0 ? ", " : "").append('#').append(i + 1);
			show(Component.literal("The lines don't agree" + (sb.length() > 0 ? " (" + sb + " is off)" : "")
					+ ": the compass may have pointed at different structures. /nr compass undo removes the last line, /nr compass clear starts over.").withStyle(ChatFormatting.GOLD));
		} else if (nextSpot != null) {
			show(Component.literal("Not sure enough yet. Use another compass at the cyan marker (" + fmt(nextSpot) + ").").withStyle(ChatFormatting.YELLOW));
		}
	}

	/** ", probably X (already found)" if a found structure sits inside the estimate's radius. */
	private static String nearbyStructure(Vec3 p, double radius) {
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			if (l == null || l.source() == StructureMap.Source.AREA) continue;
			if (l.pos().sub(p).length() <= Math.max(radius, 20)) return ", probably " + s.displayName + " (already found)";
		}
		return "";
	}

	// ------------------------------------------------------------------ commands

	public static void clearAll() {
		lines.clear();
		estimate = null;
		nextSpot = null;
		captureStartMs = -1;
		captured.clear();
	}

	public static boolean undo() {
		if (lines.isEmpty()) return false;
		lines.remove(lines.size() - 1);
		recompute();
		return true;
	}

	public static void status() {
		show(Component.literal("Wishing Compass").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		if (lines.isEmpty()) {
			show(Component.literal("  No compass lines yet. Right-click a Wishing Compass in the Crystal Hollows.").withStyle(ChatFormatting.GRAY));
			return;
		}
		for (int i = 0; i < lines.size(); i++) {
			CompassLine l = lines.get(i);
			show(Component.literal(String.format("  #%d from %s, %s, %d particles, +-%.1f deg", i + 1, fmt(l.from()),
					l.particle().replace("minecraft:", ""), l.points(), Math.toDegrees(l.sigmaRad()))).withStyle(ChatFormatting.WHITE));
		}
		describeEstimate();
	}

	public static void undoAndReport() {
		if (undo()) {
			show(Component.literal("Removed the last compass line (" + lines.size() + " left).").withStyle(ChatFormatting.GREEN));
			describeEstimate();
		} else {
			show(Component.literal("No compass lines to remove.").withStyle(ChatFormatting.GRAY));
		}
	}

	// ------------------------------------------------------------------ drawing

	private static final int LINE = 0xFFAA00, ESTIMATE = 0xFF55FF, NEXT = 0x55FFFF;

	private static void draw(WorldDraw draw) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		Vec3 eye = new Vec3(client.player.getX(), client.player.getEyeY(), client.player.getZ());
		for (int i = 0; i < lines.size(); i++) {
			CompassLine l = lines.get(i);
			double len = estimate != null && estimate.ok() ? Math.max(30, l.along(estimate.point()) + 30) : 200;
			Vec3 start = l.origin().add(l.dir().scale(-l.extent() / 2));
			draw.line(WorldDraw.mc(start), WorldDraw.mc(l.origin().add(l.dir().scale(len))), WorldDraw.argb(200, LINE), 2f, true);
			draw.text("#" + (i + 1), WorldDraw.mc(start.add(new Vec3(0, 0.6, 0))), WorldDraw.argb(255, LINE), 0.6f, true);
		}
		if (estimate != null && estimate.ok()) {
			Vec3 p = estimate.point();
			double dist = p.sub(eye).length();
			int col = estimate.inconsistent() ? 0xFF5555 : ESTIMATE;
			draw.marker(WorldDraw.mc(p), 1.0, WorldDraw.argb(255, col), WorldDraw.argb(80, col), true);
			draw.circle(WorldDraw.mc(p), (float) Math.max(1, estimate.radius()), WorldDraw.argb(200, col), true);
			draw.line(WorldDraw.mc(p), WorldDraw.mc(p.add(new Vec3(0, 30, 0))), WorldDraw.argb(160, col), 2f, true);
			float scale = (float) (0.6 * Math.max(1.0, dist / 10.0));
			draw.text(String.format("Compass target +-%.0f  %dm", estimate.radius(), Math.round(dist)), WorldDraw.mc(p.add(new Vec3(0, 31 + scale, 0))), WorldDraw.argb(255, col), scale, true);
		}
		if (nextSpot != null) {
			double dist = nextSpot.sub(eye).length();
			draw.marker(WorldDraw.mc(nextSpot), 0.8, WorldDraw.argb(255, NEXT), WorldDraw.argb(80, NEXT), true);
			draw.line(WorldDraw.mc(nextSpot.add(new Vec3(0, -20, 0))), WorldDraw.mc(nextSpot.add(new Vec3(0, 20, 0))), WorldDraw.argb(160, NEXT), 2f, true);
			float scale = (float) (0.6 * Math.max(1.0, dist / 10.0));
			draw.text("Use a compass here  " + Math.round(dist) + "m", WorldDraw.mc(nextSpot.add(new Vec3(0, 21 + scale, 0))), WorldDraw.argb(255, NEXT), scale, true);
		}
	}

	private static void show(Component c) { NucleusRouterClient.showLocal(c); }

	private static String fmt(Vec3 v) {
		return v == null ? "?" : Math.round(v.x()) + " " + Math.round(v.y()) + " " + Math.round(v.z());
	}
}
