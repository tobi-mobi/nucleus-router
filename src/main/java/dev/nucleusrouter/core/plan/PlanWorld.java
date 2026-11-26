package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.world.VoxelClass;
import dev.nucleusrouter.core.world.WorldSnapshot;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The planner's view of the terrain. Rays and landing room use {@link #solid} (sticky: respawning blocks
 * count as solid even while mined; unseen blocks count as solid). Landing targets also need
 * {@link #goodTarget}: a known block that is solid now and isn't one players mine away.
 */
public interface PlanWorld extends SolidQuery {
	/** True if this block can be planned as an etherwarp target. */
	boolean goodTarget(int x, int y, int z);

	/**
	 * From a world snapshot. Unseen blocks are never targets, nor blocks that are air right now. Blocks whose id
	 * matches {@code unstable} (ores, gemstones, mithril: mined by players, then respawn) are not targets
	 * either, because a warp onto them fails once someone mines them. Structure blocks (stone bricks, smooth
	 * stone, ...) are fine targets even though the terrain model files them under "respawning".
	 */
	static PlanWorld of(WorldSnapshot snap, Predicate<String> unstable) {
		return of(snap, unstable, (x, z) -> true);
	}

	/** Which block columns the player has explored. */
	@FunctionalInterface
	interface Columns {
		boolean allowed(int x, int z);
	}

	/**
	 * Same, but targets must also lie in {@code explored} columns (user 2026-11-25: only plan where the player
	 * has been, even though farther terrain is loaded).
	 */
	static PlanWorld of(WorldSnapshot snap, Predicate<String> unstable, Columns explored) {
		// The pattern test per block type, once (performance pass 5.3: matching names per lookup was slow)
		boolean[] unstableId = new boolean[snap.blockIdCount()];
		for (int i = 0; i < unstableId.length; i++) unstableId[i] = unstable.test(snap.blockName(i));
		return new PlanWorld() {
			@Override public boolean solid(int x, int y, int z) { return snap.plannedSolid(x, y, z); }
			@Override public boolean goodTarget(int x, int y, int z) {
				if (!explored.allowed(x, z)) return false;
				VoxelClass c = snap.classAt(x, y, z);
				if (c == VoxelClass.UNKNOWN || !c.plannedSolid() || snap.currentlyAir(x, y, z)) return false;
				int orig = snap.originalId(x, y, z);
				return orig < 0 || orig >= unstableId.length || !unstableId[orig];
			}
		};
	}

	/** Matcher for block-id patterns where '*' stands for any text, e.g. "*_ore" or "minecraft:*stained_glass*". */
	static Predicate<String> patterns(List<String> globs) {
		if (globs == null || globs.isEmpty()) return id -> false;
		StringBuilder re = new StringBuilder();
		for (String g : globs) {
			if (g == null || g.isBlank()) continue;
			if (re.length() > 0) re.append('|');
			String p = g.trim();
			if (!p.contains(":") && !p.startsWith("*")) p = "minecraft:" + p;
			re.append(Pattern.quote(p).replace("*", "\\E.*\\Q"));
		}
		Pattern pattern = Pattern.compile(re.toString());
		return id -> pattern.matcher(id).matches();
	}
}
