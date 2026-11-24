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

			context.runOnClient(mc -> DebugRay.toggle());
			showTestShapes = false;
		}
	}
}
