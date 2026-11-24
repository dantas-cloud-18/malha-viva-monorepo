package br.malhaviva.app

import br.malhaviva.feature.tracking.net.IntegrityTokenProvider

/**
 * Release ainda nao tem Play Integrity: devolve null, entao nenhum lote e enviado.
 * Trocar por a implementacao real (Play Integrity API) antes de publicar o rastreamento.
 */
object UnavailableIntegrityTokenProvider : IntegrityTokenProvider {
    override suspend fun token(): String? = null
}

fun integrityTokenProvider(): IntegrityTokenProvider = UnavailableIntegrityTokenProvider
