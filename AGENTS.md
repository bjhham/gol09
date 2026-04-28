# AGENTS.md

Operational notes for AI coding agents (e.g. Junie) working in this repository.
Human contributors are welcome to read this too — it captures conventions that
have proven useful while iterating on the project.

## Testing

- **Run JVM tests only.** A JVM test run is sufficient for verifying changes;
  do **not** run the full Kotlin Multiplatform test matrix (js, wasmJs,
  iosSimulatorArm64, android) on every change. Those targets are slow and add
  little signal for typical edits.
- Prefer running a specific test class or directory rather than the whole
  module. Examples:
  - A single test file:
    `composeApp/src/commonTest/kotlin/org/jetbrains/game/MapParserTest.kt`
  - All common tests in the composeApp module:
    `composeApp/src/commonTest/kotlin/org/jetbrains`
- If a change is platform-specific (e.g. touches `jvmMain`, `iosMain`, etc.),
  run the tests for that target in addition to the JVM tests.
- Common-source tests are duplicated per target by Kotlin Multiplatform; running
  them on the JVM target executes the same logic without paying for the other
  targets.

## Project layout

- This is a Kotlin Multiplatform + Compose Multiplatform project.
- Targets enabled in `composeApp/build.gradle.kts`: `androidTarget`,
  `iosArm64`, `iosSimulatorArm64`, `jvm`, `js` (browser), `wasmJs` (browser).
- Modules:
  - `composeApp` — the Compose Multiplatform application (UI + game logic).
  - `shared` — code shared between platforms.
  - `kscript` — a separate Gradle build for the embedded scripting library
    (`org.jetbrains:kscript`); it's published locally and consumed by
    `composeApp` via Maven coordinates.

### Source sets of interest

- `composeApp/src/commonMain/kotlin/org/jetbrains/` — application entry point
  (`App.kt`).
- `composeApp/src/commonMain/kotlin/org/jetbrains/game/` — game model
  (`GameGrid`, `Position`, `Direction`), tokens (`GameToken`, `Golem`), and
  the map parser (`MapParser`).
- `composeApp/src/commonTest/kotlin/org/jetbrains/` — common tests, including
  `MapParserTest`.

### Resources

- Compose Multiplatform resources live under
  `composeApp/src/commonMain/composeResources/` and are accessed at runtime via
  the generated `Res` object (`gol09.composeapp.generated.resources.Res`),
  e.g. `Res.readBytes("files/maps/level0")`.
- Stage map files live in `composeApp/src/commonMain/composeResources/files/maps/`.
- **Do not** put runtime-loadable resources under `src/commonMain/resources/`;
  on non-JVM Compose targets they are not packaged the same way and won't be
  reachable from `Res`.

## Game model conventions

- Grid origin `(0, 0)` is the **top-left** corner. `x` increases to the east,
  `y` increases to the south.
- `GRID_SIZE` is currently `12` (declared in `GameGrid.kt`); update both the
  constant and the corresponding tests if this changes.
- The map file format is plain text, one entry per line:
  `OBJECT_REF X,Y`
  - `#` starts a comment; blank lines are ignored.
  - Currently supported refs: `START` (the golem's starting cell, facing
    `Direction.SOUTH`).
  - Malformed input throws `MapParseException`.
- Renderable entities implement the `GameToken` sealed interface and provide a
  `paint(scope, cellOrigin, cellSize)` method. The canvas is responsible only
  for layout (cell size, grid origin); each token paints itself within its
  cell.

## Code style

- Match existing conventions in the surrounding files: 4-space indentation,
  Kotlin official style, KDoc on public declarations, trailing commas where
  already used.
- Use ratio constants (e.g. `HEAD_HEIGHT_RATIO`) inside private companions
  rather than magic numbers when describing rendering proportions; this is the
  pattern established in `GameToken.kt`.
- Keep rendering schematic and gameplay-readable rather than realistic.

## Build & verification tips

- Prefer `run_test` on a specific path over `build` — it's much faster and
  builds only what's needed for the requested tests.
- `lint` the file you just edited to catch obvious issues without running a
  full build.
- The pre-existing unused-property warnings for `scriptParser` and
  `scriptRunner` in `App.kt` are known and unrelated to current work; ignore
  them unless the task is specifically about wiring scripting in.

## Housekeeping

- The `.junie/` folder is reserved for guideline/configuration files; do not
  use it for scratch or temporary artifacts.
- Don't commit on the user's behalf unless explicitly asked. When you do,
  add the Junie co-author trailer:
  `--trailer "Co-authored-by: Junie <junie@jetbrains.com>"`.
