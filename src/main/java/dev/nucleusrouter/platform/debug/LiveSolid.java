package dev.nucleusrouter.platform.debug;

import dev.nucleusrouter.core.etherwarp.SolidQuery;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Etherwarp solidity read straight from the client world, for the live debug overlay.
 * Air and the "ray passes through" blocks (water, lava) are not solid; everything else is a full cube.
 * Client thread only.
 */
final class LiveSolid implements SolidQuery {
	private final ClientLevel level;
	private final Set<String> passThrough;
	private final Map<BlockState, Boolean> cache = new IdentityHashMap<>();
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	LiveSolid(ClientLevel level, Set<String> passThrough) {
		this.level = level;
		this.passThrough = passThrough;
	}

	@Override
	public boolean solid(int x, int y, int z) {
		BlockState s = level.getBlockState(pos.set(x, y, z));
		if (s.isAir()) return false;
		return cache.computeIfAbsent(s, st -> !passThrough.contains(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()));
	}
}
