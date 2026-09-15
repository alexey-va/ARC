package ru.arc.iteminfo

import org.bukkit.entity.Player

internal interface ItemInfoRenderer : AutoCloseable {
    fun render(player: Player, preferences: ItemInfoPreferences, target: ItemInfoTarget)

    fun follow(player: Player)

    fun clear(player: Player)

    override fun close()
}

internal class ItemInfoController(
    private val preferences: (Player) -> ItemInfoPreferences,
    private val target: (Player) -> ItemInfoTarget?,
    private val renderer: ItemInfoRenderer,
) : AutoCloseable {
    fun update(player: Player) {
        val selected = preferences(player)
        val selectedTarget = if (selected.mode == ItemInfoMode.OFF) null else target(player)
        if (selectedTarget == null) renderer.clear(player)
        else renderer.render(player, selected, selectedTarget)
    }

    fun follow(player: Player) = renderer.follow(player)

    fun reset(player: Player) = renderer.clear(player)

    override fun close() = renderer.close()
}
