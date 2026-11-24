package br.malhaviva.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

object MalhaVivaSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

val MalhaVivaShapes =
    Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
    )

object MalhaVivaTheme {
    val colors: MalhaVivaColors
        @Composable get() = LocalMalhaVivaColors.current
}

@Composable
fun MalhaVivaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkMalhaVivaColors else LightMalhaVivaColors
    CompositionLocalProvider(LocalMalhaVivaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(darkTheme),
            typography = MalhaVivaTypography,
            shapes = MalhaVivaShapes,
            content = content,
        )
    }
}

private fun MalhaVivaColors.toColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = surface,
            secondary = accent,
            background = surface,
            onBackground = text,
            surface = surface,
            onSurface = text,
            onSurfaceVariant = textMuted,
            error = status.interrupted,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = surface,
            secondary = accent,
            background = surface,
            onBackground = text,
            surface = surface,
            onSurface = text,
            onSurfaceVariant = textMuted,
            error = status.interrupted,
        )
    }
