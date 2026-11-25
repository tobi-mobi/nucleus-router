package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.SetupGate;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Roadmap 2.6 (SPEC §4): a small on-screen list of the run's structures (found, approximate, missing)
 * and the "all structures found" gate for setup planning. When the gate opens you're told once in chat;
 * by default the list then hides after a few seconds.
 */
public final class StructureHud {
	private static final long HIDE_AFTER_COMPLETE_MS = 10_000;
	private static SetupGate.Status status = SetupGate.check(new StructureMap());
	private static String lobby;
	private static boolean announcedReady;
	private static long completeSinceMs = -1;
	private static int ticks;
	/** Gametests only: draw even outside the Hollows. */
	public static volatile boolean forceShowForTest;

	private StructureHud() {}

	public static void init() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "structure_list"), StructureHud::draw);
		ClientTickEvents.END_CLIENT_TICK.register(c -> { if (++ticks % 10 == 0) update(); });
	}

	/** The gate: true once every structure has an exact position. Setup planning (milestone 4) checks this. */
	public static boolean setupReady() { return status.ready(); }
	public static SetupGate.Status status() { return status; }

	private static void update() {
		status = SetupGate.check(StructureTracker.map());
		String now = LocationTracker.state().serverId();
		if (now != null && !now.equals(lobby)) { lobby = now; announcedReady = false; completeSinceMs = -1; }
		if (status.ready()) {
			if (completeSinceMs < 0) completeSinceMs = System.currentTimeMillis();
			if (!announcedReady && LocationTracker.state().inHollows()) {
				announcedReady = true;
				if (ConfigManager.get().general.chatNotices)
					NucleusRouterClient.showLocal(Component.literal("All " + status.total() + " structures found: this lobby is ready for setup planning.").withStyle(ChatFormatting.GREEN));
			}
		} else {
			completeSinceMs = -1;
		}
	}

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		NrConfig.Hud cfg = ConfigManager.get().hud;
		Minecraft mc = Minecraft.getInstance();
		if (!forceShowForTest && (!cfg.showStructureList || !LocationTracker.state().inHollows() || mc.player == null)) return;
		if (mc.getDebugOverlay().showDebugScreen()) return; // F1 (hidden GUI) already skips HUD elements
		if (!forceShowForTest && cfg.hideWhenAllFound && status.ready() && completeSinceMs > 0
				&& System.currentTimeMillis() - completeSinceMs > HIDE_AFTER_COMPLETE_MS) return;

		if (mc.gui.screen() instanceof HudEditScreen) return; // the editor draws its own copy
		drawList(g, cfg.x, cfg.y);
	}

	/** Draws the list with its top-left corner at (x, y), kept on screen. Returns {x, y, width, height} as drawn. */
	public static int[] drawList(GuiGraphicsExtractor g, int px, int py) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		StructureMap map = StructureTracker.map();
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Structures " + status.found().size() + "/" + status.total()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		Vec3 me = mc.player != null ? new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()) : null;
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			String dist = l != null && me != null ? "  " + Math.round(l.pos().sub(me).length()) + "m" : "";
			if (status.found().contains(s)) lines.add(Component.literal("+ " + s.displayName + dist).withStyle(ChatFormatting.GREEN));
			else if (status.approximate().contains(s)) lines.add(Component.literal("~ " + s.displayName + dist + " (go closer)").withStyle(ChatFormatting.YELLOW));
			else lines.add(Component.literal("- " + s.displayName).withStyle(ChatFormatting.RED));
		}
		if (status.ready()) lines.add(Component.literal("Ready for setup").withStyle(ChatFormatting.GREEN));

		int w = 0;
		for (Component c : lines) w = Math.max(w, font.width(c));
		int h = lines.size() * (font.lineHeight + 1) + 3;
		int x = Math.max(0, Math.min(px, g.guiWidth() - w - 6)), y = Math.max(0, Math.min(py, g.guiHeight() - h));
		g.fill(x, y, x + w + 6, y + h, 0x90000000);
		int ty = y + 3;
		for (Component c : lines) {
			g.text(font, c, x + 3, ty, 0xFFFFFFFF, true);
			ty += font.lineHeight + 1;
		}
		return new int[]{x, y, w + 6, h};
	}
}
