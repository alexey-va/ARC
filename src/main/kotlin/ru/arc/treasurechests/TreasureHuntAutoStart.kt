package ru.arc.treasurechests

import org.bukkit.Bukkit
import ru.arc.config.ConfigSection
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/** Operator settings; cadence, quantity bounds and attempt semantics preserve the retired timer. */
internal data class HuntAutoStartSettings(
    val enabled: Boolean = false,
    val world: String = "rc_origin_spawn",
    val preset: String = "spawn",
    val initialNotBefore: Long = 0L,
) {
    companion object {
        fun load(section: ConfigSection): HuntAutoStartSettings {
            val settings = HuntAutoStartSettings(
                enabled = section.boolean("enabled", false),
                world = section.string("world", "rc_origin_spawn").trim(),
                preset = section.string("preset", "spawn").trim(),
                initialNotBefore = section.long("initial-not-before", 0L),
            )
            if (settings.world.isBlank() || settings.preset.isBlank() || settings.initialNotBefore < 0L) {
                warn("[hunt-auto-start] invalid world, preset or initial-not-before; automatic starts disabled")
                return settings.copy(enabled = false)
            }
            return settings
        }
    }
}

/** Bukkit reads and hunt placement belong to the server thread. */
internal interface HuntAutoStartWorld {
    fun playerCount(world: String): Int
    fun start(preset: String, chests: Int)
}

internal class PaperHuntAutoStartWorld : HuntAutoStartWorld {
    override fun playerCount(world: String): Int {
        val target = Bukkit.getWorld(world) ?: return 0
        // World.players can contain Citizens actors; the legacy timer excluded NPCs.
        return Bukkit.getOnlinePlayers().count { it.world === target }
    }

    override fun start(preset: String, chests: Int) {
        val config = TreasureHuntRegistry.getHuntConfig(preset)
        if (config == null) {
            warn("[hunt-auto-start] unknown hunt preset {}; attempt cooldown retained", preset)
            return
        }
        // This overload validates reward pools and hooks and preserves the preset's TTL.
        TreasureHuntRegistry.startHunt(config, chests, Bukkit.getConsoleSender(), replaceExisting = false)
    }
}

/**
 * One quarter-hour check, one durable attempt reservation, then one native start.
 *
 * All fields and world calls are server-thread-owned. Only one journal operation
 * may be in flight, including across reloads. Its completion updates bookkeeping
 * even after reload, while the lifecycle token fences the old native effect.
 * Shutdown never waits for disk; an accepted reservation may finish without a hunt.
 */
internal class TreasureHuntAutoStart(
    private val scheduler: TaskScheduler,
    private val world: HuntAutoStartWorld,
    private val store: HuntAutoStartStore,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope(scheduler)
    private var settings = HuntAutoStartSettings()
    private var nextAttemptAt: Long? = null
    private var lastCheckSlot = 0L
    private var ioPending = false
    private var failed = false
    private var closed = false

    fun reload(settings: HuntAutoStartSettings) {
        check(!closed) { "Hunt automatic starter is closed" }
        tasks.restart()
        this.settings = settings
        failed = false
        lastCheckSlot = Math.floorDiv(now(), CHECK_MILLIS)
        if (settings.enabled) {
            // The furniture janitor drains old anchors after 100 startup ticks.
            tasks.runTimer(120L, 20L, ::checkDue)
            loadIfNeeded()
        }
        logState()
    }

    private fun loadIfNeeded() {
        if (nextAttemptAt != null || ioPending || failed || !settings.enabled) return
        val seed = settings.initialNotBefore.takeIf { it > 0L } ?: Math.addExact(now(), COOLDOWN_MILLIS)
        submitIo({ store.loadOrInitialize(seed) }) {
            // Do not replay a quarter-hour boundary crossed while loading state.
            lastCheckSlot = Math.floorDiv(now(), CHECK_MILLIS)
        }
    }

    private fun checkDue() {
        if (closed || !settings.enabled || failed || ioPending) return
        if (nextAttemptAt == null) {
            loadIfNeeded()
            return
        }
        val timestamp = now()
        val slot = Math.floorDiv(timestamp, CHECK_MILLIS)
        if (slot <= lastCheckSlot) return
        lastCheckSlot = slot // Missed checks coalesce; there is no catch-up burst.
        if (timestamp < requireNotNull(nextAttemptAt)) return
        val online = world.playerCount(settings.world)
        if (online <= 0) return
        val chests = (online.toLong() * 3L).coerceIn(20L, 60L).toInt()
        val attemptSettings = settings
        val token = tasks.token()
        val deadline = Math.addExact(timestamp, COOLDOWN_MILLIS)
        submitIo({ store.reserve(deadline) }) {
            // A failed/active start consumes the reservation, exactly like the old timer.
            if (tasks.isCurrent(token) && now() < requireNotNull(nextAttemptAt) &&
                world.playerCount(attemptSettings.world) > 0
            ) {
                runCatching { world.start(attemptSettings.preset, chests) }.onFailure { failure ->
                    warn("[hunt-auto-start] start failed; cooldown retained", failure)
                }
            }
        }
    }

    private fun submitIo(operation: () -> Long, afterCommit: () -> Unit) {
        check(!ioPending)
        ioPending = true
        try {
            // Deliberately outside LifecycleTaskScope: cancelling a reload must
            // not discard an accepted durable reservation or its bookkeeping.
            scheduler.runAsync(Runnable {
                val result = runCatching(operation)
                runCatching {
                    scheduler.runSync(Runnable {
                        ioPending = false
                        if (!closed) {
                            result.fold(
                                onSuccess = { committed ->
                                    nextAttemptAt = committed
                                    afterCommit()
                                },
                                onFailure = ::storageFailed,
                            )
                            logState()
                        }
                    })
                }.onFailure { failure ->
                    warn("[hunt-auto-start] journal completion could not return to server thread", failure)
                }
            })
        } catch (failure: RuntimeException) {
            ioPending = false
            storageFailed(failure)
        }
    }

    private fun storageFailed(failure: Throwable) {
        nextAttemptAt = null
        failed = true
        warn("[hunt-auto-start] state unavailable; automatic starts paused until reload", failure)
    }

    private fun logState() {
        info(
            "[hunt-auto-start] enabled={} world={} preset={} nextAttemptAt={} stateReady={}",
            settings.enabled, settings.world, settings.preset, nextAttemptAt,
            nextAttemptAt != null && !failed,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
    }

    companion object {
        const val CHECK_MILLIS = 15L * 60L * 1000L
        const val COOLDOWN_MILLIS = 3L * 60L * 60L * 1000L
    }
}
