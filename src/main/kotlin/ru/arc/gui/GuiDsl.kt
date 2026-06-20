@file:Suppress("unused", "UNUSED", "DuplicatedCode")

package ru.arc.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import ru.arc.configs.Config
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.ConfigItemSpec
import ru.arc.util.applyItemFromConfig
import ru.arc.util.ItemConfigTarget
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.itemStack
import ru.arc.util.skullItem

/**
 * DSL for building GUIs declaratively.
 *
 * Example:
 * ```kotlin
 * val gui = gui("My GUI", 6, player) {
 *     background()
 *
 *     pagination(0 until 5) {
 *         items(myList) { item ->
 *             material(item.material)
 *             display("<gold>${item.name}")
 *             onClick { player.sendMessage("Clicked!") }
 *         }
 *     }
 *
 *     navBar {
 *         back(command = "menu")
 *         prevPage()
 *         nextPage()
 *     }
 * }
 * gui.show(player)
 * ```
 */
@DslMarker
annotation class GuiDslMarker

/**
 * Entry point for GUI DSL.
 */
fun gui(
    title: String,
    rows: Int = 6,
    player: Player? = null,
    config: Config? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    val builder = GuiBuilder(title, rows, player, config)
    builder.block()
    return builder.build()
}

/**
 * Entry point with config-based title.
 */
fun gui(
    config: Config,
    titleKey: String,
    rows: Int = 6,
    player: Player? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    val title = config.string(titleKey, "Menu")
    return gui(title, rows, player, config, block)
}

/**
 * Entry point for GUI with dynamic rows based on content.
 *
 * @param itemCount Number of items to display
 * @param itemsPerRow Items per row (default 9)
 * @param minRows Minimum number of rows (default 2)
 * @param maxRows Maximum number of rows (default 6)
 * @param navRows Rows reserved for navigation (default 1)
 */
fun dynamicGui(
    title: String,
    itemCount: Int,
    itemsPerRow: Int = 9,
    minRows: Int = 2,
    maxRows: Int = 6,
    navRows: Int = 1,
    player: Player? = null,
    config: Config? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    val contentRows = kotlin.math.ceil(itemCount.toDouble() / itemsPerRow).toInt()
    val totalRows = (contentRows + navRows).coerceIn(minRows, maxRows)
    return gui(title, totalRows, player, config, block)
}

/**
 * Entry point for GUI with dynamic rows and config-based title.
 */
fun dynamicGui(
    config: Config,
    titleKey: String,
    itemCount: Int,
    itemsPerRow: Int = 9,
    minRows: Int = 2,
    maxRows: Int = 6,
    navRows: Int = 1,
    player: Player? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    val title = config.string(titleKey, "Menu")
    return dynamicGui(title, itemCount, itemsPerRow, minRows, maxRows, navRows, player, config, block)
}

/**
 * Entry point for GUI with dynamic rows and config-based title with placeholders.
 */
fun dynamicGui(
    config: Config,
    titleKey: String,
    placeholders: Map<String, String>,
    itemCount: Int,
    itemsPerRow: Int = 9,
    minRows: Int = 2,
    maxRows: Int = 6,
    navRows: Int = 1,
    player: Player? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    var title = config.string(titleKey, "Menu")
    placeholders.forEach { (key, value) ->
        title = title.replace("<$key>", value).replace("{$key}", value)
    }
    return dynamicGui(title, itemCount, itemsPerRow, minRows, maxRows, navRows, player, config, block)
}

/**
 * Entry point for GUI with title containing placeholders.
 *
 * @param titleKey Config key for the title string (may contain placeholders)
 * @param placeholders Map of placeholder names to values
 */
fun gui(
    config: Config,
    titleKey: String,
    placeholders: Map<String, String>,
    rows: Int = 6,
    player: Player? = null,
    block: GuiBuilder.() -> Unit,
): ChestGui {
    var title = config.string(titleKey, "Menu")
    placeholders.forEach { (key, value) ->
        title = title.replace("<$key>", value).replace("{$key}", value)
    }
    return gui(title, rows, player, config, block)
}

