package ru.arc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/**
 * Infix tag block for [ItemStackDslBuilder.tags] and [ru.arc.gui.ItemBuilder.tags].
 *
 * ```kotlin
 * tags {
 *     "pool_id" to pool.id
 *     "balance" to formatAmount(balance)
 * }
 * ```
 */
class TagsDslBuilder(
    private val registeredTagNames: MutableSet<String>,
) {
    internal val resolvers = mutableListOf<TagResolver>()

    infix fun String.to(value: String) {
        registeredTagNames.add(this)
        resolvers.add(TagResolver.resolver(this, Tag.inserting(TextUtil.mm(value, true))))
    }

    infix fun String.to(value: Component) {
        registeredTagNames.add(this)
        resolvers.add(TagResolver.resolver(this, Tag.inserting(value)))
    }
}
