package dev.nucleusrouter.platform.structure;

import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.platform.NucleusRouterClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Skyblocker's Crystal Hollows waypoints (its Wishing Compass solver, coordinates from chat, and
 * waypoints shared by other Skyblocker users). Read-only, by reflection, so Nucleus Router works the same
 * without Skyblocker or with a version that changed these internals: then this just returns nothing.
 *
 * Checked against Skyblocker 6.11.0+26.2: {@code CrystalsLocationsManager.activeWaypoints}
 * (Map name -> MiningLocationLabel), {@code verifiedWaypoints} (List of names),
 * {@code MiningLocationLabel.category()} (enum CrystalHollowsLocationsCategory) and
 * {@code Waypoint.pos} (BlockPos).
 */
public final class SkyblockerBridge {
	public record Waypoint(double x, double y, double z, boolean verified) {}

	private static boolean tried, available;
	private static Field activeField, verifiedField, posField;
	private static Method categoryMethod;
	private static String failure = "not checked yet";

	private SkyblockerBridge() {}

	public static String status() { return available ? "reading Skyblocker waypoints" : failure; }

	private static void setUp() {
		tried = true;
		if (!FabricLoader.getInstance().isModLoaded("skyblocker")) { failure = "Skyblocker not installed"; return; }
		try {
			Class<?> mgr = Class.forName("de.hysky.skyblocker.skyblock.dwarven.CrystalsLocationsManager");
			activeField = mgr.getDeclaredField("activeWaypoints");
			activeField.setAccessible(true);
			verifiedField = mgr.getDeclaredField("verifiedWaypoints");
			verifiedField.setAccessible(true);
			Class<?> label = Class.forName("de.hysky.skyblocker.skyblock.dwarven.MiningLocationLabel");
			categoryMethod = label.getMethod("category");
			posField = Class.forName("de.hysky.skyblocker.utils.waypoint.Waypoint").getField("pos");
			available = true;
			NucleusRouterClient.LOG.info("[Nucleus Router] Reading Skyblocker's Crystal Hollows waypoints (read-only).");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failure = "Skyblocker found, but its waypoints can't be read (" + e.getClass().getSimpleName() + ")";
			NucleusRouterClient.LOG.warn("[Nucleus Router] {}: {}", failure, e.toString());
		}
	}

	/** Skyblocker's current waypoints for the run's structures. Call on the client thread. */
	public static Map<Structure, Waypoint> read() {
		Map<Structure, Waypoint> out = new EnumMap<>(Structure.class);
		if (!tried) setUp();
		if (!available) return out;
		try {
			Map<?, ?> active = (Map<?, ?>) activeField.get(null);
			Object verifiedObj = verifiedField.get(null);
			Collection<?> verified = verifiedObj instanceof Collection<?> c ? c : List.of();
			if (active == null) return out;
			for (Map.Entry<?, ?> e : active.entrySet()) {
				Object wp = e.getValue();
				if (wp == null) continue;
				Object cat = categoryMethod.invoke(wp);
				Structure s = cat instanceof Enum<?> en ? map(en.name()) : null;
				if (s == null) continue;
				Object pos = posField.get(wp);
				if (!(pos instanceof net.minecraft.core.BlockPos bp)) continue;
				out.put(s, new Waypoint(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5, verified.contains(e.getKey())));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			available = false;
			failure = "reading Skyblocker waypoints failed (" + e.getClass().getSimpleName() + ")";
			NucleusRouterClient.LOG.warn("[Nucleus Router] {}: {}", failure, e.toString());
		}
		return out;
	}

	private static Structure map(String category) {
		return switch (category) {
			case "MINES_OF_DIVAN" -> Structure.MINES_OF_DIVAN;
			case "LOST_PRECURSOR_CITY" -> Structure.PRECURSOR_CITY;
			case "KING_YOLKAR" -> Structure.KING_YOLKAR;
			case "GOBLIN_QUEENS_DEN" -> Structure.GOBLIN_QUEENS_DEN;
			case "JUNGLE_TEMPLE" -> Structure.JUNGLE_TEMPLE;
			case "KHAZAD_DUM" -> Structure.KHAZAD_DUM;
			default -> null;
		};
	}
}
