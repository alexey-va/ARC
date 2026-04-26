package ru.arc.common.locationpools

import com.destroystokyo.paper.ParticleBuilder
import com.google.common.cache.CacheBuilder
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.debug
import ru.arc.util.ParticleManager
import ru.arc.util.TextUtil.mm
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Interface for getting players by UUID.
 */
interface PlayerProvider {
    fun getPlayer(uuid: UUID): Player?
}

/**
 * Service for managing location pool editing.
 *
 * Fully testable through dependency injection.
 */
class LocationPoolService(
    private var config: LocationPoolModuleConfig,
    private val scheduler: TaskScheduler,
    private val playerProvider: PlayerProvider,
) {
    private val editingPlayers = ConcurrentHashMap<UUID, String>()

    private val recentEdits =
        CacheBuilder
            .newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build<UUID, String>()

    private var showTask: ScheduledTask? = null
    private var timeoutTask: ScheduledTask? = null

    // === Lifecycle ===

    /**
     * Start the editing service tasks.
     */
    fun start() {
        stop()
        startShowTask()
        startTimeoutTask()
        debug("LocationPool service started")
    }

    /**
     * Stop the editing service tasks.
     */
    fun stop() {
        showTask?.cancel()
        timeoutTask?.cancel()
        showTask = null
        timeoutTask = null
    }

    /**
     * Reload configuration.
     */
    fun reloadConfig(newConfig: LocationPoolModuleConfig) {
        this.config = newConfig
        // Restart tasks with new config
        start()
    }

    /**
     * Clear all editing sessions.
     */
    fun clearSessions() {
        editingPlayers.clear()
        recentEdits.invalidateAll()
    }

    // === Editing Management ===

    /**
     * Start editing a pool for a player.
     */
    fun startEditing(
        player: Player,
        poolId: String,
    ) {
        editingPlayers[player.uniqueId] = poolId

        val message = config.messages.startEditing.replace("%name%", poolId)
        player.sendMessage(mm(message))
    }

    /**
     * Cancel editing for a player.
     *
     * @param timeout true if cancelled due to timeout
     */
    fun cancelEditing(
        uuid: UUID,
        timeout: Boolean = false,
    ) {
        val poolId = editingPlayers.remove(uuid) ?: return

        playerProvider.getPlayer(uuid)?.let { player ->
            val message =
                if (timeout) {
                    config.messages.timeoutEditing
                } else {
                    config.messages.cancelEditing
                }.replace("%name%", poolId)

            player.sendMessage(mm(message))
        }
    }

    /**
     * Get the pool ID being edited by a player.
     */
    fun getEditingPool(uuid: UUID): String? = editingPlayers[uuid]

    /**
     * Check if a player is editing.
     */
    fun isEditing(uuid: UUID): Boolean = editingPlayers.containsKey(uuid)

    /**
     * Cancel editing for all players editing a specific pool.
     */
    fun cancelEditingForPool(poolId: String) {
        editingPlayers.entries
            .filter { it.value == poolId }
            .forEach { cancelEditing(it.key, timeout = false) }
    }

    // === Block Processing ===

    /**
     * Process a block place event for location pool editing.
     *
     * @return true if the event was handled
     */
    fun processBlockPlace(event: BlockPlaceEvent): Boolean {
        val poolId = editingPlayers[event.player.uniqueId] ?: return false

        val blockType = event.blockPlaced.type
        val isAdd = blockType == Material.GOLD_BLOCK
        val isRemove = blockType == Material.REDSTONE_BLOCK

        if (!isAdd && !isRemove) {
            event.player.sendMessage(mm(config.messages.invalidBlock))
            return true
        }

        event.isCancelled = true
        val location = event.block.location.toCenterLocation()
        val pool = LocationPoolRepository.getOrCreate(poolId)

        if (isAdd) {
            pool.addLocation(location)
            val message = config.messages.blockAdded.replace("%count%", pool.size.toString())
            event.player.sendMessage(mm(message))
        } else {
            val removed = pool.removeLocation(location)
            val message =
                if (removed) {
                    config.messages.blockRemoved
                } else {
                    config.messages.notInPool
                }.replace("%count%", pool.size.toString())
            event.player.sendMessage(mm(message))
        }

        recentEdits.put(event.player.uniqueId, poolId)
        return true
    }

    // === Tasks ===

    private fun startShowTask() {
        val delay = config.editorSettings.particleShowDelayTicks
        val interval = config.editorSettings.particleShowIntervalTicks

        showTask =
            scheduler.runTimerAsync(delay, interval) {
                val settings = config.editorSettings
                val radius = settings.nearbyRadius
                val particleType = settings.particleType
                val particleCount = settings.particleCount
                val particleExtra = settings.particleExtra
                val particleOffset = settings.particleOffset

                val toRemove = mutableListOf<UUID>()

                for ((uuid, poolId) in editingPlayers) {
                    val player = playerProvider.getPlayer(uuid)
                    if (player == null || !player.isOnline) {
                        toRemove.add(uuid)
                        continue
                    }

                    val nearby = LocationPoolRepository.getNearbyLocations(poolId, player.location, radius)
                    nearby.forEach { loc ->
                        ParticleManager.queue(
                            ParticleBuilder(particleType)
                                .location(loc)
                                .count(particleCount)
                                .extra(particleExtra)
                                .offset(particleOffset, particleOffset, particleOffset)
                                .receivers(listOf(player)),
                        )
                    }
                }

                toRemove.forEach { editingPlayers.remove(it) }
            }
    }

    private fun startTimeoutTask() {
        val delay = config.editorSettings.timeoutCheckDelayTicks
        val interval = config.editorSettings.timeoutCheckIntervalTicks

        timeoutTask =
            scheduler.runTimerAsync(delay, interval) {
                for (uuid in editingPlayers.keys) {
                    if (recentEdits.getIfPresent(uuid) == null) {
                        cancelEditing(uuid, timeout = true)
                    }
                }
            }
    }

    // === Stats ===

    /**
     * Get count of players currently editing.
     */
    fun getEditingPlayersCount(): Int = editingPlayers.size
}
