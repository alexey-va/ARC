package ru.arc.common.locationpools

import com.destroystokyo.paper.ParticleBuilder
import com.google.common.cache.CacheBuilder
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.BudgetedTaskQueue
import ru.arc.ARC
import ru.arc.common.ServerLocation
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
    private val nativeWork: BudgetedTaskQueue,
) {
    private val editingPlayers = ConcurrentHashMap<UUID, String>()

    private val recentEdits =
        CacheBuilder
            .newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build<UUID, String>()

    private var showTask: ScheduledTask? = null
    private var timeoutTask: ScheduledTask? = null
    @Volatile private var generation = 0L
    private val pendingShows = ConcurrentHashMap<UUID, Long>()

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
        generation++
        showTask?.cancel()
        timeoutTask?.cancel()
        showTask = null
        timeoutTask = null
        pendingShows.clear()
    }

    fun reloadConfig(newConfig: LocationPoolModuleConfig) {
        config = newConfig
        start()
    }

    fun clearSessions() {
        editingPlayers.clear()
        recentEdits.invalidateAll()
    }

    fun startEditing(player: Player, poolId: String) {
        editingPlayers[player.uniqueId] = poolId
        player.sendMessage(mm(config.messages.startEditing.replace("%name%", poolId)))
    }

    fun cancelEditing(uuid: UUID, timeout: Boolean = false) {
        val poolId = editingPlayers.remove(uuid) ?: return
        playerProvider.getPlayer(uuid)?.let { player ->
            val message = if (timeout) config.messages.timeoutEditing else config.messages.cancelEditing
            player.sendMessage(mm(message.replace("%name%", poolId)))
        }
    }

    fun getEditingPool(uuid: UUID): String? = editingPlayers[uuid]

    fun isEditing(uuid: UUID): Boolean = editingPlayers.containsKey(uuid)

    fun cancelEditingForPool(poolId: String) {
        editingPlayers.entries.filter { it.value == poolId }.forEach { cancelEditing(it.key) }
    }

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
        try {
            val pool = LocationPoolRepository.getOrCreate(poolId)
            val message = if (isAdd) {
                pool.addLocation(location)
                config.messages.blockAdded
            } else if (pool.removeLocation(location)) config.messages.blockRemoved else config.messages.notInPool
            event.player.sendMessage(mm(message.replace("%count%", pool.size.toString())))
            recentEdits.put(event.player.uniqueId, poolId)
        } catch (failure: IllegalStateException) {
            event.player.sendMessage(mm("<red>Пул пока занят сохранением. Изменение не принято; повторите позже."))
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            event.player.sendMessage(mm("<red>Хранилище пула занято. Изменение не принято; повторите позже."))
        }
        return true
    }

    private fun startShowTask() {
        val delay = config.editorSettings.particleShowDelayTicks
        val interval = config.editorSettings.particleShowIntervalTicks
        showTask = scheduler.runTimer(delay, interval) {
            val epoch = generation
            val entries = editingPlayers.entries.iterator()
            nativeWork.submit({ generation == epoch }) {
                if (!entries.hasNext()) return@submit true
                val (uuid, poolId) = entries.next()
                showForPlayer(uuid, poolId, epoch)
                !entries.hasNext()
            }
        }
    }

    private fun showForPlayer(uuid: UUID, poolId: String, epoch: Long) {
        val player = playerProvider.getPlayer(uuid)
        if (player == null || !player.isOnline) {
            editingPlayers.remove(uuid, poolId)
            return
        }
        val pool = LocationPoolRepository.get(poolId) ?: return
        val store = ARC.plugin?.locationPoolConfig ?: return
        if (pendingShows.putIfAbsent(uuid, epoch) != null) return
        val settings = config.editorSettings
        val radius = settings.nearbyRadius
        val particleType = settings.particleType
        val particleCount = settings.particleCount
        val particleExtra = settings.particleExtra
        val particleOffset = settings.particleOffset
        val center = ServerLocation.of(player.location)
        // The worker searches only its own coordinate mirror. Bukkit capture and emission
        // each consume the same plugin-wide native budget, one player/particle per step.
        store.nearby(pool, center, radius).thenCompose { nearby ->
            val points = nearby.iterator()
            nativeWork.call({ generation == epoch }) {
                nativeWork.submit({ generation == epoch && editingPlayers[uuid] == poolId }) {
                    if (!points.hasNext()) return@submit true
                    val point = points.next()
                    val current = playerProvider.getPlayer(uuid)
                    if (current == null || !current.isOnline || current.world.name != center.world) return@submit true
                    val location = point.toLocation()
                    if (location != null && current.location.distanceSquared(location) <= radius * radius) {
                        ParticleManager.queue(ParticleBuilder(particleType).location(location).count(particleCount)
                            .extra(particleExtra).offset(particleOffset, particleOffset, particleOffset)
                            .receivers(listOf(current)))
                    }
                    !points.hasNext()
                }
            }.thenCompose { it }
        }.whenComplete { _, _ -> pendingShows.remove(uuid, epoch) }
    }

    private fun startTimeoutTask() {
        val delay = config.editorSettings.timeoutCheckDelayTicks
        val interval = config.editorSettings.timeoutCheckIntervalTicks
        timeoutTask = scheduler.runTimer(delay, interval) {
            val epoch = generation
            val players = editingPlayers.keys.iterator()
            nativeWork.submit({ generation == epoch }) {
                if (!players.hasNext()) return@submit true
                val uuid = players.next()
                if (recentEdits.getIfPresent(uuid) == null) cancelEditing(uuid, timeout = true)
                !players.hasNext()
            }
        }
    }

    // === Stats ===

    /**
     * Get count of players currently editing.
     */
    fun getEditingPlayersCount(): Int = editingPlayers.size
}
