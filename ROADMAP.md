# Nucleus Router: roadmap

Claude Code: work on the first unchecked item. Each item lists the `SPEC.md` sections to read. Tick items as they're done, and add a line to the session log at the bottom.
At the end of each milestone: give the user the in-game test checklist plus the open [VERIFY] items, then stop and wait.

---

## Milestone 1: Skeleton and sensing
- [x] 1.1 Gradle/Loom project for MC 26.2, Java 25, Fabric API, accepts Fabric Loader 0.20.1. Module or package layout `core` / `game` / `platform`. Builds and launches in the dev client. (§1)
- [x] 1.2 JSON config + YACL config screen + Mod Menu entry. Add every tunable from §2 now, with the spec's defaults. (§1, §2)
- [x] 1.3 `/nr` command root. Crystal Hollows detection (Hypixel Mod API location event, with scoreboard/tab-list fallback), lobby id, lobby day (`dayTime / 24000`), and ping reading. (§1, §2.1, §2.6)
- [ ] 1.4 World model: voxel classes, sticky "ghost solid" memory for respawning blocks, capture from chunk loads and block updates, per-lobby persistence on disk. Unit tests. (§3.1, §2.4)
- [ ] 1.5 Etherwarp ray model (DDA, first hit, 2 air above, full-cube rule, configurable eye height, valid aim region). Unit tests. (§2.3)
- [ ] 1.6 `/nr debug ray` overlay + logging of predicted vs actual landing + eye-height suggestion. Also the first world-render utility: lines, outlines and filled boxes through walls, working on OpenGL, Vulkan and Iris. (§6, §5)
- [ ] 1.7 `/nr calibrate bomb` (snapshot → block diff → measured centre/radius/shape, impact vs predicted throw) and `/nr dump`. (§6, §2.4)
- [ ] 1.8 Milestone report: in-game checklist + open [VERIFY] items. **Stop.**

## Milestone 2: Discovery and lobby evaluation
- [ ] 2.1 Structure anchors from NPC/entity nametags + scoreboard area names. Manual set/clear commands. (§3.5, §4)
- [ ] 2.2 Wishing Compass particle capture + multi-use 3D triangulation + uncertainty display + "use another compass from here" hint. (§4)
- [ ] 2.3 Precise scanner + structure bounds/exclusion zones + `/nr capture <structure>` template dump + orientation detection. (§4, §6, §2.4)
- [ ] 2.4 Exploration coverage tracking. (§4)
- [ ] 2.5 Lobby evaluator + swap notifications with reasons (spread, day, coverage without finds, estimated cost), each rule configurable. (§4)
- [ ] 2.6 "All structures found" gate + HUD list of missing structures. (§4)
- [ ] 2.7 Milestone report. **Stop.**

## Milestone 3: Run routes on existing terrain
- [ ] 3.1 Cost model (base warp + ping, turn time, miss probability × recovery, manual segment times, `/warp nucleus` edge). Unit tests. (§2.6)
- [ ] 3.2 Leg planner A: landing nodes, lazy edges, A* over (node, aim bucket), ray cache + voxel→edge invalidation. Unit tests + benchmark. (§3.3)
- [ ] 3.3 Default stop points guessed from anchors (keepers, Robot/crystal, King, Queen crystal + den exit ≤ 2.5 s, temple door → temple steps, Bal spot, nucleus). (§2.2, §3.5)
- [ ] 3.4 Route ordering: precedence (King before Queen), direct vs `/warp nucleus` per transition, keeper sub-order, branch-and-bound. (§3.2)
- [ ] 3.5 Run-mode renderer: full-route line coloured per leg, dashed manual segments, next 10 targets numbered and fading, optional aim dot, advance on arrival, no replanning off-route. (§5)
- [ ] 3.6 Run HUD: current stop, next action, 3 s Queen-den countdown, warp count, run timer with per-crystal splits. (§5, §2.2)
- [ ] 3.7 Milestone report with measured planner timings. **Stop.**

## Milestone 4: Setup designer
- [ ] 4.1 Straight-lane model (T_k = T_0 + kΔ, constant look direction, aim-region maximisation). Unit tests. (§3.4)
- [ ] 4.2 Leg designer: candidate polylines with 0–3 bends, avoiding unbreakable, structure, exclusion and respawning voxels; post-setup validity check. (§3.4)
- [ ] 4.3 Bomb planner: progressive greedy sphere cover (r = 8), feasible throws (straight ray up to max range, then straight down), never touching structure zones; mine steps where bombs can't be used. (§3.4, §2.4)
- [ ] 4.4 Block-placement planner (+2/+1 style targets, support face, reach, standing spot). (§2.5, §3.4)
- [ ] 4.5 Full lobby setup plan within 5 s on a worker thread, with HUD progress. Benchmark on a `/nr dump` of a real lobby. (§3.4, §7)
- [ ] 4.6 Guided setup rendering (throw / place / mine markers, blast-sphere wireframes, numbered steps), auto step completion from block updates, skip/confirm keybind. (§5)
- [ ] 4.7 Local replanning when a step fails (setup mode only) + "verify route" check. (§5)
- [ ] 4.8 Milestone report. **Stop.**

## Milestone 5: Polish
- [ ] 5.1 `/nr setpoint <stop>` + keybind: user-defined default stop points stored relative to anchor and orientation, used in every lobby. (§3.5)
- [ ] 5.2 Optional: Bal-area block-placement suggestion. (§2.2)
- [ ] 5.3 Performance pass: < 0.5 ms per frame for rendering, planner timings within §3 targets, memory check.
- [ ] 5.4 Prepare the 26.3 port: keep version-specific code isolated in `platform` and list what has to change.
- [ ] 5.5 Final report. **Stop.**

