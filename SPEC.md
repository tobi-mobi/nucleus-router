# Build "Nucleus Router": a render-only route planner for Hypixel SkyBlock Nucleus Runs

You are building a client-side Fabric mod that plans fast, easy-to-execute **etherwarp routes** for Crystal Nucleus runs in the Crystal Hollows (Hypixel SkyBlock), plans the **one-time lobby setup** (Bob-omb tunnels, placed blocks) that makes those routes possible, and **renders** both in the world.

The user who commissioned this plays Nucleus Runs regularly. The game-mechanics section below comes from them and is more current than the Fandom or official wikis. Where this document and a wiki disagree, follow this document. Where something is marked **[VERIFY]**, do not guess: build it as a config value with the given default, add a debug or calibration tool for it, and list it in your end-of-milestone report so the user can check it in-game.

The user will not read or edit the code much. Prefer clarity, good logs, in-game debug commands and clear error messages over cleverness. Ask the user when a decision depends on game behaviour you can't verify.

---

## 0. Hard constraints (non-negotiable)

1. **Render-only. No automation of any kind.** The mod must never:
   - set or nudge the player's rotation (no aim assist, no smoothing toward targets)
   - press, hold or simulate any key or mouse button (no auto-sneak, auto-use, auto-throw, auto-place)
   - send chat messages or commands (it may *display* "/warp nucleus" as an instruction, but the user types it)
   - send any packets beyond what vanilla sends
   - move items, click in GUIs, or change hotbar slots.

   Macroing gets accounts banned. This rule beats every other requirement.
2. Everything heavy runs **off the render and tick threads**. A frame must never wait on the planner.
3. It must coexist with the user's modpack, **Skyblock Enhanced** (Fabric, 26.2). Relevant members: Skyblocker, SkyHanni, Odin, SBO, SkyOcean, Sodium, Sodium Extra, Iris (with shaders), ImmediatelyFast, Entity Culling, More Culling, Bobby, Fabric Language Kotlin, YetAnotherConfigLib, Cloth Config, and Hypixel Mod API (required by SkyHanni). Don't mixin into anything those mods are likely to overwrite when a Fabric API event exists for it.

## 1. Platform and build

- **Minecraft 26.2, Fabric.** Design for an easy port to **26.3** later: keep all version-touching code (rendering, events, mixins, commands, HUD) in a thin `platform` package, and keep everything else free of Minecraft classes.
- 26.x is **unobfuscated** and uses Mojang's official names. There is no Yarn and no `remapJar`.
- **Java 25** toolchain. Write it in plain **Java** (no Kotlin).
- Look up the current recommended Loom, Gradle, Fabric Loader and Fabric API versions for 26.2 on the Fabric develop site or in the Fabric example mod. Don't hardcode versions from memory. The user runs Fabric Loader 0.20.1, so make sure `fabric.mod.json` accepts it.
- **Rendering must work on both the OpenGL and the Vulkan backends of 26.2, and with Iris shaders on.** Use Fabric API world-render events and the vanilla render-pipeline abstractions. Make no raw GL calls. Skyblocker 6.6 (26.2, LGPL, open source) added Vulkan support, so read its world-rendering utilities as a reference for how to draw lines, boxes and filled faces through walls correctly on 26.2.
- Config: JSON on disk plus a YACL config screen (YACL is already in the pack), and a Mod Menu entry if Mod Menu is present.
- Use **Hypixel Mod API** location events, when available, to detect being in the Crystal Hollows and to get the server/lobby id. Fall back to parsing the scoreboard and tab list.

### Module layout (suggested)
```
core/      pure Java, no Minecraft imports: world model, raycasts, planners, cost model, route/setup plans. Fully unit-testable.
game/      Crystal Hollows knowledge: block classification, structures, stops, lobby evaluation (depends on core).
platform/  Fabric/Minecraft glue: chunk & block-update capture, entity/nametag scan, scoreboard, ping, rendering, HUD, commands, config screen.
```

---

## 2. Game mechanics (authoritative, from the user)

