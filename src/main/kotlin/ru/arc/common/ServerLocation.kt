package ru.arc.common

import org.bukkit.Bukkit
import org.bukkit.Location
import ru.arc.ARC
import java.util.Optional

data class ServerLocation(
    var server: String? = null,
    var world: String? = null,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = 0.0,
    var yaw: Float = 0.0f,
    var pitch: Float = 0.0f
) {
    companion object {
        @JvmStatic
        fun of(loc: Location?): ServerLocation {
            if (loc == null) {
                throw IllegalArgumentException("Location cannot be null")
            }
            if (loc.world == null) {
                throw IllegalArgumentException("Location world cannot be null")
            }
            return ServerLocation(
                ARC.serverName,
                loc.world!!.name,
                loc.x,
                loc.y,
                loc.z,
                loc.yaw,
                loc.pitch
            )
        }

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var server: String? = null
        private var world: String? = null
        private var x: Double = 0.0
        private var y: Double = 0.0
        private var z: Double = 0.0
        private var yaw: Float = 0.0f
        private var pitch: Float = 0.0f

        fun server(server: String?): Builder {
            this.server = server
            return this
        }

        fun world(world: String?): Builder {
            this.world = world
            return this
        }

        fun x(x: Double): Builder {
            this.x = x
            return this
        }

        fun y(y: Double): Builder {
            this.y = y
            return this
        }

        fun z(z: Double): Builder {
            this.z = z
            return this
        }

        fun yaw(yaw: Float): Builder {
            this.yaw = yaw
            return this
        }

        fun pitch(pitch: Float): Builder {
            this.pitch = pitch
            return this
        }

        fun build(): ServerLocation {
            return ServerLocation(server, world, x, y, z, yaw, pitch)
        }
    }

    fun toLocation(): Location? {
        val world1 = Bukkit.getWorld(world ?: return null) ?: return null
        return Location(world1, x, y, z, yaw, pitch)
    }

    fun distance(location: Location): Optional<Double> {
        if (ARC.serverName != server) return Optional.empty()
        if (location.world == null) return Optional.empty()
        if (world != location.world!!.name) return Optional.empty()
        val distance = Math.pow(location.x - x, 2.0) +
            Math.pow(location.y - y, 2.0) +
            Math.pow(location.z - z, 2.0)
        return Optional.of(Math.sqrt(distance))
    }

    fun isSameServer(): Boolean {
        if (server == null) return false
        return server == ARC.serverName
    }
}

