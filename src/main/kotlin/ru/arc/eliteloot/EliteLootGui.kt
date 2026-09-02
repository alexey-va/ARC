package ru.arc.eliteloot

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.util.TextUtil

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

    fun open(player: Player) {
        val cfg = config
        val items = buildItemList()
        val backCommand = cfg.string("navigation.back.command", "menu")
        ArcMenus.open(
            player,
            ArcMenuSchema.ELITE_LOOT,
            TextUtil.mm(cfg.string("title", "Elite Loot"), true),
            elements = mapOf(
                "previous" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.ELITE_LOOT, "previous")) {
                    it.session.previousPage()
                },
                "next" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.ELITE_LOOT, "next")) {
                    it.session.nextPage()
                },
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.ELITE_LOOT, "back")) {
                    it.closeInventory()
                    it.performCommand(backCommand)
                },
            ),
            regions = mapOf(
                ArcMenuSchema.ELITE_LOOT_ITEMS to items.map { ArcMenus.entry(it.stack) },
            ),
        )
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
