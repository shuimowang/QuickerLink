package app.quickerlink.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF146B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F1D5),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF765A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA1),
    onSecondaryContainer = Color(0xFF251A00),
    tertiary = Color(0xFF9A452E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3B0901),
    background = Color(0xFFF8FAF7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF8FAF7),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE0E4E0),
    onSurfaceVariant = Color(0xFF414944),
    outline = Color(0xFF717974),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF99D5BA),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFB5F1D5),
    secondary = Color(0xFFEAC248),
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = Color(0xFF594400),
    onSecondaryContainer = Color(0xFFFFDEA1),
    tertiary = Color(0xFFFFB5A1),
    onTertiary = Color(0xFF5D1908),
    tertiaryContainer = Color(0xFF7C2E1A),
    onTertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF414944),
    onSurfaceVariant = Color(0xFFC1C9C3),
    outline = Color(0xFF8B938E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun QuickerLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
