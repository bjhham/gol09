package org.jetbrains

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gol09.composeapp.generated.resources.Res
import gol09.composeapp.generated.resources.VT323_Regular
import kscript.KComment
import kscript.KElement
import kscript.KFile
import kscript.KIdentifier
import kscript.KLiteral
import kscript.KNode
import kscript.KThisExpression
import kscript.KToken
import kscript.KTokenType
import kscript.KValOrVar
import kscript.KVariableReference
import kscript.KWhitespace
import org.jetbrains.compose.resources.Font

/**
 * Color scheme used by [CodeEditor] to render the highlighted source text.
 *
 * Each property is a [Color] applied to spans of one logical category. The
 * defaults use the Material 3 color scheme so the editor blends with the rest
 * of the application; callers that want a custom palette can construct their
 * own instance.
 */
data class CodeEditorColors(
    val text: Color,
    val keyword: Color,
    val identifier: Color,
    val number: Color,
    val string: Color,
    val comment: Color,
    val operator: Color,
    val punctuation: Color,
    val invalid: Color,
)

/**
 * Default color scheme derived from the current [MaterialTheme]. Keywords use
 * the primary accent, literals and strings use the tertiary/secondary accents,
 * and comments are dimmed via the `outline` role.
 */
@Composable
fun defaultCodeEditorColors(): CodeEditorColors {
    val scheme = MaterialTheme.colorScheme
    return CodeEditorColors(
        text = scheme.onSurface,
        keyword = scheme.primary,
        identifier = scheme.onSurface,
        number = scheme.tertiary,
        string = scheme.secondary,
        comment = scheme.outline,
        operator = scheme.onSurfaceVariant,
        punctuation = scheme.onSurfaceVariant,
        invalid = scheme.error,
    )
}

