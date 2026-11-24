package dev.nucleusrouter.core.world;

import java.util.Arrays;

/**
 * One 16x16x16 block section. Each voxel stores two small palette indexes:
 * the block it <em>originally</em> was (sticky) and the block it is <em>now</em>.
 * Index 0 means "never seen". About 8 KB per section.
 *
 * Copy-on-write: once a snapshot holds this section ({@link #shared}), the model copies it
 * before changing it, so snapshots never change under a planner thread.
 */
final class Section {
	static final int VOLUME = 4096;
	static final int UNSEEN = 0;
	static final int MAX_PALETTE = 256;

	final byte[] orig;
	final byte[] cur;
	/** Local palette index -> global block id. Slot 0 is the "unseen" marker. */
	int[] palette;
	int paletteSize;
	/** Set when a snapshot references this section; never cleared on this instance. */
	boolean shared;

	Section() {
		orig = new byte[VOLUME];
		cur = new byte[VOLUME];
		palette = new int[8];
		palette[0] = -1;
		paletteSize = 1;
	}

	private Section(Section other) {
		orig = other.orig.clone();
		cur = other.cur.clone();
		palette = Arrays.copyOf(other.palette, other.palette.length);
		paletteSize = other.paletteSize;
	}

	Section copy() {
		return new Section(this);
	}

	/** Local index for a global block id, adding it if needed. Returns {@code overflowLocal} if full. */
	int local(int globalId, int overflowGlobalId) {
		for (int i = 1; i < paletteSize; i++) if (palette[i] == globalId) return i;
		if (paletteSize == palette.length) palette = Arrays.copyOf(palette, palette.length * 2);
		// The last free slot is reserved for "overflow" (an unbreakable solid). Extremely unlikely in real terrain.
		if (paletteSize >= MAX_PALETTE - 1) {
			if (paletteSize == MAX_PALETTE - 1) palette[paletteSize++] = overflowGlobalId;
			return MAX_PALETTE - 1;
		}
		palette[paletteSize] = globalId;
		return paletteSize++;
	}

	int origGlobal(int i) {
		int l = orig[i] & 0xFF;
		return l == UNSEEN ? -1 : palette[l];
	}

	int curGlobal(int i) {
		int l = cur[i] & 0xFF;
		return l == UNSEEN ? -1 : palette[l];
	}

	/** Index inside a section from local coordinates 0..15. Same order as Minecraft (y, then z, then x). */
	static int index(int lx, int ly, int lz) {
		return (ly << 8) | (lz << 4) | lx;
	}

	/** Packs section coordinates into one map key. */
	static long key(int sx, int sy, int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
	}

	static int keyX(long key) { return (int) (key >> 42) << 10 >> 10; }
	static int keyZ(long key) { return (int) ((key >> 20) & 0x3FFFFF) << 10 >> 10; }
	static int keyY(long key) { return (int) (key & 0xFFFFF) << 12 >> 12; }
}
