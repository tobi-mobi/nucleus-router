package dev.nucleusrouter.core.plan;

import dev.nucleusrouter.core.geom.Vec3;

/** Fixed sets of look directions the leg planner tries from each spot. */
final class Directions {
	private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

	private Directions() {}

	/** {@code n} directions spread evenly over the whole sphere (Fibonacci sphere). */
	static Vec3[] sphere(int n) {
		Vec3[] out = new Vec3[n];
		for (int i = 0; i < n; i++) {
			double y = 1 - 2 * (i + 0.5) / n;
			double r = Math.sqrt(1 - y * y), t = GOLDEN * i;
			out[i] = new Vec3(Math.cos(t) * r, y, Math.sin(t) * r);
		}
		return out;
	}

	/** {@code n} directions spread evenly inside a cone of half-angle {@code halfDeg} around +z. */
	static Vec3[] cone(int n, double halfDeg) {
		Vec3[] out = new Vec3[n];
		double minCos = Math.cos(Math.toRadians(halfDeg));
		for (int i = 0; i < n; i++) {
			double z = 1 - (1 - minCos) * (i + 0.5) / n;
			double r = Math.sqrt(Math.max(0, 1 - z * z)), t = GOLDEN * i;
			out[i] = new Vec3(Math.cos(t) * r, Math.sin(t) * r, z);
		}
		return out;
	}

	/** Rotates a direction given around +z so that +z points along {@code axis} (unit). */
	static Vec3 rotateTo(Vec3 d, Vec3 axis) {
		// Orthonormal basis (u, v, axis)
		Vec3 helper = Math.abs(axis.y()) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 u = cross(helper, axis).normalize();
		Vec3 v = cross(axis, u);
		return u.scale(d.x()).add(v.scale(d.y())).add(axis.scale(d.z()));
	}

	static Vec3 cross(Vec3 a, Vec3 b) {
		return new Vec3(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
	}

	/** Bucket index (8 yaw x 2 pitch: up or down) of a direction, for the search state. */
	static int bucket(Vec3 d) {
		if (d == null) return 16;
		double yaw = Math.atan2(d.x(), d.z()); // -pi..pi
		int yi = (int) Math.floor((yaw + Math.PI) / (2 * Math.PI) * 8) & 7;
		return (d.y() >= 0 ? 8 : 0) + yi;
	}
}
