package ru.arc.scheduled

import ru.arc.ARC
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging
import ru.arc.util.Logging.withContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

interface ScheduledCommandsSettings {
    val enabled: Boolean
    val checkIntervalTicks: Long
    val timezone: ZoneId
    val guiTitle: String
    val detailGuiTitle: String
    val permissionRunNow: String

    fun entries(): List<ScheduledCommandEntry>

    fun entry(id: String): ScheduledCommandEntry?

    fun setEnabled(
        id: String,
        enabled: Boolean,
    )
}

interface ScheduleClock {
    fun now(zone: ZoneId): ZonedDateTime
}

object SystemScheduleClock : ScheduleClock {
    override fun now(zone: ZoneId): ZonedDateTime = ZonedDateTime.now(zone)
}

class ScheduledCommandsService(
    private val settingsProvider: () -> ScheduledCommandsSettings,
    private val dispatcher: CommandDispatcher,
    private val serverNameProvider: () -> String?,
    private val clock: ScheduleClock = SystemScheduleClock,
    private val taskScheduler: TaskScheduler,
) {
    private val lastFireKeys = ConcurrentHashMap<String, String>()
    private val intervalBootstrapped = ConcurrentHashMap.newKeySet<String>()
    private var tickTask: ScheduledTask? = null

    fun start() {
        stop()
        val settings = settingsProvider()
        if (!settings.enabled) {
            Logging.info("Scheduled commands module disabled")
            return
        }

        val period = settings.checkIntervalTicks.coerceAtLeast(20L)
        tickTask =
            taskScheduler.runTimer(period, period) {
                tick()
            }
        Logging.info("Scheduled commands started (check every {} ticks)", period)
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
    }

    fun reload() {
        stop()
        start()
    }

    fun tick(now: ZonedDateTime? = null) {
        val settings = settingsProvider()
        if (!settings.enabled) return

        val zone = settings.timezone
        val current = now ?: clock.now(zone)
        val grace = Duration.ofMillis(settings.checkIntervalTicks * 50L + 1_000L)
        val serverName = serverNameProvider()

        for (entry in settings.entries()) {
            if (!entry.enabled || !entry.runsOn(serverName)) continue
            if (entry.command.isBlank()) continue

            val fireKey = resolveFireKey(entry, current, grace) ?: continue
            if (lastFireKeys[entry.id] == fireKey) continue

            lastFireKeys[entry.id] = fireKey
            execute(entry)
        }
    }

    fun runNow(id: String): Boolean {
        val entry = settingsProvider().entry(id) ?: return false
        execute(entry)
        return true
    }

    fun toggleEnabled(id: String): Boolean? {
        val settings = settingsProvider()
        val entry = settings.entry(id) ?: return null
        val newValue = !entry.enabled
        settings.setEnabled(id, newValue)
        return newValue
    }

    fun lastFireKey(id: String): String? = lastFireKeys[id]

    internal fun clearState() {
        lastFireKeys.clear()
        intervalBootstrapped.clear()
    }

    fun clearEntryState(id: String) {
        lastFireKeys.remove(id)
        intervalBootstrapped.remove(id)
    }

    private fun resolveFireKey(
        entry: ScheduledCommandEntry,
        now: ZonedDateTime,
        grace: Duration,
    ): String? {
        val schedule = entry.schedule
        if (schedule is IntervalSchedule) {
            if (!intervalBootstrapped.contains(entry.id)) {
                intervalBootstrapped.add(entry.id)
                if (!schedule.runOnStart) return null
            }
        }
        return schedule.matchSlot(now, grace)
    }

    private fun execute(entry: ScheduledCommandEntry) {
        withContext(module = "scheduled-commands", action = entry.id) {
            Logging.info("Running scheduled command '{}': {}", entry.id, entry.command)
        }
        dispatcher.dispatch(entry.command)
    }
}

object ScheduledCommandsManager {
    private var owner: ScheduledCommandsOwner? = null
    private var predecessor = java.util.concurrent.CompletableFuture.completedFuture<Void>(null)
    private var testService: ScheduledCommandsService? = null

