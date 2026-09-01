package ru.arc.parkour

import io.github.a5h73y.parkour.event.ParkourCheckpointEvent
import io.github.a5h73y.parkour.event.ParkourDeathEvent
import io.github.a5h73y.parkour.event.ParkourFinishEvent
import io.github.a5h73y.parkour.event.ParkourJoinEvent
import io.github.a5h73y.parkour.event.ParkourLeaveEvent
import io.github.a5h73y.parkour.event.ParkourRestartEvent
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.util.TextUtil
import java.time.Duration
import java.util.Locale

internal fun formatParkourMillis(milliseconds: Long): String {
    val safeMilliseconds = milliseconds.coerceAtLeast(0)
    val totalSeconds = safeMilliseconds / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = safeMilliseconds % 1_000
    return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
}

class ArcParkourHudListener(
    private val settings: ArcParkourSettings,
    private val gateway: ArcParkourGateway,
    private val tasks: LifecycleTaskScope,
    private val audience: PaperAudienceEffects = NativePaperAudienceEffects,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJoin(event: ParkourJoinEvent) {
        if (event.isSilent) return
        show(
            event.player,
            "join",
            "course" to courseName(event.courseName),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCheckpoint(event: ParkourCheckpointEvent) {
        val run = gateway.activeRun(event.player) ?: return
        val path = if (run.checkpoint >= run.totalCheckpoints) "checkpoint-all" else "checkpoint"
        show(
            event.player,
            path,
            "current" to run.checkpoint.toString(),
            "total" to run.totalCheckpoints.toString(),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: ParkourDeathEvent) {
        val run = gateway.activeRun(event.player) ?: return
        show(
            event.player,
            "death",
            "checkpoint" to run.checkpoint.toString(),
            "deaths" to run.deaths.toString(),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRestart(event: ParkourRestartEvent) {
        show(event.player, "restart", "course" to courseName(event.courseName))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeave(event: ParkourLeaveEvent) {
        if (!event.isSilent) show(event.player, "leave")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFinish(event: ParkourFinishEvent) {
        show(
            event.player,
            "finish",
            "time" to formatParkourMillis(event.session.timeFinished),
            "deaths" to event.session.deaths.toString(),
        )
    }

    private fun show(
        player: Player,
        path: String,
        vararg values: Pair<String, String>,
    ) {
        if (!settings.hudEnabled) return
        val title = template(settings.gui.string("hud.$path.title", ""), *values)
        val subtitle = template(settings.gui.string("hud.$path.subtitle", ""), *values)
        if (title.isBlank() && subtitle.isBlank()) return
        val payload =
            Title.title(
                TextUtil.mm(title, true),
                TextUtil.mm(subtitle, true),
                Title.Times.times(
                    Duration.ofMillis(settings.fadeInTicks * TICK_MILLIS),
                    Duration.ofMillis(settings.stayTicks * TICK_MILLIS),
                    Duration.ofMillis(settings.fadeOutTicks * TICK_MILLIS),
                ),
            )
        tasks.runLater(1) {
            if (player.isOnline) audience.showTitle(player, payload)
        }
    }

    private fun courseName(courseId: String): String =
        escape(ArcParkourCatalog.displayName(courseId, settings.categories))

    private fun template(template: String, vararg values: Pair<String, String>): String =
        values.fold(template) { current, (key, value) -> current.replace("<$key>", value) }

    private fun escape(value: String): String = value.replace("<", "\\<").replace(">", "\\>")

    companion object {
        private const val TICK_MILLIS = 50L
    }
}
