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
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.delay
import kscript.CompilationException
import kscript.KFile
import kscript.KIdentifier
import kscript.KInt
import kscript.KScriptParser
import kscript.KScriptRunner
import kscript.KToken
import kscript.KTokenData
import kscript.KTokenType
import kscript.KUnit
import kscript.bridgeFunction
import kscript.bridgeFunctionVoid
import kscript.bridgeGetter
import kscript.emptyProcessState
import kscript.parseFile
import org.jetbrains.game.Cheese
import org.jetbrains.game.GameGrid
import org.jetbrains.game.MapParser

val scriptParser by lazy { KScriptParser() }
val scriptRunner by lazy { KScriptRunner() }

/**
 * Resource path of the initial level map, loaded at application startup.
 */
private const val INITIAL_MAP_PATH = "files/maps/level0"

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

@Composable
@Preview
fun App() {
    MaterialTheme {
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

        LaunchedEffect(Unit) {
            val bytes = Res.readBytes(INITIAL_MAP_PATH)
            val parsed = MapParser().parse(bytes.decodeToString())
            initialGrid = parsed
            gameGrid = parsed
        }

        // Drive the simulation while running: execute the user's parsed `KFile`
        // via `scriptRunner` in a loop. The script can call the bridged
        // `move()` function, which advances the golem one cell in its facing
        // direction and waits SIMULATION_TICK_MILLIS so movement is paced.
        // It can also call `turnRight()` / `turnLeft()` to rotate the golem
        // in place; turning updates state immediately and does not advance
        // simulation time. Pausing cancels this effect immediately, stopping
        // the loop.
        LaunchedEffect(isRunning) {
            if (!isRunning) return@LaunchedEffect
            val moveFn = bridgeFunctionVoid("move") {
                gameGrid = gameGrid?.moveGolem()
                delay(SIMULATION_TICK_MILLIS)
            }
            val turnRightFn = bridgeFunctionVoid("turnRight") {
                gameGrid = gameGrid?.turnGolemRight()
            }
            val turnLeftFn = bridgeFunctionVoid("turnLeft") {
                gameGrid = gameGrid?.turnGolemLeft()
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
                this[xGetter.name] = xGetter
                this[yGetter.name] = yGetter
            }
            while (true) {
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

                    for (token in grid.tokens) {
                        val cellOrigin = Offset(
                            x = originX + token.position.x * cellSize,
                            y = originY + token.position.y * cellSize,
                        )
                        token.paint(this, cellOrigin, cellSize)
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
                        levelCompleteDismissed = false
                    },
                    enabled = !isInitialState,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
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
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                        )
                    }
                }
            }

            // Lower half: text area for the user's code input.
            //
            // The text is re-parsed on every change and the resulting `KFile` is
            // stored in state for the script runner to consume later. Parsing
            // failures are expected while the user is mid-edit, so we swallow
            // `CompilationException` and simply leave `kFile` as `null` until
            // the input parses cleanly again. While the simulation is running
            // the text field is disabled so the code can't change underneath
            // the runner.
            OutlinedTextField(
                value = code,
                onValueChange = { newCode ->
                    code = newCode
                    kFile = try {
                        scriptParser.parseFile(newCode)
                    } catch (e: CompilationException) {
                        e.printStackTrace()
                        null
                    }
                },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                label = { Text("Code") },
            )
        }

        // Level-complete overlay. When the golem occupies a Cheese cell and
        // the player has not yet dismissed the celebration, draw a dimming
        // scrim over the whole screen with a centred card congratulating the
        // player and offering a button to continue to the next level.
        if (levelComplete && !levelCompleteDismissed) {
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
                        Button(onClick = { levelCompleteDismissed = true }) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
        }
    }
}
