package ru.arc.drawers

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal data class ControllerDeposit(val consumed: Int, val discarded: Int) {
    val stored get() = consumed - discarded
}

/** Main-thread index of connected drawers. Neither topology discovery nor item routing has a tick task. */
internal class DrawerControllers(private val store: DrawerStore, private val visuals: DrawerVisuals) : AutoCloseable {
    private data class ChunkPosition(val world: UUID, val x: Int, val z: Int)
    private data class Slot(val block: Block, val index: Int)
    private data class Candidate(val slot: Slot, val drawer: Drawer, val room: Int)
    private data class Entry(val key: ItemStack, val writable: Boolean, val voiding: Boolean)

    private class Network(val controller: Block) {
        val parents = LinkedHashMap<Block, Block>()
        val chunks = HashSet<ChunkPosition>()
        val writable = HashMap<ItemStack, LinkedHashSet<Slot>>()
        val voiding = HashMap<ItemStack, LinkedHashSet<Slot>>()
        var dirty = false
    }

    private val networks = HashMap<Block, Network>()
    private val watchingChunks = HashMap<ChunkPosition, MutableSet<Network>>()
    private val watchingDrawers = HashMap<Block, MutableSet<Network>>()

    fun size(controller: Block): Int = current(controller)?.parents?.size ?: 0

    fun accepts(controller: Block, item: ItemStack): Boolean {
        val network = current(controller) ?: return false
        val key = item.asOne()
        return key in network.writable || key in network.voiding
    }

    fun insert(controller: Block, item: ItemStack, amount: Int): ControllerDeposit {
        require(amount > 0)
        var network = current(controller) ?: return ControllerDeposit(0, 0)
        val key = item.asOne()
        var remaining = amount
        var discarded = 0
        while (remaining > 0) {
            val destination = choose(network, key) ?: break
            // choose can rebuild after a native change that did not fire a Bukkit block event.
            network = networks.getValue(controller)
            val (slot, drawer, room) = destination
            // Fill all usable space before a matching overflow upgrade is allowed to discard anything.
            val wanted = if (room > 0) minOf(room, remaining) else remaining
            val result = drawer.insert(slot.index, key, wanted) ?: break
            store.save(slot.block, result.drawer, drawer)
            remaining -= result.consumed
            discarded += result.discarded
            if (result.drawer !== drawer) visuals.refresh(slot.block, result.drawer, slot.index)
        }
        return ControllerDeposit(amount - remaining, discarded)
    }

    fun drawerChanged(block: Block, previous: Drawer?, next: Drawer?) {
        if (previous == null || next == null) {
            watchingChunks[chunkOf(block)]?.forEach { it.dirty = true }
            return
        }
        val watchers = watchingDrawers[block] ?: return
        val rulesChanged = previous.capacity != next.capacity || previous.voidOverflow != next.voidOverflow
        for (index in next.slots.indices) {
            if (!rulesChanged && previous.slots[index] === next.slots[index]) continue
            val before = entry(previous, index)
            val after = entry(next, index)
            if (before == after) continue
            val slot = Slot(block, index)
            for (network in watchers) {
                removeEntry(network, slot, before)
                addEntry(network, slot, after)
            }
        }
    }

    fun chunkChanged(chunk: Chunk, unloading: Boolean = false) {
        val position = ChunkPosition(chunk.world.uid, chunk.x, chunk.z)
        val watchers = watchingChunks[position] ?: return
        if (unloading) {
            watchers.filter { chunkOf(it.controller) == position }.forEach { remove(it.controller) }
        }
        watchingChunks[position]?.forEach { it.dirty = true }
    }

    fun worldUnloaded(world: UUID) {
        networks.keys.filter { it.world.uid == world }.forEach(::remove)
    }

    fun remove(controller: Block) {
        val network = networks.remove(controller) ?: return
        network.chunks.forEach { unwatch(watchingChunks, it, network) }
        network.parents.keys.forEach { unwatch(watchingDrawers, it, network) }
    }

    override fun close() {
        networks.clear()
        watchingChunks.clear()
        watchingDrawers.clear()
    }

