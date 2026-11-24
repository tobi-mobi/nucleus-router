package dev.nucleusrouter.platform.mixin;

import dev.nucleusrouter.platform.world.WorldCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports every block change applied to the client world to the world model. Read-only: it
 * only observes and never changes the result.
 *
 * Why Level and not ClientLevel: server-sent changes (your drill, mining spread, respawns, bombs)
 * go through {@code ClientLevel.setServerVerifiedBlockState}, which calls {@code Level.setBlock}
 * directly and skips the {@code ClientLevel.setBlock} override (found in-game 2026-11-24).
 * Both paths end here. Fabric API has no client block-change event, hence the mixin.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void nucleusrouter$onSetBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) return;
		if ((Object) this instanceof ClientLevel level) {
			// Predicting = the client is applying your own action ahead of the server (e.g. placing a block).
			boolean byPlayer = ((ClientLevelAccessor) level).nucleusrouter$predictionHandler().isPredicting();
			WorldCapture.onBlockChanged(level, pos, state, byPlayer);
		}
	}
}
