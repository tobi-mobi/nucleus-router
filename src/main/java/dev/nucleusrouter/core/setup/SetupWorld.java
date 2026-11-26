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

	/**
	 * A block may be placed here. Confirmed 2026-11-26: not within 50 blocks (a cube) of the Jade, Sapphire and
	 * Amber crystals, and nowhere inside the Jungle Temple.
	 */
	default boolean placeAllowed(int x, int y, int z) { return true; }

	/** The column has been explored. */
	boolean explored(int x, int z);

	/** Solid for planning now (sticky: respawning blocks count even while mined; unseen counts as solid). */
	default boolean solid(int x, int y, int z) { return classAt(x, y, z).plannedSolid(); }

	/** Broken for good by a bomb or a pickaxe. */
	default boolean clearable(int x, int y, int z) {
		VoxelClass c = classAt(x, y, z);
		return c == VoxelClass.HARD_STONE || c == VoxelClass.PLACED;
	}

	/**
	 * Can be an etherwarp target as it stands, or once a block is placed there. Respawning and unseen blocks can't;
	 * where placing isn't allowed only a block that is already there can.
	 */
	default boolean targetAllowed(int x, int y, int z) {
		VoxelClass c = classAt(x, y, z);
		if (c == VoxelClass.UNKNOWN || c == VoxelClass.RESPAWNING_SOLID || !explored(x, z)) return false;
		return placeAllowed(x, y, z) || c.plannedSolid();
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

	/**
	 * This world after setup work: {@code cleared} blocks gone (only those that are clearable), {@code placed}
	 * blocks added. Everything else (zones, explored, the planners' rules) stays the same.
	 */
	default SetupWorld changed(Set<Long> cleared, Set<Long> placed) {
		SetupWorld base = this;
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) {
				long k = Lane.pack(x, y, z);
				if (placed.contains(k)) return VoxelClass.PLACED;
				if (cleared.contains(k) && base.clearable(x, y, z)) return VoxelClass.AIR;
				return base.classAt(x, y, z);
			}
			@Override public boolean inZone(int x, int y, int z) { return base.inZone(x, y, z); }
			@Override public boolean explored(int x, int z) { return base.explored(x, z); }
			@Override public boolean placeAllowed(int x, int y, int z) { return base.placeAllowed(x, y, z); }
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
		return of(snap, zones, explored, List.of());
	}

	/** Same, with boxes where no block may be placed ({minX, minY, minZ, maxX, maxY, maxZ}, inclusive). */
	static SetupWorld of(WorldSnapshot snap, List<int[]> zones, dev.nucleusrouter.core.plan.PlanWorld.Columns explored, List<int[]> noPlace) {
		return new SetupWorld() {
			@Override public VoxelClass classAt(int x, int y, int z) { return snap.classAt(x, y, z); }
			@Override public boolean inZone(int x, int y, int z) { return inAny(zones, x, y, z); }
			@Override public boolean explored(int x, int z) { return explored.allowed(x, z); }
			@Override public boolean placeAllowed(int x, int y, int z) { return !inAny(noPlace, x, y, z); }
		};
	}
}
