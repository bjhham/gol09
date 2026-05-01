package org.jetbrains

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import gol09.composeapp.generated.resources.Res
import gol09.composeapp.generated.resources.VT323_Regular
import org.jetbrains.compose.resources.Font

/**
 * Application-wide [MaterialTheme] wrapper.
 *
 * Uses a high-contrast dark color scheme with strong, saturated accent colors.
 * The intent is to give the syntax-highlighted code editor (and the rest of
 * the UI) a punchier palette than the default Material 3 baseline so that
 * keywords, literals, and other token categories stand out clearly against
 * the editor's dark background.
 *
 * Also overrides the default [Typography] so every text style picks up the
 * pixel-art `VT323` font shipped in `composeResources/font/`. Using the font
 * project-wide (rather than per call site) keeps the retro arcade feel
 * consistent across the editor, status bar, dialogs, and overlays.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HighContrastDarkColors,
        typography = appTypography(),
        content = content,
    )
}

/**
 * Builds a [Typography] whose every text style is rebased onto the
 * `VT323-Regular` font family.
 *
 * We start from Material 3's default [Typography] so that font sizes,
 * line heights, and letter spacing keep their carefully tuned defaults,
 * and only swap in our custom [FontFamily]. This gives the whole UI a
 * consistent retro look without having to enumerate every individual
 * text style.
 */
@Composable
private fun appTypography(): Typography {
    val vt323 = FontFamily(Font(Res.font.VT323_Regular))
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = vt323),
        displayMedium = base.displayMedium.copy(fontFamily = vt323),
        displaySmall = base.displaySmall.copy(fontFamily = vt323),
        headlineLarge = base.headlineLarge.copy(fontFamily = vt323),
        headlineMedium = base.headlineMedium.copy(fontFamily = vt323),
        headlineSmall = base.headlineSmall.copy(fontFamily = vt323),
        titleLarge = base.titleLarge.copy(fontFamily = vt323),
        titleMedium = base.titleMedium.copy(fontFamily = vt323),
        titleSmall = base.titleSmall.copy(fontFamily = vt323),
        bodyLarge = base.bodyLarge.copy(fontFamily = vt323),
        bodyMedium = base.bodyMedium.copy(fontFamily = vt323),
        bodySmall = base.bodySmall.copy(fontFamily = vt323),
        labelLarge = base.labelLarge.copy(fontFamily = vt323),
        labelMedium = base.labelMedium.copy(fontFamily = vt323),
        labelSmall = base.labelSmall.copy(fontFamily = vt323),
    )
}

/**
 * High-contrast dark Material 3 color scheme.
 *
 * Roles are picked so that the [CodeEditor] palette derived from
 * `MaterialTheme.colorScheme` (see [defaultCodeEditorColors]) maps to
 * vivid, easily distinguishable hues:
 *
 * - `primary`        → keywords (vivid cyan)
 * - `secondary`      → strings   (vivid green)
 * - `tertiary`       → numbers   (vivid orange)
 * - `outline`        → comments  (muted grey, still legible on the dark surface)
 * - `error`          → invalid tokens (vivid red)
 * - `onSurface`      → identifiers / default text (near-white for max contrast)
 *
 * Background and surface tones are deliberately near-black so that the
 * accent colors pop. Surfaces are nudged slightly lighter than the
 * background to give the editor a subtle frame.
 */
private val HighContrastDarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),            // vivid cyan — keywords
    onPrimary = Color(0xFF00131E),
    primaryContainer = Color(0xFF004C66),
    onPrimaryContainer = Color(0xFFB7EAFF),

    secondary = Color(0xFF7CFC8E),          // vivid green — strings
    onSecondary = Color(0xFF002910),
    secondaryContainer = Color(0xFF005222),
    onSecondaryContainer = Color(0xFFB6FFC4),

    tertiary = Color(0xFFFFB74D),           // vivid orange — numbers
    onTertiary = Color(0xFF2E1500),
    tertiaryContainer = Color(0xFF6A3A00),
    onTertiaryContainer = Color(0xFFFFE0B8),

    error = Color(0xFFFF5370),              // vivid red — invalid tokens
    onError = Color(0xFF2A0008),
    errorContainer = Color(0xFF7A0018),
    onErrorContainer = Color(0xFFFFD6DD),

    background = Color(0xFF0F1115),         // near-black canvas
    onBackground = Color(0xFFEDEFF3),

    surface = Color(0xFF161A21),            // editor background
    onSurface = Color(0xFFF2F4F8),          // identifiers / default text

    surfaceVariant = Color(0xFF2A2F38),     // status-bar background
    onSurfaceVariant = Color(0xFFCBD2DC),   // operators / punctuation

    outline = Color(0xFF8A93A0),            // comments — dim but legible
    outlineVariant = Color(0xFF3A4150),

    inverseSurface = Color(0xFFEDEFF3),
    inverseOnSurface = Color(0xFF161A21),
    inversePrimary = Color(0xFF006782),
)
