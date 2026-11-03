package ru.arc.graves

import com.google.gson.Gson
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.network.BackendServerId
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * Durable shared grave storage. Calls are nonblocking and perform no Bukkit
 * work; successful completion acknowledges a committed SQL operation.
 * Insert accepts an exact retry and rejects a conflicting record at that id.
 * Replace is strict revision/payload CAS; a stale or repeated replacement is
 * false. One pending operation per owner and inventory scope is enforced across backends;
 * an owner collision is false. Transport, validation and corruption are exceptional.
 * [closeAsync] rejects new work, drains accepted work and releases the SQL lease.
 */
internal interface NativeGraveRepository : AutoCloseable {
    /** Full admin/test read, including empty CLAIMED tombstones. Ordinary refreshes use [loadCurrent]. */
    fun loadAll(): CompletableFuture<List<NativeGraveRecord>>
    /** Current administrator browse, including offline owners on every backend. */
    fun loadActive(now: Long, server: String? = null): CompletableFuture<List<NativeGraveRecord>> =
        loadAll().thenApply { rows -> rows.filter { it.phase == NativeGravePhase.ACTIVE &&
            (it.expiresAt < 0 || it.expiresAt > now) && (server == null || it.server == server) } }
    fun findActive(id: UUID, now: Long): CompletableFuture<NativeGraveRecord?> =
        loadActive(now).thenApply { rows -> rows.firstOrNull { it.id == id } }
    /** Local or requested-owner graves: live ACTIVE plus durable unfinished/import/review records. */
    fun loadCurrent(server: String, owners: Set<UUID>, now: Long): CompletableFuture<List<NativeGraveRecord>>
    fun loadWatermarks(owners: Set<UUID>): CompletableFuture<Map<UUID, NativeGraveWatermark>>
    /** Positive globally ordered receipt number; exact retries retain it, another owner is exceptional. Gaps are allowed. */
    fun reserveSequence(operationId: UUID, ownerId: UUID): CompletableFuture<Long>
    fun insert(record: NativeGraveRecord): CompletableFuture<Boolean>
    fun replace(expected: NativeGraveRecord, next: NativeGraveRecord): CompletableFuture<Boolean>
    fun closeAsync(): CompletableFuture<Unit>

    /** Starts cleanup without blocking; lifecycle owners must await [closeAsync]. */
    override fun close() { closeAsync() }
}

