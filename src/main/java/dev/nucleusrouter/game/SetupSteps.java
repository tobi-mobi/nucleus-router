package dev.nucleusrouter.game;

import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.setup.BombPlanner;
import dev.nucleusrouter.core.setup.PlacementPlanner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The guided setup as a checklist (SPEC §5 setup mode, roadmap 4.6). Pure logic.
 * <p>
 * Order: for each leg in route order its bomb throws and then the blocks no bomb can reach (mining); after every
 * leg's bombs, all placements (a blast would break placed blocks). Each step knows how to see that it's done in
 * the world as it is now:
 * <ul>
 *   <li><b>Throw</b>: every block this throw is meant to break is gone (by this throw or others nearby). The
 *       blast model matched real throws exactly (confirmed 2026-11-24), so a throw that leaves blocks standing is
 *       worth a look: the step stays open until you confirm it with the key. A looser rule ("most blocks gone")
 *       was tried and let overlapping blasts tick throws off with needed blocks still standing.</li>
 *   <li><b>Mine</b>: all of its blocks are gone.</li>
 *   <li><b>Place</b>: the block is there.</li>
 * </ul>
 * Steps are only judged where the terrain is loaded, and placements only once every bomb and mining step is
 * done. A step that was done stays done (no re-planning here: that is
 * 4.7). Steps can be done out of order; the current step is the first one not done.
 */
public final class SetupSteps {
	public enum Kind { THROW, MINE, PLACE }

	/** Blocks to mine are grouped: up to this many, each within this distance of the group's first block. */
	public static final int MINE_GROUP_MAX = 12;
	public static final double MINE_GROUP_RADIUS = 4;

	/**
	 * One thing to do.
	 *
	 * @param number   shown to the player: B1.. for throws, M1.. for mining, P1.. for placing
	 * @param leg      which leg (index into the plan's setups) it belongs to
	 * @param bomb     THROW: the throw
	 * @param blocks   THROW: the blocks it should break; MINE: the blocks to mine; PLACE: the block to place
	 * @param place    PLACE: the placement
	 */
	public record Step(Kind kind, String number, int leg, BombPlanner.Throw bomb, List<int[]> blocks, PlacementPlanner.Step place) {
		/** Where you do it from (stand spot), for distances and markers. */
		public Vec3 where() {
			return switch (kind) {
				case THROW -> bomb.standFeet();
				case PLACE -> place.standFeet();
				case MINE -> new Vec3(blocks.get(0)[0] + 0.5, blocks.get(0)[1] + 0.5, blocks.get(0)[2] + 0.5);
			};
		}
	}

	/** Is the terrain at this block loaded (only then can a step be judged)? */
	@FunctionalInterface
	public interface Loaded { boolean loaded(int x, int y, int z); }

	/** Changed when a leg is re-planned (4.7); client thread only. */
	private List<Step> steps;
	private boolean[] done;
	private int current;
	/** The designed legs, by {@link Step#leg()}, for re-planning (empty for ready-made test steps). */
	private final List<dev.nucleusrouter.core.setup.LegDesigner.Design> designs;

	private SetupSteps(List<Step> steps, List<dev.nucleusrouter.core.setup.LegDesigner.Design> designs) {
		this.steps = List.copyOf(steps);
		this.done = new boolean[steps.size()];
		this.designs = List.copyOf(designs);
	}

	/** The checklist for a planned lobby. */
	public static SetupSteps of(LobbySetup.Result plan, double blastRadius) {
		List<Step> out = new ArrayList<>();
		int[] numbers = {0, 0};
		List<LobbySetup.LegSetup> legs = plan.setups();
		List<dev.nucleusrouter.core.setup.LegDesigner.Design> designs = new ArrayList<>();
		for (int i = 0; i < legs.size(); i++) {
			LobbySetup.LegSetup leg = legs.get(i);
			designs.add(leg.design());
			out.addAll(bombSteps(i, leg.bombs(), leg.design().toClear(), blastRadius, numbers));
		}
		List<PlacementPlanner.Plan> placing = new ArrayList<>();
		for (LobbySetup.LegSetup leg : legs) placing.add(leg.placing());
		out.addAll(placeSteps(placing, 0));
		return new SetupSteps(out, designs);
	}

