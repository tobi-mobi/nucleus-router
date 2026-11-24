package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;

/**
 * Where an etherwarp lands (SPEC §2.3 plus confirmed facts in ROADMAP.md):
 * <ul>
 *   <li>The ray starts at the sneaking eye position ({@code feet + eyeHeight}) along the look direction.</li>
 *   <li>The first solid block hit is the target. Every block counts as a full cube.
 *       Water and lava are invisible (the caller's {@link SolidQuery} returns false for them).</li>
 *   <li>The target must be within range: the ray must enter it within {@code range} blocks.</li>
 *   <li>The {@code requiredAirAbove} blocks above the target must not be solid (fluids are fine).</li>
 *   <li>You land standing on the target, centred: feet at (x + 0.5, y + 1, z + 0.5).</li>
 * </ul>
 */
public final class Etherwarp {
	public record Params(double range, double eyeHeight, int requiredAirAbove) {
		public static Params fromConfig(NrConfig cfg) {
			return new Params(cfg.etherwarp.range, cfg.etherwarp.eyeHeight, cfg.etherwarp.requiredAirAbove);
		}
	}

	public enum Outcome {
		/** Lands on the hit block. */
		VALID,
		/** Nothing solid within range: no warp happens. */
		NO_TARGET_IN_RANGE,
		/** Something solid is in the blocks above the target: no warp happens. */
		NOT_ENOUGH_AIR_ABOVE
	}

	/** @param hit the block the ray hit (null for NO_TARGET_IN_RANGE); @param landingFeet feet position after the warp (VALID only) */
	public record Result(Outcome outcome, VoxelRay.Hit hit, Vec3 landingFeet) {
		public boolean valid() { return outcome == Outcome.VALID; }
	}

	private Etherwarp() {}

	/** Sneaking eye position for feet at {@code feet}. */
	public static Vec3 eye(Vec3 feet, double eyeHeight) {
		return new Vec3(feet.x(), feet.y() + eyeHeight, feet.z());
	}

	/** Feet position after landing on block (x, y, z). */
	public static Vec3 landingFeet(int x, int y, int z) {
		return new Vec3(x + 0.5, y + 1, z + 0.5);
	}

	/** True if the {@code air} blocks above (x, y, z) are not solid. */
	public static boolean hasRoomAbove(int x, int y, int z, int air, SolidQuery solid) {
		for (int i = 1; i <= air; i++) if (solid.solid(x, y + i, z)) return false;
		return true;
	}

	/** Simulates one etherwarp from an eye position and look direction. */
	public static Result warp(Vec3 eye, Vec3 direction, Params p, SolidQuery solid) {
		VoxelRay.Hit hit = VoxelRay.cast(eye, direction, p.range(), solid);
		if (hit == null) return new Result(Outcome.NO_TARGET_IN_RANGE, null, null);
		if (!hasRoomAbove(hit.x(), hit.y(), hit.z(), p.requiredAirAbove(), solid)) return new Result(Outcome.NOT_ENOUGH_AIR_ABOVE, hit, null);
		return new Result(Outcome.VALID, hit, landingFeet(hit.x(), hit.y(), hit.z()));
	}

	/** Simulates one etherwarp from feet position, yaw and pitch (Minecraft conventions, degrees). */
	public static Result warpFromFeet(Vec3 feet, double yawDeg, double pitchDeg, Params p, SolidQuery solid) {
		return warp(eye(feet, p.eyeHeight()), Vec3.fromYawPitch(yawDeg, pitchDeg), p, solid);
	}
}
