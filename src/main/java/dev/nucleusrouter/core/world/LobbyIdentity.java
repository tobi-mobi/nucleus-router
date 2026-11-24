package dev.nucleusrouter.core.world;

/**
 * Hypixel reuses server names (e.g. "mini89R") for new lobbies after the old one closes.
 * A save file for the same server name therefore only belongs to the current lobby if the
 * world clock moved forward by about the real time that passed (20 ticks per second).
 */
public final class LobbyIdentity {
	/** Allow the server to run as slow as half speed (lag) and a little faster than expected. */
	static final double MIN_TPS_FACTOR = 0.5, MAX_TPS_FACTOR = 1.1;
	/** Extra slack in ticks (one minute) for clock jitter and save timing. */
	static final long SLACK_TICKS = 1200;

	private LobbyIdentity() {}

	public static boolean isSameLobby(long savedTicks, long savedAtMillis, long nowTicks, long nowMillis) {
		if (nowTicks < savedTicks) return false; // clock went backwards: a fresh lobby
		long elapsedMs = Math.max(0, nowMillis - savedAtMillis);
		double expected = elapsedMs / 50.0; // 20 ticks per second
		long advanced = nowTicks - savedTicks;
		return advanced >= expected * MIN_TPS_FACTOR - SLACK_TICKS
				&& advanced <= expected * MAX_TPS_FACTOR + SLACK_TICKS;
	}
}
