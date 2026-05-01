package org.jetbrains.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import gol09.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Pixel-art sprite atlases used to render the [Golem].
 *
 * Each atlas is a horizontal strip of [FRAME_COUNT] frames, where every
 * frame is [FRAME_WIDTH] x [FRAME_HEIGHT] pixels. The frames are sampled
 * at runtime to drive a (currently identical-frame) walking animation.
 *
 * Three views are supplied so the orientation of the figure on the grid
 * can be expressed visually:
 *  - [front] when the golem is facing south (towards the viewer),
 *  - [back] when facing north (away from the viewer),
 *  - [side] when facing east; the same atlas is mirrored horizontally
 *    when facing west.
 *
 * The figure occupies a 32-wide / 64-tall sprite cell, i.e. it is twice
 * as tall as it is wide. When rendered onto the game grid the sprite is
 * scaled so its width matches a single grid cell, and its height extends
 * one full cell *upward* from the cell the golem occupies.
 */
data class GolemSprites(
    val front: ImageBitmap,
    val back: ImageBitmap,
    val side: ImageBitmap,
) {
    companion object {
        /** Width of a single sprite frame, in pixels. */
        const val FRAME_WIDTH: Int = 32

        /** Height of a single sprite frame, in pixels. */
        const val FRAME_HEIGHT: Int = 64

        /** Number of animation frames laid out horizontally in each atlas. */
        const val FRAME_COUNT: Int = 8
    }
}

private const val FRONT_PATH = "files/images/golem-front.png"
private const val BACK_PATH = "files/images/golem-back.png"
private const val SIDE_PATH = "files/images/golem-side.png"

/**
 * Loads and remembers the three [GolemSprites] atlases from Compose
 * Multiplatform resources. Returns `null` while loading is in progress
 * or if any of the resources fails to decode; callers should fall back
 * to not rendering the golem until the sprites become available.
 *
 * The decode happens once per composition; the resulting bitmaps are
 * cached for the lifetime of the enclosing composable.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberGolemSprites(): GolemSprites? {
    var sprites by remember { mutableStateOf<GolemSprites?>(null) }
    LaunchedEffect(Unit) {
        val front = Res.readBytes(FRONT_PATH).decodeToImageBitmap()
        val back = Res.readBytes(BACK_PATH).decodeToImageBitmap()
        val side = Res.readBytes(SIDE_PATH).decodeToImageBitmap()
        sprites = GolemSprites(front = front, back = back, side = side)
    }
    return sprites
}
