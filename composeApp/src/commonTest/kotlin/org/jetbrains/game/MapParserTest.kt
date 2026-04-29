package org.jetbrains.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapParserTest {

    private val parser = MapParser()

    @Test
    fun parses_level0_start_in_top_left_facing_south() {
        // The contents of composeApp/src/commonMain/composeResources/files/maps/level0.
        val text = "START 0,0\n"

        val model = parser.parse(text)

        assertEquals(12, model.width)
        assertEquals(12, model.height)
        assertEquals(Position(0, 0), model.golem.position)
        assertEquals(Direction.SOUTH, model.golem.facing)
        // The golem must also appear in the rendered token list.
        assertEquals(listOf(Golem(Position(0, 0), Direction.SOUTH)), model.tokens)
    }

    @Test
    fun parses_cheese_entries_into_cheese_tokens() {
        val text = """
            START 0,0
            CHEESE 11,11
        """.trimIndent()

        val model = parser.parse(text)

        assertEquals(Position(0, 0), model.golem.position)
        // The cheese should be present alongside the golem in the rendered token list.
        assertEquals(
            listOf(
                Golem(Position(0, 0), Direction.SOUTH),
                Cheese(Position(11, 11)),
            ),
            model.tokens,
        )
    }

    @Test
    fun cheese_out_of_bounds_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nCHEESE 12,0\n")
        }
    }

    @Test
    fun ignores_blank_lines_and_comments() {
        val text = """
            # This is a comment

            START 3,5
        """.trimIndent()

        val model = parser.parse(text)

        assertEquals(Position(3, 5), model.golem.position)
        assertEquals(Direction.SOUTH, model.golem.facing)
    }

    @Test
    fun missing_start_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("")
        }
    }

    @Test
    fun duplicate_start_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nSTART 1,1\n")
        }
    }

    @Test
    fun unknown_object_reference_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nMYSTERY 2,2\n")
        }
    }

    @Test
    fun out_of_bounds_position_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 12,0\n")
        }
        assertFailsWith<MapParseException> {
            parser.parse("START -1,0\n")
        }
    }

    @Test
    fun malformed_line_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0\n")
        }
        assertFailsWith<MapParseException> {
            parser.parse("START a,b\n")
        }
    }

    @Test
    fun parses_vertical_wall_with_y_range() {
        // Mirrors the contents of the level1 map file.
        val text = """
            START 0,5
            WALL 2,2-9
            CHEESE 11,5
        """.trimIndent()

        val model = parser.parse(text)

        assertEquals(
            listOf(
                Golem(Position(0, 5), Direction.SOUTH),
                Cheese(Position(11, 5)),
                Wall(position = Position(2, 2), end = Position(2, 9)),
            ),
            model.elements,
        )
    }

    @Test
    fun parses_horizontal_wall_with_x_range() {
        val text = """
            START 0,0
            WALL 0-5,7
        """.trimIndent()

        val model = parser.parse(text)

        val wall = model.walls.single()
        assertEquals(Position(0, 7), wall.position)
        assertEquals(Position(5, 7), wall.end)
        assertEquals(
            listOf(
                Position(0, 7),
                Position(1, 7),
                Position(2, 7),
                Position(3, 7),
                Position(4, 7),
                Position(5, 7),
            ),
            wall.cells,
        )
    }

    @Test
    fun wall_with_two_ranges_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nWALL 1-3,2-4\n")
        }
    }

    @Test
    fun wall_with_no_range_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nWALL 2,3\n")
        }
    }

    @Test
    fun wall_descending_range_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nWALL 2,9-2\n")
        }
    }

    @Test
    fun wall_out_of_bounds_throws() {
        assertFailsWith<MapParseException> {
            parser.parse("START 0,0\nWALL 2,5-12\n")
        }
    }
}
