package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run HUD's numbers (SPEC §5, roadmap 3.6): run timer, per-crystal splits, warp count, and what to do
 * now. Plain Java and driven by the caller's clock, so it's unit-tested without Minecraft.
 * <ul>
 *   <li>The timer starts when you first move away from where run mode was turned on (or first reach a
 *       route step), and stops when the route is finished.</li>
 *   <li>A structure's split is taken when you <em>leave</em> it (the route moves past the stop): by then
 *       you have its crystal. The time you arrived is kept too, so the time spent inside can be shown.</li>
 * </ul>
 */
public final class RunClock {
	/** Moving this far from where run mode started starts the timer. */
	public static final double START_MOVE = 2.0;

	/**
	 * One structure's split.
	 *
	 * @param arriveMs     ms after the start when you got there, -1 if not yet
	 * @param leaveMs      ms after the start when you left (crystal in hand), -1 if not yet
	 * @param plannedLeave the planner's estimate for leaving, in seconds after the start
	 */
	public record Split(String name, String label, long arriveMs, long leaveMs, double plannedArrive, double plannedLeave) {
		public boolean done() { return leaveMs >= 0; }
		/** Seconds ahead (negative) or behind (positive) the plan when you left; NaN if not left yet. */
		public double deltaSeconds() { return done() ? leaveMs / 1000.0 - plannedLeave : Double.NaN; }
	}

	/** What the HUD says right now. {@code atStop} is null when you're between structures. */
	public record Status(String atStop, String doNow, String next, String heading, double headingDistance, boolean finished) {}

	private final RunPath path;
	private final Vec3 origin;
	private long startMs = -1, endMs = -1;
	private int etherwarps, teleports;
	private final Map<Integer, long[]> times = new LinkedHashMap<>();

	public RunClock(RunPath path, Vec3 origin) {
		this.path = path;
		this.origin = origin;
	}

	public boolean started() { return startMs >= 0; }
	public boolean finished() { return endMs >= 0; }
	public int etherwarps() { return etherwarps; }
	public int teleports() { return teleports; }

	/** Etherwarps in the plan. */
	public int plannedEtherwarps() {
		return (int) path.nodes().stream().filter(n -> n.kind() == RunPath.Kind.WARP).count();
	}

	/** Normal teleport clicks in the plan. */
	public int plannedTeleportClicks() {
		return path.nodes().stream().filter(n -> n.kind() == RunPath.Kind.TELEPORT).mapToInt(RunPath.Node::clicks).sum();
	}

	/** Counted while the timer runs: a real etherwarp happened. */
	public void onEtherwarp(long now) { if (started() && !finished()) etherwarps++; }
	/** Counted while the timer runs: a normal Aspect of the Void teleport happened. */
	public void onTeleport(long now) { if (started() && !finished()) teleports++; }

	/** Call every tick with where you are and how the route index moved ({@code oldIndex -> newIndex}). */
	public void update(long now, Vec3 feet, int oldIndex, int newIndex) {
		if (!started() && (feet.sub(origin).length() > START_MOVE || newIndex > oldIndex)) startMs = now;
		if (newIndex > oldIndex && started()) {
			long t = now - startMs;
			for (int i = oldIndex; i < newIndex && i < path.size(); i++)
				if (path.stopInfo(i) != null) times.computeIfAbsent(i, k -> new long[]{t, -1});
			for (Map.Entry<Integer, long[]> e : times.entrySet())
				if (e.getValue()[1] < 0 && newIndex > e.getKey() + 1) e.getValue()[1] = t;
		}
		if (started() && !finished() && newIndex >= path.size()) {
			endMs = now;
			for (long[] v : times.values()) if (v[1] < 0) v[1] = now - startMs;
		}
	}

	/** Milliseconds on the run timer. */
	public long elapsedMs(long now) {
		if (!started()) return 0;
		return (finished() ? endMs : now) - startMs;
	}

	/** Every structure on the route in order, done or not. */
	public List<Split> splits() {
		List<Split> out = new ArrayList<>();
		for (int i = 0; i < path.size(); i++) {
			RunPath.StopInfo s = path.stopInfo(i);
			if (s == null) continue;
			long[] t = times.get(i);
			out.add(new Split(s.split(), path.nodes().get(i).label(), t != null ? t[0] : -1, t != null ? t[1] : -1, s.plannedArrive(), s.plannedLeave()));
		}
		return out;
	}

	/** What to show: the structure you're at (and what to do there), the next step, and where you're heading. */
	public static Status status(RunPath path, int index, Vec3 feet, double arriveRadius) {
		List<RunPath.Node> nodes = path.nodes();
		if (index >= nodes.size()) return new Status(null, null, "Route finished", null, 0, true);
		String atStop = null, doNow = null;
		if (index > 0) {
			RunPath.Node prev = nodes.get(index - 1);
			RunPath.StopInfo info = path.stopInfo(index - 1);
			if (prev.kind() == RunPath.Kind.STOP && info != null && prev.pos().sub(feet).length() <= arriveRadius) {
				atStop = prev.label();
				doNow = info.action();
			}
		}
		String heading = null;
		double headingDist = 0;
		int movesLeft = 0;
		for (int i = index; i < nodes.size(); i++) {
			RunPath.Node n = nodes.get(i);
			if (n.isMove()) movesLeft++;
			if (n.kind() == RunPath.Kind.STOP) {
				heading = n.label();
				headingDist = n.pos().sub(feet).length();
				break;
			}
			if (n.kind() == RunPath.Kind.WARP_NUCLEUS && i == nodes.size() - 1) {
				heading = "Crystal Nucleus";
				headingDist = n.pos().sub(feet).length();
				break;
			}
		}
		return new Status(atStop, doNow, describe(nodes.get(index), movesLeft), heading, headingDist, false);
	}

	/** One step in words. {@code movesLeft}: etherwarps and teleport stretches until the next structure. */
	public static String describe(RunPath.Node n, int movesLeft) {
		String rest = movesLeft > 1 ? " (" + movesLeft + " moves to go)" : "";
		return switch (n.kind()) {
			case WARP -> "Etherwarp to block 1" + rest;
			case TELEPORT -> "Teleport x" + n.clicks() + " along the arrow" + rest;
			case WARP_NUCLEUS -> "Type /warp nucleus";
			case TEMPLE_TELEPORT -> "Take the teleport to the front of the temple";
			case FLY -> "Fly yourself: " + n.label().replaceFirst("^fly to ", "");
			case STOP -> "Go to " + n.label();
		};
	}

	/** 83_450 ms -> "1:23.4". */
	public static String format(long ms) {
		if (ms < 0) ms = 0;
		long tenths = ms / 100;
		return String.format("%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
	}

	/** +3.1 / -2.0 seconds against the plan. */
	public static String formatDelta(double seconds) {
		return String.format("%s%.1f", seconds >= 0 ? "+" : "-", Math.abs(seconds));
	}
}
