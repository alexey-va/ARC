package ru.arc.dungeons.menu

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.arc.dungeons.api.ArcDungeonNavigation
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperMenuSession
import java.nio.file.Path

/** Plugin-local adapters over the published menu and dialog runtimes. */
object DungeonsMenus {
    private val itemFactory = PaperMenuItemFactory()
    private var dataRoot: Path? = null
    private var runtime: PaperMenuRuntime? = null
    private var dialogRuntime: PaperDialogRuntime? = null

    /** [legacyDataRoot] is the plugin data folder containing `guis/menus.yml`. */
    fun initialize(plugin: Plugin, legacyDataRoot: Path, prepared: ru.arc.config.Config) {
        requirePrimaryThread()
        check(runtime == null && dialogRuntime == null) { "ArcDungeons menu runtime is already initialized" }

        val configuration = DungeonsMenuSchema.bind(prepared)
        val menuCandidate = PaperMenuRuntime(plugin, Tasks.scheduler, configuration)
        val dialogCandidate = try {
            PaperDialogRuntime(plugin)
        } catch (failure: Throwable) {
            menuCandidate.close()
            throw failure
        }

        dataRoot = legacyDataRoot
        runtime = menuCandidate
        dialogRuntime = dialogCandidate
    }

    /** Parse a complete candidate first, then atomically replace the active menu generation. */
    fun reload(prepared: ru.arc.config.Config) {
        requirePrimaryThread()
        val active = requireNotNull(runtime) { "ArcDungeons menu runtime is not initialized" }
        active.replace(DungeonsMenuSchema.bind(prepared))
    }

    fun current() = requireNotNull(runtime) { "ArcDungeons menu runtime is not initialized" }.current()

    fun beginDialogFlow(player: Player) {
        requireNotNull(dialogRuntime) { "ArcDungeons dialog runtime is not initialized" }.beginFlow(player)
    }

    fun closeDialog(player: Player) {
        requireNotNull(dialogRuntime) { "ArcDungeons dialog runtime is not initialized" }.close(player)
    }

    fun openDialog(
        player: Player,
        screen: PaperDialogScreen,
        closeButton: PaperDialogButton? = null,
        reopen: (() -> Unit)? = null,
        onDismiss: () -> Unit = {},
    ) {
        val escapeGoesBack = MenuEscapeBehavior.goesBack(player)
        val close = closeButton ?: PaperDialogButton(
            id = PaperDialogActionId.of("close_menu"),
            label = Component.translatable("gui.back"),
            width = 200,
            closeDialogBeforeAction = true,
            onClick = {},
        )
        requireNotNull(dialogRuntime) { "ArcDungeons dialog runtime is not initialized" }.open(
            player,
            MenuEscapeBehavior.apply(screen, escapeGoesBack, close),
            reopen,
            onDismiss,
            !escapeGoesBack,
        )
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
    ): PaperMenuSession = requireNotNull(runtime) { "ArcDungeons menu runtime is not initialized" }.open(player, menu) {
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

    /** Close owned dialog history and inventory viewers during plugin shutdown. */
    fun close() {
        val dialogs = dialogRuntime
        val menus = runtime
        dialogRuntime = null
        runtime = null
        dataRoot = null
        try {
            dialogs?.close()
        } finally {
            menus?.close()
        }
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "ArcDungeons menu lifecycle must run on the primary server thread" }
    }

    private val STANDARD_CLICKS: Set<ClickType> = setOf(
        ClickType.LEFT,
        ClickType.RIGHT,
        ClickType.SHIFT_LEFT,
        ClickType.SHIFT_RIGHT,
        ClickType.MIDDLE,
    )
}
