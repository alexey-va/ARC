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

/** Mirrors ARC's five-tick cooldown clock without depending on ARC implementation classes. */
internal class StaffTestCooldowns(
    private val scheduler: TestTaskScheduler,
) : ArcStaffCooldowns, AutoCloseable {
    private val remaining = mutableMapOf<UUID, Long>()
    private val countdown = scheduler.runTimer(5, 5) {
        remaining.toMap().forEach { (playerId, ticks) ->
            if (ticks <= 5) remaining.remove(playerId) else remaining[playerId] = ticks - 5
        }
    }

    override fun remainingTicks(player: UUID): Long = remaining[player]?.takeIf { it > 0 } ?: 0L

    override fun start(player: UUID, ticks: Long) {
        if (ticks <= 0) remaining.remove(player) else remaining[player] = ticks
    }

    override fun clear(player: UUID) {
        remaining.remove(player)
    }

    override fun close() {
        countdown.cancel()
        remaining.clear()
    }
}
