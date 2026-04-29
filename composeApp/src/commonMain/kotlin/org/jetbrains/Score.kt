package org.jetbrains

import kscript.KToken
import kscript.KTokenInput
import kscript.KTokenType
import kscript.TextInput

/**
 * Token types treated as identifiers when counting tokens for the
 * level score.
 */
private val IDENTIFIER_TOKEN_TYPES = setOf(
    KTokenType.IDENTIFIER,
    KTokenType.FIELD_IDENTIFIER,
)

/**
 * Token types treated as literals when counting tokens for the level
 * score. The boolean and `null` keywords are included here because
 * they are literal values rather than language constructs.
 */
private val LITERAL_TOKEN_TYPES = setOf(
    KTokenType.INTEGER_LITERAL,
    KTokenType.FLOAT_LITERAL,
    KTokenType.CHARACTER_LITERAL,
    KTokenType.REGULAR_STRING_PART,
    KTokenType.TRUE_KEYWORD,
    KTokenType.FALSE_KEYWORD,
    KTokenType.NULL_KEYWORD,
)

/**
 * Counts identifiers, literals, and Kotlin keywords in [code] using
 * the kscript lexer. Whitespace, comments, punctuation, and operators
 * are ignored.
 */
fun countCodeTokens(code: String): Int {
    val input = KTokenInput(TextInput(code))
    var count = 0
    while (true) {
        val token: KToken = input.next()
        val type = token.type
        if (type == KTokenType.EOF) break
        if (type in IDENTIFIER_TOKEN_TYPES) {
            count++
            continue
        }
        if (type in LITERAL_TOKEN_TYPES) {
            count++
            continue
        }
        // Any other `_KEYWORD` token is a Kotlin keyword (e.g. `fun`,
        // `val`, `if`, `while`). The boolean and `null` literal
        // keywords were already counted above.
        if (type.name.endsWith("_KEYWORD")) {
            count++
        }
    }
    return count
}

/**
 * Computes the level score as `100 * target / tokens`, clamped to
 * `[0, 100]`. Returns `0` when [tokens] is `0` so that empty programs
 * never receive a passing grade.
 */
fun computeScore(target: Int, tokens: Int): Int {
    if (tokens <= 0) return 0
    val raw = 100 * target / tokens
    return raw.coerceIn(0, 100)
}

/**
 * Maps a score in the range `[0, 100]` to a US university letter
 * grade.
 *
 * The boundaries follow the standard plus/minus scale:
 *  - A+: 97–100
 *  - A:  93–96
 *  - A-: 90–92
 *  - B+: 87–89
 *  - B:  83–86
 *  - B-: 80–82
 *  - C+: 77–79
 *  - C:  73–76
 *  - C-: 70–72
 *  - D+: 67–69
 *  - D:  63–66
 *  - D-: 60–62
 *  - F:   0–59
 */
fun letterGrade(score: Int): String = when {
    score >= 97 -> "A+"
    score >= 93 -> "A"
    score >= 90 -> "A-"
    score >= 87 -> "B+"
    score >= 83 -> "B"
    score >= 80 -> "B-"
    score >= 77 -> "C+"
    score >= 73 -> "C"
    score >= 70 -> "C-"
    score >= 67 -> "D+"
    score >= 63 -> "D"
    score >= 60 -> "D-"
    else -> "F"
}
