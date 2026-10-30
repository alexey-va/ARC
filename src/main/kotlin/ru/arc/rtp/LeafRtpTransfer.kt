package ru.arc.rtp

import io.github.dailystruggle.rtp.api.RTPAPI
import io.github.dailystruggle.rtp.api.RtpTarget
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.util.Logging.error
import ru.arc.util.Logging.warn

/** Provider API is isolated so ARC can still load when the optional plugin is absent. */
internal object LeafRtpTransfer {
    fun start(player: Player, world: World, onFailure: (String) -> Unit): FirstRtpResult {
        val region = FirstRtpService.leafRegion(player, world)
        if (!selectorAllowed(player, world, region)) {
            return FirstRtpResult.Rejected("нет доступа к выбранному направлению")
        }
        val target = if (region == null) RtpTarget.world(world.name) else RtpTarget.region(region)
        val future =
            try {
                RTPAPI.teleport(player.uniqueId, target)
            } catch (failure: Exception) {
                error("Could not start LeafRTP transfer for {}", player.name, failure)
                return FirstRtpResult.Rejected("телепортация временно недоступна")
            }
        ARC.instance.whenCompleteOnMain(future) { result, failure ->
            if (failure != null) {
                error("LeafRTP transfer failed for {}", player.name, failure)
                if (player.isOnline) onFailure("произошла ошибка при обработке запроса")
            } else if (result == null || !result.isSuccess) {
                val reason = result?.reason()?.name ?: "UNKNOWN"
                warn("LeafRTP transfer for {} ended with {}", player.name, reason)
                if (player.isOnline) onFailure(failureMessage(reason))
            }
        }
        return FirstRtpResult.Started(RtpProvider.LEAFRTP, "LeafRTP API")
    }

    internal fun selectorAllowed(player: Player, world: World, region: String?): Boolean =
        if (region == null) {
            player.hasPermission("rtp.world") && player.hasPermission("rtp.worlds.${world.name}")
        } else {
            player.hasPermission("rtp.region")
        }

    internal fun failureMessage(reason: String): String =
        when (reason) {
            "COOLDOWN" -> "ещё действует задержка между телепортациями"
            "ALREADY_TELEPORTING" -> "телепортация уже выполняется"
            "NO_PERMISSION" -> "нет доступа к выбранному направлению"
            "INSUFFICIENT_FUNDS" -> "недостаточно средств для оплаты"
            "NO_SAFE_LOCATION" -> "телепортация не выполнена. Попробуйте ещё раз"
            "RELOADING" -> "телепортация временно недоступна"
            else -> "произошла ошибка при обработке запроса"
        }
}
