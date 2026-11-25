package dev.nucleusrouter.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoverageTest {
	private static Coverage hollows() { return new Coverage(202, 823, 202, 823); }

	@Test
	void visitingMarksADiscAndCountsOnce() {
		Coverage c = hollows();
		assertEquals(0, c.visitedFraction());
		c.visit(512, 512, 48);
		double once = c.visitedFraction();
		// Disc of radius 48 = ~7238 blocks^2 out of 622^2 = 386884 -> about 1.9%
		assertEquals(Math.PI * 48 * 48 / (622.0 * 622.0), once, 0.002);
		c.visit(512, 512, 48);
		assertEquals(once, c.visitedFraction(), "same spot twice counts once");
		assertTrue(c.loadedFraction() >= once, "visited implies loaded");
	}

	@Test
	void chunksOnlyCountAsLoaded() {
		Coverage c = hollows();
		c.loadChunk(512, 512);
		assertEquals(0, c.visitedFraction());
		assertEquals(25, c.loadedFraction() * c.cells(), 1e-9, "the grid starts at 202, not on a chunk edge, so a chunk touches 5 x 5 cells");
		c.loadChunk(0, 0); // outside the Hollows: ignored
		assertEquals(25, c.loadedFraction() * c.cells(), 1e-9);
	}

	@Test
	void walkingEverywhereCoversEverything() {
		Coverage c = hollows();
		for (int x = 202; x <= 823; x += 40) for (int z = 202; z <= 823; z += 40) c.visit(x, z, 48);
		assertEquals(1.0, c.visitedFraction(), 1e-9);
		assertNull(c.nearestUnexplored(512, 512, 2));
	}

	@Test
	void pointsToTheNearestUnexploredStretch() {
		Coverage c = hollows();
		// Explore the whole west half (x < 512)
		for (int x = 202; x <= 500; x += 20) for (int z = 202; z <= 823; z += 20) c.visit(x, z, 30);
		double[] p = c.nearestUnexplored(400, 500, 2);
		assertNotNull(p);
		assertTrue(p[0] > 500, "the unexplored stretch is to the east: " + p[0]);
		assertEquals(500, p[1], 12);
	}

	@Test
	void saveAndRestore() {
		Coverage c = hollows();
		c.visit(300, 700, 48);
		c.loadChunk(640, 640);
		Coverage back = Coverage.restore(c.save(), 202, 823, 202, 823);
		assertEquals(c.visitedFraction(), back.visitedFraction());
		assertEquals(c.loadedFraction(), back.loadedFraction());
		assertEquals(0, Coverage.restore(c.save(), 200, 823, 202, 823).visitedFraction(), "other bounds start fresh");
	}

	@Test
	void miniMapShowsWhereYouWere() {
		Coverage c = hollows();
		c.visit(220, 220, 30); // north-west corner
		char[][] m = c.miniMap(16);
		assertEquals('#', m[0][0]);
		assertEquals(' ', m[15][15]);
		assertArrayEquals(new int[]{0, 0}, c.miniMapCell(205, 205, 16));
		assertArrayEquals(new int[]{15, 15}, c.miniMapCell(823, 823, 16));
	}
}
