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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import gol09.composeapp.generated.resources.Res
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kscript.CompilationException
import kscript.KFile
import kscript.KFunctionParameter
import kscript.KIdentifier
import kscript.KInt
import kscript.KScriptParser
import kscript.KScriptRunner
import kscript.KString
import kscript.KToken
import kscript.KTokenData
import kscript.KTokenType
import kscript.KUnit
import kscript.KVariableDeclaration
import kscript.bridgeFunction
import kscript.bridgeFunctionVoid
import kscript.bridgeGetter
import kscript.emptyProcessState
import kscript.parseFile
import org.jetbrains.game.Cheese
import org.jetbrains.game.GameGrid
import org.jetbrains.game.Golem
import org.jetbrains.game.MapParser
import org.jetbrains.game.Position

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
)

/**
 * Delay between simulation ticks, in milliseconds, while the simulation is running.
 */
private const val SIMULATION_TICK_MILLIS = 500L

/**
 * Source code that initially populates the editor. Provides a working program
 * for the first puzzle and demonstrates how the `move()` bridge function
 * advances the golem one cell per call.
 */
private const val INITIAL_CODE = "move()"

/**
 * Snapshot of an in-progress walk animation. While `move()` is animating,
 * the golem's logical position in [GameGrid] is held at [from] until
 * [progress] reaches `1f`; the canvas renders its figure interpolated
 * between [from] and [to] in the meantime. Holding the model still until
 * the animation completes keeps the level-complete overlay (which keys on
 * the golem occupying a cheese cell) in sync with the visible motion.
 */
private data class WalkAnimation(
    val from: Position,
    val to: Position,
    val progress: Float,
)

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
        // `move()` function, which advances the golem one cell in its facing
        // direction over SIMULATION_TICK_MILLIS, animating the figure as it
        // slides between cells (with a vertical bob and walking legs). It
        // can also call `turnRight()` / `turnLeft()` to rotate the golem
        // in place; turning updates state immediately and does not advance
        // simulation time. Pausing cancels this effect, but the in-progress
        // step (if any) is allowed to finish first so the golem doesn't stop
        // halfway between cells; cancellation is then observed when the
        // script runner asks for its next instruction.
        LaunchedEffect(isRunning) {
            if (!isRunning) {
                walkAnimation = null
                return@LaunchedEffect
            }
            val moveFn = bridgeFunctionVoid("move") {
                // Bail out before doing any work if the simulation has been
                // paused/reset since the last instruction. The move's slide
                // runs inside a `withContext(NonCancellable)` block below,
                // which intentionally swallows parent cancellation so the
                // golem doesn't stop mid-cell; without this guard the
                // script would happily start *another* move after the
                // previous one completed even though the user had asked
                // the simulation to stop.
                coroutineContext.ensureActive()
                if (!isRunning) return@bridgeFunctionVoid
                val grid = gameGrid ?: return@bridgeFunctionVoid
                val target = grid.moveGolem()
                val from = grid.golem.position
                val to = target.golem.position
                // Run the step inside a NonCancellable context so that pressing
                // pause mid-step lets the current move animation complete before
                // the simulation coroutine is cancelled. Cancellation will be
                // observed at the next suspension point after this block (i.e.
                // when the script runner asks for its next instruction). Refresh
                // also flips `isRunning` to false, but additionally replaces
                // `gameGrid`; we detect that below and skip committing the move
                // so refresh isn't overwritten by an in-flight animation.
                withContext(NonCancellable) {
                    if (from == to) {
                        // The move would have taken the golem off the grid, so the
                        // model didn't change. Still pace the simulation so the
                        // script doesn't spin, but skip the slide animation.
                        delay(SIMULATION_TICK_MILLIS)
                        return@withContext
                    }
                    walkAnimation = WalkAnimation(from = from, to = to, progress = 0f)
                    val startMillis = withFrameMillis { it }
                    while (true) {
                        val nowMillis = withFrameMillis { it }
                        val elapsed = nowMillis - startMillis
                        val progress = (elapsed.toFloat() / SIMULATION_TICK_MILLIS).coerceIn(0f, 1f)
                        walkAnimation = WalkAnimation(from = from, to = to, progress = progress)
                        if (progress >= 1f) break
                    }
                    // Commit the move to the model and clear the animation so the
                    // golem renders in its idle pose at its new cell. If the grid
                    // has been replaced underneath us (e.g. by the refresh button),
                    // honour that change instead of stomping on it.
                    if (gameGrid === grid) {
                        gameGrid = target
                    }
                    walkAnimation = null
                }
            }
            val turnRightFn = bridgeFunctionVoid("turnRight") {
                gameGrid = gameGrid?.turnGolemRight()
            }
            val turnLeftFn = bridgeFunctionVoid("turnLeft") {
                gameGrid = gameGrid?.turnGolemLeft()
            }
            // `warp(name)` jumps to a level by base file name (e.g. `warp("level1")`
            // loads `files/maps/level1`). Useful for testing as more stages are
            // added: a script can position the golem on any level without having
            // to advance through the preceding ones via the UI. Updating
            // [levelIndex] triggers the keyed `LaunchedEffect(levelIndex)` above
            // to reload the map and reset per-level state (including
            // `isRunning`); we additionally throw [CancellationException] so the
            // currently-executing script stops immediately rather than running
            // one more instruction against the freshly-loaded grid.
            val warpFn = bridgeFunctionVoid(
                "warp",
                parameters = listOf(KFunctionParameter("name", "String")),
            ) {
                val nameDecl = state[KIdentifier("name")] as KVariableDeclaration
                val nameValue = nameDecl.initializer?.let { runner.execute(it, state) }
                val name = (nameValue as? KString)?.value
                    ?: error("warp(name): expected a String argument, got $nameValue")
                val targetIndex = LEVEL_MAP_PATHS.indexOfFirst { it.substringAfterLast('/') == name }
                if (targetIndex < 0) {
                    error("warp(name): unknown level '$name'")
                }
                isRunning = false
                if (levelIndex != targetIndex) {
                    levelIndex = targetIndex
                }
                throw CancellationException("warp(\"$name\")")
            }
            // Bridge getters expose the golem's current coordinates to the script.
            // They re-evaluate on every reference so the script always sees the
            // up-to-date position after `move()` calls.
            val intType = KIdentifier("Int")
            val xGetter = bridgeGetter("x", intType) {
                val value = gameGrid?.golem?.position?.x ?: 0
                KInt(KToken(KTokenData.Text(KTokenType.INTEGER_LITERAL, value.toString())), value)
            }
            val yGetter = bridgeGetter("y", intType) {
                val value = gameGrid?.golem?.position?.y ?: 0
                KInt(KToken(KTokenData.Text(KTokenType.INTEGER_LITERAL, value.toString())), value)
            }
            val state = emptyProcessState().apply {
                this[KIdentifier("move")] = moveFn
                this[KIdentifier("turnRight")] = turnRightFn
                this[KIdentifier("turnLeft")] = turnLeftFn
                this[KIdentifier("warp")] = warpFn
                this[xGetter.name] = xGetter
                this[yGetter.name] = yGetter
            }
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
                scriptRunner.execute(file, state)
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
                                    isRunning = false
                                    gameGrid = initialGrid
                                    walkAnimation = null
                                    levelCompleteDismissed = true
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
