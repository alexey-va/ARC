package ru.arc.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import ru.arc.configs.Config
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig
import ru.arc.util.guiItem

/**
 * Base class for config-driven GUIs.
 *
 * Reduces boilerplate by providing common functionality:
 * - Automatic background setup
 * - Config-driven title and items
 * - Built-in navigation (back, prev, next)
 * - Pagination support
 *
 * Example:
 * ```kotlin
 * class MyGui(player: Player) : ConfigGui(
 *     config = MyModule.config,
 *     configPrefix = "my-gui",
 *     player = player,
 *     rows = 6
 * ) {
 *     init {
 *         setupBackground()
 *         setupContent()
 *         setupNavBar()
 *     }
 *
 *     private fun setupContent() {
 *         pagination(0 until 5) { pane ->
 *             pane.populateWithGuiItems(items.map { toGuiItem(it) })
 *         }
 *     }
 *
 *     override fun setupNavBar() {
 *         navBar { pane ->
 *             pane.addItem(backButton(), 0, 0)
 *             pane.addItem(prevButton(), 3, 0)
 *             pane.addItem(nextButton(), 5, 0)
 *         }
 *     }
 * }
 * ```
 */
abstract class ConfigGui(
    protected val config: Config,
    protected val configPrefix: String,
    protected val player: Player,
    rowCount: Int = 6,
) : ChestGui(
        rowCount,
        TextHolder.deserialize(
            TextUtil.toLegacy(config.string("$configPrefix.title", "Menu")),
        ),
    ) {
    protected var paginatedPane: PaginatedPane? = null
    protected val rowCount: Int = rowCount

    // ==================== Background ====================

    /**
     * Setup full background.
     */
    protected fun setupBackground(
        material: Material = GuiDefaults.Background.material,
        modelData: Int = GuiDefaults.Background.modelData,
    ) {
        val pane = OutlinePane(9, rowCount, Pane.Priority.LOWEST).apply {
            addItem(GuiUtils.background(material, modelData))
            setRepeat(true)
        }
        addPane(Slot.fromXY(0, 0), pane)
    }

    /**
     * Setup background for content area (all except last row).
     */
    protected fun setupContentBackground(
        material: Material = GuiDefaults.Background.contentMaterial,
        modelData: Int = GuiDefaults.Background.contentModelData,
    ) {
        val pane = OutlinePane(9, rowCount - 1, Pane.Priority.LOWEST).apply {
            addItem(GuiUtils.background(material, modelData))
            setRepeat(true)
        }
        addPane(Slot.fromXY(0, 0), pane)
    }

    /**
     * Setup background for navigation bar (last row only).
     */
    protected fun setupNavBackground(
        material: Material = GuiDefaults.Background.material,
        modelData: Int = GuiDefaults.Background.modelData,
    ) {
        val pane = OutlinePane(9, 1, Pane.Priority.LOWEST).apply {
            addItem(GuiUtils.background(material, modelData))
            setRepeat(true)
        }
        addPane(Slot.fromXY(0, rowCount - 1), pane)
    }

    // ==================== Pagination ====================

    /**
     * Create pagination area.
     */
    protected fun pagination(
        rowRange: IntRange = 0 until (rowCount - 1),
        block: (PaginatedPane) -> Unit,
    ) {
        val pane = PaginatedPane(9, rowRange.last - rowRange.first + 1)
        paginatedPane = pane
        addPane(Slot.fromXY(0, rowRange.first), pane)
        block(pane)
    }

    // ==================== Navigation Bar ====================

    /**
     * Create navigation bar at bottom row.
     */
    protected fun navBar(block: (StaticPane) -> Unit) {
        val pane = StaticPane(9, 1)
        addPane(Slot.fromXY(0, rowCount - 1), pane)
        block(pane)
    }

    /**
     * Create a back button.
     */
    protected fun backButton(
        key: String = "back",
        material: Material = GuiDefaults.BackButton.material,
        modelData: Int = GuiDefaults.BackButton.modelData,
        command: String? = null,
    ): GuiItem =
        guiItem(material) {
            if (modelData != 0) modelData(modelData)
            display(GuiDefaults.BackButton.defaultDisplay)
            lore(emptyList())
            fromConfig(config, "$configPrefix.$key")
            onClick {
                val cmd = command ?: config.string("$configPrefix.$key-command", GuiDefaults.BackButton.defaultCommand)
                (it.whoClicked as? Player)?.performCommand(cmd)
            }
        }

    /**
     * Create a previous page button.
     */
    protected fun prevButton(
        key: String = "previous",
        material: Material = GuiDefaults.PrevButton.material,
        modelData: Int = GuiDefaults.PrevButton.modelData,
    ): GuiItem =
        guiItem(material) {
            if (modelData != 0) modelData(modelData)
            display(GuiDefaults.PrevButton.defaultDisplay)
            lore(emptyList())
            fromConfig(config, "$configPrefix.$key")
            onClick {
                paginatedPane?.let { pane ->
                    if (pane.page > 0) {
                        pane.page = pane.page - 1
                        update()
                    }
                }
            }
        }

    /**
     * Create a next page button.
     */
    protected fun nextButton(
        key: String = "next",
        material: Material = GuiDefaults.NextButton.material,
        modelData: Int = GuiDefaults.NextButton.modelData,
    ): GuiItem =
        guiItem(material) {
            if (modelData != 0) modelData(modelData)
            display(GuiDefaults.NextButton.defaultDisplay)
            lore(emptyList())
            fromConfig(config, "$configPrefix.$key")
            onClick {
                paginatedPane?.let { pane ->
                    if (pane.page < pane.pages - 1) {
                        pane.page = pane.page + 1
                        update()
                    }
                }
            }
        }

    // ==================== Item Builders ====================

    /**
     * Build item from config keys.
     */
    protected fun configItem(
        key: String,
        material: Material = Material.STONE,
        modelData: Int = 0,
        tagResolver: TagResolver = TagResolver.standard(),
        onClick: ((InventoryClickEvent) -> Unit)? = null,
    ): GuiItem =
        guiItem(material) {
            if (modelData != 0) modelData(modelData)
            display(config.string("$configPrefix.$key.display", config.string("$configPrefix.$key.name", key)))
            lore(config.stringList("$configPrefix.$key.lore"))
            tagResolver(tagResolver)
            onClick?.let { handler -> onClick { handler(it) } }
        }

    /**
     * Build tag resolver with common placeholders.
     */
    protected fun resolver(vararg pairs: Pair<String, String>): TagResolver {
        val builder = TagResolver.builder()
        pairs.forEach { (key, value) ->
            builder.tag(key, Tag.inserting(TextUtil.mm(value, true)))
        }
        return builder.build()
    }

    /**
     * Build tag resolver with player placeholder.
     */
    protected fun playerResolver(): TagResolver = resolver("player" to player.name)

    // ==================== Utilities ====================

    /**
     * Show temporary error message on an item.
     */
    protected fun showError(
        item: GuiItem,
        messageKey: String,
        ticks: Long = 60,
    ) {
        GuiUtils.temporaryChange(
            item.item,
            TextUtil.mm(config.string("$configPrefix.$messageKey", "<red>Ошибка"), true),
            null,
            ticks,
            ::update,
        )
    }

    /**
     * Show temporary cooldown message on an item.
     */
    protected fun showCooldown(
        item: GuiItem,
        ticks: Long = 40,
    ) {
        GuiUtils.temporaryChange(
            item.item,
            TextUtil.mm(config.string("$configPrefix.cooldown", "<red>Подождите..."), true),
            null,
            ticks,
            ::update,
        )
    }

    /**
     * Navigate to another GUI.
     */
    protected fun openGui(guiSupplier: () -> ChestGui) {
        GuiUtils.constructAndShowAsync({ guiSupplier() }, player)
    }
}

