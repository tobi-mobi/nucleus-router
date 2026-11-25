package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.Coverage;
import dev.nucleusrouter.game.RouteOrder;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /nr explored map}: a top-down map of the Crystal Hollows (north up). Shows explored ground, found
 * structures, the planned route (numbered stops, straight lines; dashed via the nucleus) and you, live.
 * Hovering shows what is there. Read-only.
 */
public final class ExploreMapScreen extends Screen {
	private static final int VISITED = 0xFF2E7D32, LOADED = 0xFF3A3A3A, UNKNOWN = 0xFF141414;
	private static final int EXACT = 0xFFE040FB, APPROX = 0xFFFFEB3B, ROUTE = 0xFF4FC3F7, ME = 0xFFFFFFFF;

	private int ox, oy, size;

	public ExploreMapScreen() {
		super(Component.literal("Crystal Hollows map"));
	}

	@Override
	public boolean isPauseScreen() { return false; }

	private Coverage cov() { return ExplorationTracker.coverage(); }

	private double px(double x) { Coverage c = cov(); return ox + (x - c.minX) / (double) (c.maxX - c.minX + 1) * size; }
	private double pz(double z) { Coverage c = cov(); return oy + (z - c.minZ) / (double) (c.maxZ - c.minZ + 1) * size; }
	private double wx(double mx) { Coverage c = cov(); return c.minX + (mx - ox) / size * (c.maxX - c.minX + 1); }
	private double wz(double my) { Coverage c = cov(); return c.minZ + (my - oy) / size * (c.maxZ - c.minZ + 1); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		Coverage c = cov();
		size = Math.max(64, Math.min(width - 20, height - 66));
		ox = (width - size) / 2;
		oy = 42;
		g.centeredText(font, String.format("Crystal Hollows (north is up), %.0f%% explored", c.visitedFraction() * 100), width / 2, 6, 0xFFFFFFFF);
		g.centeredText(font, "green explored  grey loaded  purple structure", width / 2, 18, 0xFFAAAAAA);
		g.centeredText(font, "yellow approximate  blue route  white you", width / 2, 28, 0xFFAAAAAA);

		// Explored cells, drawn as runs of equal colour per row
		g.fill(ox - 1, oy - 1, ox + size + 1, oy + size + 1, 0xFF808080);
		for (int cz = 0; cz < c.cellsZ; cz++) {
			int y0 = oy + cz * size / c.cellsZ, y1 = oy + (cz + 1) * size / c.cellsZ;
			if (y1 <= y0) y1 = y0 + 1;
			int runStart = 0, runColour = colour(c, 0, cz);
			for (int cx = 1; cx <= c.cellsX; cx++) {
				int col = cx < c.cellsX ? colour(c, cx, cz) : 0;
				if (cx == c.cellsX || col != runColour) {
					g.fill(ox + runStart * size / c.cellsX, y0, ox + cx * size / c.cellsX, y1, runColour);
					runStart = cx;
					runColour = col;
				}
			}
		}

		// Route: straight lines between stops in order (dashed when it goes via /warp nucleus)
		Map<String, Vec3> stopAt = new HashMap<>();
		List<Object[]> stopLabels = new ArrayList<>();
		RouteTracker.Route route = RouteTracker.current();
		if (route != null && java.util.Objects.equals(route.lobby(), StructureTracker.lobby()) && route.plan().hasRoute()) {
			for (StopPlan.Stop s : route.stops()) stopAt.put(s.id(), s.standFeet() != null ? s.standFeet() : s.target());
			Vec3 nucleus = stopAt.get("nucleus");
			Vec3 at = nucleus;
			int n = 1;
			for (RouteOrder.Step step : route.plan().steps()) {
				Vec3 to = stopAt.get(step.toId());
				if (to == null) continue;
				Vec3 from = step.viaWarpNucleus() ? nucleus : (step.fromId().equals("temple.exit") ? stopAt.getOrDefault("temple.exit", at) : at);
				line(g, from, to, ROUTE, step.viaWarpNucleus());
				if (!step.toId().equals("nucleus")) stopLabels.add(new Object[]{to, String.valueOf(n++)});
				at = to;
			}
		}

		// Structures
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			if (l == null || l.source() == StructureMap.Source.AREA) continue;
			if (!inside(px(l.pos().x()), pz(l.pos().z()))) continue;
			int x = (int) px(l.pos().x()), z = (int) pz(l.pos().z());
			int col = StructureMap.exact(l) ? EXACT : APPROX;
			g.fill(x - 3, z - 3, x + 4, z + 4, 0xFF000000);
			g.fill(x - 2, z - 2, x + 3, z + 3, col);
			g.text(font, s.displayName, x + 5, z - 4, col, true);
		}
		for (Object[] sl : stopLabels) {
			Vec3 p = (Vec3) sl[0];
			g.text(font, (String) sl[1], (int) px(p.x()) - 2, (int) pz(p.z()) + 4, ROUTE, true);
		}

