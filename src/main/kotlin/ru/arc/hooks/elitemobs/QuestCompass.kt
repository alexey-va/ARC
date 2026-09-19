package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.quests.QuestTracking
import com.magmaguy.elitemobs.dungeons.EliteMobsWorld
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.util.Logging
import java.util.UUID
import org.bukkit.boss.BossBar as NativeBossBar

/**
 * Main-thread-only presentation adapter. EliteMobs retains its tracker, targets and timers.
 * Its per-player bar stays hidden, but its audience still carries dialogue suspension state.
 * Closing restores native visibility; incompatibility leaves the native compass operational.
 */
internal class QuestCompass(
    private val trackedBars: () -> Map<UUID, NativeBossBar>,
    private val findPlayer: (UUID) -> Player? = Bukkit::getPlayer,
    private val warn: (String) -> Unit = { Logging.warn(it) },
    private val isEliteWorld: (Player) -> Boolean = { EliteMobsWorld.isEliteMobsWorld(it.world.uid) },
) : AutoCloseable {
    private val sessions = mutableMapOf<UUID, Session>()
    private var closed = false

    fun refresh() {
        if (closed) return
        runCatching {
            val current = trackedBars()
            val removed = sessions.keys.filter { current[it] !== sessions[it]?.native }
            removed.forEach { sessions.remove(it)?.close() }
            current.forEach { (id, native) ->
                val player = findPlayer(id)?.takeIf { it.isOnline }
                if (player == null) {
                    sessions.remove(id)?.close()
                    return@forEach
                }
                val session = sessions.getOrPut(id) { Session(player, native) }
                session.refresh(isEliteWorld(player))
            }
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

    private class Session(val player: Player, val native: NativeBossBar) : AutoCloseable {
        private val nativeWasVisible = native.isVisible
        private val bar = BossBar.bossBar(
            QuestCompassRenderer.render(player.location.yaw, native.title),
            1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS,
        )
        private var shown = false

        init { native.isVisible = false }

        fun refresh(inEliteWorld: Boolean) {
            bar.name(QuestCompassRenderer.render(player.location.yaw, native.title))
            val visible = inEliteWorld && nativeWasVisible && native.players.contains(player)
            if (visible == shown) return
            if (visible) player.showBossBar(bar) else player.hideBossBar(bar)
            shown = visible
        }

        override fun close() {
            if (shown) player.hideBossBar(bar)
            shown = false
            native.isVisible = nativeWasVisible
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
