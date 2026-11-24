package dev.nucleusrouter.core.location;

/**
 * What one source says about where the player is. Any field may be null when that source
 * doesn't know it.
 *
 * @param inHollows whether this source says we're in the Crystal Hollows
 * @param serverId  canonical server id (e.g. "mini123AB"), see {@link LocationParser#canonicalServerId}
 * @param area      area name as shown by the source (tab: "Crystal Hollows", scoreboard: "Goblin Holdout")
 * @param pingMs    ping shown by the source, if any
 */
public record LocationReading(Source source, boolean inHollows, String serverId, String area, Integer pingMs) {
	public enum Source { MOD_API, TAB_LIST, SCOREBOARD }
}
