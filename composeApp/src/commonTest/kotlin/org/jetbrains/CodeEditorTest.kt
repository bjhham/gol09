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

    // -----------------------------------------------------------------
    // IDE-style editing helpers
    // -----------------------------------------------------------------

    private fun edit(text: String, start: Int, end: Int = start) =
        EditorEdit(text, start, end)

    @Test
    fun `autoPairOpen inserts a closing brace and places the caret between`() {
        val result = autoPairOpen(edit("if (x) ", 7), '{')
        assertEquals("if (x) {}", result.text)
        assertEquals(8, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `autoPairOpen inserts a closing parenthesis`() {
        val result = autoPairOpen(edit("foo", 3), '(')
        assertEquals("foo()", result.text)
        assertEquals(4, result.selectionStart)
    }

    @Test
    fun `autoPairOpen wraps an existing selection`() {
        // "foo bar" with "bar" selected (offsets 4..7).
        val result = autoPairOpen(edit("foo bar", 4, 7), '(')
        assertEquals("foo (bar)", result.text)
        // Caret collapses just past the closing of the opener but before the
        // wrapped text — i.e. at the start of the original selection's
        // content shifted by the inserted `(`.
        assertEquals(8, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `autoIndentOnEnter copies the previous line's indent`() {
        val result = autoIndentOnEnter(edit("    foo", 7))
        assertEquals("    foo\n    ", result.text)
        assertEquals(12, result.selectionStart)
    }

    @Test
    fun `autoIndentOnEnter adds an extra level after an opening brace`() {
        val source = "if (x) {"
        val result = autoIndentOnEnter(edit(source, source.length))
        assertEquals("if (x) {\n    ", result.text)
        assertEquals(13, result.selectionStart)
    }

    @Test
    fun `autoIndentOnEnter splits an empty block onto three lines`() {
        // Caret sits between `{` and `}`; Enter should produce an indented
        // empty line and push `}` to its own line at the original indent.
        val source = "if (x) {}"
        val openIndex = source.indexOf('{') + 1
        val result = autoIndentOnEnter(edit(source, openIndex))
        assertEquals("if (x) {\n    \n}", result.text)
        // Caret lands on the indented blank line, after its four spaces.
        assertEquals("if (x) {\n    ".length, result.selectionStart)
    }

    @Test
    fun `autoIndentOnEnter keeps indentation inside a nested block`() {
        val source = "    if (x) {"
        val result = autoIndentOnEnter(edit(source, source.length))
        // 4-space outer indent + 4-space block indent = 8 leading spaces.
        assertEquals("    if (x) {\n        ", result.text)
    }

    @Test
    fun `handleTab inserts spaces up to the next four-column stop`() {
        // Caret at column 0 → insert four spaces.
        val atStart = handleTab(edit("foo", 0))
        assertEquals("    foo", atStart.text)
        assertEquals(4, atStart.selectionStart)

        // Caret at column 2 → insert two spaces to reach column 4.
        val midColumn = handleTab(edit("ab", 2))
        assertEquals("ab  ", midColumn.text)
        assertEquals(4, midColumn.selectionStart)
    }

    @Test
    fun `handleTab indents every selected line on a multi-line selection`() {
        val source = "foo\nbar\nbaz"
        // Selection covers the start of "foo" through the start of "baz".
        val result = handleTab(edit(source, 0, source.indexOf("baz")))
        assertEquals("    foo\n    bar\n    baz", result.text)
        // Selection grows to encompass the inserted leading indents.
        assertEquals(0, result.selectionStart)
        assertEquals(source.indexOf("baz") + 12, result.selectionEnd)
    }

    @Test
    fun `handleShiftTab removes up to four leading spaces from the current line`() {
        val result = handleShiftTab(edit("    foo", 7))
        assertEquals("foo", result.text)
        assertEquals(3, result.selectionStart)
    }

    @Test
    fun `handleShiftTab is a no-op when the line has no leading spaces`() {
        val result = handleShiftTab(edit("foo", 1))
        assertEquals("foo", result.text)
        assertEquals(1, result.selectionStart)
    }

    @Test
    fun `handleShiftTab dedents every selected line on a multi-line selection`() {
        val source = "    foo\n    bar\n    baz"
        // Selection from start of "foo" to start of "baz".
        val result = handleShiftTab(edit(source, 4, source.indexOf("baz")))
        assertEquals("foo\nbar\nbaz", result.text)
        assertEquals(0, result.selectionStart)
        assertEquals("foo\nbar\n".length, result.selectionEnd)
    }

    @Test
    fun `handleShiftTab removes only the available leading spaces per line`() {
        // First line has two spaces, second has six — only what is there
        // should be dropped, capped at the indent width.
        val source = "  foo\n      bar"
        val result = handleShiftTab(edit(source, 0, source.length))
        assertEquals("foo\n  bar", result.text)
    }

    // -----------------------------------------------------------------
    // detectAutoPairInsert — change-diff based auto-pairing
    // -----------------------------------------------------------------

    @Test
    fun `detectAutoPairInsert pairs an inserted opening brace`() {
        // Simulate the user typing `{` at the end of "if (x) ".
        val previous = edit("if (x) ", 7)
        val incoming = edit("if (x) {", 8)
        val result = detectAutoPairInsert(previous, incoming)
        assertNotNull(result)
        assertEquals("if (x) {}", result.text)
        assertEquals(8, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `detectAutoPairInsert pairs an inserted opening parenthesis`() {
        val previous = edit("foo", 3)
        val incoming = edit("foo(", 4)
        val result = detectAutoPairInsert(previous, incoming)
        assertNotNull(result)
        assertEquals("foo()", result.text)
        assertEquals(4, result.selectionStart)
    }

    @Test
    fun `detectAutoPairInsert wraps a selection when the user types over it`() {
        // Selection: "bar" in "foo bar" (offsets 4..7); user types `(`.
        // The text-field's default behaviour replaces the selection with `(`,
        // landing the caret at offset 5. The detector should recognise this
        // as a single-`(` insertion and route the change through
        // `autoPairOpen`, which wraps the original selection with parens.
        val previous = edit("foo bar", 4, 7)
        val incoming = edit("foo (", 5)
        val result = detectAutoPairInsert(previous, incoming)
        assertNotNull(result)
        assertEquals("foo (bar)", result.text)
        // `autoPairOpen` collapses the selection to just past "bar".
        assertEquals(8, result.selectionStart)
        assertEquals(8, result.selectionEnd)
    }

    @Test
    fun `detectAutoPairInsert returns null for non-paired characters`() {
        val previous = edit("foo", 3)
        val incoming = edit("fooa", 4)
        assertNull(detectAutoPairInsert(previous, incoming))
    }

    @Test
    fun `detectAutoPairInsert returns null on a multi-character paste`() {
        // Pasting "{}" should not retrigger pairing — the user already
        // produced a balanced fragment.
        val previous = edit("foo", 3)
        val incoming = edit("foo{}", 5)
        assertNull(detectAutoPairInsert(previous, incoming))
    }

    @Test
    fun `detectAutoPairInsert returns null on deletion`() {
        val previous = edit("foo{", 4)
        val incoming = edit("foo", 3)
        assertNull(detectAutoPairInsert(previous, incoming))
    }

    @Test
    fun `detectAutoPairInsert returns null when only the caret moved`() {
        val previous = edit("foo{}", 4)
        val incoming = edit("foo{}", 3)
        assertNull(detectAutoPairInsert(previous, incoming))
    }

    // -----------------------------------------------------------------
    // detectCloseSkip — type-through of an existing closer
    // -----------------------------------------------------------------

    @Test
    fun `detectCloseSkip steps over an existing closing brace`() {
        // Caret is between an auto-inserted `{}` pair; the user types `}`.
        // The text-field's default behaviour would insert a second `}`,
        // landing on "foo{}}" with the caret at offset 5. The detector
        // should recognise this as a skip and produce the previous text
        // unchanged with the caret simply advanced past the existing `}`.
        val previous = edit("foo{}", 4)
        val incoming = edit("foo{}}", 5)
        val result = detectCloseSkip(previous, incoming)
        assertNotNull(result)
        assertEquals("foo{}", result.text)
        assertEquals(5, result.selectionStart)
        assertEquals(5, result.selectionEnd)
    }

    @Test
    fun `detectCloseSkip steps over an existing closing parenthesis`() {
        val previous = edit("foo()", 4)
        val incoming = edit("foo())", 5)
        val result = detectCloseSkip(previous, incoming)
        assertNotNull(result)
        assertEquals("foo()", result.text)
        assertEquals(5, result.selectionStart)
    }

    @Test
    fun `detectCloseSkip returns null when the next character does not match`() {
        // The character to the right of the caret is `)`, but the user
        // typed `}` — that's a real insertion, not a skip.
        val previous = edit("foo()", 4)
        val incoming = edit("foo(})", 5)
        assertNull(detectCloseSkip(previous, incoming))
    }

    @Test
    fun `detectCloseSkip returns null for non-closing characters`() {
        val previous = edit("foo{}", 4)
        val incoming = edit("foo{a}", 5)
        assertNull(detectCloseSkip(previous, incoming))
    }

    @Test
    fun `detectCloseSkip returns null when there is no following character`() {
        // Caret at end of text — no existing `}` to skip past.
        val previous = edit("foo", 3)
        val incoming = edit("foo}", 4)
        assertNull(detectCloseSkip(previous, incoming))
    }

    @Test
    fun `detectCloseSkip returns null when the previous state had a selection`() {
        // With a selection, typing `}` is a replace, not a skip — it would
        // delete the selection and insert a brace.
        val previous = edit("foo{x}", 4, 5)
        val incoming = edit("foo{}}", 5)
        assertNull(detectCloseSkip(previous, incoming))
    }
}
