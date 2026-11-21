// Ivory & Ink – Farben, Schriften und Formen 1:1 aus core/ui/design/Theme.kt der Android-App.
// Für Etappe 0 kopiert; später kommt das Original über ein gemeinsames KMP-Modul.
package de.marne.spike

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.spike.res.Res
import de.marne.spike.res.sans_medium
import de.marne.spike.res.sans_regular
import de.marne.spike.res.sans_semibold
import de.marne.spike.res.serif_medium
import de.marne.spike.res.serif_semibold
import org.jetbrains.compose.resources.Font

@Immutable
data class AppPalette(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val ink: Color,
    val onInk: Color,
    val brass: Color,
    val green: Color,
    val wine: Color,
    val blue: Color,
    val isDark: Boolean,
)

val LightPalette = AppPalette(
    bg = Color(0xFFF4F1EA), surface = Color(0xFFFBF9F5), surface2 = Color(0xFFECE7DD),
    surface3 = Color(0xFFE2DBCE), line = Color(0xFFE0D9CC), text = Color(0xFF1B1A17),
    muted = Color(0xFF6B655B), faint = Color(0xFF9C958A), ink = Color(0xFF1B1A17),
    onInk = Color(0xFFF4F1EA), brass = Color(0xFF8E6F43), green = Color(0xFF34503F),
    wine = Color(0xFF7A2E2E), blue = Color(0xFF3B4D63), isDark = false,
)

val DarkPalette = AppPalette(
    bg = Color(0xFF121110), surface = Color(0xFF1A1917), surface2 = Color(0xFF24221F),
    surface3 = Color(0xFF2E2B27), line = Color(0xFF2F2C27), text = Color(0xFFEDE8DF),
    muted = Color(0xFFA59E92), faint = Color(0xFF746E65), ink = Color(0xFFEDE8DF),
    onInk = Color(0xFF121110), brass = Color(0xFFC9AB7A), green = Color(0xFF93B39C),
    wine = Color(0xFFCF8F89), blue = Color(0xFF9DB0C6), isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

private fun scheme(p: AppPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.ink, onPrimary = p.onInk, primaryContainer = p.surface3, onPrimaryContainer = p.text,
        secondary = p.brass, onSecondary = p.onInk, secondaryContainer = p.surface2, onSecondaryContainer = p.text,
        tertiary = p.green, onTertiary = p.onInk, background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent, error = p.wine, onError = p.onInk,
        outline = p.faint, outlineVariant = p.line,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface3,
    )
}

/** Ziffern in der Serifenschrift sollen gleich hoch stehen (keine Mediävalziffern). */
private const val LINING = "lnum, pnum"

@Composable
private fun typography(): Typography {
    val serif = FontFamily(
        Font(Res.font.serif_medium, FontWeight.Medium),
        Font(Res.font.serif_semibold, FontWeight.SemiBold),
        Font(Res.font.serif_semibold, FontWeight.Bold),
    )
    val sans = FontFamily(
        Font(Res.font.sans_regular, FontWeight.Normal),
        Font(Res.font.sans_medium, FontWeight.Medium),
        Font(Res.font.sans_semibold, FontWeight.SemiBold),
        Font(Res.font.sans_semibold, FontWeight.Bold),
    )
    return Typography(
        displaySmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = LINING),
        headlineMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp, fontFeatureSettings = LINING),
        headlineSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp, fontFeatureSettings = LINING),
        titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp),
        titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun IvoryInkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p), shapes = shapes, typography = typography()) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
