package org.jetbrains

import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreTest {

    @Test
    fun countCodeTokens_counts_identifiers_literals_and_keywords() {
        // Identifiers: x (twice), println   -> 3
        // Literals:    1, 2, 3, "hi"        -> 4
        // Keywords:    val, if              -> 2
        // Operators / punctuation are not counted.
        val code = """
            val x = 1 + 2
            if (x == 3) println("hi")
        """.trimIndent()

        assertEquals(9, countCodeTokens(code))
    }

    @Test
    fun countCodeTokens_ignores_whitespace_and_punctuation() {
        // Just `move()` -> a single identifier.
        assertEquals(1, countCodeTokens("move()"))
    }

    @Test
    fun countCodeTokens_counts_boolean_and_null_as_literals() {
        // val a = true, val b = false, val c = null
        // Identifiers (a, b, c) = 3, literals (true, false, null) = 3,
        // keywords (val x3) = 3 -> total 9.
        val code = "val a = true\nval b = false\nval c = null\n"
        assertEquals(9, countCodeTokens(code))
    }

    @Test
    fun countCodeTokens_handles_empty_input() {
        assertEquals(0, countCodeTokens(""))
        assertEquals(0, countCodeTokens("   \n  \t\n"))
    }

    @Test
    fun countCodeTokens_ignores_line_comments() {
        // The identifier `move` is the only counted token; the rest is
        // whitespace, punctuation, or a comment.
        assertEquals(1, countCodeTokens("move() // turn around"))
    }

    @Test
    fun computeScore_basic_ratio() {
        // 100 * 4 / 8 = 50
        assertEquals(50, computeScore(target = 4, tokens = 8))
    }

    @Test
    fun computeScore_clamps_to_100_when_target_exceeds_tokens() {
        // 100 * 6 / 3 = 200, clamped to 100.
        assertEquals(100, computeScore(target = 6, tokens = 3))
    }

    @Test
    fun computeScore_returns_zero_when_no_tokens() {
        assertEquals(0, computeScore(target = 4, tokens = 0))
    }

    @Test
    fun letterGrade_boundaries_match_us_university_scale() {
        assertEquals("A+", letterGrade(100))
        assertEquals("A+", letterGrade(97))
        assertEquals("A", letterGrade(96))
        assertEquals("A", letterGrade(93))
        assertEquals("A-", letterGrade(92))
        assertEquals("A-", letterGrade(90))
        assertEquals("B+", letterGrade(89))
        assertEquals("B+", letterGrade(87))
        assertEquals("B", letterGrade(86))
        assertEquals("B", letterGrade(83))
        assertEquals("B-", letterGrade(82))
        assertEquals("B-", letterGrade(80))
        assertEquals("C+", letterGrade(79))
        assertEquals("C+", letterGrade(77))
        assertEquals("C", letterGrade(76))
        assertEquals("C", letterGrade(73))
        assertEquals("C-", letterGrade(72))
        assertEquals("C-", letterGrade(70))
        assertEquals("D+", letterGrade(69))
        assertEquals("D+", letterGrade(67))
        assertEquals("D", letterGrade(66))
        assertEquals("D", letterGrade(63))
        assertEquals("D-", letterGrade(62))
        assertEquals("D-", letterGrade(60))
        assertEquals("F", letterGrade(59))
        assertEquals("F", letterGrade(0))
    }
}
