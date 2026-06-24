package ru.arc.listeners

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.config.ConfigManager
import ru.arc.sync.SyncManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.info
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JoinListener : Listener {

    private val config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
    private val invMap: MutableMap<UUID, String> = ConcurrentHashMap()

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        SyncManager.playerJoin(event.player.uniqueId)
        invulnerable(event.player)
        fullHeal(event.player)
        AuditManager.join(event.player.name)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerLeave(event: PlayerQuitEvent) {
        SyncManager.playerQuit(event.player.uniqueId)
        AuditManager.leave(event.player.name)
        if (invMap.containsKey(event.player.uniqueId)) stripInvulnerable(event.player)
        TreasureHuntManager.onPlayerQuit(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerKick(event: PlayerKickEvent) {
        SyncManager.playerQuit(event.player.uniqueId)
        AuditManager.leave(event.player.name)
        if (invMap.containsKey(event.player.uniqueId)) stripInvulnerable(event.player)
        TreasureHuntManager.onPlayerQuit(event.player)
    }

    private fun invulnerable(player: Player) {
        if (!config.bool("join.invulnerable-enabled", true)) return
        if (!player.isOnline) return
        if (player.hasPermission("arc.bypass-invulnerable")) return
        player.isInvulnerable = true
        invMap[player.uniqueId] = player.name
        info("Player {} is invulnerable", player.name)
        val ticks = config.integer("join.invulnerable-ticks", 20 * 7).toLong()
        ARC.instance.server.scheduler.runTaskLater(ARC.instance, Runnable { stripInvulnerable(player) }, ticks)
    }

    private fun stripInvulnerable(player: Player) {
        if (!player.isOnline) return
        player.isInvulnerable = false
        invMap.remove(player.uniqueId)
        info("Player {} is not invulnerable anymore", player.name)
    }

    @Suppress("DEPRECATION")
    private fun fullHeal(player: Player) {
        ARC.instance.server.scheduler.runTaskLater(
            ARC.instance,
            Runnable {
                if (!config.bool("join.full-heal", true)) return@Runnable
                if (!player.isOnline) return@Runnable
                val currentHealth = player.health
                val maxHealth = player.maxHealth
                info("Player {} health {} maxhealth {}", player.name, currentHealth, maxHealth)
                if (currentHealth < maxHealth) player.health = maxHealth
            },
            config.integer("join.full-heal-delay-ticks", 10).toLong(),
        )
    }
}
