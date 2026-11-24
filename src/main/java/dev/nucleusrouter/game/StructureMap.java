package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each structure is in the current lobby (SPEC §3.5, §4), from three kinds of evidence, best first:
 * <ol>
 *   <li><b>MANUAL</b>: set by the user with {@code /nr structure set}; always wins.</li>
 *   <li><b>NPC</b>: an NPC or mob name tag such as "King Yolkar" was seen there (exact).</li>
 *   <li><b>AREA</b>: the scoreboard showed the structure's area name while you stood there (rough).</li>
 * </ol>
 * Plain fields so it can be saved as JSON as-is. Not thread-safe: used from the client thread only.
 */
public final class StructureMap {
	/** Name tags of one structure closer than this are the same NPC (the 4 keepers are far apart). */
	public static final double SAME_NPC_DISTANCE = 8.0;
	/** Running average weight cap, so a slowly moving mob (Bal) is still followed. */
	static final int MAX_SAMPLES = 100;

	public enum Source { MANUAL, NPC, AREA }

	/** One NPC name tag, averaged over every sighting. */
	public static final class Npc {
		public String label;
		public double x, y, z;
		public int samples;
		public long firstSeenMs, lastSeenMs;
		/** True if the position is the NPC's body (found under the name tag), false if only the floating tag. */
		public boolean onBody;

		public Vec3 pos() { return new Vec3(x, y, z); }
	}

	public static final class Entry {
		public List<Npc> npcs = new ArrayList<>();
		/** {x, y, z} set by the user, or null. */
		public double[] manual;
		/** Box around the positions where you stood while the scoreboard showed this structure's area, or null. */
		public double[] areaMin, areaMax;
	}

	/**
	 * @param pos         best known position
	 * @param uncertainty roughly how far off it may be, in blocks (0 for NPC and manual)
	 * @param detail      short human-readable reason, e.g. "name tag King Yolkar"
	 */
	public record Location(Structure structure, Vec3 pos, Source source, double uncertainty, String detail) {}

	/** Keyed by {@link Structure#name()} (plain strings keep the JSON simple). */
	public Map<String, Entry> entries = new LinkedHashMap<>();
	private transient long version;

	/** Bumped on every change; used to decide when to save. */
	public long version() { return version; }

	private Entry entry(Structure s) {
		if (entries == null) entries = new LinkedHashMap<>();
		return entries.computeIfAbsent(s.name(), k -> new Entry());
	}

	private Entry peek(Structure s) {
		return entries == null ? null : entries.get(s.name());
	}

	/** Same as {@link #observeNametag(Structure, String, double, double, double, long, boolean)} with no body found. */
	public boolean observeNametag(Structure s, String label, double x, double y, double z, long nowMs) {
		return observeNametag(s, label, x, y, z, nowMs, false);
	}

	/**
	 * Records a name tag sighting at (x, y, z). {@code onBody} says the position is the NPC's body under
	 * the tag; such a sighting replaces tag-only ones and is never averaged with them.
	 * Returns true if this is the first NPC evidence for the structure (so the caller can announce "found").
	 */
	public boolean observeNametag(Structure s, String label, double x, double y, double z, long nowMs, boolean onBody) {
		Entry e = entry(s);
		boolean first = e.npcs.isEmpty();
		Npc match = null;
		if (s.expectedAnchors <= 1) {
			if (!e.npcs.isEmpty()) match = e.npcs.get(0);
		} else {
			for (Npc n : e.npcs) {
				double dx = n.x - x, dy = n.y - y, dz = n.z - z;
				if (dx * dx + dy * dy + dz * dz <= SAME_NPC_DISTANCE * SAME_NPC_DISTANCE) { match = n; break; }
			}
		}
		if (match != null && match.onBody && !onBody) return false; // keep the better evidence
		if (match != null && onBody && !match.onBody) match.samples = 0; // restart on the body
		if (match == null) {
			match = new Npc();
			match.firstSeenMs = nowMs;
			e.npcs.add(match);
		}
		match.onBody = onBody;
		int n = Math.min(match.samples, MAX_SAMPLES - 1);
		match.x = (match.x * n + x) / (n + 1);
		match.y = (match.y * n + y) / (n + 1);
		match.z = (match.z * n + z) / (n + 1);
		match.samples = n + 1;
		match.label = label;
		match.lastSeenMs = nowMs;
		version++;
		return first;
	}

