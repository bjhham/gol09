package org.jetbrains

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import gol09.composeapp.generated.resources.Res
import kotlin.math.min
import kotlinx.coroutines.ensureActive
import kscript.CompilationException
import kscript.KScriptParser
import kscript.KScriptRunner
import kscript.LoopLimitExceededException
import kscript.parseFile
import org.jetbrains.game.Cheese
import org.jetbrains.game.GameGrid
import org.jetbrains.game.Golem
import org.jetbrains.game.MapParser

val scriptParser by lazy { KScriptParser() }
val scriptRunner by lazy { KScriptRunner() }

/**
 * Resource paths of the level maps, in play order. The first entry is the
 * level loaded at application startup; the level-complete overlay's
 * "Next Level" button advances through the list and is hidden when the
 * player completes the final level.
 */
private val LEVEL_MAP_PATHS = listOf(
    "files/maps/level0",
    "files/maps/level1",
    "files/maps/level2",
    "files/maps/level3",
)

/**
 * Delay between simulation ticks, in milliseconds, while the simulation is running.
 */
private const val SIMULATION_TICK_MILLIS = 200L

/**
 * Maximum number of script-loop iterations to allow before the play loop
 * gives up and pauses the simulation. This bounds two failure modes that
 * would otherwise freeze the UI:
 *  - the user hits play with no `move()` (or other suspending) call in
 *    the script, so the outer play loop in [App] spins as fast as the
 *    CPU allows; and
 *  - the user writes a runaway `while (true) { ... }` whose body never
 *    yields control back to the host.
 * The value is intentionally generous so that legitimate, slow-moving
 * scripts (paced by `move()`) don't trip it within a reasonable session.
 */
private const val SCRIPT_LOOP_LIMIT = 10_000

/**
 * Source code that initially populates the editor. Provides a working program
 * for the first puzzle and demonstrates how the `move()` bridge function
 * advances the golem one cell per call.
 */
private const val INITIAL_CODE = "move()"