// ==================== Quick Config GUI Factory ====================

/**
 * Quick factory for simple paginated GUIs.
 */
object QuickGui {
    /**
     * Create a simple paginated list GUI.
     */
    fun <T> paginatedList(
        config: Config,
        configPrefix: String,
        player: Player,
        items: List<T>,
        rows: Int = 6,
        itemBuilder: (T, TagResolver.Builder) -> GuiItem,
    ): ChestGui =
        gui(config.string("$configPrefix.title", "Menu"), rows, player, config) {
            background()
            navBackground()

            pagination(0 until (rows - 1)) {
                items(items) { item ->
                    // Delegate to custom builder
                    val resolverBuilder = TagResolver.builder()
                    stack(itemBuilder(item, resolverBuilder).item)
                }
            }

            navBar {
                back(configKey = "$configPrefix.back")
                prevPage(configKey = "$configPrefix.previous")
                nextPage(configKey = "$configPrefix.next")
            }
        }

    /**
     * Create a simple confirmation GUI.
     */
    fun confirmation(
        config: Config,
        configPrefix: String,
        player: Player,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = { player.closeInventory() },
    ): ChestGui =
        gui(config.string("$configPrefix.title", "Подтверждение"), 3, player, config) {
            background()

            staticPane(0, 1, 9, 1) {
                // Confirm button
                item(GuiDefaults.Slots.confirm) {
                    material(GuiDefaults.ConfirmButton.material)
                    display(GuiDefaults.ConfirmButton.defaultDisplay)
                    lore(emptyList())
                    fromConfig(config, "$configPrefix.confirm")
                    onClick { onConfirm() }
                }

                // Cancel button
                item(GuiDefaults.Slots.cancel) {
                    material(GuiDefaults.CancelButton.material)
                    display(GuiDefaults.CancelButton.defaultDisplay)
                    lore(emptyList())
                    fromConfig(config, "$configPrefix.cancel")
                    onClick { onCancel() }
                }
            }
        }
}
