package org.jetbrains.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor


/**
 * Painting waves used to compose the canvas back-to-front.
 *
 * Tokens declare which wave they belong to via [GameDrawable.paintWave];
 * the host renderer paints all tokens in [GROUND] first, then everything
 * else, so cells "stepped into" (e.g. the goal outline) sit visually
 * underneath tokens like the golem.
 */
object PaintWave {
    /**
     * Backdrop layer drawn first. Used by tokens that should appear as
     * part of the cell itself rather than as an actor on top of it
     * (e.g. the goal highlight the golem walks into).
     */
    const val GROUND: Int = 0

    /**
     * Default layer for "actor" tokens that sit on top of the ground
     * (e.g. the golem, walls).
     */
    const val DEFAULT: Int = 1
}

sealed interface GameDrawable {
    /**
     * The grid cell this token occupies. The origin `(0, 0)` is the
     * top-left corner of the grid.
     */
    val position: Point

    val x get() = position.x
    val y get() = position.y

    /**
     * Painting wave this token belongs to. Tokens in lower-numbered
     * waves are painted first, so that later waves stack on top of
     * them. Defaults to [PaintWave.DEFAULT]; override (e.g. in
     * [Cheese]) to render as a backdrop.
     */
    val paintWave: Int get() = PaintWave.DEFAULT

    /**
     * Paint this token onto [scope] inside the cell rectangle whose
     * top-left corner is [cellOrigin] and whose width/height are [cellSize].
     */
    fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float)
}

/**
 * Something that occupies a cell on the [GameGrid] and knows how to paint
 * itself onto a Compose [Canvas][androidx.compose.foundation.Canvas].
 *
 * Implementations are intentionally simple, schematic graphics — the goal
 * is gameplay clarity rather than realistic art.
 */
sealed interface GameToken: GameDrawable {
    /**
     * The name of the token.  Used in referencing from the code editor and when hovering in the grid.
     */
    val name: String
}

/**
 * The golem entity, including its current position and the direction it is facing.
 *
 * Rendered from a set of pre-authored pixel-art sprite atlases supplied
 * via [GolemSprites]. Three different views are used depending on
 * [facing]:
 *  - the [front][GolemSprites.front] atlas when facing south (towards
 *    the viewer),
 *  - the [back][GolemSprites.back] atlas when facing north (away from
 *    the viewer),
 *  - the [side][GolemSprites.side] atlas when facing east; the same
 *    atlas is mirrored horizontally when facing west.
 *
 * Each atlas is a horizontal strip of [GolemSprites.FRAME_COUNT] frames
 * that the renderer cycles through to produce a walking animation. The
 * sprite is drawn so its width fills a single grid cell while its
 * height occupies *two* cells — the figure stands inside the cell at
 * [position] and extends one full cell upward.
 */
