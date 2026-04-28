package org.jetbrains.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Something that occupies a cell on the [GameGrid] and knows how to paint
 * itself onto a Compose [Canvas][androidx.compose.foundation.Canvas].
 *
 * Implementations are intentionally simple, schematic graphics — the goal
 * is gameplay clarity rather than realistic art.
 */
sealed interface GameToken {
    /**
     * The grid cell this token occupies. The origin `(0, 0)` is the
     * top-left corner of the grid.
     */
    val position: Position

    /**
     * Paint this token onto [scope] inside the cell rectangle whose
     * top-left corner is [cellOrigin] and whose width/height are [cellSize].
     */
    fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float)
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
    override val position: Position,
    val facing: Direction,
) : GameToken {

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        // Reserve a small margin around the figure so adjacent tokens stay visually distinct.
        val margin = cellSize * MARGIN_RATIO
        val extent = cellSize - 2f * margin
        val left = cellOrigin.x + margin
        val top = cellOrigin.y + margin

        when (facing) {
            Direction.SOUTH -> paintFront(scope, left, top, extent)
            Direction.NORTH -> paintBack(scope, left, top, extent)
            Direction.EAST -> paintSide(scope, left, top, extent, mirrored = false)
            Direction.WEST -> paintSide(scope, left, top, extent, mirrored = true)
        }
    }

    /**
     * Paints the front view: head with two small red square eyes, wide body
     * with arms on both sides, and two legs.
     */
    private fun paintFront(scope: DrawScope, left: Float, top: Float, extent: Float) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawSideArms(scope, layout)
        drawLegs(scope, layout, separated = true)

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
    private fun paintBack(scope: DrawScope, left: Float, top: Float, extent: Float) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawSideArms(scope, layout)
        drawLegs(scope, layout, separated = true)
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
    ) {
        val layout = bodyLayout(left, top, extent)
        drawHead(scope, layout)
        drawBody(scope, layout)
        drawLegs(scope, layout, separated = false)

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
     */
    private fun drawLegs(scope: DrawScope, layout: BodyLayout, separated: Boolean) {
        val legWidth = layout.bodyWidth * LEG_WIDTH_RATIO
        if (separated) {
            val legGap = layout.bodyWidth - 2f * legWidth
            val leftLegLeft = layout.bodyLeft + (layout.bodyWidth - 2f * legWidth - legGap) / 2f
            val rightLegLeft = leftLegLeft + legWidth + legGap
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(leftLegLeft, layout.legsTop),
                size = Size(legWidth, layout.legsHeight),
            )
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(rightLegLeft, layout.legsTop),
                size = Size(legWidth, layout.legsHeight),
            )
        } else {
            val legLeft = layout.bodyLeft + (layout.bodyWidth - legWidth) / 2f
            scope.drawRect(
                color = LIMB_COLOR,
                topLeft = Offset(legLeft, layout.legsTop),
                size = Size(legWidth, layout.legsHeight),
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
        const val ARM_HEIGHT_RATIO = 0.70f
        const val LEG_WIDTH_RATIO = 0.30f

        // Eye sizing/position, as fractions of the head's width/height.
        const val EYE_SIZE_RATIO = 0.30f
        const val EYE_INSET_RATIO = 0.10f
        const val EYE_VERTICAL_RATIO = 0.40f
    }
}