    @JvmStatic
    fun init(dataPath: java.nio.file.Path, taskScheduler: TaskScheduler) {
        shutdown()
        check(predecessor.isDone && !predecessor.isCompletedExceptionally) { "Previous schedule generation is still draining" }
        val plugin = ARC.instance
        owner = ScheduledCommandsOwner(dataPath, plugin.backgroundOwner(), plugin.nativeWork,
            taskScheduler, { ARC.serverName?.lowercase() }, CommandDispatcher(ARC::trySeverCommand),
            reportFailure = { failure -> plugin.logger.log(java.util.logging.Level.SEVERE, "Scheduled command operation failed", failure) })
    }

    /** Pure service seam; runtime persistence and native dispatch use the asynchronous owner. */
    internal fun initForTests(
        settings: ScheduledCommandsSettings,
        dispatcher: CommandDispatcher,
        serverName: String?,
        clock: ScheduleClock,
        taskScheduler: TaskScheduler,
    ): ScheduledCommandsService = ScheduledCommandsService(
        settingsProvider = { settings }, dispatcher = dispatcher,
        serverNameProvider = { serverName?.lowercase() }, clock = clock, taskScheduler = taskScheduler,
    ).also { testService?.stop(); testService = it }

    fun readiness(): java.util.concurrent.CompletableFuture<Void> =
        owner?.ready() ?: java.util.concurrent.CompletableFuture.completedFuture(null)

    fun drained(): java.util.concurrent.CompletableFuture<Void> = predecessor.copy()

    @JvmStatic
    fun reloadAsync(): java.util.concurrent.CompletableFuture<Void> = requireOwner().reloadAsync()

    @JvmStatic
    fun reload() {
        requireOwner().reloadMaintenance()
    }

    @JvmStatic
    fun shutdown() {
        val previous = owner
        owner = null
        if (previous != null) predecessor = previous.close()
        testService?.stop()
        testService = null
    }

    @JvmStatic
    fun settings(): ScheduledCommandsSettings = requireOwner().snapshot()

    @JvmStatic
    fun entries(): List<ScheduledCommandEntry> = settings().entries()

    @JvmStatic
    fun commandIds(prefix: String): List<String> = owner?.commandIds(prefix) ?: emptyList()

    @JvmStatic
    fun entry(id: String): ScheduledCommandEntry? = settings().entry(id.trim().lowercase())

    @JvmStatic
    fun openGui(player: org.bukkit.entity.Player) {
        ru.arc.scheduled.guis.ScheduledCommandsGuiFactory.openList(player)
    }

    fun runNowAsync(id: String): java.util.concurrent.CompletableFuture<Boolean> = requireOwner().runNowAsync(id)
    fun toggleEnabledAsync(id: String): java.util.concurrent.CompletableFuture<Boolean?> = requireOwner().toggleEnabledAsync(id)
    fun saveEntryAsync(draft: ScheduledCommandDraft): java.util.concurrent.CompletableFuture<ValidationResult> = requireOwner().saveEntryAsync(draft)
    fun saveSnapshotAsync(draft: ScheduledCommandDraft): java.util.concurrent.CompletableFuture<ScheduledEntrySaveResult> = requireOwner().saveSnapshotAsync(draft)
    fun deleteEntryAsync(id: String): java.util.concurrent.CompletableFuture<Boolean> = requireOwner().deleteEntryAsync(id)

    // Compatibility for non-native callers while UI/HTTP callers migrate to explicit future APIs.
    @Deprecated("Use runNowAsync and acknowledge the native completion")
    fun runNow(id: String): Boolean = offThreadAwait { runNowAsync(id) }
    @Deprecated("Use toggleEnabledAsync")
    fun toggleEnabled(id: String): Boolean? = offThreadAwait { toggleEnabledAsync(id) }
    @Deprecated("Use saveEntryAsync and acknowledge strict persistence")
    fun saveEntry(draft: ScheduledCommandDraft): ValidationResult = offThreadAwait { saveEntryAsync(draft) }
    @Deprecated("Use deleteEntryAsync and acknowledge strict persistence")
    fun deleteEntry(id: String): Boolean = offThreadAwait { deleteEntryAsync(id) }

    private fun <T> offThreadAwait(operation: () -> java.util.concurrent.CompletableFuture<T>): T {
        check(!org.bukkit.Bukkit.isPrimaryThread()) { "Scheduled command completion must never block the server thread" }
        return operation().join()
    }

    private fun requireOwner(): ScheduledCommandsOwner = checkNotNull(owner) { "Scheduled commands are not initialized" }
}
