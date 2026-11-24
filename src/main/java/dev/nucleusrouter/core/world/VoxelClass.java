package dev.nucleusrouter.core.world;

/**
 * What the planner should assume about one block position (SPEC §3.1).
 */
public enum VoxelClass {
	/** Never seen. Planners treat it as solid (conservative). */
	UNKNOWN,
	/** Air now, and nothing that respawns was ever seen here. */
	AIR,
	/** Water or lava. The etherwarp ray passes through it (confirmed in-game 2026-11-24). */
	FLUID,
	/** Hard Stone: breaks for good. Solid until broken. */
	HARD_STONE,
	/** Ore, gemstone or anything else that respawns. Solid for planning even while it is air right now. */
	RESPAWNING_SOLID,
	/** Wood, dirt, bedrock and anything else bombs can't break. */
	UNBREAKABLE,
	/** Inside a structure region (set by the structure overlay, milestone 2). */
	STRUCTURE,
	/** A block you placed (any type), or a "placed block type" that appeared in air. It stays. */
	PLACED;

	/** Solid for planning: blocks the etherwarp ray and can be stood on. Air and fluids are not. */
	public boolean plannedSolid() {
		return this != AIR && this != FLUID;
	}
}
