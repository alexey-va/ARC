package ru.arc.enchanting

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.Config
import ru.arc.eliteloot.EliteEnchantmentBookPresentationText

internal class EnchantingConfig(private val config: Config) {
    val bookText get() = EliteEnchantmentBookPresentationText(
        namePrefix = config.string("book.name-prefix", "Книга EliteMobs"),
        scopeLore = config.string("book.scope", "Только для снаряжения EliteMobs"),
        compatibilityLabel = config.string("book.compatibility-label", "Подходит для:"),
        compatibilityFallback = config.string(
            "book.compatibility-fallback",
            "совместимого снаряжения EliteMobs",
        ),
        actionLore = config.string("book.action", "Перетащите на предмет — зачаровать"),
    )

    fun text(key: String, vararg resolvers: TagResolver): Component =
        config.component(key, TagResolver.resolver(*resolvers)).decoration(TextDecoration.ITALIC, false)

}
