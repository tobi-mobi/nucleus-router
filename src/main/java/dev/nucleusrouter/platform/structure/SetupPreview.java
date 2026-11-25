package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.LegDesigner;
import dev.nucleusrouter.core.setup.PlacementPlanner;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.game.LobbySetup;
import dev.nucleusrouter.game.RouteOrder;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Setup previews (before the guided setup of 4.6). Shows only; nothing is done for you.
 * <ul>
 *   <li>{@code /nr design <structure>} (roadmap 4.2-4.4): one leg, from where you stand to a structure.</li>
 *   <li>{@code /nr setup} (roadmap 4.5): the whole lobby. Every leg is designed in parallel within
 *       {@code setup.lobbyTimeLimitMs} (5 s), the run order picks for each leg the faster of the terrain route
 *       ({@code /nr route}, if planned) and the designed tunnel, then bombs and placements for the legs used.
 *       Progress shows at the top of the screen.</li>
 * </ul>
 * In the world: lanes (white line, targets green, orange = to place), bombs (B numbers, cyan: stand spot, line to the
 * blast centre, blast radius), blocks to mine (red), placements (P numbers, yellow: stand spot and face to click;
 * pillars as a yellow column). Numbers follow the setup order: all bombs first, then all placements.
 */
public final class SetupPreview {
	/** Runs one design or the whole-lobby plan (which waits for its own workers below). */
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "nucleusrouter-design");
		t.setDaemon(true);
		return t;
	});
	private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
	/** The whole-lobby plan's legs, in parallel. */
	private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(WORKERS, r -> {
		Thread t = new Thread(r, "nucleusrouter-setup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/** One leg as shown. */
	private record LegView(LegDesigner.Design design, BombPlanner.Plan bombs, Set<Long> placeTargets, PlacementPlanner.Plan place) {}

	private static volatile List<LegView> shown;
	private static volatile boolean running;
	private static volatile String phase = "";
	private static volatile int done, total;
	/** The last whole-lobby plan (4.6 will guide through it). */
	private static volatile LobbySetup.Result lobbyPlan;

	private SetupPreview() {}

	public static void init() {
		WorldDraw.addLayerWhile(() -> shown != null && LocationTracker.state().inHollows(), SetupPreview::draw);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "setup_progress"), SetupPreview::drawProgress);
	}

	public static void clear() { shown = null; }
	public static LobbySetup.Result lobbyPlan() { return lobbyPlan; }

	/** What the planners need, copied on the client thread. */
	private record Inputs(NrConfig cfg, StructureMap map, dev.nucleusrouter.game.KeeperLayout layout, PlanWorld.Columns explored,
			List<int[]> zones, int ping) {}

	private static Inputs inputs() {
		NrConfig cfg = ConfigManager.get();
		StructureMap map = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(StructureTracker.map()), StructureMap.class);
		var visited = ExplorationTracker.coverage().visitedCopy();
		PlanWorld.Columns explored = cfg.plan.onlyExploredTerrain ? visited::test : (x, z) -> true;
		List<int[]> zones = new ArrayList<>();
		for (Structure s : Structure.values()) {
			int[] b = map.zoneBox(s, StructureShapes.zone(s));
			if (b != null) zones.add(b);
		}
		return new Inputs(cfg, map, KeeperLayouts.layout(), explored, zones, LocationTracker.state().pingMs());
	}

	private static List<StopPlan.Stop> stops(WorldSnapshot snap, Inputs in) {
		return StopPlan.build(in.map(), in.layout(), PlanWorld.of(snap, PlanWorld.patterns(in.cfg().plan.unstableTargetBlocks)), in.cfg());
	}

	// ------------------------------------------------------------------ one leg: /nr design <structure>

	/** Starts designing one leg; the result arrives in chat. Returns a message for the player. */
	public static String start(Structure target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !LocationTracker.state().inHollows()) return "Only in the Crystal Hollows.";
		if (running) return "Already planning, wait a moment.";
		Inputs in = inputs();
		NrConfig cfg = in.cfg();
		int[] start = {(int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY() - 0.01) - 1, (int) Math.floor(mc.player.getZ())};
		running = true;
		progress("designing", 0, 1);
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			StopPlan.Stop stop = null;
			for (StopPlan.Stop s : stops(snap, in))
				if (s.structure() == target && s.kind() == StopPlan.Kind.STRUCTURE) { stop = s; break; }
			if (stop == null || stop.standFeet() == null)
				throw new IllegalStateException(target.displayName + " has no arrival spot yet (find it first: /nr stops)");
			Vec3 f = stop.standFeet();
			int[] goal = {(int) Math.floor(f.x()), (int) Math.floor(f.y()) - 1, (int) Math.floor(f.z())};
			SetupWorld w = SetupWorld.of(snap, in.zones(), in.explored());
			LegDesigner designer = new LegDesigner(w, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, in.ping()), cfg);
			LegDesigner.Result r = designer.design(start, goal);
			LegDesigner.Design d = r.best();
			if (d == null) return new Object[]{r, null, null, null, null};
			progress("bombs", 0, 1);
			BombPlanner.Plan bombs = new BombPlanner(w, cfg).plan(start, LegDesigner.path(d), d.toClear());
			LegDesigner.AfterBombs after = designer.afterBombs(d, bombs.blasted());
			progress("placing", 0, 1);
			PlacementPlanner.Plan place = new PlacementPlanner(w, cfg).plan(LegDesigner.path(d), after.placements(), bombs.blasted(),
					post -> designer.validate(d.lanes(), post) == null);
			return new Object[]{r, d, bombs, after, place};
		}, POOL).whenComplete((res, err) -> mc.execute(() -> {
			running = false;
			if (err != null) { failed(err); return; }
			reportLeg(target, (LegDesigner.Result) res[0], (LegDesigner.Design) res[1], (BombPlanner.Plan) res[2], (LegDesigner.AfterBombs) res[3],
					(PlacementPlanner.Plan) res[4]);
		}));
		return "Designing the setup to " + target.displayName + " from here...";
	}

	private static void reportLeg(Structure target, LegDesigner.Result r, LegDesigner.Design d, BombPlanner.Plan bombs, LegDesigner.AfterBombs after,
			PlacementPlanner.Plan place) {
		if (d == null) {
			shown = null;
			say(String.format("No setup found to %s (%d paths tried, %d blocked by ores/structures/unexplored ground%s, %.0f ms).",
					target.displayName, r.pathsTried(), r.pathsBlocked(), r.timedOut() ? ", ran out of time" : "", r.millis()), ChatFormatting.RED);
			return;
		}
		shown = List.of(new LegView(d, bombs, after.placements(), place));
		say(String.format("Setup to %s: %d lane(s), %d bend(s), %d etherwarps, run time %.1f s.", target.displayName, d.lanes().size(), d.bends(),
				d.warps(), d.runSeconds()), ChatFormatting.AQUA);
		say(String.format("Setup work: %d bombs, %d blocks to mine, %d blocks needed gone (%d in structure zones). Designed in %.0f ms + bombs %.0f ms.",
				bombs.bombs(), bombs.mine().size(), d.toClear().size(), d.inZone(), r.millis(), bombs.millis()), ChatFormatting.GRAY);
		if (!after.valid()) say("Check after the bombs failed: " + after.problem(), ChatFormatting.RED);
		reportPlacing(place.targets(), place.helpers(), place.pillarBlocks(), place.impossible().size(), place.nearStructure());
		legend();
	}

	// ------------------------------------------------------------------ whole lobby: /nr setup

	/** Plans the whole lobby's setup; progress on screen, the result in chat. Returns a message for the player. */
	public static String startLobby() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !LocationTracker.state().inHollows()) return "Only in the Crystal Hollows.";
		if (running) return "Already planning, wait a moment.";
		Inputs in = inputs();
		NrConfig cfg = in.cfg();
		// Legs already found on the terrain (/nr route), to compare with the designed tunnels
		Map<String, Double> terrain = new HashMap<>();
		RouteTracker.Route route = RouteTracker.current();
		if (route != null && java.util.Objects.equals(route.lobby(), StructureTracker.lobby()))
			for (Map.Entry<String, LegPlanner.Leg> e : route.legs().entrySet()) if (e.getValue().ok()) terrain.put(e.getKey(), e.getValue().seconds());
		String missing = StructureHud.setupReady() ? null
				: StructureHud.status().total() - StructureHud.status().found().size() + " structure(s) not found yet";
		running = true;
		progress("reading the terrain", 0, 1);
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			SetupWorld w = SetupWorld.of(snap, in.zones(), in.explored());
			return LobbySetup.plan(stops(snap, in), w, terrain, cfg, in.ping(), WORKER_POOL, WORKERS, SetupPreview::progress);
		}, POOL).whenComplete((res, err) -> mc.execute(() -> {
			running = false;
			if (err != null) { failed(err); return; }
			lobbyPlan = res;
			reportLobby(res, !terrain.isEmpty());
		}));
		String msg = "Planning the whole lobby's setup (up to " + cfg.setup.lobbyTimeLimitMs / 1000.0 + " s)...";
		if (missing != null) msg += " Note: " + missing + "; they're left out.";
		if (terrain.isEmpty()) msg += " Tip: /nr route first, so tunnels are only planned where they beat the existing terrain.";
		return msg;
	}

	private static void reportLobby(LobbySetup.Result r, boolean comparedWithTerrain) {
		List<LegView> views = new ArrayList<>();
		for (LobbySetup.LegSetup s : r.setups()) {
			Set<Long> targets = new java.util.HashSet<>();
			for (PlacementPlanner.Step p : s.placing().steps()) if (!p.helper()) targets.add(Lane.pack(p.block()[0], p.block()[1], p.block()[2]));
			targets.addAll(s.placing().impossible());
			views.add(new LegView(s.design(), s.bombs(), targets, s.placing()));
		}
		shown = views;
		say(String.format("Lobby setup planned in %.1f s (%d legs designed of %d%s; design %.1f s, bombs %.1f s, placing %.1f s).",
				r.totalMs() / 1000, r.designs().size(), r.legsWanted(), r.legsTimedOut() > 0 ? ", " + r.legsTimedOut() + " cut short by the time limit" : "",
				r.designMs() / 1000, r.bombsMs() / 1000, r.placeMs() / 1000), ChatFormatting.AQUA);
		if (!r.plan().hasRoute()) { say("No run order found (see the game log).", ChatFormatting.RED); return; }
		Map<String, LobbySetup.LegSetup> byKey = new HashMap<>();
		for (LobbySetup.LegSetup s : r.setups()) byKey.put(s.key(), s);
		int n = 1;
		for (RouteOrder.Step s : r.plan().steps()) {
			String from = s.viaWarpNucleus() ? "nucleus" : s.fromId();
			String to = s.toId().equals("nucleus") ? "back to the nucleus" : label(s.toId());
			LobbySetup.LegSetup leg = byKey.get(from + ">" + s.toId());
			String how = (s.viaWarpNucleus() ? "/warp nucleus, then " : "") + (leg == null
					? (s.viaWarpNucleus() && s.toId().equals("nucleus") ? "done" : "existing terrain")
					: String.format("tunnel: %d warps in %d lane(s); %d bombs, %d mined, %d placed",
							leg.design().warps(), leg.design().lanes().size(), leg.bombs().bombs(), leg.bombs().mine().size(), leg.placing().steps().size()));
			say(String.format("  %d. %s: %s (%.1f s)", n++, to, how, s.travelSeconds()), ChatFormatting.GRAY);
		}
		say(String.format("  Run after the setup: about %s. Setup work: %d bombs, %d blocks to mine, %d blocks to place.",
				dev.nucleusrouter.game.LobbyEvaluator.mmss(r.plan().seconds()), r.bombs(), r.mined(), r.placements()), ChatFormatting.AQUA);
		for (String p : r.problems()) say("  Problem: " + p, ChatFormatting.RED);
		if (!comparedWithTerrain) say("  (Not compared with the existing terrain: /nr route first.)", ChatFormatting.DARK_GRAY);
		legend();
		NucleusRouterClient.LOG.info("[Nucleus Router] Setup: {} legs wanted, {} designed, {} cut short; design {} ms, order {} ms, bombs {} ms, placing {} ms, total {} ms; problems {}",
				r.legsWanted(), r.designs().size(), r.legsTimedOut(), Math.round(r.designMs()), Math.round(r.orderMs()), Math.round(r.bombsMs()),
				Math.round(r.placeMs()), Math.round(r.totalMs()), r.problems());
	}

	private static String label(String id) {
		for (Structure s : Structure.values()) if (s.key.equals(id)) return s.displayName;
		return id;
	}

	// ------------------------------------------------------------------ shared

	private static void reportPlacing(int targets, int helpers, int pillar, int impossible, int nearStructure) {
		say(String.format("Placing (after all bombs): %d target blocks, %d helper blocks (%d by pillaring up).", targets, helpers, pillar), ChatFormatting.GRAY);
		if (impossible > 0) say(impossible + " target block(s) can't be placed (no block to click on, too high to pillar): shown in magenta.", ChatFormatting.RED);
		if (nearStructure > 0) say(nearStructure + " placement(s) are close to a structure: the game may refuse them.", ChatFormatting.YELLOW);
	}

	private static void legend() {
		say("Shown in the world: lanes (white line, targets green, orange = place), bombs (B numbers, cyan), mine (red), placing (P numbers: stand on the yellow spot, click the yellow face; pillars as a yellow column; \"edge\" = sneak to the edge). Bombs first, then placing. /nr design clear hides it.",
				ChatFormatting.DARK_GRAY);
	}

	private static void say(String text, ChatFormatting style) {
		NucleusRouterClient.showLocal(Component.literal(text).withStyle(style));
	}

	private static void failed(Throwable err) {
		Throwable c = err.getCause() != null ? err.getCause() : err;
		say("Setup planning failed: " + c.getMessage(), ChatFormatting.RED);
		NucleusRouterClient.LOG.warn("[Nucleus Router] Setup planning failed", c);
	}

	private static void progress(String what, int d, int t) {
		phase = what;
		done = d;
		total = t;
	}

	private static void drawProgress(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!running) return;
		Minecraft mc = Minecraft.getInstance();
		String text = "Nucleus Router: planning the setup: " + phase + (total > 1 ? " " + done + "/" + total : "") + "...";
		int w = mc.font.width(text);
		int x = g.guiWidth() / 2 - w / 2, y = 4;
		g.fill(x - 3, y - 2, x + w + 3, y + mc.font.lineHeight + 1, 0x90000000);
		g.text(mc.font, text, x, y, 0xFFFFEB3B, true);
	}

	private static void draw(WorldDraw draw) {
		List<LegView> legs = shown;
		if (legs == null) return;
		NrConfig cfg = ConfigManager.get();
		int bombNo = 0, placeNo = 0;
		// Lanes: a line from each lane's start to its end, every target block
		for (LegView v : legs) {
			for (LegDesigner.PlannedLane l : v.design().lanes()) {
				int[] o = l.spec().origin(), e = l.spec().end();
				draw.line(WorldDraw.mc(new Vec3(o[0] + 0.5, o[1] + 1.1, o[2] + 0.5)), WorldDraw.mc(new Vec3(e[0] + 0.5, e[1] + 1.1, e[2] + 0.5)),
						WorldDraw.argb(220, 0xFFFFFF), 2f, true);
				for (int k = 1; k <= l.spec().warps(); k++) {
					int[] t = l.spec().target(k);
					int rgb = v.placeTargets().contains(Lane.pack(t[0], t[1], t[2])) ? 0xFF9800 : 0x66FF66;
					draw.block(t[0], t[1], t[2], WorldDraw.argb(255, rgb), 2f, WorldDraw.argb(60, rgb), true);
				}
			}
		}
		// Bombs (all legs, in order): numbered stand spots, a line to the blast centre, the blast radius
		double r = cfg.bombs.blastRadius;
		for (LegView v : legs) {
			for (BombPlanner.Throw t : v.bombs().throwsInOrder()) {
				bombNo++;
				draw.marker(WorldDraw.mc(t.standFeet().add(new Vec3(0, 0.1, 0))), 0.3, WorldDraw.argb(255, 0x00E5FF), WorldDraw.argb(80, 0x00E5FF), true);
				draw.text("B" + bombNo, WorldDraw.mc(t.standFeet().add(new Vec3(0, 2.2, 0))), WorldDraw.argb(255, 0x00E5FF), 0.7f, true);
				draw.line(WorldDraw.mc(t.standFeet().add(new Vec3(0, cfg.etherwarp.eyeHeight, 0))), WorldDraw.mc(t.centre()), WorldDraw.argb(120, 0x00E5FF), 1f, true);
				draw.circle(WorldDraw.mc(t.centre()), (float) r, WorldDraw.argb(90, 0x00E5FF), true);
			}
		}
		// Placements (after all bombs): numbered blocks; click steps show where to stand and which face to click
		for (LegView v : legs) {
			for (PlacementPlanner.Step p : v.place().steps()) {
				placeNo++;
				int[] b = p.block();
				int rgb = p.helper() ? 0xFFEB3B : 0xFF9800;
				draw.outline(new AABB(b[0] + 0.05, b[1] + 0.05, b[2] + 0.05, b[0] + 0.95, b[1] + 0.95, b[2] + 0.95), WorldDraw.argb(200, rgb), 1.5f, true);
				if (p.pillar()) {
					if (!p.helper()) draw.text("P" + placeNo + " pillar up", WorldDraw.mc(new Vec3(b[0] + 0.5, b[1] + 1.4, b[2] + 0.5)), WorldDraw.argb(255, 0xFFEB3B), 0.5f, true);
					continue;
				}
				draw.text("P" + placeNo + (p.edge() ? " edge" : ""), WorldDraw.mc(new Vec3(b[0] + 0.5, b[1] + 1.3, b[2] + 0.5)), WorldDraw.argb(255, 0xFFEB3B), 0.5f, true);
				draw.marker(WorldDraw.mc(p.standFeet().add(new Vec3(0, 0.05, 0))), 0.25, WorldDraw.argb(255, 0xFFEB3B), WorldDraw.argb(70, 0xFFEB3B), true);
				Vec3 c = p.clickPoint();
				double e = 0.3, t = 0.02;
				AABB face = switch (p.face()) {
					case UP, DOWN -> new AABB(c.x() - e, c.y() - t, c.z() - e, c.x() + e, c.y() + t, c.z() + e);
					case NORTH, SOUTH -> new AABB(c.x() - e, c.y() - e, c.z() - t, c.x() + e, c.y() + e, c.z() + t);
					case WEST, EAST -> new AABB(c.x() - t, c.y() - e, c.z() - e, c.x() + t, c.y() + e, c.z() + e);
				};
				draw.box(face, WorldDraw.argb(255, 0xFFEB3B), 1.5f, WorldDraw.argb(120, 0xFFEB3B), true);
			}
			for (long k : v.place().impossible()) {
				int[] b = Lane.unpack(k);
				draw.outline(new AABB(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1), WorldDraw.argb(255, 0xFF00FF), 3f, true);
			}
		}
		// Blocks to mine by hand (first 200 overall)
		int n = 0;
		for (LegView v : legs) {
			for (long k : v.bombs().mine()) {
				if (n++ >= 200) break;
				int[] b = Lane.unpack(k);
				draw.outline(new AABB(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1), WorldDraw.argb(220, 0xFF3030), 2f, true);
			}
		}
	}
}
