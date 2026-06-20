package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.arc.configs.Config
import java.util.UUID

/**
 * DSL for building ItemStacks in a declarative Kotlin style.
 *
 * Example:
 * ```kotlin
 * val sword = itemStack(Material.DIAMOND_SWORD) {
 *     display("<gold>Legendary Sword")
 *     lore {
 *         +"<gray>A powerful weapon"
 *         +"<gray>Damage: <red>+50"
 *     }
 *     modelData(12345)
 *     enchant(Enchantment.SHARPNESS, 5)
 *     hideAll()
 * }
 * ```
 *
 * With tags:
 * ```kotlin
 * val info = itemStack(Material.PAPER) {
 *     display("<gold>Player: <player>")
 *     tags {
 *         "player" to playerName
 *         "level" to level.toString()
 *     }
 *     lore("<gray>Level: <level>")
 * }
 * ```
 */
@DslMarker
annotation class ItemStackDslMarker

/**
 * Entry point for ItemStack DSL.
 */
fun itemStack(
    material: Material,
    block: ItemStackDslBuilder.() -> Unit,
): ItemStack {
    val builder = ItemStackDslBuilder(material)
    builder.block()
    return builder.build()
}

/**
 * Entry point with amount.
 */
fun itemStack(
    material: Material,
    amount: Int,
    block: ItemStackDslBuilder.() -> Unit,
): ItemStack {
    val builder = ItemStackDslBuilder(material, amount)
    builder.block()
    return builder.build()
}

/**
 * Entry point for modifying an existing ItemStack.
 */
fun itemStack(
    stack: ItemStack,
    block: ItemStackDslBuilder.() -> Unit,
): ItemStack = stack.modify(block)

/**
 * Entry point for skull item.
 */
fun skullItem(
    uuid: UUID,
    block: ItemStackDslBuilder.() -> Unit,
): ItemStack {
    val builder = ItemStackDslBuilder(Material.PLAYER_HEAD)
    builder.skull(uuid)
    builder.block()
    return builder.build()
}

/**
 * Entry point for skull item from player.
 */
fun skullItem(
    player: Player,
    block: ItemStackDslBuilder.() -> Unit,
): ItemStack = skullItem(player.uniqueId, block)

