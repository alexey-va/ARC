package ru.arc.hooks

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.util.Logging.error
import java.lang.reflect.Method

/** Optional bridge to My_Worlds' per-world player-position history. */
class MyWorldsHook(
    plugin: Plugin,
) {
    private val readLastLocation: Method
    private val teleportToExact: Method

    init {
        val classLoader = plugin.javaClass.classLoader
        readLastLocation =
            Class.forName(PLAYER_DATA_CONTROLLER, true, classLoader)
                .getMethod("readLastLocation", Player::class.java, World::class.java)
        teleportToExact =
            Class.forName(WORLD_MANAGER, true, classLoader)
                .getMethod("teleportToExact", Entity::class.java, Location::class.java)
    }

    /** Returns null when My_Worlds has no saved position for this player and world. */
    fun lastLocation(
        player: Player,
        world: World,
    ): Location? =
        runCatching { readLastLocation.invoke(null, player, world) as? Location }
            .onFailure { failure ->
                error(
                    "Could not read My_Worlds last location for {} in {}",
                    player.name,
                    world.name,
                    failure,
                )
            }.getOrNull()

    fun teleportToExact(
        player: Player,
        location: Location,
    ): Boolean =
        runCatching { teleportToExact.invoke(null, player, location) as? Boolean ?: false }
            .onFailure { failure ->
                error(
                    "Could not return {} to a My_Worlds last location in {}",
                    player.name,
                    location.world?.name,
                    failure,
                )
            }.getOrDefault(false)

    private companion object {
        const val PLAYER_DATA_CONTROLLER = "com.bergerkiller.bukkit.mw.MWPlayerDataController"
        const val WORLD_MANAGER = "com.bergerkiller.bukkit.mw.WorldManager"
    }
}