### 2.1 Crystal Hollows and the run
- To start a run, the user types `/warp nucleus`, which puts them at the Crystal Nucleus in the middle of the Hollows. `/warp nucleus` can be used **any time inside the Hollows**. It is a legitimate routing tool: as a shortcut between structures (go to the nucleus, then route out from there) and to finish the run (go back and place the crystals). Model it as an edge from anywhere to the nucleus spawn point, with cost `warpNucleusSeconds` (default 2.5, **[VERIFY]** spawn point and time).
- Structure positions are **randomised per lobby**. The nucleus is always in the centre. Don't hardcode structure coordinates. Treat the Hollows bounds as config (community value ≈ x,z 202–823, y 31–188, **[VERIFY]**).
- A lobby lasts until it closes after roughly **15–20 Minecraft day cycles**. The lobby day is `worldDayTime / 24000`. The user sets up a lobby once, then does as many runs as possible until it closes, so low-day lobbies are valuable.
- **Run time target: 2–4 minutes per run, setup excluded.** The goal is a route that is *easy and reliable to execute* at that pace. It does not need to be frame-perfect.

### 2.2 The stops (the user already owns every item needed)

| Crystal | Structure | What happens there |
|---|---|---|
| Jade | Mines of Divan | Give 1 Scavenged tool to each of the **4 keeper NPCs**, one at the end of each bridge-like arm (Diamond Axe, Lapis Sword, Golden Hammer, Emerald Hammer). Abilities work here. The order of the 4 is free. |
| Sapphire | Precursor City | Give the Precursor Apparatus to **Professor Robot**. A gate opens **next to him**, and the crystal is behind it. |
| Amber | Goblin Holdout | First **King Yolkar**: give a Goblin Egg (the user left-clicks him so the egg isn't thrown). **Then** the **Goblin Queen's Den**: the crystal is at the top of the central structure. **The player must leave the den within 3 seconds** of taking it, meaning the scoreboard must show "Goblin Holdout" before 3 s pass, or they get teleported. Abilities work inside the den, so plan an etherwarp exit and estimate its time with margin (target ≤ 2.5 s). Whether `/warp nucleus` counts as leaving in time is **[VERIFY]**, so don't rely on it by default. |
| Amethyst | Jungle Temple | Give the Jungle Key to the **Kalhuiki Door Guardian**, then do the parkour by hand. **Abilities do not work inside the temple**, so the route **ends at the door guardian** and that section is a "manual segment" (default 25 s, configurable). A **lever next to the crystal teleports the player to the temple's front entrance steps**, and the next leg starts there. From the steps it is sometimes better to `/warp nucleus`, so let the planner decide. **Do not implement any Jungle Temple skip.** |
| Topaz | Khazad-dûm | Kill **Bal**. The current strategy is just to show up and kill him fast (he was made easier). Route to a good standing spot. Fight time is a config value (default 20 s). Blocks can be placed in the Bal area and they stay. Suggesting a block placement for a better spot is a *low-priority, optional* feature. |
| — | Crystal Nucleus | End of run: place the crystals and collect loot. Usually reached via `/warp nucleus`. |

Precedence constraint: **King Yolkar before the Goblin Queen.** All other orders are free.

### 2.3 Etherwarp
- Activated by sneak + right-click with an Aspect of the End or Void that has the Etherwarp upgrade.
- **Range is configurable**: 57 base plus 1 per Transmission Tuner, up to 61. Default 61.
- The ray starts at the player's **sneaking eye position** and follows the look vector. The **first non-air block** hit is the target. **The ray cannot pass through anything**: every non-air block blocks it (panes, flowers, carpets, slabs and so on all count).
- **Every block counts as a full cube**, whatever its real hitbox.
- The target is valid when **the 2 blocks above it are air**. The player lands standing on top of it, centred: feet at `(x+0.5, y+1, z+0.5)`.
- The **eye height the server uses is [VERIFY]**. Hypixel runs legacy server logic, so it may differ from the modern client's sneaking eye height. Default to **1.54** above the feet and make it configurable. Ship the calibration tool in section 6 so it can be measured.
- Use exact voxel traversal (Amanatides–Woo DDA) for rays, not sampled stepping.
- **Robust aim:** for every planned warp, compute the *valid aim region*: the set of look directions from that eye position that land on the intended target. Sample the target's visible faces. Prefer aim points with a large region and a margin from region edges. Human hands are imprecise, so a warp that only works with pixel-perfect aim is a bad warp.

### 2.4 Bob-ombs (the main setup tool; cheap, so the user will spend as many as needed)
- **Thrown.** It travels in a **straight line along the look vector up to 40–50 blocks** (config, default 45, **[VERIFY]**). If it hits a block first it stops there. If it reaches max range it **drops straight down** until it hits something. The **fuse starts on impact** and lasts **2.5–3 s**.
- The blast is a **sphere of radius 8**, regardless of block toughness (**[VERIFY]** the centre and distance metric; see the calibration in section 6).
- It **does not break wood** (any logs, planks, stems and the like) or **any type of dirt** (dirt, grass block, coarse dirt, podzol, rooted dirt, mud and so on). Keep the exact set in config.
- **If the blast sphere touches a structure's region, the whole bomb does nothing.** Structure regions therefore need conservative bounding volumes. Mining into structure walls with the drill is fine and cheap, so the planner may emit "mine these blocks" steps next to structures where bombs can't be used.
- **Only Hard Stone stays gone.** Ores, gemstones (blocks and panes) and everything else that gets broken **respawn**. This is critical:
  - The world model must remember each block's **original** type and treat every position that was ever an ore, gemstone or other respawning block as **permanently solid** for planning, even while it is temporarily air.
  - Planned rays, landing spaces and bomb-cleared corridors must not depend on respawning blocks staying gone.
  - Which vanilla block(s) count as Hard Stone is **[VERIFY]**. Default `minecraft:stone`, as a configurable set.
- Broken blocks drop 50% of their items. That doesn't matter to the planner.

### 2.5 Placing blocks
- The player can place blocks (for example cobblestone) to create etherwarp targets, and placed blocks stay. This is the "+2 / +1" technique: place a block either 2 blocks ahead at eye level or 1 block ahead, to create a target for a fast warp.
- The intended workflow is to **clear large areas with Bob-ombs, then place blocks exactly where a fast warp needs a target.** Planned placements must be physically placeable: an adjacent solid face to place against, within reach (config, default 4.5) from a position the player can stand on during setup.
- Whether placing is allowed everywhere in the Hollows, or only in some areas, is **[VERIFY]**. Make it a config flag (default: allowed everywhere outside structure regions).

### 2.6 What actually costs time (cost model)
The real cost is **changing where you're looking**, and above all the risk that an imprecise hand turn warps you to the wrong spot. Straight lines are king. Use this per-warp cost model, with all constants configurable:

```
cost(warp) = baseWarp                         // default 0.20 s (sneak + click + server round trip; add measured ping/2)
           + turnTime(angleFromPreviousAim)   // smooth increasing function; default ≈ 0 s at 0°, 0.40 s extra at 90°
           + P(miss | angle, aimRegionSize, distance) * missRecovery   // default missRecovery 1.5 s
```
- `P(miss)` grows with turn angle and shrinks with the angular size of the valid aim region. Pick a simple monotone model and document it.
- Zero-turn chains of warps (the same look direction repeated) are the ideal case. See 3.3.
- `/warp nucleus` = `warpNucleusSeconds`. Manual segments (temple parkour, Bal fight, NPC interactions) carry fixed configurable times, so route ordering accounts for them.
- Use the player's in-game ping (from the tab list or the Hypixel Mod API) for `baseWarp`, because ping differs between players.

---

## 3. Planning

### 3.1 World model (core)
- Compact voxel storage for the Hollows, chunk-section based. Only store sections that have been seen.
- Per voxel: current state plus a sticky classification: `AIR`, `HARD_STONE` (removable for good), `RESPAWNING_SOLID` (ore, gemstone and the like, remembered even while air), `UNBREAKABLE` (wood, dirt, bedrock, anything else bombs can't break), `STRUCTURE`, `PLACED` (placed by the player, stays).
- Fed from chunk loads and block-update packets on the client. Hold it per lobby, keyed by server id, and persist it to disk so relogging into the same lobby keeps everything.
- The planner works on **immutable snapshots**. Updates create new versions, and the planner never locks the client thread.

### 3.2 Route ordering (cheap)
- Stops: nucleus spawn → {MoD keepers ×4, Professor Robot/crystal, King Yolkar, Queen crystal (+ den exit), Jungle Temple door → temple steps exit, Bal spot} → nucleus.
- For every transition, choose between **direct route** and **`/warp nucleus` + route from the nucleus spawn**.
- Precompute pairwise leg costs on a worker pool (about 10 points, about 100 directed legs), then enumerate every order that satisfies the precedence rules (≤ 720 structure orders × 24 keeper orders, pruned) with branch-and-bound. Target: < 10 ms once the legs are known.

### 3.3 Leg planner A: existing terrain (no setup)
Usable straight away in any lobby, and used for legs that need no tunnels.
- Nodes are valid landing blocks (the 2 blocks above are air) within the relevant region, held in a spatial hash.
- Edges are valid etherwarps (DDA ray, first hit = target, within range, valid aim region ≥ minimum).
- Search state = (node, incoming aim direction bucket), so turn cost can be priced. Use A* with an admissible heuristic: `ceil(distance / range) * baseWarp`.
- Generate edges lazily and cache ray results. When a voxel changes, invalidate only the cached edges whose rays or landing space touch it (keep a voxel → edge index per leg).
- Targets: a typical leg in < 50 ms, the worst case in < 500 ms, all off-thread.

### 3.4 Leg planner B: designed tunnels (setup)
This is the core value. Design the *ideal* route assuming Hard Stone can be cleared, then output the setup steps.

**Straight lanes.** A lane is a chain of warps with a **constant look direction**. If target blocks `T_k = T_0 + k·Δ` (Δ an integer vector, |Δ| ≤ range − margin) and the corridor is clear, then from each landing the same look direction hits the next target first, so the player just keeps clicking.
- Example: a flat floor in a clear, straight tunnel with a fixed small downward pitch hits the floor at a constant distance every time.
- Bomb craters are bowls with scalloped floors that break this. So a lane is usually **bomb-cleared volume + placed target blocks** (or kept natural Hard Stone) at exact positions.

**Leg design:**
1. Generate candidate polylines from leg start to leg end with 0–3 bends. Each straight segment is one lane, and each bend is one aim change.
2. Make them avoid `UNBREAKABLE` and `STRUCTURE` voxels, the bomb-exclusion zones around structures, and `RESPAWNING_SOLID` voxels (ore and gemstone veins can't be cleared for good, so lanes must go around or above them).
3. For each lane, pick Δ and the look direction that maximise the valid aim region, and check that every ray, landing space (2 air above, player hitbox) and target block is valid in the *post-setup* world.
4. Required clearance = the union of ray corridors (with a tolerance radius) and landing spaces, minus voxels that are already air.
5. Cover the clearance with **bomb spheres (r = 8)** that don't touch any structure region. Greedy set cover, done *progressively from the leg start*: every throw must be feasible from a standing spot that already exists at that point in the setup. The throw ray must reach the impact point (first-hit or max-range-then-drop rules from 2.4).
6. Remaining Hard Stone voxels that bombs can't reach legally (next to structures) → "mine" steps.
7. Target blocks that don't exist → "place block" steps (with a standing spot and a support face).
8. Score the design with the cost model plus setup effort (bombs, placements, setup time, reported but weighted low). Keep the best design per leg.

**Targets:** a full setup plan for a lobby (all legs plus ordering) in < 5 s on a typical 6-core CPU, with progress shown on the HUD.

### 3.5 Default stop points (v1)
The user can't define good stop points without the mod, so **v1 must guess decent ones automatically**:
- Detect each structure's **anchor**: an NPC or entity nametag such as "King Yolkar", "Professor Robot", "Kalhuiki Door Guardian", the Mines of Divan keepers, or Bal. Back it up with scoreboard area names and block signatures.
- The stop point is the best valid landing block within interaction reach of the NPC (or the crystal) that has good approach lines.
- Add a keybind and command (`/nr setpoint <stop>`) that records the player's current position as the **default** for that stop. It is stored **relative to the structure anchor and orientation** so it carries over to every lobby. Whether structures can be rotated or mirrored between lobbies is **[VERIFY]**, so detect orientation from the anchor or template and support 4 rotations if needed.

---

## 4. Lobby discovery and evaluation (setup mode, part 1)

- **Wishing Compass solver:** each use shows particles pointing toward a structure. Capture the particle line from each use, triangulate across multiple uses from different positions (least-squares nearest point between 3D lines), and show the estimate with an uncertainty radius. When the uncertainty is high, tell the user to use another compass from a suggested spot. The user is fine using as many compasses as needed.
- **Scanner:** once the area is loaded, pin the exact location from anchors, block signatures and the scoreboard area name. Add commands to set or clear a structure manually.
- **Exploration coverage:** track the share of Hollows columns seen.
- **Gate: setup planning starts only once every required structure is found** (Mines of Divan, Precursor City, King Yolkar, Goblin Queen's Den, Jungle Temple, Khazad-dûm). Until then, show what's missing.
- **Lobby-swap notification** (each rule configurable and toggleable). Warn when:
  - the structures are too spread out (max pairwise distance > `maxSpreadBlocks`, default 250)
  - the lobby day is too high to pay back the setup, given that lobbies close at about 15–20 days (default warn at day > 6)
  - 70–80% of the Hollows (config) has been explored and required structures are still missing
  - the estimated run time is above target, or setup cost is unusually high, for example because big dirt or wood regions block the lanes.

  Show the reasons, not just a verdict.

## 5. Rendering and HUD

### Setup mode (guided)
- Show a numbered step list in the world. Each step is one of:
  - **Throw**: a stand marker, an aim marker, and the predicted blast sphere as a wireframe
  - **Place**: a ghost block plus the face to click
  - **Mine**: outlined blocks.
- Detect step completion automatically from block updates, and let a keybind skip or confirm a step.
- If reality diverges (a bomb fizzled near a structure, a different blast shape, a block didn't place), mark the step as failed and **replan only the affected leg's remaining steps**. Replanning is allowed in setup mode only.
- **Verify route:** check every planned warp against the current world and highlight any invalid ones.

### Run mode (never replans)
- **A line along the whole route** through the landing points, coloured per leg. Manual segments (temple parkour, Bal fight, NPC hand-ins) are dashed, and `/warp nucleus` steps appear as HUD instructions.
- **Highlight the next 10 warp target blocks**: outline plus translucent fill, numbered, fading by index. The immediate next one is the strongest. Optionally draw an aim dot at the centre of its valid aim region (toggle).
- Detect arrival when the player teleports onto a planned landing (position snap within tolerance) and advance the index. If they land on a *different* route node, jump to it. **If they go off-route, don't recalculate**: keep showing the main route and highlight the nearest route node.
- HUD: current stop, next action ("Give Goblin Egg to King Yolkar", "Type /warp nucleus", "Leave den: 3 s"), a 3-second countdown after taking the Amber crystal, warp count, and the run timer with per-crystal splits.
- Performance: rebuild geometry only when the route or index changes, and keep a render budget of < 0.5 ms per frame.

## 6. Calibration and debug tools (needed early, because several mechanics are [VERIFY])

- `/nr debug ray`: live overlay of the predicted etherwarp target for the current aim. After every real warp, log the predicted and the actual landing. From the mismatches, suggest an eye-height offset and a ray-method correction.
- `/nr calibrate bomb`: snapshot the area, throw, diff the block changes, and report the measured blast centre, radius and shape versus the model. Also log the impact point against the predicted throw trajectory.
- `/nr capture <structure>`: dump the blocks around a detected anchor to JSON (to build templates and structure bounds for later lobbies).
- `/nr dump`: export the lobby's world model and plan, so the planner can be tested offline.
- A debug overlay for voxel classes (respawning solids, unbreakable blocks, structure exclusion zones).

## 7. Testing

- The `core` module has unit tests with synthetic worlds: DDA correctness, first-hit rules, target validity, the respawning-block "ghost solid" rule, bomb sphere and structure-exclusion logic, constant-aim lane validity, route-order precedence, and the `/warp nucleus` edge.
- Benchmarks for leg planning and full setup planning on a dumped real lobby once one exists, plus a large synthetic one until then. Print the timings.
- For each milestone, an **in-game test checklist** written for a non-programmer.

## 8. Milestones (stop after each, report, and wait for the user)

The step-by-step version of these milestones, with progress tracking, is in `ROADMAP.md`. That file is the one to keep up to date.

1. **Skeleton and sensing:** builds for 26.2, loads with the Skyblock Enhanced pack, config screen, commands, Crystal Hollows detection, lobby id and day, world-model capture with persistence, `/nr debug ray` with landing-mismatch logging, and `/nr calibrate bomb`.
2. **Discovery:** anchors and scanner, Wishing Compass triangulation, coverage tracking, lobby evaluation and swap notifications, and the "all structures found" gate.
3. **Run routes on existing terrain:** planner A, route ordering with `/warp nucleus` edges, the run-mode renderer (full line + next 10), and the HUD. Already useful without any setup.
4. **Setup designer:** planner B, guided setup rendering, auto step detection, local replanning, and route verification.
5. **Polish:** `/nr setpoint` defaults, the optional Bal block suggestion, performance tuning, and preparing the 26.3 port.

In each report list: what was built, how to test it in-game, measured performance, and every **[VERIFY]** item still open, with the exact in-game action that would settle it.

## 9. Open items to confirm with the user in-game (don't guess these)
- The eye height the server uses for etherwarp, and how its ray hits blocks.
- The vanilla block types for Hard Stone versus respawning blocks, and how fast blocks respawn.
- Bob-omb throw range, the drop rule, the blast centre and distance metric, and the structure-region bounds that make bombs fizzle.
- Whether structures rotate or mirror between lobbies.
- The `/warp nucleus` spawn point and timing, and whether it counts as leaving the Queen's den in time.
- Whether blocks can be placed anywhere in the Hollows.
- How the Wishing Compass particles look and behave.
- The exact Crystal Hollows bounds.
