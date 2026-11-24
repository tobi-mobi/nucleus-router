package dev.nucleusrouter.core.geom;

/** Small immutable 3D vector (no Minecraft classes in core). */
public record Vec3(double x, double y, double z) {
	public static final Vec3 ZERO = new Vec3(0, 0, 0);

	public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
	public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
	public Vec3 scale(double s) { return new Vec3(x * s, y * s, z * s); }
	public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
	public double length() { return Math.sqrt(x * x + y * y + z * z); }

	public Vec3 normalize() {
		double l = length();
		if (l == 0) throw new IllegalArgumentException("cannot normalize a zero vector");
		return new Vec3(x / l, y / l, z / l);
	}

	/** Angle between two directions, in degrees. */
	public double angleDeg(Vec3 o) {
		double c = normalize().dot(o.normalize());
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
	}

	/** Minecraft look direction from yaw and pitch in degrees (yaw 0 = +Z/south, 90 = -X/west; pitch 90 = down). */
	public static Vec3 fromYawPitch(double yawDeg, double pitchDeg) {
		double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
		double cp = Math.cos(pitch);
		return new Vec3(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
	}

	/** Yaw in degrees (Minecraft convention) of a direction. */
	public double yawDeg() { return Math.toDegrees(Math.atan2(-x, z)); }

	/** Pitch in degrees (Minecraft convention, down is positive) of a direction. */
	public double pitchDeg() { return Math.toDegrees(-Math.atan2(y, Math.sqrt(x * x + z * z))); }

	@Override
	public String toString() { return String.format("(%.3f, %.3f, %.3f)", x, y, z); }
}
