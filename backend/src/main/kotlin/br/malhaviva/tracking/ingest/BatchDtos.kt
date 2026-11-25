package br.malhaviva.tracking.ingest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MotionState { MOVING, STATIONARY, UNKNOWN }

/** Corpo de POST /telemetry/batches. Json padrao rejeita campo desconhecido (additionalProperties: false). */
@Serializable
data class BatchDto(
    @SerialName("anon_epoch_id") val anonEpochId: String,
    val corridor: String,
    val samples: List<SampleDto>,
)

@Serializable
data class SampleDto(
    val seq: Long,
    val ts: String,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("motion_state") val motionState: MotionState? = null,
    @SerialName("mock_location") val mockLocation: Boolean = false,
)

/** Resposta 202. Contagens apenas; nunca ecoa posicoes. */
@Serializable
data class BatchResult(
    val accepted: Int,
    val rejected: Map<String, Int>,
)
