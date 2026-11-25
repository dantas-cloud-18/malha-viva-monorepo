package br.malhaviva.feature.trains.net

import br.malhaviva.feature.tracking.net.CorridorParser
import br.malhaviva.feature.trains.LineNetwork
import br.malhaviva.feature.trains.LineStation
import br.malhaviva.feature.trains.LiveTrain
import org.json.JSONException
import org.json.JSONObject

/** JSON do contrato (`TrainState`, `TrainsSnapshot`, `CorridorNetwork`) para o modelo da feature. */
object TrainsWire {
    /** null se o JSON estiver quebrado ou com valores fora do contrato (uma linha ruim nao derruba o stream). */
    fun parseTrain(json: String): LiveTrain? =
        try {
            train(JSONObject(json))
        } catch (_: JSONException) {
            null
        }

    fun parseSnapshot(json: String): List<LiveTrain>? =
        try {
            val array = JSONObject(json).getJSONArray("trains")
            (0 until array.length()).mapNotNull { parseTrain(array.getJSONObject(it).toString()) }
        } catch (_: JSONException) {
            null
        }

    fun parseNetwork(json: String): LineNetwork? =
        try {
            val route = CorridorParser.parse(json)
            val names = stationNames(json)
            route?.let { r ->
                LineNetwork(
                    stations = r.stationsAlong.map { (s, along) -> LineStation(s.id, names[s.id] ?: s.id, along) },
                    lengthM = r.lengthM,
                )
            }
        } catch (_: JSONException) {
            null
        }

    private fun stationNames(json: String): Map<String, String> {
        val array = JSONObject(json).getJSONArray("stations")
        return (0 until array.length()).associate {
            val s = array.getJSONObject(it)
            s.getString("station_id") to s.getString("name")
        }
    }

    private fun train(o: JSONObject): LiveTrain? {
        val along = o.getDouble("along_m")
        val speed = o.getDouble("speed_mps")
        val valid = along.isFinite() && speed.isFinite() && speed >= 0.0 && o.getInt("signals") >= 1
        return if (valid) {
            LiveTrain(
                id = o.getString("id"),
                alongM = along,
                speedMps = speed,
                signals = o.getInt("signals"),
                confidence = o.getDouble("confidence").coerceIn(0.0, 1.0),
                updatedAtMs = o.getLong("updated_at_ms"),
            )
        } else {
            null
        }
    }
}