@GuiDslMarker
class GuiBuilder(
    title: String,
    private val rows: Int,
    val player: Player?,
    val config: Config?,
) {
    val gui = ChestGui(rows, TextHolder.deserialize(TextUtil.toLegacy(title)))
    private var paginatedPane: PaginatedPane? = null
    private var navBar: NavBarBuilder? = null
    private val buildCallbacks = mutableListOf<(ChestGui) -> Unit>()

    /**
     * Register a callback to be called when GUI is built.
     * Used for adding event handlers and other post-build customization.
     */
    fun onBuild(callback: (ChestGui) -> Unit) {
        buildCallbacks.add(callback)
    }

    /**
     * Add a repeating background to the entire GUI or specific rows.
     */
    fun background(
        material: Material = GuiDefaults.Background.material,
        modelData: Int = GuiDefaults.Background.modelData,
        startRow: Int = 0,
        endRow: Int = rows,
    ) {
        val pane = OutlinePane(9, endRow - startRow, Pane.Priority.LOWEST).apply {
            addItem(GuiUtils.background(material, modelData))
            setRepeat(true)
        }
        gui.addPane(Slot.fromXY(0, startRow), pane)
    }

    /**
     * Add background to bottom navigation row only.
     */
    fun navBackground(
        material: Material = GuiDefaults.Background.material,
        modelData: Int = GuiDefaults.Background.modelData,
    ) {
        background(material, modelData, rows - 1, rows)
    }

    /**
     * Add background to content area (all rows except last).
     */
    fun contentBackground(
        material: Material = GuiDefaults.Background.contentMaterial,
        modelData: Int = GuiDefaults.Background.contentModelData,
    ) {
        background(material, modelData, 0, rows - 1)
    }

    /**
     * Create a pagination area.
     */
    fun pagination(
        rowRange: IntRange = 0 until (rows - 1),
        block: PaginationBuilder.() -> Unit,
    ) {
        val pane = PaginatedPane(9, rowRange.last - rowRange.first + 1)
        paginatedPane = pane
        gui.addPane(Slot.fromXY(0, rowRange.first), pane)

        val builder = PaginationBuilder(pane, this)
        builder.block()
        builder.finalize()
    }

    /**
     * Create a static pane at specific position.
     */
    fun staticPane(
        x: Int = 0,
        y: Int = 0,
        width: Int = 9,
        height: Int = 1,
        block: StaticPaneBuilder.() -> Unit,
    ) {
        val pane = StaticPane(width, height)
        gui.addPane(Slot.fromXY(x, y), pane)

        val builder = StaticPaneBuilder(pane, this)
        builder.block()
    }

    /**
     * Create a navigation bar at the bottom row.
     */
    fun navBar(
        row: Int = rows - 1,
        block: NavBarBuilder.() -> Unit,
    ) {
        val pane = StaticPane(9, 1)
        gui.addPane(Slot.fromXY(0, row), pane)

        val builder = NavBarBuilder(pane, this, paginatedPane)
        navBar = builder
        builder.block()
    }

    fun build(): ChestGui {
        buildCallbacks.forEach { it(gui) }
        return gui
    }
}

