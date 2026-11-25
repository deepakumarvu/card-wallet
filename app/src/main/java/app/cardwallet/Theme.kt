package app.cardwallet

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fraunces (OFL): a soft, high-contrast serif for big numbers and titles. */
private fun fraunces(weight: Int) = Font(
    R.font.fraunces, FontWeight(weight),
    // Weight only: the optical-size and SOFT axes made Android draw the text blank.
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope (OFL): a clean geometric sans for everything else. */
private fun manrope(weight: Int) = Font(R.font.manrope, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Display = FontFamily(fraunces(400), fraunces(500), fraunces(600), fraunces(700))
val Body = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Champagne gold: the one accent, used sparingly. */
val Gold = Color(0xFFE3C38C)

private val dark = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF1C1408),
    primaryContainer = Color(0xFF2A2217), onPrimaryContainer = Color(0xFFF3DFB8),
    secondaryContainer = Color(0xFF1C1C20), onSecondaryContainer = Color(0xFFECEAE4),
    background = Color.Black, onBackground = Color(0xFFF4F1EA),
    surface = Color.Black, onSurface = Color(0xFFF4F1EA),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF111114), surfaceContainerHigh = Color(0xFF18181C), surfaceContainerHighest = Color(0xFF222227),
    onSurfaceVariant = Color(0xFF8F8C86), outline = Color(0xFF34333A), outlineVariant = Color(0xFF232228),
    error = Color(0xFFFF8A80),
)

private val light = lightColorScheme(
    primary = Color(0xFF8A6A2F), onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E6CC), onPrimaryContainer = Color(0xFF2E2208),
    secondaryContainer = Color(0xFFECEAE6), onSecondaryContainer = Color(0xFF1B1A18),
    background = Color(0xFFF7F5F1), onBackground = Color(0xFF151412),
    surface = Color(0xFFF7F5F1), onSurface = Color(0xFF151412),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCFBF9),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0EEEA), surfaceContainerHighest = Color(0xFFE7E4DF),
    onSurfaceVariant = Color(0xFF6A665F), outline = Color(0xFFCBC6BD), outlineVariant = Color(0xFFE4E0D9),
    error = Color(0xFFC6382F),
)

private val base = Typography()
private fun TextStyle.body() = copy(fontFamily = Body)
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = Body, fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
    labelLarge = base.labelLarge.copy(fontFamily = Body, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Body, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = Body, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
)

/** Small spaced caps label: "BY CATEGORY". */
val sectionLabel = TextStyle(fontFamily = Body, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
