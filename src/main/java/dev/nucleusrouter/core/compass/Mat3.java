package dev.nucleusrouter.core.compass;

import dev.nucleusrouter.core.geom.Vec3;

/** Small 3x3 symmetric-matrix helpers for the compass solver. */
final class Mat3 {
	private Mat3() {}

	static double det(double[][] m) {
		return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
				- m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
				+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
	}

	/** Inverse, or null if (nearly) singular. */
	static double[][] inverse(double[][] m) {
		double det = det(m);
		double scale = 0;
		for (double[] r : m) for (double v : r) scale = Math.max(scale, Math.abs(v));
		if (scale == 0 || Math.abs(det) < 1e-12 * scale * scale * scale) return null;
		double[][] inv = new double[3][3];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				int r0 = (j + 1) % 3, r1 = (j + 2) % 3, c0 = (i + 1) % 3, c1 = (i + 2) % 3;
				inv[i][j] = (m[r0][c0] * m[r1][c1] - m[r0][c1] * m[r1][c0]) / det;
			}
		}
		return inv;
	}

	static Vec3 mul(double[][] m, Vec3 v) {
		return new Vec3(m[0][0] * v.x() + m[0][1] * v.y() + m[0][2] * v.z(),
				m[1][0] * v.x() + m[1][1] * v.y() + m[1][2] * v.z(),
				m[2][0] * v.x() + m[2][1] * v.y() + m[2][2] * v.z());
	}

	/** Unit eigenvector of the largest eigenvalue of a symmetric positive semi-definite matrix (power iteration). */
	static Vec3 largestEigenvector(double[][] m, Vec3 start) {
		Vec3 v = start;
		for (int i = 0; i < 100; i++) {
			Vec3 w = mul(m, v);
			double len = w.length();
			if (len < 1e-15) return null;
			w = w.scale(1 / len);
			if (w.sub(v).length() < 1e-12) return w;
			v = w;
		}
		return v;
	}

	/** Largest eigenvalue of a symmetric positive semi-definite matrix. */
	static double largestEigenvalue(double[][] m) {
		Vec3 v = largestEigenvector(m, new Vec3(0.6, 0.5, 0.62).normalize());
		if (v == null) return 0;
		return mul(m, v).dot(v);
	}
}
