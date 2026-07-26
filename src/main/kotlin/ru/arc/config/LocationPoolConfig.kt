package ru.arc.config

import com.google.gson.Gson
import ru.arc.ARC
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class LocationPoolConfig {
    private var saveTask: ScheduledTask? = null
    private val gson: Gson = Common.prettyGson

    init {
        loadConfig()
        startSaveTask()
    }

    fun loadConfig() {
        LocationPoolManager.clear()
        val folder = ensureFolder()
        info("[LocPool] Loading pools from {}", folder.toString())
        var loaded = 0
        var failed = 0
        try {
            Files.newDirectoryStream(folder).use { dir ->
                for (path in dir) {
                    if (Files.isDirectory(path)) continue
                    val fileName = path.fileName.toString()
                    if (!fileName.endsWith(".json")) {
                        info("[LocPool] Skipping non-JSON file: {}", fileName)
                        continue
                    }
                    try {
                        val json = Files.readString(path)
                        val lp = gson.fromJson(json, LocationPool::class.java)
                        if (lp.id.isBlank()) {
                            warn("[LocPool] Pool in {} has blank id after deserialization — skipping", fileName)
                            failed++
                            continue
                        }
                        val normalizedId =
                            runCatching { LocationPool.normalizePersistentId(lp.id) }
                                .getOrElse {
                                    warn("[LocPool] Pool in {} has unsafe id '{}' — skipping", fileName, lp.id)
                                    failed++
                                    continue
                                }
                        if (normalizedId != lp.id) {
                            warn("[LocPool] Pool id '{}' in {} is not normalized — skipping", lp.id, fileName)
                            failed++
                            continue
                        }
                        if (LocationPoolManager.getPool(lp.id) != null) {
                            warn("[LocPool] Duplicate pool id '{}' from file {} — overwriting", lp.id, fileName)
                        }
                        LocationPoolManager.addPool(lp)
                        info("[LocPool] Loaded pool '{}' from {} ({} locations)", lp.id, fileName, lp.size)
                        loaded++
                    } catch (e: Exception) {
                        error("[LocPool] Failed to parse {}: {}", fileName, e.message, e)
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            error("[LocPool] Error scanning pools directory", e)
        }
        if (failed == 0) {
            info("[LocPool] Done — {} pools loaded", loaded)
        } else {
            warn("[LocPool] Done — {} pools loaded, {} failed", loaded, failed)
        }
    }

    @Synchronized
    fun saveLocationPools(onlyDirty: Boolean) {
        LocationPoolManager.getAll().forEach { lp ->
            if (LocationPoolManager.isEphemeralPool(lp.id)) return@forEach
            if (onlyDirty && !lp.isDirty) return@forEach
            try {
                saveLocationPool(lp)
            } catch (e: Exception) {
                error("Error saving location pool: {}", lp.id, e)
            }
        }
    }

    @Synchronized
    fun saveLocationPool(pool: LocationPool) {
        val id = LocationPool.normalizePersistentId(pool.id)
        require(!LocationPoolManager.isEphemeralPool(id)) { "Ephemeral location pools cannot be persisted" }
        val folder = ensureFolder()
        val path = folder.resolve("$id.json")
        val temp = Files.createTempFile(folder, ".$id-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(pool), StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            pool.markClean()
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun deleteFile(id: String) {
        val normalizedId = LocationPool.normalizePersistentId(id)
        val path =
            ARC.instance.dataFolder
                .toPath()
                .resolve("location_pools/$normalizedId.json")
        Files.deleteIfExists(path)
    }

    private fun ensureFolder() =
        ARC.instance.dataFolder.toPath().resolve("location_pools").also {
            if (!Files.exists(it)) Files.createDirectories(it)
        }

    fun startSaveTask() {
        cancelTasks()
        saveTask = repeatingAsync(1_200.ticks, delay = 1_200.ticks) {
            saveLocationPools(true)
        }
    }

    fun cancelTasks() {
        saveTask?.takeUnless { it.isCancelled }?.cancel()
        saveTask = null
    }
}
