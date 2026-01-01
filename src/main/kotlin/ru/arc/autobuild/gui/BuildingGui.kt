package ru.arc.autobuild.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.autobuild.BuildConfig
import ru.arc.autobuild.BuildingManager
import ru.arc.autobuild.ConstructionSite
import ru.arc.autobuild.ConstructionState
import ru.arc.gui.GuiItems
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

/**
 * GUI shown during active construction.
 * Shows progress and allows cancellation.
 */
class BuildingGui(
    private val player: Player,
    private val site: ConstructionSite
) : ChestGui(3, TextHolder.deserialize("&8Потверждение строительства"), ARC.plugin) {

    private var youSure = false
    private var renameTask: BukkitTask? = null
    private var progressTask: BukkitTask? = null

    init {
        setupBackground()
        setupButtons()

        // Cancel progress update task when GUI is closed
        setOnClose { progressTask?.cancel() }
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

        // Progress indicator
        val progressStack = ItemStack(BuildConfig.BuildingGui.confirmMaterial).apply {
            editMeta { meta ->
                meta.displayName(TextUtil.strip(Component.text("Прогресс строительства", NamedTextColor.GOLD)))
                val percentage = (site.progress * 100).toInt()
                meta.lore(listOf(TextUtil.strip(Component.text("> $percentage%", NamedTextColor.GRAY))))
            }
        }

        // Auto-update progress
        progressTask = object : BukkitRunnable() {
            override fun run() {
                if (site.state != ConstructionState.Building) {
                    cancel()
                    viewers.forEach(HumanEntity::closeInventory)
                    return
                }
                progressStack.editMeta { meta ->
                    val percentage = (site.progress * 100).toInt()
                    meta.lore(listOf(TextUtil.strip(Component.text("> $percentage%", NamedTextColor.GRAY))))
                }
                update()
            }
        }.runTaskTimer(ARC.plugin, 20L, 60L)

        pane.addItem(GuiItems.create(progressStack) { it.isCancelled = true }, 2, 0)

        // Cancel button with confirmation
        val cancelStack = ItemStack(BuildConfig.BuildingGui.cancelMaterial).apply {
            editMeta { meta ->
                meta.displayName(BuildConfig.Messages.cancelBuildButton())
                val modelData = BuildConfig.BuildingGui.cancelModelData
                if (modelData != 0) meta.setCustomModelData(modelData)
            }
        }

        pane.addItem(GuiItems.create(cancelStack) { event ->
            event.isCancelled = true

            if (youSure) {
                renameTask?.takeIf { !it.isCancelled }?.cancel()
                BuildingManager.cancelConstruction(site)
                event.whoClicked.closeInventory()
            } else {
                youSure = true
                cancelStack.editMeta { meta ->
                    meta.displayName(BuildConfig.Messages.cancelBuildButton())
                    meta.lore(BuildConfig.Messages.cancelLore())
                }

                renameTask = object : BukkitRunnable() {
                    override fun run() {
                        cancelStack.editMeta { meta ->
                            meta.displayName(BuildConfig.Messages.cancelBuildButton())
                        }
                        youSure = false
                        update()
                    }
                }.runTaskLater(ARC.plugin, 5 * 20L)

                update()
            }
        }, 6, 0)

        // Fast finish (admin only)
        if (player.hasPermission("arc.build.fast")) {
            val fastStack = ItemStack(BuildConfig.BuildingGui.fastFinishMaterial)
            pane.addItem(GuiItems.create(fastStack) { event ->
                event.isCancelled = true
                site.finishInstantly()
                event.whoClicked.closeInventory()
            }, 4, 0)
        }

        addPane(pane)
    }
}

