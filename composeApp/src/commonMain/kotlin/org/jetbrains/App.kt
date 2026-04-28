package org.jetbrains

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import kscript.KScriptParser
import kscript.KScriptRunner
import kscript.parseFile
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

        LaunchedEffect(Unit) {
            val bytes = Res.readBytes(INITIAL_MAP_PATH)
            val parsed = MapParser().parse(bytes.decodeToString())
            initialGrid = parsed
            gameGrid = parsed
        }

        // Drive the simulation while running: tick every SIMULATION_TICK_MILLIS,
        // moving the golem one cell in its facing direction. Pausing cancels this
        // effect immediately, stopping the loop.
        LaunchedEffect(isRunning) {
            if (!isRunning) return@LaunchedEffect
            while (true) {
                delay(SIMULATION_TICK_MILLIS)
                gameGrid = gameGrid?.moveGolem()
            }
        }

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
                    },
                    enabled = !isInitialState,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                    )
                }
                IconButton(onClick = { isRunning = !isRunning }) {
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
            // `CompilationException` and simply leave the previous `KFile` in
            // place (or `null` if nothing has parsed successfully yet).
            var code by remember { mutableStateOf("") }
            var kFile by remember { mutableStateOf<KFile?>(null) }
            OutlinedTextField(
                value = code,
                onValueChange = { newCode ->
                    code = newCode
                    kFile = try {
                        scriptParser.parseFile(newCode)
                    } catch (_: CompilationException) {
                        null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                label = { Text("Code") },
            )
        }
    }
}
