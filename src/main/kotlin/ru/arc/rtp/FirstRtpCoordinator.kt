package ru.arc.rtp

import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry

sealed interface FirstRtpRouteResult {
    data object ReturnedToWorld : FirstRtpRouteResult

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
            returnToWorld = {
                val hook = HookRegistry.myWorldsHook
                val previousLocation = hook?.lastLocation(player, world)
                if (previousLocation == null) {
                    player.teleport(world.spawnLocation)
                } else {
                    hook.teleportToExact(player, previousLocation)
                }
            },
        )

    internal fun route(
        player: Player,
        world: World,
        state: PlayerRtpState,
        start: (setRespawn: Boolean) -> FirstRtpResult,
        returnToWorld: () -> Boolean,
    ): FirstRtpRouteResult {
        if (state.hasTeleportedToWorld) {
            if (player.world.uid != world.uid && !returnToWorld()) {
                return FirstRtpRouteResult.Rejected("не удалось вернуть игрока в мир")
            }
            return FirstRtpRouteResult.ReturnedToWorld
        }

        return when (val result = start(!state.hasTeleported)) {
            is FirstRtpResult.Started -> FirstRtpRouteResult.Started(result)
            is FirstRtpResult.Rejected -> FirstRtpRouteResult.Rejected(result.reason)
        }
    }
}
