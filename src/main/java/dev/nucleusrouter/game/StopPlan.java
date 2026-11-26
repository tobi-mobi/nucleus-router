package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.plan.StopPicker;

import java.util.ArrayList;
import java.util.List;

/**
 * Default stops (SPEC §2.2, §3.5, roadmap 3.3): <b>one stop per structure</b>, at its arrival point
 * (user 2026-11-25: routes are between structures and their entrances; moving between NPCs inside a
 * structure is left to the player and only counted as time):
 * <ul>
 *   <li>Mines of Divan: the Jade crystal box in the middle of the 4 keepers (keepers + pickup inside).</li>
 *   <li>Lost Precursor City: Professor Robot (the Sapphire is a short warp away, counted inside).</li>
 *   <li>King Yolkar.</li>
 *   <li>Goblin Queen's Den: the Amber crystal; left with /warp nucleus by default.</li>
 *   <li>Jungle Temple: the Door Guardian; after the parkour and the crystal you choose the teleport to the
 *       front of the temple ({@link #templeExit}) or /warp nucleus.</li>
 *   <li>Khazad-dum: on top of the Topaz crystal box, for the Bal fight.</li>
 * </ul>
 * Plus the nucleus spawn (start and end). This is a set of places, not the run order: {@link RouteOrder}
 * picks the order and when to /warp nucleus.
 */
public final class StopPlan {
	public enum Kind { NUCLEUS, STRUCTURE, TEMPLE_EXIT }

	/**
	 * One stop.
	 *
	 * @param target      what you go to (NPC feet, crystal box centre)
	 * @param standFeet   where the route arrives (null: no spot found yet; the terrain may not be loaded)
	 * @param waitSeconds time spent there, including moving around inside the structure
	 * @param manual      the time there is a hand-done segment (temple parkour, Bal fight)
	 */
	public record Stop(String id, String label, Structure structure, Kind kind, Vec3 target, Vec3 standFeet,
			double openness, double waitSeconds, boolean manual, String note) {
		public boolean ready() { return standFeet != null; }
	}

	/** How the Queen's den is left after taking the Amber crystal. */
	public enum DenExit { WARP_NUCLEUS, ETHERWARP_UP }

	private StopPlan() {}

	public static DenExit denExit(NrConfig cfg) {
		return cfg.stops.warpNucleusCountsAsLeavingDen && cfg.hollows.warpNucleusSeconds <= cfg.stops.queenDenExitTargetSeconds
				? DenExit.WARP_NUCLEUS : DenExit.ETHERWARP_UP;
	}

	public static Vec3 nucleus(NrConfig cfg) {
		return new Vec3(cfg.hollows.nucleusSpawnX, cfg.hollows.nucleusSpawnY, cfg.hollows.nucleusSpawnZ);
	}

	/** The nucleus plus one stop per found structure (structures not found are left out). */
	public static List<Stop> build(StructureMap map, KeeperLayout layout, PlanWorld world, NrConfig cfg) {
		return build(map, layout, world, cfg, null);
	}

	/**
	 * What a stop's own stop point ({@link SetPoints}) is measured from: the Jade crystal box centre, Professor
	 * Robot, King Yolkar, the Amber crystal box centre, the Door Guardian (also for the front-of-temple spot) and
	 * the Topaz crystal box centre. Null while that isn't known exactly in this lobby (the Mines need their
	 * crystal box and Khazad-dum its Topaz box, since keepers and Bal aren't fixed points).
	 */
	public static Vec3 anchor(String id, StructureMap map) {
		return switch (id) {
			case "divan" -> { int[][] b = map.crystalBox(Structure.MINES_OF_DIVAN); yield b == null ? null : centre(b); }
			case "precursor" -> exactPos(map, Structure.PRECURSOR_CITY);
			case "king" -> exactPos(map, Structure.KING_YOLKAR);
			case "queen" -> { int[][] b = map.crystalBox(Structure.GOBLIN_QUEENS_DEN); yield b == null ? null : centre(b); }
			case "temple", "temple.exit" -> exactPos(map, Structure.JUNGLE_TEMPLE);
			case "khazad" -> { int[][] b = map.crystalBox(Structure.KHAZAD_DUM); yield b == null ? null : centre(b); }
			default -> null;
		};
	}

