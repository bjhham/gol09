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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import gol09.composeapp.generated.resources.Res
import kscript.KScriptParser
import kscript.KScriptRunner
import org.jetbrains.game.GameGrid
import org.jetbrains.game.MapParser

val scriptParser by lazy { KScriptParser() }
val scriptRunner by lazy { KScriptRunner() }

/**
 * Resource path of the initial level map, loaded at application startup.
 */
private const val INITIAL_MAP_PATH = "files/maps/level0"

@Composable
@Preview
fun App() {
    MaterialTheme {
        // The current parsed game grid. `null` until the initial map has finished loading.
        var gameGrid by remember { mutableStateOf<GameGrid?>(null) }

        LaunchedEffect(Unit) {
            val bytes = Res.readBytes(INITIAL_MAP_PATH)
            gameGrid = MapParser().parse(bytes.decodeToString())
        }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize(),
        ) {
            // Upper half: canvas for drawing the game state.
            // Read `gameGrid` here so changes to the loaded model trigger recomposition;
            // actual rendering will be implemented in a follow-up.
            val currentGrid = gameGrid
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                // TODO: render `currentGrid` once rendering is implemented.
                currentGrid?.let { /* no-op for now */ }
            }

            // Status bar: empty for now, with a play button on the right
            // for starting/stopping the simulation.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { /* TODO: start/stop simulation */ }) {
                    Text("▶")
                }
            }

            // Lower half: text area for the user's code input.
            var code by remember { mutableStateOf("") }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                label = { Text("Code") },
            )
        }
    }
}
