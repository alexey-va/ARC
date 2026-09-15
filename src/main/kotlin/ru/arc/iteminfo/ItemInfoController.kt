package ru.arc.iteminfo

import org.bukkit.entity.Player

internal interface ItemInfoRenderer : AutoCloseable {
    fun render(player: Player, mode: ItemInfoMode, target: ItemInfoTarget)

    fun follow(player: Player)

    fun clear(player: Player)

    override fun close()
}

internal class ItemInfoController(
    private val mode: (Player) -> ItemInfoMode,
    private val target: (Player) -> ItemInfoTarget?,
    private val renderer: ItemInfoRenderer,
) : AutoCloseable {
    fun update(player: Player) {
        val selectedMode = mode(player)
        val selectedTarget = if (selectedMode == ItemInfoMode.OFF) null else target(player)
        if (selectedTarget == null) renderer.clear(player)
        else renderer.render(player, selectedMode, selectedTarget)
    }

    fun follow(player: Player) = renderer.follow(player)

    fun reset(player: Player) = renderer.clear(player)

    override fun close() = renderer.close()
}
