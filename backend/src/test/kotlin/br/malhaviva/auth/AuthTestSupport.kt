package br.malhaviva.auth

import java.time.Instant
import java.util.UUID

/** Segredos fixos e falsos: so existem nos testes. */
val TEST_PEPPER = ByteArray(32) { (it + 1).toByte() }
val TEST_JWT_SECRET = ByteArray(32) { (100 + it).toByte() }

/** Parametros minimos do argon2 para os testes de rota nao levarem segundos. */
val FAST_PARAMS = Argon2Params(memoryKib = 64, iterations = 1, parallelism = 1)

const val STRONG_PASSWORD = "quantas estrelas cabem num trem vazio"
const val PWNED_BUT_STRONG = "este-segredo-vazou-mas-parece-forte-9Qx"

class FakePwned(
    private val leaked: Set<String> = setOf(PWNED_BUT_STRONG),
) : PwnedPasswordsChecker {
    override suspend fun isPwned(password: String) = password in leaked
}

class CountingHasher(
    pepper: ByteArray = TEST_PEPPER,
    params: Argon2Params = FAST_PARAMS,
) : PasswordHasher(pepper, params) {
    var verifyCalls = 0

    override fun verify(
        password: String,
        phc: String,
    ): Boolean {
        verifyCalls++
        return super.verify(password, phc)
    }
}

class InMemoryAccountRepository : AccountRepository {
    private val rows = LinkedHashMap<UUID, AccountRecord>()

    @Synchronized
    override fun create(
        handle: String,
        passwordHash: String,
    ): UUID? =
        if (rows.values.any { it.handle.equals(handle, ignoreCase = true) }) {
            null
        } else {
            UUID.randomUUID().also { rows[it] = AccountRecord(it, handle, passwordHash) }
        }

    @Synchronized
    override fun findByHandle(handle: String) = rows.values.firstOrNull { it.handle.equals(handle, ignoreCase = true) }

    @Synchronized
    override fun findById(id: UUID) = rows[id]

    @Synchronized
    override fun updatePasswordHash(
        accountId: UUID,
        passwordHash: String,
    ) {
        rows[accountId] = rows.getValue(accountId).copy(passwordHash = passwordHash)
    }
}

class InMemorySessionRepository : SessionRepository {
    private val devices = LinkedHashMap<UUID, DeviceRecord>()
    private val revokedDevices = mutableSetOf<UUID>()
    private val tokens = LinkedHashMap<String, RefreshRecord>()

    val tokenRows: List<RefreshRecord> @Synchronized get() = tokens.values.toList()

    @Synchronized
    override fun createDevice(
        device: DeviceRecord,
        firstToken: RefreshRecord,
    ) {
        devices[device.id] = device
        tokens[firstToken.tokenHash] = firstToken
    }

    @Synchronized
    override fun findToken(tokenHash: String) = tokens[tokenHash]

    @Synchronized
    override fun rotate(
        oldHash: String,
        next: RefreshRecord,
        now: Instant,
    ): Boolean {
        val old = tokens[oldHash]
        val swappable = old != null && old.replacedAt == null && old.revokedAt == null
        if (swappable && old != null) {
            tokens[oldHash] = old.copy(replacedAt = now)
            tokens[next.tokenHash] = next
            devices[next.familyId]?.let { devices[it.id] = it.copy(lastUsedAt = now) }
        }
        return swappable
    }

    @Synchronized
    override fun revokeFamily(
        familyId: UUID,
        now: Instant,
    ) {
        tokens.replaceAll { _, t -> if (t.familyId == familyId && t.revokedAt == null) t.copy(revokedAt = now) else t }
        revokedDevices += familyId
    }

    @Synchronized
    override fun revokeAll(
        accountId: UUID,
        now: Instant,
    ) {
        devices.values.filter { it.accountId == accountId }.forEach { revokeFamily(it.id, now) }
    }

    @Synchronized
    override fun listDevices(accountId: UUID) =
        devices.values.filter { it.accountId == accountId && it.id !in revokedDevices }

    @Synchronized
    override fun revokeDevice(
        accountId: UUID,
        deviceId: UUID,
        now: Instant,
    ): Boolean {
        val owned = devices[deviceId]?.accountId == accountId && deviceId !in revokedDevices
        if (owned) revokeFamily(deviceId, now)
        return owned
    }
}

/** Monta o plano de conta inteiro em memoria, com relogio controlavel. */
class TestAuth(
    var now: Long = 1_800_000_000_000L,
    val hasher: CountingHasher = CountingHasher(),
    val accounts: InMemoryAccountRepository = InMemoryAccountRepository(),
    val sessionRepo: InMemorySessionRepository = InMemorySessionRepository(),
    captcha: CaptchaVerifier = NoopCaptchaVerifier,
    limiters: AuthLimiters = AuthLimiters(),
) {
    val access = AccessTokenService(TEST_JWT_SECRET)
    val sessions = SessionService(sessionRepo, access, { now })
    val guard = LoginGuard { now }
    val components =
        AuthComponents(
            AuthService(accounts, sessions, PasswordPolicy(FakePwned()), hasher, guard, captcha),
            sessions,
            access,
            limiters,
            { now },
        )
}
