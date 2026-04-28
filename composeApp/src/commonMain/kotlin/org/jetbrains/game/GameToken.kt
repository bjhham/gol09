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
 * Rendered as a grey square body with two darker rectangles for legs.
 */
data class Golem(
    override val position: Position,
    val facing: Direction,
) : GameToken {

    override fun paint(scope: DrawScope, cellOrigin: Offset, cellSize: Float) {
        // Body: a grey square that fills most of the cell, leaving a small
        // margin so adjacent tokens are visually distinct.
        val bodyMargin = cellSize * BODY_MARGIN_RATIO
        val bodyTop = cellOrigin.y + bodyMargin
        val bodyLeft = cellOrigin.x + bodyMargin
        val bodyExtent = cellSize - 2f * bodyMargin
        val bodyHeight = bodyExtent * BODY_HEIGHT_RATIO

        scope.drawRect(
            color = BODY_COLOR,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyExtent, bodyHeight),
        )

        // Legs: two darker rectangles below the body.
        val legsTop = bodyTop + bodyHeight
        val legsHeight = bodyExtent - bodyHeight
        val legWidth = bodyExtent * LEG_WIDTH_RATIO
        val legGap = bodyExtent - 2f * legWidth
        val leftLegLeft = bodyLeft + (bodyExtent - 2f * legWidth - legGap) / 2f
        val rightLegLeft = leftLegLeft + legWidth + legGap

        scope.drawRect(
            color = LEG_COLOR,
            topLeft = Offset(leftLegLeft, legsTop),
            size = Size(legWidth, legsHeight),
        )
        scope.drawRect(
            color = LEG_COLOR,
            topLeft = Offset(rightLegLeft, legsTop),
            size = Size(legWidth, legsHeight),
        )
    }

    private companion object {
        val BODY_COLOR = Color(0xFF9E9E9E)
        val LEG_COLOR = Color(0xFF616161)

        const val BODY_MARGIN_RATIO = 0.1f
        const val BODY_HEIGHT_RATIO = 0.65f
        const val LEG_WIDTH_RATIO = 0.30f
    }
}
