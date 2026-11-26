package dev.nucleusrouter.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrystalPickupTest {
	/** As seen in-game 2026-11-26, sent as one message. */
	@Test
	void oneMessage() {
		CrystalPickup p = new CrystalPickup("CRYSTAL FOUND");
		assertEquals("Amber", p.onChat(1000, "▬▬▬▬\n ✦ CRYSTAL FOUND (1/5)\n Amber Crystal\n Place this Crystal in the Crystal Nucleus and find all 5 Crystals in the Crystal Hollows to earn rare loot!\n▬▬▬▬"));
	}

	/** The same, as separate lines. */
	@Test
	void separateLines() {
		CrystalPickup p = new CrystalPickup("CRYSTAL FOUND");
		assertNull(p.onChat(1000, "§5§l✦ CRYSTAL FOUND §7(2/5)"));
		assertEquals("Topaz", p.onChat(1100, "§6Topaz Crystal"));
		assertNull(p.onChat(1200, "§6Topaz Crystal"), "one pickup per heading");
	}

	@Test
	void nameWithoutHeadingIsNotAPickup() {
		CrystalPickup p = new CrystalPickup("CRYSTAL FOUND");
		assertNull(p.onChat(1000, "Someone is selling an Amber Crystal"));
		assertNull(p.onChat(1000, "✦ CRYSTAL FOUND (3/5)"));
		assertNull(p.onChat(4000, "Jade Crystal"), "too long after the heading");
	}
}
