package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.rtp.BackendRtpRequest
import ru.arc.rtp.NetworkRtpMode
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
        val onlyIfFirstCount = args.count { it.equals(ONLY_IF_FIRST_FLAG, ignoreCase = true) }
        val worldArgs = args.filterNot { it.equals(ONLY_IF_FIRST_FLAG, ignoreCase = true) }
        if (onlyIfFirstCount > 1 || worldArgs.size > 1) {
            reject(sender)
            return true
        }
        val worldName = worldArgs.singleOrNull() ?: DEFAULT_WORLD
        val mode =
            if (onlyIfFirstCount == 1) {
                NetworkRtpMode.FIRST_ENTRY
            } else {
                NetworkRtpMode.REGULAR
            }
        val request =
            runCatching { BackendRtpRequest.create(player.uniqueId, worldName, mode) }
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
    override val defaultUsage =
        "/arc rtp [survival|mining|vanilla] [--only-if-first]"
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
        when (args.size) {
            1 -> (PUBLIC_WORLDS + ONLY_IF_FIRST_FLAG).tabComplete(args[0])
            2 ->
                if (args[0].equals(ONLY_IF_FIRST_FLAG, ignoreCase = true)) {
                    PUBLIC_WORLDS.tabComplete(args[1])
                } else {
                    listOf(ONLY_IF_FIRST_FLAG).tabComplete(args[1])
                }
            else -> null
        }

    private val PUBLIC_WORLDS = listOf("survival", "mining", "vanilla")
}

private const val DEFAULT_WORLD = "survival"
private const val ONLY_IF_FIRST_FLAG = "--only-if-first"
