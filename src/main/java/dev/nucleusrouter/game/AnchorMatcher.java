package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.location.LocationParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether an entity's name tag (e.g. "King Yolkar") marks one of the structures.
 * The names come from config ({@code structures.*Names}), because the exact name tags are
 * game facts to confirm in-game. A name matches if it contains one of the configured phrases
 * as whole words, ignoring case and colour codes ("Bal" matches "[Lv100] Bal 1M", not "Balloon").
 */
public final class AnchorMatcher {
	private record Rule(Structure structure, Pattern pattern) {}

	private final List<Rule> rules = new ArrayList<>();

	public AnchorMatcher(NrConfig.Structures cfg) {
		add(Structure.MINES_OF_DIVAN, cfg.minesOfDivanNames);
		add(Structure.PRECURSOR_CITY, cfg.precursorCityNames);
		add(Structure.KING_YOLKAR, cfg.kingYolkarNames);
		add(Structure.GOBLIN_QUEENS_DEN, cfg.goblinQueenNames);
		add(Structure.JUNGLE_TEMPLE, cfg.jungleTempleNames);
		add(Structure.KHAZAD_DUM, cfg.khazadDumNames);
	}

	private void add(Structure s, List<String> phrases) {
		if (phrases == null) return;
		for (String p : phrases) {
			if (p == null || p.isBlank()) continue;
			rules.add(new Rule(s, Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p.trim()) + "(?![\\p{L}\\p{N}])",
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
		}
	}

	/** The structure this name tag belongs to, or null. {@code rawName} may contain colour codes. */
	public Structure match(String rawName) {
		String name = LocationParser.clean(rawName);
		if (name.isEmpty()) return null;
		for (Rule r : rules) if (r.pattern.matcher(name).find()) return r.structure;
		return null;
	}
}
