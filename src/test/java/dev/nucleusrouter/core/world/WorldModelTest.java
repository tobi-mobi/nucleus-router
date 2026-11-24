package dev.nucleusrouter.core.world;

import dev.nucleusrouter.core.config.NrConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static dev.nucleusrouter.core.world.VoxelClass.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldModelTest {
	private BlockClassifier classifier;
	private WorldModel model;
	private int air, stone, gem, oak, cobble, water, bedrock;

	@BeforeEach
	void setUp() {
		classifier = BlockClassifier.fromConfig(new NrConfig());
		model = new WorldModel(classifier);
		air = model.blockId("minecraft:air");
		stone = model.blockId("minecraft:stone");
		gem = model.blockId("minecraft:red_stained_glass");
		oak = model.blockId("minecraft:oak_log");
		cobble = model.blockId("minecraft:cobblestone");
		water = model.blockId("minecraft:water");
		bedrock = model.blockId("minecraft:bedrock");
	}

	@Test
	void classifierUsesConfigLists() {
		assertEquals(BlockClassifier.Kind.AIR, classifier.classify("minecraft:cave_air"));
		assertEquals(BlockClassifier.Kind.HARD_STONE, classifier.classify("minecraft:stone"));
		assertEquals(BlockClassifier.Kind.UNBREAKABLE, classifier.classify("minecraft:jungle_log"));
		assertEquals(BlockClassifier.Kind.UNBREAKABLE, classifier.classify("minecraft:podzol"));
		assertEquals(BlockClassifier.Kind.PASS_THROUGH, classifier.classify("minecraft:lava"));
		assertEquals(BlockClassifier.Kind.PLACEABLE, classifier.classify("minecraft:cobblestone"));
		assertEquals(BlockClassifier.Kind.RESPAWNING, classifier.classify("minecraft:red_stained_glass_pane"));
		assertEquals(BlockClassifier.Kind.RESPAWNING, classifier.classify("minecraft:prismarine"));
	}

	@Test
	void unseenIsUnknownAndSolid() {
		WorldSnapshot s = model.snapshot();
		assertEquals(UNKNOWN, s.classAt(500, 100, 500));
		assertTrue(s.plannedSolid(500, 100, 500));
		assertNull(s.currentBlock(500, 100, 500));
	}

	@Test
	void hardStoneStaysGoneOnceBroken() {
		model.observe(300, 100, 300, stone);
		assertEquals(HARD_STONE, model.snapshot().classAt(300, 100, 300));
		model.observe(300, 100, 300, air);
		assertEquals(AIR, model.snapshot().classAt(300, 100, 300));
		assertFalse(model.snapshot().plannedSolid(300, 100, 300));
	}

	@Test
	void respawningBlocksAreStickySolid() {
		model.observe(300, 100, 300, gem);
		model.observe(300, 100, 300, air); // mined
		WorldSnapshot s = model.snapshot();
		assertEquals(RESPAWNING_SOLID, s.classAt(300, 100, 300));
		assertTrue(s.plannedSolid(300, 100, 300));
		assertTrue(s.currentlyAir(300, 100, 300));
		assertEquals("minecraft:red_stained_glass", s.originalBlock(300, 100, 300));
	}

	@Test
	void respawnSeenAfterFirstSeeingAirBecomesSticky() {
		model.observe(300, 100, 300, air); // first seen already mined
		model.observe(300, 100, 300, gem); // it respawns
		model.observe(300, 100, 300, air); // mined again
		assertEquals(RESPAWNING_SOLID, model.snapshot().classAt(300, 100, 300));
	}

	@Test
	void placedBlocksInAirStayPlacedAndCanBeRemoved() {
		model.observe(300, 100, 300, air);
		model.observe(300, 100, 300, cobble);
		assertEquals(PLACED, model.snapshot().classAt(300, 100, 300));
		model.observe(300, 100, 300, air);
		assertEquals(AIR, model.snapshot().classAt(300, 100, 300));
		// Placed into a spot where Hard Stone was bombed away
		model.observe(301, 100, 300, stone);
		model.observe(301, 100, 300, air);
		model.observe(301, 100, 300, cobble);
		assertEquals(PLACED, model.snapshot().classAt(301, 100, 300));
	}

	@Test
	void unbreakableAndFluids() {
		model.observe(300, 100, 300, oak);
		model.observe(301, 100, 300, water);
		model.observe(302, 100, 300, bedrock);
		WorldSnapshot s = model.snapshot();
		assertEquals(UNBREAKABLE, s.classAt(300, 100, 300));
		assertEquals(PASS_THROUGH, s.classAt(301, 100, 300));
		assertFalse(s.plannedSolid(301, 100, 300), "the etherwarp ray passes through water and lava");
		assertEquals(UNBREAKABLE, s.classAt(302, 100, 300));
	}

	@Test
	void yourOwnPlacementsArePlacedWhateverTheType() {
		// Glass would normally count as a respawning gemstone...
		model.observe(300, 100, 300, air);
		model.observe(300, 100, 300, gem, true);
		assertEquals(PLACED, model.snapshot().classAt(300, 100, 300));
		// ...placed into a hole where Hard Stone was bombed away
		model.observe(301, 100, 300, stone);
		model.observe(301, 100, 300, air);
		model.observe(301, 100, 300, gem, true);
		assertEquals(PLACED, model.snapshot().classAt(301, 100, 300));
		// Placing Hard Stone into air also counts as placed
		model.observe(302, 100, 300, air);
		model.observe(302, 100, 300, stone, true);
		assertEquals(PLACED, model.snapshot().classAt(302, 100, 300));
		// The same glass appearing on its own (server) is treated as a respawning gemstone
		model.observe(303, 100, 300, air);
		model.observe(303, 100, 300, gem);
		assertEquals(RESPAWNING_SOLID, model.snapshot().classAt(303, 100, 300));
	}

	@Test
	void mergeKeepsBlocksPlacedLastSession() {
		WorldModel older = new WorldModel(classifier);
		older.observe(300, 100, 300, older.blockId("minecraft:air"));
		older.observe(300, 100, 300, older.blockId("minecraft:red_stained_glass"), true);
		model.observe(300, 100, 300, gem); // this session we first saw it already there
		model.mergeOlder(older);
		assertEquals(PLACED, model.snapshot().classAt(300, 100, 300));
	}

	@Test
	void snapshotsNeverChangeAfterwards() {
		model.observe(300, 100, 300, stone);
		WorldSnapshot before = model.snapshot();
		model.observe(300, 100, 300, air);
		model.observe(300, 101, 300, stone);
		WorldSnapshot after = model.snapshot();
		assertEquals(HARD_STONE, before.classAt(300, 100, 300));
		assertEquals(UNKNOWN, before.classAt(300, 101, 300));
		assertEquals(AIR, after.classAt(300, 100, 300));
		assertEquals(HARD_STONE, after.classAt(300, 101, 300));
		assertTrue(after.version() > before.version());
	}

	@Test
	void wholeSectionObservationAndNegativeCoordinates() {
		int[] ids = new int[4096];
		Arrays.fill(ids, stone);
		ids[Section.index(3, 4, 5)] = gem;
		model.observeSection(-2, 6, 40, ids);
		WorldSnapshot s = model.snapshot();
		assertEquals(HARD_STONE, s.classAt(-32, 96, 640));
		assertEquals(RESPAWNING_SOLID, s.classAt(-32 + 3, 96 + 4, 640 + 5));
		assertEquals(1, s.sectionCount());
		assertEquals(4096L, s.countClasses().values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	void sectionKeyRoundTrips() {
		for (int[] c : new int[][]{{0, 0, 0}, {-1, -4, -1}, {51, 11, 51}, {-2000, 19, 3000}}) {
			long k = Section.key(c[0], c[1], c[2]);
			assertEquals(c[0], Section.keyX(k));
			assertEquals(c[1], Section.keyY(k));
			assertEquals(c[2], Section.keyZ(k));
		}
	}

	@Test
	void paletteOverflowIsSafe() {
		// 300 different blocks inside one section (x 304..319, y 112..127)
		for (int i = 0; i < 300; i++) model.observe(304 + (i & 15), 112 + ((i >> 4) & 15), 304, model.blockId("test:block_" + i));
		WorldSnapshot s = model.snapshot();
		assertEquals(1, s.sectionCount());
		assertEquals(BlockClassifier.OVERFLOW_BLOCK, s.currentBlock(304 + (299 & 15), 112 + ((299 >> 4) & 15), 304));
		assertTrue(s.plannedSolid(304 + (299 & 15), 112 + ((299 >> 4) & 15), 304));
		// Earlier entries are untouched (the bug this guards against overwrote one)
		assertEquals("test:block_253", s.originalBlock(304 + (253 & 15), 112 + ((253 >> 4) & 15), 304));
		assertEquals("test:block_100", s.originalBlock(304 + (100 & 15), 112 + ((100 >> 4) & 15), 304));
	}

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		model.observe(300, 100, 300, gem);
		model.observe(300, 100, 300, air);
		model.observe(301, 100, 300, stone);
		model.observe(302, 100, 300, cobble);
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		WorldStore.write(model.snapshot(), new WorldStore.Meta("mini89R", 356020, 1_000L), buf);

		WorldStore.Loaded loaded = WorldStore.read(new ByteArrayInputStream(buf.toByteArray()), classifier);
		assertEquals("mini89R", loaded.meta().serverId());
		assertEquals(356020, loaded.meta().clockTicks());
		WorldSnapshot s = loaded.model().snapshot();
		assertEquals(RESPAWNING_SOLID, s.classAt(300, 100, 300));
		assertEquals(HARD_STONE, s.classAt(301, 100, 300));
		assertEquals(PLACED, s.classAt(302, 100, 300));
		assertEquals(UNKNOWN, s.classAt(303, 100, 300));
	}

	@Test
	void corruptFileGivesReadableError() {
		IOException e = assertThrows(IOException.class, () -> WorldStore.read(new ByteArrayInputStream(new byte[]{1, 2, 3}), classifier));
		assertNotNull(e.getMessage());
	}

	@Test
	void mergeOlderKeepsMemoryFromLastSession() {
		// Last session: saw a gemstone (then mined it) and bombed some Hard Stone
		WorldModel older = new WorldModel(classifier);
		older.observe(300, 100, 300, older.blockId("minecraft:red_stained_glass"));
		older.observe(300, 100, 300, older.blockId("minecraft:air"));
		older.observe(301, 100, 300, older.blockId("minecraft:stone"));
		older.observe(301, 100, 300, older.blockId("minecraft:air"));
		older.observe(302, 100, 300, older.blockId("minecraft:stone"));

		// This session (before the save was loaded) we first saw both spots as air,
		// and someone placed cobblestone at a third.
		model.observe(300, 100, 300, air);
		model.observe(301, 100, 300, air);
		model.observe(302, 100, 300, air);
		model.observe(303, 100, 300, cobble);

		model.mergeOlder(older);
		WorldSnapshot s = model.snapshot();
		assertEquals(RESPAWNING_SOLID, s.classAt(300, 100, 300), "gem spot stays remembered");
		assertEquals(AIR, s.classAt(301, 100, 300), "bombed Hard Stone stays gone");
		assertEquals(AIR, s.classAt(302, 100, 300), "stone mined since the save is now air");
		assertEquals("minecraft:stone", s.originalBlock(302, 100, 300));
		assertEquals(PLACED, s.classAt(303, 100, 300), "this session's data survives the merge");
	}

	@Test
	void classifierChangeReclassifies() {
		model.observe(300, 100, 300, model.blockId("minecraft:andesite"));
		assertEquals(RESPAWNING_SOLID, model.snapshot().classAt(300, 100, 300));
		NrConfig cfg = new NrConfig();
		cfg.bombs.hardStoneBlocks.add("minecraft:andesite");
		model.setClassifier(BlockClassifier.fromConfig(cfg));
		Map<VoxelClass, Long> counts = model.snapshot().countClasses();
		assertEquals(1L, counts.get(HARD_STONE));
	}

	@Test
	void lobbyIdentity() {
		long hour = 3_600_000L;
		// Back after 10 minutes, clock moved 12000 ticks: same lobby
		assertTrue(LobbyIdentity.isSameLobby(356_020, 0, 356_020 + 12_000, 10 * 60_000L));
		// Clock went backwards: new lobby on a reused server name
		assertFalse(LobbyIdentity.isSameLobby(356_020, 0, 5_000, 10 * 60_000L));
		// 3 hours later but clock barely moved: new lobby that's already a bit old
		assertFalse(LobbyIdentity.isSameLobby(24_000, 0, 48_000, 3 * hour));
		// Laggy server running at 60 % speed still counts as the same lobby
		assertTrue(LobbyIdentity.isSameLobby(100_000, 0, 100_000 + (long) (72_000 * 0.6), hour));
		// Rejoin right away
		assertTrue(LobbyIdentity.isSameLobby(100_000, 0, 100_050, 2_000));
	}
}
