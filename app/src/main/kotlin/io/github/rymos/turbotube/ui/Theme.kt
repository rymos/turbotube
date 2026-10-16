package io.github.rymos.turbotube.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import io.github.rymos.turbotube.AppContainer

val Accent = Color(0xFFFF7A1A)
val Background = Color(0xFF0E0F13)

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer") }

@Composable
fun TurbotubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondary = Accent,
            background = Background,
            onBackground = Color(0xFFECECEC),
            surface = Color(0xFF1A1C22),
            onSurface = Color(0xFFECECEC),
            surfaceVariant = Color(0xFF262932),
            onSurfaceVariant = Color(0xFFB8BCC6),
            border = Accent,
        ),
        content = content,
    )
}
