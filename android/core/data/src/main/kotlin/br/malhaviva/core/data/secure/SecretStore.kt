package br.malhaviva.core.data.secure

/** Armazenamento local de segredos curtos (tokens, chaves). Valores nunca saem do aparelho. */
interface SecretStore {
    /** Retorna o valor ou null se ausente ou ilegivel (falha fechada: entrada corrompida e removida). */
    fun get(name: String): String?

    fun put(
        name: String,
        value: String,
    )

    fun remove(name: String)
}

/** Fake em memoria para testes de outros modulos; nao persiste nada. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override fun get(name: String): String? = values[name]

    override fun put(
        name: String,
        value: String,
    ) {
        values[name] = value
    }

    override fun remove(name: String) {
        values.remove(name)
    }
}
