package ru.arc.eliteloot

import com.google.gson.Gson
import ru.arc.persistence.DurableRecordJournal
import java.nio.file.Path
import java.util.UUID

internal enum class LostLootState { STORED, CLAIMING, CLAIMED }

/** Entity UUID is also the immutable recovery ID; claimed tombstones suppress stale chunk copies. */
internal data class LostLootRecord(
    val id: String,
    val owner: String,
    val item: String?,
    val capturedAt: Long,
    val state: LostLootState = LostLootState.STORED,
    val claim: String? = null,
) {
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(UUID.fromString(owner).toString() == owner)
        require(capturedAt > 0)
        require(state in LostLootState.entries)
        require(state == LostLootState.CLAIMED || !item.isNullOrEmpty())
        require(item == null || item.length <= 262_144)
        require((state == LostLootState.CLAIMING) == (claim != null))
        claim?.let { require(UUID.fromString(it).toString() == it) }
    }
}

/** Feature-owned state over the shared durable journal; callers hold the Paper mutation barrier. */
internal class LostLootStore(root: Path) {
    private val gson = Gson()
    private val journal = DurableRecordJournal(
        root, Path.of("data/lost-elite-loot"), 300_000,
        encode = { value: LostLootRecord -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decode = { bytes -> requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8), LostLootRecord::class.java)) },
        validate = LostLootRecord::validate,
    )
    private val records = journal.loadAll().associate { stored ->
        require(stored.recordId == stored.value.id)
        stored.recordId to stored.value
    }.toMutableMap()
    private val uncertain = mutableSetOf<String>()

    fun get(id: String): LostLootRecord? = records[id]
    fun certain(id: String): Boolean = id in records && id !in uncertain
    fun forOwner(owner: UUID): List<LostLootRecord> = records.values
        .filter { it.owner == owner.toString() && it.state != LostLootState.CLAIMED }
        .sortedWith(compareBy(LostLootRecord::capturedAt, LostLootRecord::id))
    fun available(record: LostLootRecord) = record.state == LostLootState.STORED && record.id !in uncertain

    fun write(record: LostLootRecord): LostLootRecord {
        record.validate()
        val previous = records[record.id]
        require(previous == null || previous.owner == record.owner)
        require(previous?.state != LostLootState.CLAIMED || record.state == LostLootState.CLAIMED)
        // A failed readback may still have committed: suppress all repeat claims until reconciled.
        uncertain.add(record.id)
        records[record.id] = record
        val stored = journal.commit(record.id, record)
        check(stored == record)
        records[record.id] = stored
        uncertain.remove(record.id)
        return stored
    }
}