internal class MySqlNativeGraveRepository private constructor(
    private val runtime: SqlRuntime,
    private val migration: CompletableFuture<Unit>,
) : NativeGraveRepository {
    private val lifecycle = Any()
    private var closing = false
    private var inFlight = 0
    private val drained = CompletableFuture<Unit>()
    private val cleanup = BoundedAsyncExecutor("ARC-native-graves-close", 1, 1)
    private val closed = drained.thenCompose {
        cleanup.closeAsync {
            runtime.executor.closeAsync().join()
            runtime.close()
        }
    }

    override fun loadAll(): CompletableFuture<List<NativeGraveRecord>> = admit {
        migration.thenCompose {
            runtime.executor.read { connection ->
                connection.prepareStatement("SELECT $COLUMNS FROM $TABLE ORDER BY id").use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(decodeRow(rows).record) }
                    }
                }
            }
        }
    }

    override fun loadActive(now: Long, server: String?): CompletableFuture<List<NativeGraveRecord>> = admit {
        require(now >= 0) { "Grave query time is invalid" }
        require(server == null || BackendServerId.parseOrNull(server) != null) { "Grave query server is invalid" }
        migration.thenCompose {
            runtime.executor.read { connection ->
                queryRecords(connection, "phase = 'ACTIVE' AND (expires_at < 0 OR expires_at > ?)" +
                    if (server == null) "" else " AND server = ?") { statement ->
                    statement.setLong(1, now)
                    if (server != null) statement.setString(2, server)
                }
            }
        }
    }

    override fun findActive(id: UUID, now: Long): CompletableFuture<NativeGraveRecord?> = admit {
        require(now >= 0) { "Grave query time is invalid" }
        migration.thenCompose {
            runtime.executor.read { connection ->
                queryRecords(connection, "id = ? AND phase = 'ACTIVE' AND (expires_at < 0 OR expires_at > ?)") { statement ->
                    statement.setString(1, id.toString())
                    statement.setLong(2, now)
                }.singleOrNull()
            }
        }
    }

    override fun loadCurrent(server: String, owners: Set<UUID>, now: Long): CompletableFuture<List<NativeGraveRecord>> = admit {
        val requested = owners.toSet()
        migration.thenCompose {
            runtime.executor.read { connection ->
                require(BackendServerId.parseOrNull(server) != null) { "Grave query server is invalid" }
                require(now >= 0) { "Grave query time is invalid" }
                buildList {
                    addAll(queryRecords(connection, "server = ? AND $CURRENT") {
                        it.setString(1, server)
                        it.setLong(2, now)
                    })
                    requested.chunked(OWNER_QUERY_BATCH).forEach { batch ->
                        addAll(queryRecords(connection, "owner_id IN (${placeholders(batch.size)}) AND server <> ? AND $CURRENT") { statement ->
                            batch.forEachIndexed { index, owner -> statement.setString(index + 1, owner.toString()) }
                            statement.setString(batch.size + 1, server)
                            statement.setLong(batch.size + 2, now)
                        })
                    }
                }
            }
        }
    }

    override fun loadWatermarks(owners: Set<UUID>): CompletableFuture<Map<UUID, NativeGraveWatermark>> = admit {
        val requested = owners.toSet()
        if (requested.isEmpty()) CompletableFuture.completedFuture(emptyMap()) else migration.thenCompose {
            runtime.executor.read { connection ->
                buildMap {
                    requested.chunked(OWNER_QUERY_BATCH).forEach { batch ->
                        connection.prepareStatement("SELECT * FROM $WATERMARKS WHERE owner_id IN (${placeholders(batch.size)})").use { statement ->
                            batch.forEachIndexed { index, owner -> statement.setString(index + 1, owner.toString()) }
                            statement.executeQuery().use { rows ->
                                while (rows.next()) decodeWatermark(rows).let { put(it.ownerId, it) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun reserveSequence(operationId: UUID, ownerId: UUID): CompletableFuture<Long> = admit {
        migration.thenCompose {
            runtime.executor.transaction { connection ->
                connection.prepareStatement("INSERT IGNORE INTO $SEQUENCES (operation_id, owner_id) VALUES (?, ?)").use { statement ->
                    statement.setString(1, operationId.toString())
                    statement.setString(2, ownerId.toString())
                    statement.executeUpdate()
                }
                connection.prepareStatement("SELECT `sequence`, owner_id FROM $SEQUENCES WHERE operation_id = ? FOR UPDATE").use { statement ->
                    statement.setString(1, operationId.toString())
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Native grave sequence reservation is missing" }
                        check(rows.getString("owner_id") == ownerId.toString()) { "Native grave operation belongs to another owner" }
                        rows.getLong("sequence").also { check(it > 0) { "Native grave operation sequence is invalid" } }
                    }
                }
            }
        }
    }

    override fun insert(record: NativeGraveRecord): CompletableFuture<Boolean> = admit {
        migration.thenCompose {
            runtime.executor.transaction { connection ->
                val payload = encode(record)
                connection.prepareStatement(INSERT).use { statement ->
                    bindRecord(statement, record, payload)
                    try {
                        statement.executeUpdate()
                    } catch (failure: SQLException) {
                        if (!failure.isDuplicateKey()) throw failure
                    }
                }
                val current = readLocked(connection, record.id.toString()) ?: return@transaction false
                current.bytes.contentEquals(payload.bytes) && current.hash == payload.hash
            }
        }
    }

    override fun replace(expected: NativeGraveRecord, next: NativeGraveRecord): CompletableFuture<Boolean> = admit {
        migration.thenCompose {
            runtime.executor.transaction { connection ->
                require(expected.id == next.id) { "Grave replacement id differs" }
                require(expected.revision < Long.MAX_VALUE && next.revision == expected.revision + 1) {
                    "Grave replacement revision must advance exactly once"
                }
                val before = encode(expected)
                val after = encode(next)
                val current = readLocked(connection, expected.id.toString()) ?: return@transaction false
                if (current.record.revision != expected.revision || current.hash != before.hash ||
                    !current.bytes.contentEquals(before.bytes)
                ) return@transaction false
                connection.prepareStatement(UPDATE).use { statement ->
                    bindRecord(statement, next, after)
                    statement.setString(11, expected.id.toString())
                    statement.setLong(12, expected.revision)
                    statement.setString(13, before.hash)
                    try {
                        if (statement.executeUpdate() != 1) return@transaction false
                    } catch (failure: SQLException) {
                        if (!failure.isDuplicateKey()) throw failure
                        return@transaction false
                    }
                }
                if (next.completedSequence > expected.completedSequence) {
                    advanceWatermark(connection, NativeGraveWatermark(next.ownerId, next.completedSequence, requireNotNull(next.checkpoint)))
                }
                true
            }
        }
    }

    private fun queryRecords(connection: Connection, where: String, bind: (PreparedStatement) -> Unit): List<NativeGraveRecord> =
        connection.prepareStatement("SELECT $COLUMNS FROM $TABLE WHERE $where ORDER BY id").use { statement ->
            bind(statement)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(decodeRow(rows).record) } }
        }

    private fun advanceWatermark(connection: Connection, watermark: NativeGraveWatermark) {
        watermark.validate()
        val bytes = NativeGraveInventoryPayload.encode(watermark.inventory)
        val hash = bytes.sha256()
        connection.prepareStatement(UPSERT_WATERMARK).use { statement ->
            statement.setString(1, watermark.ownerId.toString())
            statement.setLong(2, watermark.sequence)
            statement.setBytes(3, bytes)
            statement.setString(4, hash)
            statement.setLong(5, watermark.sequence)
            statement.setBytes(6, bytes)
            statement.setLong(7, watermark.sequence)
            statement.setString(8, hash)
            statement.setLong(9, watermark.sequence)
            statement.executeUpdate()
        }
    }

    private fun decodeWatermark(rows: ResultSet): NativeGraveWatermark {
        val owner = rows.getString("owner_id")
        try {
            val bytes = requireNotNull(rows.getBytes("checkpoint_payload")) { "Grave checkpoint is missing" }
            check(bytes.size <= NativeGravePayload.MAX_BYTES) { "Grave checkpoint is too large" }
            check(rows.getString("checkpoint_hash") == bytes.sha256()) { "Grave checkpoint checksum differs" }
            return NativeGraveWatermark(
                UUID.fromString(owner), rows.getLong("sequence"), NativeGraveInventoryPayload.decode(bytes),
            ).also { it.validate() }
        } catch (failure: Exception) {
            throw IllegalStateException("Native grave watermark is corrupt owner=$owner", failure)
        }
    }

    private fun readLocked(connection: Connection, id: String): Stored? =
        connection.prepareStatement("SELECT $COLUMNS FROM $TABLE WHERE id = ? FOR UPDATE").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> if (rows.next()) decodeRow(rows) else null }
        }

    private fun <T> admit(operation: () -> CompletableFuture<T>): CompletableFuture<T> {
        synchronized(lifecycle) {
            if (closing) return CompletableFuture.failedFuture(RejectedExecutionException("Native grave repository is closed"))
            inFlight++
        }
        val result = try { operation() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        result.whenComplete { _, _ ->
            val finished = synchronized(lifecycle) { --inFlight; closing && inFlight == 0 }
            if (finished) drained.complete(Unit)
        }
        // Cancelling an observer must not cancel accepted writes or their drain.
        return result.copy()
    }

    override fun closeAsync(): CompletableFuture<Unit> {
        val finished = synchronized(lifecycle) { closing = true; inFlight == 0 }
        if (finished) drained.complete(Unit)
        return closed.copy()
    }

    private data class Encoded(val bytes: ByteArray, val hash: String)
    private data class Stored(val record: NativeGraveRecord, val bytes: ByteArray, val hash: String)

    private fun encode(record: NativeGraveRecord): Encoded = NativeGravePayload.encode(record).let { Encoded(it, it.sha256()) }

    private fun decodeRow(rows: ResultSet): Stored {
        val id = rows.getString("id")
        try {
            check(rows.getInt("payload_version") == NativeGravePayload.VERSION) { "Unsupported grave payload version" }
            val bytes = requireNotNull(rows.getBytes("payload")) { "Grave payload is missing" }
            check(bytes.size <= NativeGravePayload.MAX_BYTES) { "Grave payload is too large" }
            val hash = rows.getString("payload_hash")
            check(hash == bytes.sha256()) { "Grave payload checksum differs" }
            val record = NativeGravePayload.decode(bytes)
            check(record.id.toString() == id && record.ownerId.toString() == rows.getString("owner_id") &&
                record.server == rows.getString("server") && record.phase.name == rows.getString("phase") &&
                record.revision == rows.getLong("revision") && record.expiresAt == rows.getLong("expires_at") &&
                record.pendingOwnerId() == rows.getString("pending_owner")
            ) { "Grave indexed fields differ from payload" }
            return Stored(record, bytes, hash)
        } catch (failure: Exception) {
            throw IllegalStateException("Native grave row is corrupt id=$id", failure)
        }
    }

    private fun bindRecord(statement: PreparedStatement, record: NativeGraveRecord, payload: Encoded) {
        statement.setString(1, record.id.toString())
        statement.setString(2, record.ownerId.toString())
        statement.setString(3, record.server)
        statement.setString(4, record.phase.name)
        statement.setLong(5, record.revision)
        statement.setInt(6, NativeGravePayload.VERSION)
        statement.setString(7, payload.hash)
        statement.setBytes(8, payload.bytes)
        val pendingOwner = record.pendingOwnerId()
        if (pendingOwner != null) statement.setString(9, pendingOwner) else statement.setNull(9, Types.VARCHAR)
        statement.setLong(10, record.expiresAt)
    }

    companion object {
        private const val TABLE = "arc_native_graves"
        private const val SEQUENCES = "arc_native_grave_sequences"
        private const val WATERMARKS = "arc_native_grave_watermarks"
        private const val OWNER_QUERY_BATCH = 256
        private const val CURRENT = "(phase IN ('PREPARED', 'CLAIMING', 'REVIEW', 'IMPORTED') OR (phase = 'ACTIVE' AND (expires_at < 0 OR expires_at > ?)))"
        private const val COLUMNS = "id, owner_id, server, phase, revision, payload_version, payload_hash, payload, pending_owner, expires_at"
        private const val INSERT = "INSERT INTO $TABLE ($COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val UPDATE = "UPDATE $TABLE SET id = ?, owner_id = ?, server = ?, phase = ?, revision = ?, payload_version = ?, payload_hash = ?, payload = ?, pending_owner = ?, expires_at = ? WHERE id = ? AND revision = ? AND payload_hash = ? AND payload_version = 1"
        private const val UPSERT_WATERMARK = "INSERT INTO $WATERMARKS (owner_id, `sequence`, checkpoint_payload, checkpoint_hash) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE checkpoint_payload = IF(`sequence` < ?, ?, checkpoint_payload), checkpoint_hash = IF(`sequence` < ?, ?, checkpoint_hash), `sequence` = GREATEST(`sequence`, ?)"
        private val SCHEMA = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                server VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                expires_at BIGINT NOT NULL,
                revision BIGINT NOT NULL,
                payload_version SMALLINT NOT NULL,
                payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                payload LONGBLOB NOT NULL,
                pending_owner VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
                UNIQUE INDEX pending_owner (pending_owner),
                INDEX owner_phase (owner_id, phase, expires_at),
                INDEX server_phase (server, phase, expires_at),
                INDEX phase (phase)
            ) ENGINE=InnoDB
        """.trimIndent()
        private val WATERMARK_SCHEMA = """
            CREATE TABLE IF NOT EXISTS $WATERMARKS (
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
                `sequence` BIGINT NOT NULL,
                checkpoint_payload LONGBLOB NOT NULL,
                checkpoint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
            ) ENGINE=InnoDB
        """.trimIndent()

        private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
        private val SEQUENCE_SCHEMA = """
            CREATE TABLE IF NOT EXISTS $SEQUENCES (
                `sequence` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                UNIQUE INDEX operation_id (operation_id)
            ) ENGINE=InnoDB
        """.trimIndent()

        /**
         * Worker/maintenance-thread only: runtime acquisition may open a shared
         * SQL lease/pool. Migration and every operation then run on SQL workers.
         * Uses the installed shared SqlRuntime factory and its core-owned test fallback.
         */
        fun open(config: SqlConnectionConfig): MySqlNativeGraveRepository {
            val runtime = SqlRuntime.create(config.copy(failFast = false), "ARC-native-graves")
            return try {
                val migration = runtime.executor.submit {
                    MySqlMigrator(runtime.dataSource, "arc_native_graves").migrate(
                        listOf(
                            SqlMigration(1, "create shared native graves", listOf(SCHEMA)),
                            SqlMigration(2, "create native grave receipt sequences", listOf(SEQUENCE_SCHEMA)),
                            SqlMigration(3, "create native grave owner checkpoints", listOf(WATERMARK_SCHEMA)),
                        ),
                    )
                    Unit
                }
                MySqlNativeGraveRepository(runtime, migration)
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
        }
    }
}

/** Strict canonical JSON keeps full-payload CAS stable and rejects lossy Gson coercion. */
internal object NativeGravePayload {
    const val VERSION = 1
    const val MAX_BYTES = 12_000_000
    private val operation = JsonObjectContract(
        setOf("id", "server", "kind", "before", "after", "sequence", "inventoryScope"),
        fieldContracts = mapOf("before" to NativeGraveInventoryPayload.contract, "after" to NativeGraveInventoryPayload.contract),
    )
    private val fields = setOf(
        "id", "ownerId", "ownerName", "server", "world", "x", "y", "z", "createdAt", "expiresAt",
        "experience", "items", "revision", "phase", "operation", "completedSequence", "checkpoint",
    )
    private val codec = BoundedJsonCodec(
        gson = Gson(),
        type = NativeGraveRecord::class.java,
        rootContract = JsonObjectContract(
            allowedFields = fields,
            requiredFields = fields - setOf("operation", "checkpoint"),
            fieldContracts = mapOf(
                "items" to JsonArrayContract(maxEntries = NativeGraveRecord.MAX_ITEMS),
                "operation" to operation,
                "checkpoint" to NativeGraveInventoryPayload.contract,
            ),
        ),
        bounds = JsonResourceBounds(
            maxCharacters = MAX_BYTES,
            maxDepth = 6,
            maxContainerEntries = NativeGraveRecord.MAX_ITEMS,
            maxTotalNodes = 512,
            maxStringCharacters = NativeGraveRecord.MAX_ENCODED_ITEM_CHARACTERS,
        ),
        validate = NativeGraveRecord::validate,
    )

    fun encode(record: NativeGraveRecord): ByteArray = codec.encode(record).toByteArray(StandardCharsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Grave payload is too large" }
    }

    fun decode(bytes: ByteArray): NativeGraveRecord {
        require(bytes.size <= MAX_BYTES) { "Grave payload is too large" }
        val json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val record = codec.decode(json).let { it.copy(operation = it.operation?.immutableCopy(), checkpoint = it.checkpoint?.copy()) }
        require(encode(record).contentEquals(bytes)) { "Grave payload is not canonical JSON" }
        return record
    }
}

internal object NativeGraveInventoryPayload {
    val contract = JsonObjectContract(
        setOf("slots", "level", "progress", "totalExperience"),
        fieldContracts = mapOf("slots" to JsonArrayContract(41, 41)),
    )
    private val codec = BoundedJsonCodec(
        gson = Gson(),
        type = NativeGraveInventory::class.java,
        rootContract = contract,
        bounds = JsonResourceBounds(
            maxCharacters = NativeGravePayload.MAX_BYTES,
            maxDepth = 3,
            maxContainerEntries = 41,
            maxTotalNodes = 64,
            maxStringCharacters = NativeGraveRecord.MAX_ENCODED_ITEM_CHARACTERS,
        ),
        validate = NativeGraveInventory::validate,
    )

    fun encode(inventory: NativeGraveInventory): ByteArray = codec.encode(inventory).toByteArray(StandardCharsets.UTF_8).also {
        require(it.size <= NativeGravePayload.MAX_BYTES) { "Grave checkpoint is too large" }
    }

    fun decode(bytes: ByteArray): NativeGraveInventory {
        require(bytes.size <= NativeGravePayload.MAX_BYTES) { "Grave checkpoint is too large" }
        val json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val inventory = codec.decode(json).copy()
        require(encode(inventory).contentEquals(bytes)) { "Grave checkpoint is not canonical JSON" }
        return inventory
    }
}

private fun ByteArray.sha256(): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))

private fun SQLException.isDuplicateKey(): Boolean = sqlState == "23000" && errorCode == 1062
