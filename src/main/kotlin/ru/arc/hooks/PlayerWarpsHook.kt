package ru.arc.hooks

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import org.bukkit.entity.Player
import ru.arc.util.Logging.info

class PlayerWarpsHook {
    fun warpExists(name: String, player: Player): Boolean {
        val warp = PlayerWarpsAPI.getInstance().getPlayerWarp(name, player)
        info("Warp name {} {}", name, warp)
        return warp != null
    }
}
