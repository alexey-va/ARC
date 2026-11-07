package ru.arc.graves

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** World collision for decorative graves. The chunk saves the ownership marker alongside the blocks. */
internal class DecorativeGraveBlocks(
    private val plugin: Plugin,
    private val height: (DecorativeGraveRecord) -> Int,
) : Listener, AutoCloseable {
    private data class ChunkKey(val world: UUID, val x: Int, val z: Int)
    private data class Cell(val x: Int, val y: Int, val z: Int) {
        fun block(chunk: Chunk): Block = chunk.getBlock(x, y, z)
    }

    private val journalKey = NamespacedKey(plugin, "decorative_grave_blocks")
    private var catalog = emptyMap<ChunkKey, List<DecorativeGraveRecord>>()
    private val installed = mutableMapOf<ChunkKey, Map<Cell, DecorativeGraveRecord>>()
    private var ready = false

    fun start(records: List<DecorativeGraveRecord>) {
        catalog = records.groupBy { it.chunkKey() }
        ready = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.worlds.forEach { world -> world.loadedChunks.forEach(::reconcile) }
    }

    fun update(records: List<DecorativeGraveRecord>, changed: DecorativeGraveRecord) {
        catalog = records.groupBy { it.chunkKey() }
        val key = changed.chunkKey()
        val world = plugin.server.getWorld(key.world) ?: return
        if (world.isChunkLoaded(key.x, key.z)) reconcile(world.getChunkAt(key.x, key.z))
    }

    fun refreshLoaded() {
        if (!ready) return
        for (key in catalog.keys) {
            val world = plugin.server.getWorld(key.world) ?: continue
            if (world.isChunkLoaded(key.x, key.z)) reconcile(world.getChunkAt(key.x, key.z))
        }
    }

    fun record(block: Block): DecorativeGraveRecord? =
        if (block.type != Material.BARRIER) null
        else installed[ChunkKey(block.world.uid, block.x shr 4, block.z shr 4)]?.get(Cell(block.x and 15, block.y, block.z and 15))

    // Rendering and reconciliation both accept our own upper barrier as headroom for its artwork.
    fun hasJournaledBarrier(block: Block): Boolean = block.type == Material.BARRIER &&
        Cell(block.x and 15, block.y, block.z and 15) in journal(block.chunk)

    @EventHandler(priority = EventPriority.LOWEST)
    fun load(event: ChunkLoadEvent) {
        if (ready) reconcile(event.chunk)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun unload(event: ChunkUnloadEvent) = clear(event.chunk)

    private fun reconcile(chunk: Chunk) {
        val key = chunk.key()
        val previous = journal(chunk)
        val desired = linkedMapOf<Cell, Material>()
        val owners = linkedMapOf<Cell, DecorativeGraveRecord>()
        for (record in catalog[key].orEmpty()) {
            if (record.world != chunk.world.name || record.y !in chunk.world.minHeight until chunk.world.maxHeight) continue
            val cells = (0 until height(record)).map { Cell(record.x and 15, record.y + it, record.z and 15) }
            if (cells.any { it in desired || it.y >= chunk.world.maxHeight }) continue
            val replaced = cells.map { cell ->
                val type = cell.block(chunk).type
                if (type == Material.BARRIER) previous[cell] else type.takeIf(Material::isAir)
            }
            if (replaced.any { it == null }) continue
            cells.zip(replaced).forEach { (cell, air) ->
                desired[cell] = checkNotNull(air)
                owners[cell] = record
            }
        }
        restore(chunk, previous.filterKeys { it !in desired })
        for (cell in desired.keys) {
            val block = cell.block(chunk)
            if (block.type != Material.BARRIER) block.setType(Material.BARRIER, false)
        }
        if (previous != desired) writeJournal(chunk, desired)
        if (owners.isEmpty()) installed.remove(key) else installed[key] = owners
    }

    private fun clear(chunk: Chunk) {
        val previous = journal(chunk)
        restore(chunk, previous)
        if (previous.isNotEmpty()) writeJournal(chunk, emptyMap())
        installed.remove(chunk.key())
    }

    private fun restore(chunk: Chunk, previous: Map<Cell, Material>) {
        for ((cell, air) in previous) {
            val block = cell.block(chunk)
            if (block.type == Material.BARRIER) block.setType(air, false)
        }
    }

    private fun journal(chunk: Chunk): Map<Cell, Material> {
        val coordinates = chunk.persistentDataContainer.get(journalKey, PersistentDataType.INTEGER_ARRAY) ?: return emptyMap()
        return buildMap {
            for (i in 0..coordinates.size - 4 step 4) {
                val x = coordinates[i]
                val y = coordinates[i + 1]
                val z = coordinates[i + 2]
                val air = AIR_TYPES.getOrNull(coordinates[i + 3]) ?: continue
                if (x in 0..15 && z in 0..15 && y in chunk.world.minHeight until chunk.world.maxHeight) put(Cell(x, y, z), air)
            }
        }
    }

    private fun writeJournal(chunk: Chunk, cells: Map<Cell, Material>) {
        val data = chunk.persistentDataContainer
        if (cells.isEmpty()) data.remove(journalKey)
        else data.set(journalKey, PersistentDataType.INTEGER_ARRAY,
            cells.flatMap { (cell, air) -> listOf(cell.x, cell.y, cell.z, AIR_TYPES.indexOf(air)) }.toIntArray())
    }

    private fun Chunk.key() = ChunkKey(world.uid, x, z)
    private fun DecorativeGraveRecord.chunkKey() = ChunkKey(worldId, x shr 4, z shr 4)

    override fun close() {
        if (!ready) return
        ready = false
        HandlerList.unregisterAll(this)
        for (key in installed.keys.toList()) {
            val world = plugin.server.getWorld(key.world) ?: continue
            if (world.isChunkLoaded(key.x, key.z)) clear(world.getChunkAt(key.x, key.z))
        }
        installed.clear()
    }

    private companion object {
        val AIR_TYPES = listOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
    }
}
