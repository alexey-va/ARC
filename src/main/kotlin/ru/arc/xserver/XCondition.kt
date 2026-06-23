package ru.arc.xserver

import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.warn
import java.util.UUID
import java.util.function.Predicate

data class XCondition(
    val playerName: String? = null,
    val playerUuid: UUID? = null,
    val permission: String? = null,
    val serverName: String? = null,
    val placeholders: Map<String, String>? = null
) : Predicate<Player> {

    override fun test(player: Player): Boolean {
        val miscConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
        if (serverName != null && !serverName.equals(miscConfig.string("redis.server-name"), ignoreCase = true)) return false
        if (playerName != null && !player.name.equals(playerName, ignoreCase = true)) return false
        if (playerUuid != null && player.uniqueId != playerUuid) return false
        if (permission != null && !player.hasPermission(permission)) return false
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

    companion object {
        @JvmStatic fun ofPermission(permission: String) = XCondition(permission = permission)
        @JvmStatic fun ofServerName(serverName: String) = XCondition(serverName = serverName)
        @JvmStatic fun ofPlayerName(playerName: String) = XCondition(playerName = playerName)
        @JvmStatic fun ofPlayerUuid(playerUuid: UUID) = XCondition(playerUuid = playerUuid)

        internal fun currentServerName(): String? {
            if (ARC.plugin == null) return null
            val misc = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
            return misc.string("redis.server-name").takeIf { it.isNotBlank() }?.lowercase()
                ?: ARC.serverName?.lowercase()
        }
    }
}
