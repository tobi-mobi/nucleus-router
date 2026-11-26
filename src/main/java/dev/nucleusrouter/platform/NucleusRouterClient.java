package dev.nucleusrouter.platform;

import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.command.NrCommands;
import dev.nucleusrouter.platform.config.ConfigManager;
import dev.nucleusrouter.platform.location.LocationTracker;
import dev.nucleusrouter.platform.world.WorldCapture;
import dev.nucleusrouter.platform.render.WorldDraw;
import dev.nucleusrouter.platform.debug.DebugRay;
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
		dev.nucleusrouter.platform.world.BlockRules.init();
		LocationTracker.init();
		WorldCapture.init();
		WorldDraw.init();
		DebugRay.init();
		dev.nucleusrouter.platform.debug.BombCalibration.init();
		dev.nucleusrouter.platform.structure.StructureTracker.init();
		dev.nucleusrouter.platform.compass.CompassTracker.init();
		dev.nucleusrouter.platform.structure.StructureShapes.init();
		dev.nucleusrouter.platform.structure.ExplorationTracker.init();
		dev.nucleusrouter.platform.structure.LobbyWatcher.init();
		dev.nucleusrouter.platform.structure.StructureHud.init();
		dev.nucleusrouter.platform.structure.CrystalScanner.init();
		dev.nucleusrouter.platform.structure.KeeperLayouts.init();
		dev.nucleusrouter.platform.structure.SetPointStore.load();
		dev.nucleusrouter.platform.structure.StopsTracker.init();
		dev.nucleusrouter.platform.structure.RunTracker.init();
		dev.nucleusrouter.platform.structure.RunHud.init();
		dev.nucleusrouter.platform.structure.SetupPreview.init();
		dev.nucleusrouter.platform.structure.SetupGuide.init();
		dev.nucleusrouter.platform.structure.RouteVerifier.init();
		Keys.init();
		NrCommands.register();
		LOG.info("[Nucleus Router] {} loaded (render-only; never sends input, chat or packets).", NucleusRouter.VERSION_LABEL);
	}

	/** "[NR] " chat prefix. */
	public static MutableComponent prefix() {
		return Component.literal("[NR] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	/** For CompletableFuture.exceptionally: log the error and show a short message in chat. */
	public static Void reportAsyncError(Throwable t) {
		LOG.error("[Nucleus Router] Background task failed", t);
		showLocal(Component.literal("Something went wrong: " + t.getMessage() + " (details in the game log)").withStyle(ChatFormatting.RED));
		return null;
	}

	/** Adds a line to your own chat window. Client-side only: nothing is sent to the server. */
	public static void showLocal(Component message) {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(prefix().append(message)));
	}
}
