package ru.arc.treasurechests

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.config.ConfigSection
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.error
import java.util.UUID

internal data class HuntEventAmbienceSettings(
    val enabled: Boolean = true,
    val world: String = "rc_origin_spawn",
    val time: Long = 18000L,
) {
    companion object {
        fun load(section: ConfigSection): HuntEventAmbienceSettings =
            HuntEventAmbienceSettings(
                enabled = section.boolean("enabled", true),
                world = section.string("world", "rc_origin_spawn").trim(),
                time = Math.floorMod(section.long("time", 18000L), 24000L),
            )
    }
}

/**
 * Main-thread owner of temporary hunt night and one optional world-aurora lease.
 * The module must close it before stopping its scheduler. Only online player IDs
 * and time values are retained; the one-second task handles joins and world exits.
 * World time and gamerules are never changed. A later personal-time override wins.
 */
internal class HuntEventAmbience(
    private val scheduler: TaskScheduler,
    private val settingsProvider: () -> HuntEventAmbienceSettings,
    private val onlinePlayers: () -> Collection<Player> = { Bukkit.getOnlinePlayers() },
    private val startWorldAurora: (UUID) -> AutoCloseable? = { null },
) : AutoCloseable {
    private data class PreviousTime(
        val entityId: Int,
        val offset: Long,
        val relative: Boolean,
        var appliedTime: Long,
    ) {
        fun isOwned(player: Player): Boolean =
            !player.isPlayerTimeRelative && player.playerTimeOffset == appliedTime
    }

    private var settings = settingsProvider()
    private var activeWorlds: Map<String, UUID> = emptyMap()
    private val previousTimes = mutableMapOf<UUID, PreviousTime>()
    private var timer: ScheduledTask? = null
    private var auroraWorld: UUID? = null
    private var auroraLease: AutoCloseable? = null
    private var auroraFailed = false
    private var closed = false

    /** Call after the service commits an active-hunt change; overlapping hunts share one ambience. */
    fun onActiveHuntsChanged(hunts: Collection<ActiveHunt>) {
        if (closed) return
        activeWorlds = hunts.associate { it.world.name to it.world.uid }
        reconcile()
    }

    /** Applies reloaded world/time settings while retaining each player's original time snapshot. */
    fun refresh() {
        if (closed) return
        settings = settingsProvider()
        stopAurora()
        reconcile()
    }

    private fun reconcile() {
        if (closed) return
        val worldId = activeWorlds[settings.world].takeIf { settings.enabled }
        reconcilePlayers(worldId)
        reconcileAurora(worldId)
        if (worldId == null) {
            timer?.cancel()
            timer = null
        } else if (timer == null) {
            timer = scheduler.runTimer(20L, 20L, Runnable { reconcile() })
        }
    }

    private fun reconcilePlayers(worldId: UUID?) {
        val players = onlinePlayers().filter { it.isOnline }.associateBy { it.uniqueId }
        previousTimes.keys.retainAll(players.keys)
        players.forEach { (id, player) ->
            // A reconnect may reuse the UUID before the next tick, but never the old entity session.
            val previous = previousTimes[id]?.takeIf { it.entityId == player.entityId }
            if (previous == null) previousTimes.remove(id)
            if (worldId != null && player.world.uid == worldId) {
                if (previous == null) {
                    previousTimes[id] = PreviousTime(
                        player.entityId,
                        player.playerTimeOffset,
                        player.isPlayerTimeRelative,
                        settings.time,
                    )
                    player.setPlayerTime(settings.time, false)
                } else if (previous.isOwned(player) && previous.appliedTime != settings.time) {
                    player.setPlayerTime(settings.time, false)
                    previous.appliedTime = settings.time
                }
            } else if (previous != null) {
                if (previous.isOwned(player)) player.setPlayerTime(previous.offset, previous.relative)
                previousTimes.remove(id)
            }
        }
    }

    private fun reconcileAurora(worldId: UUID?) {
        if (worldId != auroraWorld) {
            stopAurora()
            auroraWorld = worldId
        }
        if (worldId == null || auroraLease != null || auroraFailed) return
        try {
            // An absent optional service is retried quietly; a failed provider waits for refresh.
            auroraLease = startWorldAurora(worldId)
        } catch (failure: Exception) {
            auroraFailed = true
            error("[hunt-ambience] Could not start world aurora: world=$worldId; retry=reload-or-world-change", failure)
        }
    }

    private fun stopAurora() {
        val lease = auroraLease
        val worldId = auroraWorld
        auroraLease = null
        auroraWorld = null
        auroraFailed = false
        try {
            lease?.close()
        } catch (failure: Exception) {
            error("[hunt-ambience] Could not close world aurora: world=$worldId", failure)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        timer?.cancel()
        timer = null
        activeWorlds = emptyMap()
        try {
            reconcilePlayers(null)
        } finally {
            previousTimes.clear()
            stopAurora()
        }
    }
}
