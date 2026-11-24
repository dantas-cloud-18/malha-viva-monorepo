package br.malhaviva.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime

class ViagensMedidasTest {
    private val json = File("src/main/assets/viagens-medidas.json").readText()

    private val parada =
        Regex(
            """"estacao": "([a-z-]+)", "chegada": "([^"]+)", "saida": "([^"]+)", "parada_s": (\d+)""",
        )

    @Test
    fun `tempos de parada batem com chegada e saida em ordem`() {
        val paradas = parada.findAll(json).map { it.groupValues }.toList()
        assertEquals(13, paradas.size)
        var anterior: OffsetDateTime? = null
        for (grupo in paradas) {
            val (estacao, chegada, saida) = grupo.drop(1)
            val segundos = grupo[4]
            val inicio = OffsetDateTime.parse(chegada)
            val fim = OffsetDateTime.parse(saida)
            assertEquals(estacao, segundos.toLong(), Duration.between(inicio, fim).seconds)
            anterior?.let { assertTrue(estacao, it.isBefore(inicio)) }
            anterior = fim
        }
    }

    @Test
    fun `sem campos de identidade ou posicao`() {
        val proibido = Regex(""""(lat|lon|user|account|handle|phone|email|aparelho|device)""")
        assertTrue(proibido.find(json)?.value ?: "", proibido.find(json) == null)
    }
}
