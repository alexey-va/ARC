package ru.arc.rtp

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ConfigManager

/** Starts a normal player RTP while preserving provider cooldowns and checks. */
object RegularRtpService {
    fun start(
        player: Player,
        world: World,
    ): FirstRtpResult {
        val configuredProvider =
            ConfigManager
                .of(ARC.instance.dataPath, "modules/misc.yml")
                .string("rtp-respawn.provider", "betterrtp")
        val provider =
            RtpProvider.parse(configuredProvider)
                ?: return FirstRtpResult.Rejected(
                    "неизвестный RTP provider '$configuredProvider' (ожидается betterrtp или leafrtp)",
                )
        if (!Bukkit.getPluginManager().isPluginEnabled(provider.pluginName)) {
            return FirstRtpResult.Rejected("плагин ${provider.pluginName} не включён")
        }
        val command = buildCommand(provider, player, world)
        if (!player.performCommand(command)) {
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
            RtpProvider.BETTERRTP -> "betterrtp world ${world.name}"
            RtpProvider.LEAFRTP ->
                FirstRtpService.leafRegion(player, world)
                    ?.let { "rtp region=$it" }
                    ?: "rtp world=${world.name}"
        }
}
