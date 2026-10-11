package ru.arc.itemcatalog

import com.google.gson.Gson
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlRuntime
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/** Shared immutable recipe storage. Bearer identity and one-use state stay in the existing ledger. */
internal interface SharedPhysicalRewardArchiveRepository : AutoCloseable {
    fun publishBatch(records: List<FrozenPhysicalRewardRecord>): CompletableFuture<Unit>
    fun fetch(fingerprint: String): CompletableFuture<ByteArray?>
    fun status(): SharedPhysicalRewardArchiveStatus
}

internal data class SharedPhysicalRewardArchiveStatus(
    val ready: Boolean,
    val failureCause: String? = null,
)

/** Content-addressed MySQL archive using the same connection settings as the voucher ledger. */
internal class MySqlSharedPhysicalRewardArchiveRepository private constructor(
    private val runtime: SqlRuntime,
    initialMigration: CompletableFuture<Unit>,
) : SharedPhysicalRewardArchiveRepository {
    @Volatile private var migration = initialMigration
    @Volatile private var lastFailure: String? = null

    init {
        migration.whenComplete { _, failure -> lastFailure = failure?.causeName() }
    }

    override fun publishBatch(records: List<FrozenPhysicalRewardRecord>): CompletableFuture<Unit> = ready().thenCompose {
        require(records.isNotEmpty() && records.size <= MAX_BATCH_RECORDS) { "Physical reward archive batch size is invalid" }
        val payloads = records.map(::encode)
        require(payloads.sumOf { it.bytes.size.toLong() } <= MAX_BATCH_BYTES) { "Physical reward archive batch is too large" }
        runtime.executor.transaction { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                payloads.forEach { payload ->
                    statement.setString(1, payload.fingerprint)
                    statement.setString(2, payload.hash)
                    statement.setBytes(3, payload.bytes)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement(SELECT).use { statement ->
                payloads.forEach { expected ->
                    statement.setString(1, expected.fingerprint)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "Physical reward archive publish readback missing ${expected.fingerprint}" }
                        val storedHash = rows.getString("payload_hash")
                        val storedBytes = rows.getBytes("payload")
                        check(storedHash == expected.hash && storedBytes.contentEquals(expected.bytes)) {
                            "Physical reward archive immutable content mismatch ${expected.fingerprint}"
                        }
                    }
                }
            }
            Unit
        }
    }.whenComplete { _, failure -> lastFailure = failure?.causeName() }

    override fun fetch(fingerprint: String): CompletableFuture<ByteArray?> {
        require(FINGERPRINT.matches(fingerprint)) { "Physical reward archive fingerprint is invalid" }
        return ready().thenCompose {
            runtime.executor.read { connection ->
                connection.prepareStatement(SELECT).use { statement ->
                    statement.setString(1, fingerprint)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) return@use null
                        val payload = rows.getBytes("payload")
                        check(payload.size <= MAX_RECORD_BYTES) { "Physical reward archive payload is too large" }
                        check(rows.getString("payload_hash") == payload.sha256()) {
                            "Physical reward archive payload checksum mismatch $fingerprint"
                        }
                        payload
                    }
                }
            }
        }.whenComplete { _, failure -> lastFailure = failure?.causeName() }
    }

    override fun status(): SharedPhysicalRewardArchiveStatus =
        SharedPhysicalRewardArchiveStatus(
            ready = migration.isDone && !migration.isCompletedExceptionally,
            failureCause = lastFailure,
        )

    @Synchronized private fun ready(): CompletableFuture<Unit> {
        if (migration.isCompletedExceptionally) migration = migrate(runtime).also { next ->
            next.whenComplete { _, failure -> lastFailure = failure?.causeName() }
        }
        return migration
    }

    override fun close() = runtime.close()

    private data class Encoded(val fingerprint: String, val hash: String, val bytes: ByteArray)

    private fun encode(record: FrozenPhysicalRewardRecord): Encoded {
        record.validate(validateBukkitStacks = false)
        val bytes = GSON.toJson(record).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= MAX_RECORD_BYTES) { "Physical reward archive record is too large" }
        return Encoded(record.fingerprint, bytes.sha256(), bytes)
    }

    companion object {
        private const val TABLE = "arc_catalog_physical_reward_archive"
        private const val NAMESPACE = "arc_catalog_physical_reward_archive"
        private const val MAX_BATCH_RECORDS = 4
        private const val MAX_BATCH_BYTES = 64L * 1024L * 1024L
        private const val MAX_RECORD_BYTES = FrozenPhysicalRewards.MAX_RECORD_BYTES
        private const val INSERT = "INSERT IGNORE INTO $TABLE (fingerprint, payload_hash, payload) VALUES (?, ?, ?)"
        private const val SELECT = "SELECT payload_hash, payload FROM $TABLE WHERE fingerprint = ?"
        private val FINGERPRINT = Regex("[a-f0-9]{64}")
        private val GSON = Gson()
        private val SCHEMA = "CREATE TABLE IF NOT EXISTS $TABLE (fingerprint CHAR(64) NOT NULL PRIMARY KEY, payload_hash CHAR(64) NOT NULL, payload LONGBLOB NOT NULL) ENGINE=InnoDB"

        fun open(config: SqlConnectionConfig): MySqlSharedPhysicalRewardArchiveRepository {
            val runtime = SqlRuntime.create(config.copy(failFast = false), "ARC-physical-reward-archive")
            return MySqlSharedPhysicalRewardArchiveRepository(runtime, migrate(runtime))
        }

        private fun migrate(runtime: SqlRuntime) = runtime.executor.submit {
            MySqlMigrator(runtime.dataSource, NAMESPACE).migrate(
                listOf(SqlMigration(1, "create shared physical reward archive", listOf(SCHEMA))),
            )
            Unit
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun Throwable.causeName(): String = generateSequence(this) { it.cause }.last().javaClass.simpleName
