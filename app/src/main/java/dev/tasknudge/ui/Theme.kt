package dev.tasknudge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(primary = Color(0xFF006A60), onPrimary = Color.White, primaryContainer = Color(0xFF9EF2DF), secondary = Color(0xFF4A635E), background = Color(0xFFF5FAF7), surface = Color(0xFFF5FAF7))
private val DarkColors = darkColorScheme(primary = Color(0xFF82D5C3), onPrimary = Color(0xFF003730), primaryContainer = Color(0xFF005047), secondary = Color(0xFFB1CCC4), background = Color(0xFF0E1513), surface = Color(0xFF0E1513))

@Composable
fun TaskNudgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
