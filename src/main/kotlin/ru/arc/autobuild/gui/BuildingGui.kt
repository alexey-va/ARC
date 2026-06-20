package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.autobuild.BuildConfig
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionState
import ru.arc.core.ScheduledTask
import ru.arc.core.delayed
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.itemComponents
import ru.arc.util.itemLore

/**
 * GUI shown during active construction.
 * Shows progress and allows cancellation.
 */
class BuildingGui(
    private val player: Player,
    private val site: ConstructionSite
) : ChestGui(3, TextHolder.deserialize("&8Потверждение строительства"), ARC.instance) {
    private var youSure = false
    private var renameTask: ScheduledTask? = null
    private var progressTask: ScheduledTask? = null

    init {
        setupBackground()
        setupButtons()

        // Cancel progress update task when GUI is closed
        setOnClose { progressTask?.cancel() }
    }

    private fun setupBackground() {
        val pane = OutlinePane(9, 3, Pane.Priority.LOWEST).apply {
            addItem(GuiUtils.background())
            setRepeat(true)
        }
        addPane(Slot.fromXY(0, 0), pane)
    }

    private fun setupButtons() {
        val pane = StaticPane(9, 1)
        val buildConfig = BuildConfig.config()

        // Progress indicator
        val percentage = (site.progress * 100).toInt()
        val progressItem =
            guiItem(Material.PAPER) {
                lore("<gray>> $percentage%")
                fromConfig(buildConfig, "building-gui.progress")
            }

        // Auto-update progress using Task DSL
        progressTask =
            repeating(period = 60.ticks, delay = 20.ticks) {
                if (site.state != ConstructionState.Building) {
                    cancel()
                    viewers.forEach(HumanEntity::closeInventory)
                    return@repeating
                }
                progressItem.item.editMeta { meta ->
                    val newPercentage = (site.progress * 100).toInt()
                    meta.lore(listOf(TextUtil.strip(Component.text("> $newPercentage%", NamedTextColor.GRAY))))
                }
                update()
            }

        pane.addItem(progressItem, 2, 0)

        // Cancel button with confirmation
        lateinit var cancelItem: GuiItem
        cancelItem =
            guiItem(Material.RED_STAINED_GLASS_PANE) {
                fromConfig(buildConfig, "building-gui.cancel")
                onClick { event ->
                    event.isCancelled = true
                    if (youSure) {
                        renameTask?.takeIf { !it.isCancelled }?.cancel()
                        BuildingManager.cancelConstruction(site)
                        event.whoClicked.closeInventory()
                    } else {
                        youSure = true
                        player.sendMessage(BuildConfig.Messages.cancelConfirmHint())
                        val (confirmDisplay, _) = buildConfig.itemComponents("building-gui.cancel-confirm")
                        val cancelLore =
                            buildConfig.itemLore("building-gui.cancel").map {
                                TextUtil.strip(TextUtil.mm(it))
                            }
                        cancelItem.item.editMeta { meta ->
                            confirmDisplay?.let { meta.displayName(TextUtil.strip(it)) }
                            meta.lore(cancelLore.filterNotNull())
                        }

                        renameTask =
                            delayed((5 * 20).ticks) {
                                youSure = false
                                val (normalDisplay, _) = buildConfig.itemComponents("building-gui.cancel")
                                cancelItem.item.editMeta { meta ->
                                    normalDisplay?.let { meta.displayName(TextUtil.strip(it)) }
                                    meta.lore(null)
                                }
                                update()
                            }

                        update()
                    }
                }
            }

        pane.addItem(cancelItem, 6, 0)

        // Fast finish (admin only)
        if (player.hasPermission("arc.build.fast")) {
            pane.addItem(
                guiItem(Material.BLAZE_POWDER) {
                    fromConfig(buildConfig, "building-gui.fast-finish")
                    onClick { event ->
                        site.finishInstantly()
                        event.whoClicked.closeInventory()
                    }
                },
                4,
                0,
            )
        }

        addPane(Slot.fromXY(0, 1), pane)
    }
}
