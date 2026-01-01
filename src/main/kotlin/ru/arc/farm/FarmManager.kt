package ru.arc.farm

import org.bukkit.event.block.BlockBreakEvent
import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import java.time.LocalDate

/**
 * Central manager for all farm zones.
 *
 * Handles:
 * - Zone initialization and cleanup
 * - Event routing to appropriate zones
 * - Daily limit resets
 */
class FarmService(
    private val config: FarmModuleConfig,
    private val regionFactory: FarmRegionFactory,
    private val scheduler: TaskScheduler,
    private val plugin: org.bukkit.plugin.Plugin,
    private val messages: FarmMessages = FarmMessages()
) {
    private val zones = mutableListOf<FarmZone>()
    private var resetTask: ScheduledTask? = null
    private var lastResetDay = -1

    /**
     * Initialize all zones from config.
     */
    fun start() {
        stop()

        // Create farm zones
        for (farmConfig in config.farms) {
            val region = regionFactory.create(farmConfig.worldName, farmConfig.regionName)
            if (region.isValid()) {
                val farm = CropFarm(
                    id = farmConfig.id,
                    priority = farmConfig.priority,
                    config = farmConfig,
                    region = region,
                    adminPermission = config.adminPermission,
                    messages = messages
                )
                zones.add(farm)
                info("Loaded farm zone '{}' in {} / {}", farmConfig.id, farmConfig.worldName, farmConfig.regionName)
            }
        }

        // Create lumbermill zones
        for (lumberConfig in config.lumbermills) {
            val region = regionFactory.create(lumberConfig.worldName, lumberConfig.regionName)
            if (region.isValid()) {
                val lumbermill = Lumbermill(
                    id = lumberConfig.id,
                    priority = lumberConfig.priority,
                    config = lumberConfig,
                    region = region,
                    adminPermission = config.adminPermission,
                    messages = messages
                )
                zones.add(lumbermill)
                info(
                    "Loaded lumbermill zone '{}' in {} / {}",
                    lumberConfig.id,
                    lumberConfig.worldName,
                    lumberConfig.regionName
                )
            }
        }

        // Create mine zones
        for (mineConfig in config.mines) {
            val region = regionFactory.create(mineConfig.worldName, mineConfig.regionName)
            if (region.isValid()) {
                val mine = Mine(
                    id = mineConfig.id,
                    priority = mineConfig.priority,
                    config = mineConfig,
                    region = region,
                    adminPermission = config.adminPermission,
                    plugin = plugin,
                    scheduler = scheduler,
                    messages = messages
                )
                mine.start()
                zones.add(mine)
                info("Loaded mine zone '{}' in {} / {}", mineConfig.id, mineConfig.worldName, mineConfig.regionName)
            }
        }

        // Sort zones by priority (higher first)
        zones.sortByDescending { it.priority }

        // Start daily reset task
        resetTask = scheduler.runTimer(0L, 60L * 20L) {
            checkDailyReset()
        }

        info("Farm service started with {} zones", zones.size)
    }

    /**
     * Stop all zones and cleanup.
     */
    fun stop() {
        resetTask?.cancel()
        resetTask = null

        zones.forEach { it.cleanup() }
        zones.clear()
    }

    /**
     * Process a block break event.
     * Routes to the first zone that handles it.
     */
    fun processEvent(event: BlockBreakEvent) {
        for (zone in zones) {
            val result = zone.processBreak(event)
            if (result != BreakResult.NotHandled) {
                return
            }
        }
    }

    /**
     * Check if daily reset is needed.
     */
    private fun checkDailyReset() {
        val currentDay = LocalDate.now().dayOfMonth
        if (currentDay != lastResetDay) {
            lastResetDay = currentDay
            resetAllLimits()
        }
    }

    /**
     * Reset all zone limits.
     */
    fun resetAllLimits() {
        zones.forEach { it.resetLimits() }
        info("Farm daily limits reset")
    }

    /**
     * Get all active zones.
     */
    fun getZones(): List<FarmZone> = zones.toList()

    /**
     * Get zone by ID.
     */
    fun getZone(id: String): FarmZone? = zones.find { it.id == id }
}

/**
 * Static facade for Java compatibility.
 */
object FarmManager {

    private var service: FarmService? = null

    /**
     * Initialize farm manager with default production dependencies.
     */
    @JvmStatic
    fun init() {
        if (HookRegistry.wgHook == null) {
            info("WorldGuard not found! Disabling farm features...")
            return
        }

        val config = FarmModuleConfig.load(ARC.plugin.dataPath)
        val scheduler = BukkitTaskScheduler(ARC.plugin)
        val regionFactory = WorldGuardRegionFactory()

        service = FarmService(
            config = config,
            regionFactory = regionFactory,
            scheduler = scheduler,
            plugin = ARC.plugin
        )

        service?.start()
    }

    /**
     * Initialize with custom service (for testing).
     */
    @JvmStatic
    fun init(customService: FarmService) {
        service?.stop()
        service = customService
        service?.start()
    }

    /**
     * Stop and cleanup.
     */
    @JvmStatic
    fun clear() {
        service?.stop()
        service = null
    }

    /**
     * Process block break event.
     */
    @JvmStatic
    fun processEvent(event: BlockBreakEvent) {
        service?.processEvent(event)
    }

    /**
     * Cancel tasks (alias for clear).
     */
    @JvmStatic
    fun cancelTasks() {
        clear()
    }

    /**
     * Get the underlying service.
     */
    fun getService(): FarmService? = service
}

