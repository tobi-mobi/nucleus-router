# In-game checks: things only a human can test or answer

Everything here needs someone in the game, on Hypixel, in the Crystal Hollows. Claude can't settle any of it.
- **Open questions** are game facts the mod currently *guesses*. Each guess is a config value, so if the answer is
  different nothing breaks for good: the default just gets changed.
- **Test checklists** are features that have been built and pass the automated tests but haven't been tried in-game.

When you have an answer, tell Claude (a short note is enough, e.g. "den countdown started fine, side exit only showed 2 s late").
Claude then moves it to "Confirmed game facts" in `ROADMAP.md`, updates the default, and deletes it here.

---

## Open questions

### High priority (affect routes or the run directly)

| # | Question | What the mod assumes now (config) | How to settle it |
|---|---|---|---|
| Q1 | Normal Aspect of the Void teleport: what happens when a block is in the way? And does it work straight up and straight down? | Stops just in front of the block; works in every direction (`teleport.stopsAtBlocks`) | Stand ~8 blocks from a wall and click the normal teleport (not etherwarp) at it: where do you end up? Then look straight up in a tall cave and click, then straight down. Do you move 12 blocks each time? |
| Q2 | Where does the Jungle Temple's teleport (after the crystal) put you? | At the Door Guardian (`stops.templeExitOffset` 0 0 0) | After taking the Amethyst crystal, take the teleport. Stand where it puts you and type `/nr where`, then stand on the first Door Guardian and type `/nr where`. Send both lines |
| Q3 | Can you take each crystal from the planned standing spot (within 3 blocks of the barrier box)? | Yes (`stops.crystalReach` 3) | `/nr stops` shows the blue standing spots. Stand on the Sapphire, Amber and Topaz spots and try to take the crystal from there. Say which ones fail |
| Q4 | Which chat line means "you just took the Amber crystal"? It starts the 3 s den countdown | "The Goblin King's foul stench has dissipated" (`run.denTimerStartMessages`) | Take the Amber crystal. Does the big "LEAVE DEN" countdown appear right then? If not, copy the exact chat line you see when you take it |
| Q5 | Where are the Queen's castle side walls (leaving sideways also stops the 3 s)? | Unknown. The countdown only notices a side exit once the scoreboard stops saying "Goblin Queen's Den", so it may look late | Take the crystal and leave out the side (not up, no /warp nucleus). Did the countdown end when you got out, or only later? If later, `/nr where` at the spot where you were safely out |
| Q6 | Is there a chat line when you pick up each crystal? | Unknown. Splits are taken when you leave a structure | Copy the chat line you get on each crystal pickup (Jade, Sapphire, Amber, Amethyst, Topaz). With them, splits can be exact |

### For the setup designer (Milestone 4)

| # | Question | What the mod assumes now (config) | How to settle it |
|---|---|---|---|
| Q7 | Dropped Bob-omb (one that flew its full 32 blocks and fell): how far above the landing block is the blast centred? | 1 block above (`bombs.dropCentreBlocksAbove`) | Throw a bomb at nothing so it drops onto **flat** ground, with `/nr calibrate bomb` running. Send the result line. Two or three throws are enough |
| Q8 | How far can you place a block on Hypixel? | 4.5 blocks (`placing.reach`) | Stand still, hold cobblestone and try to place against a wall while backing away one block at a time. Use `/nr where` on the farthest spot that worked and on the block you placed against |
| Q9 | Structure zone bounds (where bombs fizzle) | 81 x 81 x 81 blocks around each structure; a bomb only fizzles if its centre is inside (confirmed) | Only needed if a planned bomb near a structure does nothing. Report which structure and where you threw from |
| Q10 | Can a bomb thrown 9+ blocks away hurt you or break the block you stand on? | No: throws are planned from at least 9 blocks away (`setup.minThrowDistance`) | During a `/nr design` test, throw from the planned spot: did you take damage or lose your floor? |
| Q11 | Does an ore or gemstone broken by a Bob-omb come back (like a mined one)? | Yes: blasted ores still count as solid when planning | Blast an ore vein, wait 5 minutes, check whether it's back |

### Low priority

| # | Question | What the mod assumes now | How to settle it |
|---|---|---|---|
| Q12 | Is King Yolkar always at the same offset from the Queen's den crystal? | Yes (learned from one lobby) | Nothing to do: if a lobby disagrees, chat says "Re-learned King Yolkar's crystal". Tell Claude if you ever see that line |
| Q13 | How do the Wishing Compass particles look? | Skyblocker's solver is read instead, so this barely matters | Use a compass and copy the "Compass line #1 recorded (...)" chat line |

---

## Test checklists (built, not yet tried in-game)

### Milestone 3: run mode and run HUD (3.5, 3.6)
1. `/nr route`, then `/nr run`. The run panel replaces the structure list (top left). The timer starts when you first move 2 blocks.
2. Between structures it shows "Next: ..." and "To: <structure> <distance>". Inside one it shows "At / Do / Then".
3. The etherwarp count goes up by one per etherwarp (sneak + right-click), and normal teleports are counted separately. Does it match what you actually did?
4. Leaving each structure adds a split (Jade, Sapphire, King, Amber, Amethyst, Topaz), shown against the plan's estimate.
5. When the route ends (normally the last /warp nucleus), chat lists all splits.
6. `/nr run den` starts the den countdown by hand: check it's readable. At the real den, take the Amber crystal (Q4), then /warp nucleus or go 30+ blocks up: the countdown ends and chat says how long leaving took.
7. `/nr hud` moves both panels. `/nr run stop` brings the structure list back.
8. Normal-teleport stretches show as arrows with "teleport x5" labels. Do the click counts roughly match?

### Milestone 4 so far: setup design preview (4.1-4.3)
1. Stand somewhere open in the Hollows and type `/nr design <structure>` (divan, precursor, king, queen, temple, khazad). Chat says what was designed. In the world you see the lanes, target blocks, bomb throws and blocks to mine.
2. Does the design look sensible? Straight lanes, few bends, nothing through ore veins or structures.
3. Optional: do one throw exactly as shown. Is the crater roughly where the preview says? (Also answers Q10.)
4. `/nr design clear` hides it.
