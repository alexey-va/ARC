package ru.arc.treasurechests

import org.bukkit.Location
import ru.arc.config.ConfigSection

/** Permanent grapple access in one authored cuboid, independent of treasure-hunt sessions. */
internal data class TreasureHuntGrappleArea(
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val permission: String,
) {
    init {
        require(world.isNotBlank()) { "Grapple area world is required" }
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) { "Grapple area bounds are reversed" }
    }

    fun contains(location: Location): Boolean = location.world?.name == world &&
        location.blockX in minX..maxX && location.blockY in minY..maxY && location.blockZ in minZ..maxZ

    companion object {
        fun load(section: ConfigSection) = TreasureHuntGrappleArea(
            section.string("world"),
            requireNotNull(section.intOrNull("min-x")) { "min-x is required" },
            requireNotNull(section.intOrNull("min-y")) { "min-y is required" },
            requireNotNull(section.intOrNull("min-z")) { "min-z is required" },
            requireNotNull(section.intOrNull("max-x")) { "max-x is required" },
            requireNotNull(section.intOrNull("max-y")) { "max-y is required" },
            requireNotNull(section.intOrNull("max-z")) { "max-z is required" },
            section.string("permission"),
        )
    }
}
