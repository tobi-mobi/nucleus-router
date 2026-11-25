package dev.nucleusrouter.game;

/**
 * How a structure is turned in this lobby compared with a saved capture: 4 rotations around the
 * vertical axis, each optionally mirrored (x flipped first). Confirmed in-game 2026-11-25: structures
 * don't rotate between lobbies, so everything uses {@link #R0} unless detection is turned on.
 * Minecraft axes: +x east, +z south; R90 turns east into south (clockwise seen from above).
 */
public enum Orientation {
	R0(0, false), R90(1, false), R180(2, false), R270(3, false),
	M0(0, true), M90(1, true), M180(2, true), M270(3, true);

	public final int quarterTurns;
	public final boolean mirrored;

	Orientation(int quarterTurns, boolean mirrored) {
		this.quarterTurns = quarterTurns;
		this.mirrored = mirrored;
	}

	/** Template offset (dx, dz) to world offset. Returns {x, z}. */
	public int[] apply(int dx, int dz) {
		int x = mirrored ? -dx : dx, z = dz;
		for (int i = 0; i < quarterTurns; i++) { int t = x; x = -z; z = t; }
		return new int[]{x, z};
	}

	/** World offset back to template offset (inverse of {@link #apply}). */
	public int[] invert(int x, int z) {
		for (int i = 0; i < quarterTurns; i++) { int t = z; z = -x; x = t; }
		return new int[]{mirrored ? -x : x, z};
	}

	public String describe() {
		String r = quarterTurns == 0 ? "not rotated" : "rotated " + (quarterTurns * 90) + " deg";
		return mirrored ? r + ", mirrored" : r;
	}
}