@ItemStackDslMarker
class ItemStackDslBuilder(
    private var material: Material,
    private var amount: Int = 1,
) {
    private var modelData: Int = 0
    private var display: String? = null
    private var displayComponent: Component? = null
    private val loreLines = mutableListOf<String>()
    private var loreComponents: MutableList<Component>? = null
    private var skullUuid: UUID? = null
    private val enchants = mutableListOf<EnchantEntry>()
    private val itemFlags = mutableListOf<ItemFlag>()
    private val tagResolvers = mutableListOf<TagResolver>()
    private val registeredTagNames = mutableSetOf<String>()
    private var leatherColor: org.bukkit.Color? = null
    private var unbreakable: Boolean = false
    private var guiClickHandler: ((InventoryClickEvent) -> Unit)? = null

    private data class EnchantEntry(
        val enchantment: Enchantment,
        val level: Int,
        val ignoreLevelRestriction: Boolean,
    )

    // ==================== Material & Amount ====================

    fun material(material: Material) {
        this.material = material
    }

    fun amount(amount: Int) {
        this.amount = amount
    }

    // ==================== Display Name ====================

    fun display(text: String) {
        this.display = text
        this.displayComponent = null
    }

    fun display(component: Component) {
        this.displayComponent = component
        this.display = null
    }

    // ==================== Lore ====================

    /**
     * Set lore from vararg strings.
     */
    fun lore(vararg lines: String) {
        loreLines.clear()
        loreLines.addAll(lines)
        loreComponents = null
    }

    /**
     * Set lore from list.
     */
    fun lore(lines: List<String>) {
        loreLines.clear()
        loreLines.addAll(lines)
        loreComponents = null
    }

    /**
     * Set lore from components.
     */
    fun loreComponents(components: List<Component>) {
        loreComponents = components.toMutableList()
        loreLines.clear()
    }

    /**
     * DSL for building lore with + operator.
     *
     * ```kotlin
     * lore {
     *     +"<gray>Line 1"
     *     +"<gold>Line 2"
     *     if (condition) +"<red>Conditional line"
     * }
     * ```
     */
    fun lore(block: LoreDslBuilder.() -> Unit) {
        val builder = LoreDslBuilder()
        builder.block()
        loreLines.clear()
        loreLines.addAll(builder.lines)
        loreComponents = null
    }

    /**
     * Append lines to existing lore.
     */
    fun appendLore(vararg lines: String) {
        loreComponents = null
        loreLines.addAll(lines)
    }

    /**
     * Append lines from list.
     */
    fun appendLore(lines: List<String>) {
        loreComponents = null
        loreLines.addAll(lines)
    }

    // ==================== Model Data ====================

    fun modelData(data: Int) {
        this.modelData = data
    }

    // ==================== Skull ====================

    fun skull(uuid: UUID) {
        this.material = Material.PLAYER_HEAD
        this.skullUuid = uuid
    }

    fun skull(player: Player) {
        skull(player.uniqueId)
    }

    // ==================== Enchantments ====================

    /**
     * Add enchantment with default settings.
     */
    fun enchant(
        enchantment: Enchantment,
        level: Int = 1,
    ) {
        enchants.add(EnchantEntry(enchantment, level, false))
    }

    /**
     * Add enchantment with ignore level restriction.
     */
    fun enchantUnsafe(
        enchantment: Enchantment,
        level: Int,
    ) {
        enchants.add(EnchantEntry(enchantment, level, true))
    }

    /**
     * Add glowing effect (hidden enchant).
     */
    fun glowing() {
        enchants.add(EnchantEntry(Enchantment.UNBREAKING, 1, true))
        itemFlags.add(ItemFlag.HIDE_ENCHANTS)
    }

    // ==================== Item Flags ====================

    fun flags(vararg flags: ItemFlag) {
        itemFlags.addAll(flags)
    }

    fun hideAll() {
        itemFlags.addAll(
            listOf(
                ItemFlag.HIDE_ATTRIBUTES,
                ItemFlag.HIDE_ENCHANTS,
                ItemFlag.HIDE_PLACED_ON,
                ItemFlag.HIDE_DESTROYS,
                ItemFlag.HIDE_DYE,
                ItemFlag.HIDE_UNBREAKABLE,
            ),
        )
    }

    fun hideEnchants() {
        itemFlags.add(ItemFlag.HIDE_ENCHANTS)
    }

    fun hideAttributes() {
        itemFlags.add(ItemFlag.HIDE_ATTRIBUTES)
    }

    // ==================== Tags / Placeholders ====================

    /**
     * Add a single tag.
     */
    fun tag(
        name: String,
        value: String,
    ) {
        registeredTagNames.add(name)
        tagResolvers.add(TagResolver.resolver(name, Tag.inserting(TextUtil.mm(value, true))))
    }

    /**
     * Add a tag with Component value.
     */
    fun tag(
        name: String,
        value: Component,
    ) {
        registeredTagNames.add(name)
        tagResolvers.add(TagResolver.resolver(name, Tag.inserting(value)))
    }

    /**
     * Add multiple tags via DSL.
     *
     * ```kotlin
     * tags {
     *     "player" to playerName
     *     "amount" to "100"
     * }
     * ```
     */
    fun tags(block: TagsDslBuilder.() -> Unit) {
        val builder = TagsDslBuilder(registeredTagNames)
        builder.block()
        tagResolvers.addAll(builder.resolvers)
    }

    /**
     * Add a custom TagResolver.
     */
    fun tagResolver(resolver: TagResolver) {
        tagResolvers.add(resolver)
    }

    /**
     * Click handler for [guiItem] / [guiSkull] only (ignored by [itemStack]).
     */
    fun onClick(handler: (InventoryClickEvent) -> Unit) {
        guiClickHandler = handler
    }

    // ==================== Misc ====================

    fun leatherColor(color: org.bukkit.Color) {
        this.leatherColor = color
    }

    fun unbreakable(value: Boolean = true) {
        this.unbreakable = value
    }

    // ==================== Build ====================

    fun build(): ItemStack {
        val stack =
            if (skullUuid != null) {
                HeadUtil.getSkull(skullUuid!!)
            } else {
                ItemStack(material, amount)
            }

        val meta = stack.itemMeta ?: return stack

        // Model data
        if (modelData != 0) {
            @Suppress("DEPRECATION")
            meta.setCustomModelData(modelData)
        }

        // Combined tag resolver
        val combinedResolver =
            if (tagResolvers.isNotEmpty()) {
                TagResolver.resolver(tagResolvers + TagResolver.standard())
            } else {
                TagResolver.standard()
            }

        // Display name
        when {
            displayComponent != null -> {
                meta.displayName(TextUtil.strip(displayComponent))
            }

            display != null -> {
                val component = MiniMessage.miniMessage().deserialize(display!!, combinedResolver)
                meta.displayName(TextUtil.strip(component))
            }
        }

        // Lore
        when {
            loreComponents != null -> {
                meta.lore(loreComponents)
            }

            loreLines.isNotEmpty() -> {
                val components =
                    loreLines.map { line ->
                        val component = MiniMessage.miniMessage().deserialize(line, combinedResolver)
                        TextUtil.strip(component)!!
                    }
                meta.lore(components)
            }
        }

        // Item flags
        if (itemFlags.isNotEmpty()) {
            meta.addItemFlags(*itemFlags.toTypedArray())
        }

        // Enchantments
        for ((enchantment, level, ignoreLevelRestriction) in enchants) {
            meta.addEnchant(enchantment, level, ignoreLevelRestriction)
        }

        // Leather color
        if (leatherColor != null && meta is LeatherArmorMeta) {
            meta.setColor(leatherColor)
        }

        // Unbreakable
        if (unbreakable) {
            meta.isUnbreakable = true
        }

        stack.itemMeta = meta
        return stack
    }

    internal fun buildGuiItem(): com.github.stefvanschie.inventoryframework.gui.GuiItem =
        build().toGuiItem(guiClickHandler)

    internal fun peekDisplayDefault(): String? =
        when {
            display != null -> display
            displayComponent != null -> MiniMessage.miniMessage().serialize(displayComponent!!)
            else -> null
        }

    internal fun peekLoreDefault(): List<String>? =
        when {
            loreLines.isNotEmpty() -> loreLines.toList()
            loreComponents != null && loreComponents!!.isNotEmpty() ->
                loreComponents!!.map { MiniMessage.miniMessage().serialize(it) }
            else -> null
        }

    internal fun peekModelDataDefault(): Int? = modelData.takeIf { it != 0 }

    internal fun peekMaterialDefault(): Material = material

    internal fun peekRegisteredTagNames(): Set<String> = registeredTagNames.toSet()
}

