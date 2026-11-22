package ru.arc.listeners

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
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
import ru.arc.misc.BuilderSurvivalGuard
import ru.arc.rtp.RtpRespawnTracker
import ru.arc.sync.SyncManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.info
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JoinListener : Listener, AutoCloseable {

    private companion object {
        val HEALTH_TRACE_TICKS = longArrayOf(1, 2, 5, 10, 20, 40, 100, 200, 300)
    }

    private val config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "modules/misc.yml")
    private val arrivalProtection = SurvivalArrivalProtection(
        enabled = { config.bool("join.invulnerable-enabled", true) },
        runLater = { ticks, action -> delayed(ticks.ticks) { action() } },
    )
    private val healthTraceSessions: MutableMap<UUID, UUID> = ConcurrentHashMap()
    private val builderSurvivalGuard = BuilderSurvivalGuard()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onArrival(event: PlayerJoinEvent) = arrivalProtection.join(
        event.player,
        config.integer("join.invulnerable-ticks", 20 * 7).toLong(),
        untilActivity = ARC.serverName == "survival",
    )

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        builderSurvivalGuard.checkLater(event.player)
        traceHealthAfterJoin(event.player)
        ARC.pluginMessenger?.sendBackendRtpReady(event.player)
        ChatModeService.track(event.player.uniqueId)
        JobsModule.trackPlayer(event.player.uniqueId)
        SyncManager.playerJoin(event.player.uniqueId)
        fullHeal(event.player)
        AuditManager.join(event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMovementInput(event: PlayerInputEvent) = arrivalProtection.input(event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteraction(event: PlayerInteractEvent) = arrivalProtection.interact(event)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityInteraction(event: PlayerInteractEntityEvent) = arrivalProtection.activity(event.player)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityInteractionAt(event: PlayerInteractAtEntityEvent) = arrivalProtection.activity(event.player)

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAttack(event: PrePlayerAttackEntityEvent) = arrivalProtection.activity(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) = arrivalProtection.damage(event)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) = arrivalProtection.leave(event.entity)

    internal fun checkBuilderSurvivalLater(playerId: UUID) {
        ARC.instance.server.getPlayer(playerId)?.let(builderSurvivalGuard::checkLater)
    }

    override fun close() {
        arrivalProtection.close()
        builderSurvivalGuard.close()
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerLeave(event: PlayerQuitEvent) {
        arrivalProtection.leave(event.player)
        traceHealth("quit-low", event.player)
        healthTraceSessions.remove(event.player.uniqueId)
        RtpRespawnTracker.cancel(event.player.name)
        ChatModeService.untrack(event.player.uniqueId)
        JobsModule.untrackPlayer(event.player.uniqueId)
        SyncManager.playerQuit(event.player.uniqueId)
        AuditManager.leave(event.player)
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
        TreasureHuntManager.onPlayerQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKickProtectionCleanup(event: PlayerKickEvent) = arrivalProtection.leave(event.player)

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
