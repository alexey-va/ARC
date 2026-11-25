package ru.arc.drawers

import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector

/** The point is in world coordinates on the front of a horizontally facing barrel. */
internal fun slotAt(block: Block, front: BlockFace, point: Vector, layout: DrawerLayout): Int {
    val right = (point.x - block.x - 0.5) * front.modZ - (point.z - block.z - 0.5) * front.modX
    val bottom = point.y - block.y <= 0.5
    return when (layout) {
        DrawerLayout.ONE -> 0
        DrawerLayout.TWO -> if (bottom) 1 else 0
        DrawerLayout.FOUR -> (if (bottom) 2 else 0) + (if (right >= 0.0) 1 else 0)
    }
}

/** Coordinates on the lid from the front, x right of center and y above the barrel's base. */
internal fun DrawerLayout.slotCenterX(slot: Int): Float =
    if (this != DrawerLayout.FOUR) 0f else if (slot % 2 == 0) -0.25f else 0.25f

internal fun DrawerLayout.slotCenterY(slot: Int): Float = when (this) {
    DrawerLayout.ONE -> 0.5f
    DrawerLayout.TWO -> if (slot == 0) 0.75f else 0.25f
    DrawerLayout.FOUR -> if (slot < 2) 0.75f else 0.25f
}