data class Golem(
    override val position: Point,
    val facing: Direction,
) : GameToken {
    override val name: String get() = "gol"

    /**
     * Paints the golem without sprite assets available.
     *
     * The pixel-art figure can only be rendered when the [GolemSprites]
     * atlases have been loaded; until then there is nothing to draw, so
     * this fallback intentionally paints nothing. Use the [paint]
     * overload that takes a [GolemSprites] (and an optional walk phase)
     * once the assets are ready.
     */
    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        // No-op: the canvas-drawn figure has been replaced with sprite
        // rendering. Without a [GolemSprites] reference there is nothing
        // to paint; the host renderer skips the golem in this case.
    }

    /**
     * Paints the golem from the supplied [sprites], optionally advancing
     * its walking animation by [walkPhase].
     *
     * [walkPhase] is the progress through a single walking cycle, in the
     * range `[0f, 1f]`. A value of `0f` (or any whole number) selects
     * the first frame of the chosen atlas; advancing through the cycle
     * steps through the atlas's [GolemSprites.FRAME_COUNT] frames in
     * order, looping back to the first frame at `1f`.
     *
     * The sprite is drawn so its width matches [cellSize] and its
     * height extends one full cell upward from [cellOrigin] — i.e. the
     * top edge of the rendered figure sits at `cellOrigin.y - cellSize`
     * while its bottom edge sits at `cellOrigin.y + cellSize`. When
     * [facing] is [Direction.WEST] the side-view atlas is mirrored
     * horizontally so the figure appears to face left.
     */
    fun paint(
        scope: DrawScope,
        cellOrigin: Offset,
        cellSize: Float,
        sprites: GolemSprites,
        walkPhase: Float = 0f,
    ) {
        val (atlas, mirrored) = when (facing) {
            Direction.SOUTH -> sprites.front to false
            Direction.NORTH -> sprites.back to false
            Direction.EAST -> sprites.side to false
            Direction.WEST -> sprites.side to true
        }

        // Pick the current animation frame. Wrap [walkPhase] into
        // `[0f, 1f)` first so out-of-range values still produce a valid
        // index, then quantise to one of the [FRAME_COUNT] cells laid
        // out horizontally in the atlas.
        val phase = walkPhase - floor(walkPhase)
        val frameIndex = (phase * GolemSprites.FRAME_COUNT)
            .toInt()
            .coerceIn(0, GolemSprites.FRAME_COUNT - 1)
        val srcOffset = IntOffset(
            x = frameIndex * GolemSprites.FRAME_WIDTH,
            y = 0,
        )
        val srcSize = IntSize(
            width = GolemSprites.FRAME_WIDTH,
            height = GolemSprites.FRAME_HEIGHT,
        )

        // The sprite is twice as tall as it is wide; we anchor its
        // bottom edge to the bottom of the cell so the figure stands on
        // the floor and extends one full cell upward.
        val drawWidth = cellSize
        val drawHeight = cellSize * 2f
        val drawLeft = cellOrigin.x
        val drawTop = cellOrigin.y - cellSize

        if (mirrored) {
            // Flip horizontally about the vertical centreline of the
            // destination rectangle so the side-view atlas faces west
            // instead of east.
            scope.scale(
                scaleX = -1f,
                scaleY = 1f,
                pivot = Offset(drawLeft + drawWidth / 2f, drawTop + drawHeight / 2f),
            ) {
                drawSpriteFrame(atlas, srcOffset, srcSize, drawLeft, drawTop, drawWidth, drawHeight)
            }
        } else {
            scope.drawSpriteFrame(atlas, srcOffset, srcSize, drawLeft, drawTop, drawWidth, drawHeight)
        }
    }

    private fun DrawScope.drawSpriteFrame(
        atlas: ImageBitmap,
        srcOffset: IntOffset,
        srcSize: IntSize,
        drawLeft: Float,
        drawTop: Float,
        drawWidth: Float,
        drawHeight: Float,
    ) {
        // Quantise the destination rectangle to whole pixels: the
        // assets are pixel art, so any sub-pixel offset would smear
        // them under the default bilinear filter even though we ask
        // for nearest-neighbour sampling.
        val destLeft = drawLeft.toInt()
        val destTop = drawTop.toInt()
        val destWidth = (drawLeft + drawWidth).toInt() - destLeft
        val destHeight = (drawTop + drawHeight).toInt() - destTop
        drawImage(
            image = atlas,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset(destLeft, destTop),
            dstSize = IntSize(destWidth, destHeight),
            // Nearest-neighbour sampling preserves the crisp pixel-art
            // edges when the sprite is scaled up to the (much larger)
            // grid cell size.
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * A wall segment occupying a contiguous range of cells along either a row
 * or a column. Walls are immovable obstacles.
 *
 * The wall spans every cell from [position] (inclusive) to [end] (inclusive).
 * Exactly one of the dimensions varies between [position] and [end]; the
 * other is the "static" dimension and must be equal in both endpoints.
 *
 * Rendered as a thick line drawn at the leading edge of the static
 * dimension (the top edge for a horizontal wall, the left edge for a
 * vertical wall) and traced along the full length of the ranged dimension.
 */
data class Wall(
    val start: Point,
    val end: Point,
) : GameDrawable {
    override val position: Point get() = start

    init {
        require(start.x == end.x || start.y == end.y) {
            "Wall must be axis-aligned: $start to $end"
        }
        require(start.x <= end.x && start.y <= end.y) {
            "Wall start must be the lesser endpoint: $start to $end"
        }
    }

    /**
     * Since we're dealing with integers, we must double the scale and subtract 1
     * to find the line between two cells in the grid.  This is needed for checking
     * intersections.
     */
    fun doubleScaledLine(): Line =
        if (start.x == end.x)
            start * 2 - Point(1, 1) to end * 2 - Point(1, -1)
        else
            start * 2 - Point(1, 1) to end * 2 - Point(-1, 1)

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        val thickness = cellSize * THICKNESS_RATIO
        if (start.y == end.y) {
            // Horizontal wall: thick line along the top edge of the row,
            // running from the start cell's left edge to the end cell's
            // right edge.
            val length = (end.x - start.x + 1) * cellSize
            scope.drawRect(
                color = WALL_COLOR,
                topLeft = Offset(cellOrigin.x, cellOrigin.y - thickness / 2f),
                size = Size(length, thickness),
            )
        } else {
            // Vertical wall: thick line along the left edge of the column,
            // running from the start cell's top edge to the end cell's
            // bottom edge.
            val length = (end.y - start.y + 1) * cellSize
            scope.drawRect(
                color = WALL_COLOR,
                topLeft = Offset(cellOrigin.x - thickness / 2f, cellOrigin.y),
                size = Size(thickness, length),
            )
        }
    }

    private companion object {
        val WALL_COLOR = Color(0xFF979797)

        // Thickness of the wall stroke, as a fraction of the cell size.
        const val THICKNESS_RATIO = 0.18f
    }
}

/**
 * The goal cell on the grid. Reaching the cell that contains a [Cheese]
 * completes the current level.
 *
 * Rendered as a bright green outline of the target cell rather than a
 * realistic depiction; we still call it `Cheese` in code (so existing
 * callers and saved maps keep working), but expose it to user scripts
 * under the more neutral name `goal`.
 */
data class Cheese(
    override val position: Point,
) : GameToken {
    override val name: String get() = "goal"

    // Render in the ground wave so the golem appears to step *into* the
    // highlighted cell rather than standing in front of it.
    override val paintWave: Int get() = PaintWave.GROUND

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        paint(scope, cellOrigin, cellSize, alpha = 1f)
    }

    /**
     * Variant of [paint] that draws the goal outline at the given [alpha].
     * Used by the host renderer to flash the goal cell at level start so
     * the player can immediately see the objective.
     */
    fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float, alpha: Float) {
        if (alpha <= 0f) return
        // Inset the rectangle by half the stroke width so the outline sits
        // entirely inside the cell rather than overhanging neighbouring cells.
        val stroke = cellSize * STROKE_RATIO
        val inset = stroke / 2f
        val left = cellOrigin.x + inset
        val top = cellOrigin.y + inset
        val extent = cellSize - 2f * inset
        scope.drawRect(
            color = GOAL_COLOR.copy(alpha = alpha.coerceIn(0f, 1f)),
            topLeft = Offset(left, top),
            size = Size(extent, extent),
            style = Stroke(width = stroke),
        )
    }

    private companion object {
        // Bright green chosen for high contrast against the dark canvas
        // background and the muted wall colour.
        val GOAL_COLOR = Color(0xFF00E676)

        // Outline thickness, as a fraction of the cell size.
        const val STROKE_RATIO = 0.08f
    }
}
