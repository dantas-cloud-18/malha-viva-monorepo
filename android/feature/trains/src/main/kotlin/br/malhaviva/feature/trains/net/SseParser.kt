package br.malhaviva.feature.trains.net

data class SseEvent(
    /** `message` quando o servidor nao manda `event:`. */
    val type: String,
    val data: String,
)

/**
 * Parser incremental de `text/event-stream` (WHATWG): aceita pedacos que cortam linhas ao meio, terminadores
 * `\n`, `\r\n` e `\r` (inclusive `\r` e `\n` em pedacos diferentes), comentarios (`:` = heartbeat) e `data:` em
 * varias linhas. Um evento so sai na linha em branco e so se tiver `data`. `id:` e `retry:` sao ignorados.
 */
class SseParser {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var type = ""
    private var hasData = false
    private var skipLf = false
    private var started = false

    fun feed(chunk: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (c in chunk) {
            if (!started) {
                started = true
                if (c == BOM) continue
            }
            when {
                skipLf && c == '\n' -> skipLf = false
                c == '\n' || c == '\r' -> {
                    skipLf = c == '\r'
                    endLine()?.let(out::add)
                }
                else -> {
                    skipLf = false
                    line.append(c)
                }
            }
        }
        return out
    }

    private fun endLine(): SseEvent? {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) return dispatch()
        if (!text.startsWith(':')) readField(text)
        return null
    }

    private fun readField(text: String) {
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> type = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(type.ifEmpty { "message" }, data.toString()) else null
        data.setLength(0)
        type = ""
        hasData = false
        return event
    }

    private companion object {
        const val BOM = '\uFEFF'
    }
}
