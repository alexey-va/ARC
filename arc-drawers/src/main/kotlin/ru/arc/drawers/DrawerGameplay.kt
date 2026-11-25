package ru.arc.drawers

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Barrel
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

internal class DrawerGameplay(
    private val store: DrawerStore,
    private val items: DrawerItems,
    private val visuals: DrawerVisuals,
) : Listener {
    private data class Click(val block: Block, val slot: Int, val tick: Int)
    private val clicks = HashMap<UUID, Click>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun checkPlace(event: BlockPlaceEvent) {
        if (items.identify(event.itemInHand)?.layout != null && store.find(event.blockPlaced) != null) {
            event.isCancelled = true
            event.player.tell("damaged")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun place(event: BlockPlaceEvent) {
        if (!event.canBuild()) return
        val layout = items.identify(event.itemInHand)?.layout ?: return
        val block = event.blockPlaced
        val data = block.blockData as? Barrel ?: return
        data.facing = event.player.facing.oppositeFace
        block.setBlockData(data, false)
        val drawer = Drawer(layout)
        store.save(block, drawer)
        visuals.refresh(block, drawer)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (block.type != Material.BARREL || event.useInteractedBlock() == Event.Result.DENY) return
        val record = store.find(block) ?: return
        val front = (block.blockData as Barrel).facing
        val player = event.player
        val isFront = event.blockFace == front
        if (event.action == Action.LEFT_CLICK_BLOCK) {
            if (!isFront || (player.isSneaking && player.inventory.itemInMainHand.type.name.endsWith("_AXE"))) return
            event.isCancelled = true
            if (event.hand != EquipmentSlot.HAND) return
            val drawer = record.drawer ?: return player.tell("damaged")
            val hit = block.rayTrace(player.eyeLocation, player.eyeLocation.direction, 8.0, FluidCollisionMode.NEVER) ?: return
            if (hit.hitBlockFace == front) take(player, block, drawer, slotAt(block, front, hit.hitPosition, drawer.layout))
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        // Other faces also accept normal block placement against the barrel.
        event.setUseInteractedBlock(Event.Result.DENY)
        val service = player.isSneaking && player.inventory.itemInMainHand.isEmpty
        if (!isFront && !service) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.hand != EquipmentSlot.HAND) return
        val drawer = record.drawer ?: return player.tell("damaged")
        if (!isFront) return removeUpgrades(player, block, drawer)
        val hit = event.interactionPoint?.toVector() ?: return
        val index = slotAt(block, front, hit, drawer.layout)
        val held = player.inventory.itemInMainHand
        when (val tool = items.identify(held)) {
            DrawerItem.LOCK -> {
                val next = drawer.toggleLock()
                change(block, drawer, next)
                player.tell(if (next.locked) "locked" else "unlocked")
            }
            DrawerItem.COUNTS -> {
                val next = drawer.copy(showCounts = !drawer.showCounts)
                change(block, drawer, next)
                player.tell(if (next.showCounts) "counts_on" else "counts_off")
            }
            DrawerItem.DOUBLE, DrawerItem.QUADRUPLE, DrawerItem.OCTUPLE, DrawerItem.VOID ->
                upgrade(player, block, drawer, held, tool)
            else -> depositOrInspect(player, block, drawer, index, held)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun open(event: InventoryOpenEvent) {
        val block = event.inventory.location?.block ?: return
        if (block.type == Material.BARREL && store.find(block) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun checkBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.BARREL) return
        val record = store.find(event.block) ?: return
        if (record.drawer == null || record.drawer.slots.any { it.count != 0 }) {
            event.isCancelled = true
            event.player.tell(if (record.drawer == null) "damaged" else "empty_first")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun broken(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.BARREL) return
        val drawer = store.find(block)?.drawer ?: return
        if (drawer.slots.any { it.count != 0 }) return
        val drops = event.isDropItems && event.player.gameMode != GameMode.CREATIVE
        event.isDropItems = false
        store.remove(block)
        visuals.forget(block)
        if (drops) {
            val location = block.location.add(0.5, 0.5, 0.5)
            val returned = listOf(items.create(DrawerItem.forLayout(drawer.layout))) + installedUpgrades(drawer)
            returned.forEach { block.world.dropItemNaturally(location, it) }
        }
    }

    // Keep full drawers intact under vanilla damage without spilling thousands of item entities.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun explosion(event: EntityExplodeEvent) { event.blockList().removeIf(::isDrawer) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun explosion(event: BlockExplodeEvent) { event.blockList().removeIf(::isDrawer) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun burn(event: BlockBurnEvent) {
        if (isDrawer(event.block)) event.isCancelled = true
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) { clicks.remove(event.player.uniqueId) }

    private fun isDrawer(block: Block): Boolean = block.type == Material.BARREL && store.find(block) != null

    private fun take(player: Player, block: Block, drawer: Drawer, index: Int) {
        clicks.remove(player.uniqueId)
        val slot = drawer.slots[index]
        val sample = slot.sample ?: return
        if (slot.count == 0) return
        val wanted = if (player.isSneaking) minOf(slot.count, sample.item.maxStackSize) else 1
        val remaining = player.inventory.addItem(sample.item.asQuantity(wanted)).values.sumOf(ItemStack::getAmount)
        val moved = wanted - remaining
        if (moved == 0) return player.tell("no_space")
        change(block, drawer, drawer.take(index, moved), index)
    }

    private fun depositOrInspect(player: Player, block: Block, drawer: Drawer, index: Int, held: ItemStack) {
        val slot = drawer.slots[index]
        val tick = Bukkit.getCurrentTick()
        val previous = clicks.remove(player.uniqueId)
        val sample = slot.sample
        val double = !player.isSneaking && previous?.block == block && previous.slot == index && tick - previous.tick in 0..7
        if (double && sample != null && (held.isEmpty || held.isSimilar(sample.item))) {
            depositInventory(player, block, drawer, index, sample)
            return
        }
        if (held.isEmpty) {
            if (sample != null && !player.isSneaking) clicks[player.uniqueId] = Click(block, index, tick)
            return inspect(player, drawer, index)
        }
        val result = drawer.insert(index, held, held.amount)
        if (result == null) {
            player.tell(if (sample != null && !sample.item.isSimilar(held)) "different" else "full")
            return
        }
        change(block, drawer, result.drawer, index)
        debitHand(player, held, result.consumed)
        clicks[player.uniqueId] = Click(block, index, tick)
        reportDiscard(player, result)
    }

    private fun depositInventory(player: Player, block: Block, drawer: Drawer, index: Int, sample: DrawerSample) {
        val contents = player.inventory.storageContents
        val matched = contents.indices.filter { contents[it]?.isSimilar(sample.item) == true }
        val available = matched.sumOf { contents[it]!!.amount }
        if (available == 0) return
        val result = drawer.insert(index, sample.item, available) ?: return player.tell("full")
        change(block, drawer, result.drawer, index)
        var remaining = result.consumed
        for (inventorySlot in matched) {
            if (remaining == 0) break
            val item = contents[inventorySlot]!!
            val consumed = minOf(remaining, item.amount)
            player.inventory.setItem(inventorySlot, item.remainder(consumed))
            remaining -= consumed
        }
        reportDiscard(player, result)
    }

    private fun upgrade(player: Player, block: Block, drawer: Drawer, held: ItemStack, tool: DrawerItem) {
        val target = tool.capacity
        if (target == null) {
            if (drawer.voidOverflow) return player.tell("void_present")
            change(block, drawer, drawer.copy(voidOverflow = true))
            debitHand(player, held, 1)
            player.tell("void_on")
            return
        }
        if (target <= drawer.capacity) return player.tell("higher")
        change(block, drawer, drawer.copy(capacity = target))
        debitHand(player, held, 1)
        if (drawer.capacity != Capacity.BASE) giveBack(player, items.create(DrawerItem.forCapacity(drawer.capacity)))
        player.tell("expanded", "stacks" to Component.text(drawer.layout.stacksPerSlot * target.multiplier))
    }

    private fun removeUpgrades(player: Player, block: Block, drawer: Drawer) {
        val upgrades = installedUpgrades(drawer)
        if (upgrades.isEmpty()) return
        if (!drawer.canUse(Capacity.BASE)) {
            // Always permit switching off voiding even while the capacity upgrade is needed.
            if (drawer.voidOverflow) {
                change(block, drawer, drawer.copy(voidOverflow = false))
                giveBack(player, items.create(DrawerItem.VOID))
            }
            return player.tell("too_many")
        }
        change(block, drawer, drawer.copy(capacity = Capacity.BASE, voidOverflow = false))
        upgrades.forEach { giveBack(player, it) }
        player.tell("removed")
    }

    private fun installedUpgrades(drawer: Drawer): List<ItemStack> = buildList {
        if (drawer.capacity != Capacity.BASE) add(items.create(DrawerItem.forCapacity(drawer.capacity)))
        if (drawer.voidOverflow) add(items.create(DrawerItem.VOID))
    }

    private fun inspect(player: Player, drawer: Drawer, index: Int) {
        val slot = drawer.slots[index]
        val item = slot.sample?.item
        val flags = Component.empty()
            .append(if (drawer.locked) DrawerText.message("flag_locked") else Component.empty())
            .append(if (drawer.voidOverflow) DrawerText.message("flag_void") else Component.empty())
        player.tell("status",
            "name" to (item?.effectiveName() ?: DrawerText.message("empty")),
            "count" to Component.text(slot.count),
            "limit" to Component.text(drawer.limit(item ?: ItemStack.of(Material.COBBLESTONE))),
            "flags" to flags,
        )
    }

    private fun change(block: Block, previous: Drawer, next: Drawer, index: Int? = null) {
        if (previous === next) return
        store.save(block, next, previous)
        if (index == null) visuals.refresh(block, next) else visuals.refresh(block, next, index)
    }

    private fun reportDiscard(player: Player, result: Drawer.Deposit) {
        if (result.discarded > 0) player.tell("discarded", "count" to Component.text(result.discarded))
    }

    private fun debitHand(player: Player, held: ItemStack, amount: Int) {
        player.inventory.setItemInMainHand(held.remainder(amount))
    }
}

internal fun ItemStack.remainder(consumed: Int): ItemStack? =
    if (amount == consumed) null else asQuantity(amount - consumed)

internal fun giveBack(player: Player, item: ItemStack) {
    player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
}

internal fun Player.tell(key: String, vararg values: Pair<String, Component>) {
    sendActionBar(DrawerText.message(key, *values))
}
