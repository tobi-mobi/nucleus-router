package dev.nucleusrouter.platform.mixin;

import dev.nucleusrouter.platform.debug.DebugRay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
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
		dev.nucleusrouter.platform.structure.RunHud.onServerTeleport();
	}

	/** If the server sends a vanilla explosion packet, /nr calibrate bomb records its centre and radius. */
	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void nucleusrouter$afterExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		dev.nucleusrouter.platform.debug.BombCalibration.onExplosion(packet.center(), packet.radius(), packet.blockCount());
	}

	/** Particles from the server, collected for a few seconds after a Wishing Compass use. */
	@Inject(method = "handleParticleEvent", at = @At("TAIL"))
	private void nucleusrouter$afterParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		dev.nucleusrouter.platform.compass.CompassTracker.onParticle(packet.getParticle(), packet.getX(), packet.getY(), packet.getZ());
	}
}
