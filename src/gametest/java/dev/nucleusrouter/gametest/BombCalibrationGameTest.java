package dev.nucleusrouter.gametest;

import dev.nucleusrouter.core.bomb.BlastAnalysis;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.platform.debug.BombCalibration;
import dev.nucleusrouter.platform.world.WorldCapture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end check of /nr calibrate bomb (roadmap 1.7) and /nr dump, inside the Hollows coordinates.
 * The "bomb" is faked: the test server removes a radius-8 ball one block away from where the model
 * predicts the impact, and the calibration must find the ball, the radius and the 1-block error.
 */
public final class BombCalibrationGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			var server = sp.getServer();
			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @p");
			server.runCommand("forceload add 470 450 530 530");
			context.waitTicks(20);
			server.runCommand("fill 485 95 480 515 108 515 minecraft:stone");
			server.runCommand("fill 485 109 480 515 122 515 minecraft:stone");
			server.runCommand("fill 495 100 465 505 109 479 minecraft:stone");
			server.runCommand("setblock 503 110 482 minecraft:oak_log");
			server.runCommand("tp @p 500.5 110 470.5 0 0");
			context.waitTicks(60);

			context.runOnClient(mc -> {
				BombCalibration.arm();
				// Eye at y 111.27 looking straight south: model impact is the stone face at z=480
				BombCalibration.simulateThrowForTest(new Vec3(500.5, 111.27, 470.5), new Vec3(0, 0, 1));
			});
			context.waitTicks(20);

			// The "real" blast: radius 8 around (501.5, 111.5, 481.5), one block east of the model's impact block
			// Carve the ball one x-row at a time with normal commands (they reach the client like real updates)
			int rows = 0;
			for (int y = 100; y <= 123; y++) for (int z = 470; z <= 493; z++) {
				double dy = y + 0.5 - 111.5, dz = z + 0.5 - 481.5;
				double rest = 64 - dy * dy - dz * dz;
				if (rest < 0) continue;
				double half = Math.sqrt(rest);
				int x0 = (int) Math.ceil(501.5 - half - 0.5), x1 = (int) Math.floor(501.5 + half - 0.5);
				if (x0 > x1) continue;
				server.runCommand("fill " + x0 + " " + y + " " + z + " " + x1 + " " + y + " " + z + " minecraft:air replace minecraft:stone");
				rows++;
			}
			int removedOnServer = rows;
			System.out.println("[Nucleus Router gametest] carved " + removedOnServer + " rows");
			long before = WorldCapture.blockChanges();
			long chunksBefore = WorldCapture.chunksSeen();
			context.waitTicks(40);
			int clientAir = context.computeOnClient(mc -> {
				int n = 0;
				for (int x = 490; x <= 513; x++) for (int y = 100; y <= 123; y++) for (int z = 480; z <= 493; z++) {
					double dx = x + 0.5 - 501.5, dy = y + 0.5 - 111.5, dz = z + 0.5 - 481.5;
					if (dx * dx + dy * dy + dz * dz <= 64 && mc.level.getBlockState(new BlockPos(x, y, z)).isAir()) n++;
				}
				return n;
			});
			System.out.println("[Nucleus Router gametest] client sees " + clientAir + " air blocks in the ball; chunk loads during blast: " + (WorldCapture.chunksSeen() - chunksBefore));
			System.out.println("[Nucleus Router gametest] block changes seen during blast: " + (WorldCapture.blockChanges() - before) + " (total " + WorldCapture.blockChanges() + ")");
			context.waitFor(mc -> BombCalibration.lastResultForTest != null, 400);
			context.waitTicks(10);
			context.takeScreenshot("nr-6-bomb-calibration");

			BlastAnalysis.Result r = BombCalibration.lastResultForTest;
			check(r.best().metric() == BlastAnalysis.Metric.EUCLIDEAN, "shape should be a ball, got " + r.best().metric());
			check(Math.abs(r.best().radius() - 8.0) < 0.6, "radius should be ~8, got " + r.best().radius());
			check(r.best().centre().sub(new Vec3(501.5, 111.5, 481.5)).length() < 1.0, "centre should be ~(501.5,111.5,481.5), got " + r.best().centre());
			check(Math.abs(r.modelCentreError() - Math.sqrt(2)) < 1.0, "model error should be ~1.4 blocks, got " + r.modelCentreError());
			check(r.survivorsInside().isEmpty(), "the oak log is immune, not a survivor: " + r.survivorsInside().size());
			check(BombCalibration.lastReportForTest != null && Files.exists(BombCalibration.lastReportForTest), "report file written");

			String msg = WorldCapture.dump().join();
			check(msg.startsWith("Dump saved"), msg);
			Path dumps = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("nucleusrouter/dumps");
			try (var files = Files.list(dumps)) {
				check(files.filter(f -> f.toString().endsWith(".nrw")).count() >= 1, "dump .nrw file exists");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			System.out.println("[Nucleus Router gametest] bomb calibration OK: " + r.best() + " | " + msg);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}
}
