package ru.arc.homemarkers

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

internal enum class HomeMarkerKind { HOME, WARP }

/** An owner-verified point on this server; the original plugin remains its sole storage owner. */
internal data class HomeMarker(
    val kind: HomeMarkerKind,
    val id: String,
    val ownerId: UUID,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && world.isNotBlank())
        require(listOf(x, y, z).all(Double::isFinite))
    }

    fun location(world: World): Location = Location(world, x, y, z)
}

internal fun isHomeMarkerGesture(sneaking: Boolean, hand: EquipmentSlot?, action: Action): Boolean =
    sneaking && hand == EquipmentSlot.HAND && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)
