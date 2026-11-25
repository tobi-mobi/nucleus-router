package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The normal Aspect of the Void teleport (right-click without sneaking): each click moves you up to
 * {@code blocksPerClick} blocks along your look direction (confirmed: 12 with the user's tuners).
 * [VERIFY] At a block in the way it is assumed to stop just in front of it, in every direction (up and down
 * too). Your body (the block at your feet and the one above) must stay free of solid blocks.
 */
public final class NormalTeleport {
	/** Step used to find where you stop in front of a block. */
	static final double STEP = 0.5;

	private NormalTeleport() {}

	/** Feet position after one click from {@code feet} in direction {@code dir} (unit). */
	public static Vec3 click(Vec3 feet, Vec3 dir, double blocksPerClick, SolidQuery solid) {
		Vec3 at = feet;
		for (double t = STEP; t <= blocksPerClick + 1e-9; t += STEP) {
			Vec3 p = feet.add(dir.scale(t));
			if (!bodyFree(p, solid)) break;
			at = p;
		}
		return at;
	}

	/** Feet positions after each of {@code clicks} clicks (stops early if a click no longer moves you). */
	public static List<Vec3> stretch(Vec3 feet, Vec3 dir, double blocksPerClick, int clicks, SolidQuery solid) {
		List<Vec3> out = new ArrayList<>();
		Vec3 at = feet;
		for (int i = 0; i < clicks; i++) {
			Vec3 next = click(at, dir, blocksPerClick, solid);
			if (next.sub(at).length() < 0.5) break;
			out.add(next);
			at = next;
		}
		return out;
	}

	/** True if a player standing with feet at {@code p} doesn't overlap a solid block (feet and head block). */
	public static boolean bodyFree(Vec3 p, SolidQuery solid) {
		int x = (int) Math.floor(p.x()), y = (int) Math.floor(p.y()), z = (int) Math.floor(p.z());
		return !solid.solid(x, y, z) && !solid.solid(x, y + 1, z) && !solid.solid(x, (int) Math.floor(p.y() + 1.8), z);
	}
}
