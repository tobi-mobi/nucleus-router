package dev.nucleusrouter.platform.mixin;

import dev.nucleusrouter.platform.debug.DebugRay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices when the server teleports the player (e.g. after an etherwarp), for /nr debug ray.
 * Read-only. TAIL only runs on the client thread, after the packet has been applied.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void nucleusrouter$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		DebugRay.onServerTeleport();
	}
}
