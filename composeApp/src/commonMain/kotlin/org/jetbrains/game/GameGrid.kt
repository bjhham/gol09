package org.jetbrains.game

/**
 * The size (width and height) of the game grid.
 */
const val GRID_SIZE: Int = 24

/**
 * A position on the game grid. The origin (0, 0) is the top-left corner;
 * x increases to the east and y increases to the south.
 */
data class Position(val x: Int, val y: Int)

/**
 * The four cardinal directions an entity can face on the grid.
 */
enum class Direction {
    NORTH,
    EAST,
    SOUTH,
    WEST,
}

/**
 * The parsed model of a single stage / level.
 *
 * The grid is fixed at [GRID_SIZE] x [GRID_SIZE] cells. All renderable
 * entities live in [tokens]; [golem] is provided as a convenience accessor
 * to the (single) [Golem] in the grid.
 */
data class GameGrid(
    val width: Int = GRID_SIZE,
    val height: Int = GRID_SIZE,
    val tokens: List<GameToken>,
) {
    val golem: Golem
        get() = tokens.filterIsInstance<Golem>().single()
}