	/**
	 * Throw and mining steps for one leg's bomb plan. Each throw is meant to break the blocks of {@code toClear} in
	 * its blast that earlier throws haven't. {@code numbers} = {last B number, last M number}, advanced here.
	 */
	public static List<Step> bombSteps(int leg, BombPlanner.Plan plan, java.util.Collection<Long> toClear, double blastRadius, int[] numbers) {
		List<Step> out = new ArrayList<>();
		Set<Long> remaining = new LinkedHashSet<>(toClear);
		for (BombPlanner.Throw t : plan.throwsInOrder()) {
			List<int[]> blocks = new ArrayList<>();
			for (var it = remaining.iterator(); it.hasNext(); ) {
				long k = it.next();
				int[] q = Lane.unpack(k);
				if (inBlast(t.centre(), blastRadius, q)) { blocks.add(q); it.remove(); }
			}
			out.add(new Step(Kind.THROW, "B" + (++numbers[0]), leg, t, List.copyOf(blocks), null));
		}
		for (List<int[]> group : groups(plan.mine())) out.add(new Step(Kind.MINE, "M" + (++numbers[1]), leg, null, group, null));
		return out;
	}

	/** Placement steps for every leg's placing plan (index = leg), numbered from {@code lastP} + 1. */
	public static List<Step> placeSteps(List<PlacementPlanner.Plan> placing, int lastP) {
		List<Step> out = new ArrayList<>();
		for (int i = 0; i < placing.size(); i++)
			for (PlacementPlanner.Step s : placing.get(i).steps())
				out.add(new Step(Kind.PLACE, "P" + (++lastP), i, null, List.of(s.block()), s));
		return out;
	}

	/** From ready-made steps (tests). */
	public static SetupSteps of(List<Step> steps) { return new SetupSteps(steps, List.of()); }

	public List<dev.nucleusrouter.core.setup.LegDesigner.Design> designs() { return designs; }

	/** The highest number used so far for a kind (B12 -> 12), so new steps continue from it. */
	public int lastNumber(Kind kind) {
		int max = 0;
		for (Step s : steps)
			if (s.kind() == kind) {
				try { max = Math.max(max, Integer.parseInt(s.number().substring(1))); } catch (NumberFormatException ignored) {}
			}
		return max;
	}

	/** Every throw and mining step is done (the placing can start). */
	public boolean bombWorkDone() {
		for (int i = 0; i < steps.size(); i++) if (steps.get(i).kind() != Kind.PLACE && !done[i]) return false;
		return true;
	}

	/**
	 * Re-planned bomb work for one leg (4.7): its throw and mining steps not done yet are replaced by {@code fresh},
	 * at the place of the first one replaced (or just before the placing).
	 */
	public void replaceOpenBombWork(int leg, List<Step> fresh) {
		List<Step> out = new ArrayList<>();
		List<Boolean> d = new ArrayList<>();
		int insertAt = -1;
		for (int i = 0; i < steps.size(); i++) {
			Step s = steps.get(i);
			if (s.leg() == leg && s.kind() != Kind.PLACE && !done[i]) {
				if (insertAt < 0) insertAt = out.size();
				continue;
			}
			out.add(s);
			d.add(done[i]);
		}
		if (insertAt < 0) insertAt = firstPlace(out);
		out.addAll(insertAt, fresh);
		for (int i = 0; i < fresh.size(); i++) d.add(insertAt, false);
		set(out, d);
	}

	/** Extra steps (left-over mining) just before the placing. */
	public void insertBeforePlacing(List<Step> fresh) {
		List<Step> out = new ArrayList<>(steps);
		List<Boolean> d = new ArrayList<>();
		for (boolean b : done) d.add(b);
		int at = firstPlace(out);
		out.addAll(at, fresh);
		for (int i = 0; i < fresh.size(); i++) d.add(at, false);
		set(out, d);
	}

	/** Re-planned placing (4.7): every placement not done yet is replaced by {@code fresh}. */
	public void replaceOpenPlacements(List<Step> fresh) {
		List<Step> out = new ArrayList<>();
		List<Boolean> d = new ArrayList<>();
		for (int i = 0; i < steps.size(); i++) {
			if (steps.get(i).kind() == Kind.PLACE && !done[i]) continue;
			out.add(steps.get(i));
			d.add(done[i]);
		}
		out.addAll(fresh);
		for (int i = 0; i < fresh.size(); i++) d.add(false);
		set(out, d);
	}

