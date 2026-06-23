package ru.arc.restart

import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.time.Duration
import java.time.Instant

data class PendingRestart(
    val scheduledAt: Instant,
    val executeAt: Instant,
    val initiatedBy: String,
    val delay: Duration,
)

class RestartService(
    private val configProvider: () -> RestartConfig,
    private val scheduler: TaskScheduler,
    private val showTitle: (title: String, subtitle: String, secondsRemaining: Int) -> Unit,
    private val broadcastChat: (message: String) -> Unit,
    private val kickPlayers: () -> Unit,
    private val shutdown: () -> Unit,
    private val now: () -> Instant = { Instant.now() },
) {
    private var pending: PendingRestart? = null
    private val warningTasks = mutableListOf<ScheduledTask>()
    private var shutdownTask: ScheduledTask? = null

    fun isPending(): Boolean = pending != null

    fun pendingRestart(): PendingRestart? = pending

    fun schedule(
        delay: Duration,
        initiatedBy: String,
    ): Boolean {
        if (!configProvider().enabled) return false
        if (pending != null) return false
        if (delay.isZero || delay.isNegative) return false

        val config = configProvider()
        val executeAt = now().plus(delay)
        pending = PendingRestart(now(), executeAt, initiatedBy, delay)

        notifyInitial(delay.toSeconds().toInt().coerceAtLeast(1), config)
        scheduleWarnings(delay, config)
        shutdownTask =
            scheduler.runLater(delay.toMillis().coerceAtLeast(50L) / 50) {
                if (pending == null) return@runLater
                pending = null
                clearWarningTasks()
                executeRestart()
            }

        return true
    }

    private fun executeRestart() {
        val config = configProvider()
        if (config.kickBeforeRestart) {
            kickPlayers()
            val delayTicks = config.kickDelayTicks.coerceAtLeast(0L)
            if (delayTicks > 0L) {
                scheduler.runLater(delayTicks) { shutdown() }
            } else {
                shutdown()
            }
        } else {
            shutdown()
        }
    }

    fun cancel(initiatedBy: String): Boolean {
        if (pending == null) return false
        pending = null
        shutdownTask?.cancel()
        shutdownTask = null
        clearWarningTasks()

        val config = configProvider()
        showTitle(config.titleCancelledTitle, config.titleCancelledSubtitle, 0)
        if (config.warningChatBroadcast) {
            broadcastChat(config.messageBroadcastCancelled)
        }
        return true
    }

    fun shutdownModule() {
        pending = null
        shutdownTask?.cancel()
        shutdownTask = null
        clearWarningTasks()
    }

    private fun notifyInitial(
        secondsRemaining: Int,
        config: RestartConfig,
    ) {
        showWarning(secondsRemaining, config, chat = config.warningChatBroadcast)
    }

    private fun scheduleWarnings(
        delay: Duration,
        config: RestartConfig,
    ) {
        val plan =
            RestartWarningPlanner.plan(
                delay = delay,
                configuredSeconds = config.warningAtSeconds,
                minuteMarks = config.warningMinuteMarks,
                finalCountdownSeconds = config.titleFinalCountdownSeconds,
            )

        for (secondsBefore in plan.titlePoints) {
            val wait = delay.minusSeconds(secondsBefore.toLong())
            if (wait.isNegative || wait.isZero) continue
            val ticks = (wait.toMillis() / 50).coerceAtLeast(0L)
            val chat = config.warningChatBroadcast && plan.chatPoints.contains(secondsBefore)
            val task =
                scheduler.runLater(ticks) {
                    if (pending == null) return@runLater
                    showWarning(secondsBefore, configProvider(), chat)
                }
            warningTasks += task
        }
    }

    private fun showWarning(
        secondsRemaining: Int,
        config: RestartConfig,
        chat: Boolean,
    ) {
        val time = RestartDurationFormat.formatSeconds(secondsRemaining)
        showTitle(
            config.titleWarning,
            config.titleSubtitle.replace("<time>", time),
            secondsRemaining,
        )
        if (chat) {
            broadcastChat(config.messageBroadcastScheduled.replace("<delay>", time))
        }
    }

    private fun clearWarningTasks() {
        warningTasks.forEach { it.cancel() }
        warningTasks.clear()
    }
}
