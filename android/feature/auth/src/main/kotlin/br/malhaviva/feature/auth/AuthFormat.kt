package br.malhaviva.feature.auth

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private const val BIRTH_DIGITS = 8
private const val BIRTH_SLASH_1 = 2
private const val BIRTH_SLASH_2 = 4
private const val PHONE_DIGITS_MAX = 15
private const val BR_DDI = "55"
private const val BR_AREA_LEN = 2
private const val BR_LANDLINE_LEN = 8
private const val BR_SPLIT_MOBILE = 5
private const val BR_SPLIT_LANDLINE = 4

/** Mascara de um campo: o estado guarda so o valor cru (digitos / E.164); a tela mostra o formatado. */
internal enum class Mask { None, Birth, Phone }

internal fun Mask.sanitize(typed: String): String =
    when (this) {
        Mask.None -> typed
        Mask.Birth -> typed.filter(Char::isDigit).take(BIRTH_DIGITS)
        Mask.Phone -> sanitizePhoneInput(typed)
    }

internal fun Mask.transformation(): VisualTransformation =
    when (this) {
        Mask.None -> VisualTransformation.None
        Mask.Birth -> FormatTransformation(::formatBirthDate)
        Mask.Phone -> FormatTransformation(::formatPhone)
    }

/** Mantem so digitos, sempre com "+" na frente (E.164). */
internal fun sanitizePhoneInput(typed: String): String = "+" + typed.filter(Char::isDigit).take(PHONE_DIGITS_MAX)

/** "01021972" vira "01/02/1972"; as barras so aparecem quando ha digito depois delas. */
internal fun formatBirthDate(raw: String): String {
    val d = raw.filter(Char::isDigit).take(BIRTH_DIGITS)
    return buildString {
        d.forEachIndexed { i, c ->
            if (i == BIRTH_SLASH_1 || i == BIRTH_SLASH_2) append('/')
            append(c)
        }
    }
}

/** "+5521969717210" vira "+55 (21) 96971-7210"; outros DDI ficam so com "+" e os digitos. */
internal fun formatPhone(raw: String): String {
    val d = raw.filter(Char::isDigit)
    if (!d.startsWith(BR_DDI)) return "+$d"
    val area = d.drop(BR_DDI.length).take(BR_AREA_LEN)
    val local = d.drop(BR_DDI.length + BR_AREA_LEN)
    val split = if (local.length > BR_LANDLINE_LEN) BR_SPLIT_MOBILE else BR_SPLIT_LANDLINE
    return buildString {
        append("+$BR_DDI")
        if (area.isNotEmpty()) append(" (").append(area)
        if (local.isNotEmpty()) append(") ").append(local.take(split))
        if (local.length > split) append('-').append(local.drop(split))
    }
}

private fun isSource(c: Char) = c.isDigit() || c == '+'

/** Posicao no texto formatado logo depois do [source]-esimo caractere cru. */
internal fun sourceToFormatted(
    formatted: String,
    source: Int,
): Int {
    var seen = 0
    val at = formatted.indexOfFirst { isSource(it) && ++seen == source }
    return if (source <= 0) {
        0
    } else if (at < 0) {
        formatted.length
    } else {
        at + 1
    }
}

/** Quantos caracteres crus existem antes da posicao [pos] do texto formatado. */
internal fun formattedToSource(
    formatted: String,
    pos: Int,
): Int = formatted.take(pos).count(::isSource)

private class FormatTransformation(
    private val format: (String) -> String,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = format(text.text)
        return TransformedText(
            AnnotatedString(out),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = sourceToFormatted(out, offset)

                override fun transformedToOriginal(offset: Int) = formattedToSource(out, offset)
            },
        )
    }
}
