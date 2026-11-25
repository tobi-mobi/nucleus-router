package dev.nucleusrouter.game;

import dev.nucleusrouter.core.config.NrConfig;
import dev.nucleusrouter.core.geom.Vec3;
import dev.nucleusrouter.core.plan.PlanWorld;
import dev.nucleusrouter.core.plan.StopPicker;

import java.util.ArrayList;
import java.util.List;

/**
 * Default stop points (SPEC §2.2, §3.5, roadmap 3.3), guessed from the structures' anchors:
 * <ul>
 *   <li>Nucleus spawn (start and end of the run).</li>
 *   <li>Mines of Divan: the 4 keepers (seen ones, or placed from the learned keeper layout), then the Jade
 *       crystal in the middle of them (user 2026-11-25).</li>
 *   <li>Professor Robot, then the Sapphire crystal behind the gate, a short warp away (user 2026-11-25).</li>
 *   <li>King Yolkar.</li>
 *   <li>Goblin Queen's Den: the Amber crystal, then the den exit (by default /warp nucleus, which counts as
 *       leaving and is faster than the 2.5 s target).</li>
 *   <li>Jungle Temple: the Door Guardian lets you in, the parkour by hand ends at the crystal; after taking
 *       it you choose: teleport to the front steps, or /warp nucleus (route ordering picks).</li>
 *   <li>Khazad-dum: standing on the Topaz crystal's barrier box for the Bal fight.</li>
 * </ul>
 * Where to stand: the spot you can etherwarp onto within reach (3 blocks for everything, confirmed) of the
 * NPC or crystal with the most open view ({@link StopPicker}). This is a list of places, not the run order:
 * the order (and when to /warp nucleus) is chosen by the route planner.
 */
public final class StopPlan {
	public enum Kind { NUCLEUS, NPC, CRYSTAL, BAL_SPOT, TEMPLE_EXIT }