		// You, live: a square and a short line for where you're facing
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && inside(px(mc.player.getX()), pz(mc.player.getZ()))) {
			int x = (int) px(mc.player.getX()), z = (int) pz(mc.player.getZ());
			double yaw = Math.toRadians(mc.player.getYRot());
			double dx = -Math.sin(yaw), dz = Math.cos(yaw);
			for (int i = 3; i <= 9; i++) g.fill(x + (int) Math.round(dx * i), z + (int) Math.round(dz * i), x + (int) Math.round(dx * i) + 1, z + (int) Math.round(dz * i) + 1, ME);
			g.fill(x - 3, z - 3, x + 4, z + 4, 0xFF000000);
			g.fill(x - 2, z - 2, x + 3, z + 3, ME);
		}

		// Hover: what's here
		if (mouseX >= ox && mouseX < ox + size && mouseY >= oy && mouseY < oy + size) tooltip(g, mouseX, mouseY, stopLabels);
		g.centeredText(font, "Hover for details. Esc to close.", width / 2, oy + size + 6, 0xFF888888);
	}

	private boolean inside(double x, double z) {
		return x >= ox && x < ox + size && z >= oy && z < oy + size;
	}

	private static int colour(Coverage c, int cx, int cz) {
		return c.isVisited(cx, cz) ? VISITED : c.isLoaded(cx, cz) ? LOADED : UNKNOWN;
	}

	/** A line on the map, drawn as small squares (dashed if asked). */
	private void line(GuiGraphicsExtractor g, Vec3 a, Vec3 b, int col, boolean dashed) {
		double x0 = px(a.x()), z0 = pz(a.z()), x1 = px(b.x()), z1 = pz(b.z());
		double len = Math.hypot(x1 - x0, z1 - z0);
		int steps = Math.max(1, (int) len);
		for (int i = 0; i <= steps; i++) {
			if (dashed && (i / 3) % 2 == 1) continue;
			int x = (int) Math.round(x0 + (x1 - x0) * i / steps), z = (int) Math.round(z0 + (z1 - z0) * i / steps);
			g.fill(x, z, x + 1, z + 1, col);
		}
	}

	private void tooltip(GuiGraphicsExtractor g, int mx, int my, List<Object[]> stopLabels) {
		Coverage c = cov();
		double x = wx(mx + 0.5), z = wz(my + 0.5);
		List<String> lines = new ArrayList<>();
		lines.add(Math.round(x) + ", " + Math.round(z));
		int cx = (int) Math.floor((x - c.minX) / Coverage.CELL), cz = (int) Math.floor((z - c.minZ) / Coverage.CELL);
		lines.add(c.isVisited(cx, cz) ? "explored" : c.isLoaded(cx, cz) ? "loaded, not explored" : "not seen yet");
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			if (l == null || l.source() == StructureMap.Source.AREA) continue;
			if (Math.hypot(px(l.pos().x()) - mx, pz(l.pos().z()) - my) <= 6)
				lines.add(s.displayName + " (" + l.detail() + ")  " + Math.round(l.pos().x()) + " " + Math.round(l.pos().y()) + " " + Math.round(l.pos().z()));
		}
		for (Object[] sl : stopLabels) {
			Vec3 p = (Vec3) sl[0];
			if (Math.hypot(px(p.x()) - mx, pz(p.z()) - my) <= 6) lines.add("route stop " + sl[1]);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && Math.hypot(px(mc.player.getX()) - mx, pz(mc.player.getZ()) - my) <= 5) lines.add("you");
		int w = 0;
		for (String l : lines) w = Math.max(w, font.width(l));
		int tx = Math.min(mx + 10, width - w - 8), ty = Math.min(my + 10, height - lines.size() * 10 - 8);
		g.fill(tx - 3, ty - 3, tx + w + 3, ty + lines.size() * 10 + 1, 0xE0101010);
		for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), tx, ty + i * 10, i == 0 ? 0xFFFFFFFF : 0xFFCCCCCC, false);
	}
}
