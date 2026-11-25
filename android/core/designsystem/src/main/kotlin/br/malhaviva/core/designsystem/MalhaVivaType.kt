package br.malhaviva.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun variable(
    resId: Int,
    weight: FontWeight,
) = Font(resId, weight, variationSettings = FontVariation.Settings(weight, FontStyle.Normal))

val SpaceGroteskFamily =
    FontFamily(
        variable(R.font.space_grotesk, FontWeight.SemiBold),
        variable(R.font.space_grotesk, FontWeight.Bold),
    )

val InterFamily =
    FontFamily(
        variable(R.font.inter, FontWeight.Normal),
        variable(R.font.inter, FontWeight.Medium),
        variable(R.font.inter, FontWeight.SemiBold),
    )

val MalhaVivaTypography =
    Typography(
        displayMedium =
            TextStyle(
                fontFamily = SpaceGroteskFamily,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            ),
        titleLarge =
            TextStyle(
                fontFamily = SpaceGroteskFamily,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        titleMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
            ),
        bodyLarge = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 24.sp),
        labelMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            ),
    )

// Algarismos tabulares: o horario nao pula quando muda.
val TimeTextStyle =
    TextStyle(
        fontFamily = InterFamily,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
