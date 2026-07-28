package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.rtp.RtpRespawnTracker

/**
 * /arc respawnonrtp - пометить следующий RTP для установки респавна.
 *
 * После post-teleport события RTP-провайдера ARC установит точку возрождения
 * в месте приземления. Запись истекает через 1 минуту.
 */
object RespawnOnRtpSubCommand : SubCommand {

    override val configKey = "respawnonrtp"
    override val defaultName = "respawnonrtp"
    override val defaultPermission = "arc.rtp-respawn"
    override val defaultDescription = "Установить респавн после следующего RTP игрока (действует 1 мин)"
    override val defaultUsage = "/arc respawnonrtp <player>"

    /**
     * Legacy accessor retained for tests and extensions that used the old API.
     * New code should use [RtpRespawnTracker].
     */
    @Deprecated("Use RtpRespawnTracker")
    val playersForRtp = RtpRespawnTracker.pending

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val playerName = args[0]
        RtpRespawnTracker.mark(playerName)
        sender.sendMessage(CommandConfig.rtpAdded(playerName))

        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> tabCompletePlayers(args[0])
            else -> null
        }
    }
}
