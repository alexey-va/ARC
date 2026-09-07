package ru.arc.contracts

import org.bukkit.entity.Player

/** Resource contracts are submitted only from the Origin spawn contract desk. */
object ContractOriginGate {
    const val ORIGIN_WORLD = "rc_origin_spawn"

    fun canSubmit(player: Player): Boolean =
        player.isOnline && player.world.name.equals(ORIGIN_WORLD, ignoreCase = true)
}
