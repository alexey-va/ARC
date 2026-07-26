package ru.arc.common

import org.bukkit.Bukkit
import org.bukkit.Location
import ru.arc.ARC
import kotlin.math.sqrt

data class ServerLocation(
    val server: String? = null,
    val world: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Float = 0.0f,
    val pitch: Float = 0.0f,
) {
    companion object {
        @JvmStatic
        fun of(loc: Location?): ServerLocation {
            requireNotNull(loc) { "Location cannot be null" }
            val world = requireNotNull(loc.world) { "Location world cannot be null" }
            return ServerLocation(
                server = ARC.serverName,
                world = world.name,
                x = loc.x,
                y = loc.y,
                z = loc.z,
                yaw = loc.yaw,
                pitch = loc.pitch,
            )
        }
    }

    fun toLocation(): Location? {
        val resolvedWorld = Bukkit.getWorld(world ?: return null) ?: return null
        return Location(resolvedWorld, x, y, z, yaw, pitch)
    }

    fun distance(location: Location): Double? {
        if (!isSameServer()) return null
        val locationWorld = location.world ?: return null
        if (world != locationWorld.name) return null
        val dx = location.x - x
        val dy = location.y - y
        val dz = location.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun isSameServer(): Boolean =
        server?.equals(ARC.serverName, ignoreCase = true) == true
}
