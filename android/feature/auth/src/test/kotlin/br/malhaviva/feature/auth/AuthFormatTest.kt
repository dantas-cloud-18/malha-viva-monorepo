package br.malhaviva.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthFormatTest {
    @Test
    fun `birth date gets slashes only between digits and caps at 8 digits`() {
        assertEquals("", formatBirthDate(""))
        assertEquals("01", formatBirthDate("01"))
        assertEquals("01/0", formatBirthDate("010"))
        assertEquals("01/02", formatBirthDate("0102"))
        assertEquals("01/02/1", formatBirthDate("01021"))
        assertEquals("01/02/1972", formatBirthDate("01021972"))
        assertEquals("01021972", Mask.Birth.sanitize("01/02/1972999"))
    }

    @Test
    fun `brazilian phone is grouped as it is typed`() {
        assertEquals("+", formatPhone("+"))
        assertEquals("+55", formatPhone("+55"))
        assertEquals("+55 (2", formatPhone("+552"))
        assertEquals("+55 (21", formatPhone("+5521"))
        assertEquals("+55 (21) 9", formatPhone("+55219"))
        assertEquals("+55 (21) 9697-1721", formatPhone("+552196971721"))
        assertEquals("+55 (21) 96971-7210", formatPhone("+5521969717210"))
    }

    @Test
    fun `other country codes keep plus and digits`() {
        assertEquals("+14155550123", formatPhone("+14155550123"))
    }

    @Test
    fun `phone input is reduced to E164 and still validates`() {
        assertEquals("+5521969717210", sanitizePhoneInput("+55 (21) 96971-7210"))
        assertEquals("+", sanitizePhoneInput(""))
        assertEquals(16, sanitizePhoneInput("1234567890123456789").length)
        assertEquals(true, isPhoneValid(sanitizePhoneInput("+55 (21) 96971-7210")))
        assertEquals(true, isPhoneBlank(sanitizePhoneInput("")))
    }

    @Test
    fun `cursor offsets skip inserted separators`() {
        val f = "+55 (21) 96971-7210"
        assertEquals(0, sourceToFormatted(f, 0))
        assertEquals(3, sourceToFormatted(f, 3))
        assertEquals(7, sourceToFormatted(f, 5))
        assertEquals(f.length, sourceToFormatted(f, 99))
        assertEquals(3, formattedToSource(f, 4))
        assertEquals(5, formattedToSource(f, 9))
        assertEquals(14, formattedToSource(f, f.length))
    }
}
