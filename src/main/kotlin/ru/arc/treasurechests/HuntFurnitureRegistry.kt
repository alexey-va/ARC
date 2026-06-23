package ru.arc.treasurechests

import com.google.gson.Gson
import org.bukkit.block.Block
import ru.arc.ARC
import ru.arc.common.chests.BlockPos
import ru.arc.util.Common
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Реестр IA-мебели охоты: entity UUID привязаны к координатам, хранятся в JSON.
 * Не зависит от CustomBlockData — переживает сломанный блок и жёсткий рестарт.
 */
data class HuntFurnitureAnchor(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val entityIds: List<String>,
    val barrierBlocks: List<BlockPos> = emptyList(),
) {
    fun entityUuids(): List<UUID> =
        entityIds.mapNotNull { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
        }

    fun matches(block: Block): Boolean {
        val loc = block.location
        return loc.world?.name == world && loc.blockX == x && loc.blockY == y && loc.blockZ == z
    }

    companion object {
        fun of(
            block: Block,
            entities: Collection<UUID>,
            barrierBlocks: Collection<BlockPos> = emptyList(),
        ): HuntFurnitureAnchor {
            val loc = block.location
            return HuntFurnitureAnchor(
                world = loc.world?.name ?: "unknown",
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                entityIds = entities.map { it.toString() },
                barrierBlocks = barrierBlocks.toList(),
            )
        }
    }

    fun anchorBlock(world: org.bukkit.World?): Block? {
        val w = world ?: return null
        return w.getBlockAt(x, y, z)
    }
}

data class HuntFurnitureIndex(
    val entries: MutableList<HuntFurnitureAnchor> = mutableListOf(),
)

object HuntFurnitureRegistry {
    private val gson: Gson = Common.prettyGson
    private val lock = Any()

    @Volatile
    var fileOverride: Path? = null

    private var index = HuntFurnitureIndex()

    private fun file(): Path {
        fileOverride?.let { return it }
        val dir =
            ARC.instance.dataFolder
                .toPath()
                .resolve("data")
        Files.createDirectories(dir)
        return dir.resolve("hunt-furniture.json")
    }

    @JvmStatic
    fun init() {
        load()
        info("[hunt-furniture] registry loaded: {} anchors", index.entries.size)
    }

    @JvmStatic
    fun register(
        block: Block,
        entityIds: Collection<UUID>,
        barrierBlocks: Collection<BlockPos> = emptyList(),
    ) {
        if (entityIds.isEmpty() && barrierBlocks.isEmpty()) return
        synchronized(lock) {
            index.entries.removeIf { it.matches(block) }
            index.entries.add(HuntFurnitureAnchor.of(block, entityIds, barrierBlocks))
            saveLocked()
        }
        debug(
            "[hunt-furniture] registered {} entities, {} barriers at {}",
            entityIds.size,
            barrierBlocks.size,
            block.location,
        )
    }

    @JvmStatic
    fun hasEntry(block: Block): Boolean =
        synchronized(lock) {
            index.entries.any { it.matches(block) }
        }

    /** Снимает запись с реестра и возвращает её (для destroy/cleanup). */
    @JvmStatic
    fun take(block: Block): HuntFurnitureAnchor? =
        synchronized(lock) {
            val entry = index.entries.firstOrNull { it.matches(block) } ?: return null
            index.entries.remove(entry)
            saveLocked()
            entry
        }

    @JvmStatic
    fun unregister(block: Block): List<UUID> = take(block)?.entityUuids().orEmpty()

    @JvmStatic
    fun entityIdsAt(block: Block): List<UUID> =
        synchronized(lock) {
            index.entries
                .firstOrNull { it.matches(block) }
                ?.entityUuids()
                .orEmpty()
        }

    /** Забирает все записи для startup-cleanup (очищает файл). */
    @JvmStatic
    fun drainAll(): List<HuntFurnitureAnchor> =
        synchronized(lock) {
            if (index.entries.isEmpty()) return emptyList()
            val copy = index.entries.toList()
            index.entries.clear()
            saveLocked()
            copy
        }

    @JvmStatic
    fun resetForTests() {
        synchronized(lock) {
            index = HuntFurnitureIndex()
        }
    }

    private fun load() {
        val path = file()
        if (!Files.isRegularFile(path)) {
            index = HuntFurnitureIndex()
            return
        }
        try {
            val json = Files.readString(path)
            index = gson.fromJson(json, HuntFurnitureIndex::class.java) ?: HuntFurnitureIndex()
        } catch (e: Exception) {
            error("[hunt-furniture] failed to load {}, starting empty", path, e)
            index = HuntFurnitureIndex()
        }
    }

    private fun saveLocked() {
        val path = file()
        try {
            Files.writeString(
                path,
                gson.toJson(index),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } catch (e: Exception) {
            error("[hunt-furniture] failed to save {}", path, e)
        }
    }
}
