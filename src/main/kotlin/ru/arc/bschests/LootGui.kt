package ru.arc.bschests

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuTransferDecision

/** Configured personal-loot screen with guarded, domain-backed item transfers. */
object LootGuiFactory {
    private val config: Config by lazy {
        ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "personalloot.yml")
    }

    fun open(
        player: Player,
        lootData: CustomLootData,
    ) {
        val itemSnapshot = lootData.snapshotItems()
        val rows = calculateRows(itemSnapshot.size)
        val menu = checkNotNull(ArcMenuSchema.PERSONAL_LOOT[rows])
        val entries = itemSnapshot.mapIndexedNotNull { index, itemStack ->
            if (itemStack == null || itemStack.type == Material.AIR) return@mapIndexedNotNull null
            val expected = itemStack.clone()
            ArcMenus.transferEntry(expected.clone()) { click ->
                val currentItem = click.event.currentItem
                if (currentItem == null || currentItem.type == Material.AIR || !currentItem.isSimilar(expected)) {
                    return@transferEntry PaperMenuTransferDecision.DENY
                }
                if (!lootData.removeItem(expected, index)) {
                    return@transferEntry PaperMenuTransferDecision.DENY
                }

                PersonalLootModule.save(lootData)
                currentItem.editMeta { meta ->
                    meta.persistentDataContainer.remove(NamespacedKey(ARC.instance, "if-uuid"))
                }
                PaperMenuTransferDecision.ALLOW
            }
        }

        ArcMenus.open(
            player = player,
            menu = menu,
            title = config.component("gui.title", "<dark_gray>Лут данжа"),
            regions = mapOf(ArcMenuSchema.PERSONAL_LOOT_ITEMS to entries),
        )
    }

    internal fun calculateRows(itemCount: Int): Int =
        maxOf(1, minOf(6, (itemCount + 8) / 9))
}
