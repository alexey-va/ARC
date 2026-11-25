package ru.arc.drawers

import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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

internal class DrawerHoppers(private val store: DrawerStore, private val visuals: DrawerVisuals) : Listener {
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
        val barrel = event.inventory ?: return
        val block = event.searchBlock
        if (barrelBlock(barrel) != block) return
        val record = store.find(block) ?: return
        event.inventory = null
        val drawer = record.drawer ?: return
        if (proposedMove != null) return
        val hopper = readyHopper(event)?.inventory ?: return
        val pushing = event.containerType == DESTINATION
        val transfer = (if (pushing) push(drawer, hopper) else pull(drawer, hopper)) ?: return
        val move = if (pushing) {
            InventoryMoveItemEvent(hopper, transfer.item.clone(), barrel, true)
        } else {
            InventoryMoveItemEvent(barrel, transfer.item.clone(), hopper, false)
        }
        proposedMove = move
        val allowed = try {
            move.callEvent() && move.item == transfer.item
        } finally {
            proposedMove = null
        }

        // Other listeners may have changed either endpoint during the move event.
        val liveHopper = readyHopper(event) ?: return
        if (liveHopper.inventory != hopper) return
        if (!allowed) {
            liveHopper.transferCooldown = TRANSFER_COOLDOWN
            return
        }
        if (!block.isLoaded() || (block.getState(false) as? Barrel)?.inventory != barrel) return
        if (store.find(block) !== record || hopper.getItem(transfer.hopperSlot) != transfer.before) return
        if (transfer.after != null && transfer.after.amount > hopper.maxStackSize) return

        store.save(block, transfer.next, drawer)
        hopper.setItem(transfer.hopperSlot, transfer.after)
        liveHopper.transferCooldown = TRANSFER_COOLDOWN
        // Paper's hopper slot setter leaves the block entity dirty flag and comparator update to its caller.
        liveHopper.update(false, false)
        if (transfer.next !== drawer && store.find(block)?.drawer === transfer.next) {
            visuals.refresh(block, transfer.next, transfer.drawerSlot)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun protectPhysicalInventory(event: InventoryMoveItemEvent) {
        if (event === proposedMove) return
        if (isDrawer(event.source) || isDrawer(event.destination)) event.isCancelled = true
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

    private fun isDrawer(inventory: Inventory): Boolean = barrelBlock(inventory)?.let { store.find(it) != null } == true

    private fun barrelBlock(inventory: Inventory): Block? =
        if (inventory.type == InventoryType.BARREL) {
            inventory.location?.block?.takeIf { it.isLoaded() && it.type == Material.BARREL }
        } else null

    private fun Block.isLoaded() = world.isChunkLoaded(x shr 4, z shr 4)

    private companion object {
        const val TRANSFER_COOLDOWN = 8
    }
}
