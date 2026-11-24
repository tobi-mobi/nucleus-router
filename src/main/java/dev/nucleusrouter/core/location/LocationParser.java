package dev.nucleusrouter.core.location;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw text from the Hypixel Mod API, the tab list and the sidebar scoreboard into
 * {@link LocationReading}s. Pure functions, so every format rule here has a unit test.
 *
 * The exact text formats are Hypixel's and can change. When something stops matching,
 * {@code /nr where} prints the raw inputs so the rule can be fixed.
 */
public final class LocationParser {
	private LocationParser() {}

	/** Hypixel Mod API "mode" for the Crystal Hollows. [VERIFY] via /nr where. */
	public static final String HOLLOWS_MODE = "crystal_hollows";
	/** Area name shown in the tab list and Mod API "map". */
	public static final String HOLLOWS_AREA = "Crystal Hollows";

	private static final Pattern FORMATTING = Pattern.compile("§.");
	private static final Pattern TAB_AREA = Pattern.compile("^\\s*Area:\\s*(.+?)\\s*$");
	private static final Pattern TAB_SERVER = Pattern.compile("^\\s*Server:\\s*(\\S+)\\s*$");
	private static final Pattern TAB_PING = Pattern.compile("^\\s*Ping:\\s*(\\d+)\\s*ms\\s*$", Pattern.CASE_INSENSITIVE);
	/** Scoreboard top line, e.g. "11/24/26 m123AB" (date, then server id). */
	private static final Pattern SB_DATE_SERVER = Pattern.compile("^\\s*\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+(\\S+)");
	/** Scoreboard location line starts with this symbol, e.g. "⏣ Goblin Holdout". */
	private static final char SB_AREA_SYMBOL = '⏣';

	/** Removes §-colour codes and invisible/emoji filler characters Hypixel adds to keep lines unique. */
	public static String clean(String s) {
		if (s == null) return "";
		String noCodes = FORMATTING.matcher(s).replaceAll("");
		StringBuilder out = new StringBuilder(noCodes.length());
		noCodes.codePoints().forEach(cp -> {
			int type = Character.getType(cp);
			boolean drop = Character.isSupplementaryCodePoint(cp)
					|| type == Character.FORMAT || type == Character.PRIVATE_USE
					|| type == Character.CONTROL || type == Character.UNASSIGNED;
			if (!drop) out.appendCodePoint(cp);
		});
		return out.toString().replace(' ', ' ').trim();
	}

	/**
	 * Canonical lobby/server id so every source agrees. The scoreboard abbreviates
	 * "mini123AB" to "m123AB" and "mega12A" to "M12A"; this expands them.
	 */
	public static String canonicalServerId(String raw) {
		if (raw == null) return null;
		String s = clean(raw);
		if (s.isEmpty()) return null;
		if (s.regionMatches(true, 0, "mini", 0, 4) || s.regionMatches(true, 0, "mega", 0, 4)) return s;
		if (s.length() > 1 && Character.isDigit(s.charAt(1))) {
			if (s.charAt(0) == 'm') return "mini" + s.substring(1);
			if (s.charAt(0) == 'M') return "mega" + s.substring(1);
		}
		return s;
	}

	public static LocationReading fromModApi(String serverName, String serverType, String mode, String map) {
		boolean skyblock = serverType == null || serverType.toUpperCase(Locale.ROOT).contains("SKYBLOCK");
		boolean hollows = skyblock && (HOLLOWS_MODE.equalsIgnoreCase(mode) || HOLLOWS_AREA.equalsIgnoreCase(map));
		String area = map != null ? clean(map) : null;
		return new LocationReading(LocationReading.Source.MOD_API, hollows, canonicalServerId(serverName), area, null);
	}

	/** Reads "Area:", "Server:" and "Ping:" widgets from the tab list. Returns null if none are present. */
	public static LocationReading fromTabList(List<String> lines) {
		String area = null, server = null;
		Integer ping = null;
		for (String raw : lines) {
			String line = clean(raw);
			Matcher m;
			if (area == null && (m = TAB_AREA.matcher(line)).matches()) area = m.group(1);
			else if (server == null && (m = TAB_SERVER.matcher(line)).matches()) server = m.group(1);
			else if (ping == null && (m = TAB_PING.matcher(line)).matches()) ping = Integer.parseInt(m.group(1));
		}
		if (area == null && server == null && ping == null) return null;
		boolean hollows = HOLLOWS_AREA.equalsIgnoreCase(area);
		return new LocationReading(LocationReading.Source.TAB_LIST, hollows, canonicalServerId(server), area, ping);
	}

	/**
	 * Reads the sidebar. {@code knownAreas} are scoreboard location names we recognise directly
	 * (the Crystal Hollows sub-areas, e.g. "Goblin Holdout"). Returns null if the sidebar isn't SkyBlock's.
	 */
	public static LocationReading fromScoreboard(String title, List<String> lines, Set<String> knownAreas) {
		if (!clean(title).toUpperCase(Locale.ROOT).contains("SKYBLOCK")) return null;
		String server = null;
		for (String raw : lines) {
			Matcher m;
			if (server == null && (m = SB_DATE_SERVER.matcher(clean(raw))).find()) server = m.group(1);
		}
		String area = scoreboardArea(lines, knownAreas);
		boolean hollows = area != null && (HOLLOWS_AREA.equalsIgnoreCase(area) || knownName(knownAreas, area) != null);
		return new LocationReading(LocationReading.Source.SCOREBOARD, hollows, canonicalServerId(server), area, null);
	}

	/**
	 * Finds the location line of the sidebar. Hypixel marks it with a symbol (⏣, or a custom-font
	 * character that {@link #clean} removes, confirmed 2026-11-24: the line arrives as just "Mines of Divan").
	 * So: first, a line that is exactly a known area name; otherwise a line that starts with ⏣ or a
	 * private-use (custom font) character.
	 */
	public static String scoreboardArea(List<String> lines, Set<String> knownAreas) {
		for (String raw : lines) {
			String known = knownName(knownAreas, clean(raw));
			if (known != null) return known;
		}
		for (String raw : lines) {
			String noCodes = FORMATTING.matcher(raw == null ? "" : raw).replaceAll("").trim();
			if (noCodes.isEmpty()) continue;
			int first = noCodes.codePointAt(0);
			if (first == SB_AREA_SYMBOL || Character.getType(first) == Character.PRIVATE_USE) {
				String area = clean(noCodes.substring(Character.charCount(first)));
				if (!area.isEmpty()) return area;
			}
		}
		return null;
	}

	private static String knownName(Set<String> set, String value) {
		for (String s : set) if (s.equalsIgnoreCase(value)) return s;
		return null;
	}
}
