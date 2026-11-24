package dev.nucleusrouter.platform.location;

import net.hypixel.data.type.ServerType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

/**
 * Listens for Hypixel Mod API location packets. This class is only loaded when the
 * Hypixel Mod API mod is installed, so the mod still works without it.
 *
 * Render-only rule: we only add a listener. We never call subscribeToEventPacket or sendPacket,
 * so this mod causes no extra packets. Location packets arrive because other mods in the pack
 * (e.g. SkyHanni) already subscribe to them. If none do, the tab list and scoreboard are used instead.
 */
final class HypixelApiBridge {
	private HypixelApiBridge() {}

	static void install() {
		HypixelModAPI.getInstance().createHandler(ClientboundLocationPacket.class, packet ->
				LocationTracker.onModApiLocation(
						packet.getServerName(),
						packet.getServerType().map(ServerType::name).orElse(null),
						packet.getMode().orElse(null),
						packet.getMap().orElse(null)));
	}
}
