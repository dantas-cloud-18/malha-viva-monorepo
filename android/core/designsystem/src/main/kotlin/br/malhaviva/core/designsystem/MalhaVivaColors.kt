package br.malhaviva.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class StatusColors(
    val onTime: Color,
    val delayed: Color,
    val interrupted: Color,
    val noSignal: Color,
    val lowConfidence: Color,
)

@Immutable
data class MalhaVivaColors(
    val surface: Color,
    val primary: Color,
    val accent: Color,
    val text: Color,
    val textMuted: Color,
    val live: Color,
    val status: StatusColors,
)

val LightMalhaVivaColors =
    MalhaVivaColors(
        surface = Color(0xFFEEF4F0),
        primary = Color(0xFF0B2B22),
        accent = Color(0xFF2E7D4F),
        text = Color(0xFF0E1A15),
        textMuted = Color(0xFF4F6A5F),
        live = Color(0xFF2E7D4F),
        status =
            StatusColors(
                onTime = Color(0xFF005F8A),
                delayed = Color(0xFF8A5300),
                interrupted = Color(0xFFC62828),
                noSignal = Color(0xFF5F6B66),
                lowConfidence = Color(0xFF6A4C9C),
            ),
    )

val DarkMalhaVivaColors =
    MalhaVivaColors(
        surface = Color(0xFF0F392D),
        primary = Color(0xFFE1E5E7),
        accent = Color(0xFF48D467),
        text = Color(0xFFF2F5F3),
        textMuted = Color(0xFF9FB3AA),
        live = Color(0xFF3BE666),
        status =
            StatusColors(
                onTime = Color(0xFF5CC8FF),
                delayed = Color(0xFFFFB74D),
                interrupted = Color(0xFFFF8A80),
                noSignal = Color(0xFF9AA6A0),
                lowConfidence = Color(0xFFB39DDB),
            ),
    )

val LocalMalhaVivaColors = staticCompositionLocalOf { LightMalhaVivaColors }
