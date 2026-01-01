package ru.arc.commands.arc.subcommands

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabCompletePlayers
import java.util.concurrent.TimeUnit

/**
 * /arc respawnonrtp - добавить игрока в список RTP-респауна.
 *
 * Игроки в этом списке будут телепортированы в случайную локацию при респауне.
 * Запись истекает через 1 минуту.
 */
object RespawnOnRtpSubCommand : SubCommand {

    override val configKey = "respawnonrtp"
    override val defaultName = "respawnonrtp"
    override val defaultPermission = "arc.rtp-respawn"
    override val defaultDescription = "Добавить игрока в список RTP-респауна"
    override val defaultUsage = "/arc respawnonrtp <player>"

    /** Кеш игроков для RTP при респауне (истекает через 1 минуту) */
    val playersForRtp: Cache<String, Any> = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build()

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val playerName = args[0]
        playersForRtp.put(playerName, Unit)
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
