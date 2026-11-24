package br.malhaviva.auth

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

/** Liga os parametros na ordem; Instant vira Timestamp. */
internal fun PreparedStatement.bind(values: List<Any>): PreparedStatement {
    values.forEachIndexed { i, v -> setObject(i + 1, if (v is Instant) Timestamp.from(v) else v) }
    return this
}

internal fun Connection.update(
    sql: String,
    vararg values: Any,
): Int = prepareStatement(sql).use { it.bind(values.toList()).executeUpdate() }

internal fun Connection.exists(
    sql: String,
    vararg values: Any,
): Boolean = prepareStatement(sql).use { st -> st.bind(values.toList()).executeQuery().use { it.next() } }

internal fun <T> Connection.queryList(
    sql: String,
    vararg values: Any,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(values.toList()).executeQuery().use { rs ->
            generateSequence { if (rs.next()) map(rs) else null }.toList()
        }
    }

internal fun <T> DataSource.read(block: (Connection) -> T): T = connection.use(block)

internal fun <T> DataSource.tx(block: (Connection) -> T): T =
    connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: SQLException) {
            c.rollback()
            throw e
        }
    }
