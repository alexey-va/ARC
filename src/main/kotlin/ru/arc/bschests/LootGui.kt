package ru.arc.bschests

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperCloudStorage
import ru.arc.paper.menu.PaperCloudStorageContent
import ru.arc.paper.menu.PaperCloudStorageFailure
import kotlin.random.Random

/** Read-only cloud chest; backing rewards stay separate from decorative debris. */
object LootGuiFactory {
    private val config: Config
        get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "personalloot.yml")

    fun open(player: Player, lootData: CustomLootData) {
        val count = lootData.snapshotItems().size
        val menu = checkNotNull(ArcMenuSchema.PERSONAL_LOOT[calculateRows(count)])
        val seed = lootData.playerUuid.hashCode() xor lootData.chestUuid.hashCode()
        val order = scatteredSlots(count, seed)
        val debris = listOf("cobweb", "cobblestone", "dirt", "stick").shuffled(Random(seed))
        ArcMenus.openStorage(
            player, menu, ArcMenuSchema.PERSONAL_LOOT_ITEMS,
            object : PaperCloudStorage {
                override fun snapshot(): List<ItemStack?> = lootData.snapshotItems()
                override fun compareAndSet(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean {
                    if (!lootData.compareAndSetItems(expected, replacement)) return false
                    PersonalLootModule.save(lootData)
                    return true
                }
            },
            PaperCloudStorageContent(
                title = config.component("gui.title", "<dark_gray>Лут данжа"),
                allowDeposits = false,
                slotOrder = order,
                decorations = debris.take((order.size - count).coerceAtLeast(0)).mapIndexed { offset, name ->
                    count + offset to ArcMenus.item("personal-loot-$name")
                }.toMap(),
                onFailure = { viewer, reason ->
                    if (reason == PaperCloudStorageFailure.FULL) {
                        viewer.sendMessage(config.component("messages.inventory-full", "<red>В инвентаре нет места. Заберите предмет на курсор обычным кликом."))
                    }
                },
            ),
        )
    }

    internal fun scatteredSlots(itemCount: Int, seed: Int): List<Int> {
        require(itemCount in 0..54) { "Personal loot must fit in a chest inventory" }
        return (0 until calculateRows(itemCount) * 9).shuffled(Random(seed))
    }

    internal fun calculateRows(itemCount: Int): Int = maxOf(3, minOf(6, (itemCount + 8) / 9))
}
