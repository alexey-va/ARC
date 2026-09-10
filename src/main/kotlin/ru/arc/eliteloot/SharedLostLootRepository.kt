package ru.arc.eliteloot

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal enum class SharedLootState { AVAILABLE, CLAIMING, CLAIMED, SOLD, CREDITING, PAID }

internal data class SharedLootRecord(
    val id: String,
    val owner: String,
    val item: String?,
    val itemHash: String,
    val capturedAt: Long,
    val sourceServer: String,
    val state: SharedLootState,
    val token: String?,
    val operationServer: String?,
    val salePrice: Double?,
    val nativePrice: Double?,
)

internal interface SharedLostLootRepository : AutoCloseable {
    fun publish(record: LostLootRecord, sourceServer: String): CompletableFuture<Unit>
    fun list(owner: UUID): CompletableFuture<List<SharedLootRecord>>
    fun nextPending(owner: UUID): CompletableFuture<SharedLootRecord?>
    fun reserve(id: String, owner: UUID, state: SharedLootState, token: UUID, server: String, salePrice: Double?): CompletableFuture<SharedLootRecord?>
    fun sell(id: String, owner: UUID, token: UUID, expectedPrice: Double): CompletableFuture<Boolean>
    fun release(id: String, owner: UUID, token: UUID, state: SharedLootState): CompletableFuture<Boolean>
    fun finish(record: SharedLootRecord): CompletableFuture<Boolean>
}

