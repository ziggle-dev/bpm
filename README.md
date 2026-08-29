# bpm

**Visual scripting for Minecraft automation.** Graphs run on the server, are edited in-game on a node canvas, and
every transfer they make is drawn in the world.

NeoForge 1.21.1 · Kotlin · built on the [vscript](https://github.com/osrsx/osrsx-vscript) language, VM and canvas editor

---

## What it is

bpm adds a **Quantum Controller**: a block that runs a program you draw as a node graph. Link it to chests, tanks,
machines, cables, floors, doors — anything with a face — and the graph moves items, fluids, energy and experience
between them, mines, places, clicks, reads redstone, writes to wall displays, and talks to you in chat.

The graph is not a script that gets *transpiled* into something else. It is compiled and executed by vscript's VM
on the server thread, one pass per tick, with real coroutines: a node that has to wait (a slow mining job, a
`Wait For` on an inventory) simply parks its fiber and resumes when the world catches up.

## How it plays

1. **Place a Quantum Controller.** Right-click it to open the workbench — its own graph is created on first open.
2. **Bind a Quantum Linker** by sneak-using the controller with the wand in hand.
3. **Link things.** Use the wand on a block face and it gets a name (`chest`, `chest-2`, …). Link names hang over
   the blocks while you hold the wand; sneak-use a linked face to unlink it, ctrl-use to rename.
4. **Draw the program.** The `on tick` event *is* the program: its body runs once per server tick as a long-lived
   loop, and latent nodes stretch a pass over as many ticks as they need. `on start` / `on stop` are one-shot hooks.
5. **Save and deploy.** Ctrl+S saves, Ctrl+Shift+S deploys; edits autosave after four idle seconds. The debug
   drawer shows the validator's findings, live variable scopes, logs, and honours breakpoints.

The controller has stores of its own that every verb can name as `self`: nine item slots, four 16,000 mB tanks
and a 100,000 FE cell, all exposed as block capabilities so pipes and cables reach them directly.

Everything the graph does is visible: items travel between a rift and the face they came from, fluids, energy
and experience stream as dust, and a world job swings the actual tool in the controller's hand.

### Node libraries

127 host nodes in 14 libraries, on top of vscript's own flow, math, string, list and map nodes:

| library | what it covers |
|---|---|
| `items` | filters (`filter`, `anyOf`, `allOf`, `not`), move / count / has / find / wait-for across any linked inventory, drop to the ground |
| `fluids`, `energy` | move / count between tanks and cells; pick up and place fluid source blocks |
| `xp` | vacuum and drop experience orbs; XP is stored as a real fluid (`bpm:experience`, 20 mB a point) that pipes and buckets understand |
| `world` | vacuum ground items, `click` a block or entity exactly as a player would (break, use, attack — with the stack from a buffer slot), `area` boxes, entity / block / state queries |
| `redstone` | read signals, and emit them remotely through a **Signal Emitter** |
| `controller` | links by name or by coordinate, link info, own state |
| `monitor` | text, item, fluid, energy and bar widgets on a wall of **Quantum Monitor** blocks |
| `chat` | messages and action-bar notifications to players |
| `gate`, `pedestal`, `trap`, `turret`, `phase` | the Decoherence Chamber's devices, scriptable from a graph |

### Content

- **Entanglium** ore (stone and deepslate, with worldgen) → shards → **Quantum Alloy** → lenses, circuits and conduits.
- The **Quantum Gate**, a multiblock that opens onto a per-player room in the `bpm:decoherence` dimension.
- The **Quantum Warden**, a staged boss fought with the linker's pulses among observer turrets, phase spikes,
  decoherence vents and superposition floor. It guards a **Quantum Core**; refined and pristine cores upgrade a
  controller's link range and tick budget.
- The **Warden's Visor**, **Phase Gauntlet**, **Entangled Compass** and **Warden's Program**.
- All devices are GeckoLib models with Molang-driven animation.

### `/bpm` (operators)

Documents, controllers and links can also be driven from chat — `/bpm docs`, `/bpm doc load|export|delete`,
`/bpm bind|unbind`, `/bpm start|stop|restart|status <pos>`, `/bpm links|link|unlink`, `/bpm slot|fluid|energy`,
`/bpm chamber enter|leave|awaken|reset`, `/bpm gate open`, `/bpm warden spawn`, `/bpm stats`, `/bpm catalog`,
`/bpm editor` (the workbench free-standing, for library graphs).

## Under the hood

- **One graph per controller**, created on first open and deleted with the block. **Library graphs** are the shared,
  importable documents; both live in a per-world `SavedData` library.
- **Single-writer editing**: a lease per document, whole-document snapshot commits, watch-based status for viewers,
  and a conflict card instead of silent overwrites. The old per-edit packet storm is gone.
- **VM on the server thread only.** Multi-tick actions are `TickJob`s awaited by the fiber; nothing runs off-thread.
- **World actions behave like a player**: breaking uses the real stack (speed, harvestability, Fortune / Silk Touch,
  durability, `BreakEvent`), drops land on the ground, and a sword click is a sword swing, sweep included.
- **The editor is vscript's ImGui canvas inside a Minecraft `Screen`.** `bpm.client.editor` is Minecraft-free (an
  architecture test keeps it that way); `bpm.client.mc` is the glue.
