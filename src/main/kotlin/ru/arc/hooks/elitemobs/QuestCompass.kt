package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.quests.QuestTracking
import com.magmaguy.elitemobs.dungeons.EliteMobsWorld
import com.magmaguy.elitemobs.quests.dialogue.QuestDialogueBossBarManager
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.util.Logging
import java.util.UUID
import org.bukkit.boss.BossBar as NativeBossBar

/**
 * Main-thread-only dungeon compass. EliteMobs retains its tracker, targets and timers.
 * Its per-player bar stays hidden, but its audience still carries dialogue suspension state.
 * Closing restores native visibility; incompatibility leaves the native compass operational.
 */
internal class QuestCompass(
    private val trackedBars: () -> Map<UUID, NativeBossBar>,
    private val findPlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val warn: (String) -> Unit = { Logging.warn(it) },
    private val isEliteWorld: (Player) -> Boolean = { EliteMobsWorld.isEliteMobsWorld(it.world.uid) },
    private val points: DungeonCompassPoints = DungeonCompassPoints(),
) : AutoCloseable {
    private val sessions = mutableMapOf<UUID, Session>()
    private var closed = false
    private var tick = 0L

    fun refresh() {
        if (closed) return
        runCatching {
            val current = trackedBars()
            val players = Bukkit.getOnlinePlayers().filter { it.isOnline && isEliteWorld(it) }
                .associateBy { it.uniqueId }.toMutableMap()
            current.keys.forEach { id -> findPlayer(id)?.takeIf { it.isOnline }?.let { players[id] = it } }
            val removed = sessions.keys.filter { it !in players || current[it] !== sessions[it]?.native }
            removed.forEach { sessions.remove(it)?.close() }
            players.forEach { (id, player) ->
                val session = sessions.getOrPut(id) { Session(player, current[id]) }
                session.refresh(isEliteWorld(player), tick, points)
            }
            tick++
        }.onFailure {
            close()
            warn("ARC quest compass unavailable; native compass restored: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private class Session(val player: Player, val native: NativeBossBar?) : AutoCloseable {
        private val nativeWasVisible = native?.isVisible ?: true
        private val bar = BossBar.bossBar(
            QuestCompassRenderer.render(player.location.yaw, native?.title),
            1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS,
        )
        private var shown = false
        private var nearby = emptyList<DungeonCompassPoint>()
        private var nearbyWorld: UUID? = null
        private var nextPointRefresh = 0L

        init { native?.isVisible = false }

        fun refresh(inEliteWorld: Boolean, tick: Long, points: DungeonCompassPoints) {
            val location = player.location
            if (!inEliteWorld) {
                nearby = emptyList()
                nextPointRefresh = 0
            } else if (tick >= nextPointRefresh || nearbyWorld != location.world?.uid) {
                nearby = points.nearby(player)
                nearbyWorld = location.world?.uid
                nextPointRefresh = tick + 20
            }
            bar.name(QuestCompassRenderer.render(location.yaw, native?.title, nearby.mapNotNull { it.project(location) }))
            val visible = inEliteWorld && nativeWasVisible && (native == null || native.players.contains(player)) &&
                !QuestDialogueBossBarManager.hasActiveSession(player)
            if (visible == shown) return
            if (visible) player.showBossBar(bar) else player.hideBossBar(bar)
            shown = visible
        }

        override fun close() {
            if (shown) player.hideBossBar(bar)
            shown = false
            native?.isVisible = nativeWasVisible
        }
    }

    companion object {
        fun create(): QuestCompass? = runCatching {
            // EliteMobs exposes tracking publicly, but not the compass. Resolve this single
            // exact field once; never inspect NMS or search private members on a tick path.
            val field = QuestTracking::class.java.getDeclaredField("compassBar").apply {
                check(type == NativeBossBar::class.java)
                isAccessible = true
            }
            QuestCompass(trackedBars = {
                QuestTracking.getPlayerTrackingQuests().mapNotNull { (id, tracker) ->
                    (field.get(tracker) as? NativeBossBar)?.let { id to it }
                }.toMap()
            })
        }.onFailure {
            Logging.warn("ARC quest compass compatibility unavailable; keeping EliteMobs compass: ${it.message}")
        }.getOrNull()
    }
}
