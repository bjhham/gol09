package org.jetbrains.game

/**
 * The size (width and height) of the game grid.
 */
const val GRID_SIZE: Int = 12

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
    WEST;

    /**
     * The unit step on the grid corresponding to moving one cell in this
     * direction. Recall that `y` increases to the south.
     */
    val delta: Position
        get() = when (this) {
            NORTH -> Position(0, -1)
            EAST -> Position(1, 0)
            SOUTH -> Position(0, 1)
            WEST -> Position(-1, 0)
        }
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

    /**
     * Returns whether the cell at [position] is inside the grid bounds.
     */
    fun isInBounds(position: Position): Boolean =
        position.x in 0 until width && position.y in 0 until height

    /**
     * Returns a new [GameGrid] with the golem advanced one cell in the
     * direction it is facing. If that would move it off the grid, the
     * current grid is returned unchanged.
     */
    fun moveGolem(): GameGrid {
        val current = golem
        val next = Position(
            x = current.position.x + current.facing.delta.x,
            y = current.position.y + current.facing.delta.y,
        )
        if (!isInBounds(next)) return this
        val moved = current.copy(position = next)
        return copy(tokens = tokens.map { if (it === current) moved else it })
    }

    /**
     * Returns a new [GameGrid] with the golem rotated 90 degrees clockwise
     * (i.e. turned to its right). The golem's position is unchanged.
     */
    fun turnGolemRight(): GameGrid = rotateGolem(1)

    /**
     * Returns a new [GameGrid] with the golem rotated 90 degrees
     * counter-clockwise (i.e. turned to its left). The golem's position is
     * unchanged.
     */
    fun turnGolemLeft(): GameGrid = rotateGolem(-1)

    private fun rotateGolem(steps: Int): GameGrid {
        val current = golem
        val directions = Direction.entries
        val newFacing = directions[(current.facing.ordinal + steps).mod(directions.size)]
        val turned = current.copy(facing = newFacing)
        return copy(tokens = tokens.map { if (it === current) turned else it })
    }
}
