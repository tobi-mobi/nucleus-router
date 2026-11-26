package dev.nucleusrouter.platform.render;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.debug.Perf;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * The mod's one place for on-screen (HUD) drawing: filled boxes and text. Like {@link WorldDraw} for the world,
 * this keeps Minecraft's GUI drawing classes (which change between versions, e.g. {@code GuiGraphicsExtractor} on
 * 26.x) in a single file, so a port only has to change this class (roadmap 5.4, see PORTING.md).
 */
public final class Hud {
	private final GuiGraphicsExtractor g;

	private Hud(GuiGraphicsExtractor g) { this.g = g; }

	/** Wraps the game's drawing context (for the mod's own screens, which get one from the game). */
	public static Hud of(GuiGraphicsExtractor g) { return new Hud(g); }

	/** Adds a HUD panel drawn every frame after the vanilla HUD, timed for {@code /nr perf} under {@code name}. */
	public static void add(String id, String name, Consumer<Hud> body) {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, id), Perf.hud(name, (g, delta) -> body.accept(new Hud(g))));
	}

	private static Font font() { return Minecraft.getInstance().font; }

	public int width() { return g.guiWidth(); }

	public int height() { return g.guiHeight(); }

	public static int lineHeight() { return font().lineHeight; }

	public static int textWidth(String s) { return font().width(s); }

	public static int textWidth(Component c) { return font().width(c); }

	/** Filled rectangle, colour 0xAARRGGBB. */
	public void fill(int x0, int y0, int x1, int y1, int argb) { g.fill(x0, y0, x1, y1, argb); }

	/** Text with a shadow, top-left at (x, y). */
	public void text(String s, int x, int y, int argb) { g.text(font(), s, x, y, argb, true); }

	public void text(Component c, int x, int y, int argb) { g.text(font(), c, x, y, argb, true); }

	/** Text centred on (cx, cy), scaled up {@code scale} times (big countdowns). */
	public void bigCentered(Component c, float cx, float cy, float scale, int argb) {
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		g.centeredText(font(), c, 0, 0, argb);
		g.pose().popMatrix();
	}
}
