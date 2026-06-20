package ru.arc.listeners

import com.jeff_media.customblockdata.CustomBlockData
import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.NBTItem
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Beehive
import org.bukkit.entity.Bee
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionState
import ru.arc.bschests.PersonalLootModule
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.configs.ConfigManager
import ru.arc.farm.FarmManager
import ru.arc.leafdecay.LeafDecayManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BlockListener : Listener {

    companion object {
        private val TREASURE_USE_COOLDOWN: MutableMap<UUID, Long> = ConcurrentHashMap()
        private const val TREASURE_USE_COOLDOWN_MS = 500L
        private val BEE_KEY = NamespacedKey(ARC.instance, "bee")
    }

    private val beeConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        LocationPoolManager.processLocationPool(event)
        processPlaceForLeaves(event)
        processPlaceBees(event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockInteract(event: PlayerInteractEvent) {
        processTreasureHunt(event)
        processBuildingEvent(event)
        processBees(event)
        processTreasureItemUse(event)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onBlockBreakLow(event: BlockBreakEvent) {
        processFarmBreak(event)
        PersonalLootModule.processChestBreak(event)
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockBreakHigh(event: BlockBreakEvent) {
        CustomBlockData(event.block, ARC.instance).clear()
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onChestClick(event: InventoryOpenEvent) {
        PersonalLootModule.processChestOpen(event)
    }

    private fun processTreasureItemUse(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (item.type == Material.AIR) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val playerId = event.player.uniqueId
        val now = System.currentTimeMillis()
        val lastUse = TREASURE_USE_COOLDOWN[playerId]
        if (lastUse != null && now - lastUse < TREASURE_USE_COOLDOWN_MS) {
            event.isCancelled = true
            return
        }

        NBT.get<Unit>(item) { data ->
            if (!data.hasTag("arc:treasure_key")) return@get
            val treasureKey = data.getString("arc:treasure_key")
            val pool = Treasures.getPool(treasureKey)
            if (pool == null) {
                debug("[treasure] pool {} not found for player {}", treasureKey, event.player.name)
                error("Treasure pool {} not found", treasureKey)
                return@get
            }
            if (pool.isEmpty()) {
                debug("[treasure] pool {} is empty for player {}", treasureKey, event.player.name)
                error("Treasure pool {} is empty", treasureKey)
                return@get
            }
            val handItem = event.player.inventory.itemInMainHand
            if (handItem.type == Material.AIR || handItem.amount < 1) return@get

            TREASURE_USE_COOLDOWN[playerId] = now
            event.isCancelled = true

            if (handItem.amount > 1) handItem.amount -= 1
            else event.player.inventory.setItemInMainHand(null)

            val treasure = pool.random()
            if (treasure != null) Treasures.service.give(treasure, event.player)
            event.player.playSound(event.player.location, "ui.loom.take_result", 1f, 1f)
        }
    }

    private fun processPlaceBees(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (block.type != Material.BEEHIVE) return
        CustomBlockData(block, ARC.instance)[BEE_KEY, PersistentDataType.BOOLEAN] = true
    }

    private fun processBees(event: PlayerInteractEvent) {
        if (!event.hasBlock()) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BEE_NEST && block.type != Material.BEEHIVE) return
        if (CustomBlockData.hasCustomBlockData(block, ARC.instance)) return
        if (!beeConfig.bool("bees.enabled", false)) return
        val worlds = beeConfig.stringList("bees.worlds").toHashSet()
        if (!worlds.contains(block.world.name)) return
        val beehive = block.state as? Beehive ?: return
        val amount = beeConfig.integer("bees.amount", 2)
        val spawnLoc = beehive.location.add(0.5, 1.0, 0.5)
        repeat(amount) {
            block.world.spawn(spawnLoc, Bee::class.java) { bee -> beehive.addEntity(bee) }
        }
        beehive.update()
        CustomBlockData(block, ARC.instance)[BEE_KEY, PersistentDataType.BOOLEAN] = true
    }

    private fun processPlaceForLeaves(event: BlockPlaceEvent) {
        if (event.isCancelled) return
        if (event.player.hasPermission("arc.leafdecay.bypass")) return
        LeafDecayManager.markAsPlayerPlaced(event.block)
    }

    @Suppress("DEPRECATION")
    private fun processBuildingEvent(event: PlayerInteractEvent) {
        if (!event.hasItem()) return
        val hand = event.item ?: return
        if (hand.type != Material.BOOK) return
        if (!event.player.hasPermission("arc.buildings.build")) {
            event.player.sendMessage(TextUtil.noPermissions())
            return
        }
        if (!event.hasBlock() || event.action != Action.RIGHT_CLICK_BLOCK) {
            val site = BuildingManager.getPendingConstruction(event.player.uniqueId)
            if (site != null && site.state == ConstructionState.DisplayingOutline) {
                BuildingManager.cancelConstruction(site)
            }
            return
        }
        val clickedBlock = event.clickedBlock ?: return
        val center = clickedBlock.location.add(0.0, 1.0, 0.0)
        val nbtItem = NBTItem(hand)
        val buildingId = nbtItem.getString("arc:building_key")
        if (buildingId.isNullOrEmpty()) return

        val rotation = if (nbtItem.hasKey("arc:rotation")) nbtItem.getString("arc:rotation") else null
        val yOff = if (nbtItem.hasKey("arc:y_offset")) nbtItem.getString("arc:y_offset") else null

        event.isCancelled = true
        BuildingManager.processPlayerClick(event.player, center, buildingId, rotation, yOff)
    }

    private fun processFarmBreak(event: BlockBreakEvent) {
        FarmManager.processEvent(event)
    }

    private fun processTreasureHunt(event: PlayerInteractEvent) {
        var block = event.clickedBlock ?: return
        val blocks = listOf(
            block,
            block.getRelative(1, 0, 0),
            block.getRelative(-1, 0, 0),
            block.getRelative(0, 0, 1),
            block.getRelative(0, 0, -1),
            block.getRelative(0, 1, 0),
            block.getRelative(0, -1, 0),
        )
        var treasureHunt = blocks.firstOrNull { TreasureHuntManager.getByBlock(it) != null }
            ?.let { b -> block = b; TreasureHuntManager.getByBlock(b) }
            ?: return
        info("Player {} found treasure hunt chest at {}", event.player.name, block.location)
        event.isCancelled = true
        TreasureHuntManager.popChest(block, treasureHunt, event.player)
    }
}
