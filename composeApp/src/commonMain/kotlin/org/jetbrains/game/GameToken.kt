package org.jetbrains.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin


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
 * Rendered as a schematic figure within its grid cell. Three different views are
 * used depending on [facing]:
 *  - [paintFront] when facing south (towards the viewer); shows a face with eyes,
 *  - [paintBack] when facing north (away from the viewer); same silhouette, no eyes,
 *  - [paintSide] when facing east or west; a profile silhouette, mirrored when
 *    facing west.
 */
data class Golem(
    override val position: Point,
    val facing: Direction,
) : GameToken {
    override val name: String get() = "gol"

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        paint(scope, cellOrigin, cellSize, walkPhase = 0f)
    }

    /**
     * Paints the golem with an optional walking animation applied.
     *
     * [walkPhase] is the progress through a single walking cycle, in the
     * range `[0f, 1f]`. A value of `0f` (or any whole number) renders the
     * golem in its idle pose, identical to the base [paint] implementation.
     * As [walkPhase] advances through a cycle the figure bobs up and down
     * once and its legs alternate in a simple walking motion, providing
     * visual feedback while the simulation is moving the golem between
     * cells.
     */
    fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float, walkPhase: Float) {
        // Reserve a small margin around the figure so adjacent tokens stay visually distinct.
        val margin = cellSize * MARGIN_RATIO
        val extent = cellSize - 2f * margin
        val left = cellOrigin.x + margin
        // Apply a vertical bob: the figure rises smoothly to a peak at the
        // middle of the cycle and returns to its resting height by the end.
        // A negative offset shifts the figure up the canvas because `y`
        // increases downwards.
        val bob = -extent * BOB_AMPLITUDE_RATIO * sin(((walkPhase % 1f) * PI).toFloat())
        val top = cellOrigin.y + margin + bob

        when (facing) {
            Direction.SOUTH -> paintFront(scope, left, top, extent, walkPhase)
            Direction.NORTH -> paintBack(scope, left, top, extent, walkPhase)
            Direction.EAST -> paintSide(scope, left, top, extent, mirrored = false, walkPhase = walkPhase)
            Direction.WEST -> paintSide(scope, left, top, extent, mirrored = true, walkPhase = walkPhase)
        }
    }

    /**
     * Paints the front view: head with two small red square eyes, wide body
     * with arms on both sides, and two legs.
     */
    private fun paintFront(scope: DrawScope, left: Float, top: Float, extent: Float, walkPhase: Float) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawSideArms(scope, layout)
        drawLegs(scope, layout, separated = true, walkPhase = walkPhase)

        // Eyes: two small red squares on the head, distinguishing front from back.
        val eyeSize = layout.headWidth * EYE_SIZE_RATIO
        val eyeY = layout.headTop + layout.headHeight * EYE_VERTICAL_RATIO
        val eyeInset = layout.headWidth * EYE_INSET_RATIO
        val leftEyeX = layout.headLeft + eyeInset
        val rightEyeX = layout.headLeft + layout.headWidth - eyeInset - eyeSize
        scope.drawRect(
            color = EYE_COLOR,
            topLeft = Offset(leftEyeX, eyeY),
            size = Size(eyeSize, eyeSize),
        )
        scope.drawRect(
            color = EYE_COLOR,
            topLeft = Offset(rightEyeX, eyeY),
            size = Size(eyeSize, eyeSize),
        )
    }

    /**
     * Paints the back view: identical silhouette to the front, but with no
     * eyes so that orientation is visually unambiguous.
     */
    private fun paintBack(scope: DrawScope, left: Float, top: Float, extent: Float, walkPhase: Float) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawSideArms(scope, layout)
        drawLegs(scope, layout, separated = true, walkPhase = walkPhase)
    }

    /**
     * Paints the side (profile) view. By convention this draws the figure
     * facing right (east); pass [mirrored] = `true` to flip it horizontally
     * for the west-facing case.
     *
     * Differences from the front/back view:
     *  - a single arm is shown, projecting forward in the facing direction,
     *  - the legs overlap (one in front of the other), so they're drawn
     *    centred rather than separated.
     */
    private fun paintSide(
        scope: DrawScope,
        left: Float,
        top: Float,
        extent: Float,
        mirrored: Boolean,
        walkPhase: Float,
    ) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawLegs(scope, layout, separated = false, walkPhase = walkPhase)

        // A single forward-projecting arm. In un-mirrored (east-facing) form it
        // points to the right of the body; mirroring flips it to the left.
        val armWidth = (extent - layout.bodyWidth) / 2f
        val armHeight = layout.bodyHeight * ARM_HEIGHT_RATIO
        val armTop = layout.bodyTop + (layout.bodyHeight - armHeight) / 2f
        val armLeft = if (mirrored) {
            left + armWidth
        } else {
            left + extent - 2f * armWidth
        }
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(armLeft, armTop),
            size = Size(armWidth, armHeight),
        )
    }

    /**
     * Computes the shared head/body layout used by every view.
     */
    private fun bodyLayout(left: Float, top: Float, extent: Float): BodyLayout {
        val headHeight = extent * HEAD_HEIGHT_RATIO
        val bodyHeight = extent * BODY_HEIGHT_RATIO
        val legsHeight = extent - headHeight - bodyHeight

        val headWidth = extent * HEAD_WIDTH_RATIO
        val headLeft = left + (extent - headWidth) / 2f
        val headTop = top

        val bodyWidth = extent * BODY_WIDTH_RATIO
        val bodyLeft = left + (extent - bodyWidth) / 2f
        val bodyTop = headTop + headHeight

        return BodyLayout(
            headLeft = headLeft,
            headTop = headTop,
            headWidth = headWidth,
            headHeight = headHeight,
            bodyLeft = bodyLeft,
            bodyTop = bodyTop,
            bodyWidth = bodyWidth,
            bodyHeight = bodyHeight,
            legsTop = bodyTop + bodyHeight,
            legsHeight = legsHeight,
            figureLeft = left,
            figureExtent = extent,
        )
    }

    private fun drawHead(scope: DrawScope, layout: BodyLayout) {
        scope.drawRect(
            color = BODY_COLOR,
            topLeft = Offset(layout.headLeft, layout.headTop),
            size = Size(layout.headWidth, layout.headHeight),
        )
    }

    private fun drawBody(scope: DrawScope, layout: BodyLayout) {
        scope.drawRect(
            color = BODY_COLOR,
            topLeft = Offset(layout.bodyLeft, layout.bodyTop),
            size = Size(layout.bodyWidth, layout.bodyHeight),
        )
    }

    /**
     * Draws two arms, one on each side of the body, used by the front/back views.
     */
    private fun drawSideArms(scope: DrawScope, layout: BodyLayout) {
        val armWidth = (layout.figureExtent - layout.bodyWidth) / 2f
        val armHeight = layout.bodyHeight * ARM_HEIGHT_RATIO
        val armTop = layout.bodyTop + (layout.bodyHeight - armHeight) / 2f
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(layout.figureLeft, armTop),
            size = Size(armWidth, armHeight),
        )
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(layout.figureLeft + layout.figureExtent - armWidth, armTop),
            size = Size(armWidth, armHeight),
        )
    }

    /**
     * Draws the legs underneath the body. In [separated] mode the legs are drawn
     * side-by-side (front/back view); otherwise they are stacked at the centre
     * (side view, where one leg is hidden behind the other).
     *
     * [walkPhase] drives a simple walking animation: when non-zero, the legs
     * shorten and lengthen alternately to suggest stepping. In the side
     * (un-separated) view the single visible leg also swings forward and
     * back along the figure's facing direction.
     */
    private fun drawLegs(scope: DrawScope, layout: BodyLayout, separated: Boolean, walkPhase: Float) {
        val legWidth = layout.bodyWidth * LEG_WIDTH_RATIO
        // One full sine cycle per cell: positive lobe lifts the left leg,
        // negative lobe lifts the right leg.
        val swing = sin(((walkPhase % 1f) * 2f * PI).toFloat())
        val leftLift = layout.legsHeight * LEG_LIFT_RATIO * maxOf(swing, 0f)
        val rightLift = layout.legsHeight * LEG_LIFT_RATIO * maxOf(-swing, 0f)
        if (separated) {
            val legGap = layout.bodyWidth - 2f * legWidth
            val leftLegLeft = layout.bodyLeft + (layout.bodyWidth - 2f * legWidth - legGap) / 2f
            val rightLegLeft = leftLegLeft + legWidth + legGap
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(leftLegLeft, layout.legsTop),
                size = Size(legWidth, layout.legsHeight - leftLift),
            )
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(rightLegLeft, layout.legsTop),
                size = Size(legWidth, layout.legsHeight - rightLift),
            )
        } else {
            // Side view: two legs are drawn, one in front of the other.
            // While idle (`swing == 0`) they overlap exactly so the figure
            // still reads as a profile silhouette. While walking, the two
            // legs swing in opposite phase: one forward and lifted while
            // the other is back and planted, then they trade roles. The
            // back leg is drawn first so the front leg overlaps it, which
            // preserves the side-on impression of one leg passing in front
            // of the other.
            val centerX = layout.bodyLeft + (layout.bodyWidth - legWidth) / 2f
            val swingShift = legWidth * LEG_SWING_RATIO * swing
            // The "front" leg in the figure's facing direction swings
            // synchronously with the head's bob; the "back" leg swings in
            // anti-phase. The lift uses the same lobed pattern as the
            // separated case so each leg only lifts on its own swing.
            val frontLift = layout.legsHeight * LEG_LIFT_RATIO * maxOf(swing, 0f)
            val backLift = layout.legsHeight * LEG_LIFT_RATIO * maxOf(-swing, 0f)
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(centerX - swingShift, layout.legsTop),
                size = Size(legWidth, layout.legsHeight - backLift),
            )
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(centerX + swingShift, layout.legsTop),
                size = Size(legWidth, layout.legsHeight - frontLift),
            )
        }
    }

    /**
     * Pre-computed geometry shared by the head, body, arms, and legs renderers.
     */
    private data class BodyLayout(
        val headLeft: Float,
        val headTop: Float,
        val headWidth: Float,
        val headHeight: Float,
        val bodyLeft: Float,
        val bodyTop: Float,
        val bodyWidth: Float,
        val bodyHeight: Float,
        val legsTop: Float,
        val legsHeight: Float,
        val figureLeft: Float,
        val figureExtent: Float,
    )

    private companion object {
        val BODY_COLOR = Color(0xFF9E9E9E)
        val LIMB_COLOR = Color(0xFF616161)
        val EYE_COLOR = Color(0xFFD32F2F)

        // Margin around the entire figure, as a fraction of the cell size.
        const val MARGIN_RATIO = 0.1f

        // Vertical proportions (must sum to <= 1.0; remainder goes to legs).
        const val HEAD_HEIGHT_RATIO = 0.27f
        const val BODY_HEIGHT_RATIO = 0.44f

        // Horizontal proportions of head/body relative to the figure's extent.
        const val HEAD_WIDTH_RATIO = 0.39f
        const val BODY_WIDTH_RATIO = 0.62f

        // Arms: how tall they are relative to the body, and legs: how wide they are
        // relative to the body.
        const val ARM_HEIGHT_RATIO = 0.85f
        const val LEG_WIDTH_RATIO = 0.30f

        // Eye sizing/position, as fractions of the head's width/height.
        const val EYE_SIZE_RATIO = 0.30f
        const val EYE_INSET_RATIO = 0.10f
        const val EYE_VERTICAL_RATIO = 0.40f

        // How high the figure bobs at the peak of a walking cycle, as a
        // fraction of the figure's extent.
        const val BOB_AMPLITUDE_RATIO = 0.06f

        // How much each leg shortens at the peak of its lift, as a fraction
        // of the legs' resting height.
        const val LEG_LIFT_RATIO = 0.45f

        // How far the side-view leg swings forward/back from centre, as a
        // fraction of the leg's width.
        const val LEG_SWING_RATIO = 0.9f
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
