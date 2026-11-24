package br.malhaviva.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Space Grotesk e Inter ainda nao estao embarcadas (download pendente de aprovacao): usa a fonte do sistema.
val MalhaVivaTypography =
    Typography(
        displayMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )

// Algarismos tabulares: o horario nao pula quando muda.
val TimeTextStyle =
    TextStyle(
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
