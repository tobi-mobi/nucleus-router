package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.DenTimer;
import dev.nucleusrouter.game.RunClock;
import dev.nucleusrouter.game.RunPath;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Run HUD (SPEC §5, roadmap 3.6): an on-screen panel while run mode is on (run timer, structure you're at
 * and what to do there, the next step, warp count, per-crystal splits) and a big "Leave den" countdown
 * after taking the Amber crystal.
 * <p>
 * Render-only: it only <em>reads</em> chat lines, your own right-clicks (always passed on unchanged) and the
 * server's teleports. It never sends or cancels anything.
 */
public final class RunHud {
	/** A server teleport this soon after an Aspect of the Void click counts as that click's warp. */
	private static final long CLICK_TO_TELEPORT_MS = 1000;

	private static final DenTimer den = new DenTimer();
	private static long lastClickMs = -1;
	private static boolean lastClickSneaking;
	/** Gametests only: draw even outside the Hollows. */
	public static volatile boolean forceShowForTest;

	private RunHud() {}

	public static void init() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "run_panel"), RunHud::draw);
		ClientTickEvents.END_CLIENT_TICK.register(RunHud::tick);
		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChat(msg.getString()); });
		UseItemCallback.EVENT.register((player, level, hand) -> { onUse(player); return InteractionResult.PASS; });
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> { onUse(player); return InteractionResult.PASS; });
	}

	public static DenTimer den() { return den; }

	// ------------------------------------------------------------------ inputs (read-only)

	private static void onUse(Player player) {
		Minecraft mc = Minecraft.getInstance();
		if (player != mc.player) return;
		String name = player.getMainHandItem().getHoverName().getString();
		if (!name.contains("Aspect of the Void") && !name.contains("Aspect of the End")) return;
		lastClickMs = System.currentTimeMillis();
		lastClickSneaking = player.isShiftKeyDown();
	}

	/** Called by {@code ClientPacketListenerMixin} after the server moved the player. */
	public static void onServerTeleport() {
		long now = System.currentTimeMillis();
		RunClock clock = RunTracker.clock();
		if (clock == null || lastClickMs < 0 || now - lastClickMs > CLICK_TO_TELEPORT_MS) return;
		if (lastClickSneaking) clock.onEtherwarp(now); else clock.onTeleport(now);
		lastClickMs = -1; // one teleport per click
	}

	private static void onChat(String text) {
		NrConfig cfg = ConfigManager.get();
		if (!cfg.run.denCountdown || !LocationTracker.state().inHollows()) return;
		if (DenTimer.isStartMessage(text, cfg.run.denTimerStartMessages)) den.start(System.currentTimeMillis());
	}

	/** {@code /nr run den}: start the countdown by hand (to try it out). */
	public static void startDenForTest() { den.start(System.currentTimeMillis()); }

	private static void tick(Minecraft mc) {
		if (den.state() == DenTimer.State.IDLE || mc.player == null) return;
		NrConfig cfg = ConfigManager.get();
		DenTimer.State before = den.state();
		long now = System.currentTimeMillis();
		den.update(now, isOutOfDen(mc, cfg), cfg.stops.queenDenExitLimitSeconds);
		if (before == DenTimer.State.RUNNING && den.state() != DenTimer.State.RUNNING && cfg.general.chatNotices) {
			String msg = den.state() == DenTimer.State.OUT
					? String.format("Left the Queen's den in %.1f s.", den.resultSeconds())
					: String.format("Den countdown ran out (%.1f s) before the mod saw you leave.", cfg.stops.queenDenExitLimitSeconds);
			NucleusRouterClient.showLocal(Component.literal(msg).withStyle(den.state() == DenTimer.State.OUT ? ChatFormatting.GREEN : ChatFormatting.RED));
		}
	}

	private static boolean isOutOfDen(Minecraft mc, NrConfig cfg) {
		Vec3 feet = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		int[][] box = StructureTracker.map().crystalBox(Structure.GOBLIN_QUEENS_DEN);
		Vec3 crystal = box == null ? null
				: new Vec3((box[0][0] + box[1][0] + 1) / 2.0, (box[0][1] + box[1][1] + 1) / 2.0, (box[0][2] + box[1][2] + 1) / 2.0);
		return DenTimer.isOut(feet, crystal, cfg.stops.denExitHeightAboveCrystal, StopPlan.nucleus(cfg), LocationTracker.state().scoreboardArea());
	}

	// ------------------------------------------------------------------ drawing

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getDebugOverlay().showDebugScreen()) return;
		if (!forceShowForTest && !LocationTracker.state().inHollows()) return;
		NrConfig cfg = ConfigManager.get();
		drawDen(g, cfg);
		if (!cfg.run.showHud || !RunTracker.active() || mc.gui.screen() instanceof HudEditScreen) return;
		drawPanel(g, cfg.run.hudX, cfg.run.hudY);
	}

	private static void drawDen(GuiGraphicsExtractor g, NrConfig cfg) {
		if (!cfg.run.denCountdown || den.state() == DenTimer.State.IDLE) return;
		long now = System.currentTimeMillis();
		String text;
		int color;
		switch (den.state()) {
			case RUNNING -> {
				double left = den.remaining(now, cfg.stops.queenDenExitLimitSeconds);
				text = String.format("LEAVE DEN  %.1f", left);
				color = left > 1.5 ? 0xFFFFEB3B : 0xFFFF5252;
			}
			case OUT -> { text = String.format("Out in %.1f s", den.resultSeconds()); color = 0xFF66FF66; }
			default -> { text = "Den time is up"; color = 0xFFFF5252; }
		}
		Font font = Minecraft.getInstance().font;
		float scale = 2.5f;
		g.pose().pushMatrix();
		g.pose().translate(g.guiWidth() / 2f, g.guiHeight() / 2f - 40);
		g.pose().scale(scale, scale);
		g.centeredText(font, Component.literal(text).withStyle(ChatFormatting.BOLD), 0, 0, color);
		g.pose().popMatrix();
	}

	/** The panel's lines for the current run (also used by the /nr hud editor). */
	public static List<Component> lines() {
		List<Component> out = new ArrayList<>();
		RunPath path = RunTracker.path();
		RunClock clock = RunTracker.clock();
		Minecraft mc = Minecraft.getInstance();
		NrConfig cfg = ConfigManager.get();
		if (path == null || clock == null || mc.player == null) {
			out.add(Component.literal("Run 0:00.0").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
			out.add(Component.literal("(run panel: shows while /nr run is on)").withStyle(ChatFormatting.GRAY));
			return out;
		}
		long now = System.currentTimeMillis();
		Vec3 feet = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());

		String timer = "Run " + RunClock.format(clock.elapsedMs(now));
		if (!clock.started()) timer += "  (starts when you move)";
		else if (cfg.run.showPlanDelta) timer += "  plan " + RunClock.format(Math.round(path.plannedSeconds() * 1000));
		out.add(Component.literal(timer).withStyle(clock.finished() ? ChatFormatting.GREEN : ChatFormatting.AQUA, ChatFormatting.BOLD));

		if (den.state() == DenTimer.State.RUNNING)
			out.add(Component.literal(String.format("Leave den: %.1f s", den.remaining(now, cfg.stops.queenDenExitLimitSeconds))).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

		RunClock.Status st = RunClock.status(path, RunTracker.index(), feet, cfg.run.arriveRadius);
		if (st.atStop() != null) {
			out.add(Component.literal("At: " + st.atStop()).withStyle(ChatFormatting.GREEN));
			out.add(Component.literal("Do: " + st.doNow()).withStyle(ChatFormatting.WHITE));
			out.add(Component.literal("Then: " + st.next()).withStyle(ChatFormatting.GRAY));
		} else if (!st.finished()) {
			out.add(Component.literal("Next: " + st.next()).withStyle(ChatFormatting.YELLOW));
			if (st.heading() != null)
				out.add(Component.literal("To: " + st.heading() + "  " + Math.round(st.headingDistance()) + "m").withStyle(ChatFormatting.GRAY));
		} else {
			out.add(Component.literal("Route finished").withStyle(ChatFormatting.GREEN));
		}

		out.add(Component.literal("Etherwarps " + clock.etherwarps() + " / " + clock.plannedEtherwarps() + " planned"
				+ (clock.teleports() > 0 || clock.plannedTeleportClicks() > 0 ? "   teleports " + clock.teleports() : "")).withStyle(ChatFormatting.GRAY));

		for (RunClock.Split s : clock.splits()) {
			String t;
			ChatFormatting colour;
			if (s.done()) {
				t = RunClock.format(s.leaveMs());
				if (cfg.run.showPlanDelta) t += "  " + RunClock.formatDelta(s.deltaSeconds());
				colour = !cfg.run.showPlanDelta ? ChatFormatting.WHITE : s.deltaSeconds() <= 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
			} else if (s.arriveMs() >= 0) {
				t = "here since " + RunClock.format(s.arriveMs());
				colour = ChatFormatting.YELLOW;
			} else {
				t = cfg.run.showPlanDelta ? "plan " + RunClock.format(Math.round(s.plannedLeave() * 1000)) : "-";
				colour = ChatFormatting.DARK_GRAY;
			}
			out.add(Component.literal(s.name() + ": " + t).withStyle(colour));
		}
		return out;
	}

	/** Draws the panel with its top-left corner at (x, y), kept on screen. Returns {x, y, width, height}. */
	public static int[] drawPanel(GuiGraphicsExtractor g, int px, int py) {
		Font font = Minecraft.getInstance().font;
		List<Component> lines = lines();
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

	/** Chat summary when the route is finished. */
	public static void announceFinish(RunClock clock) {
		if (!ConfigManager.get().run.splitsInChat || clock == null || !clock.started()) return;
		StringBuilder sb = new StringBuilder("Run " + RunClock.format(clock.elapsedMs(System.currentTimeMillis())) + ":");
		for (RunClock.Split s : clock.splits())
			if (s.done()) sb.append("  ").append(s.name()).append(' ').append(RunClock.format(s.leaveMs()));
		sb.append("  |  ").append(clock.etherwarps()).append(" etherwarps");
		if (clock.teleports() > 0) sb.append(", ").append(clock.teleports()).append(" teleports");
		NucleusRouterClient.showLocal(Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA));
	}
}
