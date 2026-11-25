package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.etherwarp.SolidQuery;
import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayDeque;

/**
 * A coarse "distance through open space" to the goal, used to guide the leg search around walls
 * (otherwise it heads straight at the goal and has to search sideways block by block).
 * <p>
 * The box around start and goal (plus a margin) is cut into {@link #CELL}^3 cells. Two neighbouring cells
 * are connected if a straight line of air runs between their centres (any of 5 parallel lines). A
 * breadth-first search from the goal's cell gives each cell a step count; {@link #distance} turns that into
 * blocks and never returns less than the straight-line distance.
 */
final class CoarseField {
	static final int CELL = 4;
	static final double STEP_FACTOR = 0.8;
	private static final int MARGIN = 64;
	/** Fields bigger than this many cells aren't built (straight-line distance is used instead). */
	private static final int MAX_CELLS = 2_500_000;

	private final int x0, y0, z0, nx, ny, nz;
	private final int[] steps; // -1 = unreached
	private final Vec3 goal;

	private CoarseField(int x0, int y0, int z0, int nx, int ny, int nz, Vec3 goal) {
		this.x0 = x0; this.y0 = y0; this.z0 = z0; this.nx = nx; this.ny = ny; this.nz = nz;
		this.steps = new int[nx * ny * nz];
		this.goal = goal;
		java.util.Arrays.fill(steps, -1);
	}

	/** Builds the field, or returns null if the box would be too large. */
	static CoarseField build(Vec3 start, Vec3 goal, SolidQuery solid) {
		int x0 = (int) Math.floor(Math.min(start.x(), goal.x())) - MARGIN, x1 = (int) Math.floor(Math.max(start.x(), goal.x())) + MARGIN;
		int y0 = (int) Math.floor(Math.min(start.y(), goal.y())) - MARGIN, y1 = (int) Math.floor(Math.max(start.y(), goal.y())) + MARGIN;
		int z0 = (int) Math.floor(Math.min(start.z(), goal.z())) - MARGIN, z1 = (int) Math.floor(Math.max(start.z(), goal.z())) + MARGIN;
		int nx = (x1 - x0) / CELL + 1, ny = (y1 - y0) / CELL + 1, nz = (z1 - z0) / CELL + 1;
		if ((long) nx * ny * nz > MAX_CELLS) return null;
		CoarseField f = new CoarseField(x0, y0, z0, nx, ny, nz, goal);

		// Two neighbouring cells are connected if any of 5 parallel straight lines of air runs between their
		// centres (thin walls inside a cell still block, unlike a plain "any air in the cell" test)
		int[][] offsets = {{0, 0}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
		int gx = f.cx(goal.x()), gy = f.cy(goal.y()), gz = f.cz(goal.z());
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		f.steps[f.idx(gx, gy, gz)] = 0;
		queue.add(new int[]{gx, gy, gz});
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		while (!queue.isEmpty()) {
			int[] c = queue.poll();
			int s = f.steps[f.idx(c[0], c[1], c[2])];
			for (int[] d : dirs) {
				int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
				if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) continue;
				int i = f.idx(x, y, z);
				if (f.steps[i] >= 0 || !f.connected(c, d, offsets, solid)) continue;
				f.steps[i] = s + 1;
				queue.add(new int[]{x, y, z});
			}
		}
		return f;
	}

	/** Is there a straight line of air from cell c's centre to its neighbour in direction d? */
	private boolean connected(int[] c, int[] d, int[][] offsets, SolidQuery solid) {
		int bx = x0 + c[0] * CELL + CELL / 2, by = y0 + c[1] * CELL + CELL / 2, bz = z0 + c[2] * CELL + CELL / 2;
		for (int[] o : offsets) {
			// Offsets lie in the plane across the direction of travel
			int ox = d[0] != 0 ? 0 : o[0], oy = d[1] != 0 ? 0 : (d[0] != 0 ? o[0] : o[1]), oz = d[2] != 0 ? 0 : o[1];
			boolean free = true;
			for (int k = 0; k <= CELL && free; k++)
				if (solid.solid(bx + ox + d[0] * k, by + oy + d[1] * k, bz + oz + d[2] * k)) free = false;
			if (free) return true;
		}
		return false;
	}

	private int idx(int x, int y, int z) { return (y * nz + z) * nx + x; }
	private int cx(double x) { return Math.max(0, Math.min(nx - 1, (int) Math.floor((x - x0) / CELL))); }
	private int cy(double y) { return Math.max(0, Math.min(ny - 1, (int) Math.floor((y - y0) / CELL))); }
	private int cz(double z) { return Math.max(0, Math.min(nz - 1, (int) Math.floor((z - z0) / CELL))); }

	/**
	 * Blocks to the goal through open space (at least the straight-line distance). Unreached cells (closed off
	 * at this coarse scale) fall back to the straight-line distance.
	 */
	double distance(Vec3 p) {
		double straight = p.sub(goal).length();
		int s = steps[idx(cx(p.x()), cy(p.y()), cz(p.z()))];
		if (s <= 1) return straight;
		// Steps go along the axes, so diagonals count a bit long; 0.8 per cell keeps it close to real distances
		return Math.max(straight, (s - 1) * CELL * STEP_FACTOR);
	}
}
