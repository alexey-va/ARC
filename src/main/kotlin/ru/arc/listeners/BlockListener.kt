package ru.arc.listeners

import com.jeff_media.customblockdata.CustomBlockData
import de.tr7zw.changeme.nbtapi.NBT
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
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.gui.BuildBookEditorGui
import ru.arc.bschests.PersonalLootModule
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.ConfigManager
import ru.arc.leafdecay.LeafDecayManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasure.pouch.Pouches
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BlockListener : Listener {
    companion object {
        private val TREASURE_USE_COOLDOWN: MutableMap<UUID, Long> = ConcurrentHashMap()
        private const val TREASURE_USE_COOLDOWN_MS = 500L
        private val BEE_KEY = NamespacedKey(ARC.instance, "bee")
    }

    private val beeConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "modules/misc.yml")

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        LocationPoolManager.processLocationPool(event)
        processPlaceForLeaves(event)
        processPlaceBees(event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (processBuildingEvent(event)) return
        processTreasureHunt(event)
        processBees(event)
        processTreasureItemUse(event)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onBlockBreakLow(event: BlockBreakEvent) {
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
            if (data.hasTag("arc:pouch_id")) {
                val pouchId = data.getString("arc:pouch_id")
                event.isCancelled = true
                val result = Pouches.open(pouchId, event.player)
                if (result.shouldConsume) {
                    consumeOneFromMainHand(event.player)
                    TREASURE_USE_COOLDOWN[playerId] = now
                } else {
                    debug("[pouch] {} could not be opened for player {}: {}", pouchId, event.player.name, result.failures)
                    event.player.sendMessage(TextUtil.mm("<red>Мешочек сейчас не открывается. Сообщите администрации."))
                }
                return@get
            }

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

            event.isCancelled = true
            val treasure = pool.random()
            if (treasure != null && Treasures.service.give(treasure, event.player).isSuccess) {
                consumeOneFromMainHand(event.player)
                TREASURE_USE_COOLDOWN[playerId] = now
                event.player.playSound(event.player.location, "ui.loom.take_result", 1f, 1f)
            }
        }
    }

    private fun consumeOneFromMainHand(player: org.bukkit.entity.Player) {
        val handItem = player.inventory.itemInMainHand
        if (handItem.type == Material.AIR || handItem.amount < 1) return
        if (handItem.amount > 1) handItem.amount -= 1 else player.inventory.setItemInMainHand(null)
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
        if (event.player.hasPermission("arc.leaf.decay.bypass")) return
        LeafDecayManager.markAsPlayerPlaced(event.block)
    }

    @Suppress("DEPRECATION")
    private fun processBuildingEvent(event: PlayerInteractEvent): Boolean {
        if (!event.hasItem()) return false
        val hand = event.item ?: return false
        if (hand.type != Material.BOOK) return false
        val book = BuildBookCodec.read(hand) ?: return false
        if (event.hand != EquipmentSlot.HAND) return false
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return false

        if (event.action == Action.RIGHT_CLICK_AIR || event.player.isSneaking) {
            event.isCancelled = true
            BuildBookEditorGui.open(event.player)
            return true
        }
        if (!event.player.hasPermission("arc.build.book.use")) {
            event.isCancelled = true
            event.player.sendMessage(TextUtil.noPermissions())
            return true
        }
        val clickedBlock = event.clickedBlock ?: return false
        val center = clickedBlock.location.add(0.0, 1.0, 0.0)

        event.isCancelled = true
        BuildingManager.processPlayerClick(event.player, center, book)
        return true
    }

    private fun processTreasureHunt(event: PlayerInteractEvent) {
        if (!TreasureHuntManager.hasActiveHunts()) return
        var block = event.clickedBlock ?: return
        val blocks =
            listOf(
                block,
                block.getRelative(1, 0, 0),
                block.getRelative(-1, 0, 0),
                block.getRelative(0, 0, 1),
                block.getRelative(0, 0, -1),
                block.getRelative(0, 1, 0),
                block.getRelative(0, -1, 0),
            )
        var treasureHunt =
            blocks
                .firstOrNull { TreasureHuntManager.getByBlock(it) != null }
                ?.let { b ->
                    block = b
                    TreasureHuntManager.getByBlock(b)
                }
                ?: return
        debug("Player {} found treasure hunt chest at {}", event.player.name, block.location)
        event.isCancelled = true
        TreasureHuntManager.popChest(block, treasureHunt, event.player)
    }
}
