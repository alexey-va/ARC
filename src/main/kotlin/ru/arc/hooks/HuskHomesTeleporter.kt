package ru.arc.hooks

import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.position.World as HuskWorld
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.common.ServerLocation
import ru.arc.util.Logging.error

/** Shared native teleport entry point; does not install gameplay listeners. */
object HuskHomesTeleporter {
    /** Starts HuskHomes' normal teleport flow, including its cross-server portal handoff. */
    fun teleport(player: Player, destination: ServerLocation): Boolean {
        val serverName = destination.server?.takeIf { it.isNotBlank() } ?: return false
        val worldName = destination.world?.takeIf { it.isNotBlank() } ?: return false
        if (listOf(destination.x, destination.y, destination.z, destination.yaw.toDouble(), destination.pitch.toDouble()).any { !it.isFinite() }) return false
        return runCatching {
            val api = HuskHomesAPI.getInstance()
            val user = api.adaptUser(player)
            val target = Bukkit.getWorld(worldName)?.let { world ->
                val location = Location(world, destination.x, destination.y, destination.z, destination.yaw, destination.pitch)
                api.adaptPosition(location, serverName)
            } ?: Position.at(
                destination.x,
                destination.y,
                destination.z,
                destination.yaw,
                destination.pitch,
                HuskWorld.from(worldName),
                serverName,
            )
            api.teleportBuilder(user).target(target).toTimedTeleport().execute()
            true
        }.onFailure { failure ->
            error("Could not start HuskHomes teleport for {} to {}", player.name, destination, failure)
        }.getOrDefault(false)
    }

}
