package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.autobuild.BuildConfig
import ru.arc.autobuild.BuildBookCodec
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.buildertools.BuilderToolsModule
import ru.arc.gui.gui
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig

/**
 * Confirmation GUI shown when player clicks on construction NPC.
 * Allows confirming or cancelling the construction.
 */
object ConfirmGuiFactory {
    fun create(
        player: Player,
        site: ConstructionSite,
    ): ChestGui {
        val buildConfig = BuildConfig.config()
        return gui(TextUtil.toLegacy(BuildConfig.ConfirmGui.title), 3, player) {
            background()

            staticPane(0, 1, 9, 1) {
                item(2, 0) {
                    fromConfig(buildConfig, "confirm-gui.confirm")

                    onClick { event ->
                        event.isCancelled = true
                        val found = findBook(player, site)
                        if (found == null) {
                            player.sendMessage(BuildConfig.Messages.noBook())
                        } else if (site.bookData?.playerCreated == true) {
                            BuilderToolsModule.startPlayerBuildBook(
                                player,
                                site,
                                found.second.clone().also { it.amount = 1 },
                            )
                        } else {
                            removeOne(player, found.first, found.second)
                            BuildingManager.confirmConstruction(player, true)
                        }
                        event.whoClicked.closeInventory()
                    }
                }

                item(6, 0) {
                    fromConfig(buildConfig, "confirm-gui.cancel")

                    onClick { event ->
                        event.isCancelled = true
                        BuildingManager.confirmConstruction(player, false)
                        event.whoClicked.closeInventory()
                    }
                }
            }
        }
    }

    /**
     * Removes the building book from player's inventory.
     * @return true if book was found and removed
     */
    private fun findBook(
        player: Player,
        site: ConstructionSite,
    ): Pair<Int, org.bukkit.inventory.ItemStack>? {
        val expected = site.bookData ?: return null
        val inventory = player.inventory
        for (i in 0 until inventory.size) {
            val stack = inventory.getItem(i) ?: continue
            if (stack.type != Material.BOOK) continue
            if (BuildBookCodec.matches(stack, expected)) return i to stack
        }
        return null
    }

    private fun removeOne(player: Player, slot: Int, stack: org.bukkit.inventory.ItemStack) {
        if (stack.amount == 1) player.inventory.setItem(slot, null) else stack.amount -= 1
    }
}