/**
 * Rich code editor that performs syntax highlighting based on a parsed
 * [KFile]. The text content of the editor is owned by the caller via
 * [code]/[onCodeChange]; the editor is purely a presentation component.
 *
 * Highlighting is applied as a [VisualTransformation] over the raw text so
 * that selection, cursor positioning, and IME interaction continue to work
 * with the original source string. When [kFile] is `null` (e.g. while the
 * user is mid-edit and the source does not parse) or when its reconstructed
 * source does not exactly match [code], the editor falls back to rendering
 * the text without any color spans — never altering what the user typed.
 *
 * The editor also implements a small set of IDE-style conveniences that
 * operate purely on the editor's text + caret/selection state:
 *
 *  - Typing `{` or `(` inserts the matching `}` or `)` and leaves the caret
 *    between the pair. Typing `}` or `)` when the same character already
 *    sits to the right of the caret simply moves the caret past it instead
 *    of inserting a duplicate, so the user can "type through" an
 *    auto-inserted closer.
 *  - Pressing return inserts a newline that copies the previous line's
 *    leading indent, plus an extra level of four spaces when the caret sits
 *    immediately after a `{`. When the caret is between `{` and `}`, the
 *    closing brace is pushed to its own line at the original indent.
 *  - Tab inserts spaces up to the next four-column stop, while Shift+Tab
 *    removes up to four leading spaces from the current line. With a
 *    multi-line selection both keys indent / dedent every selected line.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    kFile: KFile?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CodeEditorColors = defaultCodeEditorColors(),
    completions: List<CompletionItem> = emptyList(),
) {
    val baseStyle = LocalTextStyle.current.merge(
        TextStyle(
            fontFamily = FontFamily(Font(Res.font.VT323_Regular)),
            color = colors.text,
        ),
    )
    val transformation = remember(kFile, colors) {
        SyntaxHighlightTransformation(kFile, colors)
    }
    val scrollState = rememberScrollState()

    // The editor is a controlled component as far as text is concerned —
    // the caller owns `code` — but it needs to manage caret and selection
    // itself in order to implement the IDE-style key handlers below. We
    // mirror the incoming `code` into a [TextFieldValue] and only forward
    // text changes to `onCodeChange`; selection lives entirely in this
    // composable's state.
    var fieldValue by remember { mutableStateOf(TextFieldValue(text = code)) }
    if (fieldValue.text != code) {
        // The caller swapped in new text out-of-band (e.g. loading a level).
        // Reset the selection to the end of the new text.
        fieldValue = TextFieldValue(
            text = code,
            selection = TextRange(code.length),
        )
    }

    // Autocompletion popup state. The popup only opens once the user has
    // actually started typing an identifier — i.e. there is a non-empty
    // prefix at the caret whose first character is a letter. That keeps
    // the suggestion list from intruding on a freshly-opened or
    // freshly-cleared line where the user hasn't expressed any intent
    // yet. Typing more characters re-opens the popup on the next match;
    // Escape dismisses it for the current word. [selectedIndex] is
    // clamped back into range whenever the matches list shrinks.
    var popupDismissed by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    // Tracked layout state used to anchor the completion popup directly
    // beneath the caret. The text-field's [TextLayoutResult] gives us per-
    // character cursor rects; the editor [Box]'s [LayoutCoordinates] (the
    // popup's parent) and the [BasicTextField]'s coordinates let us
    // translate that cursor rect into the popup's local coordinate space,
    // accounting for the editor's vertical scroll.
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var editorBoxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var textFieldCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val caret = fieldValue.selection.start
    val noSelection = fieldValue.selection.start == fieldValue.selection.end
    val prefix = if (noSelection) currentWordPrefix(fieldValue.text, caret) else ""
    // Require the prefix to start with a letter before offering
    // suggestions. Underscore-led identifiers are valid Kotlin but rarely
    // typed in this editor, and gating on `isLetter` matches the user's
    // ask: the popup appears once they type an alphabetic character.
    val hasTypedPrefix = prefix.isNotEmpty() && prefix[0].isLetter()
    // Only offer completions when the caret is at the end of the word
    // (i.e. there's no identifier character immediately to the right).
    // This prevents the popup from appearing when the user clicks into
    // the middle of an already-completed identifier such as `turnRight`.
    val caretAtWordEnd = isCaretAtWordEnd(fieldValue.text, caret)
    val matches = remember(completions, prefix, hasTypedPrefix, caretAtWordEnd) {
        if (!hasTypedPrefix || !caretAtWordEnd) emptyList()
        else filterCompletions(completions, prefix)
    }
    val popupVisible = enabled && !popupDismissed && matches.isNotEmpty() && noSelection && hasTypedPrefix && caretAtWordEnd
    LaunchedEffect(matches) {
        if (selectedIndex >= matches.size) selectedIndex = 0
    }
    // Re-open the popup automatically whenever the prefix changes (the user
    // typed a new character or moved the caret into a fresh word) so a
    // dismissal only suppresses the currently-active completion session.
    LaunchedEffect(prefix, caret) {
        popupDismissed = false
    }

    fun applyEdit(edit: EditorEdit) {
        fieldValue = TextFieldValue(
            text = edit.text,
            selection = TextRange(edit.selectionStart, edit.selectionEnd),
        )
        if (edit.text != code) onCodeChange(edit.text)
    }

    fun acceptSelectedCompletion(): Boolean {
        if (!popupVisible) return false
        val item = matches.getOrNull(selectedIndex) ?: return false
        val current = EditorEdit(
            text = fieldValue.text,
            selectionStart = fieldValue.selection.start,
            selectionEnd = fieldValue.selection.end,
        )
        applyEdit(applyCompletion(current, item))
        popupDismissed = true
        selectedIndex = 0
        return true
    }

    val keyHandler = Modifier.onPreviewKeyEvent { event ->
        if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        // When the autocompletion popup is showing, intercept the
        // navigation keys so they drive the popup instead of moving the
        // caret. Tab and Enter accept the highlighted suggestion; Escape
        // dismisses the popup without touching the buffer.
        if (popupVisible) {
            when (event.key) {
                Key.DirectionDown -> {
                    selectedIndex = (selectedIndex + 1).coerceAtMost(matches.lastIndex)
                    return@onPreviewKeyEvent true
                }
                Key.DirectionUp -> {
                    selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                    return@onPreviewKeyEvent true
                }
                Key.Enter, Key.NumPadEnter -> {
                    if (event.isShiftPressed) return@onPreviewKeyEvent false
                    return@onPreviewKeyEvent acceptSelectedCompletion()
                }
                Key.Tab -> {
                    if (event.isShiftPressed) return@onPreviewKeyEvent false
                    return@onPreviewKeyEvent acceptSelectedCompletion()
                }
                Key.Escape -> {
                    popupDismissed = true
                    return@onPreviewKeyEvent true
                }
                else -> {
                    // Fall through to the regular editing key handlers below.
                }
            }
        }
        val current = EditorEdit(
            text = fieldValue.text,
            selectionStart = fieldValue.selection.start,
            selectionEnd = fieldValue.selection.end,
        )
        when (event.key) {
            Key.Tab -> {
                val next = if (event.isShiftPressed) handleShiftTab(current) else handleTab(current)
                applyEdit(next)
                true
            }
            Key.Enter, Key.NumPadEnter -> {
                if (event.isShiftPressed) return@onPreviewKeyEvent false
                applyEdit(autoIndentOnEnter(current))
                true
            }
            else -> false
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp)
            .onGloballyPositioned { editorBoxCoords = it },
    ) {
        BasicTextField(
            value = fieldValue,
            onTextLayout = { textLayout = it },
            onValueChange = { newValue ->
                // Auto-pair `{`/`(` when the only thing the user did was type
                // one of those characters. We can't reliably catch printable
                // keystrokes via `onPreviewKeyEvent` on every platform — on
                // desktop JVM in particular the character information is
                // delivered through the IME pipeline, which surfaces here as
                // a normal text-field change. Diffing the previous value
                // against the new one lets us intercept the insertion
                // regardless of input source.
                val previous = EditorEdit(
                    text = fieldValue.text,
                    selectionStart = fieldValue.selection.start,
                    selectionEnd = fieldValue.selection.end,
                )
                val incoming = EditorEdit(
                    text = newValue.text,
                    selectionStart = newValue.selection.start,
                    selectionEnd = newValue.selection.end,
                )
                val paired = detectAutoPairInsert(previous, incoming)
                val skipped = if (paired == null) detectCloseSkip(previous, incoming) else null
                val applied = when {
                    paired != null -> TextFieldValue(
                        text = paired.text,
                        selection = TextRange(paired.selectionStart, paired.selectionEnd),
                    )
                    skipped != null -> TextFieldValue(
                        text = skipped.text,
                        selection = TextRange(skipped.selectionStart, skipped.selectionEnd),
                    )
                    else -> newValue
                }
                fieldValue = applied
                if (applied.text != code) onCodeChange(applied.text)
            },
            enabled = enabled,
            textStyle = baseStyle,
            cursorBrush = SolidColor(colors.text),
            visualTransformation = transformation,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .onGloballyPositioned { textFieldCoords = it }
                .then(keyHandler),
            decorationBox = { innerTextField ->
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = "Code",
                        style = baseStyle.copy(color = colors.comment),
                    )
                }
                innerTextField()
            },
        )

        // Compute the caret rect in the editor [Box]'s local coordinate
        // space. We start from the [TextLayoutResult]'s cursor rect (in
        // the BasicTextField's *content* coords, ignoring scroll), then
        // translate the rect's top-left into the Box's coords via the two
        // captured [LayoutCoordinates]. That mapping naturally folds in
        // the vertical scroll offset, since `textFieldCoords` reflects
        // the field's clipped, scrolled position. When any required piece
        // is missing (first composition, popup not yet visible) we fall
        // back to `null` and `CompletionPopup` will use a sensible
        // default.
        val caretAnchor = run {
            val layout = textLayout
            val boxCoords = editorBoxCoords
            val fieldCoords = textFieldCoords
            if (layout == null || boxCoords == null || fieldCoords == null) {
                null
            } else {
                val cursorOffset = caret.coerceIn(0, layout.layoutInput.text.length)
                val cursor = layout.getCursorRect(cursorOffset)
                val topLeftInBox = boxCoords.localPositionOf(fieldCoords, cursor.topLeft)
                Rect(
                    left = topLeftInBox.x,
                    top = topLeftInBox.y,
                    right = topLeftInBox.x + cursor.width,
                    bottom = topLeftInBox.y + cursor.height,
                )
            }
        }

        if (popupVisible) {
            CompletionPopup(
                items = matches,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    selectedIndex = index
                    acceptSelectedCompletion()
                },
                onDismiss = { popupDismissed = true },
                colors = colors,
                textStyle = baseStyle,
                anchorInParent = caretAnchor,
            )
        }
    }
}

/**
 * Floating list rendered next to the caret showing the currently-matching
 * [items]. The popup is anchored to the caret rectangle [anchorInParent]
 * (expressed in the editor [Box]'s local coordinate space): it appears
 * just below the caret line, but flips above the caret automatically when
 * there isn't enough vertical room beneath it. Falling back to `null`
 * places the popup at the editor's top-left, which only happens during
 * the first frame before layout has settled. The highlighted entry is the
 * one Enter/Tab will accept; clicking an entry both selects and accepts
 * it in a single gesture.
 */
