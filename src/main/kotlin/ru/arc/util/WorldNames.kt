package ru.arc.util

import net.kyori.adventure.text.Component

/** The operator-owned aliases in modules/misc.yml also name remote worlds in menus. */
object WorldNames {
    fun configured(world: String, aliases: Map<String, String>): String? =
        aliases[world] ?: aliases.entries.firstOrNull { (pattern, _) ->
            '*' in pattern && world.matches(Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }))
        }?.value

    fun display(world: String, aliases: Map<String, String>): Component =
        TextUtil.legacy(configured(world, aliases) ?: "&7Обычный мир")
}
