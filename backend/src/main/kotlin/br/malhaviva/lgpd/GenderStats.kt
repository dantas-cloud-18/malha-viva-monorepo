package br.malhaviva.lgpd

import br.malhaviva.auth.Gender
import br.malhaviva.auth.queryList
import br.malhaviva.auth.read
import javax.sql.DataSource

/** k-anonimato das estatisticas de genero: bucket com menos contas que isto nao e divulgado. */
const val GENDER_MIN_BUCKET = 5L

/** Remove os buckets com menos de [k] contas; "nao informado" nunca entra no relatorio. */
fun suppressSmallBuckets(
    counts: Map<Gender, Long>,
    k: Long = GENDER_MIN_BUCKET,
): Map<Gender, Long> = counts.filterValues { it >= k }

/**
 * Perfil de uso agregado e anonimo. Le so a coluna `accounts.gender`: nunca junta com localizacao, viagens ou
 * telemetria, e nunca devolve linha por pessoa.
 */
class GenderStatsRepository(
    private val dataSource: DataSource,
) {
    fun distribution(k: Long = GENDER_MIN_BUCKET): Map<Gender, Long> =
        suppressSmallBuckets(
            dataSource
                .read {
                    it.queryList(
                        "SELECT gender, count(*) AS n FROM accounts " +
                            "WHERE gender IS NOT NULL AND status <> 'deleted' GROUP BY gender",
                    ) { rs -> Gender.fromWire(rs.getString("gender")) to rs.getLong("n") }
                }.mapNotNull { (g, n) -> g?.let { it to n } }
                .toMap(),
            k,
        )
}
