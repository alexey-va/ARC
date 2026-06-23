package ru.arc.configs

import com.google.gson.Gson
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.common.locationpools.LocationPool
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.util.Common
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class LocationPoolConfig {
    private var saveTask: BukkitTask? = null
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

    fun saveLocationPools(onlyDirty: Boolean) {
        LocationPoolManager.getAll().forEach { lp ->
            if (LocationPoolManager.isEphemeralPool(lp.id)) return@forEach
            if (onlyDirty && !lp.isDirty) return@forEach
            val path = ensureFolder().resolve("${lp.id}.json")
            lp.markClean()
            try {
                Files.writeString(path, gson.toJson(lp), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            } catch (e: Exception) {
                error("Error saving location pool: {}", lp.id, e)
            }
        }
    }

    fun deleteFile(id: String) {
        val path =
            ARC.instance.dataFolder
                .toPath()
                .resolve("location_pools/$id.json")
        Files.deleteIfExists(path)
    }

    private fun ensureFolder() =
        ARC.instance.dataFolder.toPath().resolve("location_pools").also {
            if (!Files.exists(it)) Files.createDirectories(it)
        }

    fun startSaveTask() {
        cancelTasks()
        saveTask =
            object : BukkitRunnable() {
                override fun run() = saveLocationPools(true)
            }.runTaskTimerAsynchronously(ARC.instance, 1200L, 1200L)
    }

    fun cancelTasks() {
        saveTask?.takeUnless { it.isCancelled }?.cancel()
    }
}
