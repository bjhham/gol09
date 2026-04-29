package org.jetbrains.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GameGridTest {

    @Test
    fun moveGolem_advances_one_cell_south() {
        val grid = GameGrid(
            tokens = listOf(Golem(Point(0, 0), Direction.SOUTH)),
        )

        val moved = grid.moveGolem()

        assertEquals(Point(0, 1), moved.golem.position)
        assertEquals(Direction.SOUTH, moved.golem.facing)
    }

    @Test
    fun moveGolem_advances_one_cell_for_each_direction() {
        val cases = listOf(
            Direction.NORTH to Point(5, 4),
            Direction.EAST to Point(6, 5),
            Direction.SOUTH to Point(5, 6),
            Direction.WEST to Point(4, 5),
        )
        for ((direction, expected) in cases) {
            val grid = GameGrid(
                tokens = listOf(Golem(Point(5, 5), direction)),
            )
            assertEquals(expected, grid.moveGolem().golem.position, "for $direction")
        }
    }

    @Test
    fun moveGolem_at_south_edge_does_nothing() {
        val grid = GameGrid(
            tokens = listOf(Golem(Point(0, GRID_SIZE - 1), Direction.SOUTH)),
        )

        val moved = grid.moveGolem()

        assertSame(grid, moved)
    }

    @Test
    fun moveGolem_at_each_edge_does_nothing() {
        val edges = listOf(
            Point(0, 0) to Direction.NORTH,
            Point(0, 0) to Direction.WEST,
            Point(GRID_SIZE - 1, 0) to Direction.EAST,
            Point(GRID_SIZE - 1, 0) to Direction.NORTH,
            Point(0, GRID_SIZE - 1) to Direction.SOUTH,
            Point(GRID_SIZE - 1, GRID_SIZE - 1) to Direction.EAST,
        )
        for ((position, direction) in edges) {
            val grid = GameGrid(
                tokens = listOf(Golem(position, direction)),
            )
            assertSame(grid, grid.moveGolem(), "for $position facing $direction")
        }
    }

    @Test
    fun moveGolem_blocked_by_wall_in_front() {
        val grid = GameGrid(
            tokens = listOf(
                Golem(Point(5, 5), Direction.SOUTH),
            ),
            walls = listOf(Wall(Point(3, 6), Point(7, 6)))
        )

        assertSame(grid, grid.moveGolem())
    }

    @Test
    fun moveGolem_blocked_by_wall_for_each_direction() {
        // Single-cell wall placed one step ahead of the golem in the
        // direction it is facing; movement must be prevented.
        val cases = listOf(
            Direction.NORTH to Point(5, 4),
            Direction.EAST to Point(6, 5),
            Direction.SOUTH to Point(5, 6),
            Direction.WEST to Point(4, 5),
        )
        for ((direction, wallCell) in cases) {
            val grid = GameGrid(
                tokens = listOf(
                    Golem(Point(5, 5), direction)
                ),
                walls = listOf(
                    // A wall must span at least two cells, so extend it
                    // perpendicularly to the direction of travel.
                    Wall(wallCell, wallCell.copy(x = wallCell.x + 1)),
                )
            )
            assertSame(grid, grid.moveGolem(), "for $direction")
        }
    }

    @Test
    fun moveGolem_advances_when_wall_is_not_in_front() {
        val grid = GameGrid(
            tokens = listOf(
                Golem(Point(5, 5), Direction.SOUTH),
            ),
            walls = listOf(
                // Wall two cells ahead, not directly in front.
                Wall(Point(3, 7), Point(7, 7)),
            )
        )

        val moved = grid.moveGolem()

        assertEquals(Point(5, 6), moved.golem.position)
    }

    @Test
    fun turnGolemRight_rotates_clockwise_through_all_directions() {
        val cases = listOf(
            Direction.NORTH to Direction.EAST,
            Direction.EAST to Direction.SOUTH,
            Direction.SOUTH to Direction.WEST,
            Direction.WEST to Direction.NORTH,
        )
        for ((from, expected) in cases) {
            val grid = GameGrid(
                tokens = listOf(Golem(Point(5, 5), from)),
            )
            val turned = grid.turnGolemRight()
            assertEquals(expected, turned.golem.facing, "right from $from")
            assertEquals(Point(5, 5), turned.golem.position, "right from $from preserves position")
        }
    }

    @Test
    fun turnGolemLeft_rotates_counter_clockwise_through_all_directions() {
        val cases = listOf(
            Direction.NORTH to Direction.WEST,
            Direction.WEST to Direction.SOUTH,
            Direction.SOUTH to Direction.EAST,
            Direction.EAST to Direction.NORTH,
        )
        for ((from, expected) in cases) {
            val grid = GameGrid(
                tokens = listOf(Golem(Point(5, 5), from)),
            )
            val turned = grid.turnGolemLeft()
            assertEquals(expected, turned.golem.facing, "left from $from")
            assertEquals(Point(5, 5), turned.golem.position, "left from $from preserves position")
        }
    }

    @Test
    fun four_right_turns_returns_to_original_direction() {
        val grid = GameGrid(
            tokens = listOf(Golem(Point(3, 7), Direction.NORTH)),
        )

        val rotated = grid.turnGolemRight().turnGolemRight().turnGolemRight().turnGolemRight()

        assertEquals(Direction.NORTH, rotated.golem.facing)
        assertEquals(Point(3, 7), rotated.golem.position)
    }
}
