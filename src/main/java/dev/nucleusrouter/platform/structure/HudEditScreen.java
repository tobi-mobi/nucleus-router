package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.platform.config.ConfigManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * {@code /nr hud}: drag the on-screen structure list to where you want it. Esc saves.
 * Right-click puts it back in the default spot.
 */
public final class HudEditScreen extends Screen {
	private final Screen parent;
	private int x, y;
	private int[] drawn = {0, 0, 0, 0};
	private boolean dragging;
	private double grabX, grabY;

	public HudEditScreen(Screen parent) {
		super(Component.literal("Move the structure list"));
		this.parent = parent;
		NrConfig.Hud h = ConfigManager.get().hud;
		this.x = h.x;
		this.y = h.y;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawn = StructureHud.drawList(g, x, y);
		g.fill(drawn[0] - 1, drawn[1] - 1, drawn[0] + drawn[2] + 1, drawn[1], 0xFFFFFF55);
		g.fill(drawn[0] - 1, drawn[1] + drawn[3], drawn[0] + drawn[2] + 1, drawn[1] + drawn[3] + 1, 0xFFFFFF55);
		g.fill(drawn[0] - 1, drawn[1], drawn[0], drawn[1] + drawn[3], 0xFFFFFF55);
		g.fill(drawn[0] + drawn[2], drawn[1], drawn[0] + drawn[2] + 1, drawn[1] + drawn[3], 0xFFFFFF55);
		g.centeredText(font, "Drag the list to move it. Right-click: default spot. Esc: save and close.", width / 2, height - 20, 0xFFFFFFFF);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		boolean inside = e.x() >= drawn[0] && e.x() <= drawn[0] + drawn[2] && e.y() >= drawn[1] && e.y() <= drawn[1] + drawn[3];
		if (e.button() == 1) {
			NrConfig.Hud d = new NrConfig.Hud();
			x = d.x;
			y = d.y;
			return true;
		}
		if (e.button() == 0 && inside) {
			dragging = true;
			grabX = e.x() - drawn[0];
			grabY = e.y() - drawn[1];
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (!dragging) return super.mouseDragged(e, dx, dy);
		x = (int) Math.round(e.x() - grabX);
		y = (int) Math.round(e.y() - grabY);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		dragging = false;
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		NrConfig.Hud h = ConfigManager.get().hud;
		// Store where it was actually drawn (kept on screen)
		h.x = drawn[2] > 0 ? drawn[0] : x;
		h.y = drawn[3] > 0 ? drawn[1] : y;
		ConfigManager.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
