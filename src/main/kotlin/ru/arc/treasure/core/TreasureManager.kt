package ru.arc.treasure.core

import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
     * Atomically persists one complete pool and only then publishes it in memory.
     *
     * This is the content-management write boundary. Unlike the lifecycle
     * save methods, persistence failures are propagated to the caller.
     */
    @Synchronized
    fun replaceAndSave(
        directory: File,
        pool: TreasurePool,
    ): TreasurePool {
        directory.mkdirs()
        require(directory.isDirectory) { "Treasure path is not a directory: ${directory.absolutePath}" }

        val saved = pool.markClean()
        val yaml = YamlConfiguration()
        saved.toMap().forEach { (key, value) -> yaml.set(key, value) }
        val target = File(directory, "${saved.id}.yml").toPath()
        val temp = Files.createTempFile(directory.toPath(), ".${saved.id}-", ".tmp")

        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            pools[saved.id] = saved
            return saved
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Deletes one durable pool and then removes it from memory.
     *
     * Missing files are tolerated when the in-memory pool exists, which lets
     * an administrator repair drift without inventing a second state model.
     */
    @Synchronized
    fun deleteAndSave(
        directory: File,
        poolId: String,
    ): Boolean {
        if (!pools.containsKey(poolId)) return false
        Files.deleteIfExists(File(directory, "$poolId.yml").toPath())
        pools.remove(poolId)
        return true
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
            info("Treasure directory does not exist, creating: ${directory.absolutePath}")
            directory.mkdirs()
            return
        }

        val files = directory.listFiles { file -> file.extension == "yml" && file.name != "config.yml" }
            ?: emptyArray()

        info("Scanning treasure directory '${directory.name}': found ${files.size} yml file(s)")

        if (files.isEmpty()) {
            info("No treasure pool files found in ${directory.absolutePath}")
            return
        }

        var loaded = 0
        var failed = 0
        for (file in files) {
            try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val map = yaml.getValues(false)

                if (map.isEmpty()) {
                    warn("  Empty or unparseable pool file: ${file.name} — skipping")
                    debug("[treasure] empty pool file: {}", file.absolutePath)
                    failed++
                    continue
                }

                val filenameId = file.nameWithoutExtension

                // Use filename as id fallback if not present in file
                val mapWithId = if (map.containsKey("id")) map else map + ("id" to filenameId)

                val pool = TreasurePool.fromMap(mapWithId)
                if (pool != null) {
                    pools[pool.id] = pool
                    info("  Loaded pool '${pool.id}' — ${pool.size} treasure(s)")
                    loaded++
                } else {
                    warn("  Failed to parse pool file: ${file.name} (keys: ${map.keys})")
                    debug("[treasure] parse failed for {} keys={}", file.name, map.keys)
                    failed++
                }
            } catch (e: Exception) {
                error("  Error loading pool file: ${file.name}", e)
                debug("[treasure] exception loading {}: {}", file.name, e.message)
                failed++
            }
        }

        info("Treasure pools: $loaded loaded, $failed failed (total scanned: ${files.size})")
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
