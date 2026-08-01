package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.rtp.BackendRtpRequest
import ru.arc.util.TextUtil

internal class RtpCommandHandler(
    private val send: (Player, BackendRtpRequest) -> Unit,
    private val reject: (CommandSender) -> Unit,
) {
    fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        val player =
            sender as? Player
                ?: run {
                    reject(sender)
                    return true
                }
        if (args.size != 1) {
            reject(sender)
            return true
        }
        val request =
            runCatching { BackendRtpRequest.create(player.uniqueId, args.single()) }
                .getOrElse {
                    reject(sender)
                    return true
                }
        send(player, request)
        return true
    }
}

object RtpSubCommand : SubCommand {
    override val configKey = "rtp"
    override val defaultName = "rtp"
    override val defaultDescription = "Запустить сетевой RTP"
    override val defaultUsage = "/arc rtp <survival|mining|vanilla>"
    override val defaultPlayerOnly = true

    private val handler =
        RtpCommandHandler(
            send = { player, request ->
                ARC.pluginMessenger?.sendBackendRtpRequest(player, request)
                    ?: player.sendMessage(TextUtil.mm("<red>Сетевой RTP временно недоступен."))
            },
            reject = { sender -> sendUsage(sender) },
        )

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean = handler.execute(sender, args)

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        if (args.size == 1) {
            PUBLIC_WORLDS.tabComplete(args[0])
        } else {
            null
        }

    private val PUBLIC_WORLDS = listOf("survival", "mining", "vanilla")
}
