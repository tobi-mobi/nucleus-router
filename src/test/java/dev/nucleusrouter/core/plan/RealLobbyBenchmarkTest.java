package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.core.world.WorldSnapshot;
import dev.nucleusrouter.core.world.WorldStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Leg-planner benchmark on a real lobby save (SPEC §7). Needs {@code bench/mini92AD.nrw} (copied from the
 * game's {@code nucleusrouter/lobbies/}; git-ignored, so this is skipped on other machines).
 * Points: nucleus spawn, the 4 Mines of Divan keepers and Professor Robot, as found in that lobby.
 */
class RealLobbyBenchmarkTest {
	@Test
	void legsInARealLobby() throws Exception {
		Path file = Path.of("bench", "mini92AD.nrw");
		Assumptions.assumeTrue(Files.exists(file), "no real lobby in bench/");
		NrConfig cfg = new NrConfig();
		List<String> pass = new ArrayList<>(cfg.etherwarp.rayPassesThrough);
		pass.addAll(List.of("minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
				"minecraft:dandelion", "minecraft:poppy", "minecraft:vine", "minecraft:dead_bush"));
		WorldSnapshot snap;
		long t = System.nanoTime();
		try (InputStream in = Files.newInputStream(file)) {
			snap = WorldStore.read(in, BlockClassifier.fromConfig(cfg, pass)).model().snapshot();
		}
		System.out.printf("[bench-real] loaded %d sections in %.0f ms%n", snap.sectionCount(), (System.nanoTime() - t) / 1e6);

		PlanWorld w = PlanWorld.of(snap, PlanWorld.patterns(cfg.plan.unstableTargetBlocks));
		LegPlanner planner = new LegPlanner(w, Etherwarp.Params.fromConfig(cfg), new CostModel(cfg, 170), LegPlanner.Settings.fromConfig(cfg));
		Vec3 nucleus = new Vec3(513.5, 106, 526.5);
		Vec3[] keepers = {new Vec3(588.5, 89, 442.5), new Vec3(618.5, 89, 478.5), new Vec3(654.5, 89, 448.5), new Vec3(624.5, 89, 412.5)};
		Vec3 robot = new Vec3(714.5, 118, 600.5);
		Vec3[][] legs = {
				{nucleus, keepers[0]}, {keepers[0], keepers[1]}, {keepers[1], keepers[2]}, {keepers[2], keepers[3]},
				{keepers[3], robot}, {nucleus, robot}, {robot, nucleus}};
		int found = 0;
		long worst = 0, totalFirst = 0;
		for (Vec3[] l : legs) {
			LegPlanner.Leg leg = planner.plan(l[0], null, l[1], 3);
			if (leg.status() == LegPlanner.Status.FOUND) { found++; totalFirst += leg.firstMicros(); worst = Math.max(worst, leg.firstMicros()); }
			System.out.printf("[bench-real] %s -> %s (%.0f blocks): %s, %d warps, %.2f s, %d expansions, %d priced, first route %.1f ms, total %.1f ms%n",
					fmt(l[0]), fmt(l[1]), l[0].sub(l[1]).length(), leg.status(), leg.warps().size(), leg.seconds(),
					leg.expansions(), leg.edgesPriced(), leg.firstMicros() / 1000.0, leg.micros() / 1000.0);
		}
		System.out.printf("[bench-real] %d of %d legs found; first route avg %.1f ms, worst %.1f ms; cache %d entries%n",
				found, legs.length, found == 0 ? 0 : totalFirst / 1000.0 / found, worst / 1000.0, planner.cacheSize());
	}

	private static String fmt(Vec3 v) { return Math.round(v.x()) + "," + Math.round(v.y()) + "," + Math.round(v.z()); }
}
