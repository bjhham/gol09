package org.jetbrains

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    // Mobile phone aspect ratio (1 : 2.25) for a consistent experience across platforms.
    val widthDp = 400.dp
    val heightDp = 900.dp // 400 * 2.25
    Window(
        onCloseRequest = ::exitApplication,
        title = "gol09",
        state = rememberWindowState(width = widthDp, height = heightDp),
        resizable = false,
    ) {
        App()
    }
}
