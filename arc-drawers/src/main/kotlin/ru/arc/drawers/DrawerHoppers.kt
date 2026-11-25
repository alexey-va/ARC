package ru.arc.drawers

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Hopper
import org.bukkit.block.data.type.Hopper as HopperData
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.HopperInventorySearchEvent
import org.bukkit.event.inventory.HopperInventorySearchEvent.ContainerType.DESTINATION
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

internal class DrawerHoppers(
    private val store: DrawerStore,
    private val visuals: DrawerVisuals,
    private val controllers: DrawerControllers,
) : Listener {
    private data class Transfer(
        val next: Drawer,
        val drawerSlot: Int,
        val hopperSlot: Int,
        val before: ItemStack?,
        val after: ItemStack?,
        val item: ItemStack,
    )

    private var proposedMove: InventoryMoveItemEvent? = null

    @EventHandler(priority = EventPriority.HIGHEST)
    fun search(event: HopperInventorySearchEvent) {
        val backing = event.inventory ?: return
        val block = event.searchBlock
        if (backingBlock(backing) != block) return
        val controller = block.type == Material.BLAST_FURNACE
        val record = if (controller) {
            if (!store.isController(block)) return
            null
        } else {
            store.find(block) ?: return
        }
        event.inventory = null
        if (proposedMove != null) return
        if (controller) {
            if (event.containerType == DESTINATION) pushController(event, backing)
            return
        }

        val drawer = record?.drawer ?: return
        val hopper = readyHopper(event)?.inventory ?: return
        val pushing = event.containerType == DESTINATION
        val transfer = (if (pushing) push(drawer, hopper) else pull(drawer, hopper)) ?: return
        val liveHopper = approveMove(event, hopper, backing, transfer.item) ?: return
        if (store.find(block) !== record || !hopper.canReplace(transfer.hopperSlot, transfer.before, transfer.after)) return

        store.save(block, transfer.next, drawer)
        liveHopper.completeMove(transfer.hopperSlot, transfer.after)
        if (transfer.next !== drawer && store.find(block)?.drawer === transfer.next) {
            visuals.refresh(block, transfer.next, transfer.drawerSlot)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protectPhysicalInventory(event: InventoryMoveItemEvent) {
        if (event === proposedMove) return
        if (isManaged(event.source) || isManaged(event.destination)) event.isCancelled = true
    }

    private fun pushController(event: HopperInventorySearchEvent, backing: Inventory) {
        val block = event.searchBlock
        val hopper = readyHopper(event)?.inventory ?: return
        for ((index, stack) in hopper.storageContents.withIndex()) {
            if (stack == null || stack.isEmpty) continue
            val item = stack.asOne()
            if (!controllers.accepts(block, item)) continue
            val before = stack.clone()
            val after = stack.remainder(1)
            val liveHopper = approveMove(event, hopper, backing, item) ?: return
            if (!store.isController(block) || !hopper.canReplace(index, before, after)) return
            if (controllers.insert(block, item, 1).consumed == 1) liveHopper.completeMove(index, after)
            return
        }
    }

    private fun approveMove(
        event: HopperInventorySearchEvent,
        hopper: Inventory,
        backing: Inventory,
        item: ItemStack,
    ): Hopper? {
        val pushing = event.containerType == DESTINATION
        val move = if (pushing) {
            InventoryMoveItemEvent(hopper, item.clone(), backing, true)
        } else {
            InventoryMoveItemEvent(backing, item.clone(), hopper, false)
        }
        proposedMove = move
        val allowed = try {
            move.callEvent() && move.item == item
        } finally {
            proposedMove = null
        }

        // Other listeners may have changed either endpoint during the move event.
        val liveHopper = readyHopper(event) ?: return null
        if (liveHopper.inventory != hopper) return null
        if (!allowed) {
            liveHopper.transferCooldown = TRANSFER_COOLDOWN
            return null
        }
        val block = event.searchBlock
        if (!block.isLoaded() || (block.getState(false) as? Container)?.inventory != backing) return null
        return liveHopper
    }

    private fun Inventory.canReplace(slot: Int, before: ItemStack?, after: ItemStack?): Boolean =
        getItem(slot) == before && (after == null || after.amount <= minOf(maxStackSize, after.maxStackSize))

    private fun Hopper.completeMove(slot: Int, after: ItemStack?) {
        inventory.setItem(slot, after)
        transferCooldown = TRANSFER_COOLDOWN
        // Paper's hopper slot setter leaves the block entity dirty flag and comparator update to its caller.
        update(false, false)
    }

    private fun push(drawer: Drawer, hopper: Inventory): Transfer? {
        for ((index, item) in hopper.storageContents.withIndex()) {
            if (item == null || item.isEmpty) continue
            val matching = drawer.slots.indices.filter { drawer.slots[it].sample?.item?.isSimilar(item) == true }
            val limit = drawer.limit(item)
            val slot = matching.firstOrNull { drawer.slots[it].count < limit }
                ?: drawer.slots.indexOfFirst { it.sample == null }.takeIf { it >= 0 }
                ?: matching.firstOrNull()
                ?: continue
            val deposit = drawer.insert(slot, item, 1) ?: continue
            return Transfer(deposit.drawer, slot, index, item.clone(), item.remainder(1), item.asOne())
        }
        return null
    }

    private fun pull(drawer: Drawer, hopper: Inventory): Transfer? {
        val contents = hopper.storageContents
        for ((index, slot) in drawer.slots.withIndex()) {
            if (slot.count == 0) continue
            val item = slot.sample?.item ?: continue
            val limit = minOf(hopper.maxStackSize, item.maxStackSize)
            if (limit < 1) continue
            val target = contents.indexOfFirst { it == null || (it.amount < limit && it.isSimilar(item)) }
            if (target == -1) continue
            val existing = contents[target]
            return Transfer(drawer.take(index, 1), index, target, existing?.clone(),
                item.asQuantity((existing?.amount ?: 0) + 1), item.asOne())
        }
        return null
    }

    private fun readyHopper(event: HopperInventorySearchEvent): Hopper? {
        val block = event.block
        if (!block.isLoaded()) return null
        val hopper = block.getState(false) as? Hopper ?: return null
        val data = hopper.blockData as? HopperData ?: return null
        if (!data.isEnabled || hopper.transferCooldown > 0) return null
        val face = if (event.containerType == DESTINATION) data.facing else BlockFace.UP
        return hopper.takeIf { block.getRelative(face) == event.searchBlock }
    }

    private fun isManaged(inventory: Inventory): Boolean {
        val block = backingBlock(inventory) ?: return false
        return if (block.type == Material.BARREL) store.find(block) != null else store.isController(block)
    }

    private fun backingBlock(inventory: Inventory): Block? {
        val material = when (inventory.type) {
            InventoryType.BARREL -> Material.BARREL
            InventoryType.BLAST_FURNACE -> Material.BLAST_FURNACE
            else -> return null
        }
        return inventory.location?.block?.takeIf { it.isLoaded() && it.type == material }
    }

    private fun Block.isLoaded() = world.isChunkLoaded(x shr 4, z shr 4)

    private companion object {
        const val TRANSFER_COOLDOWN = 8
    }
}
