package br.malhaviva.core.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiBaseUrlTest {
    @Test
    fun trimsSpacesAndTrailingSlashes() {
        assertEquals("https://a.trycloudflare.com", normalizeBaseUrl("  https://a.trycloudflare.com//  ", false))
    }

    @Test
    fun keepsPortAndPath() {
        assertEquals("https://host:8443/api", normalizeBaseUrl("https://host:8443/api/", false))
    }

    @Test
    fun httpOnlyWhenAllowed() {
        assertNull(normalizeBaseUrl("http://10.0.2.2:8080", false))
        assertEquals("http://10.0.2.2:8080", normalizeBaseUrl("http://10.0.2.2:8080/", true))
    }

    @Test
    fun rejectsInvalid() {
        listOf(
            "",
            "   ",
            "ftp://host",
            "host.com",
            "https://",
            "https:///x",
            "https://a b",
            "https://h?x=1",
            "https://h#f",
        ).forEach { assertNull(it, normalizeBaseUrl(it, true)) }
    }
}
