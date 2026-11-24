package dev.nucleusrouter.platform.debug;

import com.google.gson.GsonBuilder;
import dev.nucleusrouter.core.bomb.BlastAnalysis;
import dev.nucleusrouter.core.bomb.BombThrow;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.world.VoxelClass;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.BlockRules;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /nr calibrate bomb} (SPEC §6): measures one real Bob-omb blast against the model.
 *
 * Flow: arm with the command, then throw a Bob-omb. At the throw we save a snapshot of the world and
 * the model's prediction. The blocks that turn to air shortly after are the blast. We work out the
 * real centre, radius and shape, compare with the model, show a summary in chat, draw both centres,
 * and save a full report to {@code nucleusrouter/calibration/}.
 *
 * Render-only: the throw is only observed (Fabric use events, always PASS); nothing is sent.
 */
public final class BombCalibration {
	private static final long NO_BLAST_TIMEOUT_MS = 12_000;
	private static final long COLLECT_WINDOW_MS = 1_500;
	private static final long SHOW_RESULT_MS = 60_000;

	private enum State { OFF, ARMED, THROWN, COLLECTING }

	private static State state = State.OFF;
	private static long throwMs, firstChangeMs;
	private static Vec3 throwEye, throwDir, modelCentre;
	private static BombThrow.Prediction prediction;
	private static CompletableFuture<WorldSnapshot> before;
	private static final Map<Long, int[]> removed = new LinkedHashMap<>();
	private static String explosionPacket;

	// What to draw after a measurement
	private static Vec3 shownModelCentre, shownMeasuredCentre;
	private static double shownModelRadius, shownMeasuredRadius;
	private static long shownUntil;
	/** Last finished measurement, for the in-game test. */
	public static volatile BlastAnalysis.Result lastResultForTest;
	public static volatile Path lastReportForTest;

	private BombCalibration() {}

