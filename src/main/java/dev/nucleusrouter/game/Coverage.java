package dev.nucleusrouter.game;

import java.util.Base64;
import java.util.BitSet;

/**
 * How much of the Crystal Hollows has been explored in this lobby (SPEC §4), on a grid of
 * {@link #CELL} x {@link #CELL} block columns over the Hollows bounds. Two layers:
 * <ul>
 *   <li><b>visited</b>: came within the visit radius of you (close enough to see NPC name tags, which is
 *       what finds structures);</li>
 *   <li><b>loaded</b>: terrain your game received (chunks), a looser upper bound.</li>
 * </ul>
 * Not thread-safe: used from the client thread.
 */
public final class Coverage {
	public static final int CELL = 4;

	public final int minX, minZ, maxX, maxZ;
	public final int cellsX, cellsZ;
	private final BitSet visited, loaded;
	private int visitedCount, loadedCount;
	private long version;

	public Coverage(int minX, int maxX, int minZ, int maxZ) {
		this.minX = minX;
		this.maxX = maxX;
		this.minZ = minZ;
		this.maxZ = maxZ;
		this.cellsX = (maxX - minX) / CELL + 1;
		this.cellsZ = (maxZ - minZ) / CELL + 1;
		this.visited = new BitSet(cellsX * cellsZ);
		this.loaded = new BitSet(cellsX * cellsZ);
	}

	public int cells() { return cellsX * cellsZ; }
	public long version() { return version; }
	public double visitedFraction() { return visitedCount / (double) cells(); }
	public double loadedFraction() { return loadedCount / (double) cells(); }

	private int cellX(double x) { return (int) Math.floor((x - minX) / CELL); }
	private int cellZ(double z) { return (int) Math.floor((z - minZ) / CELL); }
	private boolean inGrid(int cx, int cz) { return cx >= 0 && cz >= 0 && cx < cellsX && cz < cellsZ; }

	/** Cell centre in world coordinates. */
	public double centreX(int cx) { return minX + (cx + 0.5) * CELL; }
	public double centreZ(int cz) { return minZ + (cz + 0.5) * CELL; }

	public boolean isVisited(int cx, int cz) { return inGrid(cx, cz) && visited.get(cz * cellsX + cx); }
	public boolean isLoaded(int cx, int cz) { return inGrid(cx, cz) && loaded.get(cz * cellsX + cx); }

	/** Marks every cell whose centre is within {@code r} blocks (horizontally) of (x, z) as visited (and loaded). */
	public void visit(double x, double z, double r) {
		int c0 = cellX(x - r), c1 = cellX(x + r), d0 = cellZ(z - r), d1 = cellZ(z + r);
		boolean changed = false;
		for (int cz = Math.max(0, d0); cz <= Math.min(cellsZ - 1, d1); cz++)
			for (int cx = Math.max(0, c0); cx <= Math.min(cellsX - 1, c1); cx++) {
				double dx = centreX(cx) - x, dz = centreZ(cz) - z;
				if (dx * dx + dz * dz > r * r) continue;
				int i = cz * cellsX + cx;
				if (!visited.get(i)) { visited.set(i); visitedCount++; changed = true; }
				if (!loaded.get(i)) { loaded.set(i); loadedCount++; changed = true; }
			}
		if (changed) version++;
	}

	/** Marks the cells of a 16x16 chunk (block coordinates of its corner) as loaded. */
	public void loadChunk(int blockX, int blockZ) {
		boolean changed = false;
		for (int cz = Math.max(0, cellZ(blockZ)); cz <= Math.min(cellsZ - 1, cellZ(blockZ + 15)); cz++)
			for (int cx = Math.max(0, cellX(blockX)); cx <= Math.min(cellsX - 1, cellX(blockX + 15)); cx++) {
				int i = cz * cellsX + cx;
				if (!loaded.get(i)) { loaded.set(i); loadedCount++; changed = true; }
			}
		if (changed) version++;
	}

