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

	/** What each position originally was (sticky memory). */
	byte[] orig;
	/**
	 * What is there now. <b>The same array as {@link #orig}</b> until a block differs (mined, placed, or a
	 * respawning block currently gone): in most sections nothing ever does, which halves the memory (5.3).
	 * Write only through {@link #setCur}, {@link #setOrig} and {@link #setBoth}.
	 */
	byte[] cur;
	/** Local palette index -> global block id. Slot 0 is the "unseen" marker. */
	int[] palette;
	int paletteSize;
	/** Set when a snapshot references this section; never cleared on this instance. */
	boolean shared;

	/** Approximate heap bytes (arrays plus object headers). */
	long bytes() {
		return 16L + (orig == null ? 0 : orig.length + 16) + (cur == null || cur == orig ? 0 : cur.length + 16) + palette.length * 4L + 16 + 32;
	}

	Section() {
		orig = new byte[VOLUME];
		cur = orig;
		palette = new int[8];
		palette[0] = -1;
		paletteSize = 1;
	}

	private Section(Section other) {
		orig = other.orig.clone();
		cur = other.cur == other.orig ? orig : other.cur.clone();
		palette = Arrays.copyOf(other.palette, other.palette.length);
		paletteSize = other.paletteSize;
	}

	/** Sets what is there now. */
	void setCur(int i, byte b) {
		if (cur == orig) {
			if (orig[i] == b) return;
			cur = orig.clone();
		}
		cur[i] = b;
	}

	/** Sets what the position originally was (what is there now stays). */
	void setOrig(int i, byte b) {
		if (cur == orig) {
			if (orig[i] == b) return;
			cur = orig.clone();
		}
		orig[i] = b;
	}

	/** Sets both (a position seen for the first time). */
	void setBoth(int i, byte b) {
		orig[i] = b;
		if (cur != orig) cur[i] = b;
	}

	/** Shares the arrays again if nothing differs any more. */
	void compact() {
		if (cur != orig && Arrays.equals(cur, orig)) cur = orig;
	}

	boolean sharesArrays() { return cur == orig; }

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
