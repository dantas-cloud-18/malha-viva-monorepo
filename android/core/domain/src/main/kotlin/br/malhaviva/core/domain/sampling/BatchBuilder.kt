package br.malhaviva.core.domain.sampling

import br.malhaviva.core.domain.checkin.Reading
import br.malhaviva.core.domain.tracking.TrackPolicy

/** Amostra enviada ao backend: sem nenhum identificador de usuario ou aparelho. */
data class Sample(
    val seq: Int,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMs: Double,
)

data class SampleBatch(
    val samples: List<Sample>,
)

/** Limite do contrato (maxItems de samples). */
const val MAX_BATCH_SIZE = 120

/** Acumula leituras ja saneadas; `seq` cresce a partir de 0 por viagem, atravessando lotes. */
class BatchBuilder {
    private val pending = mutableListOf<Sample>()
    private var nextSeq = 0

    /** Devolve um lote quando o limite de tamanho e atingido; senao null. */
    fun add(r: Reading): SampleBatch? {
        pending +=
            Sample(
                seq = nextSeq++,
                timeMs = r.timeMs,
                lat = r.point.lat,
                lon = r.point.lon,
                accuracyM = r.accuracyM,
                speedMs = r.speedKmh / TrackPolicy.KMH_TO_MS,
            )
        return if (pending.size >= MAX_BATCH_SIZE) flush() else null
    }

    /** Emite o que estiver acumulado; vazio nao gera lote. */
    fun flush(): SampleBatch? {
        if (pending.isEmpty()) return null
        return SampleBatch(pending.toList()).also { pending.clear() }
    }
}
