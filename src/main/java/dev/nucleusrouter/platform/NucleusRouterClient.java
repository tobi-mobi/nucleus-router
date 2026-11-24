package dev.nucleusrouter.platform;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.command.NrCommands;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Everything that touches Minecraft or Fabric lives in the
 * {@code platform} package; {@code core} and {@code game} stay plain Java.
 */
public final class NucleusRouterClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger(NucleusRouter.MOD_ID);

	@Override
	public void onInitializeClient() {
		ConfigManager.load();
		LocationTracker.init();
		NrCommands.register();
		LOG.info("[Nucleus Router] {} loaded (render-only; never sends input, chat or packets).", NucleusRouter.VERSION_LABEL);
	}

	/** "[NR] " chat prefix. */
	public static MutableComponent prefix() {
		return Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	/** Adds a line to your own chat window. Client-side only: nothing is sent to the server. */
	public static void showLocal(Component message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(prefix().append(message)));
	}
}
