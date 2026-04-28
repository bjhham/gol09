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
 * Rendered as a schematic figure within its grid cell:
 *  - a small grey square head on top,
 *  - a wider grey square body in the middle, flanked by two darker rectangular arms,
 *  - two darker rectangular legs underneath.
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

        // Vertical layout: head, then body, then legs.
        val headHeight = extent * HEAD_HEIGHT_RATIO
        val bodyHeight = extent * BODY_HEIGHT_RATIO
        val legsHeight = extent - headHeight - bodyHeight

        // Head: a small square centred horizontally above the body.
        val headWidth = extent * HEAD_WIDTH_RATIO
        val headLeft = left + (extent - headWidth) / 2f
        val headTop = top
        scope.drawRect(
            color = BODY_COLOR,
            topLeft = Offset(headLeft, headTop),
            size = Size(headWidth, headHeight),
        )

        // Body: a wider grey block beneath the head.
        val bodyWidth = extent * BODY_WIDTH_RATIO
        val bodyLeft = left + (extent - bodyWidth) / 2f
        val bodyTop = headTop + headHeight
        scope.drawRect(
            color = BODY_COLOR,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
        )

        // Arms: two darker rectangles flanking the body on either side.
        val armWidth = (extent - bodyWidth) / 2f
        val armHeight = bodyHeight * ARM_HEIGHT_RATIO
        val armTop = bodyTop + (bodyHeight - armHeight) / 2f
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(left, armTop),
            size = Size(armWidth, armHeight),
        )
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(left + extent - armWidth, armTop),
            size = Size(armWidth, armHeight),
        )

        // Legs: two darker rectangles below the body.
        val legsTop = bodyTop + bodyHeight
        val legWidth = bodyWidth * LEG_WIDTH_RATIO
        val legGap = bodyWidth - 2f * legWidth
        val leftLegLeft = bodyLeft + (bodyWidth - 2f * legWidth - legGap) / 2f
        val rightLegLeft = leftLegLeft + legWidth + legGap
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(leftLegLeft, legsTop),
            size = Size(legWidth, legsHeight),
        )
        scope.drawRect(
            color = LIMB_COLOR,
            topLeft = Offset(rightLegLeft, legsTop),
            size = Size(legWidth, legsHeight),
        )
    }

    private companion object {
        val BODY_COLOR = Color(0xFF9E9E9E)
        val LIMB_COLOR = Color(0xFF616161)

        // Margin around the entire figure, as a fraction of the cell size.
        const val MARGIN_RATIO = 0.1f

        // Vertical proportions (must sum to <= 1.0; remainder goes to legs).
        const val HEAD_HEIGHT_RATIO = 0.22f
        const val BODY_HEIGHT_RATIO = 0.45f

        // Horizontal proportions of head/body relative to the figure's extent.
        const val HEAD_WIDTH_RATIO = 0.45f
        const val BODY_WIDTH_RATIO = 0.70f

        // Arms: how tall they are relative to the body, and legs: how wide they are
        // relative to the body.
        const val ARM_HEIGHT_RATIO = 0.70f
        const val LEG_WIDTH_RATIO = 0.30f
    }
}