// ==================== Lore DSL Builder ====================

@ItemStackDslMarker
class LoreDslBuilder {
    internal val lines = mutableListOf<String>()

    /**
     * Add a line using + operator.
     */
    operator fun String.unaryPlus() {
        lines.add(this)
    }

    /**
     * Add line conditionally.
     */
    fun lineIf(
        condition: Boolean,
        line: String,
    ) {
        if (condition) lines.add(line)
    }

    /**
     * Add empty line.
     */
    fun empty() {
        lines.add("")
    }

    /**
     * Add multiple lines.
     */
    fun lines(vararg additionalLines: String) {
        lines.addAll(additionalLines)
    }

    /**
     * Add lines from list.
     */
    fun lines(additionalLines: List<String>) {
        lines.addAll(additionalLines)
    }
}

// ==================== Tags DSL Builder ====================

@ItemStackDslMarker
class TagsDslBuilder(
    private val registeredTagNames: MutableSet<String>,
) {
    internal val resolvers = mutableListOf<TagResolver>()

    /**
     * Add tag using infix to operator.
     */
    infix fun String.to(value: String) {
        registeredTagNames.add(this)
        resolvers.add(TagResolver.resolver(this, Tag.inserting(TextUtil.mm(value, true))))
    }

    /**
     * Add tag with Component value.
     */
    infix fun String.to(value: Component) {
        registeredTagNames.add(this)
        resolvers.add(TagResolver.resolver(this, Tag.inserting(value)))
    }
}

