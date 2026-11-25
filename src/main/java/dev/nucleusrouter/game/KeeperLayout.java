package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each Mines of Divan keeper stands relative to the keepers' centre. The same in every lobby
 * (checked on the user's saves 2026-11-25: Lapis +33/+3, Emerald +3/-33, Diamond -33/-3, Gold -3/+33 in x/z),
 * because structures don't rotate. Once known, seeing any one keeper gives the centre.
 *
 * @param offsets keeper name (e.g. "Keeper of Lapis") to {dx, dy, dz} from the centre
 */
public record KeeperLayout(Map<String, double[]> offsets) {
	public static final int KEEPERS = 4;

	/** Learns the layout from all 4 keepers seen in one lobby, or null if not all 4 (with distinct names) are known. */
	public static KeeperLayout learn(List<StructureMap.Npc> npcs) {
		if (npcs == null || npcs.size() != KEEPERS) return null;
		Set<String> names = new HashSet<>();
		double cx = 0, cy = 0, cz = 0;
		for (StructureMap.Npc n : npcs) {
			if (n.label == null || !names.add(n.label)) return null;
			cx += n.x; cy += n.y; cz += n.z;
		}
		cx /= KEEPERS; cy /= KEEPERS; cz /= KEEPERS;
		Map<String, double[]> off = new LinkedHashMap<>();
		for (StructureMap.Npc n : npcs) off.put(n.label, new double[]{n.x - cx, n.y - cy, n.z - cz});
		return new KeeperLayout(off);
	}

	/** The centre from whichever keepers were seen (averaged), or null if none of their names is known. */
	public Vec3 centreFrom(List<StructureMap.Npc> npcs) {
		double x = 0, y = 0, z = 0;
		int k = 0;
		for (StructureMap.Npc n : npcs) {
			double[] o = n.label == null ? null : offsets.get(n.label);
			if (o == null) continue;
			x += n.x - o[0]; y += n.y - o[1]; z += n.z - o[2];
			k++;
		}
		return k == 0 ? null : new Vec3(x / k, y / k, z / k);
	}

	/** Where a keeper stands, given the centre (for drawing the keepers you haven't seen). */
	public Vec3 keeperAt(String name, Vec3 centre) {
		double[] o = offsets.get(name);
		return o == null ? null : new Vec3(centre.x() + o[0], centre.y() + o[1], centre.z() + o[2]);
	}
}
