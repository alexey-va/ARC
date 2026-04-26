package ru.arc.treasure.core

import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages treasure pools with in-memory storage and file persistence.
 * Thread-safe for concurrent access.
 */
class TreasureManager {
    private val pools = ConcurrentHashMap<String, TreasurePool>()

    // ==================== Pool Operations ====================

    /**
     * Creates a new pool with the given ID.
     * Returns null if a pool with this ID already exists.
     */
    fun createPool(id: String): TreasurePool? {
        val pool = TreasurePool(id)
        return if (pools.putIfAbsent(id, pool) == null) pool else null
    }

    /**
     * Gets a pool by ID, or null if not found.
     */
    fun getPool(id: String): TreasurePool? = pools[id]

    /**
     * Gets a pool by ID, creating it if it doesn't exist.
     */
    fun getOrCreatePool(id: String): TreasurePool = pools.getOrPut(id) { TreasurePool(id) }

    /**
     * Updates a pool with a new version.
     * Returns the updated pool, or null if pool not found.
     */
    fun updatePool(pool: TreasurePool): TreasurePool? {
        if (!pools.containsKey(pool.id)) return null
        pools[pool.id] = pool
        return pool
    }

    /**
     * Deletes a pool by ID.
     * Returns true if the pool was deleted.
     */
    fun deletePool(id: String): Boolean = pools.remove(id) != null

    /**
     * Gets all pools.
     */
    val allPools: List<TreasurePool>
        get() = pools.values.toList()

    /**
     * Clears all pools from memory.
     */
    fun clear() {
        pools.clear()
    }

    // ==================== Treasure Operations ====================

    /**
     * Adds a treasure to the specified pool.
     * Returns the updated pool, or null if pool not found.
     */
    fun addTreasure(
        poolId: String,
        treasure: Treasure,
    ): TreasurePool? {
        val pool = pools[poolId] ?: return null
        val updated = pool.add(treasure)
        pools[poolId] = updated
        return updated
    }

    /**
     * Removes a treasure from the specified pool.
     * Returns the updated pool, or null if pool not found.
     */
    fun removeTreasure(
        poolId: String,
        treasureId: String,
    ): TreasurePool? {
        val pool = pools[poolId] ?: return null
        val updated = pool.remove(treasureId)
        pools[poolId] = updated
        return updated
    }

    /**
     * Updates a treasure in the specified pool.
     * Returns the updated pool, or null if pool not found.
     */
    fun updateTreasure(
        poolId: String,
        treasure: Treasure,
    ): TreasurePool? {
        val pool = pools[poolId] ?: return null
        val updated = pool.update(treasure)
        pools[poolId] = updated
        return updated
    }

    // ==================== Persistence ====================

    /**
     * Loads all pools from the specified directory.
     * Clears existing pools before loading.
     */
    fun loadFrom(directory: File) {
        pools.clear()

        if (!directory.exists()) {
            directory.mkdirs()
            return
        }

        directory.listFiles { file -> file.extension == "yml" }?.forEach { file ->
            try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val map = yaml.getValues(false)
                val pool = TreasurePool.fromMap(map)

                if (pool != null) {
                    pools[pool.id] = pool
                    info("Loaded treasure pool: ${pool.id} with ${pool.size} treasures")
                } else {
                    warn("Failed to parse pool file: ${file.name}")
                }
            } catch (e: Exception) {
                error("Error loading pool file: ${file.name}", e)
            }
        }
    }

    /**
     * Saves all pools to the specified directory.
     */
    fun saveTo(directory: File) {
        directory.mkdirs()

        pools.forEach { (id, pool) ->
            try {
                val file = File(directory, "$id.yml")
                val yaml = YamlConfiguration()
                pool.toMap().forEach { (key, value) -> yaml.set(key, value) }
                yaml.save(file)

                // Mark pool as clean
                pools[id] = pool.markClean()
            } catch (e: Exception) {
                error("Error saving pool: $id", e)
            }
        }
    }

    /**
     * Saves only dirty pools to the specified directory.
     */
    fun saveDirty(directory: File) {
        directory.mkdirs()

        pools.filter { it.value.isDirty }.forEach { (id, pool) ->
            try {
                val file = File(directory, "$id.yml")
                val yaml = YamlConfiguration()
                pool.toMap().forEach { (key, value) -> yaml.set(key, value) }
                yaml.save(file)

                // Mark pool as clean
                pools[id] = pool.markClean()
            } catch (e: Exception) {
                error("Error saving dirty pool: $id", e)
            }
        }
    }

    /**
     * Deletes a pool file from the specified directory.
     */
    fun deletePoolFile(
        directory: File,
        poolId: String,
    ): Boolean {
        val file = File(directory, "$poolId.yml")
        return file.delete()
    }
}
