package ru.arc.listeners

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.chat.ChatModeService
import ru.arc.config.ConfigManager
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.jobs.JobsModule
import ru.arc.rtp.RtpRespawnTracker
import ru.arc.sync.SyncManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.info
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JoinListener : Listener {

    private companion object {
        val HEALTH_TRACE_TICKS = longArrayOf(1, 2, 5, 10, 20, 40, 100, 200, 300)
    }

    private val config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "modules/misc.yml")
    private val invMap: MutableMap<UUID, String> = ConcurrentHashMap()
    private val healthTraceSessions: MutableMap<UUID, UUID> = ConcurrentHashMap()

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        traceHealthAfterJoin(event.player)
        ARC.pluginMessenger?.sendBackendRtpReady(event.player)
        ChatModeService.track(event.player.uniqueId)
        JobsModule.trackPlayer(event.player.uniqueId)
        SyncManager.playerJoin(event.player.uniqueId)
        invulnerable(event.player)
        fullHeal(event.player)
        AuditManager.join(event.player)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerLeave(event: PlayerQuitEvent) {
        traceHealth("quit-low", event.player)
        healthTraceSessions.remove(event.player.uniqueId)
        RtpRespawnTracker.cancel(event.player.name)
        ChatModeService.untrack(event.player.uniqueId)
        JobsModule.untrackPlayer(event.player.uniqueId)
        SyncManager.playerQuit(event.player.uniqueId)
        AuditManager.leave(event.player)
        if (invMap.containsKey(event.player.uniqueId)) stripInvulnerable(event.player)
        TreasureHuntManager.onPlayerQuit(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerKick(event: PlayerKickEvent) {
        traceHealth("kick", event.player)
        healthTraceSessions.remove(event.player.uniqueId)
        RtpRespawnTracker.cancel(event.player.name)
        ChatModeService.untrack(event.player.uniqueId)
        JobsModule.untrackPlayer(event.player.uniqueId)
        SyncManager.playerQuit(event.player.uniqueId)
        AuditManager.leave(event.player)
        if (invMap.containsKey(event.player.uniqueId)) stripInvulnerable(event.player)
        TreasureHuntManager.onPlayerQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerLeaveMonitor(event: PlayerQuitEvent) {
        traceHealth("quit-monitor", event.player)
    }

    private fun traceHealthAfterJoin(player: Player) {
        if (!config.bool("join.health-trace-enabled", true)) return

        val playerId = player.uniqueId
        val sessionId = UUID.randomUUID()
        healthTraceSessions[playerId] = sessionId
        traceHealth("join+0t", player)

        HEALTH_TRACE_TICKS.forEach { tick ->
            delayed(tick.ticks) {
                if (healthTraceSessions[playerId] != sessionId) return@delayed
                val currentPlayer = ARC.instance.server.getPlayer(playerId)
                if (currentPlayer == null || !currentPlayer.isOnline) return@delayed
                traceHealth("join+${tick}t", currentPlayer)
            }
        }
    }

    private fun traceHealth(marker: String, player: Player) {
        if (!config.bool("join.health-trace-enabled", true)) return

        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)
        val modifiers = maxHealth?.modifiers
            ?.sortedBy { it.key.toString() }
            ?.joinToString(prefix = "[", postfix = "]") { modifier ->
                "${modifier.key}:${modifier.amount}:${modifier.operation}"
            }
            ?: "unavailable"
        val scale = if (player.isHealthScaled) player.healthScale.toString() else "off"

        info(
            "[HealthTrace] marker={} player={} uuid={} world={} health={} absorption={} max={} base={} scale={} modifiers={}",
            marker,
            player.name,
            player.uniqueId,
            player.world.name,
            player.health,
            player.absorptionAmount,
            maxHealth?.value,
            maxHealth?.baseValue,
            scale,
            modifiers,
        )
    }

    private fun invulnerable(player: Player) {
        if (!config.bool("join.invulnerable-enabled", true)) return
        if (!player.isOnline) return
        if (player.hasPermission("arc.join.invulnerability.bypass")) return
        player.isInvulnerable = true
        invMap[player.uniqueId] = player.name
        info("Player {} is invulnerable", player.name)
        val ticks = config.integer("join.invulnerable-ticks", 20 * 7).toLong()
        delayed(ticks.ticks) {
            stripInvulnerable(player)
        }
    }

    private fun stripInvulnerable(player: Player) {
        if (!player.isOnline) return
        player.isInvulnerable = false
        invMap.remove(player.uniqueId)
        info("Player {} is not invulnerable anymore", player.name)
    }

    @Suppress("DEPRECATION")
    private fun fullHeal(player: Player) {
        delayed(config.integer("join.full-heal-delay-ticks", 10).toLong().ticks) {
            if (!config.bool("join.full-heal", true)) return@delayed
            if (!player.isOnline) return@delayed
            val currentHealth = player.health
            val maxHealth = player.maxHealth
            info("Player {} health {} maxhealth {}", player.name, currentHealth, maxHealth)
            if (currentHealth < maxHealth) player.health = maxHealth
        }
    }
}
