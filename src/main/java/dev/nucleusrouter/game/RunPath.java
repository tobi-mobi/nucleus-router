package dev.nucleusrouter.game;

import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.LegPlanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A planned run flattened into what you do, in order (SPEC §5 run mode): etherwarps, /warp nucleus,
 * the temple teleport, stretches you fly yourself (not explored yet), and arriving at structures.
 * {@link #advance} moves along it as you land on planned spots; it never re-plans.
 */
public final class RunPath {
	public enum Kind { WARP, TELEPORT, WARP_NUCLEUS, TEMPLE_TELEPORT, FLY, STOP }

	/**
	 * One thing to do.
	 *
	 * @param leg        which move of the run (for colouring), 0-based
	 * @param pos        WARP: landing feet; STOP/FLY: where to go; WARP_NUCLEUS: nucleus spawn; TEMPLE_TELEPORT: front of the temple
	 * @param block      WARP: the target block {x, y, z}, else null
	 * @param aimPoint   WARP: the most forgiving point to aim at, else null
	 * @param label      STOP: structure name; others: short instruction
	 */
	public record Node(Kind kind, int leg, Vec3 pos, int[] block, Vec3 aimPoint, String label, Vec3 dir, int clicks) {
		public Node(Kind kind, int leg, Vec3 pos, int[] block, Vec3 aimPoint, String label) {
			this(kind, leg, pos, block, aimPoint, label, null, 0);
		}

		/** An etherwarp or a teleport stretch: something you do with the Aspect of the Void. */
		public boolean isMove() { return kind == Kind.WARP || kind == Kind.TELEPORT; }
	}

	/**
	 * What a structure stop is about, for the run HUD (roadmap 3.6).
	 *
	 * @param id            stop id ("divan", "king", ...)
	 * @param split         name of its split ("Jade", "King", ...)
	 * @param action        what to do there, in words
	 * @param plannedArrive planned seconds from the start of the run when you get there
	 * @param plannedLeave  planned seconds when you're done there
	 */
	public record StopInfo(String id, String split, String action, double plannedArrive, double plannedLeave) {}

	private final List<Node> nodes;
	private final Map<Integer, StopInfo> stopInfo;
	private final double plannedSeconds;

	private RunPath(List<Node> nodes, Map<Integer, StopInfo> stopInfo, double plannedSeconds) {
		this.nodes = List.copyOf(nodes);
		this.stopInfo = Map.copyOf(stopInfo);
		this.plannedSeconds = plannedSeconds;
	}

	public List<Node> nodes() { return nodes; }
	public int size() { return nodes.size(); }
	/** Info for the STOP node at {@code index}, or null. */
	public StopInfo stopInfo(int index) { return stopInfo.get(index); }
	/** The planner's estimate for the whole run, in seconds. */
	public double plannedSeconds() { return plannedSeconds; }

	/** The split name for a stop: the crystal you have once you leave it (King Yolkar gives none). */
	public static String splitName(String stopId) {
		return switch (stopId) {
			case "divan" -> "Jade";
			case "precursor" -> "Sapphire";
			case "king" -> "King";
			case "queen" -> "Amber";
			case "temple" -> "Amethyst";
			case "khazad" -> "Topaz";
			default -> stopId;
		};
	}

	/** What to do at a stop, in words (SPEC §2.2). */
	public static String action(StopPlan.Stop s) {
		return switch (s.id()) {
			case "divan" -> "Give the 4 keepers their tools, take the Jade crystal";
			case "precursor" -> "Give Professor Robot the Apparatus, take the Sapphire";
			case "king" -> "Give the Goblin Egg to King Yolkar (left-click)";
			case "queen" -> "Take the Amber crystal, then leave within 3 s";
			case "temple" -> "Give the Jungle Key to the Door Guardian, parkour, crystal";
			case "khazad" -> "Stand on the Topaz crystal and kill Bal";
			default -> s.note();
		};
	}

	/**
	 * @param legs      planned legs by "from>to"
	 * @param estimated legs that weren't planned on the terrain ("from>to"): you fly those yourself
	 */
	public static RunPath build(RouteOrder.Plan plan, List<StopPlan.Stop> stops, Map<String, LegPlanner.Leg> legs, Set<String> estimated) {
		Map<String, StopPlan.Stop> byId = new HashMap<>();
		for (StopPlan.Stop s : stops) byId.put(s.id(), s);
		StopPlan.Stop nucleus = byId.get("nucleus");
		List<Node> out = new ArrayList<>();
		Map<Integer, StopInfo> info = new HashMap<>();
		double t = 0;
		int legIndex = 0;
		for (RouteOrder.Step step : plan.steps()) {
			double arrive = t + step.travelSeconds();
			t = arrive + step.waitSeconds();
			StopPlan.Stop to = byId.get(step.toId());
			String from = step.fromId();
			if (step.viaWarpNucleus()) {
				out.add(new Node(Kind.WARP_NUCLEUS, legIndex, nucleus.standFeet(), null, null, "/warp nucleus"));
				from = "nucleus";
			} else if (from.equals("temple.exit")) {
				StopPlan.Stop exit = byId.get("temple.exit");
				out.add(new Node(Kind.TEMPLE_TELEPORT, legIndex, exit.standFeet(), null, null, "take the teleport to the front of the temple"));
			}
			if (to != null && !step.toId().equals("nucleus")) {
				String key = from + ">" + step.toId();
				LegPlanner.Leg leg = legs.get(key);
				if (leg != null && leg.ok() && !estimated.contains(key)) {
					for (LegPlanner.Warp w : leg.warps()) {
						if (w.isTeleport())
							out.add(new Node(Kind.TELEPORT, legIndex, w.landingFeet(), null, null, "teleport x" + w.clicks(), w.aimDir(), w.clicks()));
						else
							out.add(new Node(Kind.WARP, legIndex, w.landingFeet(), new int[]{w.tx(), w.ty(), w.tz()}, w.aimPoint(), null, w.aimDir(), 0));
					}
				} else {
					out.add(new Node(Kind.FLY, legIndex, to.standFeet() != null ? to.standFeet() : to.target(), null, null,
							"fly to " + to.label() + " yourself (not explored yet)"));
				}
				info.put(out.size(), new StopInfo(to.id(), splitName(to.id()), action(to), arrive, t));
				out.add(new Node(Kind.STOP, legIndex, to.standFeet() != null ? to.standFeet() : to.target(), null, null, to.label()));
			}
			legIndex++;
		}
		return new RunPath(out, info, plan.seconds());
	}

	/** Landing within this distance of a planned spot counts as having warped there. */
	public static final double LANDED = 1.2;
	/** Teleport stretches end less exactly (clicks are spammed): within this counts. */
	public static final double TELEPORT_LANDED = 3;
	/**
	 * Default: being within this distance of a structure stop or fly-to point counts as arrived. Structures
	 * are big (in-game 2026-11-25: standing in the Mines of Divan, 28 m from its stop spot, still said "fly there").
	 */
	public static final double ARRIVED = 30;
	/** How far ahead an arrival is looked for before searching the whole route. */
	public static final int LOOKAHEAD = 15;

	/**
	 * The new position in the route after the player is at {@code feet}: past the furthest node (within the
	 * look-ahead) the player has reached. If none matches but the player stands exactly on another planned
	 * landing anywhere on the route, jump there (SPEC: "if they land on a different route node, jump to it").
	 * Otherwise unchanged: off-route never re-plans.
	 */
	public int advance(int index, Vec3 feet) {
		return advance(index, feet, ARRIVED);
	}

	/** Same, with the arrival distance for structures and fly-to points. */
	public int advance(int index, Vec3 feet, double arrived) {
		int best = -1;
		for (int i = index; i < Math.min(nodes.size(), index + LOOKAHEAD); i++) {
			Node n = nodes.get(i);
			// A /warp nucleus or the temple teleport only counts when it's the very next thing to do
			// (at the start of a run you're already standing at the nucleus)
			boolean teleport = n.kind() == Kind.WARP_NUCLEUS || n.kind() == Kind.TEMPLE_TELEPORT;
			if (teleport && i != index) break;
			if (reached(n, feet, arrived)) best = i;
		}
		if (best >= 0) return best + 1;
		// At a structure further along (e.g. flown there your own way, skipping planned warps): jump there.
		// In-game 2026-11-25: at the Jungle Temple it still said "fly to Jungle Temple" because the temple was
		// more than LOOKAHEAD steps ahead.
		for (int i = index; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			if ((n.kind() == Kind.STOP || n.kind() == Kind.FLY) && n.pos().sub(feet).length() <= arrived) {
				int j = i;
				while (j + 1 < nodes.size() && nodes.get(j + 1).kind() == Kind.STOP && nodes.get(j + 1).pos().sub(feet).length() <= arrived) j++;
				return j + 1;
			}
		}
		for (int i = 0; i < nodes.size(); i++) {
			Node n = nodes.get(i);
			if (n.kind() == Kind.WARP && n.pos().sub(feet).length() <= LANDED) return i + 1;
		}
		return index;
	}

	private static boolean reached(Node n, Vec3 feet, double arrived) {
		double d = n.pos().sub(feet).length();
		return switch (n.kind()) {
			case WARP -> d <= LANDED;
			case TELEPORT -> d <= TELEPORT_LANDED;
			case WARP_NUCLEUS, TEMPLE_TELEPORT -> d <= 3;
			case FLY, STOP -> d <= arrived;
		};
	}

	/** The next {@code n} moves (etherwarps and teleport stretches) from {@code index} (their positions in the list). */
	public List<Integer> nextWarps(int index, int n) {
		List<Integer> out = new ArrayList<>();
		for (int i = index; i < nodes.size() && out.size() < n; i++) if (nodes.get(i).isMove()) out.add(i);
		return out;
	}

	/** The first node at or after {@code index} that isn't a move (the next instruction), or null. */
	public Node nextInstruction(int index) {
		for (int i = index; i < nodes.size(); i++) if (!nodes.get(i).isMove()) return nodes.get(i);
		return null;
	}

	/** Index of the node nearest to {@code feet} (for "you're off the route: get back here"). */
	public int nearest(Vec3 feet) {
		int best = -1;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < nodes.size(); i++) {
			double d = nodes.get(i).pos().sub(feet).length();
			if (d < bd) { bd = d; best = i; }
		}
		return best;
	}
}
