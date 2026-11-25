package dev.nucleusrouter.core.cost;

import dev.nucleusrouter.core.config.NrConfig;

/**
 * What a warp costs in seconds (SPEC §2.6). All constants come from the config.
 * <pre>
 * cost(warp) = baseWarp + ping / 2
 *            + turnTime(angle)                      = turnSecondsAt90 * (angle / 90) ^ turnExponent
 *            + P(miss | angle, margin) * missRecovery
 * </pre>
 * <b>Miss model</b> (documented choice, constants are estimates): after turning by {@code angle} degrees,
 * where you actually aim is off by a random amount with a round (2D normal) spread whose size is
 * {@code sigma = aimErrorBaseDeg + aimErrorPerDeg * angle}. The warp misses if that error is larger than
 * the target's aim margin (how far aim may drift from the most forgiving point and still land, from
 * {@code AimRegion}). For a round normal spread, P(error > margin) = exp(-margin^2 / (2 sigma^2)).
 * So: bigger turns miss more, bigger (or closer) targets miss less, and a straight chain (angle 0) almost
 * never misses.
 */
public final class CostModel {
	private final double baseWarp, halfPing, turnAt90, turnExp, missRecovery, errBase, errPerDeg, warpNucleus;

	/** @param pingMs the player's ping (measured, or the config fallback) */
	public CostModel(NrConfig cfg, int pingMs) {
		NrConfig.Cost c = cfg.cost;
		this.baseWarp = c.baseWarpSeconds;
		int ping = c.useMeasuredPing && pingMs > 0 ? pingMs : c.fallbackPingMs;
		this.halfPing = ping / 2000.0;
		this.turnAt90 = c.turnSecondsAt90;
		this.turnExp = c.turnExponent;
		this.missRecovery = c.missRecoverySeconds;
		this.errBase = c.aimErrorBaseDeg;
		this.errPerDeg = c.aimErrorPerDeg;
		this.warpNucleus = cfg.hollows.warpNucleusSeconds;
	}

	/** Base cost of one warp with no turn and no miss risk: base + ping / 2. */
	public double baseSeconds() { return baseWarp + halfPing; }

	/** Extra time for turning your view by {@code angleDeg} degrees (0 at 0 degrees). */
	public double turnSeconds(double angleDeg) {
		double a = Math.abs(angleDeg);
		return a <= 0 ? 0 : turnAt90 * Math.pow(a / 90.0, turnExp);
	}

	/** Spread of your aim after a turn of {@code angleDeg} (degrees, one standard deviation). */
	public double aimErrorDeg(double angleDeg) {
		return errBase + errPerDeg * Math.abs(angleDeg);
	}

	/**
	 * Chance a warp misses after a turn of {@code angleDeg} onto a target whose aim margin is
	 * {@code marginDeg}. A target with no valid aim (margin 0 or less) always misses.
	 */
	public double missProbability(double angleDeg, double marginDeg) {
		if (marginDeg <= 0) return 1;
		double s = aimErrorDeg(angleDeg);
		if (s <= 0) return 0;
		return Math.exp(-(marginDeg * marginDeg) / (2 * s * s));
	}

	/** Expected seconds for one warp: base + ping/2 + turn + P(miss) * miss recovery. */
	public double warpSeconds(double angleDeg, double marginDeg) {
		return baseSeconds() + turnSeconds(angleDeg) + missProbability(angleDeg, marginDeg) * missRecovery;
	}

	/** {@code /warp nucleus} from anywhere in the Hollows. */
	public double warpNucleusSeconds() { return warpNucleus; }

	public double missRecoverySeconds() { return missRecovery; }
}