	/**
	 * The nearest point (x, z) that starts an unvisited stretch: a cell whose whole neighbourhood of
	 * {@code ring} cells in every direction is unvisited. Null if nothing that big is left.
	 */
	public double[] nearestUnexplored(double x, double z, int ring) {
		int px = cellX(x), pz = cellZ(z);
		int maxR = Math.max(cellsX, cellsZ);
		for (int r = 0; r <= maxR; r++) {
			double best = Double.MAX_VALUE;
			double[] out = null;
			for (int cz = pz - r; cz <= pz + r; cz++)
				for (int cx = px - r; cx <= px + r; cx++) {
					if (Math.max(Math.abs(cx - px), Math.abs(cz - pz)) != r) continue; // only the ring at distance r
					if (!inGrid(cx, cz) || !emptyAround(cx, cz, ring)) continue;
					double dx = centreX(cx) - x, dz = centreZ(cz) - z, d = dx * dx + dz * dz;
					if (d < best) { best = d; out = new double[]{centreX(cx), centreZ(cz)}; }
				}
			if (out != null) return out;
		}
		return null;
	}

	private boolean emptyAround(int cx, int cz, int ring) {
		for (int dz = -ring; dz <= ring; dz++) for (int dx = -ring; dx <= ring; dx++) {
			int x = cx + dx, z = cz + dz;
			if (!inGrid(x, z)) return false;
			if (visited.get(z * cellsX + x)) return false;
		}
		return true;
	}

	/**
	 * A small text map: {@code size} x {@code size} characters, each summarising a block of cells.
	 * '#' mostly visited, '+' partly visited, '.' only loaded, ' ' unknown.
	 */
	public char[][] miniMap(int size) {
		char[][] out = new char[size][size];
		for (int my = 0; my < size; my++)
			for (int mx = 0; mx < size; mx++) {
				int x0 = mx * cellsX / size, x1 = (mx + 1) * cellsX / size, z0 = my * cellsZ / size, z1 = (my + 1) * cellsZ / size;
				int n = 0, v = 0, l = 0;
				for (int cz = z0; cz < Math.max(z1, z0 + 1); cz++) for (int cx = x0; cx < Math.max(x1, x0 + 1); cx++) {
					if (!inGrid(cx, cz)) continue;
					n++;
					if (visited.get(cz * cellsX + cx)) v++;
					else if (loaded.get(cz * cellsX + cx)) l++;
				}
				out[my][mx] = n == 0 ? ' ' : v * 2 >= n ? '#' : v > 0 ? '+' : l * 2 >= n ? '.' : ' ';
			}
		return out;
	}

	/** Map cell (column, row) of a world position in {@link #miniMap(int)} coordinates. */
	public int[] miniMapCell(double x, double z, int size) {
		int mx = (int) Math.floor((x - minX) / (double) (maxX - minX + 1) * size);
		int my = (int) Math.floor((z - minZ) / (double) (maxZ - minZ + 1) * size);
		return new int[]{Math.max(0, Math.min(size - 1, mx)), Math.max(0, Math.min(size - 1, my))};
	}

	// ------------------------------------------------------------------ saving

	public record Saved(int minX, int maxX, int minZ, int maxZ, String visited, String loaded) {}

	public Saved save() {
		Base64.Encoder enc = Base64.getEncoder();
		return new Saved(minX, maxX, minZ, maxZ, enc.encodeToString(visited.toByteArray()), enc.encodeToString(loaded.toByteArray()));
	}

	/** Restores a save; returns a fresh grid if the bounds changed since. */
	public static Coverage restore(Saved s, int minX, int maxX, int minZ, int maxZ) {
		Coverage c = new Coverage(minX, maxX, minZ, maxZ);
		if (s == null || s.minX() != minX || s.maxX() != maxX || s.minZ() != minZ || s.maxZ() != maxZ) return c;
		Base64.Decoder dec = Base64.getDecoder();
		BitSet v = BitSet.valueOf(dec.decode(s.visited())), l = BitSet.valueOf(dec.decode(s.loaded()));
		int n = c.cells();
		for (int i = v.nextSetBit(0); i >= 0 && i < n; i = v.nextSetBit(i + 1)) { c.visited.set(i); c.visitedCount++; }
		for (int i = l.nextSetBit(0); i >= 0 && i < n; i = l.nextSetBit(i + 1)) { c.loaded.set(i); c.loadedCount++; }
		return c;
	}
}
