package br.malhaviva.tracking.ingest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ClientPlatform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

/** Atesta que o cliente e um app genuino antes de emitir token anonimo. */
interface IntegrityVerifier {
    suspend fun verify(
        platform: ClientPlatform,
        integrityToken: String,
    ): Boolean
}

/**
 * Padrao seguro de producao: recusa tudo.
 *
 * A verificacao real (Play Integrity no Android, App Attest no iOS) exige credenciais do Google e da Apple
 * que ainda NAO existem neste projeto. Enquanto nao houver implementacao real, producao nunca emite token:
 * aceitar em silencio abriria a ingestao para qualquer cliente.
 */
object RejectAllIntegrityVerifier : IntegrityVerifier {
    override suspend fun verify(
        platform: ClientPlatform,
        integrityToken: String,
    ): Boolean = false
}

/** So para desenvolvimento e testes: aceita apenas o literal [ACCEPTED_TOKEN]. Nunca e o padrao. */
object DevIntegrityVerifier : IntegrityVerifier {
    const val ACCEPTED_TOKEN = "dev-integrity"

    override suspend fun verify(
        platform: ClientPlatform,
        integrityToken: String,
    ): Boolean = integrityToken == ACCEPTED_TOKEN
}

private const val DEV_INTEGRITY_ENV = "MALHAVIVA_DEV_INTEGRITY"

/** Verificador dev somente com MALHAVIVA_DEV_INTEGRITY=true; qualquer outro valor ou ausencia recusa tudo. */
fun integrityVerifierFromEnv(env: (String) -> String? = System::getenv): IntegrityVerifier =
    if (env(DEV_INTEGRITY_ENV) == "true") DevIntegrityVerifier else RejectAllIntegrityVerifier
