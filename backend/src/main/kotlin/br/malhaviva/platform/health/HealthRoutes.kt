package br.malhaviva.platform.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class Health(
    val status: String,
)

fun Route.healthRoutes(dataSource: DataSource) {
    get("/health") {
        val dbUp =
            runCatching {
                dataSource.connection.use { it.isValid(2) }
            }.getOrDefault(false)
        if (dbUp) {
            call.respond(Health("ok"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, Health("degraded"))
        }
    }
}
