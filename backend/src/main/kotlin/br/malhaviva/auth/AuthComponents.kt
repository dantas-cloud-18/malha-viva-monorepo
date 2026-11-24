package br.malhaviva.auth

import br.malhaviva.tracking.ingest.RateLimiter

private const val IP_CAPACITY = 20.0
private const val IP_REFILL_PER_S = 1.0 / 3
private const val HANDLE_CAPACITY = 10.0
private const val HANDLE_REFILL_PER_S = 1.0 / 6
private const val SIGNUP_CAPACITY = 5.0
private const val SIGNUP_REFILL_PER_S = 1.0 / 60

/** Limites de taxa: login por IP, login por handle e cadastro por IP. */
class AuthLimiters(
    val ip: RateLimiter = RateLimiter(IP_CAPACITY, IP_REFILL_PER_S),
    val handle: RateLimiter = RateLimiter(HANDLE_CAPACITY, HANDLE_REFILL_PER_S),
    val signup: RateLimiter = RateLimiter(SIGNUP_CAPACITY, SIGNUP_REFILL_PER_S),
)

/** Tudo que as rotas de conta precisam; montado em AuthConfig (producao) ou nos testes. */
class AuthComponents(
    val auth: AuthService,
    val sessions: SessionService,
    val access: AccessTokenService,
    val limiters: AuthLimiters = AuthLimiters(),
    val clock: () -> Long = System::currentTimeMillis,
)
