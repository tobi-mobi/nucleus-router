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
| Q14 | How close to a big structure can you place blocks? | Unknown; placements inside the guessed 81-block zones are only flagged ("may be refused") | Walk towards a structure placing a cobblestone every few blocks. `/nr where` at the first spot where placing is refused, and name the structure |
| Q15 | Can you pillar up (jump and place a block under yourself) in the Hollows, also over a bomb crater? | Yes: targets left floating over craters are reached by pillaring up to 16 blocks (`setup.maxPillarBlocks`) | Stand in a crater, look straight down, jump and place cobblestone under you a few times |
| Q16 | Sneaking to the edge of a block: can you place a block against the side of the block you're standing on? | Yes, with your body up to 0.7 blocks past its middle | Pillar up 3 blocks, sneak to an edge, look down at the pillar's side and place cobblestone there |
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
4. Placements (4.4): "P" numbers on the blocks to place, in order. For a normal one, a yellow spot to stand on and a yellow square on the face to click. Pillars show as a yellow column with "pillar up" on top. Try one of each: can you do it from the shown spot? (Also answers Q8 and Q15.)
5. Chat lists how many target and helper blocks to place. Any it couldn't solve are magenta; tell Claude where.
6. `/nr design clear` hides it.

### Milestone 4: whole-lobby setup plan (4.5)
1. In a lobby where all structures are found: `/nr route` first (so tunnels are only planned where they beat the terrain), then `/nr setup`.
2. A yellow line at the top of the screen shows progress ("designing legs 12/30", "bombs", "placing"). The game should not stutter while it runs.
3. Chat: "Lobby setup planned in X s". **Please send that line**: it is the real-lobby speed measurement (target: under 5 s). Then the run order with, per structure, "existing terrain" or "tunnel: N warps; B bombs, M mined, P placed", the run time after the setup, and any "Problem:" lines.
4. In the world: all legs at once. Bombs are numbered B1, B2... across the whole lobby (do all of them first), then placements P1, P2... "edge" on a P number means: stand on the pillar top and sneak towards the block before placing it.
5. Optional, also very useful: `/nr dump` in that lobby and send the file it writes, so the planner can be tested on your real terrain.

### Milestone 4: guided setup (4.6)
1. After `/nr setup`, type `/nr setup start`. A panel at the top shows "Setup 0/N  bombs 0/B  mining 0/M  placing 0/P", the current instruction and how far away it is.
2. B steps: stand on the cyan spot, aim at the white block (a dot marks the exact point), throw. The cyan wireframe ball is the predicted blast. When every block it should break is gone, the step ticks off by itself and the next one shows. Does it tick off right after the explosion? If a throw doesn't tick off, look at what's left standing, then press `]` (or `/nr setup skip`) and tell Claude.
3. M steps: mine the red blocks; ticks off when they're all gone.
4. After the last bomb and mining step, chat says "now the placing". P steps tick off as soon as the block is there.
5. `]` marks the current step done by hand; `/nr setup back` reopens the last one; `/nr setup stop` ends it. Check in Controls that `]` doesn't clash with another mod in the pack (it can be rebound under "Nucleus Router").
6. Does the game stay smooth with the guide on?
