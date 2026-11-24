package dev.nucleusrouter.core.geom;

/** A block face, named like Minecraft's directions (north = -Z, west = -X). */
public enum Face {
	DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

	public final int dx, dy, dz;

	Face(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }

	public Vec3 normal() { return new Vec3(dx, dy, dz); }
}
