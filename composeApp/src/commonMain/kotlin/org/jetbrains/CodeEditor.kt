package org.jetbrains

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    kFile: KFile?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CodeEditorColors = defaultCodeEditorColors(),
) {
    val baseStyle = LocalTextStyle.current.merge(
        TextStyle(
            fontFamily = FontFamily.Monospace,
            color = colors.text,
        ),
    )
    val transformation = remember(kFile, colors) {
        SyntaxHighlightTransformation(kFile, colors)
    }
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = enabled,
            textStyle = baseStyle,
            cursorBrush = SolidColor(colors.text),
            visualTransformation = transformation,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            decorationBox = { innerTextField ->
                if (code.isEmpty()) {
                    Text(
                        text = "Code",
                        style = baseStyle.copy(color = colors.comment),
                    )
                }
                innerTextField()
            },
        )
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
