package ru.arc.treasurechests

import com.google.gson.Gson
import ru.arc.persistence.DurableRecordJournal
import java.nio.file.Path

/** Blocking storage port; the controller invokes it only on its single in-flight I/O task. */
internal interface HuntAutoStartStore {
    fun loadOrInitialize(initialNotBefore: Long): Long
    fun reserve(nextAttemptAt: Long): Long
}

/** Uses the shared atomic commit/readback barrier; construction performs no disk access. */
internal class FileHuntAutoStartStore(root: Path) : HuntAutoStartStore {
    private val gson = Gson()
    private val journal by lazy {
        DurableRecordJournal(
            root = root,
            relativeDirectory = Path.of("data", "hunt-auto-start"),
            maxRecordBytes = 1024L,
            encode = { record: HuntAutoStartRecord -> gson.toJson(record).toByteArray(Charsets.UTF_8) },
            decode = { bytes -> requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8), HuntAutoStartRecord::class.java)) },
            validate = { record ->
                require(record.version == 1 && record.nextAttemptAt > 0L) { "Invalid hunt automatic-start state" }
            },
        )
    }

    override fun loadOrInitialize(initialNotBefore: Long): Long =
        (journal.loadOrNull(RECORD_ID) ?: journal.commit(RECORD_ID, HuntAutoStartRecord(1, initialNotBefore))).nextAttemptAt

    override fun reserve(nextAttemptAt: Long): Long {
        val current = requireNotNull(journal.loadOrNull(RECORD_ID)) { "Hunt automatic-start state disappeared" }
        return journal.commit(RECORD_ID, HuntAutoStartRecord(1, maxOf(current.nextAttemptAt, nextAttemptAt))).nextAttemptAt
    }

    private companion object {
        const val RECORD_ID = "spawn"
    }
}

private data class HuntAutoStartRecord(val version: Int, val nextAttemptAt: Long)
