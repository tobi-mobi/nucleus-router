package dev.nucleusrouter.platform;

import com.mojang.blaze3d.platform.InputConstants;
import dev.nucleusrouter.core.NucleusRouter;
import dev.nucleusrouter.platform.structure.ExploreMapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Key bindings (Options > Controls > Key Binds > "Nucleus Router"). Only open the mod's own screens. */
public final class Keys {
	private static KeyMapping map;

	private Keys() {}

	public static void init() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(NucleusRouter.MOD_ID, "main"));
		map = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nucleusrouter.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (map.consumeClick()) {
				if (client.gui.screen() == null) client.gui.setScreen(new ExploreMapScreen());
			}
		});
	}
}