    private fun current(controller: Block): Network? {
        if (!store.isController(controller)) {
            remove(controller)
            return null
        }
        return networks[controller]?.takeUnless { it.dirty } ?: discover(controller)
    }

    private fun choose(network: Network, key: ItemStack): Candidate? {
        val slot = preferredSlot(network, key) ?: return null
        validate(network, slot, key)?.let { return it }
        val refreshed = discover(network.controller)
        val replacement = preferredSlot(refreshed, key) ?: return null
        return validate(refreshed, replacement, key)
    }

    private fun preferredSlot(network: Network, key: ItemStack): Slot? =
        network.writable[key]?.firstOrNull() ?: network.voiding[key]?.firstOrNull()

    private fun validate(network: Network, slot: Slot, key: ItemStack): Candidate? {
        val target = drawerAt(slot.block) ?: return null
        val drawerSlot = target.slots.getOrNull(slot.index) ?: return null
        if (drawerSlot.sample?.item != key) return null
        val room = target.limit(key) - drawerSlot.count
        if (room <= 0 && !target.voidOverflow) return null
        // Only the chosen branch is rechecked. The first missing link invalidates this cached topology.
        var parent = network.parents.getValue(slot.block)
        while (parent != network.controller) {
            if (drawerAt(parent) == null) return null
            parent = network.parents.getValue(parent)
        }
        return Candidate(slot, target, room)
    }

    private fun discover(controller: Block): Network {
        remove(controller)
        val network = Network(controller)
        val seen = HashSet<Block>().apply { add(controller) }
        val queue = ArrayDeque<Pair<Block, Int>>().apply { add(controller to 0) }
        network.chunks += chunkOf(controller)
        while (queue.isNotEmpty()) {
            val (block, depth) = queue.removeFirst()
            for (face in NEIGHBORS) {
                val next = block.getRelative(face)
                if (!seen.add(next)) continue
                // Watch the frontier too, including currently unloaded chunks.
                network.chunks += chunkOf(next)
                val drawer = drawerAt(next) ?: continue
                network.parents[next] = block
                drawer.slots.indices.forEach { index -> addEntry(network, Slot(next, index), entry(drawer, index)) }
                if (depth + 1 < RANGE) queue.addLast(next to depth + 1)
            }
        }
        networks[controller] = network
        network.chunks.forEach { watchingChunks.getOrPut(it, ::HashSet).add(network) }
        network.parents.keys.forEach { watchingDrawers.getOrPut(it, ::HashSet).add(network) }
        return network
    }

    private fun drawerAt(block: Block): Drawer? =
        if (block.y in block.world.minHeight until block.world.maxHeight &&
            block.world.isChunkLoaded(block.x shr 4, block.z shr 4) && block.type == Material.BARREL
        ) store.find(block)?.drawer else null

    private fun entry(drawer: Drawer, index: Int): Entry? {
        val slot = drawer.slots[index]
        val key = slot.sample?.item ?: return null
        val writable = slot.count < drawer.limit(key)
        return if (writable || drawer.voidOverflow) Entry(key, writable, drawer.voidOverflow) else null
    }

    private fun addEntry(network: Network, slot: Slot, entry: Entry?) {
        if (entry == null) return
        if (entry.writable) network.writable.getOrPut(entry.key, ::LinkedHashSet).add(slot)
        if (entry.voiding) network.voiding.getOrPut(entry.key, ::LinkedHashSet).add(slot)
    }

    private fun removeEntry(network: Network, slot: Slot, entry: Entry?) {
        if (entry == null) return
        unwatch(network.writable, entry.key, slot)
        unwatch(network.voiding, entry.key, slot)
    }

    private fun chunkOf(block: Block) = ChunkPosition(block.world.uid, block.x shr 4, block.z shr 4)

    private fun <K, V> unwatch(map: MutableMap<K, out MutableSet<V>>, key: K, value: V) {
        val set = map[key] ?: return
        set.remove(value)
        if (set.isEmpty()) map.remove(key)
    }

    private companion object {
        const val RANGE = 12
        val NEIGHBORS = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN)
    }
}
