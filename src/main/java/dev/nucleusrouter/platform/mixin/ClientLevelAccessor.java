package dev.nucleusrouter.platform.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the client's block prediction handler (to tell your own placements from server changes). */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	@Accessor("blockStatePredictionHandler")
	BlockStatePredictionHandler nucleusrouter$predictionHandler();
}
