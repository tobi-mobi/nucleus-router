package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.LegDesigner;
import dev.nucleusrouter.core.setup.SetupWorld;
import dev.nucleusrouter.game.StopPlan;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /nr design <structure>} (roadmap 4.2-4.3 debug view, before the guided setup of 4.6): designs the setup
 * for the leg from where you stand to a structure on a background thread, prints a summary and shows it in the
 * world: lanes (line + target blocks, orange = to place), bomb throws (numbered stand spots, a line to the blast
 * centre and its radius) and blocks to mine (red). Shows only; nothing is done for you.
 */
public final class SetupPreview {
	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "nucleusrouter-design");
		t.setDaemon(true);
		return t;
	});

	private record Shown(LegDesigner.Design design, BombPlanner.Plan bombs, LegDesigner.AfterBombs after) {}

	private static volatile Shown shown;
	private static volatile boolean running;

	private SetupPreview() {}

	public static void init() {
		WorldDraw.addLayerWhile(() -> shown != null && LocationTracker.state().inHollows(), SetupPreview::draw);
	}

	public static void clear() { shown = null; }

	/** Starts designing; the result arrives in chat. Returns a message for the player. */
	public static String start(Structure target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !LocationTracker.state().inHollows()) return "Only in the Crystal Hollows.";
		if (running) return "Already designing, wait a moment.";
		NrConfig cfg = ConfigManager.get();
		StructureMap map = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(StructureTracker.map()), StructureMap.class);
		var layout = KeeperLayouts.layout();
		var visited = ExplorationTracker.coverage().visitedCopy();
		PlanWorld.Columns explored = cfg.plan.onlyExploredTerrain ? visited::test : (x, z) -> true;
		List<int[]> zones = new ArrayList<>();
		for (Structure s : Structure.values()) {
			int[] b = map.zoneBox(s, StructureShapes.zone(s));
			if (b != null) zones.add(b);
		}
		int[] start = {(int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY() - 0.01) - 1, (int) Math.floor(mc.player.getZ())};
		int ping = LocationTracker.state().pingMs();
		running = true;
		WorldCapture.snapshot().thenApplyAsync(snap -> {
			StopPlan.Stop stop = null;
			for (StopPlan.Stop s : StopPlan.build(map, layout, PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks)), cfg))
				if (s.structure() == target && s.kind() == StopPlan.Kind.STRUCTURE) { stop = s; break; }
			if (stop == null || stop.standFeet() == null)
				throw new IllegalStateException(target.displayName + " has no arrival spot yet (find it first: /nr stops)");
			Vec3 f = stop.standFeet();
			int[] goal = {(int) Math.floor(f.x()), (int) Math.floor(f.y()) - 1, (int) Math.floor(f.z())};
			SetupWorld w = SetupWorld.of(snap, zones, explored);
			LegDesigner designer = new LegDesigner(w, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, ping), cfg);
			LegDesigner.Result r = designer.design(start, goal);
			LegDesigner.Design d = r.best();
			if (d == null) return new Object[]{r, null, null, null};
			BombPlanner.Plan bombs = new BombPlanner(w, cfg).plan(start, LegDesigner.path(d), d.toClear());
			return new Object[]{r, d, bombs, designer.afterBombs(d, bombs.blasted())};
		}, POOL).whenComplete((res, err) -> mc.execute(() -> {
			running = false;
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				NucleusRouterClient.showLocal(Component.literal("Design failed: " + c.getMessage()).withStyle(ChatFormatting.RED));
				return;
			}
			report(target, (LegDesigner.Result) res[0], (LegDesigner.Design) res[1], (BombPlanner.Plan) res[2], (LegDesigner.AfterBombs) res[3]);
		}));
		return "Designing the setup to " + target.displayName + " from here...";
	}

	private static void report(Structure target, LegDesigner.Result r, LegDesigner.Design d, BombPlanner.Plan bombs, LegDesigner.AfterBombs after) {
		if (d == null) {
			shown = null;
			NucleusRouterClient.showLocal(Component.literal(String.format(
					"No setup found to %s (%d paths tried, %d blocked by ores/structures/unexplored ground%s, %.0f ms).",
					target.displayName, r.pathsTried(), r.pathsBlocked(), r.timedOut() ? ", ran out of time" : "", r.millis())).withStyle(ChatFormatting.RED));
			return;
		}
		shown = new Shown(d, bombs, after);
		NucleusRouterClient.showLocal(Component.literal(String.format(
				"Setup to %s: %d lane(s), %d bend(s), %d etherwarps, run time %.1f s.", target.displayName, d.lanes().size(), d.bends(),
				d.warps(), d.runSeconds())).withStyle(ChatFormatting.AQUA));
		NucleusRouterClient.showLocal(Component.literal(String.format(
				"Setup work: %d bombs, %d blocks to mine, %d blocks to place (%d blocks needed gone, %d in structure zones). Designed in %.0f ms + bombs %.0f ms.",
				bombs.bombs(), bombs.mine().size(), after.placements().size(), d.toClear().size(), d.inZone(), r.millis(), bombs.millis())).withStyle(ChatFormatting.GRAY));
		if (!after.valid())
			NucleusRouterClient.showLocal(Component.literal("Check after the bombs failed: " + after.problem()).withStyle(ChatFormatting.RED));
		NucleusRouterClient.showLocal(Component.literal("Shown in the world: lanes (white line, targets green, orange = place), bombs (numbered, cyan), mine (red). /nr design clear hides it.")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void draw(WorldDraw draw) {
		Shown s = shown;
		if (s == null) return;
		// Lanes: a line from each lane's start to its end, every target block
		for (LegDesigner.PlannedLane l : s.design().lanes()) {
			int[] o = l.spec().origin(), e = l.spec().end();
			draw.line(WorldDraw.mc(new Vec3(o[0] + 0.5, o[1] + 1.1, o[2] + 0.5)), WorldDraw.mc(new Vec3(e[0] + 0.5, e[1] + 1.1, e[2] + 0.5)),
					WorldDraw.argb(220, 0xFFFFFF), 2f, true);
			for (int k = 1; k <= l.spec().warps(); k++) {
				int[] t = l.spec().target(k);
				boolean place = s.after().placements().contains(Lane.pack(t[0], t[1], t[2]));
				int rgb = place ? 0xFF9800 : 0x66FF66;
				draw.block(t[0], t[1], t[2], WorldDraw.argb(255, rgb), 2f, WorldDraw.argb(60, rgb), true);
			}
		}
		// Bombs: numbered stand spots, a line to the blast centre, the blast radius
		double r = ConfigManager.get().bombs.blastRadius;
		List<BombPlanner.Throw> ts = s.bombs().throwsInOrder();
		for (int i = 0; i < ts.size(); i++) {
			BombPlanner.Throw t = ts.get(i);
			draw.marker(WorldDraw.mc(t.standFeet().add(new Vec3(0, 0.1, 0))), 0.3, WorldDraw.argb(255, 0x00E5FF), WorldDraw.argb(80, 0x00E5FF), true);
			draw.text(String.valueOf(i + 1), WorldDraw.mc(t.standFeet().add(new Vec3(0, 2.2, 0))), WorldDraw.argb(255, 0x00E5FF), 0.7f, true);
			draw.line(WorldDraw.mc(t.standFeet().add(new Vec3(0, ConfigManager.get().etherwarp.eyeHeight, 0))), WorldDraw.mc(t.centre()),
					WorldDraw.argb(120, 0x00E5FF), 1f, true);
			draw.circle(WorldDraw.mc(t.centre()), (float) r, WorldDraw.argb(90, 0x00E5FF), true);
		}
		// Blocks to mine by hand (first 200)
		int n = 0;
		for (long k : s.bombs().mine()) {
			if (n++ >= 200) break;
			int[] b = Lane.unpack(k);
			draw.outline(new AABB(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1), WorldDraw.argb(220, 0xFF3030), 2f, true);
		}
	}
}
