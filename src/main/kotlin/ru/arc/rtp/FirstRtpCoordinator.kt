package ru.arc.rtp

import org.bukkit.World
import org.bukkit.entity.Player

sealed interface FirstRtpRouteResult {
    data object ReturnedToWorldSpawn : FirstRtpRouteResult

    data class Started(val result: FirstRtpResult.Started) : FirstRtpRouteResult

    data class Rejected(val reason: String) : FirstRtpRouteResult
}

/** Owns the first-world/first-network decision independently of transport. */
object FirstRtpCoordinator {
    fun route(
        player: Player,
        world: World,
    ): FirstRtpRouteResult =
        route(
            player = player,
            world = world,
            state = RtpPlayerRegistry.state(player.uniqueId, world.name),
            start = {
                FirstRtpService.start(
                    player = player,
                    world = world,
                    setRespawn = it,
                    persist = true,
                )
            },
            teleport = { player.teleport(world.spawnLocation) },
        )

    internal fun route(
        player: Player,
        world: World,
        state: PlayerRtpState,
        start: (setRespawn: Boolean) -> FirstRtpResult,
        teleport: () -> Boolean,
    ): FirstRtpRouteResult {
        if (state.hasTeleportedToWorld) {
            if (player.world.uid != world.uid && !teleport()) {
                return FirstRtpRouteResult.Rejected("не удалось вернуть игрока на спавн мира")
            }
            return FirstRtpRouteResult.ReturnedToWorldSpawn
        }

        return when (val result = start(!state.hasTeleported)) {
            is FirstRtpResult.Started -> FirstRtpRouteResult.Started(result)
            is FirstRtpResult.Rejected -> FirstRtpRouteResult.Rejected(result.reason)
        }
    }
}