	/**
	 * One stop.
	 *
	 * @param id          stable id, e.g. "divan.Keeper of Lapis"
	 * @param target      what you interact with (NPC feet, crystal box centre)
	 * @param standFeet   where to stand (null: no spot found in reach yet; the terrain may not be loaded)
	 * @param waitSeconds time spent at the stop (hand-in, pickup, fight, parkour)
	 * @param manual      a hand-done segment follows (temple parkour, Bal fight)
	 * @param note        short explanation for the player
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

	/** All stops known so far (structures not found yet are left out). */
	public static List<Stop> build(StructureMap map, KeeperLayout layout, PlanWorld world, NrConfig cfg) {
		NrConfig.Stops st = cfg.stops;
		double eye = cfg.etherwarp.eyeHeight;
		int air = cfg.etherwarp.requiredAirAbove;
		List<Stop> out = new ArrayList<>();
		Vec3 nucleus = new Vec3(cfg.hollows.nucleusSpawnX, cfg.hollows.nucleusSpawnY, cfg.hollows.nucleusSpawnZ);
		out.add(new Stop("nucleus", "Crystal Nucleus", null, Kind.NUCLEUS, nucleus, nucleus, 1, 0, false, "/warp nucleus lands here"));

		// Mines of Divan: 4 keepers (order free)
		StructureMap.Location divan = map.locate(Structure.MINES_OF_DIVAN);
		if (StructureMap.exact(divan)) {
			List<String> seen = new ArrayList<>();
			for (StructureMap.Npc n : map.npcs(Structure.MINES_OF_DIVAN)) {
				seen.add(n.label);
				out.add(npcStop("divan." + n.label, n.label, Structure.MINES_OF_DIVAN, n.pos(), st.npcInteractionSeconds, false, "give the matching tool", world, st.npcReach, eye, air));
			}
			if (layout != null) {
				for (String name : layout.offsets().keySet()) {
					if (seen.contains(name)) continue;
					Vec3 p = layout.keeperAt(name, divan.pos());
					out.add(npcStop("divan." + name, name, Structure.MINES_OF_DIVAN, p, st.npcInteractionSeconds, false, "give the matching tool (placed from the keeper layout)", world, st.npcReach, eye, air));
				}
			}
			// The Jade crystal in the middle of the keepers. Crystals sit at the centre of a solid barrier box
			// (~5 blocks inside), so the stop needs the box: you stand next to it.
			int[][] jade = map.crystalBox(Structure.MINES_OF_DIVAN);
			if (jade != null)
				out.add(crystalStop("divan.crystal", "Jade crystal", Structure.MINES_OF_DIVAN, jade, null,
						"after all 4 keepers, take the crystal in the middle", world, st, eye, air));
			else
				out.add(new Stop("divan.crystal", "Jade crystal", Structure.MINES_OF_DIVAN, Kind.CRYSTAL, divan.pos(), null, 0,
						st.crystalPickupSeconds, false, "crystal box not found yet (it's in the middle of the keepers)"));
		}

		// Professor Robot (+ the crystal behind the gate next to him)
		StructureMap.Location robot = map.locate(Structure.PRECURSOR_CITY);
		if (StructureMap.exact(robot)) {
			int[][] sapphire = map.crystalBox(Structure.PRECURSOR_CITY);
			out.add(npcStop("precursor.robot", "Professor Robot", Structure.PRECURSOR_CITY, robot.pos(),
					st.npcInteractionSeconds + (sapphire == null ? st.crystalPickupSeconds : 0), false,
					sapphire == null ? "give the Precursor Apparatus, then take the crystal behind the gate (crystal box not found yet)"
							: "give the Precursor Apparatus; the gate next to him opens", world, st.npcReach, eye, air));
			if (sapphire != null)
				out.add(crystalStop("precursor.crystal", "Sapphire crystal", Structure.PRECURSOR_CITY, sapphire, null,
						"behind the gate, a short warp from Professor Robot", world, st, eye, air));
		}

		// King Yolkar
		StructureMap.Location king = map.locate(Structure.KING_YOLKAR);
		if (StructureMap.exact(king))
			out.add(npcStop("king", "King Yolkar", Structure.KING_YOLKAR, king.pos(), st.npcInteractionSeconds, false, "left-click him with the Goblin Egg", world, st.npcReach, eye, air));

		// Goblin Queen's Den: the Amber crystal, then out
		int[][] amber = map.crystalBox(Structure.GOBLIN_QUEENS_DEN);
		if (amber != null) {
			String exit = denExit(cfg) == DenExit.WARP_NUCLEUS ? "then /warp nucleus right away (counts as leaving the den)"
					: "then etherwarp " + st.denExitHeightAboveCrystal + " blocks up within " + st.queenDenExitTargetSeconds + " s";
			out.add(crystalStop("queen.crystal", "Amber crystal", Structure.GOBLIN_QUEENS_DEN, amber, null, "take the crystal, " + exit, world, st, eye, air));
		}

		// Jungle Temple: Door Guardian, parkour by hand, lever back to the front steps
		StructureMap.Location temple = map.locate(Structure.JUNGLE_TEMPLE);
		if (StructureMap.exact(temple)) {
			out.add(npcStop("temple.door", "Kalhuiki Door Guardian", Structure.JUNGLE_TEMPLE, temple.pos(),
					st.npcInteractionSeconds + st.templeManualSeconds + st.crystalPickupSeconds, true,
					"give the Jungle Key, do the parkour by hand, take the crystal; then the lever (front steps) or /warp nucleus", world, st.npcReach, eye, air));
			double[] o = st.templeExitOffset;
			Vec3 steps = temple.pos().add(new Vec3(o[0], o[1], o[2]));
			out.add(new Stop("temple.exit", "Temple front steps", Structure.JUNGLE_TEMPLE, Kind.TEMPLE_EXIT, steps, steps, 1, 0, false,
					"the lever next to the crystal brings you here (the other choice is /warp nucleus)"));
		}

		// Khazad-dum: stand on the Topaz crystal's box to fight Bal
		int[][] topaz = map.crystalBox(Structure.KHAZAD_DUM);
		StructureMap.Location khazad = map.locate(Structure.KHAZAD_DUM);
		if (topaz != null) {
			int cx = (topaz[0][0] + topaz[1][0]) / 2, cz = (topaz[0][2] + topaz[1][2]) / 2, top = topaz[1][1];
			boolean room = !world.solid(cx, top + 1, cz) && !world.solid(cx, top + 2, cz);
			Vec3 feet = room ? new Vec3(cx + 0.5, top + 1, cz + 0.5) : null;
			out.add(new Stop("khazad.bal", "Bal (on the Topaz crystal)", Structure.KHAZAD_DUM, Kind.BAL_SPOT, centre(topaz), feet, 1,
					st.balFightSeconds + st.crystalPickupSeconds, true, "stand on the crystal and kill Bal"));
		} else if (StructureMap.exact(khazad)) {
			out.add(npcStop("khazad.bal", "Bal", Structure.KHAZAD_DUM, khazad.pos(), st.balFightSeconds + st.crystalPickupSeconds, true,
					"kill Bal (crystal box not found yet)", world, st.npcReach, eye, air));
		}
		return out;
	}

	private static Stop npcStop(String id, String label, Structure s, Vec3 npcFeet, double wait, boolean manual, String note,
			PlanWorld world, double reach, double eye, int air) {
		StopPicker.Pick p = StopPicker.pick(world, StopPicker.npcBox(npcFeet), reach, eye, air);
		return new Stop(id, label, s, Kind.NPC, npcFeet, p.standFeet(), p.openness(), wait, manual, note);
	}

	/** A crystal stop: within reach of its barrier box, or of {@code fallback} if the box isn't known. */
	private static Stop crystalStop(String id, String label, Structure s, int[][] box, Vec3 fallback, String note,
			PlanWorld world, NrConfig.Stops st, double eye, int air) {
		double[] target = box != null ? box(box) : StopPicker.npcBox(fallback);
		Vec3 at = box != null ? centre(box) : fallback;
		StopPicker.Pick p = StopPicker.pick(world, target, st.crystalReach, eye, air);
		return new Stop(id, label, s, Kind.CRYSTAL, at, p.standFeet(), p.openness(), st.crystalPickupSeconds, false, note);
	}

	private static double[] box(int[][] b) {
		return new double[]{b[0][0], b[0][1], b[0][2], b[1][0], b[1][1], b[1][2]};
	}

	private static Vec3 centre(int[][] b) {
		return new Vec3((b[0][0] + b[1][0] + 1) / 2.0, (b[0][1] + b[1][1] + 1) / 2.0, (b[0][2] + b[1][2] + 1) / 2.0);
	}
}
