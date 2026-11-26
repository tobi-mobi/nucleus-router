package dev.nucleusrouter.game;

import dev.nucleusrouter.core.etherwarp.Etherwarp;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Your own stop points (SPEC §3.5, roadmap 5.1): where you like to stand at a stop, recorded with
 * {@code /nr setpoint <stop>} (or the key) and stored <b>relative to the structure's anchor</b> (the NPC's
 * feet, or the crystal box centre), turned into the structure's template frame with its {@link Orientation}.
 * Structures don't rotate (confirmed 2026-11-25), so in practice this is a plain block offset that carries
 * over to every lobby.
 * <p>
 * Used by {@link StopPlan} instead of the automatic pick, as long as the spot is a block you can etherwarp
 * onto in this lobby (solid, not respawning, room above). If not, the automatic pick is used and the stop's
 * note says so.
 */
public final class SetPoints {
	/** The stops you can set, in the order they're listed. The nucleus spawn is fixed by the game. */
	public static final List<String> IDS = List.of("divan", "precursor", "king", "queen", "temple", "temple.exit", "khazad");

	/** Feet block offset from the anchor block, in the structure's template frame. */
	public record Point(int dx, int dy, int dz, long setAtMs) {}

	/** What a stored point means in this lobby. */
	public enum Status { NONE, USED, BLOCKED }

	public Map<String, Point> points = new LinkedHashMap<>();

	public static boolean isId(String id) { return IDS.contains(id); }

	/** The block an anchor position falls in. */
	public static int[] anchorBlock(Vec3 anchor) {
		return new int[]{(int) Math.floor(anchor.x()), (int) Math.floor(anchor.y()), (int) Math.floor(anchor.z())};
	}

	/** The block your feet are in (standing on a full block: the block above the one you stand on). */
	public static int[] feetBlock(Vec3 feet) {
		return new int[]{(int) Math.floor(feet.x()), (int) Math.floor(feet.y() + 0.01), (int) Math.floor(feet.z())};
	}

	/** Records {@code feet} as the stop point for {@code id}, relative to {@code anchor}. Returns the stored point. */
	public Point record(String id, Vec3 anchor, Vec3 feet, Orientation o, long nowMs) {
		int[] a = anchorBlock(anchor), f = feetBlock(feet);
		int[] t = (o == null ? Orientation.R0 : o).invert(f[0] - a[0], f[2] - a[2]);
		Point p = new Point(t[0], f[1] - a[1], t[1], nowMs);
		points.put(id, p);
		return p;
	}

	public boolean clear(String id) { return points.remove(id) != null; }

	public Point get(String id) { return points.get(id); }

	/** Where the stored point for {@code id} puts your feet in this lobby, or null if none is stored. */
	public Vec3 feet(String id, Vec3 anchor, Orientation o) {
		Point p = points.get(id);
		if (p == null) return null;
		int[] a = anchorBlock(anchor);
		int[] w = (o == null ? Orientation.R0 : o).apply(p.dx(), p.dz());
		return Etherwarp.landingFeet(a[0] + w[0], a[1] + p.dy() - 1, a[2] + w[1]);
	}

	/** Whether you can etherwarp onto the stored spot in {@code world}: the block under the feet is a good target with room above. */
	public static boolean usable(Vec3 feet, PlanWorld world, int requiredAir) {
		int x = (int) Math.floor(feet.x()), y = (int) Math.floor(feet.y()) - 1, z = (int) Math.floor(feet.z());
		return world.goodTarget(x, y, z) && Etherwarp.hasRoomAbove(x, y, z, requiredAir, world);
	}

	/** The point's status for {@code id} in this world. */
	public Status status(String id, Vec3 anchor, Orientation o, PlanWorld world, int requiredAir) {
		Vec3 f = feet(id, anchor, o);
		if (f == null) return Status.NONE;
		return usable(f, world, requiredAir) ? Status.USED : Status.BLOCKED;
	}
}
