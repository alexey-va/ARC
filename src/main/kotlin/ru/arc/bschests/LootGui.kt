package ru.arc.bschests

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.misc.VanillaPlayerStorageTransfer
import kotlin.random.Random

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
        val empty = checkNotNull(ArcMenus.background(menu))
        val cobweb = ArcMenus.item("personal-loot-cobweb")
        val entries = scatteredSlots(itemSnapshot.size, lootData.playerUuid.hashCode() xor lootData.chestUuid.hashCode()).map { index ->
            val expected = itemSnapshot.getOrNull(index)
            if (expected == null || expected.type.isAir) {
                ArcMenus.entry(if (index in itemSnapshot.size until itemSnapshot.size + 2) cobweb else empty, enabled = false)
            } else {
                ArcMenus.entryWithContext(expected.clone(), acceptedClicks = setOf(ClickType.LEFT, ClickType.SHIFT_LEFT)) { click ->
                    takeLoot(click.event, lootData, expected, index)
                }
            }
        }

        ArcMenus.open(
            player = player,
            menu = menu,
            title = config.component("gui.title", "<dark_gray>Лут данжа"),
            regions = mapOf(ArcMenuSchema.PERSONAL_LOOT_ITEMS to entries),
        )
    }

    internal fun takeLoot(
        event: InventoryClickEvent,
        lootData: CustomLootData,
        expected: ItemStack,
        index: Int,
    ): Boolean {
        event.isCancelled = true
        val currentItem = event.currentItem?.clone() ?: return false
        // InventoryFramework adds its own UUID to the displayed stack only.
        currentItem.editMeta { meta ->
            meta.persistentDataContainer.remove(NamespacedKey(ARC.instance, "if-uuid"))
        }
        if (currentItem != expected) {
            return false
        }
        if (event.click !in setOf(ClickType.LEFT, ClickType.SHIFT_LEFT)) return false
        val player = event.whoClicked as? Player ?: return false
        val transfer = VanillaPlayerStorageTransfer.planFull(player.inventory.storageContents, currentItem)
            ?: run {
                player.sendMessage(config.component("messages.inventory-full", "<red>Освободите место в инвентаре для всей стопки."))
                return false
            }
        if (!lootData.removeItem(expected, index)) return false
        PersonalLootModule.save(lootData)
        // Commit both inventories while the native move stays cancelled.
        transfer.updates.forEach { update -> player.inventory.setItem(update.slot, update.item) }
        event.currentItem = null
        return true
    }

    internal fun scatteredSlots(itemCount: Int, seed: Int): List<Int> {
        require(itemCount in 0..54) { "Personal loot must fit in a chest inventory" }
        return (0 until calculateRows(itemCount) * 9).shuffled(Random(seed))
    }

    internal fun calculateRows(itemCount: Int): Int =
        maxOf(3, minOf(6, (itemCount + 8) / 9))
}
