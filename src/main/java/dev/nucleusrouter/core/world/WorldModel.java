package dev.nucleusrouter.core.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The remembered Crystal Hollows terrain for one lobby (SPEC §3.1).
 *
 * Threading: a WorldModel is owned by exactly one thread (the mod's "world" thread).
 * Other threads only ever see {@link WorldSnapshot}s, which are immutable.
 *
 * Sticky memory: for each position we keep the block it originally was. If that was an
 * ore, gemstone or anything else that respawns, the position stays solid for planning
 * forever, even while it's temporarily air.
 */
public final class WorldModel {
	private final List<String> names = new ArrayList<>();
	private final Map<String, Integer> ids = new HashMap<>();
	private BlockClassifier.Kind[] kinds = new BlockClassifier.Kind[16];
	private BlockClassifier classifier;
	private final HashMap<Long, Section> sections = new HashMap<>();
	private final int overflowId;
	private long version;

	public WorldModel(BlockClassifier classifier) {
		this.classifier = classifier;
		this.overflowId = blockId(BlockClassifier.OVERFLOW_BLOCK);
	}

	/** Global id for a block name such as "minecraft:stone". Cheap after the first call per name. */
	public int blockId(String name) {
		Integer id = ids.get(name);
		if (id != null) return id;
		int nid = names.size();
		names.add(name);
		ids.put(name, nid);
		if (nid >= kinds.length) kinds = Arrays.copyOf(kinds, kinds.length * 2);
		kinds[nid] = classifier.classify(name);
		return nid;
	}

	/** Re-sorts every known block name, e.g. after the Hard Stone list changed in the config. */
	public void setClassifier(BlockClassifier classifier) {
		this.classifier = classifier;
		for (int i = 0; i < names.size(); i++) kinds[i] = classifier.classify(names.get(i));
		version++;
	}

	/** One block changed (block update packet, mining, bomb). */
	public void observe(int x, int y, int z, int blockId) {
		Section s = writable(Section.key(x >> 4, y >> 4, z >> 4));
		apply(s, Section.index(x & 15, y & 15, z & 15), blockId);
		version++;
	}

	/**
	 * A whole section arrived (chunk load). {@code blockIds} has 4096 entries in
	 * {@link Section#index} order (y, then z, then x).
	 */
	public void observeSection(int sx, int sy, int sz, int[] blockIds) {
		if (blockIds.length != Section.VOLUME) throw new IllegalArgumentException("section needs 4096 blocks, got " + blockIds.length);
		Section s = writable(Section.key(sx, sy, sz));
		for (int i = 0; i < Section.VOLUME; i++) apply(s, i, blockIds[i]);
		version++;
	}

	private void apply(Section s, int i, int blockId) {
		int newLocal = s.local(blockId, overflowId);
		int origLocal = s.orig[i] & 0xFF;
		if (origLocal == Section.UNSEEN) {
			s.orig[i] = (byte) newLocal;
			s.cur[i] = (byte) newLocal;
			return;
		}
		s.cur[i] = (byte) newLocal;
		BlockClassifier.Kind was = kinds[s.palette[origLocal]];
		BlockClassifier.Kind now = kinds[s.palette[newLocal]];
		// Placed blocks appearing in air don't change what the spot originally was.
		boolean placedInAir = was == BlockClassifier.Kind.AIR && now == BlockClassifier.Kind.PLACEABLE;
		// A respawning block appearing where we first saw air means it was mined before we saw it.
		if (now.rank > was.rank && !placedInAir) s.orig[i] = (byte) newLocal;
	}

	private Section writable(long key) {
		Section s = sections.get(key);
		if (s == null) {
			s = new Section();
			sections.put(key, s);
		} else if (s.shared) {
			s = s.copy();
			sections.put(key, s);
		}
		return s;
	}

	/**
	 * An immutable view for planners and the saver. Cheap: it copies only the section map;
	 * sections are copied lazily the next time they change.
	 */
	public WorldSnapshot snapshot() {
		for (Section s : sections.values()) s.shared = true;
		return new WorldSnapshot(new HashMap<>(sections), Arrays.copyOf(kinds, names.size()), List.copyOf(names), version);
	}

	/**
	 * Adds what an older save of the same lobby knew. The older save decides what each position
	 * originally was; what we've seen since is replayed on top, so mined Hard Stone stays mined
	 * and respawning blocks stay remembered.
	 */
	public void mergeOlder(WorldModel older) {
		int[] remap = new int[older.names.size()];
		for (int i = 0; i < remap.length; i++) remap[i] = blockId(older.names.get(i));

		for (Map.Entry<Long, Section> e : older.sections.entrySet()) {
			Section old = e.getValue();
			Section ours = sections.get(e.getKey());
			Section merged = new Section();
			for (int i = 0; i < Section.VOLUME; i++) {
				int oo = old.origGlobal(i);
				if (oo >= 0) {
					int ol = merged.local(remap[oo], overflowId);
					int cl = merged.local(remap[old.curGlobal(i)], overflowId);
					merged.orig[i] = (byte) ol;
					merged.cur[i] = (byte) cl;
				}
				if (ours != null && ours.origGlobal(i) >= 0) {
					apply(merged, i, ours.origGlobal(i));
					apply(merged, i, ours.curGlobal(i));
				}
			}
			sections.put(e.getKey(), merged);
		}
		version++;
	}

	public int sectionCount() { return sections.size(); }
	public long version() { return version; }

	// Package-private access for WorldStore
	List<String> names() { return names; }
	Map<Long, Section> sections() { return sections; }
	void putSection(long key, Section s) { sections.put(key, s); }
}
