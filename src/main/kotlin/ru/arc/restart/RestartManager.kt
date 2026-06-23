package ru.arc.restart

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.TaskScheduler
import ru.arc.util.TextUtil
import ru.arc.util.showTitleMM
import ru.arc.xserver.XActionManager
import ru.arc.xserver.XRestart
import ru.arc.xserver.XRestartCancel
import java.nio.file.Path
import java.time.Duration

object RestartManager {
    private var service: RestartService? = null
    private lateinit var config: RestartConfig

    @JvmStatic
    fun init(
        dataPath: Path,
        taskScheduler: TaskScheduler,
    ) {
        config = RestartConfig.load(dataPath)
        service = createService(taskScheduler)
    }

    internal fun initForTests(
        testConfig: RestartConfig,
        taskScheduler: TaskScheduler,
        showTitle: (String, String, Int) -> Unit,
        broadcastChat: (String) -> Unit,
        kickPlayers: () -> Unit = {},
        shutdown: () -> Unit,
    ): RestartService {
        config = testConfig
        val created =
            RestartService(
                configProvider = { config },
                scheduler = taskScheduler,
                showTitle = showTitle,
                broadcastChat = broadcastChat,
                kickPlayers = kickPlayers,
                shutdown = shutdown,
            )
        service = created
        return created
    }

    @JvmStatic
    fun reload() {
        if (service == null) return
        config = RestartConfig.load(ARC.instance.dataPath)
    }

    @JvmStatic
    fun shutdown() {
        service?.shutdownModule()
        service = null
    }

    @JvmStatic
    fun settings(): RestartConfig = config

    @JvmStatic
    fun isPending(): Boolean = service?.isPending() == true

    @JvmStatic
    fun scheduleFromCommand(
        flags: RestartFlags,
        initiatedBy: String,
    ): ScheduleResult {
        if (!config.enabled) return ScheduleResult.Disabled
        require(!flags.cancel) { "Use cancelFromCommand for cancel" }

        val delay = flags.delay ?: config.defaultDelay
        if (RestartFlagParser.requiresCrossServerPublish(flags.serverTarget, ARC.serverName)) {
            val action =
                XRestart.create(
                    delay = delay,
                    initiatedBy = initiatedBy,
                    servers = RestartFlagParser.toXActionServers(flags.serverTarget),
                )
            XActionManager.publish(action)
            return ScheduleResult.Published(delay, flags.serverTarget)
        }

        return scheduleLocal(delay, initiatedBy)
    }

    @JvmStatic
    fun cancelFromCommand(
        flags: RestartFlags,
        initiatedBy: String,
    ): CancelResult {
        if (RestartFlagParser.requiresCrossServerPublish(flags.serverTarget, ARC.serverName)) {
            val action =
                XRestartCancel.create(
                    initiatedBy = initiatedBy,
                    servers = RestartFlagParser.toXActionServers(flags.serverTarget),
                )
            XActionManager.publish(action)
            return CancelResult.Published(flags.serverTarget)
        }

        val cancelled = service?.cancel(initiatedBy) == true
        return if (cancelled) CancelResult.Cancelled else CancelResult.NothingPending
    }

    @JvmStatic
    fun handleRemoteSchedule(
        delay: Duration,
        initiatedBy: String,
    ): ScheduleResult = scheduleLocal(delay, initiatedBy)

    @JvmStatic
    fun handleRemoteCancel(initiatedBy: String): CancelResult {
        val cancelled = service?.cancel(initiatedBy) == true
        return if (cancelled) CancelResult.Cancelled else CancelResult.NothingPending
    }

    private fun scheduleLocal(
        delay: Duration,
        initiatedBy: String,
    ): ScheduleResult {
        val scheduled = service?.schedule(delay, initiatedBy) == true
        return if (scheduled) {
            ScheduleResult.Scheduled(delay)
        } else if (service?.isPending() == true) {
            ScheduleResult.AlreadyPending
        } else {
            ScheduleResult.Failed
        }
    }

    private fun createService(taskScheduler: TaskScheduler): RestartService =
        RestartService(
            configProvider = { config },
            scheduler = taskScheduler,
            showTitle = { title, subtitle, secondsRemaining ->
                val timing = config.titleTimingFor(secondsRemaining)
                Bukkit.getOnlinePlayers().forEach { player ->
                    player.showTitleMM(title, subtitle, timing.fadeIn, timing.stay, timing.fadeOut)
                }
            },
            broadcastChat = { message ->
                Bukkit.broadcast(TextUtil.mm(message))
            },
            kickPlayers = {
                if (config.kickBeforeRestart) {
                    val message = TextUtil.mm(config.messageKick)
                    Bukkit.getOnlinePlayers().toList().forEach { player ->
                        player.kick(message)
                    }
                }
            },
            shutdown = {
                Bukkit.getServer().shutdown()
            },
        )

    fun knownServerNames(): Set<String> = RestartServerNames.suggestions(config).filter { it != "all" }.toSet()
}

sealed class ScheduleResult {
    data class Scheduled(
        val delay: Duration,
    ) : ScheduleResult()

    data class Published(
        val delay: Duration,
        val target: RestartServerTarget,
    ) : ScheduleResult()

    data object AlreadyPending : ScheduleResult()

    data object Disabled : ScheduleResult()

    data object Failed : ScheduleResult()
}

sealed class CancelResult {
    data object Cancelled : CancelResult()

    data class Published(
        val target: RestartServerTarget,
    ) : CancelResult()

    data object NothingPending : CancelResult()
}
