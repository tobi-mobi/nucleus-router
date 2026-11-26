package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.AimRegion;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.plan.LegPlanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Bal-area block suggestion (SPEC §2.2, roadmap 5.2; optional, low priority). Blocks placed in Khazad-dum stay
 * (confirmed 2026-11-26), so one well-placed block can turn the route's last few warps into the Bal fight into a
 * single etherwarp: from one of the leg's earlier landing spots straight onto the new block, which is within
 * {@code placing.balSpotRadius} of the spot on the Topaz crystal box.
 * <p>
 * Picks the earliest departure on the arriving leg that has such a block (most warps saved), then the block with
 * the biggest aim margin (ties: closer to the Topaz spot). The block must: be air now with room above, be allowed
 * for placing, keep every earlier warp of the leg landing where it did, and be placeable from where you can stand
 * ({@link PlacementPlanner}, without helper chains being needed for a block that already touches a solid one).
 */
public final class BalPlacement {
	/** Aim-sample grid used to screen candidates (the full config grid is used only for the winner). */
	static final int SCREEN_GRID = 4;

	/**
	 * @param block         where to place the block (null: no suggestion; see {@code reason})
	 * @param fromWarp      index of the leg warp whose start you warp from instead (0 = the leg's start)
	 * @param warpsSaved    warps fewer on the leg
	 * @param secondsSaved  rough time saved (the warps dropped, minus nothing: the new warp replaces warp {@code fromWarp})
	 * @param fromFeet      where you warp from
	 * @param aimPoint      where to aim (on the new block)
	 * @param marginDeg     aim margin of the new warp
	 * @param landingFeet   where you land (on top of the new block)
	 * @param place         how to place it (stand spot, face to click)
	 */
	public record Suggestion(int[] block, int fromWarp, int warpsSaved, double secondsSaved, Vec3 fromFeet, Vec3 aimPoint,
			double marginDeg, Vec3 landingFeet, PlacementPlanner.Step place, String reason) {
		public boolean found() { return block != null; }

		static Suggestion none(String reason) { return new Suggestion(null, -1, 0, 0, null, null, 0, null, null, reason); }
	}

	private BalPlacement() {}

	/**
	 * @param world    the terrain now (with zones, explored columns and no-place areas)
	 * @param warps    the route's leg that ends at Khazad-dum, in order
	 * @param goalFeet the Khazad-dum stop spot (on the Topaz crystal box)
	 */
	public static Suggestion suggest(SetupWorld world, List<LegPlanner.Warp> warps, Vec3 goalFeet, NrConfig cfg) {
		int n = warps.size();
		if (n < 2) return Suggestion.none(n == 0 ? "the route has no warps into Khazad-dum" : "the route already reaches Bal in one warp");
		Etherwarp.Params params = Etherwarp.Params.fromConfig(cfg);
		double minMargin = cfg.plan.minAimMarginDeg;
		List<int[]> candidates = candidates(world, goalFeet, cfg.placing.balSpotRadius, params.requiredAirAbove());
		if (candidates.isEmpty()) return Suggestion.none("no free spot to place a block within " + cfg.placing.balSpotRadius + " blocks of the Topaz crystal");
		int[] goalBlock = {(int) Math.floor(goalFeet.x()), (int) Math.floor(goalFeet.y()) - 1, (int) Math.floor(goalFeet.z())};
		PlacementPlanner placer = new PlacementPlanner(world, cfg);

		for (int i = 0; i < n - 1; i++) {
			final int fi = i;
			LegPlanner.Warp from = warps.get(i);
			Vec3 eye = Etherwarp.eye(from.fromFeet(), params.eyeHeight());
			record Option(int[] c, AimRegion.Result aim, double toGoal) {}
			List<Option> options = new ArrayList<>();
			for (int[] c : candidates) {
				Vec3 centre = new Vec3(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5);
				if (centre.sub(eye).length() > params.range()) continue;
				SolidQuery with = plus(world, c);
				// Quick look: a ray at the top face's centre must land on it
				Vec3 top = new Vec3(c[0] + 0.5, c[1] + 0.999, c[2] + 0.5);
				Etherwarp.Result r = Etherwarp.warp(eye, top.sub(eye).normalize(), params, with);
				if (!r.valid() || r.hit().x() != c[0] || r.hit().y() != c[1] || r.hit().z() != c[2]) continue;
				AimRegion.Result aim = AimRegion.compute(eye, c[0], c[1], c[2], params, with, SCREEN_GRID);
				if (!aim.reachable() || aim.bestMarginDegrees() < minMargin) continue;
				if (!earlierWarpsStillLand(warps, i, with, params)) continue;
				options.add(new Option(c, aim, Etherwarp.landingFeet(c[0], c[1], c[2]).sub(goalFeet).length()));
			}
			options.sort(Comparator.comparingDouble((Option o) -> -o.aim().bestMarginDegrees()).thenComparingDouble(Option::toGoal));
			for (Option o : options) {
				long key = Lane.pack(o.c()[0], o.c()[1], o.c()[2]);
				PlacementPlanner.Plan plan = placer.plan(List.of(goalBlock), Set.of(key), Set.of(),
						(SolidQuery post, Set<Long> added) -> earlierWarpsStillLand(warps, fi, post, params));
				PlacementPlanner.Step step = plan.steps().stream()
						.filter(s -> s.block()[0] == o.c()[0] && s.block()[1] == o.c()[1] && s.block()[2] == o.c()[2]).findFirst().orElse(null);
				if (step == null || plan.steps().size() != 1) continue; // only single-block suggestions
				AimRegion.Result fine = AimRegion.compute(eye, o.c()[0], o.c()[1], o.c()[2], params, plus(world, o.c()), cfg.etherwarp.aimSamplesPerFace);
				double saved = 0;
				for (int j = i + 1; j < n; j++) saved += warps.get(j).seconds();
				return new Suggestion(o.c(), i, n - 1 - i, saved, from.fromFeet(), fine.reachable() ? fine.bestPoint() : o.aim().bestPoint(),
						fine.reachable() ? fine.bestMarginDegrees() : o.aim().bestMarginDegrees(),
						Etherwarp.landingFeet(o.c()[0], o.c()[1], o.c()[2]), step, "");
			}
		}
		return Suggestion.none("no single block saves a warp here (the route's last warp already comes from the furthest spot that sees the Topaz crystal area)");
	}

