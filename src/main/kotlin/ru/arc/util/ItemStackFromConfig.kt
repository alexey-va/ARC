package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import ru.arc.configs.Config

/**
 * Config overlay for [ItemStackDslBuilder]: code defaults are injected as a nested item map when missing,
 * then applied back (lazy config fill → editable buttons without hand-writing YAML).
 *
 * ```kotlin
 * val config = StockConfig.config()  // or BoardConfig.config(), jobs Config, gui Config, …
 * itemStack(Material.STICK) {
 *     display("<gold>Баланс")
 *     lore(listOf("<gray>Баланс: <balance>"))
 *     modelData(11138)
 *     tags { "balance" to formatAmount(balance) }
 *     fromConfig(config, "locale.profile-menu.balance")
 * }
 * ```
 *
 * YAML at [path] uses unified nested item format (`display`, `lore`, `customModelData`, `material`).
 * On first inject, a block comment lists available MiniMessage tags from [tag]/[tags] and display/lore text.
 */
interface ItemConfigTarget {
    fun applyDisplay(text: String)

    fun applyLore(lines: List<String>)

    fun applyModelData(data: Int)

    fun applyMaterial(material: Material)

    /** Code default set before [fromConfig], or null to skip this field. */
    fun peekDisplay(): String? = null

    fun peekLore(): List<String>? = null

    fun peekModelData(): Int? = null

    fun peekMaterial(): Material? = null

    /** Tags registered via [ItemStackDslBuilder.tag] / [tags] before [fromConfig]. */
    fun peekRegisteredTags(): Collection<String> = emptyList()
}

private class ItemStackDslConfigTarget(
    private val builder: ItemStackDslBuilder,
) : ItemConfigTarget {
    override fun peekDisplay(): String? = builder.peekDisplayDefault()

    override fun peekLore(): List<String>? = builder.peekLoreDefault()

    override fun peekModelData(): Int? = builder.peekModelDataDefault()

    override fun peekMaterial(): Material? = builder.peekMaterialDefault()

    override fun peekRegisteredTags(): Collection<String> = builder.peekRegisteredTagNames()

    override fun applyDisplay(text: String) = builder.display(text)

    override fun applyLore(lines: List<String>) = builder.lore(lines)

    override fun applyModelData(data: Int) = builder.modelData(data)

    override fun applyMaterial(material: Material) = builder.material(material)
}

/**
 * Inject code defaults into [config] (if missing) and apply resolved values.
 * Call after setting fallback display/lore/modelData in the DSL block.
 */
fun ItemStackDslBuilder.fromConfig(
    config: Config,
    path: String,
) {
    applyItemFromConfig(config, path, ItemStackDslConfigTarget(this))
}

/** Resolved MiniMessage display/lore for temporary GUI feedback (e.g. [ru.arc.util.GuiUtils.temporaryChange]). */
fun Config.itemComponents(
    path: String,
    resolver: TagResolver? = null,
): Pair<Component?, List<Component>> {
    val spec = ConfigItemSpec.readFromConfig(this, path)
    val display =
        spec?.display?.let { text ->
            if (resolver != null) TextUtil.mm(text, resolver) else TextUtil.mm(text)
        }
    val lore =
        spec?.lore?.map { line ->
            if (resolver != null) TextUtil.mm(line, resolver) else TextUtil.mm(line)
        } ?: emptyList()
    return display to lore
}

/** Lore lines from a nested item block, or empty if missing. */
fun Config.itemLore(path: String): List<String> = ConfigItemSpec.readFromConfig(this, path)?.lore ?: emptyList()

/** Apply resolved [ConfigItemSpec] fields to an item DSL builder (used by ops JSON and tests). */
fun ItemStackDslBuilder.applySpec(
    spec: ConfigItemSpec,
    applyMaterial: Boolean = true,
) {
    if (applyMaterial) {
        spec.material?.let { material(it) }
    }
    spec.display?.let { display(it) }
    spec.lore?.let { lore(it) }
    spec.modelData?.takeIf { it != 0 }?.let { modelData(it) }
}