	/** The structure a stop id belongs to (null for the nucleus or an unknown id). */
	public static Structure structureOf(String id) {
		return switch (id) {
			case "divan" -> Structure.MINES_OF_DIVAN;
			case "precursor" -> Structure.PRECURSOR_CITY;
			case "king" -> Structure.KING_YOLKAR;
			case "queen" -> Structure.GOBLIN_QUEENS_DEN;
			case "temple", "temple.exit" -> Structure.JUNGLE_TEMPLE;
			case "khazad" -> Structure.KHAZAD_DUM;
			default -> null;
		};
	}

	private static Vec3 exactPos(StructureMap map, Structure s) {
		StructureMap.Location l = map.locate(s);
		return StructureMap.exact(l) ? l.pos() : null;
	}

	/** Where to stand: your own stop point if it's usable here, else the automatic pick. */
	private record Spot(Vec3 feet, double openness, String note) {}

	private static Spot spot(String id, StructureMap map, SetPoints sp, PlanWorld world, NrConfig cfg, java.util.function.Supplier<StopPicker.Pick> auto) {
		Vec3 anchor = sp == null ? null : anchor(id, map);
		Vec3 own = anchor == null ? null : sp.feet(id, anchor, map.orientation(structureOf(id)));
		if (own != null && SetPoints.usable(own, world, cfg.etherwarp.requiredAirAbove)) return new Spot(own, 1, " (your stop point)");
		StopPicker.Pick p = auto.get();
		String note = own != null ? " (your stop point is blocked or not loaded here; automatic spot)" : "";
		return new Spot(p.standFeet(), p.openness(), note);
	}

	/** Like {@link #build(StructureMap, KeeperLayout, PlanWorld, NrConfig)}, using your own stop points where set. */
	public static List<Stop> build(StructureMap map, KeeperLayout layout, PlanWorld world, NrConfig cfg, SetPoints sp) {
		NrConfig.Stops st = cfg.stops;
		double eye = cfg.etherwarp.eyeHeight;
		int air = cfg.etherwarp.requiredAirAbove;
		List<Stop> out = new ArrayList<>();
		Vec3 nucleus = nucleus(cfg);
		out.add(new Stop("nucleus", "Crystal Nucleus", null, Kind.NUCLEUS, nucleus, nucleus, 1, 0, false, "/warp nucleus lands here"));

		StructureMap.Location divan = map.locate(Structure.MINES_OF_DIVAN);
		if (StructureMap.exact(divan)) {
			int[][] jade = map.crystalBox(Structure.MINES_OF_DIVAN);
			Spot p = spot("divan", map, sp, world, cfg, () -> jade != null ? StopPicker.pick(world, box(jade), st.crystalReach, eye, air)
					: StopPicker.pick(world, StopPicker.npcBox(divan.pos()), 8, eye, air));
			out.add(new Stop("divan", "Mines of Divan", Structure.MINES_OF_DIVAN, Kind.STRUCTURE, jade != null ? centre(jade) : divan.pos(),
					p.feet(), p.openness(), StopCosts.seconds(Structure.MINES_OF_DIVAN, cfg), false,
					"the 4 keepers, then the Jade crystal in the middle" + p.note()));
		}

		StructureMap.Location robot = map.locate(Structure.PRECURSOR_CITY);
		if (StructureMap.exact(robot))
			out.add(npcStop("precursor", "Lost Precursor City", Structure.PRECURSOR_CITY, robot.pos(), cfg,
					"Professor Robot, then the Sapphire behind the gate", world, map, sp));

		StructureMap.Location king = map.locate(Structure.KING_YOLKAR);
		if (StructureMap.exact(king))
			out.add(npcStop("king", "King Yolkar", Structure.KING_YOLKAR, king.pos(), cfg, "left-click him with the Goblin Egg", world, map, sp));

		int[][] amber = map.crystalBox(Structure.GOBLIN_QUEENS_DEN);
		if (amber != null) {
			Spot p = spot("queen", map, sp, world, cfg, () -> StopPicker.pick(world, box(amber), st.crystalReach, eye, air));
			String exit = denExit(cfg) == DenExit.WARP_NUCLEUS ? "then /warp nucleus right away (counts as leaving)"
					: "then etherwarp " + st.denExitHeightAboveCrystal + " blocks up within " + st.queenDenExitTargetSeconds + " s";
			out.add(new Stop("queen", "Goblin Queen's Den", Structure.GOBLIN_QUEENS_DEN, Kind.STRUCTURE, centre(amber), p.feet(), p.openness(),
					StopCosts.seconds(Structure.GOBLIN_QUEENS_DEN, cfg), false, "take the Amber crystal, " + exit + p.note()));
		}

		StructureMap.Location temple = map.locate(Structure.JUNGLE_TEMPLE);
		if (StructureMap.exact(temple)) {
			out.add(npcStop("temple", "Jungle Temple", Structure.JUNGLE_TEMPLE, temple.pos(), cfg,
					"Door Guardian, parkour by hand, crystal; then the teleport out front or /warp nucleus", world, map, sp));
			Stop exit = templeExit(temple.pos(), cfg);
			Vec3 own = sp == null ? null : sp.feet("temple.exit", temple.pos(), map.orientation(Structure.JUNGLE_TEMPLE));
			// The teleport puts you there whatever the blocks are, so your own point is used as it is
			if (own != null) exit = new Stop(exit.id(), exit.label(), exit.structure(), exit.kind(), own, own, 1, 0, false,
					"the teleport after the temple crystal puts you here (your stop point)");
			out.add(exit);
		}

		int[][] topaz = map.crystalBox(Structure.KHAZAD_DUM);
		StructureMap.Location khazad = map.locate(Structure.KHAZAD_DUM);
		if (topaz != null) {
			int cx = (topaz[0][0] + topaz[1][0]) / 2, cz = (topaz[0][2] + topaz[1][2]) / 2, top = topaz[1][1];
			boolean room = !world.solid(cx, top + 1, cz) && !world.solid(cx, top + 2, cz);
			Spot p = spot("khazad", map, sp, world, cfg, () -> room ? new StopPicker.Pick(new Vec3(cx + 0.5, top + 1, cz + 0.5), 1, 0)
					: new StopPicker.Pick(null, 0, Double.POSITIVE_INFINITY));
			out.add(new Stop("khazad", "Khazad-dûm", Structure.KHAZAD_DUM, Kind.STRUCTURE, centre(topaz),
					p.feet(), p.openness(), StopCosts.seconds(Structure.KHAZAD_DUM, cfg), true,
					"stand on the Topaz crystal and kill Bal" + p.note()));
		} else if (StructureMap.exact(khazad)) {
			out.add(npcStop("khazad", "Khazad-dûm", Structure.KHAZAD_DUM, khazad.pos(), cfg, "kill Bal (crystal box not found yet)", world, map, null));
		}
		return out;
	}

