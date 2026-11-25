package dev.nucleusrouter.core.etherwarp;

import dev.nucleusrouter.core.geom.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NormalTeleportTest {
	@Test
	void twelveBlocksPerClickAndStopsInFrontOfAWall() {
		SolidQuery world = (x, y, z) -> y < 100 || x >= 30;
		Vec3 start = new Vec3(0.5, 100, 0.5), east = new Vec3(1, 0, 0);
		assertEquals(new Vec3(12.5, 100, 0.5), NormalTeleport.click(start, east, 12, world));
		List<Vec3> pts = NormalTeleport.stretch(start, east, 12, 5, world);
		assertEquals(3, pts.size(), "the third click hits the wall; after that it doesn't move");
		assertTrue(pts.get(2).x() < 30 && pts.get(2).x() > 28, "stopped just in front of the wall: " + pts.get(2));
	}

	@Test
	void worksUpAndDownToo() {
		SolidQuery world = (x, y, z) -> y < 100 || y > 140;
		Vec3 up = NormalTeleport.click(new Vec3(0.5, 100, 0.5), new Vec3(0, 1, 0), 12, world);
		assertEquals(112, up.y(), 1e-9);
		Vec3 capped = NormalTeleport.click(new Vec3(0.5, 130, 0.5), new Vec3(0, 1, 0), 12, world);
		assertTrue(capped.y() + 1.8 < 141, "head stays under the ceiling: " + capped);
	}
}
