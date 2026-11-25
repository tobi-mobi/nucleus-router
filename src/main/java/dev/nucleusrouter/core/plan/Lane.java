package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.cost.CostModel;
import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Face;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Straight lanes (SPEC §3.4, roadmap 4.1): a chain of etherwarps with one <b>constant look direction</b>.
 * <p>
 * You stand on the lane's origin block {@code O}. Its targets are {@code T_k = O + k·Δ} for k = 1..n, with Δ an
 * integer vector. Etherwarp lands you centred on each target and keeps your view, so after every warp your
 * eye is in exactly the same place relative to the next target. If the same look direction works for every
 * warp in the (post-setup) world, you just keep clicking. Confirmed in-game: a same-aim chain is the only case
 * where etherwarp beats spamming the normal teleport, so this is what setups are built from.
 * <p>
 * {@link #evaluate} finds the directions that work for <em>every</em> warp of the lane (the intersection of
 * each warp's valid aim region), the most forgiving one and its margin. {@link #design} picks Δ, the number
 * of warps and the exact end block (near the wanted end) that are cheapest by the cost model, which trades
 * fewer warps (long Δ, small target, more misses) against a bigger aim margin. {@link #clearance} lists the
 * blocks that must be air for the lane to work, for the setup planners (4.2-4.4).
 */
public final class Lane {
	/**
	 * One lane.
	 *
	 * @param origin the block you stand on at the start {x, y, z} (your feet are on top of it, centred)
	 * @param delta  Δ, the step between targets
	 * @param warps  how many warps (targets) the lane has
	 */
	public record Spec(int[] origin, int[] delta, int warps) {
		public int[] target(int k) {
			return new int[]{origin[0] + k * delta[0], origin[1] + k * delta[1], origin[2] + k * delta[2]};
		}

		/** The last target: where the lane ends. */
		public int[] end() { return target(warps); }

		/** Eye position standing on target {@code k} (k = 0 is the origin). */
		public Vec3 eye(int k, double eyeHeight) {
			int[] t = target(k);
			return Etherwarp.eye(Etherwarp.landingFeet(t[0], t[1], t[2]), eyeHeight);
		}

		public double stepLength() { return Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]); }

		@Override public String toString() {
			return String.format("lane from %d %d %d, step %d %d %d x%d", origin[0], origin[1], origin[2], delta[0], delta[1], delta[2], warps);
		}
	}

	/**
	 * How well a lane works.
	 *
	 * @param valid        some look direction works for every warp
	 * @param direction    the most forgiving look direction (null if not valid)
	 * @param marginDeg    how far your aim can drift from {@code direction} and still work for every warp
	 * @param coneDeg      size of the working region as the width of a round cone with the same area
	 * @param validSamples directions tried that worked for every warp
	 * @param samples      directions tried
	 * @param failingWarp  1-based warp that fails most often (0 if none fails); helps say what to clear
	 * @param problem      why it doesn't work, in words (null if valid)
	 */
	public record Result(boolean valid, Vec3 direction, double marginDeg, double coneDeg, int validSamples, int samples,
			int failingWarp, String problem) {}

	/** A lane with its evaluation and expected time in seconds. */
	public record Choice(Spec spec, Result result, double seconds) {}

	private Lane() {}

	/** Lanes can't be longer than this many warps (sanity limit). */
	public static final int MAX_WARPS = 64;

	/**
	 * The directions that work for every warp of the lane in {@code world} (the world as it will be after the
	 * setup). Directions are sampled on an n x n grid over each face of the first target that faces the eye, plus a
	 * ring just outside the faces (as {@code AimRegion}); because every warp sees the same geometry relative to its
	 * eye, each sampled direction is tried on every warp.
	 */
	public static Result evaluate(Spec s, Etherwarp.Params p, SolidQuery world, int gridPerFace) {
		if (s.warps() < 1 || s.warps() > MAX_WARPS) return fail(0, "a lane needs 1 to " + MAX_WARPS + " warps");
		if (s.delta()[0] == 0 && s.delta()[1] == 0 && s.delta()[2] == 0) return fail(0, "step is zero");
		for (int k = 1; k <= s.warps(); k++) {
			int[] t = s.target(k);
			if (!world.solid(t[0], t[1], t[2])) return fail(k, "target " + k + " (" + xyz(t) + ") is not a solid block");
			if (!Etherwarp.hasRoomAbove(t[0], t[1], t[2], p.requiredAirAbove(), world))
				return fail(k, "no room to stand on target " + k + " (" + xyz(t) + ")");
		}

		int n = Math.max(2, gridPerFace);
		Vec3 eye0 = s.eye(0, p.eyeHeight());
		int[] t1 = s.target(1);
		List<Vec3> dirs = new ArrayList<>();
		List<Boolean> onFace = new ArrayList<>();
		List<Double> cell = new ArrayList<>();
		for (Face f : Face.values()) {
			if (!faceVisible(f, eye0, t1[0], t1[1], t1[2])) continue;
			for (int i = -1; i <= n; i++) {
				for (int j = -1; j <= n; j++) {
					double u = (i + 0.5) / n, v = (j + 0.5) / n;
					boolean in = i >= 0 && i < n && j >= 0 && j < n;
					Vec3 to = facePoint(f, t1[0], t1[1], t1[2], u, v).sub(eye0);
					double dist = to.length();
					if (dist < 1e-9) continue;
					Vec3 d = to.scale(1 / dist);
					dirs.add(d);
					onFace.add(in);
					cell.add(in ? (1.0 / (n * n)) * Math.abs(d.dot(f.normal())) / (dist * dist) : 0);
				}
			}
		}

		int m = dirs.size();
		boolean[] ok = new boolean[m];
		int[] failsAt = new int[s.warps() + 1];
		int validCount = 0;
		double solidAngle = 0;
		for (int i = 0; i < m; i++) {
			int bad = firstFailingWarp(s, dirs.get(i), p, world);
			ok[i] = bad == 0;
			if (ok[i]) {
				validCount++;
				solidAngle += cell.get(i);
			} else if (bad > 1) {
				failsAt[bad]++; // aims that work for warp 1 but fail later: those point at what's in the way
			}
		}

		int best = -1;
		double bestMargin = -1;
		for (int i = 0; i < m; i++) {
			if (!ok[i] || !onFace.get(i)) continue;
			double margin = Double.POSITIVE_INFINITY;
			for (int j = 0; j < m; j++) if (!ok[j]) margin = Math.min(margin, dirs.get(i).angleDeg(dirs.get(j)));
			if (margin > bestMargin) { bestMargin = margin; best = i; }
		}
		int worst = 0;
		for (int k = 2; k <= s.warps(); k++) if (failsAt[k] > failsAt[worst]) worst = k;
		if (best < 0 && worst == 0) worst = 1; // nothing even works for the first warp

		double cos = 1 - solidAngle / (2 * Math.PI);
		double cone = 2 * Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
		if (best < 0) {
			String why = worst > 0 ? "no single look direction works; warp " + worst + " is blocked or lands elsewhere most often"
					: "no single look direction works";
			return new Result(false, null, 0, 0, validCount, m, worst, why);
		}
		return new Result(true, dirs.get(best), Math.min(bestMargin, 180), cone, validCount, m, worst, null);
	}

	/** 0 if looking along {@code dir} works for every warp of the lane, else the first warp (1-based) that doesn't. */
	public static int firstFailingWarp(Spec s, Vec3 dir, Etherwarp.Params p, SolidQuery world) {
		for (int k = 1; k <= s.warps(); k++) {
			Etherwarp.Result r = Etherwarp.warp(s.eye(k - 1, p.eyeHeight()), dir, p, world);
			int[] t = s.target(k);
			if (!r.valid() || !r.hit().isBlock(t[0], t[1], t[2])) return k;
		}
		return 0;
	}

	/**
	 * Expected seconds to run the lane: the first warp pays the turn from {@code turnDeg} (your view before the
	 * lane; 0 if it continues a lane with the same direction), the others are same-aim warps.
	 */
	public static double seconds(Spec s, Result r, double turnDeg, CostModel cost) {
		if (!r.valid()) return Double.POSITIVE_INFINITY;
		return cost.warpSeconds(turnDeg, r.marginDeg()) + (s.warps() - 1) * cost.warpSeconds(0, r.marginDeg());
	}

	/**
	 * Settings for {@link #design}.
	 *
	 * @param endSnap      the lane may end this many blocks (each axis) from the wanted end, so the step can be whole numbers
	 * @param extraWarps   besides the fewest warps that reach, also try up to this many more (shorter steps, bigger targets)
	 * @param marginBlocks keep the step this much under the etherwarp range
	 * @param gridPerFace  aim sampling per face (3 is fast, 8 is fine)
	 * @param keep         how many of the best lanes to return
	 * @param minMarginDeg lanes whose aim margin is smaller than this are left out (like {@code plan.minAimMarginDeg})
	 * @param placeTargets evaluate as if the lane's target blocks will be placed (a setup), instead of only using
	 *                     blocks already in the world
	 */
	public record Options(int endSnap, int extraWarps, double marginBlocks, int gridPerFace, int keep, double minMarginDeg,
			boolean placeTargets) {
		public static Options defaults() { return new Options(2, 3, 2.0, 4, 5, 0.3, false); }

		public Options withPlaceTargets(boolean place) {
			return new Options(endSnap, extraWarps, marginBlocks, gridPerFace, keep, minMarginDeg, place);
		}
	}

	/**
	 * The best straight lanes from {@code origin} to (near) {@code wantedEnd}: every end block within
	 * {@code endSnap} of the wanted one and every warp count from the fewest that reach up to {@code extraWarps}
	 * more, keeping only whole-number steps no longer than range minus margin. Each is evaluated in {@code world}
	 * and priced; cheapest first. Lanes that don't work in {@code world} are left out.
	 *
	 * @param turnDeg how far your view turns for the first warp (see {@link #seconds})
	 */
	public static List<Choice> design(int[] origin, int[] wantedEnd, double turnDeg, Etherwarp.Params p, SolidQuery world,
			CostModel cost, Options o) {
		double maxStep = p.range() - o.marginBlocks();
		List<Spec> candidates = candidates(origin, wantedEnd, maxStep, o.endSnap(), o.extraWarps());
		List<Choice> out = new ArrayList<>();
		for (Spec s : candidates) {
			Result r = evaluate(s, p, o.placeTargets() ? withTargets(s, world) : world, o.gridPerFace());
			if (!r.valid() || r.marginDeg() < o.minMarginDeg()) continue;
			out.add(new Choice(s, r, seconds(s, r, turnDeg, cost)));
		}
		// Cheapest first; on equal time the end closer to the wanted one, then the larger margin
		out.sort(Comparator.comparingDouble(Choice::seconds)
				.thenComparingDouble(c -> dist2(c.spec().end(), wantedEnd))
				.thenComparing(Comparator.comparingDouble((Choice c) -> c.result().marginDeg()).reversed()));
		return out.size() > o.keep() ? new ArrayList<>(out.subList(0, o.keep())) : out;
	}

	/** {@code world} plus the lane's target blocks (as if placed). */
	public static SolidQuery withTargets(Spec s, SolidQuery world) {
		Set<Long> t = new HashSet<>();
		for (int k = 1; k <= s.warps(); k++) { int[] b = s.target(k); t.add(pack(b[0], b[1], b[2])); }
		return (x, y, z) -> t.contains(pack(x, y, z)) || world.solid(x, y, z);
	}

	/** Every lane shape from {@code origin} ending within {@code snap} of {@code wantedEnd} with a whole-number step. */
	public static List<Spec> candidates(int[] origin, int[] wantedEnd, double maxStep, int snap, int extraWarps) {
		List<Spec> out = new ArrayList<>();
		for (int dx = -snap; dx <= snap; dx++)
			for (int dy = -snap; dy <= snap; dy++)
				for (int dz = -snap; dz <= snap; dz++) {
					int ex = wantedEnd[0] + dx - origin[0], ey = wantedEnd[1] + dy - origin[1], ez = wantedEnd[2] + dz - origin[2];
					double len = Math.sqrt((double) ex * ex + (double) ey * ey + (double) ez * ez);
					if (len < 1) continue;
					int fewest = (int) Math.ceil(len / maxStep - 1e-9);
					for (int n = Math.max(1, fewest); n <= Math.min(MAX_WARPS, fewest + extraWarps); n++) {
						if (ex % n != 0 || ey % n != 0 || ez % n != 0) continue;
						out.add(new Spec(origin.clone(), new int[]{ex / n, ey / n, ez / n}, n));
					}
				}
		return out;
	}

	/**
	 * Blocks that must be air for the lane to work with aim drift up to {@code toleranceDeg} around
	 * {@code direction}: every block the rays pass through (the centre ray and 8 rays on a cone of that width, from
	 * every eye position) before their target, plus the {@code requiredAirAbove} blocks over the origin and every target.
	 * The targets themselves are never included. Packed with {@link #pack}.
	 */
	public static Set<Long> clearance(Spec s, Vec3 direction, double toleranceDeg, Etherwarp.Params p) {
		Set<Long> targets = new HashSet<>();
		for (int k = 1; k <= s.warps(); k++) { int[] t = s.target(k); targets.add(pack(t[0], t[1], t[2])); }
		List<Vec3> rays = new ArrayList<>();
		rays.add(direction.normalize());
		if (toleranceDeg > 0) rays.addAll(cone(direction.normalize(), toleranceDeg, 8));

		Set<Long> out = new HashSet<>();
		for (int k = 1; k <= s.warps(); k++) {
			int[] t = s.target(k);
			Vec3 eye = s.eye(k - 1, p.eyeHeight());
			// Rays beside the target (aim drift) only need clearing up to about the target's distance
			double reach = Math.min(p.range(), new Vec3(t[0] + 0.5, t[1] + 0.5, t[2] + 0.5).sub(eye).length() + 0.5);
			for (Vec3 d : rays) {
				// Walk the ray, recording every block until it reaches this warp's target (or that distance)
				VoxelRay.cast(eye, d, reach, (x, y, z) -> {
					if (x == t[0] && y == t[1] && z == t[2]) return true;
					long key = pack(x, y, z);
					if (!targets.contains(key)) out.add(key);
					return false;
				});
			}
		}
		for (int k = 0; k <= s.warps(); k++) {
			int[] t = s.target(k);
			for (int i = 1; i <= p.requiredAirAbove(); i++) out.add(pack(t[0], t[1] + i, t[2]));
		}
		return out;
	}

	/** {@code count} unit vectors at {@code angleDeg} around {@code axis}, evenly spread. */
	static List<Vec3> cone(Vec3 axis, double angleDeg, int count) {
		Vec3 helper = Math.abs(axis.y()) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 u = cross(axis, helper).normalize(), v = cross(axis, u).normalize();
		double a = Math.toRadians(angleDeg);
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double phi = 2 * Math.PI * i / count;
			Vec3 side = u.scale(Math.cos(phi)).add(v.scale(Math.sin(phi)));
			out.add(axis.scale(Math.cos(a)).add(side.scale(Math.sin(a))).normalize());
		}
		return out;
	}

	private static Vec3 cross(Vec3 a, Vec3 b) {
		return new Vec3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
	}

	/** Packs a block position into one long (21 bits per axis, signed). */
	public static long pack(int x, int y, int z) {
		return (((long) x & 0x1FFFFF) << 42) | (((long) y & 0x1FFFFF) << 21) | ((long) z & 0x1FFFFF);
	}

	/** Unpacks {@link #pack}. */
	public static int[] unpack(long k) {
		return new int[]{(int) (k << 1 >> 43), (int) (k << 22 >> 43), (int) (k << 43 >> 43)};
	}

	private static Result fail(int warp, String why) {
		return new Result(false, null, 0, 0, 0, 0, warp, why);
	}

	private static String xyz(int[] t) { return t[0] + " " + t[1] + " " + t[2]; }

	private static double dist2(int[] a, int[] b) {
		double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return dx * dx + dy * dy + dz * dz;
	}

	private static boolean faceVisible(Face f, Vec3 eye, int x, int y, int z) {
		return switch (f) {
			case WEST -> eye.x() < x;
			case EAST -> eye.x() > x + 1;
			case DOWN -> eye.y() < y;
			case UP -> eye.y() > y + 1;
			case NORTH -> eye.z() < z;
			case SOUTH -> eye.z() > z + 1;
		};
	}

	private static Vec3 facePoint(Face f, int x, int y, int z, double u, double v) {
		return switch (f) {
			case WEST -> new Vec3(x, y + u, z + v);
			case EAST -> new Vec3(x + 1, y + u, z + v);
			case DOWN -> new Vec3(x + u, y, z + v);
			case UP -> new Vec3(x + u, y + 1, z + v);
			case NORTH -> new Vec3(x + u, y + v, z);
			case SOUTH -> new Vec3(x + u, y + v, z + 1);
		};
	}
}
