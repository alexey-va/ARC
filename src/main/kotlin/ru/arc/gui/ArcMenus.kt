package ru.arc.gui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuSession
import ru.arc.paper.menu.PaperMenuTransferDecision
import ru.arc.paper.menu.PaperMenuTransferHandler
import java.nio.file.Path

/**
 * ARC's single configured-menu runtime.
 *
 * Feature code supplies domain values and typed actions. The validated YAML
 * generation owns topology and safe item presentation. Reload swaps the whole
 * generation and closes stale viewers before any new click can be dispatched.
 */
object ArcMenus {
    private val itemFactory = PaperMenuItemFactory()
    private lateinit var dataRoot: Path
    private var runtime: PaperMenuRuntime? = null
    private var dialogRuntime: PaperDialogRuntime? = null

    fun initialize(plugin: Plugin, root: Path) {
        check(runtime == null) { "ARC menu runtime is already initialized" }
        dataRoot = root
        val disk = ConfigManager.of(root, ArcMenuConfiguration.RESOURCE)
        disk.mergeMissingFromBundled(ArcMenuConfiguration.RESOURCE)
        runtime = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), ArcMenuConfiguration.load(root))
        dialogRuntime = PaperDialogRuntime(plugin)
    }

    fun reload() {
        val active = requireNotNull(runtime) { "ARC menu runtime is not initialized" }
        active.replace(ArcMenuConfiguration.load(dataRoot))
    }

    fun current(): PaperMenuConfiguration =
        requireNotNull(runtime) { "ARC menu runtime is not initialized" }.current()

    fun openDialog(player: Player, screen: PaperDialogScreen) {
        requireNotNull(dialogRuntime) { "ARC dialog runtime is not initialized" }.open(player, screen)
    }

    fun item(
        menu: MenuId,
        element: String,
        context: PaperMenuItemRenderContext = PaperMenuItemRenderContext(),
    ): ItemStack = itemFactory.create(current().template(menu, MenuElementId.of(element)), context)

    fun item(
        template: String,
        context: PaperMenuItemRenderContext = PaperMenuItemRenderContext(),
    ): ItemStack = itemFactory.create(current().template(MenuTemplateId.of(template)), context)

    fun background(menu: MenuId): ItemStack? =
        current().catalog.require(menu).backgroundTemplate?.let { template ->
            itemFactory.create(current().template(template), Component.empty(), emptyList())
        }

    fun open(
        player: Player,
        menu: MenuId,
        title: Component,
        elements: Map<String, PaperMenuEntry> = emptyMap(),
        regions: Map<MenuRegionId, List<PaperMenuEntry>> = emptyMap(),
    ): PaperMenuSession =
        requireNotNull(runtime) { "ARC menu runtime is not initialized" }.open(player, menu) {
            PaperMenuContent(
                title = title,
                background = background(menu),
                elements = elements.mapKeys { MenuElementId.of(it.key) },
                regions = regions,
            )
        }

    fun entry(
        item: ItemStack,
        enabled: Boolean = true,
        acceptedClicks: Set<ClickType> = STANDARD_CLICKS,
        action: (Player) -> Unit = {},
    ): PaperMenuEntry = PaperMenuEntry(
        item = item,
        enabled = enabled,
        acceptedClicks = acceptedClicks,
        onClick = { context -> action(context.player) },
    )

    fun entryWithContext(
        item: ItemStack,
        enabled: Boolean = true,
        acceptedClicks: Set<ClickType> = STANDARD_CLICKS,
        action: (PaperMenuClickContext) -> Unit,
    ): PaperMenuEntry = PaperMenuEntry(
        item = item,
        enabled = enabled,
        acceptedClicks = acceptedClicks,
        onClick = action,
    )

    fun transferEntry(
        item: ItemStack,
        acceptedClicks: Set<ClickType> = setOf(ClickType.LEFT, ClickType.SHIFT_LEFT),
        transfer: (PaperMenuClickContext) -> PaperMenuTransferDecision,
    ): PaperMenuEntry = PaperMenuEntry(
        item = item,
        acceptedClicks = acceptedClicks,
        transfer = PaperMenuTransferHandler(transfer),
    )

    fun close() {
        dialogRuntime?.close()
        dialogRuntime = null
        runtime?.close()
        runtime = null
    }

    internal fun resetForTests() {
        dialogRuntime = null
        runtime = null
    }

    val STANDARD_CLICKS: Set<ClickType> = setOf(
        ClickType.LEFT,
        ClickType.RIGHT,
        ClickType.SHIFT_LEFT,
        ClickType.SHIFT_RIGHT,
        ClickType.MIDDLE,
    )
}
