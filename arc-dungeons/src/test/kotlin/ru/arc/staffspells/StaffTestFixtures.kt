package ru.arc.staffspells

import ru.arc.config.Config
import ru.arc.core.TestTaskScheduler
import ru.arc.dungeons.api.ArcStaffCooldowns
import java.nio.file.Files
import java.util.UUID

/** Small test-local config fixture; the child module must not depend on ARC's tests. */
internal class StaffTestConfig(
    values: Map<String, Any> = emptyMap(),
) : Config(Files.createTempDirectory("arc-dungeons-staff-test-"), "staff-test-config.yml") {
    init {
        values.forEach { (path, value) -> inject(path, value) }
    }

    operator fun set(path: String, value: Any) = inject(path, value)

    private fun inject(path: String, value: Any) {
        when (value) {
            is Int -> setInt(path, value)
            is Boolean -> setBoolean(path, value)
            is Double -> setDouble(path, value)
            is Long -> setLong(path, value)
            is String -> setString(path, value)
            is List<*> -> setStringList(path, value.map { it.toString() })
            is Map<*, *> -> value.forEach { (key, nested) ->
                if (key != null && nested != null) inject("$path.$key", nested)
            }
            else -> setString(path, value.toString())
        }
    }

    override fun reload() = Unit
    override fun save() = Unit
}

/** Mirrors exact server-tick deadlines without depending on ARC implementation classes. */
internal class StaffTestCooldowns(
    scheduler: TestTaskScheduler,
) : ArcStaffCooldowns, AutoCloseable {
    private val deadlines = mutableMapOf<UUID, Long>()
    private var tick = 0L
    private val clock = scheduler.runTimer(1, 1) { tick++ }

    override fun remainingTicks(player: UUID): Long {
        val remaining = ((deadlines[player] ?: return 0L) - tick).coerceAtLeast(0L)
        if (remaining == 0L) deadlines.remove(player)
        return remaining
    }

    override fun start(player: UUID, ticks: Long) {
        if (ticks <= 0) clear(player) else deadlines[player] = tick + ticks
    }

    override fun clear(player: UUID) {
        deadlines.remove(player)
    }

    override fun close() {
        clock.cancel()
        deadlines.clear()
    }
}
