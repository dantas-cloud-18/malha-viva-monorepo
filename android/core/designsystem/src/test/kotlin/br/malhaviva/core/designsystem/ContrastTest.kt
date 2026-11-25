package br.malhaviva.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun check(colors: MalhaVivaColors) {
        val s = colors.status
        val text = listOf(colors.primary, colors.text, colors.textMuted)
        val status = listOf(s.onTime, s.delayed, s.interrupted, s.noSignal, s.lowConfidence)
        (text + status).forEach {
            val ratio = contrast(it, colors.surface)
            assertTrue("contraste $ratio < 4.5", ratio >= TEXT_MIN)
        }
        assertTrue(contrast(colors.accent, colors.surface) >= GRAPHIC_MIN)
        assertTrue(contrast(colors.live, colors.surface) >= GRAPHIC_MIN)
    }

    @Test
    fun lightPaletteMeetsWcagAa() = check(LightMalhaVivaColors)

    @Test
    fun darkPaletteMeetsWcagAa() = check(DarkMalhaVivaColors)

    private companion object {
        const val TEXT_MIN = 4.5f
        const val GRAPHIC_MIN = 3f
    }
}
