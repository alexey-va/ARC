package ru.arc.eliteloot

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.gui

/**
 * GUI for displaying all elite loot items.
 * Displays decor items from all loot types in a paginated view.
 *
 * Configuration: `guis/eliteloot.yml`
 */
object EliteLootGuiFactory {
    private val config: Config by lazy {
        ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/eliteloot.yml")
    }

    fun create(player: Player): ChestGui {
        val cfg = config
        val items = buildItemList()
        val title = cfg.string("title", "Elite Loot")
        val rows = cfg.integer("rows", 6)

        // Capture nav button config values before entering DSL
        val prevSlot = cfg.integer("navigation.prev.slot", 0)
        val nextSlot = cfg.integer("navigation.next.slot", 8)
        val backSlot = cfg.integer("navigation.back.slot", 4)
        val backCommand = cfg.string("navigation.back.command", "menu")

        return gui(title, rows, player, cfg) {
            navBackground()

            pagination(0 until (rows - 1)) {
                items(items) { itemData ->
                    stack(itemData.stack)
                    onClick { /* Display only, no action */ }
                }
            }

            navBar {
                prevPage(slot = prevSlot, configKey = "navigation.prev")
                nextPage(slot = nextSlot, configKey = "navigation.next")
                back(slot = backSlot, command = backCommand, configKey = "navigation.back")
            }
        }
    }

    private data class DecorItemData(
        val stack: ItemStack,
    )

    private fun buildItemList(): List<DecorItemData> {
        val result = mutableListOf<DecorItemData>()

        val map: Map<LootType, DecorPool> = EliteLootManager.map
        for (entry in map.entries) {
            val lootType = entry.key
            val pool = entry.value
            val decorsMap = pool.decors
            for (decorEntry in decorsMap.entries) {
                val decorItem = decorEntry.value
                result.add(DecorItemData(decorItem.toItemStack(lootType)))
            }
        }

        return result
    }
}
