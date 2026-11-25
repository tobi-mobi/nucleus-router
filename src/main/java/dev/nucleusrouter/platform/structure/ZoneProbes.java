package dev.nucleusrouter.platform.structure;

import com.google.gson.Gson;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.game.Orientation;
import dev.nucleusrouter.game.Structure;
import dev.nucleusrouter.game.StructureMap;
import dev.nucleusrouter.game.StructureZone;
import dev.nucleusrouter.platform.NucleusRouterClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records how calibrated Bob-ombs behaved next to structures (SPEC §2.4: a blast that touches a
 * structure's zone does nothing). Each throw near a found structure is appended to
 * {@code nucleusrouter/calibration/zone-probes.jsonl}, with the blast centre relative to the structure's
 * anchor (in template orientation), so the real zone bounds can be fitted from several throws.
 */
public final class ZoneProbes {
	/** Throws whose blast ball comes within this many blocks of a zone are reported. */
	private static final double REPORT_DISTANCE = 30;
	private static final Gson GSON = new Gson();

	private ZoneProbes() {}

	/** Returns one readable line per nearby structure (empty if none is near). Called on the client thread. */
	public static List<String> record(Vec3 centre, double radius, boolean fizzled) {
		List<String> lines = new ArrayList<>();
		if (centre == null) return lines;
		StructureMap map = StructureTracker.map();
		for (Structure s : Structure.values()) {
			int[] box = map.zoneBox(s, StructureShapes.zone(s));
			int[] a = map.anchorBlock(s);
			if (box == null || a == null) continue;
			double gap = StructureZone.distance(box, centre.x(), centre.y(), centre.z()) - radius;
			if (gap > REPORT_DISTANCE) continue;
			boolean predictedFizzle = StructureZone.fizzles(box, centre.x(), centre.y(), centre.z());
			String verdict = predictedFizzle == fizzled ? "zone guess agrees"
					: fizzled ? "zone guess is too SMALL here (bomb fizzled outside it)"
					: "zone guess is too BIG here (bomb worked inside it)";
			lines.add(String.format("%s: bomb %s, zone guess said it would %s -> %s", s.displayName,
					fizzled ? "fizzled" : "exploded", predictedFizzle ? "fizzle" : "explode", verdict));

			Orientation o = map.orientation(s);
			int[] rel = o.invert((int) Math.floor(centre.x()) - a[0], (int) Math.floor(centre.z()) - a[2]);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("time", java.time.LocalDateTime.now().toString());
			row.put("lobby", StructureTracker.lobby());
			row.put("structure", s.key);
			row.put("anchor", a);
			row.put("anchorSource", map.locate(s).source().name());
			row.put("orientation", map.orientationKnown(s) ? o.name() : null);
			row.put("centre", new double[]{centre.x(), centre.y(), centre.z()});
			row.put("centreRelative", new int[]{rel[0], (int) Math.floor(centre.y()) - a[1], rel[1]});
			row.put("radius", radius);
			row.put("fizzled", fizzled);
			row.put("zoneGuessSaidFizzle", predictedFizzle);
			append(GSON.toJson(row));
		}
		return lines;
	}

	private static void append(String json) {
		Path file = FabricLoader.getInstance().getGameDir().resolve("nucleusrouter").resolve("calibration").resolve("zone-probes.jsonl");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			NucleusRouterClient.LOG.warn("[Nucleus Router] Could not write {}: {}", file, e.toString());
		}
	}
}
