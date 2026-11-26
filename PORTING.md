# Porting Nucleus Router to a new Minecraft version (26.3 and later)

Written 2026-11-26 for roadmap item 5.4. The mod is on **Minecraft 26.2**. **26.3 was released on 2026-09-15**
([Fabric's 26.3 post](https://fabricmc.net/2026/09/15/263.html)), so this is the list for that port.

## How the code is split

| Layer | Files | Lines | Touches Minecraft? |
|---|---|---|---|
| `core` (rays, planners, bombs, world model, config) | 37 | ~4,900 | **No.** Enforced by `LayerRulesTest` (no `net.minecraft`, `com.mojang`, `net.fabricmc` imports) |
| `game` (structures, stops, route order, setup steps, run clock) | 27 | ~3,100 | **No.** Same test |
| `platform` (everything that talks to the game) | 48 | ~6,900 | Yes, and only here |

So a port only changes `platform/`, `build.gradle`, `gradle.properties`, `fabric.mod.json` and the gametests.
The 194 unit tests in `core`/`game` must pass unchanged. If one breaks, the port changed game behaviour, not
just code.

Two files already act as walls between the mod and Minecraft's drawing code:

- `platform/render/WorldDraw.java`: **all** drawing in the world (lines, boxes, text). Uses vanilla "gizmos"
  (`net.minecraft.gizmos.Gizmos`, `GizmoStyle`, `TextGizmo`) and Fabric's `LevelExtractionEvents`.
- `platform/render/Hud.java` (new in 5.4): **all** on-screen panels (structure list, run panel, guided setup,
  planning progress). Uses `GuiGraphicsExtractor` and Fabric's `HudElementRegistry`.

If drawing changes, these two files should be the only ones to fix. The two full screens (the map,
`ExploreMapScreen`, and the HUD editor, `HudEditScreen`) still extend vanilla `Screen` directly, because a screen
has to.

## Already done for 26.3 (5.4)

- **GLFW is gone in 26.3 (replaced by SDL3).** Fabric's notes say mouse-button constants changed and code
  should use `InputConstants` instead of GLFW values. The mod used `GLFW.GLFW_KEY_*` for its three default keys
  and plain `0`/`1` for mouse buttons in the HUD editor. Both now use `InputConstants` (`KEY_M`,
  `KEY_RBRACKET`, `UNKNOWN`, `MOUSE_BUTTON_LEFT/RIGHT`), which already exist in 26.2. There is no
  `org.lwjgl.glfw` import left. The mod has no text-input widgets, so the new
  `onTextInputFocusChange` requirement doesn't apply.
- HUD drawing moved behind `Hud.java` (see above).
- Every tick job, HUD panel and world overlay is registered through one helper (`Perf.onTick`, `Hud.add`,
  `WorldDraw.addLayer`), so a renamed Fabric event is a one-line fix.

## What to change, file by file (riskiest first)

Fabric's 26.3 post says the vanilla changes are "primarily on rendering, new item components, world
generation, and registries". It doesn't name most classes we use, so each row below has to be checked
against the 26.3 mappings when porting. "Fabric API" rows are Fabric's stable event API, which rarely breaks.

| # | File | What it uses | Risk | Why it matters / what breaks |
|---|---|---|---|---|
| 1 | `render/WorldDraw.java` | vanilla gizmos, `LevelExtractionEvents.END_EXTRACTION`, `LevelExtractionContext`, `AABB`, `Vec3` | **High** (26.3 is a rendering release) | Every marker, route line and box. If gizmos changed, rewrite only this file. Must still be no raw GL, and work on OpenGL, Vulkan and Iris. Check with the client gametest screenshots |
| 2 | `render/Hud.java` | `GuiGraphicsExtractor` (`fill`, `text`, `centeredText`, `guiWidth/Height`, `pose()`), `HudElementRegistry.addLast`, `HudElement` | **High** | All on-screen panels |
| 3 | `structure/ExploreMapScreen.java`, `structure/HudEditScreen.java` | `Screen.extractRenderState(GuiGraphicsExtractor, ...)`, `MouseButtonEvent` | **High** | Map screen (M) and `/nr hud` editor |
| 4 | `mixin/LevelMixin.java` | `@Inject` into `Level.setBlock(BlockPos, BlockState, int, int)` | **High** | Every block change after a chunk loads (mined, placed, blasted, respawned). Without it the terrain model goes stale **silently** (see the warning below) |
| 5 | `mixin/ClientLevelAccessor.java` | `@Accessor("blockStatePredictionHandler")` on `ClientLevel`, `BlockStatePredictionHandler.isPredicting()` | Medium | Tells your own placements apart from the server's |
| 6 | `mixin/ClientPacketListenerMixin.java` | `handleMovePlayer`, `handleExplosion`, `handleParticleEvent`; `ClientboundPlayerPositionPacket`, `ClientboundExplodePacket`, `ClientboundLevelParticlesPacket` | Medium | Warp landings (debug ray, run mode, warp count), bomb blasts (calibration, setup repair), Wishing Compass particles |
| 7 | `world/WorldCapture.java` | `LevelChunk`, `LevelChunkSection`, `PalettedContainer`, `BuiltInRegistries.BLOCK`, `ClientChunkEvents` | Medium | Reading whole chunks into the terrain model |
| 8 | `world/BlockRules.java` | `BlockState.getCollisionShape(EmptyBlockGetter...)` | Low-medium | "No collision box = invisible to etherwarp" rule (torches, grass) |
| 9 | `location/LocationTracker.java`, `command/NrCommands.java` (`/nr where`) | `Scoreboard`, `Objective`, `DisplaySlot`, `PlayerScoreEntry`, `PlayerTeam`, `PlayerInfo` (tab list) | Low-medium | Crystal Hollows detection fallback, area names, ping |
| 10 | `location/HypixelApiBridge.java` | Hypixel Mod API (`HypixelModAPI`, `ClientboundLocationPacket`) | Low | Needs a Hypixel Mod API build for the new version (the current one already says `mc26.1` and works on 26.2) |
| 11 | `mixin/compat/SkyblockerCrystalsMixin.java` | Skyblocker internals (`CrystalsLocationsManager.extractRendering`, `MiningLocationLabel`, `PrimitiveCollector`) | Low (optional) | Only hides Skyblocker's crystal waypoints. `@Pseudo` + `require = 0`, so it just stops working if Skyblocker changes. Bump `skyblocker_version` to Skyblocker's 26.3 build |
| 12 | `config/ConfigScreen.java`, `config/ModMenuIntegration.java` | YACL (`YetAnotherConfigLib`, option builders), Mod Menu | Low | Bump `yacl_version` and `modmenu_version` to their 26.3 builds |
| 13 | `Keys.java` | `KeyMapping`, `KeyMapping.Category`, `KeyMappingHelper`, `InputConstants` | Low (GLFW part done) | Check `KeyMapping` constructor and `Category.register` |
| 14 | `structure/StructureTracker.java`, `command/InspectCommand.java` | entities' name tags, `ArmorStand`, `EquipmentSlot`, `ItemStack` | Low | Finding NPCs, `/nr inspect` |
| 15 | `structure/RunHud.java`, `compass/CompassTracker.java`, `debug/*` | Fabric `ClientReceiveMessageEvents`, `UseItemCallback`, `UseBlockCallback`, `ParticleOptions` | Low (Fabric API) | Crystal pickup message, AOTV clicks, compass |
| 16 | everything else in `platform` | `Component`, `ChatFormatting`, `Minecraft`, `BlockPos`, `Identifier`, Fabric client commands (Brigadier), `FabricLoader` | Very low | Basic classes that rarely change |

**Warning: the mixins are set to `"defaultRequire": 0`** (in `nucleusrouter.mixins.json`), so a renamed target
method does **not** crash the game. It only logs a warning, and the feature stops working. **While porting, set
`defaultRequire` to `1` and run the game once**, so any missed mixin fails loudly. Then set it back.

## Step by step

1. Look up the current versions for the new Minecraft on <https://fabricmc.net/develop/> and the
   `fabric-example-mod` branch for that version. Don't take them from memory or from this file. Update
   `gradle.properties` (`minecraft_version`, `loader_version`, `loom_version`, `fabric_api_version`, `yacl_version`,
   `modmenu_version`, `hypixel_mod_api*`, `skyblocker_version`), the Gradle wrapper
   (`./gradlew wrapper --gradle-version <version Loom needs>`), and `fabric.mod.json` (`"minecraft": "~26.3"`).
2. `./gradlew build`. Fix compile errors, going down the table above. Keep fixes inside `platform/`.
3. Set the mixins' `defaultRequire` to `1`, run `./gradlew runClient` once to the title screen and into a world,
   fix any mixin that fails, then set it back to `0`.
4. `./gradlew test`: all unit tests (including `LayerRulesTest`) must pass without changes to `core`/`game`.
5. `./gradlew runClientGameTest`, then `./gradlew runClientGameTest -PnrVulkan`. Compare the 12 screenshots in
   `build/run/clientGameTest/screenshots/` with the 26.2 ones: target boxes, fences with torches, structure list,
   map, run mode, run HUD, guided setup, bomb calibration. `BombCalibrationGameTest` also checks the `setBlock`
   and explosion hooks end to end (a radius-8 test blast must be measured at r=8.00 with 100% agreement).
6. In game on Hypixel, with Iris and the Skyblock Enhanced pack: `/nr where` (Hollows detected), `/nr debug ray`
   (green/red target and a real warp recorded), break a block and check `/nr world here`, `/nr perf` (drawing
   under 0.5 ms), the map key, a guided-setup step. The full checklists are in `IN_GAME_CHECKS.md`.
7. Keep the 26.2 build on its own git branch or tag if both versions need to be supported. Only `platform/`
   should differ between them.
