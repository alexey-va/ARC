package ru.arc.enchanting

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText

internal class EnchantingConfig(private val config: Config) {
    val bookText get() = EliteEnchantmentBookPresentationText(
        namePrefix = config.string("book.name-prefix", "Книга EliteMobs"),
        scopeLore = config.string("book.scope", "Только для снаряжения EliteMobs"),
        actionLore = config.string("book.action", "Перетащите книгу на предмет в инвентаре"),
        previewHint = config.string("book.preview-hint", "Итог, цена и шансы — перед применением"),
    )

    fun text(key: String, vararg resolvers: TagResolver): Component =
        config.component(key, TagResolver.resolver(*resolvers)).decoration(TextDecoration.ITALIC, false)

    fun lines(key: String, vararg resolvers: TagResolver): List<Component> =
        config.stringList(key).map { MiniMessage.miniMessage().deserialize(it, TagResolver.resolver(*resolvers)) }
            .map { it.decoration(TextDecoration.ITALIC, false) }

    fun button(material: Material, key: String, vararg resolvers: TagResolver) = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(text("$key.name", *resolvers))
            meta.lore(lines("$key.lore", *resolvers))
        }
    }
}
