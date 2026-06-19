package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
import ru.arc.util.itemStack
import ru.arc.util.toGuiItem

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

        // Progress indicator
        val percentage = (site.progress * 100).toInt()
        val progressStack =
            itemStack(BuildConfig.BuildingGui.confirmMaterial) {
                display("<gold>Прогресс строительства")
                lore("<gray>> $percentage%")
            }

        // Auto-update progress using Task DSL
        progressTask =
            repeating(period = 60.ticks, delay = 20.ticks) {
                if (site.state != ConstructionState.Building) {
                    cancel()
                    viewers.forEach(HumanEntity::closeInventory)
                    return@repeating
                }
                progressStack.editMeta { meta ->
                    val newPercentage = (site.progress * 100).toInt()
                    meta.lore(listOf(TextUtil.strip(Component.text("> $newPercentage%", NamedTextColor.GRAY))))
                }
                update()
            }

        pane.addItem(progressStack.toGuiItem(), 2, 0)

        // Cancel button with confirmation
        val cancelStack =
            itemStack(BuildConfig.BuildingGui.cancelMaterial) {
                display(BuildConfig.Messages.cancelBuildButton())
                if (BuildConfig.BuildingGui.cancelModelData != 0) {
                    modelData(BuildConfig.BuildingGui.cancelModelData)
                }
            }

        pane.addItem(
            cancelStack.toGuiItem { event ->
                event.isCancelled = true
                if (youSure) {
                    renameTask?.takeIf { !it.isCancelled }?.cancel()
                    BuildingManager.cancelConstruction(site)
                    event.whoClicked.closeInventory()
                } else {
                    youSure = true
                    player.sendMessage(BuildConfig.Messages.cancelConfirmHint())
                    cancelStack.editMeta { meta ->
                        meta.displayName(BuildConfig.Messages.cancelConfirmButton())
                        meta.lore(BuildConfig.Messages.cancelLore())
                    }

                    renameTask =
                        delayed((5 * 20).ticks) {
                            youSure = false
                            cancelStack.editMeta { meta ->
                                meta.displayName(BuildConfig.Messages.cancelBuildButton())
                                meta.lore(null)
                            }
                            update()
                        }

                    update()
                }
            },
            6,
            0,
        )

        // Fast finish (admin only)
        if (player.hasPermission("arc.build.fast")) {
            val fastStack =
                itemStack(BuildConfig.BuildingGui.fastFinishMaterial) {
                    display("<green>Мгновенно завершить")
                }
            pane.addItem(
                fastStack.toGuiItem { event ->
                    site.finishInstantly()
                    event.whoClicked.closeInventory()
                },
                4,
                0,
            )
        }

        addPane(Slot.fromXY(0, 1), pane)
    }
}

