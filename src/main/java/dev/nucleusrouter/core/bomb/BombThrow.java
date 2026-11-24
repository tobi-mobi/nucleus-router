package dev.nucleusrouter.core.bomb;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.etherwarp.VoxelRay;
import dev.nucleusrouter.core.geom.Vec3;

/**
 * Where a thrown Bob-omb lands (SPEC §2.4, measured in-game 2026-11-24): it flies in a straight line
 * along the look direction up to {@code bombs.throwRange} (about 32 blocks). If it hits a block first,
 * it stops there. Otherwise, at max range it drops straight down until it hits something.
 */
public final class BombThrow {
	/** How far a dropping bomb is followed down before giving up. */
	static final double MAX_DROP = 256;

	/**
	 * @param impactBlock  block the bomb lands on (null if it never hits anything)
	 * @param impactPoint  exact point where it touches that block
	 * @param dropped      true if it reached max range and fell
	 */
	public record Prediction(int[] impactBlock, Vec3 impactPoint, boolean dropped) {
		public boolean lands() { return impactBlock != null; }

		/**
		 * Blast centre under the configured rules. Direct hit: centre of the block hit (confirmed in-game).
		 * Dropped at max range: {@code dropCentreBlocksAbove} blocks above the block it landed on ([VERIFY]).
		 */
		public Vec3 blastCentre(NrConfig.Bombs b) {
			if (!lands()) return null;
			if (b.blastCentre == NrConfig.BlastCentre.IMPACT_POINT) return impactPoint;
			int up = dropped ? b.dropCentreBlocksAbove : 0;
			return new Vec3(impactBlock[0] + 0.5, impactBlock[1] + up + 0.5, impactBlock[2] + 0.5);
		}
	}

	private BombThrow() {}

	public static Prediction predict(Vec3 eye, Vec3 direction, double range, boolean dropsAtMax, SolidQuery solid) {
		VoxelRay.Hit hit = VoxelRay.cast(eye, direction, range, solid);
		if (hit != null) return new Prediction(new int[]{hit.x(), hit.y(), hit.z()}, hit.point(), false);
		if (!dropsAtMax) return new Prediction(null, null, false);
		Vec3 end = eye.add(direction.normalize().scale(range));
		VoxelRay.Hit down = VoxelRay.cast(end, new Vec3(0, -1, 0), MAX_DROP, solid);
		if (down == null) return new Prediction(null, null, true);
		return new Prediction(new int[]{down.x(), down.y(), down.z()}, down.point(), true);
	}

	public static Prediction predict(Vec3 eye, Vec3 direction, NrConfig cfg, SolidQuery solid) {
		return predict(eye, direction, cfg.bombs.throwRange, cfg.bombs.dropsStraightDownAtMaxRange, solid);
	}
}
