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
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Synchronized
    fun load(jobId: String): MigrationJournal? {
        require(SAFE_JOB_ID.matches(jobId)) { "Unsafe LuckPerms migration job id" }
        val path = pathFor(jobId)
        if (!Files.isRegularFile(path)) return null
        return gson.fromJson(Files.readString(path), MigrationJournal::class.java)
    }

    @Synchronized
    fun loadAll(): List<MigrationJournal> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .map { gson.fromJson(Files.readString(it), MigrationJournal::class.java) }
                .toList()
        }
    }

    private fun pathFor(jobId: String): Path = directory.resolve("$jobId.json")

    companion object {
        private val SAFE_JOB_ID = Regex("[a-zA-Z0-9-]+")
    }
}

data class MigrationJournal(
    val version: Int = 1,
    val jobId: String,
    val migrationId: String,
    val contentHash: String,
    var state: LpMigrationState,
    val requestJson: String,
    var reviewTokens: MutableList<String> = mutableListOf(),
    var planJson: MutableList<String> = mutableListOf(),
    var completedSubjects: Int = 0,
    var rollbackCompletedSubjects: Int = 0,
    var currentSubjectIndex: Int? = null,
    var failures: MutableList<String> = mutableListOf(),
)
