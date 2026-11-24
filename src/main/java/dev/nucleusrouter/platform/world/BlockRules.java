package dev.nucleusrouter.platform.world;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.world.BlockClassifier;
import dev.nucleusrouter.platform.NucleusRouterClient;
import dev.nucleusrouter.platform.config.ConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Block facts that come from Minecraft itself, so they don't have to be typed into the config.
 *
 * Confirmed in-game 2026-11-24: etherwarp ignores water, lava, torches, grass and flowers. All of
 * them are blocks you can walk through (no collision box), so with
 * {@code etherwarp.noCollisionPassesThrough} on (default) every block without a collision box
 * is treated as not existing for etherwarp. The config list {@code etherwarp.rayPassesThrough}
 * is added on top.
 */
public final class BlockRules {
	private static volatile Set<String> noCollision = Set.of();

	private BlockRules() {}

	/** Reads every registered block's default collision box once. Call on the client thread at startup. */
	public static void init() {
		Set<String> out = new HashSet<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			try {
				if (block.defaultBlockState().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
					out.add(BuiltInRegistries.BLOCK.getKey(block).toString());
				}
			} catch (RuntimeException e) {
				// A block that needs a real world to answer: leave it solid (conservative)
			}
		}
		noCollision = Set.copyOf(out);
		NucleusRouterClient.LOG.info("[Nucleus Router] {} block types have no collision box (invisible to etherwarp).", out.size());
	}

	/** All block ids that don't exist for etherwarp under the current config. */
	public static Set<String> passThrough(NrConfig cfg) {
		Set<String> all = new HashSet<>(cfg.etherwarp.rayPassesThrough);
		if (cfg.etherwarp.noCollisionPassesThrough) all.addAll(noCollision);
		return all;
	}

	public static BlockClassifier classifier() {
		NrConfig cfg = ConfigManager.get();
		return BlockClassifier.fromConfig(cfg, passThrough(cfg));
	}

	public static boolean hasNoCollision(String blockId) { return noCollision.contains(blockId); }
}
