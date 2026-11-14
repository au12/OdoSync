package cc.adamu.odosync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand palette taken from the launcher icon: deep navy gauge, orange needle.
private val Light = lightColorScheme(
    primary = Color(0xFF235A8C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E5FF), onPrimaryContainer = Color(0xFF0B2540),
    secondary = Color(0xFF53606F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F5), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFFB4441F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCF), onTertiaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF161C23),
    surface = Color(0xFFF6F8FB), onSurface = Color(0xFF161C23),
    surfaceVariant = Color(0xFFDDE3EC), onSurfaceVariant = Color(0xFF47505C),
    outline = Color(0xFF737C88), outlineVariant = Color(0xFFC9D0DA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFEAEEF4), surfaceContainerHigh = Color(0xFFE4E9F0),
    surfaceContainerHighest = Color(0xFFDEE4EC), surfaceBright = Color(0xFFF6F8FB),
    surfaceDim = Color(0xFFD6DCE4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DCBFF), onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF1B3A5C), onPrimaryContainer = Color(0xFFD4E5FF),
    secondary = Color(0xFFBAC8DA), onSecondary = Color(0xFF243140),
    secondaryContainer = Color(0xFF3A4757), onSecondaryContainer = Color(0xFFD7E3F5),
    tertiary = Color(0xFFFFB59C), onTertiary = Color(0xFF5C1900),
    tertiaryContainer = Color(0xFF7F2C0D), onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF0D141C), onBackground = Color(0xFFE1E6ED),
    surface = Color(0xFF0D141C), onSurface = Color(0xFFE1E6ED),
    surfaceVariant = Color(0xFF424B57), onSurfaceVariant = Color(0xFFC1C8D2),
    outline = Color(0xFF8B939E), outlineVariant = Color(0xFF333C47),
    surfaceContainerLowest = Color(0xFF080E15), surfaceContainerLow = Color(0xFF131B24),
    surfaceContainer = Color(0xFF18212B), surfaceContainerHigh = Color(0xFF212B36),
    surfaceContainerHighest = Color(0xFF2B3541), surfaceBright = Color(0xFF323C47),
    surfaceDim = Color(0xFF0D141C),
)

private val Base = Typography()
private val AppTypography = Base.copy(
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Tabular figures so the odometer doesn't jitter as digits change. */
val OdometerStyle = TextStyle(
    fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum", letterSpacing = (-1).sp,
)

@Composable
fun OdoSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

enum class Tone { GOOD, BUSY, BAD, IDLE }

@Composable
@ReadOnlyComposable
fun toneColor(tone: Tone): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (tone) {
        Tone.GOOD -> if (dark) Color(0xFF6FD89A) else Color(0xFF1E8E4F)
        Tone.BUSY -> if (dark) Color(0xFFFFC56E) else Color(0xFFB26A00)
        Tone.BAD -> MaterialTheme.colorScheme.error
        Tone.IDLE -> MaterialTheme.colorScheme.outline
    }
}
