package br.malhaviva.core.data.net

import android.content.Context
import java.net.URI

/**
 * Valida e normaliza o endereco do servidor: so https (http apenas se [allowHttp], builds de debug),
 * com host, sem query/fragmento e sem barra final. Devolve null se invalido.
 */
fun normalizeBaseUrl(
    input: String,
    allowHttp: Boolean,
): String? {
    val url = input.trim().trimEnd('/')
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val schemeOk = uri.scheme.equals("https", true) || (allowHttp && uri.scheme.equals("http", true))
    val ok = schemeOk && !uri.host.isNullOrEmpty() && uri.query == null && uri.fragment == null
    return url.takeIf { ok }
}

/**
 * Endereco efetivo da API: o valor salvo pelo usuario (nao secreto) ou o [default] do build. Os clientes
 * de rede chamam [current] a cada requisicao, entao uma mudanca vale sem reiniciar o app.
 */
class ApiBaseUrlStore(
    context: Context,
    private val default: String,
    private val allowHttp: Boolean,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): String = prefs.getString(KEY, null)?.let { normalizeBaseUrl(it, allowHttp) } ?: default

    /** Salva se valido; devolve false (sem alterar nada) se invalido. */
    fun save(input: String): Boolean {
        val url = normalizeBaseUrl(input, allowHttp) ?: return false
        prefs.edit().putString(KEY, url).apply()
        return true
    }

    fun reset() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "malhaviva_server"
        const val KEY = "api_base_url"
    }
}
