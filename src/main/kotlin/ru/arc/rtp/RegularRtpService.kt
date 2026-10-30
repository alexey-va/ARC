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
        serverTransfer: Boolean,
        onFailure: (String) -> Unit,
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
        return start(
            provider, player, world, serverTransfer,
            destinationAllowed = {
                Bukkit.getPluginManager().isPluginEnabled("LuckPerms") &&
                    apiAvailable("net.luckperms.api.LuckPerms") &&
                    DestinationRtpPermission.canUse(player, world)
            },
            startLeafTransfer = {
                if (apiAvailable("io.github.dailystruggle.rtp.api.RTPAPI")) {
                    LeafRtpTransfer.start(player, world, onFailure)
                } else {
                    FirstRtpResult.Rejected("телепортация временно недоступна")
                }
            },
            dispatch = player::performCommand,
        )
    }

    internal fun start(
        provider: RtpProvider,
        player: Player,
        world: World,
        serverTransfer: Boolean,
        destinationAllowed: () -> Boolean,
        startLeafTransfer: () -> FirstRtpResult,
        dispatch: (String) -> Boolean,
    ): FirstRtpResult {
        if (provider == RtpProvider.LEAFRTP && serverTransfer && !player.hasPermission("rtp.use")) {
            if (!destinationAllowed()) return FirstRtpResult.Rejected("нет доступа к выбранному направлению")
            return startLeafTransfer()
        }
        val command = buildCommand(provider, player, world)
        if (!dispatch(command)) {
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

    private fun apiAvailable(name: String): Boolean =
        try {
            Class.forName(name, false, javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