	/** Records that you stood at (x, y, z) while the scoreboard showed {@code s}'s area. Returns true the first time. */
	public boolean observeArea(Structure s, double x, double y, double z) {
		Entry e = entry(s);
		boolean first = e.areaMin == null;
		if (first) {
			e.areaMin = new double[]{x, y, z};
			e.areaMax = new double[]{x, y, z};
			version++;
			return true;
		}
		double[] p = {x, y, z};
		boolean grew = false;
		for (int i = 0; i < 3; i++) {
			if (p[i] < e.areaMin[i]) { e.areaMin[i] = p[i]; grew = true; }
			if (p[i] > e.areaMax[i]) { e.areaMax[i] = p[i]; grew = true; }
		}
		if (grew) version++;
		return false;
	}

	public void setManual(Structure s, double x, double y, double z) {
		entry(s).manual = new double[]{x, y, z};
		version++;
	}

	/** Forgets everything about one structure (manual point, name tags and area). */
	public void clear(Structure s) {
		if (entries != null && entries.remove(s.name()) != null) version++;
	}

	public void clearAll() {
		if (entries != null && !entries.isEmpty()) { entries.clear(); version++; }
	}

	/** Best position for {@code s}, or null if nothing is known. */
	public Location locate(Structure s) {
		Entry e = peek(s);
		if (e == null) return null;
		if (e.manual != null && e.manual.length == 3)
			return new Location(s, new Vec3(e.manual[0], e.manual[1], e.manual[2]), Source.MANUAL, 0, "set by you");
		if (e.npcs != null && !e.npcs.isEmpty()) {
			double x = 0, y = 0, z = 0;
			for (Npc n : e.npcs) { x += n.x; y += n.y; z += n.z; }
			int k = e.npcs.size();
			String detail = s.expectedAnchors > 1
					? k + " of " + s.expectedAnchors + " name tags (" + s.anchorNoun + ")"
					: "name tag " + e.npcs.get(0).label;
			return new Location(s, new Vec3(x / k, y / k, z / k), Source.NPC, 0, detail);
		}
		if (e.areaMin != null && e.areaMax != null) {
			Vec3 min = new Vec3(e.areaMin[0], e.areaMin[1], e.areaMin[2]);
			Vec3 max = new Vec3(e.areaMax[0], e.areaMax[1], e.areaMax[2]);
			Vec3 centre = min.add(max).scale(0.5);
			double unc = Math.max(16, max.sub(min).length() / 2 + 8);
			return new Location(s, centre, Source.AREA, unc, "scoreboard area, from where you stood");
		}
		return null;
	}

	/** Name tags seen for {@code s} (for drawing each keeper separately). */
	public List<Npc> npcs(Structure s) {
		Entry e = peek(s);
		return e == null || e.npcs == null ? List.of() : List.copyOf(e.npcs);
	}

	/** Every structure with a known position, in {@link Structure} order. */
	public List<Location> located() {
		List<Location> out = new ArrayList<>();
		for (Structure s : Structure.values()) {
			Location l = locate(s);
			if (l != null) out.add(l);
		}
		return out;
	}

	/** Structures with no position yet, or only a rough area one. */
	public List<Structure> missing() {
		List<Structure> out = new ArrayList<>();
		for (Structure s : Structure.values()) {
			Location l = locate(s);
			if (l == null || l.source() == Source.AREA) out.add(s);
		}
		return out;
	}
}