- **In-world effects** are coalesced per (kind, from, to) stream and sent only to players tracking either end.

## Building and running

Requirements: JDK 21 (provisioned by the toolchain), Gradle 9.6.1 (wrapper), and access to the vscript repository.

```
git clone --recurse-submodules https://github.com/ziggle-dev/bpm.git
cd bpm

gradlew build                            # compile, unit tests, assemble the jar (bundled libs jar-in-jar'd)
gradlew runClient                        # dev client
gradlew runClient -Pworld="Test World"   # straight into that single-player world (created on first use)
gradlew runClient -Pframes=120           # smoke test: open the editor on the title screen, draw 120 frames, quit
gradlew runServer                        # dev dedicated server
gradlew runGameTestServer                # the game tests
```

`vscript/` is a git submodule built as part of this build (`includeBuild` in `settings.gradle`), so changes to the
language, runtime or canvas are picked up on the next Gradle run with no publish step. The host seams bpm needs
live on the submodule's `feat/bpm-host-seams` branch. On Windows run the wrapper as `gradlew.bat` from a native
shell.

Runtime dependencies for players: [NeoForge](https://neoforged.net/) 21.1+, [Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge) 5.12+,
[GeckoLib](https://github.com/bernie-g/geckolib) 4.5+. vscript, Dear ImGui and commonmark ship inside the jar.

Dev runs also load Mekanism, JEI, Just Dire Things and Tag & Component Tooltips from the runtime classpath — never
compiled against, never bundled — so there are real machines, pipes and cables to link against while testing.

## Repository layout

```
src/main/kotlin/bpm/
  Bpm.kt, BpmCommands.kt, BpmConfig.kt   mod entry, /bpm, server config
  catalog/     value types, the node catalogue and its hash
  nodes/       the 14 node libraries and the ControllerHost they act through
  runtime/     RuntimeManager, ControllerRuntime, tick jobs, world jobs, run view, effects
  library/     the per-world document library
  session/     editor sessions, leases, the commit pipeline
  net/         payloads, chunked transfer, server side of the protocol
  world/       blocks, items, block entities, fluids, links, content registries
  chamber/     the Decoherence Chamber: room builder, fight state machine, per-player slots
  client/      editor/ (Minecraft-free workbench), mc/ (Screen + input glue), render/, fx/, net/
src/main/resources/   assets (geo, animations, textures, models, lang) and data (recipes, tags, loot, worldgen, dimension)
src/dev/              dev-only: game tests, a websocket Lua console, sample documents — never in the jar
src/test/             JUnit, including an ImGui harness for the editor
vscript/              the language, VM and editor (git submodule)
```

## Tests

- `gradlew test` — unit tests: the editor under an ImGui harness, networking chunks, catalogue comparison,
  effect streams, tick jobs, editor sessions, the architecture rule.
- `gradlew runGameTestServer` — game tests against a live server: controllers and every world verb, monitors,
  core tiers, traps and turrets, the chamber, the Warden.

## Status

Early and moving fast — version 0.1.0, no release yet. The controller, linker, editor, node libraries, networking,
effects, monitors, and the chamber with its boss are in and covered by tests. Still to come: the Quantum Relay
(link range extender), a collection log, sounds, and an item-side model for the visor.

## License

MIT, as declared in the mod manifest.
