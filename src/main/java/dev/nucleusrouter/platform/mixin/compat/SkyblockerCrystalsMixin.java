package dev.nucleusrouter.platform.mixin.compat;

import de.hysky.skyblocker.skyblock.dwarven.MiningLocationLabel;
import de.hysky.skyblocker.utils.render.primitive.PrimitiveCollector;
import dev.nucleusrouter.platform.structure.SkyblockerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Optional: skips drawing some of Skyblocker's Crystal Hollows waypoints to reduce clutter (setting
 * "Skyblocker's waypoints" on the Structures page). Only the drawing is skipped; Skyblocker still
 * collects and shares them, and Nucleus Router still reads them. Does nothing if Skyblocker is missing
 * or its code changed ({@code @Pseudo}, {@code require = 0}).
 */
@Pseudo
@Mixin(targets = "de.hysky.skyblocker.skyblock.dwarven.CrystalsLocationsManager", remap = false)
public abstract class SkyblockerCrystalsMixin {
	@Redirect(method = "extractRendering", require = 0, remap = false,
			at = @At(value = "INVOKE", remap = false,
					target = "Lde/hysky/skyblocker/skyblock/dwarven/MiningLocationLabel;extractRendering(Lde/hysky/skyblocker/utils/render/primitive/PrimitiveCollector;)V"))
	private static void nucleusrouter$maybeDraw(MiningLocationLabel label, PrimitiveCollector collector) {
		String category = label.category() instanceof Enum<?> e ? e.name() : null;
		if (!SkyblockerBridge.shouldHide(category)) label.extractRendering(collector);
	}
}
