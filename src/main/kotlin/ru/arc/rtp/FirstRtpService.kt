package ru.arc.rtp

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.util.Logging.error

enum class RtpProvider(val pluginName: String) {
    BETTERRTP("BetterRTP"),
    LEAFRTP("RTP"),
    ;

    companion object {
        fun parse(value: String): RtpProvider? =
            when (value.trim().lowercase()) {
                "betterrtp" -> BETTERRTP
                "leafrtp" -> LEAFRTP
                else -> null
            }
    }
}

sealed interface FirstRtpResult {
    data class Started(
        val provider: RtpProvider,
        val command: String,
    ) : FirstRtpResult

    data class Rejected(val reason: String) : FirstRtpResult
}

/**
 * Starts the provider-specific RTP command and, when requested, correlates the
 * provider completion event with ARC's respawn update.
 */
object FirstRtpService {
    fun start(
        player: Player,
        world: World,
        setRespawn: Boolean,
        persist: Boolean = false,
    ): FirstRtpResult {
        val configuredProvider =
            ConfigManager
                .of(ARC.instance.dataPath, "misc.yml")
                .string("rtp-respawn.provider", "betterrtp")
        val provider =
            RtpProvider.parse(configuredProvider)
                ?: return FirstRtpResult.Rejected(
                    "неизвестный RTP provider '$configuredProvider' (ожидается betterrtp или leafrtp)",
                )

        return start(
            provider = provider,
            player = player,
            world = world,
            setRespawn = setRespawn,
            persist = persist,
            isPluginEnabled = Bukkit.getPluginManager()::isPluginEnabled,
            dispatch = { command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) },
        )
    }

    internal fun start(
        provider: RtpProvider,
        player: Player,
        world: World,
        setRespawn: Boolean,
        persist: Boolean = false,
        isPluginEnabled: (String) -> Boolean,
        dispatch: (String) -> Boolean,
    ): FirstRtpResult {
        if (!isPluginEnabled(provider.pluginName)) {
            return FirstRtpResult.Rejected("плагин ${provider.pluginName} не включён")
        }

        val command = buildCommand(provider, player, world)
        if (setRespawn || persist) {
            RtpRespawnTracker.mark(
                playerName = player.name,
                provider = provider,
                setRespawn = setRespawn,
                persistPlayerId = player.uniqueId.takeIf { persist },
                persistWorldName = world.name.takeIf { persist },
            )
        }

        val dispatched =
            runCatching {
                dispatch(command)
            }.getOrElse { failure ->
                error("Failed to dispatch first RTP command for {}", player.name, failure)
                false
            }

        if (!dispatched) {
            if (setRespawn || persist) RtpRespawnTracker.cancel(player.name)
            return FirstRtpResult.Rejected("RTP provider отклонил команду")
        }

        return FirstRtpResult.Started(provider, command)
    }

    internal fun buildCommand(
        provider: RtpProvider,
        player: Player,
        world: World,
    ): String =
        when (provider) {
            RtpProvider.BETTERRTP ->
                "betterrtp player ${player.name} ${world.name} NODELAY NOCOOLDOWN IGNORECOOLDOWN"

            RtpProvider.LEAFRTP -> {
                val region = leafRegion(player, world)
                buildString {
                    append("rtp player:${player.name}")
                    if (region != null) {
                        append(" region:$region")
                    } else {
                        append(" world:${world.name}")
                    }
                }
            }
        }

    internal fun leafRegion(
        player: Player,
        world: World,
    ): String? =
        when (world.name.lowercase()) {
            "survival" ->
                when {
                    player.hasPermission("rtp.regions.vip") -> "vip"
                    player.hasPermission("rtp.regions.newbie") -> "newbie"
                    else -> "survival"
                }
            "mining" -> "mining"
            "vanilla" -> "vanilla"
            else -> null
        }
}