// ==================== Extension Functions ====================

/**
 * Convert ItemStack to builder for modification.
 */
fun ItemStack.modify(block: ItemStackDslBuilder.() -> Unit): ItemStack {
    val builder = ItemStackDslBuilder(this.type, this.amount)
    // Copy existing properties
    this.itemMeta?.let { meta ->
        meta.customModelDataOrNull?.let { builder.modelData(it) }
        meta.displayName()?.let { builder.display(it) }
        meta.lore()?.let { builder.loreComponents(it) }
        meta.itemFlags.forEach { builder.flags(it) }
    }
    builder.block()
    return builder.build()
}

/**
 * Quick item with just display name.
 */
fun quickItem(
    material: Material,
    displayName: String,
): ItemStack =
    itemStack(material) {
        display(displayName)
        hideAttributes()
    }

/**
 * Quick item with display and lore.
 */
fun quickItem(
    material: Material,
    displayName: String,
    vararg loreLines: String,
): ItemStack =
    itemStack(material) {
        display(displayName)
        lore(*loreLines)
        hideAttributes()
    }

// ==================== GuiItem Integration ====================

/**
 * Convert ItemStack to GuiItem with optional click handler.
 */
fun ItemStack.toGuiItem(
    onClick: ((InventoryClickEvent) -> Unit)? = null,
): com.github.stefvanschie.inventoryframework.gui.GuiItem =
    ru.arc.gui.GuiItems.create(this) { event ->
        event.isCancelled = true
        onClick?.invoke(event)
    }

/**
 * Build a clickable GUI item — material in constructor, appearance + [onClick] in one block.
 *
 * ```kotlin
 * guiItem(Material.STICK) {
 *     onClick { event -> ... }
 *     display("<gold>Баланс")
 *     modelData(11138)
 *     fromConfig(config, "profile-menu.balance")  // module Config + path to item block
 * }
 * ```
 */
fun guiItem(
    material: Material,
    block: ItemStackDslBuilder.() -> Unit,
): com.github.stefvanschie.inventoryframework.gui.GuiItem {
    val builder = ItemStackDslBuilder(material)
    builder.block()
    return builder.buildGuiItem()
}

/**
 * Build a clickable GUI item from an existing stack (material taken from [stack]).
 */
fun guiItem(
    stack: ItemStack,
    block: ItemStackDslBuilder.() -> Unit = {},
): com.github.stefvanschie.inventoryframework.gui.GuiItem {
    val builder = ItemStackDslBuilder(stack.type, stack.amount)
    stack.itemMeta?.let { meta ->
        meta.customModelDataOrNull?.let { builder.modelData(it) }
        meta.displayName()?.let { builder.display(it) }
        meta.lore()?.let { builder.loreComponents(it) }
        meta.itemFlags.forEach { builder.flags(it) }
    }
    builder.block()
    return builder.buildGuiItem()
}

/**
 * Skull GuiItem — [onClick] and appearance in one block.
 */
fun guiSkull(
    uuid: UUID,
    block: ItemStackDslBuilder.() -> Unit,
): com.github.stefvanschie.inventoryframework.gui.GuiItem {
    val builder = ItemStackDslBuilder(Material.PLAYER_HEAD)
    builder.skull(uuid)
    builder.block()
    return builder.buildGuiItem()
}

fun guiSkull(
    player: Player,
    block: ItemStackDslBuilder.() -> Unit,
): com.github.stefvanschie.inventoryframework.gui.GuiItem = guiSkull(player.uniqueId, block)
