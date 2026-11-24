package br.malhaviva.app

import br.malhaviva.feature.tracking.net.IntegrityTokenProvider

/** Somente debug: o backend de desenvolvimento aceita este valor fixo no lugar do Play Integrity. */
object DebugIntegrityTokenProvider : IntegrityTokenProvider {
    override suspend fun token(): String = "dev-integrity"
}

fun integrityTokenProvider(): IntegrityTokenProvider = DebugIntegrityTokenProvider