	private static int firstPlace(List<Step> list) {
		for (int i = 0; i < list.size(); i++) if (list.get(i).kind() == Kind.PLACE) return i;
		return list.size();
	}

	private void set(List<Step> out, List<Boolean> d) {
		steps = List.copyOf(out);
		done = new boolean[d.size()];
		for (int i = 0; i < d.size(); i++) done[i] = d.get(i);
		moveToFirstOpen();
	}

	static boolean inBlast(Vec3 c, double r, int[] q) {
		double dx = q[0] + 0.5 - c.x(), dy = q[1] + 0.5 - c.y(), dz = q[2] + 0.5 - c.z();
		return dx * dx + dy * dy + dz * dz <= r * r + 1e-9;
	}

	/** Nearby mining blocks together, in the planned order. */
	static List<List<int[]>> groups(List<Long> mine) {
		List<List<int[]>> out = new ArrayList<>();
		List<int[]> cur = null;
		for (long k : mine) {
			int[] q = Lane.unpack(k);
			if (cur != null && cur.size() < MINE_GROUP_MAX) {
				int[] f = cur.get(0);
				double dx = q[0] - f[0], dy = q[1] - f[1], dz = q[2] - f[2];
				if (dx * dx + dy * dy + dz * dz <= MINE_GROUP_RADIUS * MINE_GROUP_RADIUS) { cur.add(q); continue; }
			}
			cur = new ArrayList<>();
			cur.add(q);
			out.add(cur);
		}
		return out;
	}

	public List<Step> steps() { return steps; }
	public int size() { return steps.size(); }
	public boolean done(int i) { return done[i]; }
	/** The first step not done (size() when finished). */
	public int current() { return current; }
	public boolean finished() { return current >= steps.size(); }
	public Step currentStep() { return finished() ? null : steps.get(current); }

	public int doneCount() {
		int n = 0;
		for (boolean d : done) if (d) n++;
		return n;
	}

	public int doneCount(Kind kind) {
		int n = 0;
		for (int i = 0; i < steps.size(); i++) if (done[i] && steps.get(i).kind() == kind) n++;
		return n;
	}

	public int count(Kind kind) {
		return (int) steps.stream().filter(s -> s.kind() == kind).count();
	}

	/**
	 * Checks the steps from the current one up to {@code window} ahead against the world now, marks those that are
	 * done, and moves the current step to the first one not done. Returns how many steps were newly marked done.
	 */
	public int update(SolidQuery world, Loaded loaded, int window) {
		int newly = 0;
		// Placements are only judged once every throw and mining step is done: before the blasts, the block a
		// placement replaces may still be standing (it looks "placed" but a bomb is still to come)
		boolean bombsDone = bombWorkDone();
		for (int i = current; i < Math.min(steps.size(), current + window); i++) {
			if (done[i]) continue;
			if (steps.get(i).kind() == Kind.PLACE && !bombsDone) break;
			if (isDone(steps.get(i), world, loaded)) { done[i] = true; newly++; }
		}
		moveToFirstOpen();
		return newly;
	}

	/** Confirm or skip the current step by hand (the keybind). Returns the step, or null if finished. */
	public Step confirmCurrent() {
		if (finished()) return null;
		Step s = steps.get(current);
		done[current] = true;
		moveToFirstOpen();
		return s;
	}

	/** Go back: un-mark the last done step before the current one. Returns it, or null. */
	public Step back() {
		for (int i = Math.min(current, steps.size()) - 1; i >= 0; i--) {
			if (done[i]) {
				done[i] = false;
				current = i;
				return steps.get(i);
			}
		}
		return null;
	}

	private void moveToFirstOpen() {
		current = 0;
		while (current < steps.size() && done[current]) current++;
	}

	/** Whether a step is done in {@code world}. Unloaded terrain never counts as done. */
	public static boolean isDone(Step s, SolidQuery world, Loaded loaded) {
		for (int[] q : s.blocks()) if (!loaded.loaded(q[0], q[1], q[2])) return false;
		return switch (s.kind()) {
			case PLACE -> { int[] q = s.blocks().get(0); yield world.solid(q[0], q[1], q[2]); }
			case MINE -> s.blocks().stream().noneMatch(q -> world.solid(q[0], q[1], q[2]));
			case THROW -> !s.blocks().isEmpty() && s.blocks().stream().noneMatch(q -> world.solid(q[0], q[1], q[2]));
		};
	}
}
