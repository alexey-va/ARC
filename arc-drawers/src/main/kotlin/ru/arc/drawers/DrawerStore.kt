package ru.arc.drawers

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level

/**
 * Main-thread cache of the loaded chunk records. Paper writes the PDC with the world.
 * Counts are 16-bit values; only a changed item sample touches the native item payload.
 */
internal class DrawerStore(private val plugin: Plugin) {
    private data class ChunkAddress(val world: UUID, val chunk: Long)
    internal data class Record(val drawer: Drawer?)
    private val cached = HashMap<ChunkAddress, MutableMap<Int, Record>>()

    fun find(block: Block): Record? {
        val chunk = block.chunk
        val position = position(block)
        cached[address(chunk)]?.get(position)?.let { return it }
        val data = chunk.persistentDataContainer
        val key = key(position)
        if (!data.has(key)) return null
        val record = Record(runCatching {
            val header = requireNotNull(data.get(key, PersistentDataType.BYTE_ARRAY))
            DrawerHeader.decode(header) { index ->
                data.get(sampleKey(position, index), PersistentDataType.BYTE_ARRAY)?.let {
                    val item = ItemStack.deserializeBytes(it)
                    require(!item.isEmpty)
                    DrawerSample(item)
                }
            }
        }.onFailure { failure ->
            plugin.logger.log(Level.WARNING, "Cannot read drawer at ${block.world.name} ${block.x},${block.y},${block.z}", failure)
        }.getOrNull())
        cached.getOrPut(address(chunk), ::HashMap)[position] = record
        return record
    }

    fun save(block: Block, next: Drawer, previous: Drawer? = null) {
        if (next === previous) return
        val position = position(block)
        val chunk = block.chunk
        val data = chunk.persistentDataContainer
        val header = DrawerHeader.encode(next)
        val changes = next.slots.indices.filter { next.slots[it].sample !== previous?.slots?.get(it)?.sample }
        // Prepare every native item before touching the record or an inventory.
        val payloads = changes.associateWith { next.slots[it].sample?.item?.serializeAsBytes() }
        payloads.forEach { (index, bytes) -> writeSample(data, position, index, bytes) }
        data.set(key(position), PersistentDataType.BYTE_ARRAY, header)
        cached.getOrPut(address(chunk), ::HashMap)[position] = Record(next)
    }

    fun remove(block: Block) {
        val position = position(block)
        val chunk = block.chunk
        val data = chunk.persistentDataContainer
        data.remove(key(position))
        repeat(DrawerLayout.FOUR.slotCount) { data.remove(sampleKey(position, it)) }
        cached[address(chunk)]?.remove(position)
    }

    fun positions(chunk: Chunk): List<Int> = chunk.persistentDataContainer.keys.mapNotNull {
        if (it.namespace == namespace && it.key.startsWith("d_")) it.key.substring(2).toIntOrNull(36) else null
    }

    fun cachedBlocks(chunk: Chunk): List<Block> =
        cached.remove(address(chunk))?.keys?.map { blockAt(chunk, it) }.orEmpty()

    fun blockAt(chunk: Chunk, position: Int): Block =
        chunk.getBlock(position and 15, position shr 8, (position shr 4) and 15)

    fun clear() = cached.clear()

    private fun position(block: Block): Int = (block.y shl 8) or ((block.z and 15) shl 4) or (block.x and 15)
    private fun address(chunk: Chunk) = ChunkAddress(chunk.world.uid, chunk.chunkKey)
    private val namespace = NamespacedKey(plugin, "drawer").namespace
    private fun key(position: Int) = NamespacedKey(plugin, "d_${position.toString(36)}")
    private fun sampleKey(position: Int, slot: Int) = NamespacedKey(plugin, "d_${position.toString(36)}_${slot}")

    private fun writeSample(data: PersistentDataContainer, position: Int, index: Int, bytes: ByteArray?) {
        val key = sampleKey(position, index)
        if (bytes == null) data.remove(key) else data.set(key, PersistentDataType.BYTE_ARRAY, bytes)
    }
}

internal object DrawerHeader {
    private const val VERSION: Byte = 1
    private const val LOCKED = 0x10
    private const val SHOW_COUNTS = 0x20
    private const val VOID = 0x40

    fun encode(drawer: Drawer): ByteArray {
        val flags = drawer.layout.ordinal or (drawer.capacity.ordinal shl 2) or
            (if (drawer.locked) LOCKED else 0) or (if (drawer.showCounts) SHOW_COUNTS else 0) or
            (if (drawer.voidOverflow) VOID else 0)
        val bytes = ByteArray(2 + 2 * drawer.slots.size)
        bytes[0] = VERSION
        bytes[1] = flags.toByte()
        drawer.slots.forEachIndexed { index, slot ->
            check(slot.count in 0..0xFFFF)
            bytes[2 + index * 2] = (slot.count ushr 8).toByte()
            bytes[3 + index * 2] = slot.count.toByte()
        }
        return bytes
    }

    fun decode(bytes: ByteArray, sample: (Int) -> DrawerSample?): Drawer {
        require(bytes.size >= 2 && bytes[0] == VERSION)
        val flags = bytes[1].toInt() and 0xFF
        require(flags and 0x80 == 0)
        val layout = DrawerLayout.entries[flags and 3]
        require(bytes.size == 2 + 2 * layout.slotCount)
        val locked = flags and LOCKED != 0
        val slots = List(layout.slotCount) { index ->
            val count = ((bytes[2 + index * 2].toInt() and 0xFF) shl 8) or (bytes[3 + index * 2].toInt() and 0xFF)
            val item = sample(index)
            require(count == 0 || item != null)
            require(locked || count > 0 || item == null)
            DrawerSlot(item, count)
        }
        return Drawer(
            layout, Capacity.entries[(flags shr 2) and 3], locked, flags and SHOW_COUNTS != 0, flags and VOID != 0, slots,
        ).also { require(it.canUse(it.capacity)) }
    }
}
