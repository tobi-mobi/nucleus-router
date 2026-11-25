package dev.nucleusrouter.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks in a cube around a structure's anchor block, as captured by {@code /nr capture}
 * (SPEC §6). Used to learn structure sizes and to detect a structure's orientation in a new lobby
 * ({@link TemplateMatcher}). Blocks are stored relative to the anchor block (the block the anchor
 * point is in), as indexes into {@link #palette}; index 0 means "not seen".
 */
public final class StructureTemplate {
	public static final String UNSEEN = "?";

	/** Looks up a block by world position; null if never seen. */
	public interface BlockLookup {
		String at(int x, int y, int z);
	}

	public final String structure;
	/** Anchor block in the world it was captured from. */
	public final int anchorX, anchorY, anchorZ;
	public final int radius;
	public final List<String> palette;
	public final int[] data;

	public StructureTemplate(String structure, int anchorX, int anchorY, int anchorZ, int radius, List<String> palette, int[] data) {
		int size = 2 * radius + 1;
		if (data.length != size * size * size) throw new IllegalArgumentException("data has " + data.length + " entries, expected " + size * size * size);
		this.structure = structure;
		this.anchorX = anchorX;
		this.anchorY = anchorY;
		this.anchorZ = anchorZ;
		this.radius = radius;
		this.palette = List.copyOf(palette);
		this.data = data;
	}

	/** Copies the cube of radius {@code r} around the anchor block out of the world. */
	public static StructureTemplate capture(String structure, int ax, int ay, int az, int r, BlockLookup world) {
		List<String> palette = new ArrayList<>(List.of(UNSEEN));
		Map<String, Integer> index = new HashMap<>();
		index.put(UNSEEN, 0);
		int size = 2 * r + 1;
		int[] data = new int[size * size * size];
		int i = 0;
		for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
			String b = world.at(ax + dx, ay + dy, az + dz);
			if (b == null) b = UNSEEN;
			Integer k = index.get(b);
			if (k == null) { k = palette.size(); palette.add(b); index.put(b, k); }
			data[i++] = k;
		}
		return new StructureTemplate(structure, ax, ay, az, r, palette, data);
	}

	public int size() { return 2 * radius + 1; }

	/** Block at the offset from the anchor block, or {@link #UNSEEN}. */
	public String at(int dx, int dy, int dz) {
		if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) return UNSEEN;
		int s = size();
		return palette.get(data[((dy + radius) * s + (dz + radius)) * s + (dx + radius)]);
	}

	/** Share of the cube that was seen (0..1). */
	public double seenFraction() {
		int seen = 0;
		for (int v : data) if (v != 0) seen++;
		return seen / (double) data.length;
	}

	/**
	 * True for blocks that say something about the structure: not air, fluids, Hard Stone, ores or
	 * gemstones (those are the random cave around it and differ in every lobby).
	 */
	public static boolean distinctive(String block) {
		if (block == null || block.equals(UNSEEN)) return false;
		String b = block.startsWith("minecraft:") ? block.substring(10) : block;
		return !(b.equals("air") || b.equals("cave_air") || b.equals("void_air") || b.equals("stone")
				|| b.equals("water") || b.equals("lava") || b.endsWith("_ore") || b.contains("stained_glass")
				|| b.equals("glass") || b.equals("glass_pane") || b.equals("barrier") || b.equals("bedrock"));
	}

	/** Bounding box {minDx, minDy, minDz, maxDx, maxDy, maxDz} of distinctive blocks, or null if none. */
	public int[] distinctiveBounds() {
		int[] b = null;
		int s = size(), i = 0;
		for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
			if (distinctive(palette.get(data[i++]))) {
				if (b == null) b = new int[]{dx, dy, dz, dx, dy, dz};
				b[0] = Math.min(b[0], dx); b[1] = Math.min(b[1], dy); b[2] = Math.min(b[2], dz);
				b[3] = Math.max(b[3], dx); b[4] = Math.max(b[4], dy); b[5] = Math.max(b[5], dz);
			}
		}
		return b;
	}

	// ------------------------------------------------------------------ compact text form

	/** Run-length encoding of {@link #data}: "index*count index*count ...". */
	public String encodeData() {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < data.length) {
			int v = data[i], n = 1;
			while (i + n < data.length && data[i + n] == v) n++;
			if (sb.length() > 0) sb.append(' ');
			sb.append(v);
			if (n > 1) sb.append('*').append(n);
			i += n;
		}
		return sb.toString();
	}

	public static int[] decodeData(String text, int expected) {
		int[] out = new int[expected];
		int i = 0;
		if (!text.isBlank()) {
			for (String part : text.trim().split(" ")) {
				int star = part.indexOf('*');
				int v = Integer.parseInt(star < 0 ? part : part.substring(0, star));
				int n = star < 0 ? 1 : Integer.parseInt(part.substring(star + 1));
				if (i + n > expected) throw new IllegalArgumentException("template data longer than " + expected);
				for (int k = 0; k < n; k++) out[i++] = v;
			}
		}
		if (i != expected) throw new IllegalArgumentException("template data has " + i + " entries, expected " + expected);
		return out;
	}
}
