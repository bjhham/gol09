package org.jetbrains.game

/**
 * The size (width and height) of the game grid.
 */
const val GRID_SIZE: Int = 12

/**
 * A position on the game grid. The origin (0, 0) is the top-left corner;
 * x increases to the east and y increases to the south.
 */
data class Point(val x: Int, val y: Int) {
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun contains(other: Point) = x == other.x && y == other.y
    operator fun times(factor: Int) = Point(x * factor, y * factor)
    operator fun div(factor: Int) = Point(x / factor, y / factor)
    operator fun unaryMinus() = Point(-x, -y)

    override fun toString(): String = "($x, $y)"
}

/**
 * The four cardinal directions an entity can face on the grid.
 */
enum class Direction(val vector: Point) {
    NORTH(Point(0, -1)),
    EAST(Point(1, 0)),
    SOUTH(Point(0, 1)),
    WEST(Point(-1, 0));
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
    val walls: List<Wall> = emptyList(),
) {
    val elements: List<GameDrawable> get() = tokens + walls

    val golem: Golem
        get() = tokens.filterIsInstance<Golem>().single()

    /**
     * Returns whether the cell at [position] is inside the grid bounds.
     */
    fun isInBounds(position: Point): Boolean =
        position.x in 0 until width && position.y in 0 until height

    /**
     * Returns a new [GameGrid] with the golem advanced one cell in the
     * direction it is facing. If that would move it off the grid, or onto
     * a cell occupied by a [Wall], the current grid is returned unchanged.
     */
    fun moveGolem(): GameGrid {
        val current = golem
        val next = current.position + current.facing.vector
        if (!isInBounds(next)) return this
        if (isWall(next)) return this
        val moved = current.copy(position = next)
        return copy(tokens = tokens.map {
            if (it === current) moved else it
        })
    }

    /**
     * Returns whether the golem can move one cell in the direction it is facing.
     */
    fun canMoveGolem(): Boolean =
        (golem.position + golem.facing.vector).let {
            isInBounds(it) && !isWall(it)
        }

    /**
     * Returns whether [position] is occupied by any [Wall] segment.
     */
    private fun isWall(position: Point): Boolean =
        walls.any { wall ->
            linesIntersect(
                golem.position * 2 to position * 2,
                wall.doubleScaledLine()
            )
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
