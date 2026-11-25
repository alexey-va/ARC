package ru.arc.drawers

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

/** The native furnace supplies the shape and hitbox. Only marked blocks act as controllers. */
internal class ControllerGameplay(
    private val store: DrawerStore,
    private val items: DrawerItems,
    private val controllers: DrawerControllers,
) : Listener {
    private data class Click(val controller: Block, val tick: Int)
    private val clicks = HashMap<UUID, Click>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun place(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (!event.canBuild() || block.type != Material.BLAST_FURNACE) return
        if (items.identify(event.itemInHand) == DrawerItem.CONTROLLER) {
            controllers.remove(block)
            store.setController(block, true)
        } else {
            // A replacement made outside Bukkit can leave an old, data-free marker at this position.
            forget(block)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BLAST_FURNACE || !store.isController(block)) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if ((block.blockData as Directional).facing != event.blockFace) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val tick = Bukkit.getCurrentTick()
        val previous = clicks.remove(player.uniqueId)
        if (!player.isSneaking && previous?.controller == block && tick - previous.tick in 0..7) {
            report(player, depositInventory(player, block))
            return
        }
        if (!player.isSneaking) clicks[player.uniqueId] = Click(block, tick)
        val held = player.inventory.itemInMainHand
        if (held.isEmpty) {
            val count = controllers.size(block)
            if (count == 0) player.tell("controller_empty")
            else player.tell("controller_status", "count" to Component.text(count))
            return
        }
        val result = controllers.insert(block, held, held.amount)
        if (result.consumed > 0) player.inventory.setItemInMainHand(held.remainder(result.consumed))
        report(player, result)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun open(event: InventoryOpenEvent) {
        val block = event.inventory.location?.block ?: return
        if (block.type == Material.BLAST_FURNACE && store.isController(block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun broken(event: BlockBreakEvent) {
        val block = event.block
        if (!forget(block)) return
        val drops = event.isDropItems && event.player.gameMode != GameMode.CREATIVE
        event.isDropItems = false
        if (drops) block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), items.create(DrawerItem.CONTROLLER))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun exploded(event: EntityExplodeEvent) { event.blockList().forEach(::forget) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun exploded(event: BlockExplodeEvent) { event.blockList().forEach(::forget) }

    @EventHandler
    fun quit(event: PlayerQuitEvent) { clicks.remove(event.player.uniqueId) }

    private fun forget(block: Block): Boolean {
        if (block.type != Material.BLAST_FURNACE || !store.isController(block)) return false
        store.setController(block, false)
        controllers.remove(block)
        return true
    }

    private fun depositInventory(player: Player, controller: Block): ControllerDeposit {
        val inventory = player.inventory
        var consumed = 0
        var discarded = 0
        for (index in inventory.storageContents.indices) {
            val item = inventory.getItem(index) ?: continue
            if (item.isEmpty) continue
            val result = controllers.insert(controller, item, item.amount)
            if (result.consumed == 0) continue
            inventory.setItem(index, item.remainder(result.consumed))
            consumed += result.consumed
            discarded += result.discarded
        }
        return ControllerDeposit(consumed, discarded)
    }

    private fun report(player: Player, result: ControllerDeposit) = when {
        result.consumed == 0 -> player.tell("controller_no_match")
        result.discarded > 0 -> player.tell("controller_discarded",
            "count" to Component.text(result.stored), "discarded" to Component.text(result.discarded))
        else -> player.tell("controller_moved", "count" to Component.text(result.stored))
    }
}
