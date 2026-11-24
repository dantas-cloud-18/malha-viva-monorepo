package br.malhaviva.auth

import java.time.Instant
import java.util.UUID

data class AccountRecord(
    val id: UUID,
    val handle: String,
    val passwordHash: String,
)

interface AccountRepository {
    /** Cria a conta; null se o @handle ja existe (sem diferenciar maiusculas). */
    fun create(
        handle: String,
        passwordHash: String,
    ): UUID?

    fun findByHandle(handle: String): AccountRecord?

    fun findById(id: UUID): AccountRecord?

    fun updatePasswordHash(
        accountId: UUID,
        passwordHash: String,
    )
}

/** Um dispositivo e uma familia de refresh: [id] tambem e o family_id dos tokens. */
data class DeviceRecord(
    val id: UUID,
    val accountId: UUID,
    val label: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
)

data class RefreshRecord(
    val tokenHash: String,
    val accountId: UUID,
    val familyId: UUID,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val replacedAt: Instant? = null,
    val revokedAt: Instant? = null,
)

interface SessionRepository {
    /** Grava dispositivo e primeiro refresh de forma atomica. */
    fun createDevice(
        device: DeviceRecord,
        firstToken: RefreshRecord,
    )

    fun findToken(tokenHash: String): RefreshRecord?

    /**
     * Troca atomica: marca [oldHash] como substituido (so se ainda nao estava) e grava [next].
     * False se outro pedido ja o tinha trocado ou revogado, ou seja, reuso concorrente.
     */
    fun rotate(
        oldHash: String,
        next: RefreshRecord,
        now: Instant,
    ): Boolean

    /** Revoga todos os tokens da familia e o dispositivo. */
    fun revokeFamily(
        familyId: UUID,
        now: Instant,
    )

    /** Revoga todos os dispositivos e refresh tokens da conta (recuperacao de conta). */
    fun revokeAll(
        accountId: UUID,
        now: Instant,
    )

    /** Somente dispositivos nao revogados. */
    fun listDevices(accountId: UUID): List<DeviceRecord>

    /** Revoga se o dispositivo pertence a [accountId] (anti-IDOR); false caso contrario ou inexistente. */
    fun revokeDevice(
        accountId: UUID,
        deviceId: UUID,
        now: Instant,
    ): Boolean
}