internal class MySqlSharedLostLootRepository private constructor(private val runtime: SqlRuntime, private var migration: CompletableFuture<Unit>) : SharedLostLootRepository {
    override fun publish(record: LostLootRecord, sourceServer: String) = ready().thenCompose {
        validatePublish(record, sourceServer)
        runtime.executor.transaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                statement.setString(1, record.id); statement.setString(2, record.owner); statement.setString(3, record.item)
                statement.setString(4, itemHash(record.item!!)); statement.setLong(5, record.capturedAt); statement.setString(6, sourceServer)
                statement.setObject(7, nativePrice(record))
                statement.executeUpdate()
            }
            connection.prepareStatement(SELECT_BY_ID).use { statement ->
                statement.setString(1, record.id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Shared lost loot publish readback missing ${record.id}" }
                    check(rows.getString("owner") == record.owner && rows.getString("item_hash") == itemHash(requireNotNull(record.item))) {
                        "Shared lost loot identity or payload mismatch for ${record.id}"
                    }
                }
            }
            Unit
        }
    }

    override fun list(owner: UUID) = ready().thenCompose {
        runtime.executor.read { connection ->
            connection.prepareStatement(LIST).use { statement ->
                statement.setString(1, owner.toString())
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
            }
        }
    }

    override fun nextPending(owner: UUID) = ready().thenCompose {
        runtime.executor.read { connection ->
            connection.prepareStatement(NEXT_PENDING).use { statement ->
                statement.setString(1, owner.toString())
                statement.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
            }
        }
    }

    override fun reserve(id: String, owner: UUID, state: SharedLootState, token: UUID, server: String, salePrice: Double?) = ready().thenCompose {
        validateReserve(id, owner, state, token, server, salePrice)
        runtime.executor.transaction { connection ->
            connection.prepareStatement(GATE_INSERT).use { statement ->
                statement.setString(1, owner.toString()); statement.setString(2, id); statement.setString(3, token.toString()); statement.setString(4, state.name)
                if (statement.executeUpdate() != 1) return@transaction null
            }
            connection.prepareStatement(RESERVE).use { statement ->
                statement.setString(1, state.name); statement.setString(2, token.toString()); statement.setString(3, server)
                if (salePrice == null) statement.setNull(4, Types.DOUBLE) else statement.setDouble(4, salePrice)
                statement.setString(5, id); statement.setString(6, owner.toString()); statement.setString(7, if (state == SharedLootState.CREDITING) "SOLD" else "AVAILABLE")
                if (statement.executeUpdate() != 1) {
                    deleteGate(connection, owner.toString(), id, token.toString())
                    return@transaction null
                }
            }
            connection.prepareStatement(SELECT_BY_ID).use { statement ->
                statement.setString(1, id); statement.executeQuery().use { rows -> check(rows.next()); read(rows) }
            }
        }
    }

    override fun sell(id: String, owner: UUID, token: UUID, expectedPrice: Double) = ready().thenCompose {
        validateUuid(id); require(expectedPrice.isFinite() && expectedPrice > 0.0)
        runtime.executor.transaction { connection ->
            connection.prepareStatement(SELL).use { statement ->
                statement.setString(1, token.toString()); statement.setString(2, id); statement.setString(3, owner.toString()); statement.setDouble(4, expectedPrice)
                statement.executeUpdate() == 1
            }
        }
    }

    override fun release(id: String, owner: UUID, token: UUID, state: SharedLootState) = ready().thenCompose {
        validateOperation(id, owner, token, state)
        runtime.executor.transaction { connection ->
            connection.prepareStatement(RELEASE).use { statement ->
                statement.setString(1, id); statement.setString(2, owner.toString()); statement.setString(3, token.toString()); statement.setString(4, state.name)
                val changed = statement.executeUpdate() == 1
                if (changed) deleteGate(connection, owner.toString(), id, token.toString())
                changed
            }
        }
    }

    override fun finish(record: SharedLootRecord) = ready().thenCompose {
        validateFinish(record)
        runtime.executor.transaction { connection ->
            connection.prepareStatement(FINISH).use { statement ->
                statement.setString(1, record.state.name); statement.setString(2, record.id); statement.setString(3, record.owner); statement.setString(4, record.token)
                statement.setString(5, if (record.state == SharedLootState.PAID) SharedLootState.CREDITING.name else SharedLootState.CLAIMING.name)
                if (statement.executeUpdate() == 1) {
                    deleteGate(connection, record.owner, record.id, record.token!!)
                    return@transaction true
                }
            }
            connection.prepareStatement(FINAL_MATCH).use { statement ->
                statement.setString(1, record.id); statement.setString(2, record.owner); statement.setString(3, record.token); statement.setString(4, record.state.name)
                statement.executeQuery().use {
                    val sameOperation = it.next()
                    if (sameOperation) deleteGate(connection, record.owner, record.id, record.token!!)
                    sameOperation
                }
            }
        }
    }

    @Synchronized private fun ready(): CompletableFuture<Unit> {
        if (migration.isCompletedExceptionally) migration = migrate(runtime)
        return migration
    }

    override fun close() = runtime.close()

    private fun deleteGate(connection: Connection, owner: String, id: String, token: String) {
        connection.prepareStatement(GATE_DELETE).use { statement ->
            statement.setString(1, owner); statement.setString(2, id); statement.setString(3, token); statement.executeUpdate()
        }
    }

    companion object {
        private const val TABLE = "arc_shared_lost_loot"
        private const val MAX_ITEM = 262_144
        private const val MAX_SERVER = 64
        private const val GATE = "arc_shared_lost_loot_owner_gate"
        private const val INSERT = "INSERT IGNORE INTO $TABLE (entity_uuid, owner, item, item_hash, captured_at, source_server, native_price, state) VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE')"
        private const val SELECT_BY_ID = "SELECT entity_uuid, owner, item, item_hash, captured_at, source_server, state, token, operation_server, sale_price, native_price FROM $TABLE WHERE entity_uuid = ?"
        private const val LIST = "SELECT entity_uuid, owner, item, item_hash, captured_at, source_server, state, token, operation_server, sale_price, native_price FROM $TABLE WHERE owner = ? AND state IN ('AVAILABLE','CLAIMING','SOLD','CREDITING') ORDER BY captured_at, entity_uuid"
        private const val NEXT_PENDING = "SELECT entity_uuid, owner, item, item_hash, captured_at, source_server, state, token, operation_server, sale_price, native_price FROM $TABLE WHERE owner = ? AND state IN ('CLAIMING','CREDITING','SOLD') ORDER BY CASE WHEN state = 'SOLD' THEN 1 ELSE 0 END, captured_at, entity_uuid LIMIT 1"
        private const val GATE_INSERT = "INSERT IGNORE INTO $GATE (owner, entity_uuid, token, state) VALUES (?, ?, ?, ?)"
        private const val GATE_DELETE = "DELETE FROM $GATE WHERE owner = ? AND entity_uuid = ? AND token = ?"
        private const val RESERVE = "UPDATE $TABLE SET state = ?, token = ?, operation_server = ?, sale_price = COALESCE(?, sale_price) WHERE entity_uuid = ? AND owner = ? AND state = ?"
        private const val SELL = "UPDATE $TABLE SET state = 'SOLD', token = ?, sale_price = native_price, item = NULL WHERE entity_uuid = ? AND owner = ? AND state = 'AVAILABLE' AND native_price = ?"
        private const val RELEASE = "UPDATE $TABLE SET sale_price = CASE WHEN state = 'CREDITING' THEN sale_price ELSE NULL END, state = CASE WHEN state = 'CREDITING' THEN 'SOLD' ELSE 'AVAILABLE' END, token = NULL, operation_server = NULL WHERE entity_uuid = ? AND owner = ? AND token = ? AND state = ?"
        private const val FINISH = "UPDATE $TABLE SET state = ?, item = NULL WHERE entity_uuid = ? AND owner = ? AND token = ? AND state = ?"
        private const val FINAL_MATCH = "SELECT entity_uuid FROM $TABLE WHERE entity_uuid = ? AND owner = ? AND token = ? AND state = ?"
        private const val SCHEMA = "CREATE TABLE IF NOT EXISTS $TABLE (entity_uuid CHAR(36) NOT NULL PRIMARY KEY, owner CHAR(36) NOT NULL, item MEDIUMTEXT NULL, item_hash CHAR(64) NOT NULL, captured_at BIGINT NOT NULL, source_server VARCHAR(64) NOT NULL, native_price DOUBLE NULL, state VARCHAR(16) NOT NULL, token CHAR(36) NULL, operation_server VARCHAR(64) NULL, sale_price DOUBLE NULL, INDEX owner_time (owner, captured_at), INDEX state_time (state, captured_at)) ENGINE=InnoDB"
        private const val GATE_SCHEMA = "CREATE TABLE IF NOT EXISTS $GATE (owner CHAR(36) NOT NULL PRIMARY KEY, entity_uuid CHAR(36) NOT NULL, token CHAR(36) NOT NULL, state VARCHAR(16) NOT NULL) ENGINE=InnoDB"

        fun open(config: SqlConnectionConfig, runtimeName: String = "ARC-shared-lost-loot"): MySqlSharedLostLootRepository {
            val runtime = SqlRuntime.create(config.copy(failFast = false), runtimeName)
            val ready = migrate(runtime)
            return MySqlSharedLostLootRepository(runtime, ready)
        }

        private fun migrate(runtime: SqlRuntime) = runtime.executor.submit {
            MySqlMigrator(runtime.dataSource, "arc_shared_lost_loot").migrate(
                listOf(SqlMigration(1, "create shared lost elite loot mailbox", listOf(SCHEMA, GATE_SCHEMA))))
            Unit
        }

        internal fun itemHash(item: String) = MessageDigest.getInstance("SHA-256").digest(item.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun nativePrice(record: LostLootRecord): Double? = record.nativePrice?.also { require(it.isFinite() && it > 0.0) }
        private fun validateUuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun validatePublish(record: LostLootRecord, source: String) { record.validate(); require(record.state == LostLootState.STORED); validateUuid(record.id); validateUuid(record.owner); require(source.isNotBlank() && source.length <= MAX_SERVER); require(record.item != null && record.item.length <= MAX_ITEM) }
        private fun validateReserve(id: String, owner: UUID, state: SharedLootState, token: UUID, server: String, price: Double?) { validateUuid(id); require(state == SharedLootState.CLAIMING || state == SharedLootState.CREDITING); require(server.isNotBlank() && server.length <= MAX_SERVER); require(price == null); }
        private fun validateOperation(id: String, owner: UUID, token: UUID, state: SharedLootState) { validateUuid(id); require(state == SharedLootState.CLAIMING || state == SharedLootState.CREDITING) }
        private fun validateFinish(record: SharedLootRecord) {
            validateUuid(record.id); validateUuid(record.owner)
            require(record.state == SharedLootState.CLAIMED || record.state == SharedLootState.PAID)
            require(record.token != null); validateUuid(record.token)
            require(record.itemHash.matches(Regex("[a-f0-9]{64}")))
            require((record.state == SharedLootState.PAID) == (record.salePrice != null))
            record.salePrice?.let { require(it.isFinite() && it > 0.0) }
            record.nativePrice?.let { require(it.isFinite() && it > 0.0) }
        }
        private fun read(rows: ResultSet) = SharedLootRecord(rows.getString("entity_uuid"), rows.getString("owner"), rows.getString("item"), rows.getString("item_hash"), rows.getLong("captured_at"), rows.getString("source_server"), SharedLootState.valueOf(rows.getString("state")), rows.getString("token"), rows.getString("operation_server"), rows.getObject("sale_price")?.let { (it as Number).toDouble() }, rows.getObject("native_price")?.let { (it as Number).toDouble() })
    }
}
