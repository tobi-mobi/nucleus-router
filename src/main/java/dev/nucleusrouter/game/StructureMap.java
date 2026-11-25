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
 *   <li><b>CRYSTAL</b>: the barrier box that holds the structure's crystal (exact; Queen's den, Khazad-dum).</li>
 *   <li><b>NPC</b>: an NPC or mob name tag such as "King Yolkar" was seen there (exact).</li>
 *   <li><b>SKYBLOCKER</b>: Skyblocker's Crystal Hollows waypoint (its compass solver, chat coordinates or
 *       shared by other players; usually close, not exact).</li>
 *   <li><b>AREA</b>: the scoreboard showed the structure's area name while you stood there (rough).</li>
 * </ol>
 * Plain fields so it can be saved as JSON as-is. Not thread-safe: used from the client thread only.
 */
public final class StructureMap {
	/** Name tags of one structure closer than this are the same NPC (the 4 keepers are far apart). */
	public static final double SAME_NPC_DISTANCE = 8.0;
	/**
	 * For other structures, name tags closer than this are the same NPC. Small, because the Jungle Temple
	 * has two Door Guardians a few blocks apart (seen in-game 2026-11-24) that must not be averaged.
	 */
	public static final double SAME_NPC_DISTANCE_SINGLE = 1.5;
	/** Running average weight cap, so a slowly moving mob (Bal) is still followed. */
	static final int MAX_SAMPLES = 100;

	public enum Source { MANUAL, CRYSTAL, NPC, SKYBLOCKER, AREA }

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
		/** Barrier box around the crystal (inclusive block corners), or null. */
		public int[] crystalMin, crystalMax;
		/** How the structure is turned compared with the saved capture ({@link Orientation} name), or null if unknown. */
		public String orientation;
		/** Skyblocker's waypoint {x, y, z}, or null. */
		public double[] skyblocker;
		public boolean skyblockerVerified;
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
		if (s.mobileAnchor) {
			if (!e.npcs.isEmpty()) match = e.npcs.get(0); // one boss that walks around
		} else {
			double r = s.expectedAnchors > 1 ? SAME_NPC_DISTANCE : SAME_NPC_DISTANCE_SINGLE;
			for (Npc n : e.npcs) {
				double dx = n.x - x, dy = n.y - y, dz = n.z - z;
				if (dx * dx + dz * dz <= r * r && Math.abs(dy) <= 4) { match = n; break; }
			}
			// A body found: forget label-only sightings right next to it (they were the same NPC)
			if (onBody) {
				Npc keep = match;
				e.npcs.removeIf(n -> n != keep && !n.onBody && Math.hypot(n.x - x, n.z - z) <= 3 && Math.abs(n.y - y) <= 4);
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

	/** Records the barrier box around the crystal. Returns true the first time. */
	public boolean setCrystal(Structure s, int[] min, int[] max) {
		Entry e = entry(s);
		if (java.util.Arrays.equals(e.crystalMin, min) && java.util.Arrays.equals(e.crystalMax, max)) return false;
		boolean first = e.crystalMin == null;
		e.crystalMin = min.clone();
		e.crystalMax = max.clone();
		version++;
		return first;
	}

	/** Barrier box {min, max} around the crystal, or null. */
	public int[][] crystalBox(Structure s) {
		Entry e = peek(s);
		return e == null || e.crystalMin == null || e.crystalMax == null ? null : new int[][]{e.crystalMin.clone(), e.crystalMax.clone()};
	}

	/** Records Skyblocker's waypoint for {@code s} (null removes it). Returns true if it changed. */
	public boolean setSkyblocker(Structure s, double[] pos, boolean verified) {
		Entry e = pos == null ? peek(s) : entry(s);
		if (e == null) return false;
		if (java.util.Arrays.equals(e.skyblocker, pos) && e.skyblockerVerified == (pos != null && verified)) return false;
		e.skyblocker = pos == null ? null : pos.clone();
		e.skyblockerVerified = pos != null && verified;
		version++;
		return true;
	}

	/** Orientation found by template matching, or {@link Orientation#R0} if not known (yet). */
	public Orientation orientation(Structure s) {
		Entry e = peek(s);
		if (e == null || e.orientation == null) return Orientation.R0;
		try { return Orientation.valueOf(e.orientation); } catch (IllegalArgumentException ex) { return Orientation.R0; }
	}

	public boolean orientationKnown(Structure s) {
		Entry e = peek(s);
		return e != null && e.orientation != null;
	}

	public void setOrientation(Structure s, Orientation o) {
		Entry e = entry(s);
		String n = o == null ? null : o.name();
		if (java.util.Objects.equals(e.orientation, n)) return;
		e.orientation = n;
		version++;
	}

	/** True if the position is exact (manual, crystal or NPC), so zones and captures can use it. */
	public static boolean exact(Location l) {
		return l != null && (l.source() == Source.MANUAL || l.source() == Source.CRYSTAL || l.source() == Source.NPC);
	}

	/** Anchor block {x, y, z} (the block containing the best position), or null if not exactly known. */
	public int[] anchorBlock(Structure s) {
		Location l = locate(s);
		if (!exact(l)) return null;
		return new int[]{(int) Math.floor(l.pos().x()), (int) Math.floor(l.pos().y()), (int) Math.floor(l.pos().z())};
	}

	/** World box of the structure's zone, or null if its position isn't exactly known. */
	public int[] zoneBox(Structure s, StructureZone zone) {
		int[] a = anchorBlock(s);
		return a == null || zone == null ? null : zone.worldBox(a[0], a[1], a[2], orientation(s));
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
		if (e.crystalMin != null && e.crystalMax != null) {
			Vec3 c = new Vec3((e.crystalMin[0] + e.crystalMax[0] + 1) / 2.0, (e.crystalMin[1] + e.crystalMax[1] + 1) / 2.0, (e.crystalMin[2] + e.crystalMax[2] + 1) / 2.0);
			return new Location(s, c, Source.CRYSTAL, 0, "crystal (barrier box)");
		}
		if (e.npcs != null && !e.npcs.isEmpty()) {
			int k = e.npcs.size();
			if (s.expectedAnchors > 1) {
				double x = 0, y = 0, z = 0;
				for (Npc n : e.npcs) { x += n.x; y += n.y; z += n.z; }
				return new Location(s, new Vec3(x / k, y / k, z / k), Source.NPC, 0, k + " of " + s.expectedAnchors + " name tags (" + s.anchorNoun + ")");
			}
			// One NPC expected: use the first one found on its body (the temple has two equal Door Guardians)
			Npc n = e.npcs.get(0);
			for (Npc o : e.npcs) if (o.onBody) { n = o; break; }
			return new Location(s, n.pos(), Source.NPC, 0, "name tag " + n.label + (k > 1 ? " (" + k + " seen)" : ""));
		}
		if (e.skyblocker != null && e.skyblocker.length == 3) {
			return new Location(s, new Vec3(e.skyblocker[0], e.skyblocker[1], e.skyblocker[2]), Source.SKYBLOCKER, 16,
					"Skyblocker waypoint" + (e.skyblockerVerified ? " (verified)" : ""));
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
