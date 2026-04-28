package org.jetbrains.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MapParserTest {

    private val parser = MapParser()

    @Test
    fun parses_level0_start_in_top_left_facing_south() {
        // The contents of composeApp/src/commonMain/resources/maps/level0.
        val text = "START 0,0\n"

        val model = parser.parse(text)

        assertEquals(24, model.width)
        assertEquals(24, model.height)
        assertEquals(Position(0, 0), model.golem.position)
        assertEquals(Direction.SOUTH, model.golem.facing)
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
            parser.parse("START 24,0\n")
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
}