@Composable
private fun CompletionPopup(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    colors: CodeEditorColors,
    textStyle: TextStyle,
    anchorInParent: Rect?,
) {
    // A small gap between the caret and the popup keeps the suggestions
    // visually distinct from the line being typed without floating too
    // far away. Used both above and below the caret.
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val anchorPx = anchorInParent?.let {
        IntRect(
            left = it.left.toInt(),
            top = it.top.toInt(),
            right = it.right.toInt(),
            bottom = it.bottom.toInt(),
        )
    }
    val positionProvider = remember(anchorPx, gapPx) {
        CaretPopupPositionProvider(anchorPx, gapPx)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp),
                )
                .widthIn(min = 160.dp, max = 320.dp)
                .heightIn(max = 200.dp),
        ) {
            val scroll = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scroll)) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex
                    val rowColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    }
                    val textColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        colors.text
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowColor)
                            .clickable { onSelect(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = item.label,
                            style = textStyle.copy(color = textColor),
                        )
                    }
                }
            }
        }
    }
}

/**
 * [PopupPositionProvider] that places the popup just below the caret line
 * defined by [anchorInParent], or above it when the popup would otherwise
 * overflow the bottom of the available window. [verticalGap] is added
 * between the caret line and the nearest edge of the popup, both above
 * and below.
 *
 * When [anchorInParent] is `null` the provider falls back to the parent
 * bounds' top-left so the popup still renders sensibly during the first
 * frame, before layout has had a chance to settle.
 *
 * Horizontally, the popup is aligned to the caret's left edge but clamped
 * to remain inside the window so it never gets cut off on either side.
 */
