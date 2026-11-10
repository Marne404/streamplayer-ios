// Ivory & Ink – aus dem Design-System übernommen (Quelle: GlowUp).
package de.marne.streamplayer.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.core.R

/**
 * „Old Money“-Palette: warmes Off-White und Off-Black, dazu sehr sparsam Messing,
 * Tannengrün und Bordeaux. Keine knalligen Farben.
 */
@Immutable
data class AppPalette(
    val bg: Color,
    val surface: Color,      // Karten
    val surface2: Color,     // eingelassene Flächen, Tracks
    val surface3: Color,     // stärker abgesetzt
    val line: Color,         // Haarlinien
    val text: Color,
    val muted: Color,
    val faint: Color,
    val ink: Color,          // Primärfarbe (Buttons, aktive Elemente)
    val onInk: Color,
    val brass: Color,        // Akzent für Score/Highlights
    val green: Color,
    val wine: Color,
    val blue: Color,
    val isDark: Boolean,
)

val LightPalette = AppPalette(
    bg = Color(0xFFF4F1EA),
    surface = Color(0xFFFBF9F5),
    surface2 = Color(0xFFECE7DD),
    surface3 = Color(0xFFE2DBCE),
    line = Color(0xFFE0D9CC),
    text = Color(0xFF1B1A17),
    muted = Color(0xFF6B655B),
    faint = Color(0xFF9C958A),
    ink = Color(0xFF1B1A17),
    onInk = Color(0xFFF4F1EA),
    brass = Color(0xFF8E6F43),
    green = Color(0xFF34503F),
    wine = Color(0xFF7A2E2E),
    blue = Color(0xFF3B4D63),
    isDark = false,
)

val DarkPalette = AppPalette(
    bg = Color(0xFF121110),
    surface = Color(0xFF1A1917),
    surface2 = Color(0xFF24221F),
    surface3 = Color(0xFF2E2B27),
    line = Color(0xFF2F2C27),
    text = Color(0xFFEDE8DF),
    muted = Color(0xFFA59E92),
    faint = Color(0xFF746E65),
    ink = Color(0xFFEDE8DF),
    onInk = Color(0xFF121110),
    brass = Color(0xFFC9AB7A),
    green = Color(0xFF93B39C),
    wine = Color(0xFFCF8F89),
    blue = Color(0xFF9DB0C6),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Zugriff wie früher: `Palette.text` – liest die Farben des aktuellen Themes. */
object Palette {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
    val surface3: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface3
    val line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
    val text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint
    val ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
    val onInk: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onInk
    val brass: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.brass
    val green: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val wine: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.wine
    val blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalPalette.current.isDark
}

private fun scheme(p: AppPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.ink,
        onPrimary = p.onInk,
        primaryContainer = p.surface3,
        onPrimaryContainer = p.text,
        inversePrimary = p.brass,
        secondary = p.brass,
        onSecondary = p.onInk,
        secondaryContainer = p.surface2,
        onSecondaryContainer = p.text,
        tertiary = p.green,
        onTertiary = p.onInk,
        tertiaryContainer = p.surface2,
        onTertiaryContainer = p.text,
        background = p.bg,
        onBackground = p.text,
        surface = p.bg,
        onSurface = p.text,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        inverseSurface = p.ink,
        inverseOnSurface = p.onInk,
        error = p.wine,
        onError = p.onInk,
        errorContainer = p.surface2,
        onErrorContainer = p.wine,
        outline = p.faint,
        outlineVariant = p.line,
        scrim = Color.Black,
        surfaceBright = p.surface,
        surfaceDim = p.surface2,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.surface3,
    )
}

val Serif = FontFamily(
    Font(R.font.serif_medium, FontWeight.Medium),
    Font(R.font.serif_semibold, FontWeight.SemiBold),
    Font(R.font.serif_semibold, FontWeight.Bold),
)

val Sans = FontFamily(
    Font(R.font.sans_regular, FontWeight.Normal),
    Font(R.font.sans_medium, FontWeight.Medium),
    Font(R.font.sans_semibold, FontWeight.SemiBold),
    Font(R.font.sans_semibold, FontWeight.Bold),
)

/** Ziffern in der Serifenschrift sollen gleich hoch stehen (keine Mediävalziffern). */
private const val LINING = "lnum, pnum"

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 88.sp, lineHeight = 88.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = LINING),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 60.sp, lineHeight = 62.sp, letterSpacing = (-1).sp, fontFeatureSettings = LINING),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = LINING),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.4).sp, fontFeatureSettings = LINING),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp, fontFeatureSettings = LINING),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp, fontFeatureSettings = LINING),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** theme: "system" | "light" | "dark" */
@Composable
fun isAppDark(theme: String): Boolean = when (theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun IvoryInkTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialExpressiveTheme(
            colorScheme = scheme(p),
            // Ruhige Standard-Bewegung statt verspielter Federn
            motionScheme = MotionScheme.standard(),
            shapes = shapes,
            typography = typo,
        ) {
            // Standard-Textfarbe auch außerhalb von Scaffold/Surface (z. B. Onboarding, Dialoge)
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides p.text, content = content)
        }
    }
}
