package dev.nucleusrouter.core.world;

/**
 * What the planner should assume about one block position (SPEC §3.1).
 */
public enum VoxelClass {
	/** Never seen. Planners treat it as solid (conservative). */
	UNKNOWN,
	/** Air now, and nothing that respawns was ever seen here. */
	AIR,
	/** Hard Stone: breaks for good. Solid until broken. */
	HARD_STONE,
	/** Ore, gemstone or anything else that respawns. Solid for planning even while it is air right now. */
	RESPAWNING_SOLID,
	/** Wood, dirt, bedrock, fluids and anything else bombs can't break. */
	UNBREAKABLE,
	/** Inside a structure region (set by the structure overlay, milestone 2). */
	STRUCTURE,
	/** A block that appeared where there was air or broken Hard Stone (e.g. placed cobblestone). It stays. */
	PLACED;

	/** Solid for planning: blocks rays and can be stood on. Only AIR is not. */
	public boolean plannedSolid() {
		return this != AIR;
	}
}
