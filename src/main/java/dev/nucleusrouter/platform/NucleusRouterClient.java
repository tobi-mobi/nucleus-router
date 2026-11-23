package dev.nucleusrouter.platform;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.config.ConfigManager;
import net.fabricmc.api.ClientModInitializer;
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
		LOG.info("[Nucleus Router] {} loaded (render-only; never sends input, chat or packets).", NucleusRouter.VERSION_LABEL);
	}
}
