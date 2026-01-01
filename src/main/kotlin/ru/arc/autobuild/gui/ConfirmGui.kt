package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import de.tr7zw.changeme.nbtapi.NBT
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.autobuild.BuildConfig
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.gui.GuiItems
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

/**
 * Confirmation GUI shown when player clicks on construction NPC.
 * Allows confirming or cancelling the construction.
 */
class ConfirmGui(
    private val player: Player,
    private val site: ConstructionSite
) : ChestGui(3, "", ARC.plugin) {

    init {
        setTitle(TextHolder.deserialize(TextUtil.toLegacy(BuildConfig.ConfirmGui.title)))
        setupBackground()
        setupButtons()
    }

    private fun setupBackground() {
        val pane = OutlinePane(0, 0, 9, 3).apply {
            addItem(GuiUtils.background())
            setRepeat(true)
            priority = Pane.Priority.LOWEST
        }
        addPane(pane)
    }

    private fun setupButtons() {
        val pane = StaticPane(0, 1, 9, 1)

        // Confirm button
        val confirmStack = ItemStack(BuildConfig.ConfirmGui.confirmMaterial).apply {
            editMeta { meta ->
                meta.displayName(BuildConfig.Messages.confirmButton())
                val modelData = BuildConfig.ConfirmGui.confirmModelData
                if (modelData != 0) meta.setCustomModelData(modelData)
            }
        }

        pane.addItem(GuiItems.create(confirmStack) { event ->
            event.isCancelled = true
            if (removeBook()) {
                BuildingManager.confirmConstruction(player, true)
            } else {
                player.sendMessage(BuildConfig.Messages.noBook())
            }
            event.whoClicked.closeInventory()
        }, 2, 0)

        // Cancel button
        val cancelStack = ItemStack(BuildConfig.ConfirmGui.cancelMaterial).apply {
            editMeta { meta ->
                meta.displayName(BuildConfig.Messages.cancelButton())
                val modelData = BuildConfig.ConfirmGui.cancelModelData
                if (modelData != 0) meta.setCustomModelData(modelData)
            }
        }

        pane.addItem(GuiItems.create(cancelStack) { event ->
            event.isCancelled = true
            BuildingManager.confirmConstruction(player, false)
            event.whoClicked.closeInventory()
        }, 6, 0)

        addPane(pane)
    }

    /**
     * Removes the building book from player's inventory.
     * @return true if book was found and removed
     */
    private fun removeBook(): Boolean {
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

