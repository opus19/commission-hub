package kim.opus.hub.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

object Db {
    private lateinit var ds: HikariDataSource

    fun init(dbPath: Path) {
        dbPath.parent?.let { Files.createDirectories(it) }
        val url = buildString {
            append("jdbc:sqlite:")
            append(dbPath.toAbsolutePath().toString())
            append("?journal_mode=WAL")
            append("&synchronous=NORMAL")
            append("&busy_timeout=8000")
            append("&foreign_keys=on")
        }
        val cfg = HikariConfig()
        cfg.jdbcUrl = url
        cfg.driverClassName = "org.sqlite.JDBC"
        cfg.poolName = "hub-db"
        cfg.maximumPoolSize = 8
        cfg.minimumIdle = 1
        cfg.connectionTimeout = 10_000
        ds = HikariDataSource(cfg)
    }

    fun close() {
        if (::ds.isInitialized) ds.close()
    }

    fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)

    fun <T> tx(block: (Connection) -> T): T {
        ds.connection.use { c ->
            val prev = c.autoCommit
            c.autoCommit = false
            try {
                val result = block(c)
                c.commit()
                return result
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            } finally {
                runCatching { c.autoCommit = prev }
            }
        }
    }
}

private fun PreparedStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { i, a ->
        val idx = i + 1
        when (a) {
            null -> setObject(idx, null)
            is String -> setString(idx, a)
            is Int -> setInt(idx, a)
            is Long -> setLong(idx, a)
            is Boolean -> setInt(idx, if (a) 1 else 0)
            is Double -> setDouble(idx, a)
            is ByteArray -> setBytes(idx, a)
            else -> setString(idx, a.toString())
        }
    }
}

fun <T> Connection.rows(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> {
    prepareStatement(sql).use { ps ->
        ps.bindAll(args)
        ps.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(map(rs))
            return out
        }
    }
}

fun <T> Connection.row(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? {
    prepareStatement(sql).use { ps ->
        ps.bindAll(args)
        ps.executeQuery().use { rs ->
            return if (rs.next()) map(rs) else null
        }
    }
}

fun Connection.count(sql: String, vararg args: Any?): Long = row(sql, *args) { it.getLong(1) } ?: 0L

fun Connection.exec(sql: String, vararg args: Any?): Int {
    prepareStatement(sql).use { ps ->
        ps.bindAll(args)
        return ps.executeUpdate()
    }
}

fun Connection.insert(sql: String, vararg args: Any?): Long {
    prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
        ps.bindAll(args)
        ps.executeUpdate()
        ps.generatedKeys.use { rs ->
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }
}

fun ResultSet.longOrNull(column: String): Long? {
    val v = getLong(column)
    return if (wasNull()) null else v
}

fun ResultSet.intOrNull(column: String): Int? {
    val v = getInt(column)
    return if (wasNull()) null else v
}
