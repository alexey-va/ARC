package ru.arc.origin

import java.util.UUID

/** One resting worker; an accepted game pins that shift until its exact owner leaves. */
internal class OriginWorkshopSleepShift(private val tables: List<String>, private val durationTicks: Long) {
    init {
        require(tables.isNotEmpty() && tables.distinct().size == tables.size)
        require(durationTicks in 200L..72_000L)
    }

    var index: Int = 0
        private set
    var ready: Boolean = false
        private set
    private var due = Long.MAX_VALUE
    private var player: UUID? = null
    val table: String get() = tables[index]

    fun resting(now: Long, workerIndex: Int = index) {
        check(!ready)
        require(workerIndex in tables.indices)
        index = workerIndex
        ready = true
        due = now + durationTicks
    }

    fun acquire(tableId: String, playerId: UUID): Boolean {
        if (!ready || tableId != table || player != null) return false
        player = playerId
        return true
    }

    fun owns(tableId: String, playerId: UUID): Boolean = ready && tableId == table && player == playerId

    fun release(tableId: String, playerId: UUID) {
        if (tableId == table && player == playerId) player = null
    }

    fun rotationDue(now: Long): Boolean = ready && player == null && now >= due

    /** Commit only after the replacement's sleeping pose has been confirmed. */
    fun replaceWith(workerIndex: Int, now: Long) {
        check(rotationDue(now)) { "An occupied or unexpired workshop cannot change shift" }
        require(workerIndex in tables.indices && workerIndex != index)
        index = workerIndex
        due = now + durationTicks
    }

    fun invalidate() {
        ready = false
        player = null
    }
}