---

## Confirmed game facts (from in-game tests; these override SPEC.md)
| Item | Value | Confirmed on |
|---|---|---|
| Etherwarp eye height | _[VERIFY]_ (default 1.54) | |
| Hard Stone block type(s) | _[VERIFY]_ (default minecraft:stone) | |
| Respawn time of ores/gemstones | _[VERIFY]_ | |
| Bob-omb throw range / drop rule | _[VERIFY]_ (default 45, straight then down) | |
| Bob-omb blast centre / metric | _[VERIFY]_ (default sphere r = 8 from impact) | |
| Structure zone bounds (bomb fizzle) | _[VERIFY]_ | |
| Structures rotate or mirror per lobby? | _[VERIFY]_ | |
| `/warp nucleus` spawn point + time | _[VERIFY]_ (default 2.5 s) | |
| `/warp nucleus` counts as leaving the Queen's den? | _[VERIFY]_ (default: no) | |
| Block placing allowed everywhere in the Hollows? | _[VERIFY]_ (default: yes, outside structures) | |
| Crystal Hollows bounds | _[VERIFY]_ (default x,z 202–823, y 31–188) | |
| Wishing Compass particle behaviour | _[VERIFY]_ | |
| Hypixel Mod API location packets arrive (via SkyHanni's subscription) and mode = `crystal_hollows` | Yes: `server=mini89R type=SKYBLOCK mode=crystal_hollows map=Crystal Hollows` | 2026-11-24 |
| Tab list shows `Area: Crystal Hollows` and `Server: mini…` | Yes (`Area: Crystal Hollows`, `Server: mini89R`). No `Ping:` widget in the user's tab list | 2026-11-24 |
| Scoreboard area names inside the Hollows (list in `game/HollowsAreas.java`) | "Mines of Divan" confirmed; its ⏣ marker arrives as a custom-font character, so names are matched directly. Others _[VERIFY]_ (`/nr where` in each area) | 2026-11-24 (partial) |
| Own tab-list latency is real ping on Hypixel | **No**: shows 1 ms for an EU player. Values <= 5 ms are ignored; `cost.fallbackPingMs` is used | 2026-11-24 |
| Lobby day from the world clock matches the real lobby day | Yes (day 14 at clock 356020 matched) | 2026-11-24 |

## Decisions (changes from SPEC.md)
- 2026-11-23 Build versions (looked up, not from memory): MC 26.2, Fabric Loader 0.20.1, Loom 1.19-SNAPSHOT (resolves 1.19.4), Fabric API 0.161.0+26.2, Gradle 9.8.2, YACL 3.9.7+26.2-fabric, Mod Menu 20.0.1.
- 2026-11-23 Mod id `nucleusrouter`, Java package `dev.nucleusrouter`. `core` / `game` / `platform` are packages in one Gradle project, not separate modules. `LayerRulesTest` fails the build if `core` or `game` import Minecraft/Fabric classes.
- 2026-11-23 YACL is a required dependency (it's in the pack). Mod Menu is optional.
- 2026-11-23 Config tunables added beyond SPEC §2 (estimates, not game facts; user to confirm): `stops.npcInteractionSeconds` 1.0, `stops.crystalPickupSeconds` 0.5, `cost.turnExponent` 1.5 (turn time = turnSecondsAt90 x (angle/90)^exp), `cost.fallbackPingMs` 100, `bombs.fuseSeconds` 3.0 (upper end of 2.5-3 s, safer), `bombs.blastCentre` enum (default: centre of the impact block).
- 2026-11-24 Hypixel Mod API is used **listen-only**: the mod adds a handler but never calls `subscribeToEventPacket` or `sendPacket`, so it causes no extra packets (Hard rule). Location packets arrive only because SkyHanni already subscribes; otherwise tab list, then scoreboard, are used. Ping comes from the vanilla tab-list latency, then the SkyBlock tab "Ping:" widget, then `cost.fallbackPingMs`. The Mod API ping packet is never sent.
- 2026-11-24 `/nucleusrouter` is an alias of `/nr` in case another mod takes `/nr`. Added `general.chatNotices` (local-only chat lines, default on).
- 2026-11-24 Note for 1.4: Hypixel reuses server ids (e.g. mini123AB) for new lobbies, so the per-lobby save key must also detect a fresh lobby (e.g. lobby day going backwards), not just the server id.

## Session log
- 2026-11-23: 1.1 Gradle/Loom project builds and launches in the dev client. 1.2 JSON config (`config/nucleusrouter.json`, auto-repairs bad values, broken file moved to `.broken`) + YACL screen with 6 categories + Mod Menu entry. 3 unit tests pass. Next: 1.3 `/nr` command root, Crystal Hollows detection, lobby id/day, ping.
- 2026-11-24: 1.3 `/nr` (help, where, where raw, config, reload). Location from Mod API (listen-only) > tab list > scoreboard; lobby id canonicalised (`m123AB` -> `mini123AB`); lobby day = clock / 24000; ping with fallbacks. 10 unit tests pass. Next: 1.4 world model.
- 2026-11-24: In-game test of 1.3: Mod API, lobby id and lobby day work. Fixed scoreboard area (custom-font marker) and fake 1 ms ping (now falls back to config). 11 tests pass. Plan: measure real warp delay passively from the user's own etherwarps in 1.6. Next: 1.4.
