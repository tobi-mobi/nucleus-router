package dev.nucleusrouter.game;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a crystal pickup in chat (confirmed in-game 2026-11-26): every crystal gives the same message,
 * "✦ CRYSTAL FOUND (1/5)" followed by the crystal's name ("Amber Crystal"), only the name changes. Whether the
 * game sends that as one chat message or as separate lines isn't known, so both work: the name counts if it is in
 * the same message as the heading or arrives within {@link #NAME_WITHIN_MS} after it. Pure logic.
 */
public final class CrystalPickup {
	/** The crystal name line may come this long after the heading line. */
	public static final long NAME_WITHIN_MS = 2000;
	private static final Pattern NAME = Pattern.compile("\\b(Jade|Sapphire|Amber|Amethyst|Topaz) Crystal\\b");

	private final String heading;
	private long headingMs = -1;

	/** @param heading the heading text, e.g. "CRYSTAL FOUND" ({@code run.crystalFoundMessage}) */
	public CrystalPickup(String heading) {
		this.heading = heading == null ? "" : heading.trim().toLowerCase(Locale.ROOT);
	}

	/** Feed every chat message; returns the crystal picked up ("Amber") or null. */
	public String onChat(long now, String text) {
		if (text == null || heading.isEmpty()) return null;
		String plain = text.replaceAll("§.", "");
		boolean hasHeading = plain.toLowerCase(Locale.ROOT).contains(heading);
		if (hasHeading) headingMs = now;
		if (headingMs < 0 || now - headingMs > NAME_WITHIN_MS) return null;
		// The name must come after the heading when both are in one message (the line under it)
		String rest = hasHeading ? plain.substring(plain.toLowerCase(Locale.ROOT).indexOf(heading) + heading.length()) : plain;
		Matcher m = NAME.matcher(rest);
		if (!m.find()) return null;
		headingMs = -1; // one pickup per heading
		return m.group(1);
	}
}
