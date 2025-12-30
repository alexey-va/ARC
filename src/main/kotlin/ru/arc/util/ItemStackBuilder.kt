package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.TagPattern
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.util.UUID
import java.util.stream.Collectors

class ItemStackBuilder private constructor() {
    private var material: Material = Material.STONE
    private var count: Int = 1
    private var modelData: Int = 0
    private var display: SerializedString? = null
    private var enchants: MutableList<EnchantData> = mutableListOf()
    private var componentDisplay: Component? = null
    private var lore: MutableList<SerializedString> = mutableListOf()
    private var componentLore: MutableList<Component>? = null
    private var skullUuid: UUID? = null
    private var flags: List<ItemFlag>? = null
    private var globalDeserializer: Deserializer = Deserializer.MINI_MESSAGE
    var tagResolver: TagResolver = TagResolver.standard()

    constructor(material: Material) : this() {
        this.material = material
    }

    data class EnchantData(val enchantment: Enchantment, val level: Int, val ignoreLevelRestriction: Boolean)

    data class SerializedString(val string: String, val deserializer: Deserializer) {
        fun deserialize(tagResolver: TagResolver): Component {
            return if (deserializer == Deserializer.LEGACY) {
                LegacyComponentSerializer.legacyAmpersand().deserialize(string)
            } else {
                MiniMessage.miniMessage().deserialize(string, tagResolver)
            }
        }

        fun deserialize(): Component {
            return deserialize(TagResolver.standard())
        }
    }

    constructor(stack: ItemStack) : this() {
        initFromStack(stack, null, Deserializer.MINI_MESSAGE)
    }

    constructor(stack: ItemStack, deserializer: Deserializer) : this() {
        initFromStack(stack, null, deserializer)
    }

    constructor(stack: ItemStack?, def: Material?, deserializer: Deserializer) : this() {
        initFromStack(stack, def, deserializer)
    }

    private fun initFromStack(stack: ItemStack?, def: Material?, deserializer: Deserializer) {
        if (stack == null) {
            if (def != null) this.material = def
            return
        }
        this.material = stack.type
        this.count = stack.amount
        if (stack.itemMeta?.hasCustomModelData() == true) {
            this.modelData = stack.itemMeta!!.customModelData
        }
        this.display = SerializedString(
            MiniMessage.miniMessage().serialize(stack.displayName() ?: Component.empty()),
            deserializer
        )
        if (stack.itemMeta?.lore() != null) {
            this.lore = stack.itemMeta!!.lore()!!.stream()
                .map { line -> MiniMessage.miniMessage().serialize(line) }
                .map { string -> SerializedString(string, deserializer) }
                .collect(Collectors.toList())
        }
    }

    constructor(material: Material, tagResolver: TagResolver) : this() {
        this.material = material
        this.tagResolver = tagResolver
    }

    fun tagResolver(tagResolver: TagResolver): ItemStackBuilder {
        this.tagResolver = tagResolver
        return this
    }

    fun globalDeserializer(deserializer: Deserializer): ItemStackBuilder {
        this.globalDeserializer = deserializer
        return this
    }

    fun appendResolver(append: TagResolver): ItemStackBuilder {
        tagResolver = TagResolver.resolver(tagResolver, append)
        return this
    }

    fun appendResolver(@TagPattern name: String, serializedString: String): ItemStackBuilder {
        tagResolver = TagResolver.resolver(
            tagResolver,
            TagResolver.resolver(name, Tag.inserting(TextUtil.mm(serializedString, true)))
        )
        return this
    }

    fun modelData(modelData: Int): ItemStackBuilder {
        this.modelData = modelData
        return this
    }

    fun display(display: String): ItemStackBuilder {
        return display(display, Deserializer.MINI_MESSAGE)
    }

    fun display(display: Component): ItemStackBuilder {
        this.componentDisplay = display
        return this
    }

    fun display(display: String, deserializer: Deserializer): ItemStackBuilder {
        this.display = SerializedString(display, deserializer)
        return this
    }

    fun lore(lore: List<String>): ItemStackBuilder {
        return lore(lore, Deserializer.MINI_MESSAGE)
    }

    fun componentLore(lore: List<Component>): ItemStackBuilder {
        this.componentLore = lore.toMutableList()
        return this
    }

    fun lore(lore: List<String>, deserializer: Deserializer): ItemStackBuilder {
        this.lore = lore.stream()
            .map { s -> SerializedString(s, deserializer) }
            .collect(Collectors.toList())
        return this
    }

    fun appendLore(lore: List<String>): ItemStackBuilder {
        return appendLore(lore, Deserializer.MINI_MESSAGE)
    }

    fun appendComponentLore(lore: List<Component>?): ItemStackBuilder {
        if (this.componentLore == null) this.componentLore = mutableListOf()
        if (lore == null || lore.isEmpty()) return this
        this.componentLore!!.addAll(lore)
        return this
    }

    fun appendLore(lore: List<String>, deserializer: Deserializer): ItemStackBuilder {
        if (this.lore == null) this.lore = mutableListOf()
        this.lore!!.addAll(
            lore.stream()
                .map { s -> SerializedString(s, deserializer) }
                .toList()
        )
        return this
    }

    fun skull(uniqueId: UUID): ItemStackBuilder {
        this.skullUuid = uniqueId
        return this
    }

    fun flags(vararg flags: ItemFlag): ItemStackBuilder {
        this.flags = flags.toList()
        return this
    }

    fun hideAll(): ItemStackBuilder {
        this.flags = listOf(
            ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_PLACED_ON,
            ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_UNBREAKABLE,
            ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_PLACED_ON,
            ItemFlag.HIDE_DESTROYS, ItemFlag.HIDE_DYE, ItemFlag.HIDE_UNBREAKABLE
        )
        return this
    }

    fun enchant(enchantment: Enchantment, level: Int, ignoreLevelRestriction: Boolean): ItemStackBuilder {
        if (enchantment == null) return this
        enchants.add(EnchantData(enchantment, level, ignoreLevelRestriction))
        return this
    }

    fun build(): ItemStack {
        val stack: ItemStack = if (skullUuid == null) {
            ItemStack(material, count)
        } else {
            HeadUtil.getSkull(skullUuid!!)
        }
        val meta: ItemMeta = stack.itemMeta ?: return stack
        if (modelData != 0) meta.setCustomModelData(modelData)

        if (componentDisplay != null) {
            meta.displayName(TextUtil.strip(componentDisplay))
        } else if (display != null) {
            meta.displayName(TextUtil.strip(display!!.deserialize(tagResolver)))
        }

        if (componentLore != null) {
            meta.lore(componentLore)
        } else {
            meta.lore(
                lore.stream()
                    .map { line -> line.deserialize(tagResolver) }
                    .map { TextUtil.strip(it)!! }
                    .toList()
            )
        }
        if (flags != null) {
            meta.addItemFlags(*flags!!.toTypedArray())
        } else {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ATTRIBUTES)
        }

        enchants.stream()
            .filter { ed -> ed.enchantment != null }
            .forEach { enchantData ->
                meta.addEnchant(
                    enchantData.enchantment,
                    enchantData.level,
                    enchantData.ignoreLevelRestriction
                )
            }

        stack.itemMeta = meta
        return stack
    }

    fun toGuiItemBuilder(): GuiItemBuilder {
        return GuiItemBuilder(build())
    }

    enum class Deserializer {
        MINI_MESSAGE, LEGACY
    }
}