	/** Air blocks near the goal you could place a block in and then stand on: room above, a solid neighbour, placing allowed. */
	static List<int[]> candidates(SetupWorld world, Vec3 goalFeet, double radius, int air) {
		List<int[]> out = new ArrayList<>();
		int r = (int) Math.ceil(radius) + 1;
		int gx = (int) Math.floor(goalFeet.x()), gy = (int) Math.floor(goalFeet.y()) - 1, gz = (int) Math.floor(goalFeet.z());
		for (int x = gx - r; x <= gx + r; x++)
			for (int y = gy - r; y <= gy + r; y++)
				for (int z = gz - r; z <= gz + r; z++) {
					if (Etherwarp.landingFeet(x, y, z).sub(goalFeet).length() > radius) continue;
					if (world.solid(x, y, z) || !world.placeAllowed(x, y, z) || !world.explored(x, z)) continue;
					if (!Etherwarp.hasRoomAbove(x, y, z, air, world::solid)) continue;
					if (!(world.solid(x - 1, y, z) || world.solid(x + 1, y, z) || world.solid(x, y - 1, z)
							|| world.solid(x, y, z - 1) || world.solid(x, y, z + 1))) continue;
					out.add(new int[]{x, y, z});
				}
		return out;
	}

	/** Every etherwarp before {@code upTo} still lands on its own target with the new block there. */
	static boolean earlierWarpsStillLand(List<LegPlanner.Warp> warps, int upTo, SolidQuery with, Etherwarp.Params params) {
		for (int j = 0; j < upTo; j++) {
			LegPlanner.Warp w = warps.get(j);
			if (w.isTeleport()) continue;
			Etherwarp.Result r = Etherwarp.warp(Etherwarp.eye(w.fromFeet(), params.eyeHeight()), w.aimDir(), params, with);
			if (!r.valid() || r.hit().x() != w.tx() || r.hit().y() != w.ty() || r.hit().z() != w.tz()) return false;
		}
		return true;
	}

	private static SolidQuery plus(SetupWorld world, int[] c) {
		return (x, y, z) -> (x == c[0] && y == c[1] && z == c[2]) || world.solid(x, y, z);
	}
}
