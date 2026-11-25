package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.List;

/**
 * What the mod has learned about one structure's crystal box, so the structure can be recognised in any
 * lobby from the box alone (user idea, 2026-11-25). Structures don't rotate (confirmed 2026-11-25), so
 * the blocks around the box and the offset from the box to the NPC are the same in every lobby.
 *
 * @param structure    {@link Structure#key}
 * @param boxSize      barrier box size {x, y, z} in blocks
 * @param surroundings blocks around the box, anchored at the box's lower corner
 * @param anchorOffset where the structure's anchor (NPC, keepers' centre) sits relative to the box's lower
 *                     corner; null for structures marked at the crystal itself
 * @param reference    for a structure without its own crystal box (King Yolkar): the {@link Structure#key}
 *                     whose box the offset is measured from; otherwise null
 * @param lobby        lobby it was learned in
 */
public record CrystalSignature(String structure, int[] boxSize, StructureTemplate surroundings, double[] anchorOffset,
		String reference, String lobby) {

	/** Radius of the block cube kept around the box. */
	public static final int SURROUNDINGS_RADIUS = 12;

	public Structure structureEnum() { return Structure.byKey(structure); }

	/** The anchor in this lobby, given the box min corner it is measured from. */
	public Vec3 anchorFrom(int[] boxMin) {
		if (anchorOffset == null) return null;
		return new Vec3(boxMin[0] + anchorOffset[0], boxMin[1] + anchorOffset[1], boxMin[2] + anchorOffset[2]);
	}

	public static int[] sizeOf(BarrierBoxes.Box b) {
		return new int[]{b.max()[0] - b.min()[0] + 1, b.max()[1] - b.min()[1] + 1, b.max()[2] - b.min()[2] + 1};
	}

	/** Result of {@link #identify}. */
	public record Identified(CrystalSignature signature, TemplateMatcher.Match match) {}

	/**
	 * Which learned structure a barrier box belongs to: same box size, and the blocks around it match
	 * (structures don't rotate, so only small shifts are tried). Null if none matches confidently.
	 */
	public static Identified identify(BarrierBoxes.Box box, List<CrystalSignature> learned, StructureTemplate.BlockLookup world) {
		int[] size = sizeOf(box);
		Identified best = null;
		double runnerUp = 0;
		for (CrystalSignature sig : learned) {
			if (sig.reference() != null || !java.util.Arrays.equals(sig.boxSize(), size)) continue;
			TemplateMatcher.Match m = TemplateMatcher.matchAt(sig.surroundings(), box.min()[0], box.min()[1], box.min()[2], 1, world, Orientation.R0);
			if (m.compared() < TemplateMatcher.MIN_COMPARED) continue;
			if (best == null || m.score() > best.match().score()) {
				if (best != null) runnerUp = Math.max(runnerUp, best.match().score());
				best = new Identified(sig, m);
			} else {
				runnerUp = Math.max(runnerUp, m.score());
			}
		}
		if (best == null || best.match().score() < 0.6 || best.match().score() - runnerUp < 0.15) return null;
		return best;
	}
}
