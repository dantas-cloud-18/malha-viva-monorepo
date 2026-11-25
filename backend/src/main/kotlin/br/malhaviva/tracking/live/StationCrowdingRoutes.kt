package br.malhaviva.tracking.live

import br.malhaviva.Problem
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** [stations] devolve as estacoes do corredor com `along_m`, ou null se a malha nao existir. */
fun Route.crowdingRoutes(
    hub: LiveHub,
    stations: (String) -> List<StationRef>?,
    estimator: StationCrowdingEstimator = StationCrowdingEstimator(),
    smoother: StationCrowdingSmoother = StationCrowdingSmoother(),
    checkins: StationCheckins = StationCheckins(),
) {
    get("/corridors/{corridor}/stations/crowding") {
        val key = lineKey(call.parameters["corridor"], call.request.queryParameters["direction"])
        if (key == null) {
            call.badLine()
        } else {
            val refs = stations(key.corridor)
            if (refs == null) {
                val status = HttpStatusCode.NotFound
                call.respond(status, Problem("about:blank", "Unknown corridor", status.value))
            } else {
                val crowding =
                    smoother.publish(
                        key,
                        estimator.counts(hub.freshPositions(key), refs, checkins.counts(key)),
                    )
                call.respond(StationCrowdingSnapshot(key.corridor, key.direction, crowding))
            }
        }
    }
}
