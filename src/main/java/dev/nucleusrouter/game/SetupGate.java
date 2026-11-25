package dev.nucleusrouter.game;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC §4: setup planning starts only once every required structure is found. "Found" means an exact
 * position (manual, crystal box or NPC name tag); a Skyblocker waypoint is only approximate and does
 * not open the gate, because routes and zones need exact anchors.
 */
public final class SetupGate {
	/**
	 * @param ready        every structure has an exact position
	 * @param found        structures with an exact position
	 * @param approximate  only an approximate position (Skyblocker or rough area): go closer
	 * @param missing      nothing known
	 */
	public record Status(boolean ready, List<Structure> found, List<Structure> approximate, List<Structure> missing) {
		public int total() { return found.size() + approximate.size() + missing.size(); }
	}

	private SetupGate() {}

	public static Status check(StructureMap map) {
		List<Structure> found = new ArrayList<>(), approx = new ArrayList<>(), missing = new ArrayList<>();
		for (Structure s : Structure.values()) {
			StructureMap.Location l = map.locate(s);
			if (l == null) missing.add(s);
			else if (StructureMap.exact(l)) found.add(s);
			else approx.add(s);
		}
		return new Status(approx.isEmpty() && missing.isEmpty(), found, approx, missing);
	}
}
