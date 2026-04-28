package org.jetbrains.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GameGridTest {

    @Test
    fun moveGolem_advances_one_cell_south() {
        val grid = GameGrid(
            tokens = listOf(Golem(Position(0, 0), Direction.SOUTH)),
        )

        val moved = grid.moveGolem()

        assertEquals(Position(0, 1), moved.golem.position)
        assertEquals(Direction.SOUTH, moved.golem.facing)
    }

    @Test
    fun moveGolem_advances_one_cell_for_each_direction() {
        val cases = listOf(
            Direction.NORTH to Position(5, 4),
            Direction.EAST to Position(6, 5),
            Direction.SOUTH to Position(5, 6),
            Direction.WEST to Position(4, 5),
        )
        for ((direction, expected) in cases) {
            val grid = GameGrid(
                tokens = listOf(Golem(Position(5, 5), direction)),
            )
            assertEquals(expected, grid.moveGolem().golem.position, "for $direction")
        }
    }

    @Test
    fun moveGolem_at_south_edge_does_nothing() {
        val grid = GameGrid(
            tokens = listOf(Golem(Position(0, GRID_SIZE - 1), Direction.SOUTH)),
        )

        val moved = grid.moveGolem()

        assertSame(grid, moved)
    }

    @Test
    fun moveGolem_at_each_edge_does_nothing() {
        val edges = listOf(
            Position(0, 0) to Direction.NORTH,
            Position(0, 0) to Direction.WEST,
            Position(GRID_SIZE - 1, 0) to Direction.EAST,
            Position(GRID_SIZE - 1, 0) to Direction.NORTH,
            Position(0, GRID_SIZE - 1) to Direction.SOUTH,
            Position(GRID_SIZE - 1, GRID_SIZE - 1) to Direction.EAST,
        )
        for ((position, direction) in edges) {
            val grid = GameGrid(
                tokens = listOf(Golem(position, direction)),
            )
            assertSame(grid, grid.moveGolem(), "for $position facing $direction")
        }
    }
}
