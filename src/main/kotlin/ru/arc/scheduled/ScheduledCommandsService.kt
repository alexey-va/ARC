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
    private var service: ScheduledCommandsService? = null
    private lateinit var config: ScheduledCommandsConfig

    @JvmStatic
    fun init(
        dataPath: java.nio.file.Path,
        taskScheduler: TaskScheduler,
    ) {
        config = ScheduledCommandsConfig.load(dataPath)
        service =
            ScheduledCommandsService(
                settingsProvider = { config },
                dispatcher = CommandDispatcher { command -> ARC.trySeverCommand(command) },
                serverNameProvider = { ARC.serverName?.lowercase() },
                taskScheduler = taskScheduler,
            )
        service?.start()
    }

    /** Visible for tests. */
    internal fun initForTests(
        settings: ScheduledCommandsSettings,
        dispatcher: CommandDispatcher,
        serverName: String?,
        clock: ScheduleClock,
        taskScheduler: TaskScheduler,
    ): ScheduledCommandsService {
        val created =
            ScheduledCommandsService(
                settingsProvider = { settings },
                dispatcher = dispatcher,
                serverNameProvider = { serverName?.lowercase() },
                clock = clock,
                taskScheduler = taskScheduler,
            )
        service = created
        return created
    }

    @JvmStatic
    fun reload() {
        if (service == null) return
        config = ScheduledCommandsConfig.load(ARC.instance.dataPath)
        service?.reload()
    }

    @JvmStatic
    fun shutdown() {
        service?.stop()
        service = null
    }

    @JvmStatic
    fun settings(): ScheduledCommandsSettings = config

    @JvmStatic
    fun openGui(player: org.bukkit.entity.Player) {
        ru.arc.scheduled.guis.ScheduledCommandsGuiFactory
            .openList(player)
    }

    @JvmStatic
    fun runNow(id: String): Boolean = service?.runNow(id) == true

    @JvmStatic
    fun toggleEnabled(id: String): Boolean? = service?.toggleEnabled(id)

    @JvmStatic
    fun saveEntry(draft: ScheduledCommandDraft): ValidationResult {
        val existingIds = config.commandIds().toSet()
        val result = ScheduledCommandInputValidator.validateDraft(draft, existingIds)
        if (result is ValidationResult.Error) return result

        val oldId = draft.originalId
        config.saveEntry(draft)
        config.reloadConfig()
        service?.clearEntryState(oldId)
        service?.clearEntryState(draft.id)
        return ValidationResult.Ok
    }
}
