package ru.arc.hooks.viaversion

import com.viaversion.viaversion.api.Via
import org.bukkit.entity.Player

class ViaVersionHook {
    fun getPlayerVersion(player: Player): Int {
        val connection = Via.getManager().connectionManager.getConnectedClient(player.uniqueId)
        return connection?.protocolInfo?.protocolVersion ?: -1
    }
}
