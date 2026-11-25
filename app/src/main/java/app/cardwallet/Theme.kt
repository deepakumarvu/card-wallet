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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val dark = darkColorScheme(
    primary = Color(0xFF3DDC97), onPrimary = Color(0xFF00281B),
    primaryContainer = Color(0xFF0F3D2C), onPrimaryContainer = Color(0xFFB4F5D6),
    secondaryContainer = Color(0xFF262A33), onSecondaryContainer = Color(0xFFE3E6EC),
    background = Color(0xFF0E0F13), onBackground = Color(0xFFEDEFF3),
    surface = Color(0xFF0E0F13), onSurface = Color(0xFFEDEFF3),
    surfaceContainerLowest = Color(0xFF0A0B0E), surfaceContainerLow = Color(0xFF14161B),
    surfaceContainer = Color(0xFF181A20), surfaceContainerHigh = Color(0xFF1E2128), surfaceContainerHighest = Color(0xFF262A32),
    onSurfaceVariant = Color(0xFF9AA0AA), outline = Color(0xFF3A3F49), outlineVariant = Color(0xFF2A2E36),
    error = Color(0xFFFF7A7A),
)

private val light = lightColorScheme(
    primary = Color(0xFF0E9F6E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E6), onPrimaryContainer = Color(0xFF00382A),
    secondaryContainer = Color(0xFFE8EAEF), onSecondaryContainer = Color(0xFF1B1E24),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF14161B),
    surface = Color(0xFFF5F6F8), onSurface = Color(0xFF14161B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF0F3), surfaceContainerHighest = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(0xFF626874), outline = Color(0xFFC9CDD4), outlineVariant = Color(0xFFE1E4E9),
    error = Color(0xFFD93B3B),
)

private val base = Typography()
private val typography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
)

/** Small caps-style section label: "BY CATEGORY". */
val sectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
