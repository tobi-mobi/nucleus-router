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
| Q2 | Where does the Jungle Temple's teleport (after the crystal) put you? | At the Door Guardian (`stops.templeExitOffset` 0 0 0) | `/nr where` now shows your **Position** and **From Jungle Temple: +x +y +z**. Right after the teleport, type `/nr where` and send the "From Jungle Temple" line (that's the offset). (The first try couldn't work: the position line was missing, sorry.) |

### For the setup designer (Milestone 4)

| # | Question | What the mod assumes now (config) | How to settle it |
|---|---|---|---|
| Q7 | Dropped Bob-omb: how far above the landing block is the blast centred? | 1 block above (`bombs.dropCentreBlocksAbove`). **One throw on 2026-11-26 matched exactly** (0.00 off) | Send the other throws' result lines (from the same spot); if they match too, this is settled |
| Q8 | How far can you place a block on Hypixel? | 4.5 blocks (`placing.reach`) | Stand still, hold cobblestone and try to place against a wall while backing away one block at a time. Use `/nr where` on the farthest spot that worked and on the block you placed against |
| Q14 | How close to a big structure can you place blocks? | Unknown; placements inside the guessed 81-block zones are only flagged ("may be refused") | Walk towards a structure placing a cobblestone every few blocks. `/nr where` at the first spot where placing is refused, and name the structure |
| Q15 | Can you pillar up (jump and place a block under yourself) in the Hollows, also over a bomb crater? | Yes: targets left floating over craters are reached by pillaring up to 16 blocks (`setup.maxPillarBlocks`) | Stand in a crater, look straight down, jump and place cobblestone under you a few times |
| Q16 | Sneaking to the edge of a block: can you place a block against the side of the block you're standing on? | Yes, with your body up to 0.7 blocks past its middle | Pillar up 3 blocks, sneak to an edge, look down at the pillar's side and place cobblestone there |
| Q17 | In lobby mini196W King Yolkar was marked in an empty cave. Was the **Goblin Queen's Den** marker right in that lobby (on the crystal box in the middle of the castle)? | Unknown: tells whether the King's offset changes per lobby or the Queen's box was mistaken | Next time you're near the den: is the Queen's marker on the crystal? Also: does King Yolkar now show as "~ (go closer)" until you see him, then exactly on him? |
| Q9 | Structure zone bounds (where bombs fizzle) | 81 x 81 x 81 blocks around each structure; a bomb only fizzles if its centre is inside (confirmed) | Only needed if a planned bomb near a structure does nothing. Report which structure and where you threw from |
| Q10 | Can a bomb thrown 9+ blocks away hurt you or break the block you stand on? | No: throws are planned from at least 9 blocks away (`setup.minThrowDistance`) | During a `/nr design` test, throw from the planned spot: did you take damage or lose your floor? |
| Q11 | Does an ore or gemstone broken by a Bob-omb come back (like a mined one)? | Yes: blasted ores still count as solid when planning | Blast an ore vein, wait 5 minutes, check whether it's back |

### Low priority

| # | Question | What the mod assumes now | How to settle it |
|---|---|---|---|
| Q13 | How do the Wishing Compass particles look? | Skyblocker's solver is read instead, so this barely matters | Use a compass and copy the "Compass line #1 recorded (...)" chat line |

---

## Test checklists (built, not yet tried in-game)

### Suggested order for one session (covers the most with the least travelling)
1. **Quick answers first** (5 minutes, anywhere open in the Hollows): Q8 (placing reach), Q15 (pillar up), Q16 (place from a block's edge), Q7 (2-3 dropped bombs with `/nr calibrate bomb`). These decide whether the setup planner's basic moves are right.
2. **Find all structures**, then `/nr route` and `/nr setup`. Send the "Lobby setup planned in X s" line, and `/nr dump` if you can.
3. **One leg of guided setup** (`/nr setup start`): a few throws, one pillar, one edge placement. Note anything that doesn't tick off or looks wrong. Throw one bomb off-plan on purpose to see the re-plan message.
4. `/nr verify` after that leg.
5. **A run** with `/nr run` (Milestone 3 checklist): timer, splits, den countdown (starts on "CRYSTAL FOUND ... Amber Crystal"), crystal splits at pickup, temple teleport spot (Q2), King Yolkar marker (Q17).

Anything you only get partly through is still useful: send what you have.

### Milestone 3: run mode and run HUD (3.5, 3.6)
1. `/nr route`, then `/nr run`. The run panel replaces the structure list (top left). The timer starts when you first move 2 blocks.
2. Between structures it shows "Next: ..." and "To: <structure> <distance>". Inside one it shows "At / Do / Then".
3. The etherwarp count goes up by one per etherwarp (sneak + right-click), and normal teleports are counted separately. Does it match what you actually did?
4. Each crystal's split is taken the moment its "CRYSTAL FOUND" message appears (King Yolkar's when you leave him), shown against the plan's estimate. Do the split times match when you took each crystal?
5. When the route ends (normally the last /warp nucleus), chat lists all splits.
6. `/nr run den` starts the den countdown by hand: check it's readable. At the real den, take the Amber crystal: the countdown should appear with the "CRYSTAL FOUND" message; then /warp nucleus or go 30+ blocks up: it ends and chat says how long leaving took.
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

### Milestone 4: when the setup goes wrong (4.7)
1. During a B step, throw the Bob-omb somewhere else on purpose (or let one fizzle near a structure). About 4-5 s later chat says what happened ("did nothing", "went off N blocks from the plan", "left N blocks standing") and "Re-planned: ... for this leg". The next B number continues from the highest so far (e.g. B31). Does the message match what you saw?
2. If a bomb fizzled: chat gives its spot. That's direct evidence for **Q9** (structure zone bounds): send the line.
3. Skip a throw with `]` without throwing it. After the last bomb, chat says some blocks "are still there: mine them first" with new M steps.
4. After all bombs and mining: "Placing planned again on the real terrain: N block(s) to place". Then place as usual.
5. `/nr setup replan` any time: re-plans the current leg's bombs (or the placing once the bombs are done).
6. `/nr verify` (best: after `/nr route`, and again after the setup): chat says how many planned etherwarps work right now; failing ones get a red box on the target, orange on what's in the way, for a minute. Mine or place a block on a planned warp and run it again: is that warp reported? `/nr verify clear` hides the boxes.
