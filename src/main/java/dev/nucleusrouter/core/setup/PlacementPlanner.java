package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.AimRegion;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Block-placement planner (SPEC §2.5, §3.4 step 7, roadmap 4.4): how to place every target block a designed
 * leg needs, in order along the leg.
 * <p>
 * Placements come <b>after all of the leg's bombs</b> (a blast would break placed blocks again). For each block:
 * <ol>
 *   <li>a <b>support face</b>: a solid neighbour to click on, on the side facing you;</li>
 *   <li>a <b>stand spot</b> that exists at that point of the setup (floor under, room for your body, explored,
 *       not inside the block being placed), with the clicked face within {@code placing.reach} of your eye and
 *       nothing in between. Spots near the previous one are preferred, so you walk less;</li>
 *   <li>if no neighbour is solid (a target floating in a blasted cave), a <b>helper block</b> under or beside it
 *       is placed first (up to {@link #MAX_HELPERS} in a chain), but only if every lane still works with it.
 *       Never above a target: the 2 blocks above a target must stay empty.</li>
 *   <li>if that fails too (typically: the bombs left the target floating high over a crater), a <b>pillar</b>
 *       straight up from the ground under it: stand on the ground, jump and place under yourself until the
 *       top block, the target, is in place (at most {@code setup.maxPillarBlocks}), again only if every lane
 *       still works with the pillar. You end up standing on the target.</li>
 *   <li>if a pillar under it would get in a lane's way: a pillar <b>beside</b> it up to the same height; on top,
 *       sneak to the edge and place the target against the pillar's side.</li>
 * </ol>
 * Blocks with no way to place them are reported, not guessed. No block (target, helper or pillar) goes where the
 * game refuses placing ({@link SetupWorld#placeAllowed}: within 50 blocks of the Jade, Sapphire and Amber crystals,
 * inside the Jungle Temple; confirmed 2026-11-26). Placements inside a bomb zone are still flagged.
 * <p>
 * The "+2 / +1" targets (a block 2 ahead at eye level or 1 ahead) are not special here: the lane designer
 * chooses where targets go (raised targets give a bigger aim margin) and this planner places whatever it chose.
 */
public final class PlacementPlanner {
	/** Vanilla standing eye height. You place blocks standing up. */
	public static final double STANDING_EYE = 1.62;
	/** Longest chain of helper blocks under or beside a floating target. */
	public static final int MAX_HELPERS = 3;

	/**
	 * One block to place.
	 *
	 * @param block         where the new block goes
	 * @param support       the block you click on
	 * @param face          which face of {@code support} you click (it faces {@code block})
	 * @param standFeet     where to stand (feet, block centre)
	 * @param clickPoint    the point to aim at (centre of the clicked face)
	 * @param look          look direction from your standing eye to {@code clickPoint}
	 * @param helper        a helper block (not a lane target), only there to hold the next block up
	 * @param nearStructure inside a structure's zone: the game may refuse it
	 * @param pillar        placed by pillaring: jump and place under yourself ({@code standFeet} is then the
	 *                      block's own cell: where your feet are when it goes in)
	 * @param edge          placed from the edge of a pillar's top: sneak towards it ({@code standFeet} is off-centre)
	 */
	public record Step(int[] block, int[] support, Face face, Vec3 standFeet, Vec3 clickPoint, Vec3 look, boolean helper,
			boolean nearStructure, boolean pillar, boolean edge) {}

	/**
	 * @param steps      placements in order (helpers just before the block they hold)
	 * @param impossible target blocks with no way to place them, packed
	 */
	public record Plan(List<Step> steps, List<Long> impossible, double millis) {
		public int helpers() { return (int) steps.stream().filter(Step::helper).count(); }
		public int targets() { return steps.size() - helpers(); }
		public int nearStructure() { return (int) steps.stream().filter(Step::nearStructure).count(); }
		public int pillarBlocks() { return (int) steps.stream().filter(Step::pillar).count(); }
	}

	/** Checks the lanes in a world with blocks added ({@code added}: the helper or pillar blocks being tried). */
	@FunctionalInterface
	public interface LanesWork {
		boolean test(SolidQuery post, Set<Long> added);
	}

	private final SetupWorld world;
	private final double reach;
	private final int maxPillar;

	public PlacementPlanner(SetupWorld world, NrConfig cfg) {
		this.world = world;
		this.reach = cfg.placing.reach;
		this.maxPillar = cfg.setup.maxPillarBlocks;
	}

	/**
	 * Plans the placements for a leg.
	 *
	 * @param path       the leg (start, then each lane's end), for the order of work
	 * @param placements target blocks to place, packed
	 * @param cleared    blocks the bombs and mining removed, packed
	 * @param lanesWork  checks a world with helper blocks added: true if every lane still works in it
	 */
	public Plan plan(List<int[]> path, Set<Long> placements, Set<Long> cleared, Predicate<SolidQuery> lanesWork) {
		return plan(path, placements, cleared, (post, added) -> lanesWork.test(post));
	}

	/** Same; the lane check also gets the blocks being added, so it can skip lanes far from them. */
	public Plan plan(List<int[]> path, Set<Long> placements, Set<Long> cleared, LanesWork lanesWork) {
		long t0 = System.nanoTime();
		Set<Long> placed = new HashSet<>();
		SolidQuery now = (x, y, z) -> {
			long k = Lane.pack(x, y, z);
			if (placed.contains(k)) return true;
			if (cleared.contains(k)) return false;
			return world.solid(x, y, z);
		};
		List<Long> order = new ArrayList<>(placements);
		Map<Long, Double> along = new HashMap<>();
		for (long k : order) along.put(k, BombPlanner.progress(path, Lane.unpack(k)));
		order.sort(Comparator.comparingDouble(along::get));

		List<Step> steps = new ArrayList<>();
		List<Long> impossible = new ArrayList<>();
		Vec3 last = path.isEmpty() ? null : feetOn(path.get(0));
		for (long k : order) {
			if (now.solid(Lane.unpack(k)[0], Lane.unpack(k)[1], Lane.unpack(k)[2])) continue; // already there (e.g. a helper)
			List<Step> chain = place(Lane.unpack(k), now, placements, last, 0, placed, Set.of(), lanesWork, cleared);
			if (chain == null) chain = pillar(Lane.unpack(k), now, placements, placed, lanesWork, cleared);
			if (chain == null) { impossible.add(k); continue; }
			for (Step s : chain) {
				steps.add(s);
				placed.add(Lane.pack(s.block()[0], s.block()[1], s.block()[2]));
				last = s.standFeet();
			}
		}
		return new Plan(steps, impossible, (System.nanoTime() - t0) / 1e6);
	}

	/**
	 * The steps that place block {@code b} (helpers first), or null. {@code placed} (blocks already placed) is only
	 * read here; the caller adds the steps once the whole chain is known. {@code reserved}: helpers chosen further
	 * up the chain, not placed yet, counted when checking the lanes.
	 */
	private List<Step> place(int[] b, SolidQuery now, Set<Long> targets, Vec3 last, int depth, Set<Long> placed,
			Set<Long> reserved, LanesWork lanesWork, Set<Long> cleared) {
		if (!world.placeAllowed(b[0], b[1], b[2])) return null; // near a crystal or in the temple: the game refuses it
		Step direct = directStep(b, now, last, depth > 0);
		if (direct != null) return new ArrayList<>(List.of(direct));
		if (depth >= MAX_HELPERS) return null;
		// Helpers only help next to something solid; a target floating in the open goes straight to a pillar
		if (depth == 0 && !solidNear(b, MAX_HELPERS, now)) return null;
		// No neighbour to click on: try a helper under it, then beside it (never above: landing room)
		for (Face f : HELPER_FACES) {
			int[] h = {b[0] + f.dx, b[1] + f.dy, b[2] + f.dz};
			long hk = Lane.pack(h[0], h[1], h[2]);
			if (now.solid(h[0], h[1], h[2]) || targets.contains(hk)) continue;
			if (!world.explored(h[0], h[2])) continue;
			// Lanes must still work with this helper (and the ones already chosen) in the world
			Set<Long> trial = new HashSet<>(placed);
			trial.addAll(reserved);
			trial.add(hk);
			SolidQuery withHelper = (x, y, z) -> {
				long k = Lane.pack(x, y, z);
				return trial.contains(k) || targets.contains(k) || (!cleared.contains(k) && world.solid(x, y, z));
			};
			Set<Long> added = new HashSet<>(reserved);
			added.add(hk);
			if (!lanesWork.test(withHelper, added)) continue;
			// Deeper helpers are checked with this one (and those above it) counted too
			Set<Long> reservedWithH = new HashSet<>(reserved);
			reservedWithH.add(hk);
			List<Step> chain = place(h, now, targets, last, depth + 1, placed, reservedWithH, lanesWork, cleared);
			if (chain == null) continue;
			Set<Long> after = new HashSet<>(placed);
			for (Step s : chain) after.add(Lane.pack(s.block()[0], s.block()[1], s.block()[2]));
			SolidQuery nowWithChain = (x, y, z) -> after.contains(Lane.pack(x, y, z)) || now.solid(x, y, z);
			Step top = directStep(b, nowWithChain, chain.get(chain.size() - 1).standFeet(), depth > 0);
			if (top == null) continue;
			chain.add(top);
			return chain;
		}
		return null;
	}

	private static final Face[] HELPER_FACES = {Face.DOWN, Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST};

	/** Placing {@code b} against a neighbour that's solid now, from the best stand spot, or null. */
	private Step directStep(int[] b, SolidQuery now, Vec3 last, boolean helper) {
		Step best = null;
		double bestScore = Double.MAX_VALUE;
		int r = (int) Math.ceil(reach) + 1;
		for (Face f : Face.values()) {
			// The support is the neighbour on side f; you click its face pointing back at b
			int[] s = {b[0] + f.dx, b[1] + f.dy, b[2] + f.dz};
			if (!now.solid(s[0], s[1], s[2])) continue;
			Face clicked = opposite(f);
			Vec3 click = new Vec3(b[0] + 0.5 + f.dx * 0.5, b[1] + 0.5 + f.dy * 0.5, b[2] + 0.5 + f.dz * 0.5);
			for (int dx = -r; dx <= r; dx++)
				for (int dy = -r - 2; dy <= r; dy++)
					for (int dz = -r; dz <= r; dz++) {
						int fx = b[0] + dx, fy = b[1] + dy, fz = b[2] + dz;
						// Cheap tests first: reach and which side you're on, then the world
						Vec3 feet = new Vec3(fx + 0.5, fy, fz + 0.5);
						Vec3 eye = feet.add(new Vec3(0, STANDING_EYE, 0));
						Vec3 to = click.sub(eye);
						double d = to.length();
						if (d > reach || d < 1e-6) continue;
						if (!AimRegion.faceVisible(clicked, eye, s[0], s[1], s[2])) continue;
						if (!standable(fx, fy, fz, now, b)) continue;
						Vec3 dir = to.scale(1 / d);
						VoxelRay.Hit hit = VoxelRay.cast(eye, dir, d + 0.5, now);
						if (hit == null || !hit.isBlock(s[0], s[1], s[2])) continue;
						double score = (last == null ? 0 : feet.sub(last).length()) + d * 0.1;
						if (score < bestScore) {
							bestScore = score;
							best = new Step(b.clone(), s, clicked, feet, click, dir, helper, world.inZone(b[0], b[1], b[2]), false, false);
						}
					}
		}
		return best;
	}

	/**
	 * A pillar from the ground straight under {@code b} up to {@code b}, or null: every cell between must be
	 * empty, the ground cell must have room for you to stand, and the lanes must still work with the pillar.
	 */
	private List<Step> pillar(int[] b, SolidQuery now, Set<Long> targets, Set<Long> placed, LanesWork lanesWork, Set<Long> cleared) {
		List<Step> under = column(b, 0, 0, now, targets, placed, lanesWork, cleared);
		if (under != null) return under;
		// Straight under it would get in a lane's way: pillar up beside it, sneak to the edge and place it against
		// the pillar's side
		for (Face f : SIDES) {
			List<Step> side = column(b, f.dx, f.dz, now, targets, placed, lanesWork, cleared);
			if (side != null) return side;
		}
		return null;
	}

	private static final Face[] SIDES = {Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST};
	/** Sneaking at an edge, your centre can be this far past the middle of the block you stand on. */
	static final double EDGE_OFFSET = 0.7;

	/**
	 * A pillar in column (b.x + sx, b.z + sz) from the ground up to b's height. Under b (sx = sz = 0) its top block
	 * is b itself; beside b, b is then placed against the pillar's side, sneaking at the edge on top of it.
	 */
	private List<Step> column(int[] b, int sx, int sz, SolidQuery now, Set<Long> targets, Set<Long> placed, LanesWork lanesWork, Set<Long> cleared) {
		boolean under = sx == 0 && sz == 0;
		int cx = b[0] + sx, cz = b[2] + sz, top = b[1];
		if (!world.explored(cx, cz)) return null;
		if (!under && (now.solid(cx, top, cz) || now.solid(cx, top + 1, cz) || now.solid(cx, top + 2, cz)
				|| targets.contains(Lane.pack(cx, top, cz)))) return null;
		int ground = top - 1;
		while (ground >= top - maxPillar - 1 && !now.solid(cx, ground, cz)) ground--;
		if (!now.solid(cx, ground, cz)) return null;
		int height = top - ground; // column blocks (b included when under)
		if (height < 1 || height > maxPillar) return null;
		for (int y = ground + 1; y <= top; y++) if (!world.placeAllowed(cx, y, cz)) return null;
		if (!world.placeAllowed(b[0], b[1], b[2])) return null;
		Set<Long> added = new HashSet<>();
		for (int y = ground + 1; y <= top; y++) if (!(under && y == top)) added.add(Lane.pack(cx, y, cz));
		Set<Long> trial = new HashSet<>(placed);
		trial.addAll(added);
		SolidQuery withPillar = (x, y, z) -> {
			long k = Lane.pack(x, y, z);
			return trial.contains(k) || targets.contains(k) || (!cleared.contains(k) && world.solid(x, y, z));
		};
		if (!added.isEmpty() && !lanesWork.test(withPillar, added)) return null;
		List<Step> out = new ArrayList<>();
		for (int y = ground + 1; y <= top; y++) {
			int[] blk = {cx, y, cz};
			int[] sup = {cx, y - 1, cz};
			Vec3 feet = new Vec3(cx + 0.5, y, cz + 0.5);
			boolean isTarget = under && y == top;
			out.add(new Step(blk, sup, Face.UP, feet, feet, new Vec3(0, -1, 0), !isTarget, world.inZone(cx, y, cz), true, false));
		}
		if (!under) {
			// On top of the pillar, sneak towards b and click the pillar's side face that faces b
			Face clicked = faceOf(-sx, -sz);
			Vec3 feet = new Vec3(cx + 0.5 - EDGE_OFFSET * sx, top + 1, cz + 0.5 - EDGE_OFFSET * sz);
			Vec3 click = new Vec3(b[0] + 0.5 + sx * 0.5, b[1] + 0.5, b[2] + 0.5 + sz * 0.5);
			Vec3 eye = feet.add(new Vec3(0, STANDING_EYE, 0));
			if (click.sub(eye).length() > reach) return null;
			out.add(new Step(b.clone(), new int[]{cx, top, cz}, clicked, feet, click, click.sub(eye).normalize(), false,
					world.inZone(b[0], b[1], b[2]), false, true));
		}
		return out;
	}

	private static Face faceOf(int dx, int dz) {
		for (Face f : Face.values()) if (f.dx == dx && f.dy == 0 && f.dz == dz) return f;
		throw new IllegalArgumentException(dx + "," + dz);
	}


	/** Something solid within {@code r} blocks of {@code b} (not above it). */
	private static boolean solidNear(int[] b, int r, SolidQuery now) {
		for (int dx = -r; dx <= r; dx++)
			for (int dy = -r; dy <= 0; dy++)
				for (int dz = -r; dz <= r; dz++)
					if ((dx | dy | dz) != 0 && now.solid(b[0] + dx, b[1] + dy, b[2] + dz)) return true;
		return false;
	}

	/** Feet in (x, y, z): floor under, room for the body, explored, and not where the block goes. */
	private boolean standable(int x, int y, int z, SolidQuery now, int[] b) {
		if (x == b[0] && z == b[2] && (y == b[1] || y + 1 == b[1])) return false;
		return !now.solid(x, y, z) && !now.solid(x, y + 1, z) && now.solid(x, y - 1, z) && world.explored(x, z);
	}

	private static Face opposite(Face f) {
		return switch (f) {
			case DOWN -> Face.UP;
			case UP -> Face.DOWN;
			case NORTH -> Face.SOUTH;
			case SOUTH -> Face.NORTH;
			case WEST -> Face.EAST;
			case EAST -> Face.WEST;
		};
	}

	private static Vec3 feetOn(int[] block) { return new Vec3(block[0] + 0.5, block[1] + 1, block[2] + 0.5); }
}