@GuiDslMarker
class PaginationBuilder(
    private val pane: PaginatedPane,
    private val guiBuilder: GuiBuilder,
) {
    private val allItems = mutableListOf<GuiItem>()

    /**
     * Add items from a list. Can be called multiple times to combine items from different sources.
     *
     * Example:
     * ```kotlin
     * pagination {
     *     items(baseBoosts) { boost -> /* ... */ }
     *     items(playerBoosts) { boost -> /* ... */ }
     * }
     * ```
     */
    fun <T> items(
        list: List<T>,
        itemBuilder: ItemBuilder.(T) -> Unit,
    ) {
        list.forEach { item ->
            val builder = ItemBuilder(guiBuilder)
            builder.itemBuilder(item)
            allItems.add(builder.build())
        }
    }

    /**
     * Add items from a collection.
     */
    fun <T> items(
        collection: Collection<T>,
        itemBuilder: ItemBuilder.(T) -> Unit,
    ) {
        items(collection.toList(), itemBuilder)
    }

    /**
     * Add a single item.
     *
     * Example:
     * ```kotlin
     * pagination {
     *     item {
     *         material(Material.DIAMOND)
     *         display("<gold>Special Item")
     *     }
     *     items(otherItems) { /* ... */ }
     * }
     * ```
     */
    fun item(itemBuilder: ItemBuilder.() -> Unit) {
        val builder = ItemBuilder(guiBuilder)
        builder.itemBuilder()
        allItems.add(builder.build())
    }

    /**
     * Conditionally add a single item.
     *
     * Example:
     * ```kotlin
     * pagination {
     *     itemIf(player.hasPermission("vip")) {
     *         material(Material.DIAMOND)
     *         display("<gold>VIP Item")
     *     }
     * }
     * ```
     */
    fun itemIf(
        condition: Boolean,
        itemBuilder: ItemBuilder.() -> Unit,
    ) {
        if (condition) {
            item(itemBuilder)
        }
    }

    /**
     * Conditionally add items from a list.
     */
    fun <T> itemsIf(
        condition: Boolean,
        list: List<T>,
        itemBuilder: ItemBuilder.(T) -> Unit,
    ) {
        if (condition) {
            items(list, itemBuilder)
        }
    }

    /**
     * Add pre-built GuiItems.
     */
    fun guiItems(items: List<GuiItem>) {
        allItems.addAll(items)
    }

    /**
     * Add a pre-built GuiItem.
     */
    fun guiItem(item: GuiItem) {
        allItems.add(item)
    }

    /**
     * Finalize and populate the pane with all accumulated items.
     * Called automatically by GuiBuilder.
     */
    internal fun finalize() {
        if (allItems.isNotEmpty()) {
            pane.populateWithGuiItems(allItems)
        }
    }
}

@GuiDslMarker
class StaticPaneBuilder(
    private val pane: StaticPane,
    private val guiBuilder: GuiBuilder,
) {
    /**
     * Add an item at a specific position.
     */
    fun item(
        x: Int,
        y: Int = 0,
        block: ItemBuilder.() -> Unit,
    ) {
        val builder = ItemBuilder(guiBuilder)
        builder.block()
        pane.addItem(builder.build(), x, y)
    }

    /**
     * Add a pre-built GuiItem.
     */
    fun item(
        x: Int,
        y: Int = 0,
        guiItem: GuiItem,
    ) {
        pane.addItem(guiItem, x, y)
    }
}

@GuiDslMarker
class NavBarBuilder(
    private val pane: StaticPane,
    private val guiBuilder: GuiBuilder,
    private val paginatedPane: PaginatedPane?,
) {
    private val config get() = guiBuilder.config
    private val player get() = guiBuilder.player

    /**
     * Add a back button that runs a command.
     */
    fun back(
        slot: Int = GuiDefaults.Slots.back,
        command: String? = null,
        configKey: String = "back",
        material: Material = GuiDefaults.BackButton.material,
        modelData: Int = GuiDefaults.BackButton.modelData,
    ) {
        val commandKey = "$configKey-command"

        val item =
            guiItem(material) {
                if (modelData != 0) modelData(modelData)
                display(GuiDefaults.BackButton.defaultDisplay)
                lore(emptyList())
                config?.let { fromConfig(it, configKey) }
                onClick { click ->
                    val cmd = command ?: config?.string(commandKey) ?: GuiDefaults.BackButton.defaultCommand
                    (click.whoClicked as? Player)?.performCommand(cmd)
                }
            }

        pane.addItem(item, slot, 0)
    }

    /**
     * Add a back button with custom action.
     */
    fun back(
        slot: Int = GuiDefaults.Slots.back,
        configKey: String = "back",
        material: Material = GuiDefaults.BackButton.material,
        modelData: Int = GuiDefaults.BackButton.modelData,
        action: () -> Unit,
    ) {
        val item =
            guiItem(material) {
                if (modelData != 0) modelData(modelData)
                display(GuiDefaults.BackButton.defaultDisplay)
                lore(emptyList())
                config?.let { fromConfig(it, configKey) }
                onClick { action() }
            }

        pane.addItem(item, slot, 0)
    }

    /**
     * Add a previous page button.
     */
    fun prevPage(
        slot: Int = GuiDefaults.Slots.prev,
        configKey: String = "previous",
        material: Material = GuiDefaults.PrevButton.material,
        modelData: Int = GuiDefaults.PrevButton.modelData,
    ) {
        if (paginatedPane == null) return

        val item =
            guiItem(material) {
                if (modelData != 0) modelData(modelData)
                display(GuiDefaults.PrevButton.defaultDisplay)
                lore(emptyList())
                config?.let { fromConfig(it, configKey) }
                onClick {
                    if (paginatedPane.page > 0) {
                        paginatedPane.page = paginatedPane.page - 1
                        guiBuilder.gui.update()
                    }
                }
            }

        pane.addItem(item, slot, 0)
    }

    /**
     * Add a next page button.
     */
    fun nextPage(
        slot: Int = GuiDefaults.Slots.next,
        configKey: String = "next",
        material: Material = GuiDefaults.NextButton.material,
        modelData: Int = GuiDefaults.NextButton.modelData,
    ) {
        if (paginatedPane == null) return

        val item =
            guiItem(material) {
                if (modelData != 0) modelData(modelData)
                display(GuiDefaults.NextButton.defaultDisplay)
                lore(emptyList())
                config?.let { fromConfig(it, configKey) }
                onClick {
                    if (paginatedPane.page < paginatedPane.pages - 1) {
                        paginatedPane.page = paginatedPane.page + 1
                        guiBuilder.gui.update()
                    }
                }
            }

        pane.addItem(item, slot, 0)
    }

    /**
     * Add a custom button.
     */
    fun button(
        slot: Int,
        block: ItemBuilder.() -> Unit,
    ) {
        val builder = ItemBuilder(guiBuilder)
        builder.block()
        pane.addItem(builder.build(), slot, 0)
    }

    /**
     * Add a button that opens another GUI.
     */
    fun openGui(
        slot: Int,
        guiSupplier: () -> ChestGui,
        block: ItemBuilder.() -> Unit,
    ) {
        val builder = ItemBuilder(guiBuilder)
        builder.block()
        builder.onClick {
            GuiUtils.constructAndShowAsync({ guiSupplier() }, it.whoClicked)
        }
        pane.addItem(builder.build(), slot, 0)
    }
}

@GuiDslMarker
class ItemBuilder private constructor(
    private val config: Config?,
) {
    private var material: Material = Material.STONE
    private var amount: Int = 1
    private var modelData: Int = 0
    private var display: String? = null
    private var lore: List<String> = emptyList()
    private var displayComponent: Component? = null
    private var loreComponents: List<Component>? = null
    private var skullUuid: java.util.UUID? = null
    private val tagResolvers = mutableListOf<TagResolver>()
    private val registeredTagNames = mutableSetOf<String>()
    private var clickHandler: ((InventoryClickEvent) -> Unit)? = null
    private var cancelClick: Boolean = true
    private var itemStack: ItemStack? = null

    /**
     * Create ItemBuilder from GuiBuilder context.
     */
    constructor(guiBuilder: GuiBuilder) : this(guiBuilder.config)

    /**
     * Create standalone ItemBuilder (for testing or standalone use).
     */
    companion object {
        fun standalone(config: Config? = null) = ItemBuilder(config)
    }

    fun material(material: Material) {
        this.material = material
    }

    fun stack(stack: ItemStack) {
        this.itemStack = stack
    }

    fun amount(amount: Int) {
        this.amount = amount
    }

    fun modelData(data: Int) {
        this.modelData = data
    }

    fun display(text: String) {
        this.display = text
    }

    fun display(component: Component) {
        this.displayComponent = component
    }

    fun lore(lines: List<String>) {
        this.lore = lines
    }

    fun lore(vararg lines: String) {
        this.lore = lines.toList()
    }

    fun loreComponents(components: List<Component>) {
        this.loreComponents = components
    }

    fun skull(uuid: java.util.UUID) {
        this.material = Material.PLAYER_HEAD
        this.skullUuid = uuid
    }

    /**
     * Add a placeholder tag.
     */
    fun tag(
        name: String,
        value: String,
    ) {
        registeredTagNames.add(name)
        tagResolvers.add(TagResolver.resolver(name, Tag.inserting(TextUtil.mm(value, true))))
    }

    /**
     * Add a placeholder tag with Component value.
     */
    fun tag(
        name: String,
        value: Component,
    ) {
        registeredTagNames.add(name)
        tagResolvers.add(TagResolver.resolver(name, Tag.inserting(value)))
    }

    /**
     * Add multiple tags from a map.
     */
    fun tags(map: Map<String, String>) {
        map.forEach { (k, v) -> tag(k, v) }
    }

    /**
     * Add a custom TagResolver.
     */
    fun tagResolver(resolver: TagResolver) {
        tagResolvers.add(resolver)
    }

    // Store enchantments and flags for applying during build
    private val enchantments = mutableListOf<Triple<org.bukkit.enchantments.Enchantment?, Int, Boolean>>()
    private val itemFlags = mutableListOf<org.bukkit.inventory.ItemFlag>()

    /**
     * Add enchantment to item.
     */
    fun enchant(
        enchantment: org.bukkit.enchantments.Enchantment?,
        level: Int = 1,
        ignoreLevelRestriction: Boolean = false,
    ) {
        if (enchantment != null) {
            enchantments.add(Triple(enchantment, level, ignoreLevelRestriction))
        }
    }

    /**
     * Add item flags.
     */
    fun flags(vararg flags: org.bukkit.inventory.ItemFlag) {
        itemFlags.addAll(flags)
    }

    /**
     * Set click handler. Click is automatically cancelled unless allowClick() is called.
     */
    fun onClick(handler: (InventoryClickEvent) -> Unit) {
        this.clickHandler = handler
    }

    /**
     * Multi-action click handler DSL.
     *
     * Example:
     * ```kotlin
     * clicks {
     *     left { player.sendMessage("Left clicked!") }
     *     right { player.sendMessage("Right clicked!") }
     *     shiftLeft { openEditor(item) }
     * }
     * ```
     */
    fun clicks(block: ClickActionBuilder.() -> Unit) {
        val builder = ClickActionBuilder()
        builder.block()
        this.clickHandler = builder.build()
    }

    /**
     * Allow click event to propagate (don't cancel).
     */
    fun allowClick() {
        this.cancelClick = false
    }

    /**
     * Override fields from config when keys exist; code defaults set earlier are kept as fallback.
     */
    fun fromConfig(
        config: Config,
        path: String,
    ) {
        applyItemFromConfig(config, path, GuiItemConfigTarget(this))
    }

    /**
     * Style-only overlay (material, customModelData) for dynamic list rows — never injects display/lore.
     */
    fun fromConfigStyle(
        config: Config,
        path: String,
    ) {
        ConfigItemSpec.readFromConfig(config, path)?.let { spec ->
            spec.material?.let { material(it) }
            spec.modelData?.takeIf { it != 0 }?.let { modelData(it) }
        }
    }

    /**
     * Multi-action click handler builder.
     */
    @GuiDslMarker
    class ClickActionBuilder {
        private var leftAction: ((InventoryClickEvent) -> Unit)? = null
        private var rightAction: ((InventoryClickEvent) -> Unit)? = null
        private var shiftLeftAction: ((InventoryClickEvent) -> Unit)? = null
        private var shiftRightAction: ((InventoryClickEvent) -> Unit)? = null
        private var middleAction: ((InventoryClickEvent) -> Unit)? = null
        private var anyAction: ((InventoryClickEvent) -> Unit)? = null

        fun left(action: (InventoryClickEvent) -> Unit) {
            leftAction = action
        }

        fun right(action: (InventoryClickEvent) -> Unit) {
            rightAction = action
        }

        fun shiftLeft(action: (InventoryClickEvent) -> Unit) {
            shiftLeftAction = action
        }

        fun shiftRight(action: (InventoryClickEvent) -> Unit) {
            shiftRightAction = action
        }

        fun middle(action: (InventoryClickEvent) -> Unit) {
            middleAction = action
        }

        fun any(action: (InventoryClickEvent) -> Unit) {
            anyAction = action
        }

        internal fun build(): (InventoryClickEvent) -> Unit =
            { event ->
                when {
                    event.isShiftClick && event.isLeftClick -> shiftLeftAction?.invoke(event)
                    event.isShiftClick && event.isRightClick -> shiftRightAction?.invoke(event)
                    event.isLeftClick -> leftAction?.invoke(event)
                    event.isRightClick -> rightAction?.invoke(event)
                    event.click.isKeyboardClick -> middleAction?.invoke(event)
                    else -> anyAction?.invoke(event)
                }
            }
    }

    internal fun peekDisplayDefault(): String? =
        when {
            display != null -> display
            displayComponent != null -> displayComponent?.let { net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(it) }
            else -> null
        }

    internal fun peekLoreDefault(): List<String>? =
        when {
            lore.isNotEmpty() -> lore
            loreComponents != null && loreComponents!!.isNotEmpty() ->
                loreComponents!!.map { net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(it) }
            else -> null
        }

    internal fun peekModelDataDefault(): Int? = modelData.takeIf { it != 0 }

    internal fun peekMaterialDefault(): Material = material

    internal fun peekRegisteredTagNames(): Set<String> = registeredTagNames.toSet()

    fun build(): GuiItem {
        val stack =
            itemStack ?: run {
                val builtStack =
                    if (skullUuid != null) {
                        skullItem(skullUuid!!) {
                            if (modelData != 0) modelData(modelData)

                            tagResolvers.forEach { tagResolver(it) }

                            if (displayComponent != null) {
                                display(displayComponent!!)
                            } else if (display != null) {
                                display(display!!)
                            }

                            if (loreComponents != null) {
                                loreComponents(loreComponents!!)
                            } else if (lore.isNotEmpty()) {
                                lore(lore)
                            }

                            for ((enchant, level, ignoreLevelRestriction) in enchantments) {
                                if (enchant != null) {
                                    if (ignoreLevelRestriction) {
                                        enchantUnsafe(enchant, level)
                                    } else {
                                        enchant(enchant, level)
                                    }
                                }
                            }

                            if (itemFlags.isNotEmpty()) {
                                flags(*itemFlags.toTypedArray())
                            }
                        }
                    } else {
                        itemStack(material, amount) {
                            if (modelData != 0) modelData(modelData)

                            tagResolvers.forEach { tagResolver(it) }

                            if (displayComponent != null) {
                                display(displayComponent!!)
                            } else if (display != null) {
                                display(display!!)
                            }

                            if (loreComponents != null) {
                                loreComponents(loreComponents!!)
                            } else if (lore.isNotEmpty()) {
                                lore(lore)
                            }

                            for ((enchant, level, ignoreLevelRestriction) in enchantments) {
                                if (enchant != null) {
                                    if (ignoreLevelRestriction) {
                                        enchantUnsafe(enchant, level)
                                    } else {
                                        enchant(enchant, level)
                                    }
                                }
                            }

                            if (itemFlags.isNotEmpty()) {
                                flags(*itemFlags.toTypedArray())
                            }
                        }
                    }
                builtStack.also { it.amount = amount }
            }

        return GuiItems.create(stack) { event ->
            if (cancelClick) event.isCancelled = true
            clickHandler?.invoke(event)
        }
    }
}

private class GuiItemConfigTarget(
    private val builder: ItemBuilder,
) : ItemConfigTarget {
    override fun peekDisplay(): String? = builder.peekDisplayDefault()

    override fun peekLore(): List<String>? = builder.peekLoreDefault()

    override fun peekModelData(): Int? = builder.peekModelDataDefault()

    override fun peekMaterial(): Material? = builder.peekMaterialDefault()

    override fun peekRegisteredTags(): Collection<String> = builder.peekRegisteredTagNames()

    override fun applyDisplay(text: String) {
        builder.display(text)
    }

    override fun applyLore(lines: List<String>) {
        builder.lore(lines)
    }

    override fun applyModelData(data: Int) {
        builder.modelData(data)
    }

    override fun applyMaterial(material: Material) {
        builder.material(material)
    }
}

// ==================== Extension Functions ====================

/**
 * Extension to show GUI to player with null safety.
 */
fun ChestGui.showTo(player: Player?) {
    player?.let { show(it) }
}

/**
 * Extension to show GUI asynchronously.
 */
fun ChestGui.showAsync(
    player: Player,
    delay: Int = 3,
) {
    GuiUtils.constructAndShowAsync({ this }, player, delay)
}

// ==================== Convenience Builders ====================

/**
 * Quick builder for simple item.
 */
fun guiItem(
    material: Material,
    displayName: String,
    loreLines: List<String> = emptyList(),
    modelDataValue: Int = 0,
    onClick: ((InventoryClickEvent) -> Unit)? = null,
): GuiItem =
    ru.arc.util.guiItem(material) {
        display(displayName)
        lore(loreLines)
        if (modelDataValue != 0) modelData(modelDataValue)
        onClick?.let { handler -> onClick { handler(it) } }
    }

/**
 * Quick builder for config-driven item.
 */
fun guiItem(
    config: Config,
    keyPrefix: String,
    material: Material = Material.STONE,
    modelDataValue: Int = 0,
    tagResolver: TagResolver = TagResolver.standard(),
    onClick: ((InventoryClickEvent) -> Unit)? = null,
): GuiItem =
    ru.arc.util.guiItem(material) {
        display(config.string("$keyPrefix.display", config.string("$keyPrefix.name", keyPrefix)))
        lore(config.stringList("$keyPrefix.lore"))
        tagResolver(tagResolver)
        if (modelDataValue != 0) modelData(modelDataValue)
        fromConfig(config, keyPrefix)
        onClick?.let { handler -> onClick { handler(it) } }
    }
