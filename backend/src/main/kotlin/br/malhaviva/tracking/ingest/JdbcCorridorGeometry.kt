package br.malhaviva.tracking.ingest

import java.sql.Connection
import javax.sql.DataSource

private const val RETRY_MS = 30_000L

private const val POINTS_SQL = """
    SELECT ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon
    FROM track_segments s CROSS JOIN LATERAL ST_DumpPoints(s.line) p
    WHERE s.corridor_id = ?
    ORDER BY s.km_start, (p.path)[1]
"""

/**
 * Carrega o tracado de track_segments (V1) na primeira chamada. Assume km crescente do Central
 * para Santa Cruz. Tabela vazia: tenta de novo no maximo a cada 30 s e ate la tudo e fora do corredor.
 */
class JdbcCorridorGeometry(
    private val dataSource: DataSource,
    private val corridorId: String = "RAMAL_SANTA_CRUZ",
    private val clock: () -> Long = System::currentTimeMillis,
) : CorridorGeometry {
    @Volatile
    private var state: Pair<Long, PolylineCorridor?> = 0L to null

    override fun alongTrack(
        lat: Double,
        lon: Double,
    ): Pair<Double, Double>? = current()?.alongTrack(lat, lon)

    private fun current(): PolylineCorridor? {
        val (attemptedAt, line) = state
        val now = clock()
        return if (line != null || now - attemptedAt < RETRY_MS) line else load().also { state = now to it }
    }

    private fun load(): PolylineCorridor? {
        val points = dataSource.connection.use { c -> queryPoints(c) }
        return if (points.size >= 2) PolylineCorridor(points) else null
    }

    private fun queryPoints(c: Connection): List<Pair<Double, Double>> =
        c.prepareStatement(POINTS_SQL).use { st ->
            st.setString(1, corridorId)
            st.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getDouble("lat") to rs.getDouble("lon") else null }.toList()
            }
        }
}
