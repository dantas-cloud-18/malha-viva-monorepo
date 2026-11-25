package br.malhaviva.app

import br.malhaviva.feature.tracking.net.IntegrityTokenProvider

/**
 * Release ainda nao tem Play Integrity: devolve null, entao nenhum lote e enviado.
 * Excecao: build de piloto (-Pmalhaviva.pilot=true) envia o token de teste, aceito so por servidor com
 * MALHAVIVA_DEV_INTEGRITY=true. Trocar pela Play Integrity API antes de publicar na loja.
 */
object UnavailableIntegrityTokenProvider : IntegrityTokenProvider {
    override suspend fun token(): String? = if (BuildConfig.PILOT_INTEGRITY) "dev-integrity" else null
}

fun integrityTokenProvider(): IntegrityTokenProvider = UnavailableIntegrityTokenProvider
