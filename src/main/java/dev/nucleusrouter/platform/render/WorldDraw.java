package dev.nucleusrouter.platform.render;

import dev.nucleusrouter.platform.NucleusRouterClient;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The mod's one place for drawing in the world: lines, box outlines, filled boxes, points and text,
 * optionally visible through walls.
 *
 * How it works on 26.2: it uses Minecraft's own "gizmo" debug drawing. Gizmos go through vanilla
 * render pipelines, so they work on the OpenGL and Vulkan backends and with Iris shaders, with no
 * raw GL calls and no custom shaders. Layers are asked to draw once per frame at the end of
 * Fabric's level extraction; the game shows them on the following frame.
 *
 * If gizmos ever turn out not to be enough, only this class has to change.
 */
public final class WorldDraw {
	/** Something that draws every frame. Must be quick (render budget: under 0.5 ms for the whole mod). */
	@FunctionalInterface
	public interface Layer {
		void draw(WorldDraw draw, LevelExtractionContext context);
	}

	/** A layer and the class that added it (for {@code /nr perf}). */
	private record Named(String name, Layer layer) {}

	private static final List<Named> LAYERS = new CopyOnWriteArrayList<>();
	private static final WorldDraw INSTANCE = new WorldDraw();
	private static volatile double avgFrameMicros;
	private static boolean failedOnce;

	private WorldDraw() {}

	public static void init() {
		LevelExtractionEvents.END_EXTRACTION.register(WorldDraw::onEndExtraction);
	}

	public static void addLayer(Layer layer) { LAYERS.add(new Named(dev.nucleusrouter.platform.debug.Perf.caller(), layer)); }

	/** Average time spent drawing per frame (exponential average), for /nr debug ray stats. */
	public static double avgFrameMicros() { return avgFrameMicros; }

	private static void onEndExtraction(LevelExtractionContext ctx) {
		if (LAYERS.isEmpty()) return;
		long start = System.nanoTime();
		for (Named named : LAYERS) {
			long t = System.nanoTime();
			try {
				named.layer().draw(INSTANCE, ctx);
				dev.nucleusrouter.platform.debug.Perf.record(dev.nucleusrouter.platform.debug.Perf.Kind.OVERLAY, named.name(), t);
			} catch (RuntimeException e) {
				if (!failedOnce) {
					failedOnce = true;
					NucleusRouterClient.LOG.error("[Nucleus Router] A world overlay failed to draw (shown once)", e);
				}
			}
		}
		double us = (System.nanoTime() - start) / 1000.0;
		avgFrameMicros = avgFrameMicros * 0.95 + us * 0.05;
	}

	// ------------------------------------------------------------------ drawing

	/** Colour helper: alpha 0..255 plus 0xRRGGBB. */
	public static int argb(int alpha, int rgb) {
		return (alpha & 0xFF) << 24 | (rgb & 0xFFFFFF);
	}

	private static GizmoProperties through(GizmoProperties p, boolean throughWalls) {
		return throughWalls ? p.setAlwaysOnTop() : p;
	}

	public void line(Vec3 from, Vec3 to, int argb, float width, boolean throughWalls) {
		through(Gizmos.line(from, to, argb, width), throughWalls);
	}

	/** Box outline. */
	public void outline(AABB box, int argb, float width, boolean throughWalls) {
		through(Gizmos.cuboid(box, GizmoStyle.stroke(argb, width)), throughWalls);
	}

	/** Translucent filled box. */
	public void filled(AABB box, int argb, boolean throughWalls) {
		through(Gizmos.cuboid(box, GizmoStyle.fill(argb)), throughWalls);
	}

	/** Outline plus translucent fill. */
	public void box(AABB box, int strokeArgb, float width, int fillArgb, boolean throughWalls) {
		through(Gizmos.cuboid(box, GizmoStyle.strokeAndFill(strokeArgb, width, fillArgb)), throughWalls);
	}

	/** One block, slightly enlarged so it doesn't flicker against the block's own faces. */
	public void block(int x, int y, int z, int strokeArgb, float width, int fillArgb, boolean throughWalls) {
		double e = 0.002;
		box(new AABB(x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e), strokeArgb, width, fillArgb, throughWalls);
	}

	/** Small cube centred on a point (for markers like an aim dot or landing spot). */
	public void marker(Vec3 centre, double size, int strokeArgb, int fillArgb, boolean throughWalls) {
		double h = size / 2;
		box(new AABB(centre.x - h, centre.y - h, centre.z - h, centre.x + h, centre.y + h, centre.z + h), strokeArgb, 2f, fillArgb, throughWalls);
	}

	/** Horizontal circle (e.g. the outline of a blast sphere at its centre height). */
	public void circle(Vec3 centre, float radius, int argb, boolean throughWalls) {
		through(Gizmos.circle(centre, radius, GizmoStyle.stroke(argb, 3f)), throughWalls);
	}

	/** Text that always faces the camera. */
	public void text(String text, Vec3 pos, int argb, float scale, boolean throughWalls) {
		through(Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(argb).withScale(scale)), throughWalls);
	}

	/** Convenience to turn a core vector into a Minecraft one. */
	public static Vec3 mc(dev.nucleusrouter.core.geom.Vec3 v) {
		return new Vec3(v.x(), v.y(), v.z());
	}

	/** Runs {@code body} once per frame while {@code enabled} returns true. */
	public static void addLayerWhile(java.util.function.BooleanSupplier enabled, Consumer<WorldDraw> body) {
		addLayer((draw, ctx) -> { if (enabled.getAsBoolean()) body.accept(draw); });
	}
}
