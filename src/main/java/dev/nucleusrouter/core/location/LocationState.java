package dev.nucleusrouter.core.location;

/**
 * The combined, current answer to "where am I?". Immutable; a new one is built on every update.
 *
 * @param inHollows       true when in the Crystal Hollows
 * @param hollowsSource   which source decided {@code inHollows} (null if no source had an answer)
 * @param serverId        canonical lobby id, e.g. "mini123AB" (null if unknown)
 * @param scoreboardArea  sub-area from the sidebar, e.g. "Goblin Holdout" (null if unknown)
 * @param lobbyDay        world clock ticks / 24000 (-1 if no world)
 * @param clockTicks      raw world clock ticks, shown by /nr where for checking
 * @param pingMs          ping used by the cost model
 * @param pingSource      where {@code pingMs} came from
 */
public record LocationState(boolean inHollows, LocationReading.Source hollowsSource, String serverId,
		String scoreboardArea, long lobbyDay, long clockTicks, int pingMs, PingSource pingSource) {

	public enum PingSource { PLAYER_LIST_LATENCY, TAB_WIDGET, FALLBACK_CONFIG }

	public static final LocationState NONE = new LocationState(false, null, null, null, -1, 0, 0, PingSource.FALLBACK_CONFIG);

	/** Lobby day as defined in SPEC §2.1: {@code worldDayTime / 24000}. */
	public static long lobbyDay(long clockTicks) {
		return Math.floorDiv(clockTicks, 24000L);
	}

	/**
	 * Combines the sources. Priority for "in Hollows" and server id: Mod API, then tab list, then scoreboard.
	 * Ping: the player's own tab-list latency if it's above 0, else the tab "Ping:" widget, else the config fallback.
	 */
	public static LocationState resolve(LocationReading modApi, LocationReading tab, LocationReading scoreboard,
			long clockTicks, boolean hasWorld, int playerListLatency, boolean useMeasuredPing, int fallbackPingMs) {
		LocationReading decider = modApi != null ? modApi : tab != null && tab.area() != null ? tab : scoreboard;
		boolean inHollows = decider != null && decider.inHollows();

		String server = firstNonNull(
				modApi != null ? modApi.serverId() : null,
				tab != null ? tab.serverId() : null,
				scoreboard != null ? scoreboard.serverId() : null);

		int ping;
		PingSource pingSource;
		if (useMeasuredPing && playerListLatency > 0) {
			ping = playerListLatency; pingSource = PingSource.PLAYER_LIST_LATENCY;
		} else if (useMeasuredPing && tab != null && tab.pingMs() != null && tab.pingMs() > 0) {
			ping = tab.pingMs(); pingSource = PingSource.TAB_WIDGET;
		} else {
			ping = fallbackPingMs; pingSource = PingSource.FALLBACK_CONFIG;
		}

		return new LocationState(inHollows, decider != null ? decider.source() : null, server,
				scoreboard != null ? scoreboard.area() : null,
				hasWorld ? lobbyDay(clockTicks) : -1, clockTicks, ping, pingSource);
	}

	@SafeVarargs
	private static <T> T firstNonNull(T... values) {
		for (T v : values) if (v != null) return v;
		return null;
	}
}
