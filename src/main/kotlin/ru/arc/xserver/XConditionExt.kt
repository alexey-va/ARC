package ru.arc.xserver

import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ArcRedisConfig
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.warn
import ru.arc.xaction.XCondition

object XConditionContext {
    fun currentServerName(): String? {
        if (ARC.plugin == null) return null
        return ArcRedisConfig.get().serverName.takeIf { it.isNotBlank() }?.lowercase()
            ?: ARC.serverName?.lowercase()
    }
}

fun XCondition.matches(player: Player): Boolean {
    val server = serverName
    if (server != null && !server.equals(XConditionContext.currentServerName(), ignoreCase = true)) return false
    val name = playerName
    if (name != null && !player.name.equals(name, ignoreCase = true)) return false
    val uuid = playerUuid
    if (uuid != null && player.uniqueId != uuid) return false
    val perm = permission
    if (perm != null && !player.hasPermission(perm)) return false
    if (!placeholders.isNullOrEmpty()) {
        val papiHook = HookRegistry.papiHook
        if (papiHook == null) {
            warn("PAPI hook is not active!")
            return false
        }
        for ((key, value) in placeholders) {
            val res = papiHook.parse(key, player)
            if (!res.equals(value, ignoreCase = true)) return false
        }
    }
    return true
}
