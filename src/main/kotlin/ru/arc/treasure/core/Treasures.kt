package ru.arc.treasure.core

import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.modules.EconomyModule
import ru.arc.util.Logging.info
import java.io.File

/**
 * Main entry point for the treasure system.
 * Provides global access to the treasure manager and service.
 */
object Treasures : PluginModule {
    override val name = "Treasures"
    override val priority = 77

    private lateinit var _manager: TreasureManager
    private lateinit var _service: TreasureService
    private lateinit var dataDir: File

    /** The treasure manager for pool operations */
    val manager: TreasureManager
        get() = _manager

    /** The treasure service for giving treasures */
    val service: TreasureService
        get() = _service

    override fun init() {
        info("Initializing treasure module...")

        dataDir = File(ARC.instance.dataFolder, "treasures/pools")
        dataDir.mkdirs()

        _manager = TreasureManager()
        _service =
            TreasureService(
                poolProvider = { _manager.getPool(it) },
                economyProvider = { EconomyModule.getEconomy() },
            )

        _manager.loadFrom(dataDir)

        info("Treasure module initialized with ${_manager.allPools.size} pools")
    }

    override fun reload() {
        info("Reloading treasure module...")
        _manager.loadFrom(dataDir)
        info("Treasure module reloaded")
    }

    override fun shutdown() {
        info("Shutting down treasure module...")
        _manager.saveTo(dataDir)
        info("Treasure module shutdown complete")
    }

    // ==================== Convenience Methods ====================

    /**
     * Gets a pool by ID.
     */
    fun getPool(id: String): TreasurePool? = _manager.getPool(id)

    /**
     * Gets or creates a pool.
     */
    fun getOrCreatePool(id: String): TreasurePool = _manager.getOrCreatePool(id)

    /**
     * Alias for getOrCreatePool for backward compatibility.
     */
    fun getOrCreate(id: String): TreasurePool = _manager.getOrCreatePool(id)

    /**
     * Checks if a pool with the given ID exists.
     */
    fun exists(id: String): Boolean = _manager.getPool(id) != null

    /**
     * Gets all pools.
     */
    fun getAllPools(): List<TreasurePool> = _manager.allPools

    /**
     * Adds a treasure to a pool.
     */
    fun addTreasure(
        poolId: String,
        treasure: Treasure,
    ): TreasurePool? = _manager.addTreasure(poolId, treasure)

    /**
     * Removes a treasure from a pool by treasure reference.
     */
    fun removeTreasure(
        poolId: String,
        treasure: Treasure,
    ): TreasurePool? = _manager.removeTreasure(poolId, treasure.id)

    /**
     * Updates a treasure in a pool.
     */
    fun updateTreasure(
        poolId: String,
        treasure: Treasure,
    ): TreasurePool? = _manager.updateTreasure(poolId, treasure)

    /**
     * Deletes a pool by ID.
     */
    fun delete(poolId: String): Boolean {
        val deleted = _manager.deletePool(poolId)
        if (deleted) {
            _manager.deletePoolFile(dataDir, poolId)
        }
        return deleted
    }

    /**
     * Clears all pools from memory.
     */
    fun clear() {
        _manager.clear()
    }

    /**
     * Saves all pools to disk.
     */
    fun saveAll() {
        _manager.saveTo(dataDir)
    }

    /**
     * Saves only modified pools to disk.
     */
    fun saveDirty() {
        _manager.saveDirty(dataDir)
    }
}
