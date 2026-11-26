package dev.nucleusrouter.gametest;

import dev.nucleusrouter.platform.debug.DebugRay;
import dev.nucleusrouter.platform.render.WorldDraw;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Visual check of world rendering (roadmap 1.6). Builds a small scene in a test world and takes
 * screenshots (saved under run/clientGameTest/screenshots or build/...). Moving the player here is
 * test-harness setup in a local test world, not something the mod ever does.
 */
public final class RenderGameTest implements FabricClientGameTest {
	private static volatile boolean showTestShapes;

	@Override
	public void runTest(ClientGameTestContext context) {
		// Shapes drawn through a wall, to prove "through walls" works
		WorldDraw.addLayer((draw, ctx) -> {
			if (!showTestShapes) return;
			draw.block(0, 101, 30, WorldDraw.argb(255, 0xFFAA00), 3f, WorldDraw.argb(80, 0xFFAA00), true); // behind the wall
			draw.line(new Vec3(-3.5, 101.5, 12.5), new Vec3(3.5, 103.5, 12.5), WorldDraw.argb(255, 0xFF00FF), 4f, true);
			draw.outline(new AABB(-6, 100, 14, -4, 102, 16), WorldDraw.argb(255, 0x00FFFF), 3f, false);
			draw.text("through-wall test", new Vec3(0.5, 103, 30.5), WorldDraw.argb(255, 0xFFFFFF), 0.8f, true);
		});

		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			var server = sp.getServer();
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("fill -12 99 -4 12 99 40 minecraft:stone");
			server.runCommand("fill -12 100 -4 12 110 40 minecraft:air");
			// Target block in front of the player (valid warp: 2 air above)
			server.runCommand("setblock 0 100 12 minecraft:stone");
			// A wall that hides the orange box at z=30
			server.runCommand("fill -3 100 25 3 104 25 minecraft:stone");
			// Look level-ish so the ray hits the side of the target block at z=12
			server.runCommand("tp @p 0.5 100 0.5 0 5");
			context.waitTicks(40);

			context.runOnClient(mc -> { if (!DebugRay.enabled()) DebugRay.toggle(); });
			showTestShapes = true;
			context.waitTicks(20);
			context.takeScreenshot("nr-1-valid-target");

			// Block above the target: warp is not valid (red)
			server.runCommand("setblock 0 102 12 minecraft:stone");
			context.waitTicks(20);
			context.takeScreenshot("nr-2-blocked-above");
			server.runCommand("setblock 0 102 12 minecraft:air");

			// Look at a far target near the edge of range
			server.runCommand("setblock 0 100 38 minecraft:stone");
			server.runCommand("setblock 0 100 12 minecraft:air");
			server.runCommand("fill -3 100 25 3 104 25 minecraft:air");
			server.runCommand("tp @p 0.5 100 0.5 0 1.5");
			context.waitTicks(30);
			context.takeScreenshot("nr-3-far-target");

			// In-game report 2026-11-24: fence with a torch on top showed red, but you can warp onto it.
			showTestShapes = false;
			server.runCommand("setblock 0 100 38 minecraft:air");
			server.runCommand("setblock 0 100 10 minecraft:oak_fence");
			server.runCommand("setblock 0 101 10 minecraft:torch");
			server.runCommand("setblock 2 100 10 minecraft:oak_fence");
			server.runCommand("setblock 2 101 10 minecraft:soul_torch");
			server.runCommand("fill -4 100 8 4 100 8 minecraft:short_grass");
			server.runCommand("tp @p 0.5 100 0.5 0 4");
			context.waitTicks(30);
			context.takeScreenshot("nr-4-fence-with-torch");
			server.runCommand("tp @p 0.5 100 0.5 -10 4");
			context.waitTicks(30);
			context.takeScreenshot("nr-5-fence-with-soul-torch");

			context.runOnClient(mc -> DebugRay.toggle());

			// Roadmap 2.6: the on-screen structure list (forced on, since this world isn't the Crystal Hollows)
			context.runOnClient(mc -> {
				dev.nucleusrouter.game.StructureMap m = dev.nucleusrouter.platform.structure.StructureTracker.map();
				m.observeNametag(dev.nucleusrouter.game.Structure.KING_YOLKAR, "King Yolkar", 10, 100, 10, 0, true);
				m.setSkyblocker(dev.nucleusrouter.game.Structure.JUNGLE_TEMPLE, new double[]{60, 100, 60}, false);
				dev.nucleusrouter.platform.structure.StructureHud.forceShowForTest = true;
			});
			context.waitTicks(20);
			context.takeScreenshot("nr-6-structure-list");
			context.runOnClient(mc -> mc.gui.setScreen(new dev.nucleusrouter.platform.structure.HudEditScreen(null)));
			context.waitTicks(10);
			context.takeScreenshot("nr-7-move-structure-list");
			context.runOnClient(mc -> mc.gui.setScreen(null));

			// /nr explored map with some explored ground and two structures (made-up data)
			context.runOnClient(mc -> {
				dev.nucleusrouter.game.Coverage cov = dev.nucleusrouter.platform.structure.ExplorationTracker.coverage();
				for (int i = 0; i < 40; i++) cov.visit(513 + i * 6, 526 - i * 4, 48);
				for (int i = 0; i < 30; i++) cov.visit(513 - i * 8, 526 + i * 5, 48);
				for (int x = 202; x < 823; x += 16) for (int z = 300; z < 700; z += 16) cov.loadChunk(x, z);
				dev.nucleusrouter.game.StructureMap m = dev.nucleusrouter.platform.structure.StructureTracker.map();
				m.clear(dev.nucleusrouter.game.Structure.KING_YOLKAR);
				m.observeNametag(dev.nucleusrouter.game.Structure.KING_YOLKAR, "King Yolkar", 300, 120, 680, 0, true);
				m.setSkyblocker(dev.nucleusrouter.game.Structure.JUNGLE_TEMPLE, new double[]{300, 100, 330}, false);
				mc.gui.setScreen(new dev.nucleusrouter.platform.structure.ExploreMapScreen());
			});
			context.waitTicks(10);
			context.takeScreenshot("nr-8-explored-map");
			context.runOnClient(mc -> mc.gui.setScreen(null));

			// Run mode: a made-up route of 4 warps over the floor to a stop, then /warp nucleus
			server.runCommand("fill -12 100 -4 12 110 40 minecraft:air");
			server.runCommand("tp @p 0.5 100 0.5 0 12");
			context.waitTicks(20);
			context.runOnClient(mc -> {
				if (DebugRay.enabled()) DebugRay.toggle();
				showTestShapes = false;
				java.util.function.BiFunction<Integer, Integer, dev.nucleusrouter.core.plan.LegPlanner.Warp> w = (x, z) -> {
					dev.nucleusrouter.core.geom.Vec3 land = new dev.nucleusrouter.core.geom.Vec3(x + 0.5, 100, z + 0.5);
					return new dev.nucleusrouter.core.plan.LegPlanner.Warp(null, x, 99, z, land.add(new dev.nucleusrouter.core.geom.Vec3(0, 0, 0)), null, land, 2, 0, 0, 0.3);
				};
				dev.nucleusrouter.core.geom.Vec3 n0 = new dev.nucleusrouter.core.geom.Vec3(0.5, 100, 0.5), end = new dev.nucleusrouter.core.geom.Vec3(0.5, 100, 32.5);
				var stops = java.util.List.of(
						new dev.nucleusrouter.game.StopPlan.Stop("nucleus", "Crystal Nucleus", null, dev.nucleusrouter.game.StopPlan.Kind.NUCLEUS, n0, n0, 1, 0, false, ""),
						new dev.nucleusrouter.game.StopPlan.Stop("king", "King Yolkar", dev.nucleusrouter.game.Structure.KING_YOLKAR, dev.nucleusrouter.game.StopPlan.Kind.STRUCTURE, end, end, 1, 1, false, ""));
				var plan = new dev.nucleusrouter.game.RouteOrder.Plan(java.util.List.of(
						new dev.nucleusrouter.game.RouteOrder.Step("nucleus", "king", false, 2, 1),
						new dev.nucleusrouter.game.RouteOrder.Step("king", "nucleus", true, 1, 0)), 4, java.util.List.of());
				var leg = new dev.nucleusrouter.core.plan.LegPlanner.Leg(dev.nucleusrouter.core.plan.LegPlanner.Status.FOUND,
						java.util.List.of(w.apply(0, 8), w.apply(-3, 16), w.apply(2, 24), w.apply(0, 32)), 2, 0, 0, 0, 0);
				dev.nucleusrouter.platform.structure.RunTracker.forceShowForTest = true;
				dev.nucleusrouter.platform.structure.RunHud.forceShowForTest = true;
				dev.nucleusrouter.platform.structure.RunTracker.startForTest(dev.nucleusrouter.game.RunPath.build(plan, stops, java.util.Map.of("nucleus>king", leg), java.util.Set.of()));
			});
			context.waitTicks(20);
			context.takeScreenshot("nr-9-run-mode");

			// Roadmap 3.6: run panel after reaching the stop (timer running, King split), plus the den countdown
			server.runCommand("tp @p 0.5 100 -3 0 12");
			context.waitTicks(30);
			server.runCommand("tp @p 0.5 100 32.5 0 12");
			context.waitTicks(20);
			context.runOnClient(mc -> dev.nucleusrouter.platform.structure.RunHud.startDenForTest());
			context.waitTicks(10);
			context.takeScreenshot("nr-10-run-hud");
			context.runOnClient(mc -> dev.nucleusrouter.platform.structure.RunHud.forceShowForTest = false);
			context.runOnClient(mc -> { dev.nucleusrouter.platform.structure.RunTracker.stop(); dev.nucleusrouter.platform.structure.RunTracker.forceShowForTest = false; });
			context.runOnClient(mc -> dev.nucleusrouter.platform.structure.StructureHud.forceShowForTest = false);

			// Roadmap 4.6: guided setup. A wall of stone ahead: throw B1 at it, mine M1 next to it, then place P1/P2
			server.runCommand("fill -12 100 -4 12 110 40 minecraft:air");
			server.runCommand("fill -6 100 20 6 106 22 minecraft:stone");
			server.runCommand("fill 3 100 6 3 100 6 minecraft:stone");
			server.runCommand("tp @p 0.5 100 0.5 0 15");
			context.waitTicks(20);
			context.runOnClient(mc -> {
				var V = (java.util.function.Function<double[], dev.nucleusrouter.core.geom.Vec3>) d -> new dev.nucleusrouter.core.geom.Vec3(d[0], d[1], d[2]);
				var t = new dev.nucleusrouter.core.setup.BombPlanner.Throw(V.apply(new double[]{0.5, 100, 4.5}), V.apply(new double[]{0.5, 103.5, 20.5}),
						V.apply(new double[]{0, 0, 1}), new int[]{0, 103, 20}, V.apply(new double[]{0.5, 103.5, 20.5}), false, 40);
				java.util.List<int[]> blast = new java.util.ArrayList<>();
				for (int x = -2; x <= 2; x++) for (int y = 101; y <= 105; y++) blast.add(new int[]{x, y, 20});
				var place = new dev.nucleusrouter.core.setup.PlacementPlanner.Step(new int[]{3, 101, 6}, new int[]{3, 100, 6}, dev.nucleusrouter.core.geom.Face.UP,
						V.apply(new double[]{1.5, 100, 4.5}), V.apply(new double[]{3.5, 101, 6.5}), V.apply(new double[]{0.6, -0.2, 0.6}), false, false, false, false);
				var steps = dev.nucleusrouter.game.SetupSteps.of(java.util.List.of(
						new dev.nucleusrouter.game.SetupSteps.Step(dev.nucleusrouter.game.SetupSteps.Kind.THROW, "B1", 0, t, blast, null),
						new dev.nucleusrouter.game.SetupSteps.Step(dev.nucleusrouter.game.SetupSteps.Kind.MINE, "M1", 0, null,
								java.util.List.of(new int[]{-6, 100, 21}, new int[]{-6, 101, 21}), null),
						new dev.nucleusrouter.game.SetupSteps.Step(dev.nucleusrouter.game.SetupSteps.Kind.PLACE, "P1", 0, null, java.util.List.of(place.block()), place)));
				dev.nucleusrouter.platform.structure.SetupGuide.forceShowForTest = true;
				dev.nucleusrouter.platform.structure.SetupGuide.startForTest(steps);
			});
			context.waitTicks(20);
			context.takeScreenshot("nr-11-guided-setup");
			context.runOnClient(mc -> { dev.nucleusrouter.platform.structure.SetupGuide.stop(); dev.nucleusrouter.platform.structure.SetupGuide.forceShowForTest = false; });
		}
	}
}
