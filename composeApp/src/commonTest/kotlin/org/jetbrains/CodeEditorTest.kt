package org.jetbrains

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import kscript.KScriptParser
import kscript.parseFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeEditorTest {
    private val parser = KScriptParser()
    private val colors = CodeEditorColors(
        text = Color(0xFF000001),
        keyword = Color(0xFF000002),
        identifier = Color(0xFF000003),
        number = Color(0xFF000004),
        string = Color(0xFF000005),
        comment = Color(0xFF000006),
        operator = Color(0xFF000007),
        punctuation = Color(0xFF000008),
        invalid = Color(0xFF000009),
    )

    @Test
    fun `flattenTokens reproduces the original source`() {
        val source = "val x = 42\nfun greet() { x }"
        val kFile = parser.parseFile(source)
        val tokens = flattenTokens(kFile)
        val reconstructed = tokens.joinToString(separator = "") { it.value }
        assertEquals(source, reconstructed)
    }

    @Test
    fun `highlight returns null when kFile is null`() {
        assertNull(highlight("anything", null, colors))
    }

    @Test
    fun `highlight returns null when reconstructed source does not match`() {
        // Parse one source, but ask the highlighter to color a different one.
        // The reconstructed-source equality check should bail out so the
        // editor never visually rewrites what the user typed.
        val kFile = parser.parseFile("val x = 1")
        assertNull(highlight("val y = 2", kFile, colors))
    }

    @Test
    fun `highlight produces an annotated string equal to the source`() {
        val source = "val n = 7"
        val kFile = parser.parseFile(source)
        val annotated = highlight(source, kFile, colors)
        assertNotNull(annotated)
        assertEquals(source, annotated.text)
    }

    @Test
    fun `highlight assigns the keyword color to the val keyword`() {
        val source = "val n = 7"
        val kFile = parser.parseFile(source)
        val annotated = highlight(source, kFile, colors)
        assertNotNull(annotated)
        // The 'val' keyword spans offsets 0..3 and should be colored as a keyword.
        val styles = annotated.spanStyles.filter { range ->
            range.start <= 0 && range.end >= 3
        }
        assertTrue(
            styles.any { it.item == SpanStyle(color = colors.keyword) },
            "Expected a keyword-colored span covering 'val', got: $styles",
        )
    }

    @Test
    fun `highlight assigns the number color to integer literals`() {
        val source = "val n = 7"
        val kFile = parser.parseFile(source)
        val annotated = highlight(source, kFile, colors)
        assertNotNull(annotated)
        val literalStart = source.indexOf('7')
        val styles = annotated.spanStyles.filter { range ->
            range.start <= literalStart && range.end >= literalStart + 1
        }
        assertTrue(
            styles.any { it.item == SpanStyle(color = colors.number) },
            "Expected a number-colored span covering '7', got: $styles",
        )
    }
}
