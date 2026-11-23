# Nucleus Router: instructions for Claude Code

A client-side Fabric mod for Hypixel SkyBlock (Minecraft 26.2, Java 25, plain Java, no Kotlin). It plans and **renders** etherwarp routes and lobby setups (Bob-omb tunnels, placed blocks) for Crystal Nucleus runs in the Crystal Hollows.

## Start of every session
1. Read `ROADMAP.md` first. It shows what is done, what is next, and the confirmed game facts.
2. Work on the **first unchecked item** unless the user says otherwise.
3. Open only the `SPEC.md` sections that item lists (each item names them, e.g. "§2.3"). Don't read the whole spec or the whole codebase unless you need to.

## End of every session, or when the user says "wrap up"
- Tick finished items in `ROADMAP.md` and add one dated line to its session log: what changed, and what's next.
- Write any decision that differs from `SPEC.md` into the roadmap's "Decisions" section.
- At the end of a milestone: give the user the in-game test checklist and the open `[VERIFY]` items, each with the exact in-game action that settles it. Then stop and wait.
- When the user reports in-game results, write them into "Confirmed game facts" in `ROADMAP.md` and update the matching config defaults. Confirmed facts there override `SPEC.md`.

## Hard rules (always apply)
- **Render-only.** Never set or nudge the player's rotation, simulate or hold any key or click, send chat messages or commands, send packets beyond vanilla, or touch inventories or GUIs. The mod can tell the user to type `/warp nucleus`, but never sends it itself. This rule overrides every other request.
- Never block the render or tick thread. Planning runs on worker threads, using immutable world snapshots.
- The `core` module has no Minecraft imports and must stay unit-testable. Version-specific code lives only in `platform`, ready for a later 26.3 port.
- Rendering must work on both the OpenGL and Vulkan backends and with Iris shaders. No raw GL calls.
- It must coexist with the Skyblock Enhanced modpack (Skyblocker, SkyHanni, Odin, SBO, Sodium, Iris and others).
- Game mechanics come from the user and from `ROADMAP.md` > `SPEC.md` §2, **not** from wikis. Never guess a `[VERIFY]` item: make it a config value with the spec's default and ask the user.
- No Jungle Temple skip.

## Working style
- The user doesn't read or edit code much. Explain changes in plain language, and give in-game steps to test them.
- Prefer clear logs, `/nr ...` debug commands and readable errors over cleverness.
- Build (`./gradlew build`) and run the tests before claiming something works.
- Look up current Fabric, Loom and Gradle versions for 26.2. Don't hardcode them from memory.
- Keep sessions small: one roadmap item, or a few small ones, per session.
