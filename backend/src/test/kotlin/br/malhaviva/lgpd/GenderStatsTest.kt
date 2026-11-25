package br.malhaviva.lgpd

import br.malhaviva.PostgresIntegrationTest
import br.malhaviva.auth.Gender
import br.malhaviva.auth.JdbcAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenderStatsTest : PostgresIntegrationTest() {
    @Test
    fun `buckets below k are suppressed and k itself is published`() {
        val raw =
            mapOf(Gender.MALE to 5L, Gender.FEMALE to 4L, Gender.NON_BINARY to 1L, Gender.PREFER_NOT_TO_SAY to 50L)
        assertEquals(mapOf(Gender.MALE to 5L, Gender.PREFER_NOT_TO_SAY to 50L), suppressSmallBuckets(raw))
        assertTrue(suppressSmallBuckets(mapOf(Gender.MALE to 4L)).isEmpty())
    }

    @Test
    fun `distribution counts only declared genders and hides small buckets`() {
        val accounts = JdbcAccountRepository(ds)
        repeat(5) { accounts.create("@homem_$it", "h", gender = Gender.MALE) }
        repeat(4) { accounts.create("@mulher_$it", "h", gender = Gender.FEMALE) }
        accounts.create("@nb_0", "h", gender = Gender.NON_BINARY)
        repeat(7) { accounts.create("@nada_$it", "h") }
        val stats = GenderStatsRepository(ds)
        assertEquals(mapOf(Gender.MALE to 5L), stats.distribution())
        assertEquals(
            mapOf(Gender.MALE to 5L, Gender.FEMALE to 4L, Gender.NON_BINARY to 1L),
            stats.distribution(k = 1),
        )
        scalars("UPDATE accounts SET status = 'deleted', gender = NULL WHERE handle = '@homem_0'")
        assertTrue(stats.distribution().isEmpty(), "excluida sai da contagem")
    }
}
