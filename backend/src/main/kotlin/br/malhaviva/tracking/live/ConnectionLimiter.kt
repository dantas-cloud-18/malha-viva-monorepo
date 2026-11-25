package br.malhaviva.tracking.live

const val DEFAULT_MAX_SSE_CONNECTIONS = 2_000
const val DEFAULT_MAX_SSE_PER_IP = 8

/**
 * Teto de conexoes SSE abertas, global e por IP. Cada conexao fica aberta por muito tempo, entao sem teto um
 * unico cliente esgota descritores e memoria. A tabela por IP so guarda IPs com conexao aberta (limitada pelo
 * teto global). shortcut: contagem de um no so; com varios nos o teto vale por instancia.
 */
class ConnectionLimiter(
    private val maxTotal: Int = DEFAULT_MAX_SSE_CONNECTIONS,
    private val maxPerIp: Int = DEFAULT_MAX_SSE_PER_IP,
) {
    private val perIp = HashMap<String, Int>()
    private var total = 0

    /** Reserva uma conexao para [ip]; false se o teto global ou o do IP ja foi atingido. */
    @Synchronized
    fun tryAcquire(ip: String): Boolean {
        val current = perIp[ip] ?: 0
        val ok = total < maxTotal && current < maxPerIp
        if (ok) {
            total++
            perIp[ip] = current + 1
        }
        return ok
    }

    @Synchronized
    fun release(ip: String) {
        val current = perIp[ip] ?: return
        total--
        if (current <= 1) perIp.remove(ip) else perIp[ip] = current - 1
    }

    @Synchronized
    fun active(): Int = total
}