	/** Where the temple's teleport puts you (front of the temple), relative to the Door Guardian. */
	public static Stop templeExit(Vec3 guardian, NrConfig cfg) {
		double[] o = cfg.stops.templeExitOffset;
		Vec3 steps = guardian.add(new Vec3(o[0], o[1], o[2]));
		return new Stop("temple.exit", "Front of the temple", Structure.JUNGLE_TEMPLE, Kind.TEMPLE_EXIT, steps, steps, 1, 0, false,
				"the teleport after the temple crystal puts you here");
	}

	private static Stop npcStop(String id, String label, Structure s, Vec3 npcFeet, NrConfig cfg, String note, PlanWorld world,
			StructureMap map, SetPoints sp) {
		Spot p = spot(id, map, sp, world, cfg,
				() -> StopPicker.pick(world, StopPicker.npcBox(npcFeet), cfg.stops.npcReach, cfg.etherwarp.eyeHeight, cfg.etherwarp.requiredAirAbove));
		return new Stop(id, label, s, Kind.STRUCTURE, npcFeet, p.feet(), p.openness(), StopCosts.seconds(s, cfg), s == Structure.JUNGLE_TEMPLE, note + p.note());
	}

	private static double[] box(int[][] b) {
		return new double[]{b[0][0], b[0][1], b[0][2], b[1][0], b[1][1], b[1][2]};
	}

	public static Vec3 centre(int[][] b) {
		return new Vec3((b[0][0] + b[1][0] + 1) / 2.0, (b[0][1] + b[1][1] + 1) / 2.0, (b[0][2] + b[1][2] + 1) / 2.0);
	}
}
