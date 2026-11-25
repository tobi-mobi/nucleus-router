package dev.nucleusrouter.game;

import dev.nucleusrouter.core.world.WorldSnapshot;

/** Structures of the user's real lobby mini92AD (bench/), as the mod found them there. */
final class RealLobby {
	private RealLobby() {}

	static StructureMap structures(WorldSnapshot snap) {
		StructureMap m = new StructureMap();
		String[] names = {"Keeper of Diamond", "Keeper of Gold", "Keeper of Lapis", "Keeper of Emerald"};
		double[][] pos = {{588.5, 89, 442.5}, {618.5, 89, 478.5}, {654.5, 89, 448.5}, {624.5, 89, 412.5}};
		for (int i = 0; i < 4; i++) m.observeNametag(Structure.MINES_OF_DIVAN, names[i], pos[i][0], pos[i][1], pos[i][2], 0, true);
		m.observeNametag(Structure.PRECURSOR_CITY, "Professor Robot", 714.5, 118, 600.5, 0, true);
		// The Jade crystal's barrier box, as the crystal scanner finds it near the keepers' centre
		BarrierBoxes.Box jade = BarrierBoxes.pickCrystalBox(BarrierBoxes.group(snap.findBlocks("minecraft:barrier", 590, 60, 410, 660, 130, 480)), 20, 621.5, 89, 445.5);
		if (jade != null) m.setCrystal(Structure.MINES_OF_DIVAN, jade.min(), jade.max());
		return m;
	}
}
