package br.malhaviva.auth

import java.time.Instant
import java.util.UUID

const val ROLE_USER = "user"
const val ROLE_ADMIN = "admin"

/** Faixa etaria autodeclarada (LGPD art. 14); a data de nascimento nao e guardada. */
enum class AgeBracket(
    val wire: String,
) {
    UNDER_12("under_12"),
    TEEN("teen_12_17"),
    ADULT("adult"),
    ;

    companion object {
        fun fromWire(value: String): AgeBracket = entries.first { it.wire == value }
    }
}

/** Genero autodeclarado e opcional (ausente = nao informado), so para estatistica agregada anonima. */
enum class Gender(
    val wire: String,
) {
    MALE("male"),
    FEMALE("female"),
    NON_BINARY("non_binary"),
    PREFER_NOT_TO_SAY("prefer_not_to_say"),
    ;

    companion object {
        fun fromWire(value: String?): Gender? = entries.firstOrNull { it.wire == value }
    }
}

enum class AccountStatus(
    val wire: String,
) {
    ACTIVE("active"),
    PENDING_PARENTAL_CONSENT("pending_parental_consent"),
    DELETED("deleted"),
    ;

    companion object {
        fun fromWire(value: String): AccountStatus = entries.first { it.wire == value }
    }
}

data class AccountRecord(
    val id: UUID,
    val handle: String,
    val passwordHash: String,
    val role: String = ROLE_USER,
    val ageBracket: AgeBracket = AgeBracket.ADULT,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val gender: Gender? = null,
)

interface AccountRepository {
    /**
     * Cria a conta; null se o @handle ja existe (sem diferenciar maiusculas). Menor de 12 nasce
     * `pending_parental_consent` na mesma insercao (falha fechada: nunca existe ativa sem consentimento).
     */
    fun create(
        handle: String,
        passwordHash: String,
        ageBracket: AgeBracket = AgeBracket.ADULT,
        gender: Gender? = null,
    ): UUID?

    fun findByHandle(handle: String): AccountRecord?

    fun findById(id: UUID): AccountRecord?

    fun updatePasswordHash(
        accountId: UUID,
        passwordHash: String,
    )

    /**
     * Consome o codigo de enrolamento do admin (uso unico): verdadeiro so se a conta e admin e o SHA-256 (hex)
     * confere com o gravado, que e zerado na mesma operacao.
     */
    fun consumeAdminEnrollCode(
        accountId: UUID,
        codeHash: String,
    ): Boolean
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
    /** O login da familia passou pelo TOTP; herdado na rotacao. */
    val mfa: Boolean = false,
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

    /** A familia (dispositivo) existe e nao foi revogada. */
    fun isFamilyActive(familyId: UUID): Boolean

    /** Somente dispositivos nao revogados. */
    fun listDevices(accountId: UUID): List<DeviceRecord>

    /** Revoga se o dispositivo pertence a [accountId] (anti-IDOR); false caso contrario ou inexistente. */
    fun revokeDevice(
        accountId: UUID,
        deviceId: UUID,
        now: Instant,
    ): Boolean
}
