package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;

/**
 * The Queen's den countdown (SPEC §2.2, §5, roadmap 3.6): after you take the Amber crystal you have
 * {@code limitSeconds} (3) to leave the Queen's castle. Started by a chat line (see
 * {@code run.denTimerStartMessages}); ends when you're out. Plain Java, driven by the caller's clock.
 * <p>
 * "Out" (confirmed facts): {@code denExitHeightAboveCrystal} (30) blocks above the crystal, /warp nucleus
 * (you're at the nucleus spawn), or the scoreboard no longer showing the Queen's den. Leaving through the
 * castle's sides counts too, but the castle's size isn't known, so a side exit only shows once the
 * scoreboard changes or the time runs out.
 */
public final class DenTimer {
	public enum State { IDLE, RUNNING, OUT, TOO_LATE }

	/** How long the result ("Out in 1.8 s") stays on screen. */
	public static final long SHOW_RESULT_MS = 3000;
	/** Within this of the nucleus spawn counts as having typed /warp nucleus. */
	public static final double AT_NUCLEUS = 5;
	public static final String DEN_AREA = "Goblin Queen's Den";

	private State state = State.IDLE;
	private long startMs, endMs;

	public State state() { return state; }

	public void start(long now) {
		state = State.RUNNING;
		startMs = now;
		endMs = -1;
	}

	public void reset() { state = State.IDLE; }

	/** Call every tick. {@code out}: see {@link #isOut}. */
	public void update(long now, boolean out, double limitSeconds) {
		switch (state) {
			case RUNNING -> {
				if (out) { state = State.OUT; endMs = now; }
				else if (now - startMs > limitSeconds * 1000) { state = State.TOO_LATE; endMs = now; }
			}
			case OUT, TOO_LATE -> { if (now - endMs > SHOW_RESULT_MS) state = State.IDLE; }
			default -> {}
		}
	}

	/** Seconds left while running (never below 0). */
	public double remaining(long now, double limitSeconds) {
		return Math.max(0, limitSeconds - (now - startMs) / 1000.0);
	}

	/** How long leaving took (OUT) or the limit was passed (TOO_LATE), in seconds. */
	public double resultSeconds() { return (endMs - startMs) / 1000.0; }

	/**
	 * @param crystal      centre of the Amber crystal's barrier box, or null if not known
	 * @param scoreboardArea area name from the scoreboard, or null if not read
	 */
	public static boolean isOut(Vec3 feet, Vec3 crystal, int heightAboveCrystal, Vec3 nucleus, String scoreboardArea) {
		if (crystal != null && feet.y() >= crystal.y() + heightAboveCrystal) return true;
		if (nucleus != null && feet.sub(nucleus).length() <= AT_NUCLEUS) return true;
		return scoreboardArea != null && !scoreboardArea.isBlank() && !scoreboardArea.contains(DEN_AREA);
	}

	/** True if a chat line is one of the configured "you took the Amber crystal" messages. */
	public static boolean isStartMessage(String chat, java.util.List<String> startMessages) {
		if (chat == null) return false;
		String plain = chat.replaceAll("§.", "").trim();
		for (String m : startMessages) if (!m.isBlank() && plain.contains(m.trim())) return true;
		return false;
	}
}