internal class CaretPopupPositionProvider(
    private val anchorInParent: IntRect?,
    private val verticalGap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Translate the caret rect — given in the editor [Box]'s local
        // space — into window coordinates by adding the parent's top-
        // left, which is what `anchorBounds` represents.
        val caret = anchorInParent?.let {
            IntRect(
                left = anchorBounds.left + it.left,
                top = anchorBounds.top + it.top,
                right = anchorBounds.left + it.right,
                bottom = anchorBounds.top + it.bottom,
            )
        } ?: IntRect(anchorBounds.left, anchorBounds.top, anchorBounds.left, anchorBounds.top)

        // Prefer below the caret. Flip above when the popup would extend
        // past the window's bottom edge AND there is enough room above
        // the caret to fit it; otherwise stay below and let the system
        // clip if necessary, which keeps behaviour predictable for very
        // short windows.
        val below = caret.bottom + verticalGap
        val above = caret.top - verticalGap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> below
        }

        // Align the popup's left edge with the caret, then clamp so the
        // popup never extends past either side of the window.
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = caret.left.coerceIn(0, maxX)

        return IntOffset(x, y)
    }
}

/**
 * [VisualTransformation] that maps the editor's plain text into an
 * [AnnotatedString] with color spans derived from [kFile]. The transformation
 * is identity on offsets — every character in the input maps to itself in the
 * output — so cursor and selection coordinates remain unchanged.
 */
