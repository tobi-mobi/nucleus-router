package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.platform.config.ConfigManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * {@code /nr hud}: drag the on-screen panels (structure list, run panel) to where you want them. Esc saves.
 * Right-click on a panel puts it back in its default spot.
 */
public final class HudEditScreen extends Screen {
	private static final int LIST = 0, RUN = 1;
	private final Screen parent;
	private final int[][] pos = new int[2][2];
	private final int[][] drawn = new int[2][4];
	private int dragging = -1;
	/** The run panel is only shown beside the list here; saved back in place unless it was moved. */
	private boolean shifted;
	private double grabX, grabY;

	public HudEditScreen(Screen parent) {
		super(Component.literal("Move the on-screen panels"));
		this.parent = parent;
		NrConfig c = ConfigManager.get();
		pos[LIST] = new int[]{c.hud.x, c.hud.y};
		pos[RUN] = new int[]{c.run.hudX, c.run.hudY};
		// Both default to the same corner (the structure list hides while running): show them side by side here
		if (pos[RUN][0] == pos[LIST][0] && pos[RUN][1] == pos[LIST][1]) { pos[RUN][0] += 150; shifted = true; }
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawn[LIST] = StructureHud.drawList(g, pos[LIST][0], pos[LIST][1]);
		drawn[RUN] = RunHud.drawPanel(g, pos[RUN][0], pos[RUN][1]);
		for (int[] d : drawn) frame(g, d);
		g.centeredText(font, "Drag a panel to move it. Right-click a panel: default spot. Esc: save and close.", width / 2, height - 20, 0xFFFFFFFF);
	}

	private static void frame(GuiGraphicsExtractor g, int[] d) {
		g.fill(d[0] - 1, d[1] - 1, d[0] + d[2] + 1, d[1], 0xFFFFFF55);
		g.fill(d[0] - 1, d[1] + d[3], d[0] + d[2] + 1, d[1] + d[3] + 1, 0xFFFFFF55);
		g.fill(d[0] - 1, d[1], d[0], d[1] + d[3], 0xFFFFFF55);
		g.fill(d[0] + d[2], d[1], d[0] + d[2] + 1, d[1] + d[3], 0xFFFFFF55);
	}

	private int panelAt(double x, double y) {
		for (int i = drawn.length - 1; i >= 0; i--) {
			int[] d = drawn[i];
			if (x >= d[0] && x <= d[0] + d[2] && y >= d[1] && y <= d[1] + d[3]) return i;
		}
		return -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		int panel = panelAt(e.x(), e.y());
		if (e.button() == 1 && panel >= 0) {
			if (panel == LIST) { NrConfig.Hud d = new NrConfig.Hud(); pos[LIST] = new int[]{d.x, d.y}; }
			else { NrConfig.Run d = new NrConfig.Run(); pos[RUN] = new int[]{d.hudX, d.hudY}; shifted = false; }
			return true;
		}
		if (e.button() == 0 && panel >= 0) {
			dragging = panel;
			grabX = e.x() - drawn[panel][0];
			grabY = e.y() - drawn[panel][1];
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (dragging < 0) return super.mouseDragged(e, dx, dy);
		if (dragging == RUN) shifted = false;
		pos[dragging][0] = (int) Math.round(e.x() - grabX);
		pos[dragging][1] = (int) Math.round(e.y() - grabY);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = -1;
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		NrConfig c = ConfigManager.get();
		// Store where they were actually drawn (kept on screen)
		c.hud.x = drawn[LIST][2] > 0 ? drawn[LIST][0] : pos[LIST][0];
		c.hud.y = drawn[LIST][3] > 0 ? drawn[LIST][1] : pos[LIST][1];
		if (!shifted) {
			c.run.hudX = drawn[RUN][2] > 0 ? drawn[RUN][0] : pos[RUN][0];
			c.run.hudY = drawn[RUN][3] > 0 ? drawn[RUN][1] : pos[RUN][1];
		}
		ConfigManager.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
