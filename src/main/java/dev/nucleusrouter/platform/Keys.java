package dev.nucleusrouter.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.structure.ExploreMapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings (Options > Controls > Key Binds > "Nucleus Router"). They only drive the mod's own screens and
 * checklists, never anything in the game.
 */
public final class Keys {
	private static KeyMapping map;
	private static KeyMapping setupConfirm;

	private Keys() {}

	/** The setup-step key as shown in the controls (e.g. "]"). */
	public static String setupConfirmName() {
		return setupConfirm == null ? "]" : setupConfirm.getTranslatedKeyMessage().getString();
	}

	public static void init() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "main"));
		map = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nucleusrouter.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
		setupConfirm = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nucleusrouter.setupConfirm", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (map.consumeClick()) {
				if (client.gui.screen() == null) client.gui.setScreen(new ExploreMapScreen());
			}
			// Guided setup: mark the current step done (only the mod's own checklist; nothing in the game)
			while (setupConfirm.consumeClick()) {
				String msg = dev.nucleusrouter.platform.structure.SetupGuide.confirm();
				if (msg != null) NucleusRouterClient.showLocal(net.minecraft.network.chat.Component.literal(msg).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
		});
	}
}