private class SyntaxHighlightTransformation(
    private val kFile: KFile?,
    private val colors: CodeEditorColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = highlight(text.text, kFile, colors)
            ?: AnnotatedString(text.text, spanStyle = SpanStyle(color = colors.text))
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

/**
 * Build an [AnnotatedString] for [source] by walking the leaf tokens of
 * [kFile] in order and applying a color span per token according to its
 * [KTokenType]. Returns `null` when no highlighting can be safely applied —
 * either because [kFile] is `null` or because its reconstructed text does not
 * exactly match [source]; in that case the caller should render the source
 * with the default text color.
 */
internal fun highlight(
    source: String,
    kFile: KFile?,
    colors: CodeEditorColors,
): AnnotatedString? {
    if (kFile == null) return null
    val tokens = flattenTokens(kFile)
    val reconstructed = buildString {
        for (token in tokens) append(token.value)
    }
    if (reconstructed != source) return null
    return buildAnnotatedString {
        for (token in tokens) {
            val style = SpanStyle(color = colorFor(token.type, colors))
            withStyle(style) { append(token.value.toString()) }
        }
    }
}


/**
 * Recursively flatten [node] into the ordered sequence of leaf [KToken]s
 * that make up its source representation. This mirrors what
 * `KElement.asString()` produces, but exposes the underlying tokens so each
 * one can be styled individually.
 */
internal fun flattenTokens(node: KNode): List<KToken> {
    val out = ArrayList<KToken>()
    collectTokens(node, out)
    return out
}

private fun collectTokens(node: KNode, out: MutableList<KToken>) {
    when (node) {
        is KToken -> out.add(node)
        is KWhitespace -> out.add(node.token)
        is KComment -> out.add(node.token)
        is KIdentifier -> out.add(node.token)
        is KLiteral -> out.add(node.token)
        is KValOrVar -> out.add(node.token)
        is KThisExpression -> out.add(node.token)
        is KVariableReference -> collectTokens(node.name, out)
        is KElement -> for (child in node.children) collectTokens(child, out)
        else -> {
            // Any leaf KNode that isn't one of the wrappers above contributes
            // text via `asString()` but doesn't expose a `KToken`. Dropping it
            // would leave the reconstructed source shorter than the input,
            // which trips the equality check in `highlight` and falls back to
            // unstyled rendering — exactly what we want when we encounter an
            // unsupported node, so do nothing here.
        }
    }
}

private fun colorFor(type: KTokenType, colors: CodeEditorColors): Color = when (type) {
    KTokenType.BLOCK_COMMENT,
    KTokenType.EOL_COMMENT,
    KTokenType.SHEBANG_COMMENT -> colors.comment

    KTokenType.INTEGER_LITERAL,
    KTokenType.FLOAT_LITERAL,
    KTokenType.CHARACTER_LITERAL -> colors.number

    KTokenType.OPEN_QUOTE,
    KTokenType.CLOSING_QUOTE,
    KTokenType.REGULAR_STRING_PART,
    KTokenType.ESCAPE_SEQUENCE,
    KTokenType.SHORT_TEMPLATE_ENTRY_START,
    KTokenType.LONG_TEMPLATE_ENTRY_START,
    KTokenType.LONG_TEMPLATE_ENTRY_END,
    KTokenType.INTERPOLATION_PREFIX -> colors.string

    KTokenType.IDENTIFIER,
    KTokenType.FIELD_IDENTIFIER -> colors.identifier

    KTokenType.INVALID -> colors.invalid

    KTokenType.LBRACKET, KTokenType.RBRACKET,
    KTokenType.LBRACE, KTokenType.RBRACE,
    KTokenType.LPAR, KTokenType.RPAR,
    KTokenType.COMMA, KTokenType.SEMICOLON,
    KTokenType.DOUBLE_SEMICOLON, KTokenType.EOL_OR_SEMICOLON,
    KTokenType.DOT, KTokenType.COLON, KTokenType.COLONCOLON,
    KTokenType.AT, KTokenType.HASH -> colors.punctuation

    KTokenType.WHITESPACE,
    KTokenType.DANGLING_NEWLINE,
    KTokenType.EOF -> colors.text

    else -> if (type.isKeyword()) colors.keyword else colors.operator
}

private fun KTokenType.isKeyword(): Boolean {
    // Token names follow the convention `*_KEYWORD` for reserved words. Using
    // the enum name keeps this in sync automatically when new keywords are
    // added in `KToken.kt` without having to enumerate them here.
    return name.endsWith("_KEYWORD") ||
        this == KTokenType.TRUE_KEYWORD ||
        this == KTokenType.FALSE_KEYWORD ||
        this == KTokenType.NULL_KEYWORD ||
        this == KTokenType.UNIT
}

// ---------------------------------------------------------------------------
// IDE-style editing helpers
//
// The functions below operate on a plain ([text], [selectionStart],
// [selectionEnd]) triple and produce the post-edit triple. Pulling them out
// of the composable keeps them framework-free so they can be exercised by
// unit tests without spinning up a Compose host.
// ---------------------------------------------------------------------------

/** Indent unit used by the editor — four spaces, deliberately not configurable. */
internal const val INDENT_WIDTH: Int = 4

/**
 * Snapshot of the editor's text and selection. [selectionStart] is the
 * caret/anchor offset in [text]; [selectionEnd] is the focus offset. They
 * are equal when there is no selection, and `start <= end` is **not**
 * required by callers — the helpers always work with the canonical
 * `[min, max)` range internally.
 */
internal data class EditorEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * One entry in the editor's autocompletion list.
 *
 * [label] is what the popup displays to the user (e.g. `"turnLeft()"` or
 * `"while"`); [insertText] is the literal text that replaces the current
 * word at the caret when the entry is accepted. The two are usually the
 * same — they only differ when the inserted text needs decorations the
 * label doesn't carry, or vice versa. The completion engine matches a
 * typed prefix against [label] (case-insensitively, prefix-only) so the
 * label is what the user reads *and* what they're filtering by.
 */
data class CompletionItem(
    val label: String,
    val insertText: String = label,
)

/**
 * Extract the identifier-like word that ends at [caret] in [text]. An
 * identifier here is the standard Kotlin shape: it must start with a
 * letter or `_` and may continue with letters, digits, or `_`. Returns
 * an empty string when the character immediately before the caret is
 * not a valid identifier character — e.g. the caret sits at the start
 * of a fresh line or right after whitespace/punctuation. The caller can
 * use that case to mean "no prefix typed yet, show all completions".
 */
internal fun currentWordPrefix(text: String, caret: Int): String {
    if (caret <= 0 || caret > text.length) return ""
    var start = caret
    while (start > 0 && text[start - 1].isIdentifierPart()) start--
    // Reject prefixes whose first character is a digit — those aren't
    // identifiers in Kotlin and should not trigger completion.
    if (start < caret && !text[start].isIdentifierStart()) return ""
    return text.substring(start, caret)
}

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'
private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

/**
 * Returns `true` when [caret] sits at the end of an identifier-like word
 * in [text] — i.e. the character immediately to the right of the caret
 * is not part of an identifier. The caret being at the very end of [text]
 * also counts. This is used to suppress the autocompletion popup when
 * the user clicks into the middle of an already-typed word such as
 * `turnRight`, where suggestions would only be a distraction.
 */
internal fun isCaretAtWordEnd(text: String, caret: Int): Boolean {
    if (caret < 0 || caret > text.length) return false
    if (caret == text.length) return true
    return !text[caret].isIdentifierPart()
}

/**
 * Filter [items] down to those whose label starts with [prefix],
 * case-insensitively. An empty [prefix] matches every item. The order
 * of [items] is preserved so callers can pre-sort the list however they
 * prefer (alphabetically, by relevance, etc.).
 */
internal fun filterCompletions(
    items: List<CompletionItem>,
    prefix: String,
): List<CompletionItem> {
    if (prefix.isEmpty()) return items
    val lower = prefix.lowercase()
    return items.filter { it.label.lowercase().startsWith(lower) }
}

/**
 * Apply [item] to [edit] by replacing the identifier-like word that
 * ends at the caret with [CompletionItem.insertText]. When the caret
 * sits between an open paren the inserted text already provides (e.g.
 * the user accepted `move()` while the editor had auto-paired a `(`),
 * we still emit `move()` as-is — duplicate `()` is preferable to
 * silently dropping characters the user can see in the popup. The
 * resulting caret is collapsed to the end of the inserted text.
 */
internal fun applyCompletion(edit: EditorEdit, item: CompletionItem): EditorEdit {
    val caret = minOf(edit.selectionStart, edit.selectionEnd)
    val prefix = currentWordPrefix(edit.text, caret)
    val replaceFrom = caret - prefix.length
    val before = edit.text.substring(0, replaceFrom)
    val after = edit.text.substring(maxOf(edit.selectionStart, edit.selectionEnd))
    val newText = before + item.insertText + after
    val newCaret = replaceFrom + item.insertText.length
    return EditorEdit(newText, newCaret, newCaret)
}

/**
 * Insert [openChar] together with its matching closing character at the
 * caret, leaving the caret between the pair. When there is a selection,
 * the selection is replaced by `open<selection>close` and the new caret
 * collapses to the end of the inserted opener (i.e. the start of the
 * original selection's text). Only `{` and `(` are auto-paired.
 */
internal fun autoPairOpen(edit: EditorEdit, openChar: Char): EditorEdit {
    val close = when (openChar) {
        '{' -> '}'
        '(' -> ')'
        else -> return insertLiteral(edit, openChar.toString())
    }
    val start = minOf(edit.selectionStart, edit.selectionEnd)
    val end = maxOf(edit.selectionStart, edit.selectionEnd)
    val before = edit.text.substring(0, start)
    val selected = edit.text.substring(start, end)
    val after = edit.text.substring(end)
    val newText = before + openChar + selected + close + after
    val caret = start + 1 + selected.length
    return EditorEdit(newText, caret, caret)
}

/**
 * Insert a newline at the caret, copying the previous line's leading
 * whitespace so the next line starts at the same column. When the caret
 * sits immediately after an opening brace, the new line is indented by an
 * additional [INDENT_WIDTH] spaces; if the caret is sandwiched between
 * `{` and `}`, the closing brace is also pushed onto its own line at the
 * original indent so the user lands inside an empty block, ready to type.
 */
internal fun autoIndentOnEnter(edit: EditorEdit): EditorEdit {
    val start = minOf(edit.selectionStart, edit.selectionEnd)
    val end = maxOf(edit.selectionStart, edit.selectionEnd)
    val text = edit.text
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val baseIndent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
    val charBefore = if (start > 0) text[start - 1] else null
    val charAfter = if (end < text.length) text[end] else null
    val openedBlock = charBefore == '{'
    val splitBlock = openedBlock && charAfter == '}'

    val before = text.substring(0, start)
    val after = text.substring(end)
    return if (splitBlock) {
        val innerIndent = baseIndent + " ".repeat(INDENT_WIDTH)
        val inserted = "\n" + innerIndent + "\n" + baseIndent
        val caret = start + 1 + innerIndent.length
        EditorEdit(before + inserted + after, caret, caret)
    } else {
        val newIndent = if (openedBlock) baseIndent + " ".repeat(INDENT_WIDTH) else baseIndent
        val inserted = "\n" + newIndent
        val caret = start + inserted.length
        EditorEdit(before + inserted + after, caret, caret)
    }
}

/**
 * Handle a Tab keypress. With a multi-line selection, every line that
 * intersects the selection has [INDENT_WIDTH] spaces prepended and the
 * selection is grown to cover all of the inserted indentation. Otherwise
 * spaces are inserted at the caret to advance to the next column that is
 * a multiple of [INDENT_WIDTH], replacing any current selection.
 */
internal fun handleTab(edit: EditorEdit): EditorEdit {
    val start = minOf(edit.selectionStart, edit.selectionEnd)
    val end = maxOf(edit.selectionStart, edit.selectionEnd)
    return if (spansMultipleLines(edit.text, start, end)) {
        indentLines(edit, start, end)
    } else {
        val lineStart = edit.text.lastIndexOf('\n', start - 1) + 1
        val column = start - lineStart
        val spaces = INDENT_WIDTH - (column % INDENT_WIDTH)
        insertLiteral(edit, " ".repeat(spaces))
    }
}

/**
 * Handle a Shift+Tab keypress. With a multi-line selection, up to
 * [INDENT_WIDTH] leading spaces are removed from every line that
 * intersects the selection. Otherwise up to [INDENT_WIDTH] spaces are
 * removed from the start of the current line, even when the caret is not
 * itself in the leading whitespace.
 */
internal fun handleShiftTab(edit: EditorEdit): EditorEdit {
    // The single-line and multi-line cases produce the same result through
    // [dedentLines]: it walks every line that overlaps the selection and
    // strips up to [INDENT_WIDTH] leading spaces, which collapses to "trim
    // the current line" when the selection sits on a single line.
    val start = minOf(edit.selectionStart, edit.selectionEnd)
    val end = maxOf(edit.selectionStart, edit.selectionEnd)
    return dedentLines(edit, start, end)
}

/**
 * Replace the current selection (or insert at the caret when there is no
 * selection) with [literal]. The caret collapses to the end of the
 * inserted text.
 */
private fun insertLiteral(edit: EditorEdit, literal: String): EditorEdit {
    val start = minOf(edit.selectionStart, edit.selectionEnd)
    val end = maxOf(edit.selectionStart, edit.selectionEnd)
    val newText = edit.text.substring(0, start) + literal + edit.text.substring(end)
    val caret = start + literal.length
    return EditorEdit(newText, caret, caret)
}

/**
 * Returns `true` when the half-open range `[start, end)` of [text]
 * straddles at least one newline — i.e. the user has selected content on
 * more than one line and Tab/Shift+Tab should behave as block (de)indent.
 */
private fun spansMultipleLines(text: String, start: Int, end: Int): Boolean {
    if (start == end) return false
    return text.substring(start, end).contains('\n')
}

/** Prepend [INDENT_WIDTH] spaces to every line that overlaps `[start, end]`. */
private fun indentLines(edit: EditorEdit, start: Int, end: Int): EditorEdit {
    val text = edit.text
    val firstLineStart = text.lastIndexOf('\n', start - 1) + 1
    val indent = " ".repeat(INDENT_WIDTH)
    val builder = StringBuilder()
    builder.append(text, 0, firstLineStart)
    var cursor = firstLineStart
    var addedBeforeStart = 0
    var addedTotal = 0
    while (cursor <= end) {
        builder.append(indent)
        addedTotal += indent.length
        if (cursor < start) addedBeforeStart += indent.length
        val nextNewline = text.indexOf('\n', cursor)
        if (nextNewline == -1 || nextNewline >= end) {
            // Last touched line — copy through to end-of-line and stop.
            val sliceEnd = if (nextNewline == -1) text.length else nextNewline
            builder.append(text, cursor, sliceEnd)
            cursor = sliceEnd
            break
        }
        builder.append(text, cursor, nextNewline + 1)
        cursor = nextNewline + 1
    }
    builder.append(text, cursor, text.length)
    val newStart = start + addedBeforeStart
    val newEnd = end + addedTotal
    return EditorEdit(builder.toString(), newStart, newEnd)
}

/**
 * Remove up to [INDENT_WIDTH] leading spaces from every line that
 * overlaps `[start, end]`, keeping the selection anchored to the same
 * logical content (selection bounds are clamped to the new line starts
 * when their line lost characters in front of them).
 */
private fun dedentLines(edit: EditorEdit, start: Int, end: Int): EditorEdit {
    val text = edit.text
    val firstLineStart = text.lastIndexOf('\n', start - 1) + 1
    val builder = StringBuilder()
    builder.append(text, 0, firstLineStart)
    var cursor = firstLineStart
    var removedBeforeStart = 0
    var removedTotal = 0
    val effectiveEnd = if (start == end) start else end
    while (cursor <= effectiveEnd) {
        // Count the leading spaces we are about to drop on this line.
        var drop = 0
        while (drop < INDENT_WIDTH &&
            cursor + drop < text.length &&
            text[cursor + drop] == ' '
        ) drop++
        // Track how many of the dropped spaces sat in front of each
        // selection endpoint so we can shift them back accordingly.
        val droppedBeforeStart = minOf(drop, maxOf(0, start - cursor))
        removedBeforeStart += droppedBeforeStart
        removedTotal += drop
        val nextNewline = text.indexOf('\n', cursor)
        if (nextNewline == -1 || nextNewline >= effectiveEnd) {
            val sliceEnd = if (nextNewline == -1) text.length else nextNewline
            builder.append(text, cursor + drop, sliceEnd)
            cursor = sliceEnd
            break
        }
        builder.append(text, cursor + drop, nextNewline + 1)
        cursor = nextNewline + 1
    }
    builder.append(text, cursor, text.length)
    val newStart = (start - removedBeforeStart).coerceAtLeast(firstLineStart)
    val newEnd = (end - removedTotal).coerceAtLeast(newStart)
    return EditorEdit(builder.toString(), newStart, newEnd)
}

/**
 * Detect a single-character `{` or `(` insertion between the [previous]
 * and [next] editor states and rewrite the change to also insert the
 * matching closing character, leaving the caret between the pair.
 *
 * Returns `null` when the change is not a plain insertion of a single
 * `{` or `(` at the previous selection — for example when the user typed
 * a different character, deleted text, pasted multiple characters, or
 * only moved the caret. In those cases the caller should accept [next]
 * as-is. This drives auto-pairing through the normal text-field change
 * pipeline so it works regardless of how the character was delivered
 * (hardware key, IME, paste of a single character, etc.).
 */
internal fun detectAutoPairInsert(previous: EditorEdit, next: EditorEdit): EditorEdit? {
    val prevStart = minOf(previous.selectionStart, previous.selectionEnd)
    val prevEnd = maxOf(previous.selectionStart, previous.selectionEnd)
    val expectedLength = previous.text.length - (prevEnd - prevStart) + 1
    if (next.text.length != expectedLength) return null
    // The new caret should sit right after the inserted character.
    if (next.selectionStart != next.selectionEnd) return null
    val caret = next.selectionStart
    if (caret != prevStart + 1) return null
    // The prefix before the previous selection must be unchanged.
    if (caret - 1 != prevStart) return null
    if (next.text.substring(0, prevStart) != previous.text.substring(0, prevStart)) return null
    // The suffix after the previous selection must be unchanged too.
    val suffixStart = caret
    if (next.text.substring(suffixStart) != previous.text.substring(prevEnd)) return null
    val inserted = next.text[prevStart]
    if (inserted != '{' && inserted != '(') return null
    // Re-run the insertion through `autoPairOpen` against the *previous*
    // state so a non-empty selection ends up wrapped, not replaced.
    return autoPairOpen(previous, inserted)
}

/**
 * Detect a "type-through" of an existing closing bracket. When the caret
 * has no selection and the user types `}` or `)` while that exact
 * character already sits immediately to the right of the caret, the
 * editor should simply move past it instead of inserting a duplicate —
 * matching the standard IDE behaviour of typing through an auto-inserted
 * closer.
 *
 * Returns the post-edit state with the caret advanced by one and the
 * text unchanged from [previous] when the diff matches that pattern, or
 * `null` otherwise. Mirrors the constraints of [detectAutoPairInsert]:
 * single-character insertion, caret-only (no selection in either state),
 * and the prefix/suffix around the caret unchanged.
 */
internal fun detectCloseSkip(previous: EditorEdit, next: EditorEdit): EditorEdit? {
    // Only consider caret-only states — wrapping a selection with `}`
    // doesn't translate to a "skip", and a non-collapsed `next` selection
    // means the user did something other than type a single character.
    if (previous.selectionStart != previous.selectionEnd) return null
    if (next.selectionStart != next.selectionEnd) return null
    val prevCaret = previous.selectionStart
    val nextCaret = next.selectionStart
    if (nextCaret != prevCaret + 1) return null
    if (next.text.length != previous.text.length + 1) return null
    // Prefix up to the caret unchanged.
    if (next.text.substring(0, prevCaret) != previous.text.substring(0, prevCaret)) return null
    // Suffix after the inserted character unchanged.
    if (next.text.substring(nextCaret) != previous.text.substring(prevCaret)) return null
    val inserted = next.text[prevCaret]
    if (inserted != '}' && inserted != ')') return null
    // Only skip if the same character is already sitting where the user
    // typed — i.e. they're typing through an existing closer.
    if (prevCaret >= previous.text.length) return null
    if (previous.text[prevCaret] != inserted) return null
    // Result: keep the previous text intact, just advance the caret.
    return EditorEdit(previous.text, prevCaret + 1, prevCaret + 1)
}
