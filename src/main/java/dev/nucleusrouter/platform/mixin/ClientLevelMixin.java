package dev.nucleusrouter.platform.mixin;

import dev.nucleusrouter.platform.world.WorldCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports every block change the client applies (server block updates, bomb blasts, your own
 * mining) to the world model. Read-only: it only observes, it never changes the result.
 * Fabric API has no client block-change event, hence the mixin.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void nucleusrouter$onSetBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) WorldCapture.onBlockChanged((ClientLevel) (Object) this, pos, state);
	}
}
