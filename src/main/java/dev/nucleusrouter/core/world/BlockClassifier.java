package dev.nucleusrouter.core.world;

import dev.nucleusrouter.core.config.NrConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sorts block ids (e.g. "minecraft:stone") into {@link Kind}s using the config lists.
 * Rules (SPEC §2.4):
 * <ul>
 *   <li>Hard Stone ({@code bombs.hardStoneBlocks}) stays gone once broken.</li>
 *   <li>Bomb-immune blocks and fluids never break.</li>
 *   <li>Placed-block types ({@code placing.placedBlockTypes}) appearing in air count as placed.</li>
 *   <li>Everything else respawns.</li>
 * </ul>
 */
public final class BlockClassifier {
	public enum Kind {
		AIR(0), HARD_STONE(1), PLACEABLE(1), RESPAWNING(2), UNBREAKABLE(3);

		/** Higher rank wins when deciding what a position "originally" was. */
		final int rank;
		Kind(int rank) { this.rank = rank; }
	}

	public static final Set<String> AIR_BLOCKS = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
	/** Used when a section has too many different blocks to track (should never happen in practice). */
	public static final String OVERFLOW_BLOCK = "nucleusrouter:overflow";

	private final Set<String> hardStone;
	private final Set<String> placeable;
	private final Set<String> immuneExact = new HashSet<>();
	private final List<String> immuneSuffixes;

	public BlockClassifier(List<String> hardStone, List<String> bombImmune, List<String> fluids, List<String> placeable) {
		this.hardStone = Set.copyOf(hardStone);
		this.placeable = Set.copyOf(placeable);
		this.immuneSuffixes = bombImmune.stream().filter(s -> s.startsWith("*")).map(s -> s.substring(1)).toList();
		bombImmune.stream().filter(s -> !s.startsWith("*")).forEach(immuneExact::add);
		immuneExact.addAll(fluids);
	}

	public static BlockClassifier fromConfig(NrConfig cfg) {
		return new BlockClassifier(cfg.bombs.hardStoneBlocks, cfg.bombs.bombImmuneBlocks, cfg.bombs.fluidBlocks, cfg.placing.placedBlockTypes);
	}

	public Kind classify(String blockId) {
		if (blockId == null || AIR_BLOCKS.contains(blockId)) return Kind.AIR;
		if (hardStone.contains(blockId)) return Kind.HARD_STONE;
		if (immuneExact.contains(blockId) || OVERFLOW_BLOCK.equals(blockId)) return Kind.UNBREAKABLE;
		for (String suffix : immuneSuffixes) if (blockId.endsWith(suffix)) return Kind.UNBREAKABLE;
		if (placeable.contains(blockId)) return Kind.PLACEABLE;
		return Kind.RESPAWNING;
	}

	/**
	 * The voxel class from what a position originally was and what is there now.
	 * Sticky: a respawning block stays solid for planning even while it is air.
	 */
	public static VoxelClass voxelClass(Kind original, Kind current, boolean sameBlock) {
		return switch (original) {
			case RESPAWNING -> VoxelClass.RESPAWNING_SOLID;
			case UNBREAKABLE -> VoxelClass.UNBREAKABLE;
			case AIR, HARD_STONE, PLACEABLE -> {
				if (current == Kind.AIR) yield VoxelClass.AIR;
				if (original == Kind.HARD_STONE && sameBlock) yield VoxelClass.HARD_STONE;
				if (original == Kind.HARD_STONE && current == Kind.HARD_STONE) yield VoxelClass.HARD_STONE;
				if (current == Kind.UNBREAKABLE) yield VoxelClass.UNBREAKABLE;
				if (current == Kind.RESPAWNING) yield VoxelClass.RESPAWNING_SOLID;
				yield VoxelClass.PLACED;
			}
		};
	}
}
