package kr.yoosi.baroconvert

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF5754D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF),
    onPrimaryContainer = Color(0xFF24205F),
    secondary = Color(0xFF3266D5),
    tertiary = Color(0xFF00856F),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF191A20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191A20),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF5B5E69),
    outline = Color(0xFF8B8E99),
    outlineVariant = Color(0xFFDDE0E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCB8FF),
    onPrimary = Color(0xFF282266),
    primaryContainer = Color(0xFF3D397E),
    onPrimaryContainer = Color(0xFFE6E4FF),
    secondary = Color(0xFFADC6FF),
    tertiary = Color(0xFF63DCC3),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE6E7EC),
    surface = Color(0xFF181A21),
    onSurface = Color(0xFFE6E7EC),
    surfaceVariant = Color(0xFF242730),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF91939E),
    outlineVariant = Color(0xFF3E414A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun BaroConvertTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

    if (!view.isInEditMode && activity != null) {
        SideEffect {
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = AppShapes,
        content = content,
    )
}
