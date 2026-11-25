package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.bomb.BombThrow;
import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.Lane;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bomb planner (SPEC §3.4 steps 5-7, §2.4, roadmap 4.3): which Bob-omb throws clear a designed leg, in order.
 * <p>
 * Progressive greedy cover from the leg start: take the first block still to clear (in the leg's direction),
 * look for a spot to stand that <em>already exists at that point of the setup</em> (open now, after the throws
 * before it), and pick the throw from there that clears the most blocks still needed. The throw is simulated
 * with the confirmed rules: straight along your aim for {@code bombs.throwRange} (32), else straight down; the
 * blast is every block within {@code bombs.blastRadius} (7) of the centre of the block hit (dropped bombs:
 * {@code dropCentreBlocksAbove} higher). A throw whose blast centre is inside a structure zone fizzles, so it's
 * never planned. Blocks no legal throw can reach become "mine" steps.
 * <p>
 * Simplifications (documented): you can get to any open, explored stand spot (with the Aspect of the Void);
 * ores and gemstones broken by a blast count as solid (they respawn), so throws never rely on them being gone;
 * stand spots are kept out of the blast.
 */
public final class BombPlanner {
	/**
	 * One throw.
	 *
	 * @param standFeet   where to stand (feet, block centre)
	 * @param aimPoint    what to aim at (the centre of the block to hit)
	 * @param direction   look direction from your eye to {@code aimPoint}
	 * @param impactBlock the block the bomb lands on
	 * @param centre      blast centre
	 * @param dropped     the bomb reaches max range and drops
	 * @param clears      blocks this throw clears that the design needs gone
	 */
	public record Throw(Vec3 standFeet, Vec3 aimPoint, Vec3 direction, int[] impactBlock, Vec3 centre, boolean dropped, int clears) {}

	/**
	 * The plan for one leg.
	 *
	 * @param throwsInOrder bomb throws, in order
	 * @param mine          blocks to break by hand (no legal throw reaches them), packed
	 * @param blasted       every block the throws break for good (more than needed), packed
	 */
	public record Plan(List<Throw> throwsInOrder, List<Long> mine, Set<Long> blasted, double millis) {
		public int bombs() { return throwsInOrder.size(); }
	}

	private static final int STANDS_PER_STEP = 10;
	private static final int AIMS_PER_STAND = 16;
	private static final int BAND = 10;

	private final SetupWorld world;
	private final NrConfig cfg;
	private final double eyeHeight;
	private final double radius;
	private final int[][] ball;

	public BombPlanner(SetupWorld world, NrConfig cfg) {
		this.world = world;
		this.cfg = cfg;
		this.eyeHeight = cfg.etherwarp.eyeHeight;
		this.radius = cfg.bombs.blastRadius;
		int r = (int) Math.ceil(radius);
		List<int[]> offs = new ArrayList<>();
		for (int x = -r; x <= r; x++)
			for (int y = -r; y <= r; y++)
				for (int z = -r; z <= r; z++)
					if (x * x + y * y + z * z <= radius * radius + 1e-9) offs.add(new int[]{x, y, z});
		this.ball = offs.toArray(new int[0][]);
	}

	/**
	 * Plans the throws for a designed leg starting on {@code start} (the block under your feet).
	 * {@code toClear} is what the design needs gone; the order of work follows {@code path} (lane origins and ends).
	 */
	public Plan plan(int[] start, List<int[]> path, Set<Long> toClear) {
		long t0 = System.nanoTime();
		Set<Long> blasted = new HashSet<>();
		SolidQuery now = (x, y, z) -> world.solid(x, y, z) && !(blasted.contains(Lane.pack(x, y, z)) && world.clearable(x, y, z));

		// Work along the leg: order blocks by how far along the path they are
		List<Long> order = new ArrayList<>(toClear);
		Map<Long, Double> along = new HashMap<>();
		for (long k : order) along.put(k, progress(path, Lane.unpack(k)));
		order.sort(Comparator.comparingDouble(along::get));
		Set<Long> remaining = new LinkedHashSet<>(order);
		List<Long> band = band(path);

		List<Throw> throwsOut = new ArrayList<>();
		List<Long> mine = new ArrayList<>();
		while (!remaining.isEmpty()) {
			long next = remaining.iterator().next();
			if (throwsOut.size() >= cfg.setup.maxThrowsPerLeg) { mine.addAll(remaining); break; }
			Throw t = bestThrow(Lane.unpack(next), band, remaining, now);
			if (t == null) {
				remaining.remove(next);
				mine.add(next);
				blasted.add(next); // mined by hand: open from now on
				continue;
			}
			throwsOut.add(t);
			int[] c = {(int) Math.floor(t.centre().x()), (int) Math.floor(t.centre().y()), (int) Math.floor(t.centre().z())};
			for (int[] o : ball) {
				int x = c[0] + o[0], y = c[1] + o[1], z = c[2] + o[2];
				if (!inBlast(t.centre(), x, y, z)) continue;
				long k = Lane.pack(x, y, z);
				remaining.remove(k);
				if (world.clearable(x, y, z) && world.solid(x, y, z)) blasted.add(k);
			}
		}
		return new Plan(throwsOut, mine, blasted, (System.nanoTime() - t0) / 1e6);
	}

	/** The throw that clears the most still-needed blocks, among stand spots near {@code v}, or null. */
	private Throw bestThrow(int[] v, List<Long> band, Set<Long> remaining, SolidQuery now) {
		Vec3 vc = centre(v);
		double range = cfg.bombs.throwRange;
		double minDist = cfg.setup.minThrowDistance;
		// Stand spots that exist right now, not too close and within throwing distance
		List<int[]> stands = new ArrayList<>();
		for (long k : band) {
			int[] s = Lane.unpack(k);
			double d = centre(s).sub(vc).length();
			if (d < minDist || d > range + 0.5) continue;
			if (!standable(s, now)) continue;
			stands.add(s);
		}
		stands.sort(Comparator.comparingDouble(s -> centre(s).sub(vc).length()));
		if (stands.size() > STANDS_PER_STEP) stands = stands.subList(0, STANDS_PER_STEP);

		// Blocks to aim at: still-needed blocks near v (v first), they are solid now
		List<int[]> aims = new ArrayList<>();
		aims.add(v);
		for (int[] o : ball) {
			if (aims.size() >= AIMS_PER_STAND) break;
			int x = v[0] + o[0], y = v[1] + o[1], z = v[2] + o[2];
			if ((o[0] | o[1] | o[2]) != 0 && remaining.contains(Lane.pack(x, y, z))) aims.add(new int[]{x, y, z});
		}

		Throw best = null;
		for (int[] s : stands) {
			Vec3 feet = new Vec3(s[0] + 0.5, s[1], s[2] + 0.5);
			Vec3 eye = feet.add(new Vec3(0, eyeHeight, 0));
			Set<Long> seenCentres = new HashSet<>();
			for (int[] a : aims) {
				Vec3 aim = centre(a);
				Vec3 dir = aim.sub(eye).normalize();
				BombThrow.Prediction pr = BombThrow.predict(eye, dir, range, cfg.bombs.dropsStraightDownAtMaxRange, now);
				if (!pr.lands()) continue;
				Vec3 c = pr.blastCentre(cfg.bombs);
				int cx = (int) Math.floor(c.x()), cy = (int) Math.floor(c.y()), cz = (int) Math.floor(c.z());
				if (!seenCentres.add(Lane.pack(cx, cy, cz))) continue;
				if (world.inZone(cx, cy, cz) || !world.explored(cx, cz)) continue; // fizzles / not explored
				// Don't blow up where you stand
				if (c.sub(feet).length() <= radius + 0.5 || c.sub(new Vec3(feet.x(), feet.y() - 0.5, feet.z())).length() <= radius + 0.5) continue;
				int gain = 0;
				for (int[] o : ball) {
					int x = cx + o[0], y = cy + o[1], z = cz + o[2];
					if (inBlast(c, x, y, z) && remaining.contains(Lane.pack(x, y, z))) gain++;
				}
				if (gain == 0) continue;
				if (best == null || gain > best.clears()
						|| (gain == best.clears() && feet.sub(c).length() < best.standFeet().sub(best.centre()).length()))
					best = new Throw(feet, aim, dir, pr.impactBlock(), c, pr.dropped(), gain);
			}
		}
		return best;
	}

	/** Block (x, y, z) is broken by a blast at {@code c}: its centre is within the radius. */
	private boolean inBlast(Vec3 c, int x, int y, int z) {
		double dx = x + 0.5 - c.x(), dy = y + 0.5 - c.y(), dz = z + 0.5 - c.z();
		return dx * dx + dy * dy + dz * dz <= radius * radius + 1e-9;
	}

	/** Feet in block s: room for the body (2 blocks), something solid underneath, explored. */
	private boolean standable(int[] s, SolidQuery now) {
		return !now.solid(s[0], s[1], s[2]) && !now.solid(s[0], s[1] + 1, s[2]) && now.solid(s[0], s[1] - 1, s[2])
				&& world.explored(s[0], s[2]);
	}

	/** Candidate stand cells: everything within {@link #BAND} blocks of the path (feet positions). */
	private static List<Long> band(List<int[]> path) {
		Set<Long> out = new LinkedHashSet<>();
		for (int i = 0; i < path.size(); i++) {
			int[] a = path.get(i), b = i + 1 < path.size() ? path.get(i + 1) : a;
			Vec3 A = centre(a), B = centre(b);
			double len = B.sub(A).length();
			int steps = Math.max(1, (int) Math.ceil(len / 3));
			for (int sIdx = 0; sIdx <= steps; sIdx++) {
				Vec3 p = A.add(B.sub(A).scale(sIdx / (double) steps));
				int px = (int) Math.floor(p.x()), py = (int) Math.floor(p.y()), pz = (int) Math.floor(p.z());
				for (int x = -BAND; x <= BAND; x++)
					for (int y = -BAND; y <= BAND; y++)
						for (int z = -BAND; z <= BAND; z++)
							if (x * x + y * y + z * z <= BAND * BAND) out.add(Lane.pack(px + x, py + y + 1, pz + z));
			}
		}
		return new ArrayList<>(out);
	}

	/** How far along the path (in blocks) the point nearest to {@code q} is. */
	static double progress(List<int[]> path, int[] q) {
		Vec3 Q = centre(q);
		double best = Double.MAX_VALUE, bestAlong = 0, acc = 0;
		for (int i = 0; i + 1 < path.size(); i++) {
			Vec3 A = centre(path.get(i)), B = centre(path.get(i + 1)), d = B.sub(A);
			double len2 = d.dot(d);
			double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, Q.sub(A).dot(d) / len2));
			double dist = A.add(d.scale(t)).sub(Q).length();
			if (dist < best) { best = dist; bestAlong = acc + t * Math.sqrt(len2); }
			acc += Math.sqrt(len2);
		}
		return bestAlong;
	}

	private static Vec3 centre(int[] b) { return new Vec3(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5); }
}
