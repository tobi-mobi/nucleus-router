package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.plan.LegPlanner;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.LegDesigner;
import dev.nucleusrouter.core.setup.SetupWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * When the setup doesn't go as planned (SPEC §5 setup mode, roadmap 4.7). Pure logic. Re-planning happens in
 * setup mode only; run mode never re-plans.
 * <ul>
 *   <li>{@link #judge}: what a throw really did, from the blocks that broke in the seconds after it.</li>
 *   <li>{@link #replanBombs}: the leg's remaining bomb work, planned again from the terrain as it is now (only that
 *       leg; the lanes stay the same). Around a bomb that fizzled no bomb is planned again.</li>
 *   <li>{@link #leftovers}: before the placing, blocks a lane needs gone that are still there become mining.</li>
 *   <li>{@link #verify}: every planned warp against the world now ("verify route").</li>
 * </ul>
 */
public final class SetupRepair {
	/** A blast centred further than this from the planned centre "missed". */
	public static final double MISSED_BY = 3;
	/** Around a bomb that fizzled, no blast centre is planned within this distance. */
	public static final double FIZZLE_RADIUS = 5;

	public enum Outcome {
		/** Every block it should break is gone. */
		DONE,
		/** Nothing broke: centred in a structure zone (or the throw went somewhere unloaded). */
		FIZZLED,
		/** Blocks broke, but centred more than {@link #MISSED_BY} blocks from the plan. */
		MISSED,
		/** Centred about right, but blocks it should break are still there (blast shape differs). */
		PARTIAL
	}

	/**
	 * @param centre   centre of the blocks that broke (null if none)
	 * @param broken   blocks that broke
	 * @param leftover blocks it should have broken that are still there
	 * @param offBy    distance from the planned centre (0 if none broke)
	 */
	public record Judgement(Outcome outcome, Vec3 centre, int broken, int leftover, double offBy) {}

	private SetupRepair() {}

	/** What a throw did: {@code broken} = blocks that turned to air after it, {@code now} = the world now. */
	public static Judgement judge(SetupSteps.Step throwStep, List<int[]> broken, SolidQuery now) {
		int leftover = 0;
		for (int[] q : throwStep.blocks()) if (now.solid(q[0], q[1], q[2])) leftover++;
		if (broken.isEmpty()) return new Judgement(leftover == 0 && !throwStep.blocks().isEmpty() ? Outcome.DONE : Outcome.FIZZLED, null, 0, leftover, 0);
		double sx = 0, sy = 0, sz = 0;
		for (int[] q : broken) { sx += q[0] + 0.5; sy += q[1] + 0.5; sz += q[2] + 0.5; }
		Vec3 c = new Vec3(sx / broken.size(), sy / broken.size(), sz / broken.size());
		double off = c.sub(throwStep.bomb().centre()).length();
		Outcome o = leftover == 0 ? Outcome.DONE : off > MISSED_BY ? Outcome.MISSED : Outcome.PARTIAL;
		return new Judgement(o, c, broken.size(), leftover, off);
	}

	/**
	 * The leg's bomb work still to do, planned again on {@code now} (the terrain as it is): the blocks the leg needs
	 * gone that are still there. No blast is centred within {@link #FIZZLE_RADIUS} of a bomb that fizzled.
	 * {@code numbers} = {last B, last M}, advanced for the new steps.
	 */
	public static List<SetupSteps.Step> replanBombs(int leg, LegDesigner.Design d, SetupWorld now, List<Vec3> fizzled, NrConfig cfg, int[] numbers) {
		List<Long> remaining = new ArrayList<>();
		for (long k : d.toClear()) {
			int[] q = Lane.unpack(k);
			if (now.solid(q[0], q[1], q[2])) remaining.add(k);
		}
		SetupWorld world = noBombNear(now, fizzled);
		BombPlanner.Plan plan = new BombPlanner(world, cfg).plan(d.lanes().get(0).spec().origin(), LegDesigner.path(d), new java.util.LinkedHashSet<>(remaining));
		return SetupSteps.bombSteps(leg, plan, remaining, cfg.bombs.blastRadius, numbers);
	}

	/** {@code world} where every spot within {@link #FIZZLE_RADIUS} of a fizzled bomb counts as a structure zone. */
	static SetupWorld noBombNear(SetupWorld world, List<Vec3> fizzled) {
		if (fizzled.isEmpty()) return world;
		return new SetupWorld() {
			@Override public dev.nucleusrouter.core.world.VoxelClass classAt(int x, int y, int z) { return world.classAt(x, y, z); }
			@Override public boolean explored(int x, int z) { return world.explored(x, z); }
			@Override public boolean inZone(int x, int y, int z) {
				if (world.inZone(x, y, z)) return true;
				Vec3 p = new Vec3(x + 0.5, y + 0.5, z + 0.5);
				for (Vec3 f : fizzled) if (p.sub(f).length() <= FIZZLE_RADIUS) return true;
				return false;
			}
		};
	}

	/** Mining steps for blocks the designed legs need gone that are still there (after every bomb). */
	public static List<SetupSteps.Step> leftovers(List<LegDesigner.Design> legs, SolidQuery now, int lastM) {
		List<SetupSteps.Step> out = new ArrayList<>();
		for (int i = 0; i < legs.size(); i++) {
			List<Long> left = new ArrayList<>();
			for (long k : legs.get(i).toClear()) {
				int[] q = Lane.unpack(k);
				if (now.solid(q[0], q[1], q[2])) left.add(k);
			}
			for (List<int[]> g : SetupSteps.groups(left))
				out.add(new SetupSteps.Step(SetupSteps.Kind.MINE, "M" + (++lastM), i, null, g, null));
		}
		return out;
	}

	// ------------------------------------------------------------------ verify route

	/**
	 * One planned etherwarp to check.
	 *
	 * @param where  for messages ("King Yolkar to Goblin Queen's Den, warp 3")
	 * @param from   feet before the warp
	 * @param dir    planned look direction
	 * @param target the block it must land on
	 */
	public record WarpCheck(String where, Vec3 from, Vec3 dir, int[] target) {}

	/** A planned warp that doesn't work now. */
	public record Failure(WarpCheck warp, String why, int[] hit) {}

	/** The warps of designed lanes (each lane: same direction every warp). */
	public static List<WarpCheck> lanes(String name, List<LegDesigner.PlannedLane> lanes) {
		List<WarpCheck> out = new ArrayList<>();
		int n = 0;
		for (LegDesigner.PlannedLane l : lanes)
			for (int k = 1; k <= l.spec().warps(); k++) {
				int[] from = l.spec().target(k - 1);
				out.add(new WarpCheck(name + ", warp " + (++n), Etherwarp.landingFeet(from[0], from[1], from[2]), l.direction(), l.spec().target(k)));
			}
		return out;
	}

	/** The etherwarps of a leg planned on the terrain ({@code /nr route}); normal-teleport stretches aren't checked. */
	public static List<WarpCheck> terrain(String name, LegPlanner.Leg leg) {
		List<WarpCheck> out = new ArrayList<>();
		int n = 0;
		for (LegPlanner.Warp w : leg.warps()) {
			n++;
			if (w.isTeleport() || w.aimDir() == null) continue;
			out.add(new WarpCheck(name + ", warp " + n, w.fromFeet(), w.aimDir(), new int[]{w.tx(), w.ty(), w.tz()}));
		}
		return out;
	}

	/** Every warp that doesn't land on its target in {@code now}, with why. */
	public static List<Failure> verify(List<WarpCheck> warps, Etherwarp.Params p, SolidQuery now) {
		List<Failure> out = new ArrayList<>();
		for (WarpCheck w : warps) {
			Etherwarp.Result r = Etherwarp.warp(Etherwarp.eye(w.from(), p.eyeHeight()), w.dir(), p, now);
			int[] t = w.target();
			int[] hit = r.hit() != null ? new int[]{r.hit().x(), r.hit().y(), r.hit().z()} : null;
			if (r.outcome() == Etherwarp.Outcome.NO_TARGET_IN_RANGE)
				out.add(new Failure(w, now.solid(t[0], t[1], t[2]) ? "nothing hit in range" : "the target block is missing", null));
			else if (!r.hit().isBlock(t[0], t[1], t[2]))
				out.add(new Failure(w, now.solid(t[0], t[1], t[2]) ? "a block is in the way" : "the target block is missing", hit));
			else if (!r.valid())
				out.add(new Failure(w, "no room to land on the target (something above it)", hit));
		}
		return out;
	}
}
