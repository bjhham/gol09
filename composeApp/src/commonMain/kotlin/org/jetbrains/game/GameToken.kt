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
     * top-left corner is [cellOrigin]. The cell is [cellWidth] pixels
     * wide and [cellHeight] pixels tall — the two are not assumed to
     * be equal, so that the host can render a tilted, isometric-ish
     * grid where cells are visually shorter than they are wide while
     * tokens still extrude up to their natural proportions.
     *
     * Implementations are free to draw outside the bottom of the cell
     * rectangle (e.g. to extrude downward in a 3D effect) and above
     * its top edge (e.g. for tall sprites). The host renderer
     * reserves a top margin so the latter is not clipped.
     */
    fun paint(scope: DrawScope, cellOrigin: Offset, cellWidth: Float, cellHeight: Float)
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
 * height extends one full cell width upward from the cell's bottom
 * edge — the figure's feet rest on the cell's bottom while its head
 * reaches up into the top margin reserved by the host renderer.
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
    override fun paint(scope: DrawScope, cellOrigin: Offset, cellWidth: Float, cellHeight: Float) {
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
     * The sprite is drawn so its width matches [cellWidth] and its
     * height equals twice the sprite's natural aspect ratio against
     * [cellWidth] — i.e. the figure's bottom edge sits at
     * `cellOrigin.y + cellHeight` and its top edge sits one cell width
     * above the cell's bottom. With the playfield's flattened
     * [cellHeight], this lets the head extend up into the row above
     * (or, for the top row, into the host's reserved top margin).
     * When [facing] is [Direction.WEST] the side-view atlas is
     * mirrored horizontally so the figure appears to face left.
     */
    fun paint(
        scope: DrawScope,
        cellOrigin: Offset,
        cellWidth: Float,
        cellHeight: Float,
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
        // the floor. The cell's height is shorter than its width on
        // the tilted grid, so the sprite naturally extends *above*
        // the cell's top edge into the row above.
        val drawWidth = cellWidth
        val drawHeight = cellWidth * 2f
        val drawLeft = cellOrigin.x
        val drawTop = cellOrigin.y + cellHeight - drawHeight

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
 * Rendered as an extruded 3D block sitting on the leading edge of the
 * static dimension (the top edge for a horizontal wall, the left edge
 * for a vertical wall). The block has a flat *top face* offset upward
 * by [HEIGHT_RATIO] of the cell width, plus a *front face* connecting
 * the top of the block back down to the floor — so a wall in front of
 * (south of) the golem occludes the lower part of the figure behind
 * it.
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

    /**
     * Paints the entire wall in one call, ignoring the per-row
     * stacking that walls participate in when the host expands them
     * via [cellDrawables]. The host renderer prefers [cellDrawables]
     * so that, e.g., a vertical wall passing through a horizontal
     * wall is rendered with correct front/back occlusion at the
     * intersection. This fallback simply paints every cell in
     * sequence so the wall is still visible if a caller skips the
     * expansion.
     */
    override fun paint(scope: DrawScope, cellOrigin: Offset, cellWidth: Float, cellHeight: Float) {
        for (drawable in cellDrawables()) {
            val originX = cellOrigin.x + (drawable.position.x - start.x) * cellWidth
            val originY = cellOrigin.y + (drawable.position.y - start.y) * cellHeight
            drawable.paint(scope, Offset(originX, originY), cellWidth, cellHeight)
        }
    }

    /**
     * Decomposes this wall into one [GameDrawable] per occupied cell.
     *
     * Splitting the wall lets the host renderer interleave the wall's
     * cells with other tokens row-by-row so that, in an isometric-ish
     * top-down view, the parts of the wall closer to the viewer
     * (larger `y`) paint after — and therefore on top of — anything
     * further away. This is what makes a vertical (N–S) wall pass
     * *behind* a horizontal (E–W) wall on the north side of the
     * intersection and *in front* of it on the south side, instead of
     * being uniformly painted over by whichever wall happens to be
     * iterated last.
     */
    fun cellDrawables(): List<GameDrawable> {
        val isHorizontal = start.y == end.y
        return if (isHorizontal) {
            (start.x..end.x).map { x -> HorizontalCell(Point(x, start.y)) }
        } else {
            (start.y..end.y).map { y ->
                VerticalCell(Point(start.x, y), isSouthEnd = y == end.y)
            }
        }
    }

    /**
     * One cell's worth of a horizontal (E–W) wall.
     *
     * Each cell paints a top-face strip and a front-face slab over
     * the cell's full width; adjacent cells abut to form the
     * appearance of a single continuous wall.
     */
    private class HorizontalCell(override val position: Point) : GameDrawable {
        override fun paint(
            scope: DrawScope,
            cellOrigin: Offset,
            cellWidth: Float,
            cellHeight: Float,
        ) {
            val thickness = cellWidth * THICKNESS_RATIO
            val wallHeight = cellWidth * HEIGHT_RATIO
            // Front face: tall slab hanging from the top of the
            // extruded block down to just past the floor edge so it
            // occludes anything immediately to the north.
            scope.drawRect(
                color = WALL_FRONT_COLOR,
                topLeft = Offset(
                    x = cellOrigin.x,
                    y = cellOrigin.y - wallHeight + thickness / 2f,
                ),
                size = Size(cellWidth, wallHeight),
            )
            // Top face: the lit horizontal strip at the very top of
            // the block.
            scope.drawRect(
                color = WALL_TOP_COLOR,
                topLeft = Offset(
                    x = cellOrigin.x,
                    y = cellOrigin.y - wallHeight - thickness / 2f,
                ),
                size = Size(cellWidth, thickness),
            )
        }
    }

    /**
     * One cell's worth of a vertical (N–S) wall.
     *
     * Each cell paints a thin top-face strip running its own
     * cell-height vertical extent; only the southernmost cell
     * additionally paints a square cap showing the wall's south face,
     * which is the only side of a vertical wall that's visible in our
     * tilted-back oblique projection.
     */
    private class VerticalCell(
        override val position: Point,
        private val isSouthEnd: Boolean,
    ) : GameDrawable {
        override fun paint(
            scope: DrawScope,
            cellOrigin: Offset,
            cellWidth: Float,
            cellHeight: Float,
        ) {
            val thickness = cellWidth * THICKNESS_RATIO
            val wallHeight = cellWidth * HEIGHT_RATIO
            // Top face: the long thin strip running this cell's full
            // N–S extent, offset upward by [wallHeight] so the wall
            // visibly extrudes above the floor.
            scope.drawRect(
                color = WALL_TOP_COLOR,
                topLeft = Offset(
                    x = cellOrigin.x - thickness / 2f,
                    y = cellOrigin.y - wallHeight,
                ),
                size = Size(thickness, cellHeight),
            )
            if (isSouthEnd) {
                // South-end cap: closes off the slab with its only
                // visible vertical face, dropping from the top of
                // the slab down to the floor at the southern edge of
                // the wall.
                scope.drawRect(
                    color = WALL_FRONT_COLOR,
                    topLeft = Offset(
                        x = cellOrigin.x - thickness / 2f,
                        y = cellOrigin.y + cellHeight - wallHeight,
                    ),
                    size = Size(thickness, wallHeight),
                )
            }
        }
    }

    private companion object {
        // Lit "top" of the block — slightly brighter than the front
        // face so the extrusion reads as having direction (the
        // imagined light comes from above).
        val WALL_TOP_COLOR = Color(0xFFB5B5B5)

        // Shaded "front" face of the block — the original wall grey;
        // dimmer than the top so the cube reads as 3D.
        val WALL_FRONT_COLOR = Color(0xFF7A7A7A)

        // Thickness of the wall block's footprint, as a fraction of
        // the cell width.
        const val THICKNESS_RATIO = 0.18f

        // Visual height of the extruded block above the floor, as a
        // fraction of the cell width. Tall enough to clearly hide
        // the golem's legs when the figure stands directly behind a
        // horizontal wall.
        const val HEIGHT_RATIO = 0.45f
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

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellWidth: Float, cellHeight: Float) {
        paint(scope, cellOrigin, cellWidth, cellHeight, alpha = 1f)
    }

    /**
     * Variant of [paint] that draws the goal outline at the given [alpha].
     * Used by the host renderer to flash the goal cell at level start so
     * the player can immediately see the objective.
     */
    fun paint(
        scope: DrawScope,
        cellOrigin: Offset,
        cellWidth: Float,
        cellHeight: Float,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        // Inset the rectangle by half the stroke width so the outline sits
        // entirely inside the cell rather than overhanging neighbouring cells.
        // The stroke is sized against the cell width — keeping it
        // proportional to the (taller) horizontal axis prevents the
        // outline from looking spindly on the flattened cells.
        val stroke = cellWidth * STROKE_RATIO
        val inset = stroke / 2f
        val left = cellOrigin.x + inset
        val top = cellOrigin.y + inset
        val width = cellWidth - 2f * inset
        val height = cellHeight - 2f * inset
        scope.drawRect(
            color = GOAL_COLOR.copy(alpha = alpha.coerceIn(0f, 1f)),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = stroke),
        )
    }

    private companion object {
        // Bright green chosen for high contrast against the dark canvas
        // background and the muted wall colour.
        val GOAL_COLOR = Color(0xFF00E676)

        // Outline thickness, as a fraction of the cell width.
        const val STROKE_RATIO = 0.08f
    }
}
