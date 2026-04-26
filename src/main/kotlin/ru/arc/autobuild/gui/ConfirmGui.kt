package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildConfig
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.gui.gui
import ru.arc.util.TextUtil

/**
 * Confirmation GUI shown when player clicks on construction NPC.
 * Allows confirming or cancelling the construction.
 */
object ConfirmGuiFactory {
    fun create(
        player: Player,
        site: ConstructionSite,
    ): ChestGui =
        gui(TextUtil.toLegacy(BuildConfig.ConfirmGui.title), 3, player) {
            background()

            staticPane(0, 1, 9, 1) {
                // Confirm button
                item(2, 0) {
                    material(BuildConfig.ConfirmGui.confirmMaterial)
                    display(BuildConfig.Messages.confirmButton())
                    modelData(BuildConfig.ConfirmGui.confirmModelData)

                    onClick { event ->
                        event.isCancelled = true
                        if (removeBook(player, site)) {
                            BuildingManager.confirmConstruction(player, true)
                        } else {
                            player.sendMessage(BuildConfig.Messages.noBook())
                        }
                        event.whoClicked.closeInventory()
                    }
                }

                // Cancel button
                item(6, 0) {
                    material(BuildConfig.ConfirmGui.cancelMaterial)
                    display(BuildConfig.Messages.cancelButton())
                    modelData(BuildConfig.ConfirmGui.cancelModelData)

                    onClick { event ->
                        event.isCancelled = true
                        BuildingManager.confirmConstruction(player, false)
                        event.whoClicked.closeInventory()
                    }
                }
            }
        }

    /**
     * Removes the building book from player's inventory.
     * @return true if book was found and removed
     */
    private fun removeBook(
        player: Player,
        site: ConstructionSite,
    ): Boolean {
        val inventory = player.inventory
        for (i in 0 until inventory.size) {
            val stack = inventory.getItem(i) ?: continue
            if (stack.type != Material.BOOK) continue

            val buildingName = NBT.get<String>(stack) {
                it.getString("arc:building_key")
            }
            if (buildingName == site.building.fileName) {
                if (stack.amount == 1) {
                    inventory.setItem(i, null)
                } else {
                    stack.amount -= 1
                }
                return true
            }
        }
        return false
    }
}

