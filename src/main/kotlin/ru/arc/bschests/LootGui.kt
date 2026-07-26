package ru.arc.bschests

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.gui
import ru.arc.gui.onBottomClick
import ru.arc.gui.onTopClick
import ru.arc.gui.onTopDrag

/**
 * Factory for creating personal loot GUI.
 * Players can click items to take them.
 *
 * Configuration: `personalloot.yml` (gui section)
 */
object LootGuiFactory {
    private val config: Config by lazy {
        ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "personalloot.yml")
    }

    fun create(
        player: Player,
        lootData: CustomLootData,
    ): ChestGui {
        val itemSnapshot = lootData.snapshotItems()
        val rows = calculateRows(itemSnapshot.size)
        val title = config.string("gui.title", "Лут данжа")

        return gui(title, rows, player, config) {
            // Display loot items
            staticPane(0, 0, 9, rows) {
                itemSnapshot.forEachIndexed { index, itemStack ->
                    if (itemStack == null) return@forEachIndexed

                    item(index % 9, index / 9) {
                        stack(itemStack.clone())
                        allowClick() // Allow taking items

                        onClick { click ->
                            val currentItem = click.currentItem ?: return@onClick
                            if (currentItem.type == Material.AIR) return@onClick

                            // Remove item from loot data
                            if (!lootData.removeItem(itemStack, index)) {
                                click.isCancelled = true
                                return@onClick
                            }
                            PersonalLootModule.save(lootData)

                            // Remove IF metadata from item
                            currentItem.editMeta { meta ->
                                meta.persistentDataContainer.remove(NamespacedKey(ARC.instance, "if-uuid"))
                            }
                        }
                    }
                }
            }

            // Prevent placing items into GUI
            onTopClick { click ->
                when (click.action) {
                    InventoryAction.PLACE_ONE,
                    InventoryAction.PLACE_SOME,
                    InventoryAction.PLACE_ALL,
                    InventoryAction.SWAP_WITH_CURSOR,
                    -> {
                        click.isCancelled = true
                    }

                    else -> {}
                }
            }

            onTopDrag { it.isCancelled = true }

            // Prevent shift-clicking from bottom inventory
            onBottomClick { click ->
                if (click.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                    click.isCancelled = true
                }
            }
        }
    }

    private fun calculateRows(itemCount: Int): Int {
        return maxOf(1, minOf(6, (itemCount + 8) / 9))
    }
}