	public static void init() {
		UseItemCallback.EVENT.register((player, level, hand) -> { onUse(player); return InteractionResult.PASS; });
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> { onUse(player); return InteractionResult.PASS; });
		ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
		WorldDraw.addLayer((draw, ctx) -> drawResult(draw));
	}

	/** Arms the measurement. Returns a message for the player. */
	public static String arm() {
		reset();
		state = State.ARMED;
		return "Bomb calibration armed. Stand somewhere safe, aim at solid Hard Stone 10-30 blocks away (away from structures) and throw ONE Bob-omb. Don't mine until the report appears.";
	}

	public static String cancel() {
		boolean was = state != State.OFF;
		reset();
		return was ? "Bomb calibration cancelled." : "Bomb calibration wasn't running.";
	}

	private static void reset() {
		state = State.OFF;
		removed.clear();
		prediction = null;
		before = null;
		explosionPacket = null;
		firstChangeMs = 0;
	}

	private static boolean holdingBomb(Player p) {
		return p.getMainHandItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains("bob-omb");
	}

	private static void onUse(Player player) {
		Minecraft mc = Minecraft.getInstance();
		if (state != State.ARMED || player != mc.player || mc.level == null || !holdingBomb(player)) return;
		LocalPlayer p = mc.player;
		NrConfig cfg = ConfigManager.get();
		throwEye = new Vec3(p.getX(), p.getEyeY(), p.getZ());
		throwDir = Vec3.fromYawPitch(p.getYRot(), p.getXRot());
		prediction = BombThrow.predict(throwEye, throwDir, cfg, new LiveSolid(mc.level, BlockRules.passThrough(cfg)));
		modelCentre = prediction.blastCentre(cfg.bombs.blastCentre);
		before = WorldCapture.snapshot();
		throwMs = System.currentTimeMillis();
		state = State.THROWN;
		say(prediction.lands()
				? String.format("Throw recorded. Model says it lands on %d %d %d%s. Waiting for the blast...",
						prediction.impactBlock()[0], prediction.impactBlock()[1], prediction.impactBlock()[2], prediction.dropped() ? " (after dropping at max range)" : "")
				: "Throw recorded. Model says it hits nothing. Waiting for the blast...", ChatFormatting.YELLOW);
	}

	/** Called for every block change on the client thread (from WorldCapture). */
	public static void onBlockChanged(BlockPos pos, BlockState state, boolean byPlayer) {
		if ((BombCalibration.state != State.THROWN && BombCalibration.state != State.COLLECTING) || byPlayer || !state.isAir()) return;
		Vec3 near = modelCentre != null ? modelCentre : throwEye;
		double limit = modelCentre != null ? 20 : 70;
		if (near.sub(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)).length() > limit) return;
		if (BombCalibration.state == State.THROWN) {
			BombCalibration.state = State.COLLECTING;
			firstChangeMs = System.currentTimeMillis();
		}
		removed.put(pos.asLong(), new int[]{pos.getX(), pos.getY(), pos.getZ()});
	}

	/** Called by the packet mixin if the server sends a vanilla explosion packet. */
	public static void onExplosion(net.minecraft.world.phys.Vec3 centre, float radius, int blockCount) {
		if (state == State.THROWN || state == State.COLLECTING) {
			explosionPacket = String.format("centre %.2f %.2f %.2f, radius %.2f, %d blocks", centre.x, centre.y, centre.z, radius, blockCount);
		}
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		if (state == State.THROWN && now - throwMs > NO_BLAST_TIMEOUT_MS) {
			say("No blast seen within 12 s. Did the bomb fizzle (too close to a structure?) or land outside the remembered area? Calibration stopped.", ChatFormatting.RED);
			NucleusRouterClient.LOG.info("[Nucleus Router] Bomb calibration: no blast. Prediction {}", describePrediction());
			reset();
		} else if (state == State.COLLECTING && now - firstChangeMs > COLLECT_WINDOW_MS) {
			finish();
		}
	}

	private static void finish() {
		List<int[]> blocks = new ArrayList<>(removed.values());
		long fuseMs = firstChangeMs - throwMs;
		String packet = explosionPacket;
		Vec3 model = modelCentre, eye = throwEye, dir = throwDir;
		BombThrow.Prediction pred = prediction;
		NrConfig cfg = ConfigManager.get();
		CompletableFuture<WorldSnapshot> snap = before;
		reset();
		say("Blast seen (" + blocks.size() + " blocks changed). Measuring...", ChatFormatting.YELLOW);

		snap.thenApplyAsync(s -> {
			BlastAnalysis.Before b = (x, y, z) -> statusOf(s, x, y, z);
			// Only count blocks that were really solid before the throw
			List<int[]> real = new ArrayList<>();
			for (int[] p : blocks) if (b.at(p[0], p[1], p[2]) != BlastAnalysis.Status.EMPTY) real.add(p);
			if (real.isEmpty()) return null;
			BlastAnalysis.Result r = BlastAnalysis.analyze(real, b, model, cfg.bombs.blastRadius);
			Path file = writeReport(r, s, real, pred, model, eye, dir, fuseMs, packet, cfg);
			return new Object[]{r, file};
		}).thenAccept(o -> Minecraft.getInstance().execute(() -> {
			if (o == null) { say("The changed blocks weren't solid before the throw (outside the remembered area?). Nothing to measure.", ChatFormatting.RED); return; }
			lastResultForTest = (BlastAnalysis.Result) o[0];
			lastReportForTest = (Path) o[1];
			report((BlastAnalysis.Result) o[0], (Path) o[1], model, fuseMs, packet, cfg);
		})).exceptionally(NucleusRouterClient::reportAsyncError);
	}

	private static BlastAnalysis.Status statusOf(WorldSnapshot s, int x, int y, int z) {
		VoxelClass c = s.classAt(x, y, z);
		return switch (c) {
			case UNKNOWN -> BlastAnalysis.Status.UNKNOWN;
			case AIR, PASS_THROUGH -> BlastAnalysis.Status.EMPTY;
			case UNBREAKABLE, STRUCTURE -> BlastAnalysis.Status.IMMUNE;
			case HARD_STONE, PLACED -> BlastAnalysis.Status.BREAKABLE;
			case RESPAWNING_SOLID -> s.currentlyAir(x, y, z) ? BlastAnalysis.Status.EMPTY : BlastAnalysis.Status.BREAKABLE;
		};
	}

	private static void report(BlastAnalysis.Result r, Path file, Vec3 model, long fuseMs, String packet, NrConfig cfg) {
		BlastAnalysis.ShapeFit b = r.best();
		say("Bomb calibration result", ChatFormatting.AQUA);
		line("Blocks removed", String.valueOf(r.removedCount()));
		line("Best fitting shape", shapeName(b.metric()) + String.format(", radius %.2f, agreement %.0f%%", b.radius(), b.agreement() * 100));
		line("Measured centre", String.format("%.2f %.2f %.2f (%s)", b.centre().x(), b.centre().y(), b.centre().z(), b.centreSource()));
		if (r.modelCheck() != null) {
			line("Model (centre rule " + cfg.bombs.blastCentre + ", radius " + cfg.bombs.blastRadius + ")",
					String.format("centre %.2f blocks off, agreement %.0f%%", r.modelCentreError(), r.modelCheck().agreement() * 100));
		} else {
			line("Model", "predicted no landing spot");
		}
		line("Survivors inside the blast", r.survivorsInside().isEmpty() ? "none" : r.survivorsInside().size() + " breakable blocks didn't break (see report)");
		line("Throw to blast", fuseMs + " ms (flight + fuse)");
		if (packet != null) line("Server explosion packet", packet);
		line("Full report", file != null ? file.toString() : "could not be saved (see log)");
		say("Blue ring/dot = model, yellow = measured. Shown for 60 s.", ChatFormatting.GRAY);

		shownModelCentre = model;
		shownModelRadius = cfg.bombs.blastRadius;
		shownMeasuredCentre = b.centre();
		shownMeasuredRadius = b.radius();
		shownUntil = System.currentTimeMillis() + SHOW_RESULT_MS;
	}

	private static String shapeName(BlastAnalysis.Metric m) {
		return switch (m) {
			case EUCLIDEAN -> "ball (sphere)";
			case CHEBYSHEV -> "cube";
			case MANHATTAN -> "diamond";
		};
	}

	private static Path writeReport(BlastAnalysis.Result r, WorldSnapshot s, List<int[]> blocks, BombThrow.Prediction pred,
			Vec3 model, Vec3 eye, Vec3 dir, long fuseMs, String packet, NrConfig cfg) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("time", LocalDateTime.now().toString());
		out.put("config", Map.of("throwRange", cfg.bombs.throwRange, "dropsStraightDownAtMaxRange", cfg.bombs.dropsStraightDownAtMaxRange,
				"blastRadius", cfg.bombs.blastRadius, "blastCentre", cfg.bombs.blastCentre.name()));
		out.put("throwEye", eye);
		out.put("throwYawPitch", new double[]{dir.yawDeg(), dir.pitchDeg()});
		out.put("predictedImpactBlock", pred.impactBlock());
		out.put("predictedDropped", pred.dropped());
		out.put("modelCentre", model);
		out.put("throwToBlastMs", fuseMs);
		out.put("explosionPacket", packet);
		out.put("result", r);
		List<Map<String, Object>> survivors = new ArrayList<>();
		for (int[] p : r.survivorsInside()) survivors.add(Map.of("pos", p, "block", String.valueOf(s.originalBlock(p[0], p[1], p[2]))));
		out.put("survivors", survivors);
		Map<String, Integer> removedTypes = new java.util.TreeMap<>();
		for (int[] p : blocks) removedTypes.merge(String.valueOf(s.originalBlock(p[0], p[1], p[2])), 1, Integer::sum);
		out.put("removedBlockTypes", removedTypes);
		out.put("removed", blocks);
		try {
			Path dir2 = FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("calibration");
			Files.createDirectories(dir2);
			Path file = dir2.resolve("bomb-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(out));
			NucleusRouterClient.LOG.info("[Nucleus Router] Bomb calibration: {} blocks, best {} r={} agreement={} centre={}, model error {} -> {}",
					r.removedCount(), r.best().metric(), r.best().radius(), r.best().agreement(), r.best().centre(), r.modelCentreError(), file);
			return file;
		} catch (IOException e) {
			NucleusRouterClient.LOG.error("[Nucleus Router] Could not save bomb report", e);
			return null;
		}
	}

	private static String describePrediction() {
		if (prediction == null || !prediction.lands()) return "no landing";
		int[] b = prediction.impactBlock();
		return b[0] + " " + b[1] + " " + b[2] + (prediction.dropped() ? " (dropped)" : "");
	}

	private static void drawResult(WorldDraw draw) {
		if (System.currentTimeMillis() > shownUntil) return;
		if (shownModelCentre != null) {
			draw.marker(WorldDraw.mc(shownModelCentre), 0.4, WorldDraw.argb(255, 0x3399FF), WorldDraw.argb(120, 0x3399FF), true);
			draw.circle(WorldDraw.mc(shownModelCentre), (float) shownModelRadius, WorldDraw.argb(255, 0x3399FF), true);
		}
		if (shownMeasuredCentre != null) {
			draw.marker(WorldDraw.mc(shownMeasuredCentre), 0.4, WorldDraw.argb(255, 0xFFDD33), WorldDraw.argb(120, 0xFFDD33), true);
			draw.circle(WorldDraw.mc(shownMeasuredCentre), (float) shownMeasuredRadius, WorldDraw.argb(255, 0xFFDD33), true);
		}
	}

	private static void say(String msg, ChatFormatting colour) {
		NucleusRouterClient.showLocal(Component.literal(msg).withStyle(colour));
	}

	private static void line(String key, String value) {
		NucleusRouterClient.showLocal(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
	}

	/** For the gametest: pretend a throw happened from this eye position and direction. */
	public static void simulateThrowForTest(Vec3 eye, Vec3 dir) {
		Minecraft mc = Minecraft.getInstance();
		NrConfig cfg = ConfigManager.get();
		throwEye = eye;
		throwDir = dir;
		prediction = BombThrow.predict(eye, dir, cfg, new LiveSolid(mc.level, BlockRules.passThrough(cfg)));
		modelCentre = prediction.blastCentre(cfg.bombs.blastCentre);
		before = WorldCapture.snapshot();
		throwMs = System.currentTimeMillis();
		state = State.THROWN;
	}
}
