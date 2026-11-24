package dev.nucleusrouter.game;

/**
 * The structures a Crystal Nucleus run visits (SPEC §2.2, §4). Their positions are random per lobby,
 * so the mod has to find them; see {@link StructureMap}.
 */
public enum Structure {
	MINES_OF_DIVAN("divan", "Mines of Divan", "Jade", "Mines of Divan", 4, "keepers", false, false),
	PRECURSOR_CITY("precursor", "Lost Precursor City", "Sapphire", "Lost Precursor City", 1, "Professor Robot", false, false),
	/**
	 * King Yolkar sits in the Goblin Holdout, but "Goblin Holdout" covers a whole quarter of the
	 * Hollows, so it gives no useful area hint (confirmed 2026-11-24: his area shows "Goblin Holdout").
	 */
	KING_YOLKAR("king", "King Yolkar", "Amber (King)", null, 1, "King Yolkar", false, false),
	GOBLIN_QUEENS_DEN("queen", "Goblin Queen's Den", "Amber", HollowsAreas.GOBLIN_QUEENS_DEN, 1, "Goblin Queen", true, false),
	JUNGLE_TEMPLE("temple", "Jungle Temple", "Amethyst", "Jungle Temple", 1, "Door Guardian", false, false),
	KHAZAD_DUM("khazad", "Khazad-dûm", "Topaz", "Khazad-dûm", 1, "Bal", true, true);

	/** Short name used in commands, e.g. {@code /nr structure set king}. */
	public final String key;
	public final String displayName;
	public final String crystal;
	/** Scoreboard area name shown while inside it, or null if the area name doesn't help. */
	public final String areaName;
	/** How many separate NPC name tags mark it (the Mines of Divan have 4 keepers). */
	public final int expectedAnchors;
	/** What the name tags are, for messages. */
	public final String anchorNoun;
	/**
	 * Marked at its crystal (the barrier box around it) rather than an NPC: the Queen's den has no NPC,
	 * and Bal is fought standing on the Topaz crystal (user, 2026-11-24).
	 */
	public final boolean markCrystal;
	/** The anchor NPC walks around (Bal), so all its name tags are one moving anchor. */
	public final boolean mobileAnchor;

	Structure(String key, String displayName, String crystal, String areaName, int expectedAnchors, String anchorNoun, boolean markCrystal, boolean mobileAnchor) {
		this.markCrystal = markCrystal;
		this.mobileAnchor = mobileAnchor;
		this.expectedAnchors = expectedAnchors;
		this.anchorNoun = anchorNoun;
		this.key = key;
		this.displayName = displayName;
		this.crystal = crystal;
		this.areaName = areaName;
	}

	public static Structure byKey(String key) {
		for (Structure s : values()) if (s.key.equalsIgnoreCase(key) || s.name().equalsIgnoreCase(key)) return s;
		return null;
	}

	/** The structure whose scoreboard area this is, or null. */
	public static Structure byArea(String area) {
		if (area == null) return null;
		for (Structure s : values()) if (s.areaName != null && s.areaName.equalsIgnoreCase(area.trim())) return s;
		return null;
	}
}