@Composable
@Preview
fun App() {
    AppTheme {
        // Index of the currently-loaded level within [LEVEL_MAP_PATHS]. Advancing
        // to a new level (e.g. via the "Next Level" button on the level-complete
        // overlay) updates this and triggers a reload via the keyed
        // `LaunchedEffect` below.
        var levelIndex by remember { mutableStateOf(0) }

        // The freshly-parsed initial game grid. `null` until the initial map has finished loading.
        // This is used both as the starting state and as the target of the refresh button.
        var initialGrid by remember { mutableStateOf<GameGrid?>(null) }

        // The current parsed game grid. `null` until the initial map has finished loading.
        var gameGrid by remember { mutableStateOf<GameGrid?>(null) }

        // Whether the simulation is currently running. Toggled by the play/pause button.
        var isRunning by remember { mutableStateOf(false) }

        // Whether the player has acknowledged the level-complete overlay for the
        // current level. Reset when the grid is reset (e.g. via refresh) so the
        // overlay can appear again if the level is replayed.
        var levelCompleteDismissed by remember { mutableStateOf(false) }

        // Animation state for the golem's movement. While `move()` is in
        // progress, [walkAnimation] is non-null and describes the cell the
        // golem is leaving (`from`), the cell it is entering (`to`), and
        // the progress through the step in the range `[0f, 1f]`. Outside
        // an active step the field is `null` and the golem is rendered at
        // its grid cell with no animation. The state lives at this scope
        // (rather than inside the simulation `LaunchedEffect`) so the
        // canvas can read it on every recomposition.
        var walkAnimation by remember { mutableStateOf<WalkAnimation?>(null) }

        // The level is complete as soon as the golem occupies a cell that
        // contains a [Cheese]. Recomputed from the current grid so the overlay
        // appears the moment the simulation steps onto the goal cell.
        val levelComplete = gameGrid?.let { grid ->
            val golemPos = grid.golem.position
            grid.tokens.any { it is Cheese && it.position == golemPos }
        } ?: false

        // Pause the simulation immediately when the level is completed so the
        // script doesn't keep ticking under the celebration overlay.
        LaunchedEffect(levelComplete) {
            if (levelComplete) {
                isRunning = false
            }
        }

        // The user's source code, plus its most recently parsed `KFile`. The
        // file is `null` while the editor is empty or while the current text
        // fails to parse; in both cases there's nothing for the runner to
        // execute, so the play button stays disabled. The editor is
        // pre-populated with `move()` so the first puzzle has a working
        // program out of the box.
        var code by remember { mutableStateOf(INITIAL_CODE) }
        var kFile by remember {
            mutableStateOf(
                try {
                    scriptParser.parseFile(INITIAL_CODE)
                } catch (_: CompilationException) {
                    null
                }
            )
        }

        // Load (or reload, when [levelIndex] changes) the active level. Resets
        // the per-level UI state so the player starts each level with the
        // golem at its `START` cell, the simulation paused, and the
        // level-complete overlay un-dismissed.
        LaunchedEffect(levelIndex) {
            val bytes = Res.readBytes(LEVEL_MAP_PATHS[levelIndex])
            val parsed = MapParser().parse(bytes.decodeToString())
            initialGrid = parsed
            gameGrid = parsed
            isRunning = false
            walkAnimation = null
            levelCompleteDismissed = false
        }

        // Drive the simulation while running: execute the user's parsed `KFile`
        // via `scriptRunner` in a loop. The script can call the bridged
        // declarations supplied by [buildInitialState] (e.g. `move()`,
        // `turnRight()`, `turnLeft()`, `warp(name)`, and the `x`/`y`
        // getters), each backed by an action on [GameViewModel]. Pausing
        // cancels this effect, but the in-progress `move()` step (if any) is
        // allowed to finish first so the golem doesn't stop halfway between
        // cells; cancellation is then observed when the script runner asks
        // for its next instruction.
        LaunchedEffect(isRunning) {
            if (!isRunning) {
                walkAnimation = null
                return@LaunchedEffect
            }
            val vm = GameViewModel(
                getGameGrid = { gameGrid },
                setGameGrid = { gameGrid = it },
                isRunning = { isRunning },
                setRunning = { isRunning = it },
                getLevelIndex = { levelIndex },
                setLevelIndex = { levelIndex = it },
                setWalkAnimation = { walkAnimation = it },
                levelMapPaths = LEVEL_MAP_PATHS,
                tickMillis = SIMULATION_TICK_MILLIS,
            )
            val state = buildInitialState(vm, loopLimit = SCRIPT_LOOP_LIMIT)
            try {
                while (true) {
                    // The script runner only suspends inside bridged `move()`
                    // calls, and that suspension happens in a `NonCancellable`
                    // context — meaning it won't observe cancellation of this
                    // effect. We therefore re-check on each iteration so that
                    // toggling `isRunning` (via pause or refresh) actually
                    // stops the script between top-level executions.
                    coroutineContext.ensureActive()
                    if (!isRunning) break
                    val file = kFile ?: break
                    // Tick the shared loop counter for each top-level
                    // execution iteration so a non-suspending script
                    // (e.g. an empty program) trips the configured limit
                    // and lands in the catch block below instead of
                    // freezing the UI.
                    state.recordLoopIteration()
                    scriptRunner.execute(file, state)
                }
            } catch (e: Exception) {
                // The script blew through its iteration budget — likely an
                // empty program or a `while (true) {}` that never yields.
                // Pause the simulation so the user can edit and try again
                // rather than wedging the play loop.
                isRunning = false
                walkAnimation = null
                // TODO surface the error to the user
                e.printStackTrace()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize(),
        ) {
            // Upper half: canvas for drawing the game state.
            val currentGrid = gameGrid
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                currentGrid?.let { grid ->
                    // Use square cells and centre the grid inside the canvas.
                    val cellSize = min(
                        size.width / grid.width,
                        size.height / grid.height,
                    )
                    val gridWidth = cellSize * grid.width
                    val gridHeight = cellSize * grid.height
                    val originX = (size.width - gridWidth) / 2f
                    val originY = (size.height - gridHeight) / 2f

                    // Draw faint grid lines so the 24x24 cell divisions are visible.
                    val gridLineColor = Color.White.copy(alpha = 0.25f)
                    val gridLineStroke = 1f
                    for (col in 0..grid.width) {
                        val x = originX + col * cellSize
                        drawLine(
                            color = gridLineColor,
                            start = Offset(x, originY),
                            end = Offset(x, originY + gridHeight),
                            strokeWidth = gridLineStroke,
                        )
                    }
                    for (row in 0..grid.height) {
                        val y = originY + row * cellSize
                        drawLine(
                            color = gridLineColor,
                            start = Offset(originX, y),
                            end = Offset(originX + gridWidth, y),
                            strokeWidth = gridLineStroke,
                        )
                    }

                    // While a walk animation is in progress, render the
                    // golem at an interpolated position between its `from`
                    // and `to` cells, with a non-zero walk phase so the
                    // figure bobs and its legs animate. Other tokens (and
                    // the golem when no animation is in flight) render at
                    // their grid cell with no animation.
                    val activeWalk = walkAnimation
                    for (token in grid.elements) {
                        if (token is Golem && activeWalk != null) {
                            val p = activeWalk.progress
                            val animX = activeWalk.from.x + (activeWalk.to.x - activeWalk.from.x) * p
                            val animY = activeWalk.from.y + (activeWalk.to.y - activeWalk.from.y) * p
                            val cellOrigin = Offset(
                                x = originX + animX * cellSize,
                                y = originY + animY * cellSize,
                            )
                            token.paint(this, cellOrigin, cellSize, walkPhase = p)
                        } else {
                            val cellOrigin = Offset(
                                x = originX + token.position.x * cellSize,
                                y = originY + token.position.y * cellSize,
                            )
                            token.paint(this, cellOrigin, cellSize)
                        }
                    }
                }
            }

            // Status bar: empty for now, with refresh and play buttons on the
            // right for resetting and starting/stopping the simulation.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // Refresh resets the grid back to the freshly-loaded initial state
                // and stops the simulation. Disabled while the grid already matches
                // that initial state (i.e. nothing to refresh).
                val initial = initialGrid
                val isInitialState = initial != null && gameGrid == initial
                IconButton(
                    onClick = {
                        isRunning = false
                        gameGrid = initial
                        walkAnimation = null
                        levelCompleteDismissed = false
                    },
                    enabled = !isInitialState,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Play/pause toggles the simulation. The play button is disabled
                // while there is no successfully-parsed `KFile` for the runner to
                // consume; once the user is running, the pause action is always
                // available so they can stop the simulation.
                IconButton(
                    onClick = { isRunning = !isRunning },
                    enabled = isRunning || (kFile != null && !levelComplete),
                ) {
                    if (isRunning) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "Pause",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Lower half: rich code editor for the user's source input.
            //
            // The text is re-parsed on every change and the resulting `KFile` is
            // stored in state for both the script runner and the editor itself
            // to consume — the editor uses it to drive token-based syntax
            // highlighting. Parsing failures are expected while the user is
            // mid-edit, so we swallow `CompilationException` and simply leave
            // `kFile` as `null` until the input parses cleanly again; the
            // editor falls back to unstyled rendering in that case. While the
            // simulation is running the editor is disabled so the code can't
            // change underneath the runner.
            CodeEditor(
                code = code,
                onCodeChange = { newCode ->
                    code = newCode
                    kFile = try {
                        scriptParser.parseFile(newCode)
                    } catch (e: CompilationException) {
                        e.printStackTrace()
                        null
                    }
                },
                kFile = kFile,
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
            )
        }

        // Level-complete overlay. When the golem occupies a Cheese cell and
        // the player has not yet dismissed the celebration, draw a dimming
        // scrim over the whole screen with a centred card congratulating
        // the player. The card offers a "Play Again" button that resets
        // the current level, plus — when there is a subsequent level
        // available — a "Next Level" button that advances to it.
        if (levelComplete && !levelCompleteDismissed) {
            val hasNextLevel = levelIndex < LEVEL_MAP_PATHS.lastIndex
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(modifier = Modifier.padding(24.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Level complete!",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "The golem reached the cheese.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = {
                                    // Reset to the freshly-loaded initial grid for
                                    // the current level and clear any in-flight
                                    // animation so the golem starts cleanly.
                                    // The grid reset moves the golem off the
                                    // cheese, so `levelComplete` flips back to
                                    // false and hides the overlay; clearing
                                    // `levelCompleteDismissed` ensures the
                                    // celebration appears again the next time
                                    // the player reaches the cheese.
                                    isRunning = false
                                    gameGrid = initialGrid
                                    walkAnimation = null
                                    levelCompleteDismissed = false
                                },
                            ) {
                                Text("Play Again")
                            }
                            if (hasNextLevel) {
                                Button(
                                    onClick = {
                                        // Stop the simulation and clear any
                                        // in-flight animation immediately so
                                        // the golem doesn't keep moving while
                                        // the next level loads. Advancing the
                                        // level index triggers the
                                        // `LaunchedEffect(levelIndex)` above
                                        // to reload the map and reset the
                                        // remaining per-level state.
                                        isRunning = false
                                        walkAnimation = null
                                        levelIndex += 1
                                    },
                                ) {
                                    Text("Next Level")
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
