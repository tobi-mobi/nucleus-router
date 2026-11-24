package dev.nucleusrouter.core.world;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of a {@link WorldModel} at one moment. Safe to read from any thread.
 * Planners, the ray model and the saver all work on snapshots, never on the live model.
 */
public final class WorldSnapshot {
	private final Map<Long, Section> sections;
	private final BlockClassifier.Kind[] kinds;
	private final List<String> names;
	private final long version;

	WorldSnapshot(Map<Long, Section> sections, BlockClassifier.Kind[] kinds, List<String> names, long version) {
		this.sections = sections;
		this.kinds = kinds;
		this.names = names;
		this.version = version;
	}

	/** What the planner should assume at this position. */
	public VoxelClass classAt(int x, int y, int z) {
		Section s = sections.get(Section.key(x >> 4, y >> 4, z >> 4));
		if (s == null) return VoxelClass.UNKNOWN;
		int i = Section.index(x & 15, y & 15, z & 15);
		int o = s.orig[i] & 0xFF;
		if (o == Section.UNSEEN) return VoxelClass.UNKNOWN;
		int c = s.cur[i] & 0xFF;
		return BlockClassifier.voxelClass(kinds[s.palette[o]], kinds[s.palette[c]], s.palette[o] == s.palette[c]);
	}

	/** Solid for planning (sticky: respawning blocks count even while air; unseen counts as solid). */
	public boolean plannedSolid(int x, int y, int z) {
		return classAt(x, y, z).plannedSolid();
	}

	/**
	 * Solid right now, ignoring stickiness: what a warp made this instant would hit.
	 * Air and fluids are not solid; never-seen blocks count as solid.
	 */
	public boolean currentlySolid(int x, int y, int z) {
		Section s = sections.get(Section.key(x >> 4, y >> 4, z >> 4));
		if (s == null) return true;
		int g = s.curGlobal(Section.index(x & 15, y & 15, z & 15));
		return g < 0 || !kinds[g].emptyish();
	}

	/** Block there right now, e.g. "minecraft:stone"; null if never seen. */
	public String currentBlock(int x, int y, int z) {
		Section s = sections.get(Section.key(x >> 4, y >> 4, z >> 4));
		if (s == null) return null;
		int g = s.curGlobal(Section.index(x & 15, y & 15, z & 15));
		return g < 0 ? null : names.get(g);
	}

	/** Block this position originally was (sticky memory); null if never seen. */
	public String originalBlock(int x, int y, int z) {
		Section s = sections.get(Section.key(x >> 4, y >> 4, z >> 4));
		if (s == null) return null;
		int g = s.origGlobal(Section.index(x & 15, y & 15, z & 15));
		return g < 0 ? null : names.get(g);
	}

	/** True if this position is air right now (ignoring stickiness). Unseen is not air. */
	public boolean currentlyAir(int x, int y, int z) {
		Section s = sections.get(Section.key(x >> 4, y >> 4, z >> 4));
		if (s == null) return false;
		int g = s.curGlobal(Section.index(x & 15, y & 15, z & 15));
		return g >= 0 && kinds[g] == BlockClassifier.Kind.AIR;
	}

	/**
	 * Every position in the box (inclusive) whose current block is {@code blockId}. Fast: sections whose
	 * palette doesn't contain the block are skipped.
	 */
	public java.util.List<int[]> findBlocks(String blockId, int x0, int y0, int z0, int x1, int y1, int z1) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		int gid = names.indexOf(blockId);
		if (gid < 0) return out;
		for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
			for (int sy = y0 >> 4; sy <= y1 >> 4; sy++)
				for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
					Section s = sections.get(Section.key(sx, sy, sz));
					if (s == null) continue;
					boolean has = false;
					for (int i = 1; i < s.paletteSize; i++) if (s.palette[i] == gid) { has = true; break; }
					if (!has) continue;
					for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
						int x = (sx << 4) + lx, y = (sy << 4) + ly, z = (sz << 4) + lz;
						if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue;
						if (s.curGlobal(Section.index(lx, ly, lz)) == gid) out.add(new int[]{x, y, z});
					}
				}
		return out;
	}

	public int sectionCount() { return sections.size(); }
	public long version() { return version; }

	/** Counts voxels per class. Walks every stored voxel, so call it from a worker thread. */
	public Map<VoxelClass, Long> countClasses() {
		Map<VoxelClass, Long> counts = new EnumMap<>(VoxelClass.class);
		for (Section s : sections.values()) {
			for (int i = 0; i < Section.VOLUME; i++) {
				int o = s.orig[i] & 0xFF;
				if (o == Section.UNSEEN) continue;
				int c = s.cur[i] & 0xFF;
				VoxelClass vc = BlockClassifier.voxelClass(kinds[s.palette[o]], kinds[s.palette[c]], s.palette[o] == s.palette[c]);
				counts.merge(vc, 1L, Long::sum);
			}
		}
		return counts;
	}

	// For WorldStore
	Map<Long, Section> sections() { return sections; }
	List<String> names() { return names; }
}
