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
| Q9 | Structure zone bounds (where bombs fizzle) | 81 x 81 x 81 blocks around each structure; a bomb only fizzles if its centre is inside (confirmed) | Only needed if a planned bomb near a structure does nothing. Report which structure and where you threw from |
| Q10 | You said a bomb breaks the block you stand on. Was that from a planned B spot (at least 9 blocks from the blast), or closer? | Planned throws keep your floor out of the blast (`setup.minThrowDistance` 9) | Next guided setup: after a B throw, are you still standing on your block? If not, tell Claude the B number |

### Low priority

| # | Question | What the mod assumes now | How to settle it |
|---|---|---|---|
| Q13 | How do the Wishing Compass particles look? | Skyblocker's solver is read instead, so this barely matters | Use a compass and copy the "Compass line #1 recorded (...)" chat line |

---

## Test checklists (built, not yet tried in-game)

### Final test plan (5.5): suggested order, most important first
Each step lists the checklist below that has the details. Two or three sessions in a fresh, low-day lobby should cover all of it.

**Session 1: plan a lobby (no setup work yet)**
1. Find all 6 structures (the list at the top left shows 6/6). Then `/nr route` (Milestone 3) and `/nr setup` (4.5). **Send the "Lobby setup planned in X s" line** and, if you can, run `/nr dump` and send the file. That gives real-lobby numbers.
2. `/nr perf` with the setup preview showing (5.3). Send the output.
3. At King Yolkar: `/nr setpoint` (5.1), then `/nr setpoint list`.

**Session 2: build the setup**
4. `/nr setup start` and do at least one whole leg (4.6): throws, mining, a pillar and an edge placement. Note any step that doesn't tick off by itself.
5. Throw one bomb off-plan on purpose, and skip one throw with `]` (4.7). Do the re-plan messages match what happened?
6. `/nr verify` after that leg (4.7). It shows any planned warps that don't work.
7. If a bomb near a structure did nothing: send its chat line (Q9). After each throw: are you still on your block (Q10)?

**Session 3: run it**
8. `/nr route`, then `/nr run` for a full run (Milestone 3): timer, splits at each "CRYSTAL FOUND", den countdown, warp count.
9. Right after the temple teleport: `/nr where` and send the "From Jungle Temple" line (Q2), then `/nr setpoint temple.exit` (5.1).
10. In Khazad-dum: `/nr bal` (5.2, optional).
11. `/nr perf` once more during the run (5.3).

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

### Milestone 4: single-leg design preview (4.1-4.4)
1. Stand somewhere open in the Hollows and type `/nr design <structure>` (divan, precursor, king, queen, temple, khazad). Chat says what was designed. In the world you see the lanes, target blocks, bomb throws and blocks to mine.
2. Does the design look sensible? Straight lanes, few bends, nothing through ore veins or structures.
3. Optional: do one throw exactly as shown. Is the crater roughly where the preview says? (Also answers Q10.)
4. Placements (4.4): "P" numbers on the blocks to place, in order. For a normal one, a yellow spot to stand on and a yellow square on the face to click. Pillars show as a yellow column with "pillar up" on top. Try one of each: can you do it from the shown spot?
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

### Milestone 5: your own stop points (5.1)
1. Stand where you like to be at King Yolkar and type `/nr setpoint` (it picks the nearest stop) or `/nr setpoint king`. Chat says the block and the offset from King Yolkar; a **gold** block with "My stop: King Yolkar" appears under you.
2. `/nr setpoint list` shows all 7 stops (divan, precursor, king, queen, temple, temple.exit, khazad) and which use your point.
3. Next lobby: once King Yolkar is found, the gold block is at the same spot relative to him. Is it the same place you chose? `/nr route` and `/nr run` should end the leg there.
4. Mines of Divan / Queen / Khazad-dum need their crystal box found first (chat tells you if not).
5. `temple.exit`: after the temple teleport, stand where it put you and `/nr setpoint temple.exit`. That replaces the guessed front-of-temple offset (also answers Q2).
6. Optional: bind "Save my stop point here (nearest stop)" in Controls > Nucleus Router and try it.
7. `/nr setpoint clear king` (or `all`) goes back to the automatic spots.

### Milestone 5: Bal block suggestion (5.2, optional)
1. In a lobby with Khazad-dum found (Topaz crystal box seen), `/nr route`, then `/nr bal`.
2. Chat either says where to place one block and how many warps it saves, or why there's nothing to suggest.
3. In the world: yellow block = where to place, green pad + white dot = where to stand and what to click, cyan block + line = the landing spot you warp from straight onto the new block.
4. Place it: the markers disappear and chat says to `/nr route` again. Does the new route use the block (fewer warps into Khazad-dum)? Is landing there a good place to fight Bal from? If it's too far from where you like to fight, lower Settings > Placing > "Bal block: max distance".

### Milestone 5: performance (5.3)
1. In the Hollows with things on screen (best: `/nr setup` preview shown, then `/nr run`), play for a minute and type `/nr perf`. The first line ("Drawing per frame") should be green: under 0.5 ms. If it's red, send the whole output (it lists which overlay or panel is slow).
2. Same command shows "Memory: terrain of this lobby ... MB": after exploring a lot of a lobby it should be around 30-60 MB. Send the line.
3. `/nr route` in a big lobby: about how long until the result appears? (It used to be a bit slower; should feel the same or quicker.)
4. With the whole-lobby preview on (`/nr setup`), far-away bombs and placements no longer show their B/P numbers and markers; they appear when you're within ~48 blocks. Is that fine while setting up?
