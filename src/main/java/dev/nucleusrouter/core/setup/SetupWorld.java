package dev.nucleusrouter.core.setup;

import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.plan.Lane;
import dev.nucleusrouter.core.world.VoxelClass;
import dev.nucleusrouter.core.world.WorldSnapshot;

import java.util.List;
import java.util.Set;

/**
 * The setup planners' view of the terrain (SPEC §3.4, roadmap 4.2-4.3).
 * <ul>
 *   <li><b>Clearable</b> = Hard Stone or a placed block: gone for good once broken. Ores, gemstones and every
 *       other respawning block, structure blocks, unbreakable blocks (wood, dirt, bedrock) and unseen blocks
 *       are not: lanes go around them.</li>
 *   <li><b>Zones</b>: a bomb whose blast centre is inside a structure's zone does nothing (confirmed). Lanes may
 *       still run inside zones (the stops are there); blocks there are cleared by bombs centred outside, or mined.</li>
 *   <li><b>Explored</b>: only plan setup where the player has been (user 2026-11-25).</li>
 * </ul>
 */
public interface SetupWorld {
	VoxelClass classAt(int x, int y, int z);

	/** Inside a structure's bomb-exclusion zone. */
	boolean inZone(int x, int y, int z);

	/** The column has been explored. */
	boolean explored(int x, int z);

	/** Solid for planning now (sticky: respawning blocks count even while mined; unseen counts as solid). */
	default boolean solid(int x, int y, int z) { return classAt(x, y, z).plannedSolid(); }

	/** Broken for good by a bomb or a pickaxe. */
	default boolean clearable(int x, int y, int z) {
		VoxelClass c = classAt(x, y, z);
		return c == VoxelClass.HARD_STONE || c == VoxelClass.PLACED;
	}

	/** Can be an etherwarp target as it stands, or once a block is placed there. Respawning and unseen blocks can't. */
	default boolean targetAllowed(int x, int y, int z) {
		VoxelClass c = classAt(x, y, z);
		return c != VoxelClass.UNKNOWN && c != VoxelClass.RESPAWNING_SOLID && explored(x, z);
	}

	/** The best case after a setup: only what can't be cleared stays solid. Used to look for lanes. */
	default SolidQuery ideal() {
		return (x, y, z) -> solid(x, y, z) && !clearable(x, y, z);
	}

	/** The world after a setup: {@code cleared} blocks gone, {@code placed} blocks added (placed wins). */
	default SolidQuery after(Set<Long> cleared, Set<Long> placed) {
		return (x, y, z) -> {
			long k = Lane.pack(x, y, z);
			if (placed.contains(k)) return true;
			if (cleared.contains(k)) return false;
			return solid(x, y, z);
		};
	}

	/** Zone boxes as {minX, minY, minZ, maxX, maxY, maxZ} (inclusive). */
	static boolean inAny(List<int[]> zones, int x, int y, int z) {
		for (int[] b : zones)
			if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
		return false;
	}

	/** From a terrain snapshot, zone boxes and the explored columns. */
	static SetupWorld of(WorldSnapshot snap, List<int[]> zones, dev.nucleusrouter.core.plan.PlanWorld.Columns explored) {
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return snap.classAt(x, y, z); }
			@Override public boolean inZone(int x, int y, int z) { return inAny(zones, x, y, z); }
			@Override public boolean explored(int x, int z) { return explored.allowed(x, z); }
		};
	}
}
