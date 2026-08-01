package ru.arc.ops.luckperms

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class LuckPermsMigrationStore(
    private val directory: Path,
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
) {
    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    fun save(journal: MigrationJournal) {
        require(SAFE_JOB_ID.matches(journal.jobId)) { "Unsafe LuckPerms migration journal id" }
        Files.createDirectories(directory)
        val target = pathFor(journal.jobId)
        val temp = Files.createTempFile(directory, ".${journal.jobId}-", ".tmp")
        val bytes = gson.toJson(journal).toByteArray(StandardCharsets.UTF_8)
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching {
                FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Synchronized
    fun load(jobId: String): MigrationJournal? {
        require(SAFE_JOB_ID.matches(jobId)) { "Unsafe LuckPerms migration job id" }
        val path = pathFor(jobId)
        if (!Files.isRegularFile(path)) return null
        return readJournal(path).also { journal ->
            require(journal.jobId == jobId) { "LuckPerms migration journal id does not match its filename" }
        }
    }

    @Synchronized
    fun loadAll(): List<MigrationJournal> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .map(::readJournal)
                .toList()
        }
    }

    private fun pathFor(jobId: String): Path = directory.resolve("$jobId.json")

    private fun readJournal(path: Path): MigrationJournal {
        val journal = gson.fromJson(Files.readString(path), MigrationJournal::class.java)
        require(journal.version == 1) { "Unsupported LuckPerms migration journal version: ${journal.version}" }
        require(SAFE_JOB_ID.matches(journal.jobId)) { "Unsafe LuckPerms migration journal id" }
        require(path.fileName.toString() == "${journal.jobId}.json") {
            "LuckPerms migration journal id does not match its filename"
        }
        require(journal.migrationId.isNotBlank()) { "LuckPerms migration journal id must not be blank" }
        require(journal.requestJson.isNotBlank()) { "LuckPerms migration journal request must not be blank" }
        val request = OpsLuckPermsJson.parseMigration(journal.requestJson)
        require(request.id == journal.migrationId) { "LuckPerms migration journal request id does not match" }
        require(journal.contentHash == migrationHash(journal.requestJson)) {
            "LuckPerms migration journal content hash does not match its request"
        }
        require(journal.completedSubjects in 0..request.subjects.size) {
            "Invalid LuckPerms completed subject count"
        }
        require(journal.rollbackCompletedSubjects in 0..journal.completedSubjects) {
            "Invalid LuckPerms rollback subject count"
        }
        journal.currentSubjectIndex?.let { index ->
            require(index in request.subjects.indices) {
                "Invalid LuckPerms current subject index"
            }
        }
        require(journal.planJson.size <= request.subjects.size) {
            "LuckPerms migration journal has too many reviewed plans"
        }
        require(journal.liveDigests.size <= request.subjects.size) {
            "LuckPerms migration journal has too many live digests"
        }
        require(journal.planDigests.size <= request.subjects.size) {
            "LuckPerms migration journal has too many plan digests"
        }
        return journal
    }

    companion object {
        private val SAFE_JOB_ID = Regex("[a-zA-Z0-9-]+")
    }
}

data class MigrationJournal(
    val version: Int = 1,
    val jobId: String = "",
    val migrationId: String = "",
    val contentHash: String = "",
    var state: LpMigrationState = LpMigrationState.PREVIEW_FAILED,
    val requestJson: String = "",
    var planJson: MutableList<String> = mutableListOf(),
    var liveDigests: MutableList<String> = mutableListOf(),
    var planDigests: MutableList<String> = mutableListOf(),
    var completedSubjects: Int = 0,
    var rollbackCompletedSubjects: Int = 0,
    var currentSubjectIndex: Int? = null,
    var recoveryPhase: LpMigrationRecoveryPhase? = null,
    var applyIdempotencyKey: String? = null,
    var rollbackIdempotencyKey: String? = null,
    var failures: MutableList<String> = mutableListOf(),
)
